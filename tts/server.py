"""
HTTP wrapper around the real Piper TTS binary.

This exists because most ready-made Piper Docker images (including the
rhasspy/wyoming-piper image this project used to ship) speak the Wyoming
protocol (a binary/TCP protocol) rather than plain HTTP - incompatible with
LocalTTSProvider's simple "POST JSON, get WAV bytes back" contract. This
wrapper runs the actual `piper` binary and exposes exactly that contract, so
narration is genuinely synthesized instead of silently falling back to
silent placeholder audio.

Beyond synthesis it also manages the voice library:

  GET  /api/voices              list voices, installed and available
  POST /api/voices/<name>       download a catalogued voice into /voices
  POST /api/tts                 text -> wav

Voices live in the tts-data volume, so installing one persists across
restarts and does not require rebuilding the image. Only the default voice is
baked in, which keeps the image small.
"""
import os
import re
import subprocess
import tempfile
import asyncio
import json
import threading
import wave

import urllib.request
from flask import Flask, request, Response, jsonify

app = Flask(__name__)

VOICES_DIR = "/voices"
DEFAULT_VOICE = os.environ.get("TTS_DEFAULT_VOICE") or os.environ.get("PIPER_DEFAULT_VOICE", "en_US-amy-medium")
PIPER_FALLBACK_VOICE = os.environ.get("PIPER_FALLBACK_VOICE", "en_US-lessac-high")
HF_BASE = "https://huggingface.co/rhasspy/piper-voices/resolve/main"

# Voices worth offering for children's narration, with the metadata the UI needs
# to describe them. "high" models are ~110 MB and noticeably smoother than
# "medium" (~63 MB) - for narration, where the audio is the whole product, the
# extra size is worth it. "low" is deliberately not offered; it sounds robotic.
#
# Each entry maps to a Hugging Face path: <lang>/<locale>/<speaker>/<quality>/
VOICE_CATALOG = {
    "en_US-amy-medium": {
        "label": "Amy", "accent": "US", "gender": "female", "quality": "medium",
        "notes": "Warm and even. Safe default for narration.",
        "path": ("en", "en_US", "amy", "medium"),
    },
    "en_US-lessac-high": {
        "label": "Lessac", "accent": "US", "gender": "female", "quality": "high",
        "notes": "Clearest of the set. Best diction for younger listeners.",
        "path": ("en", "en_US", "lessac", "high"),
    },
    "en_US-ryan-high": {
        "label": "Ryan", "accent": "US", "gender": "male", "quality": "high",
        "notes": "Warm male storyteller. Good for narrator roles.",
        "path": ("en", "en_US", "ryan", "high"),
    },
    "en_US-hfc_female-medium": {
        "label": "HFC Female", "accent": "US", "gender": "female", "quality": "medium",
        "notes": "Bright and friendly, a little faster in feel.",
        "path": ("en", "en_US", "hfc_female", "medium"),
    },
    "en_US-hfc_male-medium": {
        "label": "HFC Male", "accent": "US", "gender": "male", "quality": "medium",
        "notes": "Neutral male. Useful contrast against Amy.",
        "path": ("en", "en_US", "hfc_male", "medium"),
    },
    "en_US-kristin-medium": {
        "label": "Kristin", "accent": "US", "gender": "female", "quality": "medium",
        "notes": "Softer and slower. Suits bedtime stories.",
        "path": ("en", "en_US", "kristin", "medium"),
    },
    "en_GB-alba-medium": {
        "label": "Alba", "accent": "GB (Scottish)", "gender": "female", "quality": "medium",
        "notes": "Scottish accent. Distinctive for character voices.",
        "path": ("en", "en_GB", "alba", "medium"),
    },
    "en_GB-northern_english_male-medium": {
        "label": "Northern English Male", "accent": "GB", "gender": "male", "quality": "medium",
        "notes": "Northern English. Good storyteller texture.",
        "path": ("en", "en_GB", "northern_english_male", "medium"),
    },
    # --- Indian languages -------------------------------------------------
    # Piper covers Hindi, Telugu and Malayalam. It does NOT have Kannada or
    # Indian-accented English - see the README for what to use instead.
    "hi_IN-pratham-medium": {
        "label": "Pratham (Hindi)", "accent": "IN (Hindi)", "gender": "male", "quality": "medium",
        "notes": "Hindi male narrator.",
        "path": ("hi", "hi_IN", "pratham", "medium"),
    },
    "hi_IN-priyamvada-medium": {
        "label": "Priyamvada (Hindi)", "accent": "IN (Hindi)", "gender": "female", "quality": "medium",
        "notes": "Hindi female. Pairs well with the kid preset.",
        "path": ("hi", "hi_IN", "priyamvada", "medium"),
    },
    "hi_IN-rohan-medium": {
        "label": "Rohan (Hindi)", "accent": "IN (Hindi)", "gender": "male", "quality": "medium",
        "notes": "Hindi male, brighter than Pratham.",
        "path": ("hi", "hi_IN", "rohan", "medium"),
    },
    "te_IN-tanuja-medium": {
        "label": "Tanuja (Telugu)", "accent": "IN (Telugu)", "gender": "female", "quality": "medium",
        "notes": "Telugu female.",
        "path": ("te", "te_IN", "tanuja", "medium"),
    },
    "ml_IN-meera-medium": {
        "label": "Meera (Malayalam)", "accent": "IN (Malayalam)", "gender": "female", "quality": "medium",
        "notes": "Malayalam female.",
        "path": ("ml", "ml_IN", "meera", "medium"),
    },
    "en_GB-jenny_dioco-medium": {
        "label": "Jenny", "accent": "GB", "gender": "female", "quality": "medium",
        "notes": "British female, gentle and measured.",
        "path": ("en", "en_GB", "jenny_dioco", "medium"),
    },
}

# ---------------------------------------------------------------------------
# Second engine: Microsoft Edge's read-aloud voices, via the edge-tts package.
#
# Why this exists: Piper has no Kannada voice, no Indian-accented English and no
# child voices, and the only other options were a paid API or a model too large
# for modest hardware. Edge voices are free, need no API key, and cover all of
# the above at Azure Neural quality.
#
# The honest caveats, because they matter for anything you intend to publish:
#   - It is an UNOFFICIAL use of an endpoint Microsoft ships for its browser.
#     There is no SLA and it can break without warning.
#   - It requires an internet connection, so the pipeline is no longer offline.
#   - Microsoft's terms do not clearly grant third-party programmatic use.
# Piper remains the default for those reasons; Edge voices are opt-in per voice.
# For a fully-offline Kannada option see the README (MMS-TTS, CC-BY-NC).
# ---------------------------------------------------------------------------
EDGE_PREFIX = "edge:"
INDIC_PREFIX = "indic:"
# Spec section 34: when set, no external network call may happen from this
# service. INDIC_PREFIX stays allowed - tts-indic is a local sibling
# container (docker compose --profile indic up -d tts-indic), not a cloud
# call. EDGE_PREFIX is the one real exception: edge-tts talks to Microsoft's
# hosted service regardless of how the voice was chosen, so it's the only
# thing this flag actually needs to block.
OFFLINE_MODE = os.environ.get("OFFLINE_MODE", "false").lower() in ("1", "true", "yes")

# The IndicF5 service, when the "indic" compose profile is running. Proxied
# through here so the UI has one voice list instead of two, and so nothing
# breaks when the profile is off - the voices simply do not appear.
INDIC_BASE_URL = os.environ.get("INDICF5_BASE_URL", "http://tts-indic:5003")

EDGE_VOICE_CATALOG = {
    "edge:kn-IN-SapnaNeural": {
        "label": "Sapna (Kannada)", "accent": "IN (Kannada)", "gender": "female",
        "notes": "Kannada female. Not available in Piper at any quality.",
    },
    "edge:kn-IN-GaganNeural": {
        "label": "Gagan (Kannada)", "accent": "IN (Kannada)", "gender": "male",
        "notes": "Kannada male narrator.",
    },
    "edge:hi-IN-SwaraNeural": {
        "label": "Swara (Hindi)", "accent": "IN (Hindi)", "gender": "female",
        "notes": "Hindi female. Noticeably smoother than the Piper Hindi voices.",
    },
    "edge:hi-IN-MadhurNeural": {
        "label": "Madhur (Hindi)", "accent": "IN (Hindi)", "gender": "male",
        "notes": "Hindi male narrator.",
    },
    "edge:en-IN-NeerjaNeural": {
        "label": "Neerja (Indian English)", "accent": "IN (English)", "gender": "female",
        "notes": "Indian-accented English. Pairs with the kid presets.",
    },
    "edge:en-IN-PrabhatNeural": {
        "label": "Prabhat (Indian English)", "accent": "IN (English)", "gender": "male",
        "notes": "Indian-accented English, male.",
    },
    "edge:ta-IN-PallaviNeural": {
        "label": "Pallavi (Tamil)", "accent": "IN (Tamil)", "gender": "female",
        "notes": "Tamil female.",
    },
    "edge:te-IN-ShrutiNeural": {
        "label": "Shruti (Telugu)", "accent": "IN (Telugu)", "gender": "female",
        "notes": "Telugu female.",
    },
    "edge:ml-IN-SobhanaNeural": {
        "label": "Sobhana (Malayalam)", "accent": "IN (Malayalam)", "gender": "female",
        "notes": "Malayalam female.",
    },
    "edge:mr-IN-AarohiNeural": {
        "label": "Aarohi (Marathi)", "accent": "IN (Marathi)", "gender": "female",
        "notes": "Marathi female.",
    },
}


def edge_synthesize(text: str, voice: str, speed: float) -> bytes:
    """
    Synthesise with edge-tts and return WAV bytes.

    edge-tts emits MP3, but the rest of the pipeline is WAV end to end (FFmpeg
    concatenation and the Java duration probe both assume it), so it is
    converted here rather than leaking a second format outward.
    """
    import edge_tts  # imported lazily so a missing package cannot break Piper

    voice_id = voice[len(EDGE_PREFIX):]
    # Edge takes a percentage delta, not a multiplier.
    rate = f"{int(round((speed - 1.0) * 100)):+d}%"

    with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as mp3_f:
        mp3_path = mp3_f.name
    wav_path = mp3_path + ".wav"
    try:
        async def run():
            communicate = edge_tts.Communicate(text, voice_id, rate=rate)
            await communicate.save(mp3_path)

        asyncio.run(run())

        if not os.path.exists(mp3_path) or os.path.getsize(mp3_path) == 0:
            raise RuntimeError("edge-tts produced no audio")

        proc = subprocess.run(
            ["ffmpeg", "-y", "-i", mp3_path, "-ar", "22050", "-ac", "1", wav_path],
            capture_output=True, timeout=120,
        )
        if proc.returncode != 0:
            raise RuntimeError(proc.stderr.decode("utf-8", "ignore")[-300:])
        with open(wav_path, "rb") as f:
            return f.read()
    finally:
        for path in (mp3_path, wav_path):
            if os.path.exists(path):
                os.remove(path)


# One install at a time: two concurrent downloads of a 110 MB model would
# otherwise race on the same temp path and leave a truncated .onnx behind,
# which piper reports as an opaque parse failure.
_install_lock = threading.Lock()


def voice_model_path(voice: str) -> str:
    safe = "".join(c for c in voice if c.isalnum() or c in "-_")
    return os.path.join(VOICES_DIR, f"{safe}.onnx")


def is_installed(voice: str) -> bool:
    model = voice_model_path(voice)
    # Piper needs BOTH files; a missing .json is a silent failure mode.
    return os.path.exists(model) and os.path.exists(model + ".json")


def download_voice(voice: str) -> None:
    entry = VOICE_CATALOG.get(voice)
    if not entry:
        raise ValueError(f"Unknown voice '{voice}'")
    lang, locale, speaker, quality = entry["path"]
    base = f"{HF_BASE}/{lang}/{locale}/{speaker}/{quality}/{voice}"
    target = voice_model_path(voice)

    os.makedirs(VOICES_DIR, exist_ok=True)
    # target already ends in ".onnx", so the sidecar is target + ".json".
    for suffix, final in ((".onnx", target), (".onnx.json", target + ".json")):
        url = base + suffix
        # Download to a temp name and rename only on success, so an interrupted
        # download can never leave a half-written model that looks installed -
        # piper reports those as an opaque parse error rather than a bad file.
        tmp = final + ".part"
        with urllib.request.urlopen(url, timeout=300) as response, open(tmp, "wb") as out:
            while True:
                chunk = response.read(1 << 20)
                if not chunk:
                    break
                out.write(chunk)
        os.replace(tmp, final)


def indic_get_voices() -> list:
    """Voice list from the IndicF5 service, or empty if the profile is not up."""
    try:
        with urllib.request.urlopen(f"{INDIC_BASE_URL}/api/voices", timeout=5) as response:
            return json.loads(response.read().decode("utf-8")).get("voices", [])
    except Exception:  # noqa: BLE001 - absence is the normal case, not an error
        return []


def indic_synthesize(text: str, voice: str) -> bytes:
    """
    Forward to the IndicF5 service.

    The timeout is generous because flow matching on CPU takes several seconds
    per second of speech - a 30-second narration line can legitimately take
    minutes, and cutting it short would look like a failure rather than slowness.
    """
    payload = json.dumps({"text": text, "voice": voice}).encode("utf-8")
    req = urllib.request.Request(
        f"{INDIC_BASE_URL}/api/tts", data=payload,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=900) as response:
        return response.read()


def shift_pitch(wav_bytes: bytes, pitch: float) -> bytes:
    """
    Raise or lower pitch without changing duration.

    Piper has no pitch control - it only exposes length_scale, which changes
    speed and pitch together and makes a "child voice" sound like a tape played
    fast. Doing it as a post-process instead lets narration keep its timing,
    which matters because scene durations are derived from the audio length.

    asetrate resamples (shifting pitch AND speed), aresample restores the sample
    rate, and atempo puts the duration back. atempo only accepts 0.5-2.0 per
    filter, but the useful pitch range here is well inside that.
    """
    pitch = max(0.5, min(2.0, pitch))
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as src_f:
        src = src_f.name
        src_f.write(wav_bytes)
    dst = src + ".shifted.wav"
    try:
        # asetrate needs the real sample rate, not a guess: Piper medium models
        # output 22050 Hz and high models 22050 too, but custom voices vary, and
        # hardcoding 44100 would detune every voice that is not 44.1 kHz.
        with wave.open(src, "rb") as w:
            sample_rate = w.getframerate()
        rate_filter = (f"asetrate={int(sample_rate * pitch)},"
                       f"aresample={sample_rate},"
                       f"atempo={1.0 / pitch:.4f}")
        proc = subprocess.run(
            ["ffmpeg", "-y", "-i", src, "-af", rate_filter, dst],
            capture_output=True, timeout=120,
        )
        if proc.returncode != 0 or not os.path.exists(dst):
            app.logger.warning("Pitch shift failed, returning unshifted audio: %s",
                               proc.stderr.decode("utf-8", "ignore")[-300:])
            return wav_bytes
        with open(dst, "rb") as f:
            return f.read()
    except Exception as exc:  # noqa: BLE001 - never fail synthesis over an effect
        app.logger.warning("Pitch shift error: %s", exc)
        return wav_bytes
    finally:
        for path in (src, dst):
            if os.path.exists(path):
                os.remove(path)


@app.get("/health")
def health():
    return jsonify({"status": "ok", "defaultVoice": DEFAULT_VOICE})


@app.get("/api/voices")
def list_voices():
    """Every catalogued voice plus whether it is ready to use right now."""
    voices = []
    for name, entry in VOICE_CATALOG.items():
        voices.append({
            "id": name,
            "label": entry["label"],
            "accent": entry["accent"],
            "gender": entry["gender"],
            "quality": entry["quality"],
            "notes": entry["notes"],
            "installed": is_installed(name),
            "engine": "piper",
            "isDefault": name == DEFAULT_VOICE,
        })
    for name, entry in EDGE_VOICE_CATALOG.items():
        voices.append({
            "id": name,
            "label": entry["label"],
            "accent": entry["accent"],
            "gender": entry["gender"],
            "quality": "neural",
            "notes": entry["notes"],
            # Nothing to download - but it needs internet, which the UI flags.
            "installed": True,
            "engine": "edge",
            "isDefault": False,
        })

    voices.extend(indic_get_voices())

    # Anything dropped into the volume by hand still works - surface it too.
    manual = sorted(os.listdir(VOICES_DIR)) if os.path.isdir(VOICES_DIR) else []
    for filename in manual:
        if filename.endswith(".onnx"):
            name = filename[: -len(".onnx")]
            if name not in VOICE_CATALOG:
                voices.append({
                    "id": name, "label": name, "accent": "", "gender": "",
                    "quality": "custom", "notes": "Installed manually.",
                    # A hand-dropped .onnx in the volume is a Piper model by
                    # definition, so it belongs in the Piper group, not "other".
                    "installed": True, "engine": "piper",
                    "isDefault": name == DEFAULT_VOICE,
                })
    return jsonify({"voices": voices, "defaultVoice": DEFAULT_VOICE})


@app.post("/api/voices/<voice>")
def install_voice(voice: str):
    if voice not in VOICE_CATALOG:
        return jsonify({"error": f"Unknown voice '{voice}'"}), 404
    if is_installed(voice):
        return jsonify({"status": "already-installed", "voice": voice})
    with _install_lock:
        if is_installed(voice):
            return jsonify({"status": "already-installed", "voice": voice})
        try:
            download_voice(voice)
        except Exception as exc:  # noqa: BLE001 - surfaced to the caller verbatim
            return jsonify({"error": f"Download failed: {exc}"}), 502
    return jsonify({"status": "installed", "voice": voice})


def piper_synthesize(text: str, voice: str, speed: float, pitch: float) -> bytes:
    """Run a local Piper voice directly. Used both normally and as a safe
    offline fallback when the optional Edge neural endpoint is unavailable."""
    if not is_installed(voice):
        with _install_lock:
            if not is_installed(voice):
                download_voice(voice)
    model_path = voice_model_path(voice)
    if not is_installed(voice):
        raise RuntimeError(f"Piper voice '{voice}' is not installed")
    length_scale = 1.0 / speed if speed > 0 else 1.0
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as out_f:
        out_path = out_f.name
    try:
        proc = subprocess.run(
            ["piper", "--model", model_path, "--output_file", out_path,
             "--length_scale", str(length_scale)],
            input=text.encode("utf-8"),
            capture_output=True,
            timeout=180,
        )
        if proc.returncode != 0:
            raise RuntimeError(proc.stderr.decode("utf-8", "ignore")[-1000:])
        with open(out_path, "rb") as f:
            wav_bytes = f.read()
        if abs(pitch - 1.0) > 0.01:
            wav_bytes = shift_pitch(wav_bytes, pitch)
        return wav_bytes
    finally:
        if os.path.exists(out_path):
            os.remove(out_path)


@app.post("/api/tts")
def synthesize():

    body = request.get_json(force=True, silent=True) or {}
    text = body.get("text", "")
    voice = body.get("voice") or DEFAULT_VOICE
    speed = float(body.get("speed") or 1.0)
    pitch = float(body.get("pitch") or 1.0)

    # Chatterbox-style paralinguistic tags ([sigh], [laugh], [gasp]...) are written
    # into the narration for Chatterbox only. Piper/Edge would read them aloud as
    # words, so drop them here.
    text = re.sub(r"\s+", " ", re.sub(r"\[[^\]]{1,40}\]", " ", text)).strip()
    if not text.strip():
        text = " "  # piper needs non-empty input; caller sends a short pause

    if voice.startswith(INDIC_PREFIX):
        try:
            wav_bytes = indic_synthesize(text, voice)
        except Exception as exc:  # noqa: BLE001
            return jsonify({"error": f"IndicF5 service unavailable ({exc}). "
                                     "Start it with: docker compose --profile indic up -d tts-indic"}), 502
        if abs(pitch - 1.0) > 0.01:
            wav_bytes = shift_pitch(wav_bytes, pitch)
        return Response(wav_bytes, mimetype="audio/wav")

    # Edge voices are cloud-side, so there is nothing to install and no local
    # model path - route them out before any of the Piper file handling.
    if voice.startswith(EDGE_PREFIX):
        if OFFLINE_MODE:
            return jsonify({"error": f"Edge TTS voice '{voice}' requires internet access to Microsoft's "
                                     "hosted service, which OFFLINE_MODE=true explicitly disallows. "
                                     "Pick a local Piper voice instead."}), 403
        if voice not in EDGE_VOICE_CATALOG:
            return jsonify({"error": f"Unknown Edge voice '{voice}'"}), 404
        try:
            wav_bytes = edge_synthesize(text, voice, speed)
        except Exception as exc:  # noqa: BLE001
            # Edge is the quality-first path, but a transient internet/endpoint
            # failure must not turn an otherwise complete episode into silent
            # narration. Fall back to the local high-quality Piper voice.
            app.logger.exception("Edge TTS failed for voice %s; using Piper fallback", voice)
            try:
                wav_bytes = piper_synthesize(text, PIPER_FALLBACK_VOICE, speed, pitch)
                return Response(wav_bytes, mimetype="audio/wav")
            except Exception as fallback_exc:
                detail = f"{type(exc).__name__}: {exc}"
                return jsonify({"error": f"Edge TTS failed ({detail}); Piper fallback also failed: {fallback_exc}"}), 502
        if abs(pitch - 1.0) > 0.01:
            wav_bytes = shift_pitch(wav_bytes, pitch)
        return Response(wav_bytes, mimetype="audio/wav")

    # Local Piper path.
    try:
        wav_bytes = piper_synthesize(text, voice, speed, pitch)
        return Response(wav_bytes, mimetype="audio/wav")
    except Exception as exc:
        return jsonify({"error": str(exc)}), 500



def preload():
    """Install the default voice plus anything in TTS_PRELOAD_VOICES."""
    wanted = [DEFAULT_VOICE]
    extra = os.environ.get("TTS_PRELOAD_VOICES", "").strip()
    if extra:
        wanted += [v.strip() for v in extra.split(",") if v.strip()]
    for voice in wanted:
        if voice in VOICE_CATALOG and not is_installed(voice):
            try:
                app.logger.info("Preloading voice %s", voice)
                download_voice(voice)
            except Exception as exc:  # noqa: BLE001
                app.logger.warning("Preload of '%s' failed: %s", voice, exc)


if __name__ == "__main__":
    os.makedirs(VOICES_DIR, exist_ok=True)
    # In a background thread so the server answers /health immediately - the
    # backend's startup check should not have to wait on a 110 MB download.
    threading.Thread(target=preload, daemon=True).start()
    app.run(host="0.0.0.0", port=5002)
