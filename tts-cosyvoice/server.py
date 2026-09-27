import io
import logging
import os
import threading
from pathlib import Path

import soundfile as sf
from flask import Flask, Response, jsonify, request

app = Flask(__name__)
logging.basicConfig(level=logging.INFO)
log = logging.getLogger("cosyvoice-server")

DEVICE = os.environ.get("COSYVOICE_DEVICE", "cpu")
MODEL_DIR = os.environ.get("COSYVOICE_MODEL_DIR", "/models/Fun-CosyVoice3-0.5B-2512")
VOICE_DIR = os.environ.get("COSYVOICE_VOICE_DIR", "/voice-profiles")
DEFAULT_VOICE = os.environ.get("COSYVOICE_DEFAULT_VOICE", "default")
_model = None
_lock = threading.Lock()


def get_model():
    global _model
    if _model is None:
        with _lock:
            if _model is None:
                try:
                    from cosyvoice.cli.cosyvoice import CosyVoice3
                    _model = CosyVoice3(MODEL_DIR, load_jit=False, load_trt=False, fp16=(DEVICE != "cpu"))
                except ImportError:
                    from cosyvoice.cli.cosyvoice import CosyVoice
                    _model = CosyVoice(MODEL_DIR)
                log.info("CosyVoice loaded from %s on %s", MODEL_DIR, DEVICE)
    return _model


def reference_path(voice):
    p = Path(VOICE_DIR) / f"{voice}.wav"
    return str(p) if p.exists() else None


def synthesize_zero_shot(model, text, reference, reference_text, instruction=None):
    # CosyVoice3's zero-shot API is intentionally isolated here because the
    # upstream Python API can evolve independently of this Spring application.
    if not reference_text:
        raise ValueError("CosyVoice requires the exact transcript of the reference recording. "
                         "Add a reference transcript to the VoiceProfile.")
    prompt_speech = reference
    # Try the current CosyVoice3 signature first, then the older CosyVoice API.
    if hasattr(model, "inference_zero_shot"):
        result = model.inference_zero_shot(text, reference_text, prompt_speech, stream=False)
    else:
        result = model.inference_zero_shot(text, reference_text, prompt_speech, stream=False)
    chunks = []
    for item in result:
        audio = item.get("tts_speech") if isinstance(item, dict) else getattr(item, "tts_speech", None)
        if audio is None:
            continue
        try:
            audio = audio.detach().cpu().numpy()
        except AttributeError:
            audio = audio.numpy() if hasattr(audio, "numpy") else audio
        chunks.append(audio)
    if not chunks:
        raise RuntimeError("CosyVoice returned no audio")
    import numpy as np
    audio = np.concatenate(chunks, axis=-1).squeeze()
    out = io.BytesIO()
    sr = getattr(model, "sample_rate", 22050)
    sf.write(out, audio, sr, format="WAV")
    return out.getvalue()


@app.get("/health")
def health():
    return jsonify({"status": "ok", "device": DEVICE, "modelLoaded": _model is not None,
                    "modelDir": MODEL_DIR})


@app.get("/api/voices")
def voices():
    names = sorted(p.stem for p in Path(VOICE_DIR).glob("*.wav")) if Path(VOICE_DIR).exists() else []
    return jsonify({"voices": [{"id": n, "name": n, "engine": "cosyvoice", "installed": True} for n in names],
                    "defaultVoice": DEFAULT_VOICE})


@app.post("/api/tts")
def tts():
    body = request.get_json(force=True, silent=True) or {}
    text = (body.get("text") or "").strip()
    voice = body.get("voice") or DEFAULT_VOICE
    if not text:
        return jsonify({"error": "text is required"}), 400
    ref = reference_path(voice)
    if not ref:
        return jsonify({"error": f"No reference audio for voice '{voice}'"}), 400
    try:
        audio = synthesize_zero_shot(get_model(), text, ref, body.get("referenceTranscript"), body.get("actingDirection"))
        return Response(audio, mimetype="audio/wav")
    except Exception as exc:
        log.exception("CosyVoice synthesis failed")
        return jsonify({"error": str(exc)}), 500


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5005)
