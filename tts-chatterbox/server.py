"""
ChatterBox Turbo (Resemble AI) expressive text-to-speech service, exposing the
same "POST JSON -> WAV bytes" contract as the Piper service (tts/server.py)
and the IndicF5 service (tts-indic/server.py), so ProviderGateway can treat
it as just another swappable TextToSpeechProvider.

Why this is a separate container rather than another engine inside tts/:
same reasoning as tts-indic - a different, heavier dependency stack (PyTorch +
chatterbox-tts) that would bloat the default image for everyone, including
people who never touch expressive narration. Off unless you opt in via the
`chatterbox` compose profile.

What this gives you that Piper/Edge do not:
  - Native paralinguistic tags in the narration text itself - [laugh],
    [chuckle], [cough], [sigh] etc. (Turbo's own feature, not something this
    server parses or invents - the tag is just part of the text sent to
    model.generate()). Whatever tag vocabulary the installed chatterbox-tts
    version actually supports is what you get; verify against
    https://github.com/resemble-ai/chatterbox before relying on a specific tag.
  - Voice cloning from a short reference clip, same idea as IndicF5's
    prompt-based approach.

What this does NOT do (out of scope for this first pass - see project chat
history for the fuller "Voice Director" proposal this is step one of):
  - No automatic insertion of tags/pauses from scene emotion or punctuation.
    The narration text arrives here exactly as StoryEngineService wrote it;
    if you want [pause:500ms]-style tags in the output, the story/narration
    prompt has to actually write them into the text - this server is a dumb
    pass-through, on purpose, so that piece can be built and tested
    independently later.
  - No multilingual support - Turbo is English-only (see ChatterboxMultilingual
    for that, a different model/dependency footprint entirely).

UNVERIFIED like every other from-scratch integration in this project: written
against chatterbox-tts's documented API (pypi.org/project/chatterbox-tts,
github.com/resemble-ai/chatterbox), never run against a live GPU/CPU to
confirm generate()'s exact kwargs, since no such environment was available
while building this. Test one synthesis call before trusting it for a real
episode - see the try/except fallbacks below for the parts most likely to
need adjusting (speed control in particular; Turbo's generate() signature in
the docs only shows text + audio_prompt_path, not a speed knob).
"""
import io
import logging
import os
import threading
import time
import urllib.request
import json

import numpy as np
import soundfile as sf
from flask import Flask, Response, jsonify, request

app = Flask(__name__)
logging.basicConfig(level=logging.INFO)
log = logging.getLogger("chatterbox-server")

DEVICE = os.environ.get("CHATTERBOX_DEVICE", "auto")
if DEVICE == "auto":
    try:
        import torch
        DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:
        DEVICE = "cpu"
VOICES_DIR = os.environ.get("CHATTERBOX_VOICES_DIR", "/voices")
# Shared with the backend container (VoiceProfileService writes here) - a
# voice recorded/uploaded through the Voice Library UI lands here under its
# VoiceProfile id, separate from the manually-placed clips in VOICES_DIR.
VOICE_PROFILES_DIR = os.environ.get("CHATTERBOX_VOICE_PROFILES_DIR", "/voice-profiles")
DEFAULT_VOICE = os.environ.get("CHATTERBOX_DEFAULT_VOICE", "narrator-male")
BOOTSTRAP_URL = os.environ.get("CHATTERBOX_REFERENCE_BOOTSTRAP_URL", "http://tts:5002")
BOOTSTRAP_VOICES = os.environ.get("CHATTERBOX_BOOTSTRAP_VOICES", "narrator-male:en_US-ryan-high,narrator-female:en_US-amy-medium,narrator-in-male:edge:en-IN-PrabhatNeural,narrator-in-female:edge:en-IN-NeerjaNeural")

_models = {}
_model_lock = threading.Lock()
_generation_lock = threading.Lock()

MODEL_TYPE = os.environ.get("CHATTERBOX_MODEL_TYPE", "standard").strip().lower()
# The backend calls /api/unload before Ollama / ComfyUI / IndicF5 use the GPU; as a safety net the
# model is also dropped after this many idle seconds (0 = never). Reload from the local HF cache ~5-15 s.
IDLE_UNLOAD_SECONDS = int(os.environ.get("CHATTERBOX_IDLE_UNLOAD_SECONDS", "300"))
_last_used = 0.0


def _free_cuda():
    try:
        import gc
        import torch
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
            torch.cuda.ipc_collect()
    except Exception:  # noqa: BLE001
        pass


def unload_models(reason: str) -> list:
    """Drop every loaded model and return its VRAM. Waits for a running generation to finish."""
    with _generation_lock, _model_lock:
        names = sorted(_models.keys())
        _models.clear()
    _free_cuda()
    if names:
        log.info("Chatterbox unloaded %s (%s)", names, reason)
    return names


def _idle_watcher():
    import time
    while True:
        time.sleep(30)
        if IDLE_UNLOAD_SECONDS > 0 and _models and _last_used and time.time() - _last_used > IDLE_UNLOAD_SECONDS:
            unload_models("idle")


def _is_oom(exc: Exception) -> bool:
    m = str(exc).lower()
    return "out of memory" in m or "cuda error" in m or "cublas" in m
DEFAULT_LANGUAGE = os.environ.get("CHATTERBOX_DEFAULT_LANGUAGE", "en").strip().lower()


# The app stores the story language as free text ("English", "Hinglish", "Hindi"...),
# but Chatterbox needs an ISO code and only knows these 23 languages. Passing
# "english" straight through made every request fail inside generate().
MTL_LANGUAGES = {"ar", "da", "de", "el", "en", "es", "fi", "fr", "he", "hi", "it", "ja", "ko",
                 "ms", "nl", "no", "pl", "pt", "ru", "sv", "sw", "tr", "zh"}
LANGUAGE_ALIASES = {
    "english": "en", "eng": "en", "en-us": "en", "en-gb": "en", "en-in": "en",
    "hindi": "hi", "hinglish": "hi", "hi-in": "hi", "hindi (roman)": "hi",
    "spanish": "es", "french": "fr", "german": "de", "italian": "it", "portuguese": "pt",
    "russian": "ru", "japanese": "ja", "korean": "ko", "chinese": "zh", "mandarin": "zh",
    "arabic": "ar", "dutch": "nl", "turkish": "tr", "polish": "pl", "swedish": "sv",
    "danish": "da", "finnish": "fi", "greek": "el", "hebrew": "he", "malay": "ms",
    "norwegian": "no", "swahili": "sw",
}


def normalize_language(value: str) -> str:
    """'English' -> 'en', 'Hinglish' -> 'hi', 'pt-BR' -> 'pt'. Unknown text is returned
    lower-cased so the caller can reject it with a clear message."""
    v = (value or "").strip().lower().replace("_", "-")
    if v in LANGUAGE_ALIASES:
        return LANGUAGE_ALIASES[v]
    return v.split("-")[0] if v.split("-")[0] in MTL_LANGUAGES else v


def get_model(language: str = "en"):
    """Load the real current Chatterbox model once and cache it.

    standard = high-quality English/reference-voice model; multilingual =
    Chatterbox Multilingual V3 for supported non-English languages. Turbo is
    intentionally not the default because Voice Lab and scene narration need
    the standard model's expressive conditioning and stable reference cloning.
    """
    key = "multilingual" if MODEL_TYPE == "multilingual" or language.lower() != "en" else "standard"
    if key not in _models:
        with _model_lock:
            if key not in _models:
                log.info("Loading Chatterbox %s model on device=%s...", key, DEVICE)
                if key == "multilingual":
                    from chatterbox.mtl_tts import ChatterboxMultilingualTTS
                    _models[key] = ChatterboxMultilingualTTS.from_pretrained(device=DEVICE, t3_model="v3")
                else:
                    from chatterbox.tts import ChatterboxTTS
                    _models[key] = ChatterboxTTS.from_pretrained(device=DEVICE)
                log.info("Chatterbox %s model loaded.", key)
    return _models[key]

def voice_reference_path(voice: str):
    """A voice is just a reference clip. Checks the Voice Library's shared
    volume first (VOICE_PROFILES_DIR - VoiceProfile.id-named files written by
    the backend), then the manually-placed VOICES_DIR clips, so a voice
    created through the UI and one dropped in by hand both just work. No
    transcript needed (unlike IndicF5's F5 architecture) - Turbo's zero-shot
    cloning only needs the audio. Returns None if neither has it, so the
    caller can decide how to handle "no reference available" rather than
    this function silently guessing."""
    profile_path = os.path.join(VOICE_PROFILES_DIR, f"{voice}.wav")
    if os.path.isfile(profile_path):
        return profile_path
    manual_path = os.path.join(VOICES_DIR, f"{voice}.wav")
    return manual_path if os.path.isfile(manual_path) else None



def bootstrap_reference_voices():
    """Create safe, local reference clips from the project's own open-source Piper voices.

    This prevents the previous failure mode where Chatterbox had no narrator.wav and
    the backend silently fell back to the default female Edge/Piper voice. The generated
    reference is only a conditioning clip; users can replace it with their own 10-second
    recording from the Voice Library at any time.
    """
    entries = []
    for item in BOOTSTRAP_VOICES.split(","):
        if ":" not in item:
            continue
        name, source = item.split(":", 1)
        entries.append((name.strip(), source.strip()))
    for name, source in entries:
        target = os.path.join(VOICES_DIR, f"{name}.wav")
        marker = target + ".ref2"  # reference text v2: v1 began with "Hello." and the cloned voice echoed it
        if os.path.isfile(target) and os.path.getsize(target) > 10000 and (os.path.isfile(marker) or not name.startswith(("narrator-", "tutor-"))):
            continue
        for attempt in range(12):
            try:
                payload = json.dumps({
                    "text": "Every big idea starts small, so we will take it one clear step at a time, keep it practical, and enjoy it along the way.",
                    "voice": source, "speed": 0.98, "pitch": 1.0, "language": "en"
                }).encode("utf-8")
                req = urllib.request.Request(BOOTSTRAP_URL + "/api/tts", data=payload, headers={"Content-Type": "application/json"})
                with urllib.request.urlopen(req, timeout=120) as resp:
                    wav = resp.read()
                if len(wav) > 10000:
                    os.makedirs(VOICES_DIR, exist_ok=True)
                    tmp = target + ".part"
                    with open(tmp, "wb") as f: f.write(wav)
                    os.replace(tmp, target)
                    open(marker, "w").write("2")
                    log.info("Bootstrapped Chatterbox reference %s from %s", name, source)
                    break
            except Exception as exc:
                if attempt == 11:
                    log.warning("Could not bootstrap Chatterbox reference %s: %s", name, exc)
                else:
                    time.sleep(5)


def list_voices():
    """Merges both directories - this endpoint is a low-level "what files
    exist" view; the real source of truth for names/metadata is the
    backend's own /api/voice-profiles (DB-backed), which the Voice Library UI
    actually calls. This stays useful for direct debugging of the sidecar."""
    names = set()
    for d in (VOICE_PROFILES_DIR, VOICES_DIR):
        if os.path.isdir(d):
            names.update(name[:-4] for name in os.listdir(d) if name.lower().endswith(".wav"))
    return sorted(names)


def audio_rms_db(wav_bytes: bytes) -> float:
    """Return RMS level in dBFS; used to reject silent/invalid synthesis."""
    try:
        data, _ = sf.read(io.BytesIO(wav_bytes), dtype="float32")
        if data.size == 0:
            return -120.0
        rms = float(np.sqrt(np.mean(np.square(data))))
        return 20.0 * np.log10(max(rms, 1e-8))
    except Exception:
        return -120.0


def apply_tempo(wav_bytes: bytes, speed: float) -> bytes:
    """
    Pitch-preserving speed change (speed 0.86 = 14 % slower, 1.1 = 10 % faster). The lessons ask for it (calm toddler pace, brisk reels),
    but this engine has no speed control of its own, so without this step the request was silently ignored.
    rubberband keeps the voice's formants (it still sounds like the same person); atempo is the fallback when ffmpeg lacks rubberband.
    The unchanged audio is returned on any failure: a voice at the wrong speed is better than no voice.
    """
    import subprocess
    try:
        speed = float(speed)
    except (TypeError, ValueError):
        return wav_bytes
    speed = max(0.6, min(1.6, speed))
    if abs(speed - 1.0) < 0.015:
        return wav_bytes
    for flt in ("rubberband=tempo=%.4f:formant=preserved:pitchq=quality:transients=smooth" % speed, "atempo=%.4f" % speed):
        try:
            p = subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-i", "pipe:0", "-af", flt, "-f", "wav", "pipe:1"],
                               input=wav_bytes, capture_output=True, timeout=90)
            if p.returncode == 0 and len(p.stdout) > 1000:
                return p.stdout
        except Exception:  # noqa: BLE001
            pass
    return wav_bytes


def shift_pitch(wav_bytes: bytes, pitch: float) -> bytes:
    """Same approach as tts/server.py's shift_pitch: post-process with a
    resample trick rather than a model-native pitch parameter, since
    Turbo's generate() (per its documented signature) does not expose one.
    Kept as a near-identical copy rather than a shared import, to keep this
    container's dependency list independent of the Piper container's."""
    if abs(pitch - 1.0) < 0.01:
        return wav_bytes
    data, sr = sf.read(io.BytesIO(wav_bytes), dtype="float32")
    resampled_len = max(1, int(len(data) / pitch))
    indices = np.linspace(0, len(data) - 1, resampled_len)
    shifted = np.interp(indices, np.arange(len(data)), data if data.ndim == 1 else data[:, 0])
    out = io.BytesIO()
    sf.write(out, shifted, sr, format="WAV")
    return out.getvalue()


@app.get("/health")
def health():
    return jsonify({"status": "ok", "device": DEVICE, "modelType": MODEL_TYPE, "loadedModels": sorted(_models.keys())})


@app.get("/api/voices")
def voices():
    names = list_voices()
    return jsonify({
        "voices": [{"id": v, "name": v, "engine": "chatterbox", "installed": True} for v in names],
        "defaultVoice": DEFAULT_VOICE if DEFAULT_VOICE in names else (names[0] if names else ""),
    })


def _expected_seconds(text: str) -> float:
    """Generous upper bound for how long a line can take to say (any script, any pace)."""
    return 2.5 + len(text) / 9.0


def _too_long(wav, sr, text) -> bool:
    return wav.shape[-1] / sr > _expected_seconds(text) * 1.6


def _cap_length(wav, sr, text):
    limit = int(_expected_seconds(text) * 1.6 * sr)
    return wav[..., :limit] if wav.shape[-1] > limit else wav


def _squeeze_silence(wav, sr, max_gap=0.6, keep=0.35, floor_db=-45.0):
    """Shorten every pause longer than max_gap seconds to keep seconds, and trim trailing silence."""
    try:
        import torch
        x = wav if wav.dim() == 2 else wav.unsqueeze(0)
        mono = x.abs().mean(0)
        hop = int(sr * 0.02)
        n = mono.shape[-1] // hop
        if n == 0:
            return wav
        frames = mono[: n * hop].reshape(n, hop).pow(2).mean(1).sqrt()
        loud = (20 * torch.log10(frames + 1e-9)) > floor_db
        keep_mask = torch.ones(n, dtype=torch.bool)
        run_start, max_frames, keep_frames = None, int(max_gap / 0.02), int(keep / 0.02)
        for i in range(n + 1):
            silent = i < n and not bool(loud[i])
            if silent and run_start is None:
                run_start = i
            elif not silent and run_start is not None:
                length = i - run_start
                tail = i == n
                if tail:
                    keep_mask[run_start + min(length, keep_frames):] = False
                elif length > max_frames:
                    keep_mask[run_start + keep_frames // 2: i - keep_frames // 2] = False
                run_start = None
        idx = torch.repeat_interleave(keep_mask, hop)
        out = x[..., : n * hop][..., idx]
        out = torch.cat([out, x[..., n * hop:]], dim=-1) if keep_mask[-1] else out
        return out if wav.dim() == 2 else out.squeeze(0)
    except Exception:  # noqa: BLE001 - never lose a line over the guard
        return wav


@app.post("/api/unload")
def api_unload():
    return jsonify({"unloaded": unload_models("requested by backend"), "device": DEVICE})


@app.post("/api/tts")
def synthesize():
    body = request.get_json(force=True, silent=True) or {}
    text = (body.get("text") or "").strip()
    if not text:
        return jsonify({"error": "text is required"}), 400
    if len(text) > 1200:
        text = text[:1200]

    voice = body.get("voice") or DEFAULT_VOICE
    language = normalize_language(str(body.get("language") or DEFAULT_LANGUAGE))
    if language not in MTL_LANGUAGES:
        return jsonify({"error": f"Chatterbox does not support language '{language}' "
                                 f"(supported: {', '.join(sorted(MTL_LANGUAGES))})."}), 400
    speed = float(body.get("speed") or 1.0)
    pitch = float(body.get("pitch") or 1.0)
    emotion = str(body.get("emotion") or "neutral").strip().lower()
    intensity = float(body.get("emotionIntensity") or 0.5)
    intensity = max(0.0, min(1.0, intensity))
    event = str(body.get("paralinguisticEvent") or "").strip().lower()

    reference = voice_reference_path(voice)
    if reference is None:
        return jsonify({"error": f"No reference audio found for voice '{voice}'. Expected {VOICE_PROFILES_DIR}/{voice}.wav or {VOICES_DIR}/{voice}.wav."}), 400

    # Chatterbox uses reference speech as the speaker/style prompt. Scene
    # emotion is mapped to exaggeration; supported paralinguistic events are
    # added explicitly rather than pretending Chatterbox has a generic
    # emotion/instruction parameter.
    if event in {"laugh", "chuckle", "gasp", "sigh", "cough", "groan", "sniff", "shush", "clear throat"}:
        if f"[{event}]" not in text.lower():
            text = f"{text} [{event}]"
    if emotion in {"excited", "happy", "joyful", "surprised", "angry", "fearful", "sad", "dramatic"}:
        intensity = max(intensity, 0.65 if emotion in {"excited", "dramatic", "angry", "surprised"} else 0.55)

    try:
        model = get_model(language)
    except Exception as exc:
        if _is_oom(exc):
            # another engine still holds the card: free our cache and try once more
            _free_cuda()
            try:
                model = get_model(language)
            except Exception as exc2:
                log.exception("Chatterbox could not load - GPU out of memory")
                return jsonify({"error": f"Chatterbox: CUDA out of memory while loading the model ({exc2}). "
                                         "Another GPU engine (Ollama, ComfyUI, IndicF5) is holding the card."}), 503
        else:
            log.exception("Could not load Chatterbox")
            return jsonify({"error": f"Chatterbox model failed to load: {exc}"}), 500

    try:
        import torchaudio as ta
        kwargs = {
            "audio_prompt_path": reference,
            "exaggeration": 0.25 + (intensity * 1.0),
            "temperature": 0.65 if intensity < 0.7 else 0.8,
            "cfg_weight": 0.5,
        }
        if language != "en":
            # the multilingual model drifts and runs on with strong exaggeration / guidance on non-English text
            kwargs["exaggeration"] = min(kwargs["exaggeration"], 0.55)
            kwargs["cfg_weight"] = 0.3
            kwargs["temperature"] = min(kwargs["temperature"], 0.7)
        if language != "en" or MODEL_TYPE == "multilingual":
            kwargs["language_id"] = language

        log.info("Generating voice=%s model=%s language=%s emotion=%s intensity=%.2f reference=%s",
                 voice, type(model).__name__, language, emotion, intensity, reference)
        # Chatterbox mutates model conditionals during generate(); serialize
        # requests so concurrent scenes cannot corrupt each other's voice.
        global _last_used
        with _generation_lock:
            try:
                wav_tensor = model.generate(text, **kwargs)
            except Exception as exc:
                if not _is_oom(exc):
                    raise
                log.warning("Chatterbox CUDA out of memory - clearing cache and retrying once")
                _free_cuda()
                wav_tensor = model.generate(text, **kwargs)
            # Runaway guard: on text it struggles with, the model keeps generating near-silence up to its length limit
            # (~30 s of dead air per line in a lesson). Cut long pauses; if it is still far longer than the text can
            # take to say, try once more with calmer settings and keep the shorter result.
            wav_tensor = _squeeze_silence(wav_tensor, model.sr)
            if _too_long(wav_tensor, model.sr, text):
                log.warning("Chatterbox runaway on %d chars (%.1f s) - retrying with calmer settings", len(text), wav_tensor.shape[-1] / model.sr)
                calm = dict(kwargs, temperature=0.5, exaggeration=min(kwargs["exaggeration"], 0.4), cfg_weight=0.3)
                retry = _squeeze_silence(model.generate(text, **calm), model.sr)
                if retry.shape[-1] < wav_tensor.shape[-1]:
                    wav_tensor = retry
                wav_tensor = _cap_length(wav_tensor, model.sr, text)
            buf = io.BytesIO()
            ta.save(buf, wav_tensor, model.sr, format="wav")
            wav_bytes = buf.getvalue()
            import time
            _last_used = time.time()
        _free_cuda()   # return activations to the pool other engines draw from
    except Exception as exc:
        log.exception("Chatterbox synthesis failed for voice=%s", voice)
        code = 503 if _is_oom(exc) else 500
        msg = (f"Chatterbox: CUDA out of memory for '{voice}' ({exc}). Another GPU engine is holding the card."
               if _is_oom(exc) else f"Voice generation failed for '{voice}': {exc}")
        return jsonify({"error": msg}), code

    if len(wav_bytes) < 1000:
        return jsonify({"error": "Chatterbox returned an invalid/empty WAV."}), 502
    rms_db = audio_rms_db(wav_bytes)
    if rms_db < -48.0:
        log.error("Rejecting effectively silent Chatterbox output: %.1f dBFS", rms_db)
        return jsonify({"error": f"Chatterbox generated effectively silent audio ({rms_db:.1f} dBFS). The voice reference/model generation failed; no silent sample was returned."}), 502
    wav_bytes = apply_tempo(wav_bytes, speed)       # the request's speed was parsed above but never used before
    if abs(pitch - 1.0) > 0.01:
        wav_bytes = shift_pitch(wav_bytes, pitch)
    return Response(wav_bytes, mimetype="audio/wav", headers={"Content-Disposition": 'inline; filename="voice.wav"'})


if __name__ == "__main__":
    os.makedirs(VOICES_DIR, exist_ok=True)
    threading.Thread(target=bootstrap_reference_voices, daemon=True).start()
    threading.Thread(target=_idle_watcher, daemon=True).start()
    app.run(host="0.0.0.0", port=5004)
