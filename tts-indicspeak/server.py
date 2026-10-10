"""
Indic-Speak sidecar (bodhan-ai/indic-speak).

  GET  /health          status: loaded / loading / loadError / device / idle seconds
  GET  /api/voices      the 95 voices as {"voices":[{id,label,language,...}]}   (ids look like "speak:kn-Deepika")
  POST /api/tts         {"text": "...", "voice": "speak:kn-Deepika", "style": "optional", "speed": 1.0} -> audio/wav (24 kHz)
  POST /api/unload      free the GPU right now

The model's own inference.py (shipped in the gated repo) does the work: TTS(local_dir)(text, speaker=..., style=...) -> float32 @ 24 kHz.
It is loaded lazily and unloaded after INDICSPEAK_IDLE_UNLOAD_SECONDS of silence so it does not hog VRAM that ComfyUI needs.
"""
import io
import os
import sys
import threading
import time

import numpy as np
import soundfile as sf
from flask import Flask, Response, jsonify, request

REPO = os.environ.get("INDICSPEAK_REPO", "bodhan-ai/indic-speak").strip()
DEVICE = os.environ.get("INDICSPEAK_DEVICE", "cuda").strip().lower()
PRELOAD = os.environ.get("INDICSPEAK_PRELOAD", "false").strip().lower() in ("1", "true", "yes")
IDLE_UNLOAD = int(os.environ.get("INDICSPEAK_IDLE_UNLOAD_SECONDS", "300"))
HF_TOKEN = os.environ.get("HF_TOKEN") or os.environ.get("HUGGING_FACE_HUB_TOKEN")
SAMPLE_RATE = 24000
MAX_CHARS = int(os.environ.get("INDICSPEAK_MAX_CHARS", "1200"))

app = Flask(__name__)

# ---------------------------------------------------------------- voice catalogue (from the model card)

LANGUAGES = {
    "en": "English", "hi": "Hindi", "bn": "Bengali", "mr": "Marathi", "te": "Telugu", "ta": "Tamil", "gu": "Gujarati",
    "kn": "Kannada", "ml": "Malayalam", "or": "Odia", "pa": "Punjabi", "as": "Assamese", "ur": "Urdu",
    "brx": "Bodo", "doi": "Dogri", "kok": "Konkani", "ks": "Kashmiri", "mai": "Maithili", "ne": "Nepali",
    "mni": "Manipuri", "sa": "Sanskrit", "sat": "Santali", "sd": "Sindhi", "bhb": "Bhili",
}
PRODUCTION = {"en", "hi", "bn", "mr", "te", "ta", "gu", "kn", "ml", "or", "pa", "as", "ur"}

# language -> [(speaker, gender)]; the card lists one male + one female voice for most languages, order = as listed there.
# Genders are inferred from the artist names; they are only used for labels and default picks.
SPEAKERS = {
    "as": [("Ankur", "male"), ("Prastuti", "female")], "bn": [("Ishita", "female"), ("Sourav", "male")],
    "bhb": [("Bhima", "male"), ("Dhulji", "male"), ("Govind", "male"), ("Jhamku", "male"), ("Kanku", "female"), ("Sarju", "male"), ("Tantya", "male")],
    "brx": [("Gwrbw", "male"), ("Sansuma", "female")], "doi": [("Preeti", "female"), ("Sham", "male")],
    "gu": [("Dhara", "female"), ("Parth", "male")], "hi": [("Amit", "male"), ("Kavya", "female")],
    "kn": [("Adarsh", "male"), ("Deepika", "female")], "ks": [("Ishfaq", "male"), ("Zoon", "female")],
    "kok": [("Anjali", "female"), ("Sandeep", "male")], "mai": [("Madhukar", "male"), ("Vaidehi", "female")],
    "ml": [("Kiran", "male"), ("Lakshmi", "female")], "mni": [("Chaoba", "male"), ("Thoibi", "female")],
    "mr": [("Anagha", "female"), ("Chinmay", "male")], "ne": [("Sagar", "male"), ("Srijana", "female")],
    "or": [("Akash", "male"), ("Itishree", "female")], "pa": [("Kaur", "female"), ("Manpreet", "male")],
    "sa": [("Aryaman", "male"), ("Bharati", "female")], "sat": [("Phulmani", "female"), ("Sibu", "male")],
    "sd": [("Moomal", "female"), ("Rano", "male")], "ta": [("Anitha", "female"), ("Arun", "male")],
    "te": [("Sravani", "female"), ("Vamsi", "male")], "ur": [("Saba", "female"), ("Zaid", "male")],
    "en": [(n, "") for n in ("Adarsh Akash Amit Anagha Anitha Anjali Ankur Arun Aryaman Bharati Chaoba Chinmay Deepika Dhara Gwrbw Ishfaq Ishita "
                             "Itishree Kaur Kavya Kiran Lakshmi Madhukar Manpreet Moomal Parth Phulmani Prastuti Preeti Rano Saba Sagar Sandeep "
                             "Sansuma Sham Sibu Sourav Sravani Srijana Thoibi Vaidehi Vamsi Zaid Zoon").split()],
}
PREFIX = "speak:"


def voice_id(lang: str, speaker: str) -> str:
    return f"{PREFIX}{lang}-{speaker}"


def parse_voice(voice: str):
    """'speak:kn-Deepika' -> ('kn', 'Deepika'). Raises ValueError for anything not in the catalogue
    (an unseen speaker name does not fail inside the model, it silently gives a worse averaged voice)."""
    v = (voice or "").strip()
    if v.startswith(PREFIX):
        v = v[len(PREFIX):]
    lang, _, speaker = v.partition("-")
    if lang not in SPEAKERS or speaker not in [s for s, _ in SPEAKERS[lang]]:
        raise ValueError(f"Unknown Indic-Speak voice '{voice}'. Use GET /api/voices.")
    return lang, speaker


# English has 44 artist voices; listing them all would swamp every voice dropdown. These Indian-accent teaching voices are
# listed by default (all 44 still work by name; INDICSPEAK_LIST_ALL_ENGLISH=true lists them all).
ENGLISH_LISTED = {"Amit", "Kavya", "Anitha", "Arun", "Deepika", "Adarsh", "Lakshmi", "Kiran"}
LIST_ALL_ENGLISH = os.environ.get("INDICSPEAK_LIST_ALL_ENGLISH", "false").strip().lower() in ("1", "true", "yes")


def catalogue():
    out = []
    for lang, items in SPEAKERS.items():
        for speaker, gender in items:
            if lang == "en" and not LIST_ALL_ENGLISH and speaker not in ENGLISH_LISTED:
                continue
            out.append({
                "id": voice_id(lang, speaker), "label": f"{speaker} ({LANGUAGES.get(lang, lang)})",
                "language": LANGUAGES.get(lang, lang), "languageCode": lang, "gender": gender,
                "accent": "IN", "quality": "neural-llm" if lang in PRODUCTION else "preview", "engine": "indicspeak",
                "installed": True, "isDefault": False,
                "notes": ("Indic-Speak: STEM + code-mixed speech, 24 kHz" + ("" if lang in PRODUCTION else " (preview language)")),
            })
    return out


# ---------------------------------------------------------------- model lifecycle

_lock = threading.Lock()          # one synthesis at a time (3.8B LM on one GPU)
_tts = None
_loading = False
_load_error = None
_last_used = 0.0


def _load():
    global _tts, _loading, _load_error
    _loading = True
    try:
        from huggingface_hub import snapshot_download
        local = snapshot_download(REPO, token=HF_TOKEN)          # inference.py, vocos/, tokenizer, weights
        if local not in sys.path:
            sys.path.insert(0, local)
        import importlib
        inference = importlib.import_module("inference")
        t = inference.TTS(local)
        if DEVICE == "cpu":
            app.logger.warning("Indic-Speak on CPU: a 3.8B model is very slow there")
        _tts = t
        _load_error = None
        app.logger.info("Indic-Speak loaded from %s", local)
    except Exception as exc:  # noqa: BLE001
        _load_error = f"{type(exc).__name__}: {exc}"
        if "403" in _load_error or "gated" in _load_error.lower() or "401" in _load_error:
            _load_error += (" - accept the licence at https://huggingface.co/" + REPO +
                            " with the account that owns HF_TOKEN, then restart this service.")
        app.logger.exception("Indic-Speak load failed")
        raise
    finally:
        _loading = False


def get_tts():
    global _tts
    if _tts is None:
        _load()
    return _tts


def unload():
    global _tts
    with _lock:
        _tts = None
    try:
        import gc
        import torch
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
    except Exception:  # noqa: BLE001
        pass
    app.logger.info("Indic-Speak unloaded (GPU memory freed)")


def _idle_watcher():
    while True:
        time.sleep(20)
        if _tts is not None and IDLE_UNLOAD > 0 and time.time() - _last_used > IDLE_UNLOAD and not _lock.locked():
            unload()


# ---------------------------------------------------------------- audio helpers

def to_wav_bytes(wav, speed: float) -> bytes:
    a = np.asarray(wav, dtype=np.float32).reshape(-1)
    a = np.nan_to_num(a, nan=0.0, posinf=0.0, neginf=0.0)
    peak = float(np.max(np.abs(a))) if a.size else 0.0
    if peak > 1.0:
        a = a / peak                                   # never clip
    buf = io.BytesIO()
    sf.write(buf, a, SAMPLE_RATE, format="WAV", subtype="PCM_16")
    data = buf.getvalue()
    if abs(speed - 1.0) > 0.02:
        data = _tempo(data, speed)
    return data


def _tempo(wav_bytes: bytes, speed: float) -> bytes:
    """Pitch-preserving speed change with ffmpeg atempo (chained for factors outside 0.5-2)."""
    import subprocess
    speed = max(0.5, min(2.0, speed))
    p = subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-i", "pipe:0", "-filter:a", f"atempo={speed:.3f}", "-f", "wav", "pipe:1"],
                       input=wav_bytes, capture_output=True)
    return p.stdout if p.returncode == 0 and p.stdout else wav_bytes


# ---------------------------------------------------------------- API

@app.get("/health")
def health():
    return jsonify({
        "status": "ok", "repo": REPO, "device": DEVICE, "modelLoaded": _tts is not None, "loading": _loading,
        "loadError": _load_error, "idleUnloadSeconds": IDLE_UNLOAD, "secondsSinceLastUse": round(time.time() - _last_used, 1) if _last_used else None,
        "voices": sum(len(v) for v in SPEAKERS.values()), "sampleRate": SAMPLE_RATE,
    })


@app.get("/api/voices")
def voices():
    return jsonify({"voices": catalogue()})


@app.post("/api/unload")
def api_unload():
    unload()
    return jsonify({"status": "unloaded"})


@app.post("/api/tts")
def api_tts():
    global _last_used
    body = request.get_json(force=True, silent=True) or {}
    text = (body.get("text") or "").strip()
    if not text:
        return jsonify({"error": "No text to speak."}), 400
    if len(text) > MAX_CHARS:
        return jsonify({"error": f"Text is {len(text)} characters; send at most {MAX_CHARS} per request (split into sentences)."}), 400
    try:
        lang, speaker = parse_voice(body.get("voice") or "")
    except ValueError as exc:
        return jsonify({"error": str(exc)}), 404
    style = (body.get("style") or "").strip() or None
    speed = float(body.get("speed") or 1.0)
    if _loading:
        return jsonify({"error": "Indic-Speak is still loading (first start downloads ~7.6 GB). Try again in a minute; GET /health shows progress."}), 503
    with _lock:
        try:
            tts = get_tts()
        except Exception as exc:  # noqa: BLE001
            return jsonify({"error": f"Indic-Speak could not load: {_load_error or exc}"}), 503
        try:
            kwargs = {"speaker": speaker}
            if style:
                kwargs["style"] = style
            wav = tts(text, **kwargs)
        except Exception as exc:  # noqa: BLE001
            app.logger.exception("Indic-Speak synthesis failed")
            msg = f"{type(exc).__name__}: {exc}"
            status = 507 if "out of memory" in msg.lower() else 500
            return jsonify({"error": "Indic-Speak synthesis failed: " + msg + (" - the GPU is full (stop ComfyUI work or lower INDICSPEAK usage)." if status == 507 else "")}), status
        finally:
            _last_used = time.time()
    return Response(to_wav_bytes(wav, speed), mimetype="audio/wav")


def _preload():
    try:
        with _lock:
            get_tts()
    except Exception:  # noqa: BLE001 - reported via /health and the next request
        pass


if __name__ == "__main__":
    threading.Thread(target=_idle_watcher, daemon=True).start()
    if PRELOAD:
        threading.Thread(target=_preload, daemon=True).start()
    app.run(host="0.0.0.0", port=5006, threaded=True)
