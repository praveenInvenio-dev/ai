# Increment 53 — Visual orchestration and PixiJS renderer

## Included
- Added PixiJS 8 to the isolated `visual-renderer` service and a deterministic particle/flow scene template. The renderer uses bundled local assets only, runs in the existing Playwright/Chromium sandbox, and rejects identical output frames.
- Added `PIXIJS` to renderer health/capability reporting and `/render-frames` routing.
- Added PixiJS to the Java engine registry/scorer. It is eligible only when the renderer health endpoint reports it available and the scene signals 2D particle/charge/wave/network-packet motion.
- Connected renderer capability discovery to the Concept Studio Visual Intelligence preview so availability is not hardcoded. The UI continues to explain unavailable engines and fallback suggestions.
- Connected the PixiJS engine through the existing per-scene rendering pipeline; existing renderer errors continue to fall back to the native diagram path.
- Updated engine status warnings and unit-test expectations.
- Concept lessons remain programmatic-only: Wan/ComfyUI image generation remains excluded from the Concept Explainer path. Separate story/cartoon paths are unchanged.

## Current boundaries (do not overstate)
- PixiJS currently renders progressive PNG frames through `/render-frames`; its dedicated continuous PixiJS MP4 clip endpoint is not enabled yet. The existing video composer can use these frames with the normal scene timing, but this is not equivalent to a continuous particle simulation for the entire narration.
- Visual Intelligence currently uses deterministic topic/scene heuristics and live renderer capability checks. A model-generated structured concept graph / Ollama visual-plan pass is not fully wired into this increment.
- Quality checks remain existing renderer validation plus PNG/frame-count checks and fallback handling; no automated semantic correctness grader has been added.
- Full build verification has not been performed in this environment: Maven is unavailable, and npm dependencies/Chromium render execution were not installed here. Node syntax and ZIP integrity checks are the available validations.

## Deployment
Rebuild the renderer image so `pixi.js` is installed in the image:

```bash
docker compose build --no-cache visual-renderer
docker compose up -d visual-renderer backend frontend
```

Then check `GET /health` on the visual-renderer service and confirm `engines.PIXIJS` is `true`. If false, the Visual Intelligence planner must treat PixiJS as unavailable and use an eligible fallback.
