# Increment 54 — Complete Visual Intelligence and timed PixiJS clips

This increment continues from Increment 53 and keeps all new controls inside the redesigned left-menu Concept Studio. It does not introduce a parallel video-generation pipeline.

## Implemented
- Ollama structured planning for Concept Studio previews: learning goal, visual strategy, a bounded concept graph (nodes and relationships), and 3–7 validated scene ideas with visual intent, preferred engine, eligible alternatives, timing guidance and reasons.
- Deterministic fallback plan when Ollama is unavailable or returns an invalid/ineligible plan. Model-produced content is treated as data; generated JavaScript is never executed.
- Renderer capability gates: unavailable Three.js, PixiJS and Motion Canvas engines are removed from model-produced scene recommendations. Manim remains gated by the production Manim health/spec checks.
- Concept Studio UI now displays the concept map and its relationships in the existing left-menu workspace, alongside engine options and scene strategies.
- Continuous PixiJS MP4 export at 30 fps, with particle movement across the full clip and narration-start-based label reveals. FFmpeg creates H.264 MP4 output.
- PixiJS MP4 capability is reported independently from still-frame capability through `/health` (`clips.PIXIJS`).
- Production `attachMotionClip` can attach a PixiJS MP4 when the scene was successfully rendered by PixiJS and clip export is available. It uses sentence start timings, existing theme and aspect ratio. If clip export fails, the PixiJS still frames are retained and the warning/status is explicit.
- Renderer smoke coverage for PixiJS frame variation and MP4 signature when FFmpeg is available.
- Renderer health includes in-memory per-engine frame/clip success, failure, last duration and average duration telemetry. Render responses include frame-count/distinct-frame validation metadata. Metrics reset when the renderer process restarts.
- Renderer concurrency gate now wraps the PixiJS/Three.js clip work itself, rather than only awaiting an already-started render promise.

## Preserved behavior
- Existing Concept Studio route and legacy Concept Explainer route are retained.
- Manim, Three.js, Motion Canvas, Rive and native diagram routing remain governed by the existing renderer registry and per-scene router.
- Wan/ComfyUI-generated imagery remains excluded from Concept Explainer lessons; separate story/cartoon workflows are unchanged.

## Verification status
- Passed: `node --check visual-renderer/server.mjs` and `node --check visual-renderer/smoke.mjs`.
- ZIP integrity is checked after packaging.
- Not verified in this environment: Maven/JUnit, Angular production build, Docker image build, browser-based PixiJS render and end-to-end generated lesson. Maven and Docker are unavailable; npm dependency installation timed out, so the live renderer smoke suite could not run here.
- The quality checks are deterministic technical checks (frame presence/variation, output signature, render status and timings), not a semantic or pedagogical correctness grader.

## Deployment
Rebuild the renderer image so dependencies and the updated server are present:

```bash
docker compose build --no-cache visual-renderer backend frontend
docker compose up -d visual-renderer backend frontend
```

Then verify `GET /health` returns `engines.PIXIJS=true` and `clips.PIXIJS=true`, and run:

```bash
node smoke.mjs http://localhost:5020
```

The MP4 route is `POST /render-clip` with `engine: "PIXIJS"`, labels, `starts` in seconds, `total`, width, height and scene kind. Only the fixed PixiJS template is rendered; no model-generated code is evaluated.
