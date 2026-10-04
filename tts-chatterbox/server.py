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
DEFAULT_VOICE = os.environ.get("CHATTERBOX_DEFAULT_VOICE", "narrator")

_models = {}
_model_lock = threading.Lock()
_generation_lock = threading.Lock()

MODEL_TYPE = os.environ.get("CHATTERBOX_MODEL_TYPE", "standard").strip().lower()
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
        if language != "en" or MODEL_TYPE == "multilingual":
            kwargs["language_id"] = language

        log.info("Generating voice=%s model=%s language=%s emotion=%s intensity=%.2f reference=%s",
                 voice, type(model).__name__, language, emotion, intensity, reference)
        # Chatterbox mutates model conditionals during generate(); serialize
        # requests so concurrent scenes cannot corrupt each other's voice.
        with _generation_lock:
            wav_tensor = model.generate(text, **kwargs)
            buf = io.BytesIO()
            ta.save(buf, wav_tensor, model.sr, format="wav")
            wav_bytes = buf.getvalue()
    except Exception as exc:
        log.exception("Chatterbox synthesis failed for voice=%s", voice)
        return jsonify({"error": f"Voice generation failed for '{voice}': {exc}"}), 500

    if len(wav_bytes) < 1000:
        return jsonify({"error": "Chatterbox returned an invalid/empty WAV."}), 502
    rms_db = audio_rms_db(wav_bytes)
    if rms_db < -48.0:
        log.error("Rejecting effectively silent Chatterbox output: %.1f dBFS", rms_db)
        return jsonify({"error": f"Chatterbox generated effectively silent audio ({rms_db:.1f} dBFS). The voice reference/model generation failed; no silent sample was returned."}), 502
    if abs(pitch - 1.0) > 0.01:
        wav_bytes = shift_pitch(wav_bytes, pitch)
    return Response(wav_bytes, mimetype="audio/wav", headers={"Content-Disposition": 'inline; filename="voice.wav"'})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5004)
