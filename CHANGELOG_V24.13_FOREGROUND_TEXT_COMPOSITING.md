# Increment 4 — Foreground-mask text compositing prototype

## Implemented

- Added `video-worker/foreground_text_compositor.py`, an optional local CLI that estimates a foreground alpha matte with `rembg`.
- Composites animated text between the source background and segmented foreground, preserving the source audio.
- Supports time range, text position, font size, RGB color, and fade/slide/floating/pop/pulse animations.
- Added optional setup, usage, limitations, and troubleshooting documentation.

## Safety and compatibility

- The standard editor render path and existing API/schema are unchanged.
- Segmentation dependencies are optional and isolated from the default worker environment.
- No mandatory model download or GPU requirement is added to normal renders.

## Verification status

- Python syntax compilation and archive integrity are checked.
- The actual segmentation render requires the optional dependencies, FFmpeg, and cached/downloadable rembg weights; runtime output has not been verified in this environment.
- The CLI is not yet wired to the Angular editor's timeline/render-job API. Full editor integration remains a follow-up item; do not describe this increment as automatic in-editor depth compositing.
