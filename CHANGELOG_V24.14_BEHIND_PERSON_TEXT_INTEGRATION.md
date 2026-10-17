# Increment 5 — In-editor behind-person text integration

## Changes

- Added `text_behind_person` as a backward-compatible overlay type in the existing finishing model.
- Added the "Behind person (AI mask)" option to the Video Editor overlay form, reusing text timing, positioning, color, and animation settings.
- Added chat command support for phrases such as `add floating text JVM at 5 seconds behind person`.
- Added internal video-worker endpoint `/api/depth-text` with shared-volume path validation.
- Wired the finishing step to invoke the worker compositor before ordinary overlays and voiceover, then continue the standard finishing pass.
- Failed depth-compositing operations now produce a diagnostic note and do not discard other overlays or voiceover.
- Kept the normal rendering path unchanged for projects that do not use this overlay type.
- Added editor-command regression coverage and updated the feature parity document.

## Verification

- Editor command regression checks: 87/87 passed.
- Python syntax compilation: `server.py` and `foreground_text_compositor.py` passed.
- ZIP integrity: passed; no original archive entries removed.
- Full Angular build, Maven/JUnit build, Docker rebuild, and real segmentation footage test were not available in this environment.
