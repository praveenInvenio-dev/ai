# Visual Renderer service

Internal-only service used by the Concept Explainer backend. It accepts structured scene data and returns PNG keyframes (one per narration sentence).
Not published to the host. `GET /health` reports which engines are really available; the backend only plans engines that are up.

- `MOTION_CANVAS`: a PREBUILT Motion Canvas player project (`motion/`, built in the Docker image). The scene (an animated step flow: boxes appear, arrows draw,
  a packet travels) is driven by plain variables (title, labels, background, orientation). Nothing generated is compiled. Reported available only when
  `motion/dist` exists.
- `THREE_JS`: fixed Three.js templates in headless Chromium chosen by `sceneKind` (orbit, molecule, crystal lattice, layers, axes, lanes = parallel work vs
  one-at-a-time); unknown kinds use a generic node chain. The last frame always shows every element.
- `RIVE`: needs a `.riv` file (uploaded per lesson, or matched from `rive-library/`). Fails on load error, timeout, or a blank canvas. The runtime wasm is served
  from `node_modules`; all other network is blocked.

Every engine takes the lesson's `theme` so its background matches the rest of the video. No model-generated JavaScript is executed.
`RENDER_CONCURRENCY` (default 2) limits parallel Chromium runs. Smoke test: `npm run smoke -- http://localhost:5020`.
