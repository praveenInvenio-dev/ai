"""
IndicF5 text-to-speech service (AI4Bharat), exposing the same
"POST JSON -> WAV bytes" contract as the Piper service.

Why this is a separate container rather than another engine inside tts/:
IndicF5 needs PyTorch and transformers (~1 GB of wheels) plus ~1.6 GB of
weights. Baking that into the default TTS image would triple its size for
everyone, including people who only ever narrate in English. So this runs under
a compose profile and is off unless you ask for it.

What IndicF5 gives you that Piper does not:
  - Kannada, and 10 other Indian languages, at near-human quality
  - MIT licence, so unlike MMS-TTS (CC-BY-NC) it is usable commercially

What it costs:
  - NO ENGLISH. It is Indic-only, so it supplements Piper rather than replacing
    it. English narration must still go through Piper or Edge.
  - It is prompt-based (F5 architecture): every request needs a reference audio
    clip AND that clip's transcript. It copies prosody and speaker identity from
    the reference, so output quality is capped by reference quality.
  - Flow matching runs many denoising steps over the whole utterance, so on CPU
    expect several seconds of compute per second of speech.

On references and consent: the model card prohibits cloning a voice you do not
have permission to use. The intended path here is that you record your own
10-second reference, which also makes the narrator sound like you.
"""
import os
import io
import threading

import numpy as np
import soundfile as sf
from flask import Flask, request, Response, jsonify

app = Flask(__name__)

REPO_ID = os.environ.get("INDICF5_REPO", "ai4bharat/IndicF5")
PROMPTS_DIR = os.environ.get("INDICF5_PROMPTS_DIR", "/prompts")
SAMPLE_RATE = 24000
DEVICE = os.environ.get("INDICF5_DEVICE", "auto").strip().lower()
PRELOAD = os.environ.get("INDICF5_PRELOAD", "true").strip().lower() in ("1", "true", "yes")
_device = "cpu"
_loading = False

# Languages IndicF5 covers. English is deliberately absent - it is not supported
# and asking for it produces garbled output rather than an error.
LANGUAGES = [
    "assamese", "bengali", "gujarati", "hindi", "kannada", "malayalam",
    "marathi", "odia", "punjabi", "tamil", "telugu",
]

_model = None
_model_lock = threading.Lock()
_load_error: str | None = None


def get_model():
    """
    Lazily load the model on first synthesis.

    Loading is ~1.6 GB and takes a while, and it happens here rather than at
    import so that /health answers immediately - the backend's startup probe
    should not block on it, and a load failure should be reportable rather than
    crash-looping the container.
    """
    global _model, _load_error, _device, _loading
    if _model is not None:
        return _model
    with _model_lock:
        if _model is not None:
            return _model
        _loading = True
        try:
            from transformers import AutoModel
            app.logger.info("Loading %s (this takes a few minutes on first run)", REPO_ID)
            # Pass the token explicitly as well as via HF's environment handling.
            # This makes the gated-repository requirement unambiguous and avoids
            # confusing failures when the hub library changes its env-variable
            # handling. Never log the token itself.
            hf_token = os.environ.get("HF_TOKEN") or os.environ.get("HUGGING_FACE_HUB_TOKEN")
            if not hf_token:
                raise RuntimeError(
                    "HF_TOKEN is not set. Accept the IndicF5 model terms on Hugging Face "
                    "and provide a read token via HF_TOKEN in .env."
                )
            # trust_remote_code: IndicF5 ships its own modelling code, which is how
            # the F5 flow-matching pipeline is wired up. Required by the model card.
            _model = AutoModel.from_pretrained(
                REPO_ID, trust_remote_code=True, token=hf_token
            )
            requested = DEVICE
            if requested == "auto":
                try:
                    import torch
                    requested = "cuda" if torch.cuda.is_available() else "cpu"
                except Exception:
                    requested = "cpu"
            if requested == "cuda":
                try:
                    _model = _model.to("cuda")
                    _device = "cuda"
                    app.logger.info("Loaded %s on CUDA", REPO_ID)
                except Exception as exc:
                    app.logger.warning("CUDA load failed (%s); keeping IndicF5 on CPU", exc)
            _model.eval()
            _load_error = None
            app.logger.info("Loaded %s on %s", REPO_ID, _device)
            return _model
        except Exception as exc:  # noqa: BLE001
            _load_error = f"{type(exc).__name__}: {exc}"
            app.logger.exception("IndicF5 load failed")
            raise
        finally:
            _loading = False


def list_prompts() -> list[dict]:
    """
    Reference clips available as voices.

    A "voice" here is a (wav, txt) pair in the prompts directory: narrator.wav
    plus narrator.txt holding that clip's exact transcript. The transcript is not
    optional - F5 aligns against it, and a wrong transcript degrades output badly
    rather than being ignored.
    """
    if not os.path.isdir(PROMPTS_DIR):
        return []
    prompts = []
    for filename in sorted(os.listdir(PROMPTS_DIR)):
        if not filename.endswith(".wav"):
            continue
        stem = filename[: -len(".wav")]
        transcript_path = os.path.join(PROMPTS_DIR, stem + ".txt")
        prompts.append({
            "id": stem,
            "wav": os.path.join(PROMPTS_DIR, filename),
            "transcript": transcript_path,
            "ready": os.path.exists(transcript_path),
        })
    return prompts


@app.get("/health")
def health():
    return jsonify({
        "status": "ok",
        "repo": REPO_ID,
        "modelLoaded": _model is not None,
        "loading": _loading,
        "device": _device,
        "loadError": _load_error,
        "prompts": [p["id"] for p in list_prompts()],
    })


@app.get("/api/voices")
def voices():
    out = []
    for prompt in list_prompts():
        out.append({
            "id": f"indic:{prompt['id']}",
            "label": f"{prompt['id']} (IndicF5)",
            "accent": "IN (11 Indic languages)",
            "gender": "",
            "quality": "near-human",
            "notes": ("Reference-prompt voice. Indic languages only - no English."
                      if prompt["ready"]
                      else f"Missing {prompt['id']}.txt with the reference transcript."),
            "installed": prompt["ready"],
            "engine": "indicf5",
            "isDefault": False,
        })
    return jsonify({"voices": out, "languages": LANGUAGES})


@app.post("/api/tts")
def synthesize():
    body = request.get_json(force=True, silent=True) or {}
    text = (body.get("text") or "").strip()
    voice = (body.get("voice") or "").replace("indic:", "", 1)

    if not text:
        return jsonify({"error": "text is required"}), 400

    prompts = {p["id"]: p for p in list_prompts()}
    if not prompts:
        return jsonify({
            "error": f"No reference prompts found in {PROMPTS_DIR}. IndicF5 cannot "
                     "synthesise without one. Add <name>.wav plus <name>.txt "
                     "containing that clip's exact transcript."
        }), 400

    prompt = prompts.get(voice) or next(iter(prompts.values()))
    if not prompt["ready"]:
        return jsonify({
            "error": f"Reference '{prompt['id']}' has no transcript file. "
                     f"Create {prompt['id']}.txt next to the wav, containing "
                     "exactly what is spoken in it."
        }), 400

    with open(prompt["transcript"], "r", encoding="utf-8") as f:
        ref_text = f.read().strip()

    if _loading and _model is None:
        return jsonify({"error": "IndicF5 is still loading (first start downloads ~1.6 GB). "
                                 "Try again in a minute; GET /health shows progress."}), 503
    try:
        model = get_model()
    except Exception as exc:  # noqa: BLE001
        return jsonify({"error": f"Could not load {REPO_ID}: {exc}. "
                                 "If this is a gated-repo error, accept the terms on "
                                 "the model page and set HF_TOKEN."}), 503

    # Synthesis is single-threaded on purpose: two concurrent flow-matching runs
    # on a CPU-only box contend for the same cores and both end up slower than
    # if they had queued.
    # IndicF5 has no English: Latin-only text comes out as garbage (or crashes on unknown
    # characters), so refuse it with a useful message instead.
    if not any(ord(ch) > 0x0900 for ch in text):
        return jsonify({"error": "IndicF5 speaks Indian languages only. Send text in an Indian script "
                                 "(e.g. Kannada, Hindi, Tamil) or pick an English voice."}), 400

    with _model_lock:
        try:
            audio = _synthesize(model, text, prompt["wav"], ref_text)
        except Exception as exc:  # noqa: BLE001
            app.logger.exception("IndicF5 synthesis failed")
            return jsonify({"error": f"IndicF5 synthesis failed: {type(exc).__name__}: {exc}"}), 500

    audio = np.asarray(audio)
    # The model returns int16 in some paths and float in others; soundfile needs
    # float32 in [-1, 1], so normalise rather than assuming one of them.
    if audio.dtype == np.int16:
        audio = audio.astype(np.float32) / 32768.0
    else:
        audio = audio.astype(np.float32)

    buffer = io.BytesIO()
    sf.write(buffer, audio, samplerate=SAMPLE_RATE, format="WAV", subtype="PCM_16")
    return Response(buffer.getvalue(), mimetype="audio/wav")


def _synthesize(model, text, ref_wav, ref_text):
    """Run IndicF5; on a CUDA problem (out of memory while ComfyUI holds the GPU, device
    mismatch) retry once on CPU instead of failing the whole narration."""
    global _model, _device
    try:
        import torch
        with torch.inference_mode():
            return model(text, ref_audio_path=ref_wav, ref_text=ref_text)
    except Exception as exc:  # noqa: BLE001
        msg = str(exc).lower()
        if _device == "cuda" and ("cuda" in msg or "out of memory" in msg or "device" in msg):
            app.logger.warning("IndicF5 CUDA synthesis failed (%s); retrying on CPU", exc)
            import torch
            _model = model.to("cpu")
            _device = "cpu"
            torch.cuda.empty_cache()
            with torch.inference_mode():
                return _model(text, ref_audio_path=ref_wav, ref_text=ref_text)
        raise
    finally:
        try:
            import torch
            if torch.cuda.is_available():
                torch.cuda.empty_cache()  # give VRAM back to ComfyUI between lines
        except Exception:  # noqa: BLE001
            pass


def _preload():
    try:
        get_model()
    except Exception:  # noqa: BLE001 - reported via /health and on the next request
        pass


if __name__ == "__main__":
    os.makedirs(PROMPTS_DIR, exist_ok=True)
    if PRELOAD:
        # Load in the background so the first "Play sample" does not wait minutes for the
        # 1.6 GB download + load (and time out in the backend). /health shows progress.
        threading.Thread(target=_preload, daemon=True).start()
    app.run(host="0.0.0.0", port=5003, threaded=True)
