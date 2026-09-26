"""
Video analysis worker for the AI Video Editor.

Exposes the same "POST JSON -> JSON" contract style as the other side services.
Runs ffprobe for container metadata, PySceneDetect for shot boundaries, and
OpenCV for the per-shot measurements the edit planner ranks footage on.

Design notes that matter on a 12-core / 2 GB-VRAM box:

  - Everything runs on CPU. There is no model here at all; scene detection is a
    histogram/threshold algorithm and quality scoring is Laplacian variance plus
    a few means. That keeps the whole stage in the seconds-per-clip range.
  - Analysis runs against the 480p PROXY, not the original. A 4K source gives
    identical scene boundaries at a fraction of the decode cost, and every
    measurement here is scale-invariant (variance is normalised, means are
    per-pixel).
  - Frames are SAMPLED, not decoded exhaustively. A shot's character is
    established by a handful of frames; decoding all 300 of them to average the
    same number is wasted CPU.
"""
import os
import subprocess
import json
import math
import threading

import cv2
import numpy as np
from flask import Flask, request, jsonify

app = Flask(__name__)

ANALYSIS_THREADS = int(os.environ.get("VIDEO_ANALYSIS_THREADS", "8"))
PROXY_HEIGHT = int(os.environ.get("VIDEO_PROXY_HEIGHT", "480"))

# Frames sampled per detected shot for the quality measurements.
FRAMES_PER_SCENE = 5

# PySceneDetect content-threshold. Lower = more cuts detected. 27 is the library
# default and errs toward missing subtle cuts rather than inventing them, which
# is the right bias here: a false cut splits a shot mid-motion and the planner
# then treats two halves of one movement as separate candidates.
SCENE_THRESHOLD = float(os.environ.get("SCENE_THRESHOLD", "27.0"))

# Shots shorter than this are merged into their neighbour. Below ~0.4s there is
# no usable footage to cut to, and flicker or a camera flash can otherwise
# register as a "shot".
MIN_SCENE_SECONDS = 0.4

_lock = threading.Lock()


# ---------------------------------------------------------------------------
# ffprobe
# ---------------------------------------------------------------------------

def probe(path: str) -> dict:
    """Container/stream metadata. Also the decodability check: a file that
       ffprobe cannot read is rejected here rather than at render time."""
    proc = subprocess.run(
        ["ffprobe", "-v", "error", "-print_format", "json",
         "-show_format", "-show_streams", path],
        capture_output=True, timeout=120,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"ffprobe failed: {proc.stderr.decode('utf-8', 'ignore')[-300:]}")
    data = json.loads(proc.stdout.decode("utf-8"))

    video = next((s for s in data.get("streams", []) if s.get("codec_type") == "video"), None)
    audio = next((s for s in data.get("streams", []) if s.get("codec_type") == "audio"), None)
    if video is None:
        raise RuntimeError("No video stream found in that file.")

    # avg_frame_rate is a rational string like "30000/1001".
    fps = 0.0
    raw_fps = video.get("avg_frame_rate", "0/0")
    try:
        num, den = raw_fps.split("/")
        fps = float(num) / float(den) if float(den) else 0.0
    except (ValueError, ZeroDivisionError):
        fps = 0.0

    duration = float(data.get("format", {}).get("duration") or video.get("duration") or 0.0)

    return {
        "durationSec": duration,
        "width": int(video.get("width") or 0),
        "height": int(video.get("height") or 0),
        "fps": round(fps, 3),
        "videoCodec": video.get("codec_name"),
        "audioCodec": audio.get("codec_name") if audio else None,
        "hasAudio": audio is not None,
        "sizeBytes": int(data.get("format", {}).get("size") or 0),
        "rotation": _rotation(video),
    }


def _rotation(video_stream: dict) -> int:
    """Phone footage is often stored landscape with a rotation tag. Ignoring it
       means treating a portrait clip as landscape and cropping the wrong axis."""
    for side in video_stream.get("side_data_list", []) or []:
        if "rotation" in side:
            return int(side["rotation"])
    tags = video_stream.get("tags", {}) or {}
    try:
        return int(tags.get("rotate", 0))
    except ValueError:
        return 0


# ---------------------------------------------------------------------------
# proxy
# ---------------------------------------------------------------------------

def make_proxy(src: str, dst: str) -> None:
    """
    480p proxy used for analysis and preview.

    ultrafast/CRF 30 on purpose: this file is never shown to anyone. Its only
    jobs are to decode quickly and preserve scene boundaries, and neither
    benefits from a slower preset.
    """
    subprocess.run(
        ["ffmpeg", "-y", "-i", src,
         "-vf", f"scale=-2:{PROXY_HEIGHT}",
         "-c:v", "libx264", "-preset", "ultrafast", "-crf", "30",
         "-threads", str(ANALYSIS_THREADS),
         "-c:a", "aac", "-b:a", "96k",
         dst],
        capture_output=True, timeout=1800, check=True,
    )


# ---------------------------------------------------------------------------
# scene detection
# ---------------------------------------------------------------------------

def detect_scenes(path: str, duration: float) -> list:
    from scenedetect import open_video, SceneManager
    from scenedetect.detectors import ContentDetector

    video = open_video(path)
    manager = SceneManager()
    manager.add_detector(ContentDetector(threshold=SCENE_THRESHOLD))
    manager.detect_scenes(video, show_progress=False)
    scenes = [(s.get_seconds(), e.get_seconds()) for s, e in manager.get_scene_list()]

    # A clip with no detected cut is one continuous shot, not zero shots.
    if not scenes:
        return [(0.0, duration)]

    return merge_short(scenes)


def merge_short(scenes: list) -> list:
    """Fold sub-minimum shots into the previous one."""
    merged = []
    for start, end in scenes:
        if merged and (end - start) < MIN_SCENE_SECONDS:
            merged[-1] = (merged[-1][0], end)
        else:
            merged.append((start, end))
    return merged


# ---------------------------------------------------------------------------
# per-scene measurement
# ---------------------------------------------------------------------------

def measure_scene(cap, fps: float, start: float, end: float) -> dict:
    """
    Samples frames across a shot and returns the measurements the planner ranks on.

    motion   mean absolute difference between consecutive samples, 0..1
    blur     Laplacian variance, normalised - low means soft or out of focus
    bright   mean luma 0..1
    """
    if fps <= 0:
        fps = 25.0
    first, last = int(start * fps), max(int(end * fps) - 1, int(start * fps))
    if last <= first:
        last = first + 1

    idxs = np.linspace(first, last, FRAMES_PER_SCENE).astype(int)
    grays, blurs, brights = [], [], []

    for idx in idxs:
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(idx))
        ok, frame = cap.read()
        if not ok:
            continue
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        grays.append(gray)
        blurs.append(cv2.Laplacian(gray, cv2.CV_64F).var())
        brights.append(float(gray.mean()) / 255.0)

    if not grays:
        return {"motion": 0.0, "blur": 0.0, "brightness": 0.0}

    motions = []
    for a, b in zip(grays, grays[1:]):
        if a.shape == b.shape:
            motions.append(float(np.abs(a.astype(np.int16) - b.astype(np.int16)).mean()) / 255.0)

    return {
        "motion": round(float(np.mean(motions)) if motions else 0.0, 4),
        # 500 is roughly the Laplacian variance of a well-focused 480p frame;
        # dividing by it puts "sharp" near 1.0 and lets the score clamp above.
        "blur": round(min(float(np.mean(blurs)) / 500.0, 1.0), 4),
        "brightness": round(float(np.mean(brights)), 4),
    }


def quality_score(m: dict) -> float:
    """
    Composite 0..1 used to prefer good footage.

    Sharpness dominates because a soft shot cannot be rescued, while brightness
    is scored as distance from mid-grey - both crushed blacks and blown
    highlights are failures, so a simple "brighter is better" would rank an
    overexposed shot top.
    """
    sharp = m["blur"]
    exposure = 1.0 - min(abs(m["brightness"] - 0.5) / 0.5, 1.0)
    # Some motion is life; a lot is camera shake or a whip pan with no usable frame.
    motion_penalty = max(0.0, m["motion"] - 0.25) * 1.5
    score = 0.6 * sharp + 0.4 * exposure - motion_penalty
    return round(max(0.0, min(1.0, score)), 4)


def shot_type(m: dict) -> str:
    """Coarse label from motion alone. Real shot-type classification needs a
       vision model, which will not fit alongside ComfyUI on a 2 GB card, so
       this reports only what is actually measurable."""
    if m["motion"] > 0.35:
        return "action"
    if m["motion"] > 0.12:
        return "moving"
    return "static"


# ---------------------------------------------------------------------------
# audio
# ---------------------------------------------------------------------------

def audio_levels(path: str, scenes: list) -> list:
    """Mean volume per shot via ffmpeg's volumedetect, used to spot silence."""
    levels = []
    for start, end in scenes:
        proc = subprocess.run(
            ["ffmpeg", "-v", "error", "-ss", str(start), "-t", str(max(end - start, 0.1)),
             "-i", path, "-af", "volumedetect", "-f", "null", "-"],
            capture_output=True, timeout=120,
        )
        text = proc.stderr.decode("utf-8", "ignore")
        mean_db = None
        for line in text.splitlines():
            if "mean_volume:" in line:
                try:
                    mean_db = float(line.split("mean_volume:")[1].strip().split()[0])
                except (IndexError, ValueError):
                    pass
        # -91 dB is ffmpeg's floor for digital silence.
        levels.append(0.0 if mean_db is None or mean_db <= -90 else
                      round(min(1.0, max(0.0, (mean_db + 60.0) / 60.0)), 4))
    return levels


# ---------------------------------------------------------------------------
# endpoints
# ---------------------------------------------------------------------------

@app.get("/health")
def health():
    return jsonify({"status": "ok", "threads": ANALYSIS_THREADS, "proxyHeight": PROXY_HEIGHT})


@app.post("/api/probe")
def api_probe():
    body = request.get_json(force=True, silent=True) or {}
    path = body.get("path")
    if not path or not os.path.exists(path):
        return jsonify({"error": f"File not found: {path}"}), 404
    try:
        return jsonify(probe(path))
    except Exception as exc:  # noqa: BLE001
        return jsonify({"error": str(exc)}), 422


@app.post("/api/proxy")
def api_proxy():
    body = request.get_json(force=True, silent=True) or {}
    src, dst = body.get("source"), body.get("target")
    if not src or not os.path.exists(src):
        return jsonify({"error": f"File not found: {src}"}), 404
    try:
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        make_proxy(src, dst)
        return jsonify({"status": "ok", "target": dst})
    except subprocess.CalledProcessError as exc:
        return jsonify({"error": exc.stderr.decode("utf-8", "ignore")[-300:]}), 500


@app.post("/api/analyze")
def api_analyze():
    """
    Full analysis of one clip: shots plus per-shot measurements.

    Serialised with a lock. Two concurrent analyses on this box contend for the
    same cores and both finish later than if they had queued - the same reason
    ComfyUI runs one prompt at a time.
    """
    body = request.get_json(force=True, silent=True) or {}
    path = body.get("path")
    if not path or not os.path.exists(path):
        return jsonify({"error": f"File not found: {path}"}), 404

    with _lock:
        try:
            meta = probe(path)
            scenes = detect_scenes(path, meta["durationSec"])
            levels = audio_levels(path, scenes) if meta["hasAudio"] else [0.0] * len(scenes)

            cap = cv2.VideoCapture(path)
            fps = cap.get(cv2.CAP_PROP_FPS) or meta["fps"]
            out = []
            for i, (start, end) in enumerate(scenes):
                m = measure_scene(cap, fps, start, end)
                out.append({
                    "index": i,
                    "startSec": round(start, 3),
                    "endSec": round(end, 3),
                    "motionScore": m["motion"],
                    "blurScore": m["blur"],
                    "brightness": m["brightness"],
                    "audioRms": levels[i] if i < len(levels) else 0.0,
                    "qualityScore": quality_score(m),
                    "shotType": shot_type(m),
                    # Real speech detection is Whisper's job; this only reports
                    # that the shot is not silent, and says so by its name.
                    "hasAudioSignal": (levels[i] if i < len(levels) else 0.0) > 0.15,
                })
            cap.release()

            return jsonify({"metadata": meta, "scenes": out, "analyzer": "video-worker/1"})
        except Exception as exc:  # noqa: BLE001
            app.logger.exception("Analysis failed for %s", path)
            return jsonify({"error": str(exc)}), 500


if __name__ == "__main__":
    cv2.setNumThreads(ANALYSIS_THREADS)
    app.run(host="0.0.0.0", port=5010)
