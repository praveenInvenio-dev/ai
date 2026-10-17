#!/usr/bin/env python3
"""
Depth-aware floating text compositor for AI Story Studio.

Requires: opencv-python, numpy, pillow, rembg (CPU or GPU extra).
Creates a text layer between the original background and a segmented foreground
person. This is an offline CLI helper so segmentation does not silently become
a mandatory dependency of the normal editor/render path.
"""
import argparse
import os
import subprocess
import tempfile
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from rembg import new_session, remove


def load_font(size: int):
    candidates = [
        os.environ.get("STUDIO_OVERLAY_FONT", ""),
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf",
        "C:/Windows/Fonts/arialbd.ttf",
    ]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            try:
                return ImageFont.truetype(candidate, size=size)
            except OSError:
                pass
    return ImageFont.load_default()


def make_text_layer(width, height, text, x_percent, y_percent, font_size, color, animation, t, start, duration):
    layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    font = load_font(font_size)
    box = draw.textbbox((0, 0), text, font=font, stroke_width=max(1, font_size // 18))
    tw, th = box[2] - box[0], box[3] - box[1]
    x = int(width * x_percent / 100 - tw / 2)
    y = int(height * y_percent / 100 - th / 2)
    elapsed = max(0.0, t - start)
    if animation == "float":
        y += int(np.sin(elapsed * 2.4) * height * 0.018)
    elif animation == "slide_up":
        y += int(height * 0.12 * max(0, 1 - elapsed / 0.45))
    elif animation == "slide_left":
        x += int(width * 0.18 * max(0, 1 - elapsed / 0.45))
    elif animation == "pulse":
        x += int(np.sin(elapsed * 5) * width * 0.006)
    elif animation == "pop":
        scale = min(1.0, max(0.05, elapsed / 0.18))
        font = load_font(max(8, int(font_size * scale)))
        box = draw.textbbox((0, 0), text, font=font, stroke_width=max(1, font_size // 18))
        tw, th = box[2] - box[0], box[3] - box[1]
        x = int(width * x_percent / 100 - tw / 2)
        y = int(height * y_percent / 100 - th / 2)
    alpha = 255
    fade = min(0.3, duration / 3.0)
    if animation == "fade" or animation == "pop":
        alpha = int(255 * min(1.0, max(0, elapsed / max(fade, 0.01)),
                              max(0, (start + duration - t) / max(fade, 0.01))))
    rgba = tuple(int(c) for c in color.split(",")) if "," in color else (255, 255, 255)
    draw.text((x, y), text, font=font, fill=(*rgba[:3], alpha),
              stroke_width=max(2, font_size // 22), stroke_fill=(0, 0, 0, alpha))
    return np.asarray(layer)


def composite(args):
    from rembg import new_session, remove

    cap = cv2.VideoCapture(args.input)
    if not cap.isOpened():
        raise RuntimeError(f"Could not open input video: {args.input}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 25.0
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    if width <= 0 or height <= 0:
        cap.release()
        raise RuntimeError("Input video has invalid dimensions")
    start_frame = max(0, int(args.start * fps))
    end_frame = max(start_frame + 1, int((args.start + args.duration) * fps))
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="studio-depth-text-") as td:
        silent = str(Path(td) / "composited-silent.mp4")
        writer = cv2.VideoWriter(silent, cv2.VideoWriter_fourcc(*"mp4v"), fps, (width, height))
        if not writer.isOpened():
            cap.release()
            raise RuntimeError("Could not initialize output video writer")
        session = new_session(args.model)
        index = 0
        processed = 0
        try:
            while True:
                ok, frame = cap.read()
                if not ok:
                    break
                if start_frame <= index < end_frame:
                    rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                    # rembg returns RGBA with an estimated subject alpha matte.
                    cutout = remove(rgb, session=session, only_mask=False, post_process_mask=True)
                    rgba = np.asarray(cutout)
                    if rgba.ndim != 3 or rgba.shape[2] < 4:
                        raise RuntimeError("Segmentation model did not return an alpha matte")
                    fg_rgb = rgba[:, :, :3].astype(np.float32)
                    alpha = rgba[:, :, 3:4].astype(np.float32) / 255.0
                    t = index / fps
                    text_rgba = make_text_layer(
                        width, height, args.text, args.x, args.y, args.font_size,
                        args.color, args.animation, t, args.start, args.duration
                    ).astype(np.float32)
                    text_rgb = text_rgba[:, :, :3]
                    text_a = (text_rgba[:, :, 3:4] / 255.0)
                    bg_rgb = rgb.astype(np.float32)
                    # Composite order: text over background, then person over text.
                    behind_text = text_rgb * text_a + bg_rgb * (1.0 - text_a)
                    result = fg_rgb * alpha + behind_text * (1.0 - alpha)
                    frame = cv2.cvtColor(np.clip(result, 0, 255).astype(np.uint8), cv2.COLOR_RGB2BGR)
                    processed += 1
                writer.write(frame)
                index += 1
        finally:
            cap.release()
            writer.release()
        if processed == 0:
            raise RuntimeError("Overlay time range did not overlap the video.")
        # Keep original audio by muxing the composited video with the source audio.
        cmd = [
            args.ffmpeg, "-y", "-i", silent, "-i", args.input,
            "-map", "0:v:0", "-map", "1:a:0?", "-c:v", "libx264",
            "-preset", args.preset, "-crf", str(args.crf), "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart",
            args.output
        ]
        run = subprocess.run(cmd, capture_output=True, text=True, timeout=args.timeout)
        if run.returncode:
            raise RuntimeError("FFmpeg mux failed: " + run.stderr[-1500:])
    return processed


def main():
    p = argparse.ArgumentParser(description="Render animated text behind a segmented foreground person.")
    p.add_argument("--input", required=True, help="Source video path")
    p.add_argument("--output", required=True, help="Output MP4 path")
    p.add_argument("--text", required=True, help="Text to place behind the person")
    p.add_argument("--start", type=float, default=0.0, help="Overlay start time in seconds")
    p.add_argument("--duration", type=float, default=3.0, help="Overlay duration in seconds")
    p.add_argument("--x", type=float, default=50.0, help="Horizontal center in percent")
    p.add_argument("--y", type=float, default=45.0, help="Vertical center in percent")
    p.add_argument("--font-size", type=int, default=72)
    p.add_argument("--color", default="255,214,10", help="RGB color, e.g. 255,214,10")
    p.add_argument("--animation", choices=["fade", "none", "slide_up", "slide_left", "float", "pop", "pulse"], default="float")
    p.add_argument("--model", default=os.environ.get("STUDIO_REMBG_MODEL", "u2net"))
    p.add_argument("--ffmpeg", default=os.environ.get("FFMPEG_BINARY", "ffmpeg"))
    p.add_argument("--preset", default="medium")
    p.add_argument("--crf", type=int, default=20)
    p.add_argument("--timeout", type=int, default=7200)
    args = p.parse_args()
    args.start = max(0.0, args.start)
    args.duration = min(3600.0, max(0.1, args.duration))
    args.x = min(100.0, max(0.0, args.x))
    args.y = min(100.0, max(0.0, args.y))
    args.font_size = min(240, max(12, args.font_size))
    args.crf = min(35, max(0, args.crf))
    count = composite(args)
    print(f"SUCCESS: rendered {count} frames with depth-aware text to {args.output}")


if __name__ == "__main__":
    main()
