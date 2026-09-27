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

DEVICE = os.environ.get("CHATTERBOX_DEVICE", "cpu")
VOICES_DIR = os.environ.get("CHATTERBOX_VOICES_DIR", "/voices")
# Shared with the backend container (VoiceProfileService writes here) - a
# voice recorded/uploaded through the Voice Library UI lands here under its
# VoiceProfile id, separate from the manually-placed clips in VOICES_DIR.
VOICE_PROFILES_DIR = os.environ.get("CHATTERBOX_VOICE_PROFILES_DIR", "/voice-profiles")
DEFAULT_VOICE = os.environ.get("CHATTERBOX_DEFAULT_VOICE", "narrator")

_model = None
_model_lock = threading.Lock()


def get_model():
    """Lazy-loaded, once, behind a lock - loading a 350M-parameter model on
    every request would make every single line of narration pay the full
    load cost. Same pattern as tts-indic's model loading."""
    global _model
    if _model is None:
        with _model_lock:
            if _model is None:
                log.info("Loading ChatterboxTurboTTS on device=%s (first request - this can take a while)...", DEVICE)
                from chatterbox.tts_turbo import ChatterboxTurboTTS
                _model = ChatterboxTurboTTS.from_pretrained(device=DEVICE)
                log.info("ChatterboxTurboTTS loaded.")
    return _model


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
    return jsonify({"status": "ok", "device": DEVICE, "modelLoaded": _model is not None})


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

    voice = body.get("voice") or DEFAULT_VOICE
    speed = body.get("speed") or 1.0
    pitch = body.get("pitch") or 1.0

    # Structured Voice Director metadata is converted to ChatterBox-native
    # paralinguistic tags only inside this provider. Other TTS engines never
    # see literal [gasp]/[laugh] text.
    event = str(body.get("paralinguisticEvent") or "").strip().lower()
    if event in {"laugh", "chuckle", "gasp", "sigh", "cough"} and f"[{event}]" not in text.lower():
        text = f"{text} [{event}]"

    reference = voice_reference_path(voice)
    if reference is None:
        return jsonify({
            "error": f"No reference clip for voice '{voice}' at {VOICES_DIR}/{voice}.wav. "
                     f"ChatterBox needs a short (~10s) reference clip per voice to clone - "
                     f"add one and restart, or select an existing voice from /api/voices."
        }), 400

    try:
        model = get_model()
    except Exception as exc:  # noqa: BLE001 - report exactly what failed to load, not a generic 500
        log.exception("Could not load ChatterboxTurboTTS")
        return jsonify({"error": f"Model failed to load: {exc}"}), 500

    try:
        import torchaudio as ta
        generate_kwargs = {"audio_prompt_path": reference}
        log.info("Voice Director: emotion=%s intensity=%s delivery=%s emphasis=%s breath=%s acting=%s",
                 body.get("emotion"), body.get("emotionIntensity"), body.get("delivery"),
                 body.get("emphasis"), body.get("breath"), body.get("actingDirection"))
        # Best-effort: the documented Turbo signature doesn't show a speed
        # kwarg, but if a future/different chatterbox-tts version does
        # support one, use it instead of silently ignoring the request.
        # Falls back to generating at native speed and logging once rather
        # than failing the whole request over a cosmetic parameter.
        try:
            wav_tensor = model.generate(text, speed=speed, **generate_kwargs)
        except TypeError:
            wav_tensor = model.generate(text, **generate_kwargs)
            if abs(speed - 1.0) > 0.01:
                log.warning("This chatterbox-tts version's generate() has no speed parameter; "
                            "requested speed=%.2f was ignored for this line.", speed)

        buf = io.BytesIO()
        ta.save(buf, wav_tensor, model.sr, format="wav")
        wav_bytes = buf.getvalue()
    except Exception as exc:  # noqa: BLE001
        log.exception("ChatterBox synthesis failed for voice=%s", voice)
        return jsonify({"error": f"Synthesis failed: {exc}"}), 500

    if abs(pitch - 1.0) > 0.01:
        wav_bytes = shift_pitch(wav_bytes, pitch)

    return Response(wav_bytes, mimetype="audio/wav")


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5004)
