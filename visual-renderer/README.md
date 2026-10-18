# Visual Renderer service

Internal-only service used by the Concept Explainer backend. It accepts structured scene data and returns a small set of PNG keyframes rendered by the selected engine. It is not published to the host.

- `MOTION_CANVAS`: builds a temporary Motion Canvas project from escaped scene text, renders an MP4 with the Motion Canvas CLI, and samples progressive frames with FFmpeg.
- `THREE_JS`: creates an actual Three.js WebGL scene in headless Chromium and captures successive animation times.
- `RIVE`: requires a `.riv` asset; missing assets are rejected. A successful response is required before the backend marks the scene as Rive-rendered.

No model-generated JavaScript is executed. The renderer constructs its own fixed templates from plain text and validated scene data.
