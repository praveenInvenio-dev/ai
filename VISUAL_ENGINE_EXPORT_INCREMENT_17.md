# Increment 17 — Concept Explainer production visual-engine adapters

## What changed

- Removed the lesson-wide topic from visual engine keyword routing. Engine choice now uses the individual scene title, narration, visual description and code, reducing the tendency to route every scene because of the overall lesson topic.
- Added a `visual-renderer` service with actual engine runtimes: Motion Canvas CLI, Three.js/WebGL through headless Chromium, and the Rive canvas runtime.
- Added a backend `VisualEngineRenderClient` that sends structured scene data (never executable model-generated source) to the isolated renderer service and accepts PNG frames only when the service confirms `RENDERED`.
- Integrated successful exported frames into the existing sentence-synchronized scene pipeline. Actual renderer/status are set only after valid frames are received.
- On renderer failure, the scene falls back to the existing native diagram renderer and records a concrete reason in the scene diagnostics and job warnings.
- Added `STUDIO_VISUAL_RENDERER_BASE_URL` and the renderer service to Docker Compose. The backend is not hard-dependent on renderer health, so a renderer startup issue does not prevent the application from starting.

## Routing rules

- Motion Canvas: ordered actions, cause/effect, code, workflow, state transitions and explanatory sequences.
- Three.js: explicit spatial/3D/camera-orbit/depth scenes.
- Rive: only when the scene plan names a `.riv` asset. A missing/invalid asset fails honestly and falls back; it is never reported as a Rive render.
- The engines are chosen per scene. A lesson does not need to use all three if the content does not suit them.

## Important implementation detail

The renderer exports progressive keyframes consumed by the existing narration-synchronized compositor. It does not yet replace the full clip compositor with a continuous 30-fps engine-native animation stream. Motion Canvas renders a short engine-native clip and samples frames; Three.js and Rive capture time-progressive engine frames. This is an incremental production adapter, not a claim that every output is a fully continuous engine-native video.

## Build and verification status

- `node --check visual-renderer/server.mjs`: passed.
- `docker-compose.yml` YAML parse: passed.
- `docker-compose.gpu.yml` is a Compose-specific override file using `!override`; generic PyYAML does not recognize that Docker Compose tag, so this check is not applicable to that file.
- Full Docker build, Maven compilation, Motion Canvas CLI render, Three.js browser render and Rive `.riv` render could not be executed in this environment (Docker unavailable; npm installation timed out). Treat this as source-implemented but not end-to-end validated until the commands in the deployment notes pass on the target server.
