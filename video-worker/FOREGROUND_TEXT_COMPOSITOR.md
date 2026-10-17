# Depth-aware floating text (foreground segmentation)

`foreground_text_compositor.py` implements the core cinematic compositing effect: it segments the foreground person, draws animated text over the original background, then composites the person over the text. This produces the visual order **background → text → foreground person**.

## Requirements

- Python 3.10+
- FFmpeg available on PATH (or `--ffmpeg` path)
- `opencv-python`, `numpy`, `Pillow`, `rembg`
- First use of the selected rembg model may download model weights. Pre-download models in restricted/offline deployments.
- CPU inference works but may be slow. GPU acceleration depends on the installed ONNX Runtime provider and available hardware.

Install the optional dependencies in a separate environment so the regular video worker's lightweight dependencies remain unchanged:

```bash
python -m venv .venv-depth-text
# Linux/macOS
source .venv-depth-text/bin/activate
# Windows PowerShell
# .venv-depth-text\Scripts\Activate.ps1
pip install opencv-python numpy pillow rembg
```

## Example

```bash
python video-worker/foreground_text_compositor.py \
  --input input.mp4 \
  --output output-depth-text.mp4 \
  --text "JAVA VIRTUAL MACHINE" \
  --start 2.0 --duration 4.5 \
  --x 50 --y 42 \
  --font-size 80 --color 255,214,10 \
  --animation float
```

Available animations: `fade`, `none`, `slide_up`, `slide_left`, `float`, `pop`, `pulse`.

## Important limitations

- This is a local rendering utility, not yet a button wired into the Angular editor's existing render-job API. The existing standard export pipeline remains unchanged and does not automatically invoke it.
- The default `u2net` model is general-purpose foreground segmentation, not guaranteed person-only segmentation. Hair, hands, motion blur, transparent objects, and busy backgrounds can produce matte artifacts.
- It processes frames individually and can be expensive on long/high-resolution footage. Test on a short segment first; use a proxy or trim before processing long footage.
- It preserves the original audio by muxing the output video with the source audio. The requested overlay time is measured against the source video's timeline.
- Review the rembg model's license and deployment terms before distributing model weights or a bundled deployment.
- The output is encoded as H.264 MP4. The regular editor pipeline's render/export behavior is unchanged.

## Troubleshooting

- `No module named rembg`: install the optional dependencies in the dedicated environment above.
- Model download fails: download/cache the selected rembg model in an environment with access, then run offline with the cached model.
- Poor subject edges: try a different supported rembg model and test with a clean, well-lit subject against a contrasting background.
- Slow processing: use a short segment, lower-resolution source, and/or a supported GPU ONNX Runtime setup.
