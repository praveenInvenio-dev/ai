"""Kokoro text-to-speech sidecar (Apache-2.0 model, 82M parameters, CPU friendly, no reference clip needed).

  GET  /health      -> {"status": "ok", "model_loaded": bool}
  GET  /api/voices  -> the voice ids this image knows
  POST /api/tts     {"text", "voice": "am_michael" | "kokoro:am_michael", "speed": 1.0}  ->  audio/wav (24 kHz mono)

The model is loaded lazily on the first request (weights are downloaded from Hugging Face once into the cache volume).
Voice ids start with a language letter: a = American English, b = British English (af_ / am_ = female / male).
"""
import io
import logging
import os
import re
import threading

import numpy as np
import soundfile as sf
from flask import Flask, jsonify, request

log = logging.getLogger("tts-kokoro")
logging.basicConfig(level=logging.INFO)
app = Flask(__name__)

SAMPLE_RATE = 24000
DEFAULT_VOICE = os.environ.get("KOKORO_DEFAULT_VOICE", "am_michael")
MAX_CHARS = int(os.environ.get("KOKORO_MAX_CHARS", "1200"))
VOICES = ["af_heart", "af_bella", "af_nicole", "af_sarah", "af_sky", "am_adam", "am_michael", "am_eric", "bf_emma", "bf_isabella", "bm_george", "bm_lewis"]
VOICE_RE = re.compile(r"^[ab][fm]_[a-z]+$")

_pipelines = {}
_lock = threading.Lock()


def _pipeline(lang_code: str):
    """One KPipeline per language letter, created on first use (the import is lazy so /health works before the model exists)."""
    with _lock:
        if lang_code not in _pipelines:
            from kokoro import KPipeline  # imported here: heavy (torch) and not needed to answer /health
            _pipelines[lang_code] = KPipeline(lang_code=lang_code)
        return _pipelines[lang_code]


def clean_voice(raw) -> str:
    voice = str(raw or DEFAULT_VOICE).strip()
    if voice.startswith("kokoro:"):
        voice = voice[len("kokoro:"):]
    return voice


@app.get("/health")
def health():
    return jsonify({"status": "ok", "model_loaded": bool(_pipelines), "default_voice": DEFAULT_VOICE})


@app.get("/api/voices")
def voices():
    return jsonify([{"id": v, "label": v, "installed": True} for v in VOICES])


@app.post("/api/tts")
def tts():
    body = request.get_json(silent=True) or {}
    text = str(body.get("text") or "").strip()
    if not text:
        return jsonify({"error": "text is required"}), 400
    if len(text) > MAX_CHARS:
        return jsonify({"error": f"text is longer than {MAX_CHARS} characters"}), 400
    voice = clean_voice(body.get("voice"))
    if not VOICE_RE.match(voice):
        return jsonify({"error": f"unknown Kokoro voice '{voice}'. Known voices: {', '.join(VOICES)}"}), 400
    try:
        speed = float(body.get("speed") or 1.0)
    except (TypeError, ValueError):
        speed = 1.0
    speed = min(1.3, max(0.7, speed))
    try:
        chunks = []
        for _graphemes, _phonemes, audio in _pipeline(voice[0])(text, voice=voice, speed=speed, split_pattern=r"\n+"):
            if audio is not None:
                chunks.append(np.asarray(audio, dtype=np.float32).reshape(-1))
        if not chunks:
            return jsonify({"error": "Kokoro produced no audio for this text"}), 500
        wav = np.concatenate(chunks)
        buf = io.BytesIO()
        sf.write(buf, wav, SAMPLE_RATE, format="WAV", subtype="PCM_16")
        return buf.getvalue(), 200, {"Content-Type": "audio/wav"}
    except Exception as exc:  # the backend turns this into a visible warning and falls back to the local voice
        log.exception("Kokoro synthesis failed")
        return jsonify({"error": f"Kokoro failed: {exc}"}), 500


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "5006")))
