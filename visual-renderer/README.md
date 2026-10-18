# Visual Renderer service

Internal-only service used by the Concept Explainer backend. It accepts structured scene data and returns a small set of PNG keyframes rendered by the selected engine. It is not published to the host.

- `MOTION_CANVAS`: currently fails explicitly with a diagnostic. The previous implementation invoked `@motion-canvas/cli`, which returns npm 404 and is not the supported official v3 export path. The official Motion Canvas path uses its Vite plugin and exporters; a supported headless player/export harness still needs to be integrated. The backend must report fallback rather than claiming Motion Canvas rendered the scene.
- `THREE_JS`: creates an actual Three.js WebGL scene in headless Chromium and captures successive animation times.
- `RIVE`: requires a `.riv` asset; missing assets are rejected. A successful response is required before the backend marks the scene as Rive-rendered.

No model-generated JavaScript is executed. The renderer constructs its own fixed templates from plain text and validated scene data.
