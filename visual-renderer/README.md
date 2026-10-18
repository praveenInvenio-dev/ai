# Visual Renderer service

Internal-only service used by the Concept Explainer backend. It accepts structured scene data and returns PNG keyframes (one per narration sentence).
Not published to the host. `GET /health` reports which engines are really available; the backend only plans engines that are up.

- `THREE_JS`: fixed Three.js templates in headless Chromium chosen by `sceneKind` (orbit, molecule, crystal lattice, layers, axes) with a progressive
  reveal; unknown kinds use a generic node chain. The last frame always shows every element.
- `RIVE`: needs a `.riv` file. Fails on load error, timeout, or a blank canvas. The runtime wasm is served from `node_modules`; all other network is blocked.
- `MOTION_CANVAS`: not available (no supported headless exporter). Reported as unavailable and rejected.

No model-generated JavaScript is executed; templates are fixed and driven by validated text/labels. `RENDER_CONCURRENCY` (default 2) limits parallel Chromium runs.
Smoke test: `npm run smoke -- http://localhost:5020`.
