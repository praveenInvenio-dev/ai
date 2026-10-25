# Increment 52 — Visual Intelligence in Concept Studio

## Scope and navigation clarification
- The new Visual Intelligence planning UI is shown on the redesigned left-menu **Concept Studio** route (`/concept-studio`).
- The legacy **Concept explainer** route (`/concept-explainer`) remains compatible and does not show the new planning panel. Both routes continue to reuse the existing generation component and APIs.
- This is an orchestration preview and explainable planning foundation, not a second video-generation pipeline.

## Changes
- Added `VisualIntelligenceService` and `POST /api/concept-explainer/visual-intelligence/plan`. It proposes scene intents, engine candidates, relative scores, reasons and narration timing guidance.
- Added a Visual Intelligence panel to Concept Studio to preview a topic-specific visual strategy.
- Added candidate guidance for Manim, Three.js, PixiJS, Motion Canvas and native diagrams.
- PixiJS is explicitly marked planned/unavailable until a working adapter is registered; the preview proposes Motion Canvas/native diagram alternatives. It is not falsely represented as an active renderer.
- The plan keeps programmatic-only visuals for concept lessons and explicitly excludes Wan/ComfyUI image generation. Story/cartoon workflows remain unchanged.

## Limitations and validation
- The current planner uses deterministic topic cues; it does not yet call Ollama to produce a full concept graph, nor does it replace the existing scene-level render router.
- Scores are initial heuristics, not empirically trained quality predictions.
- Full Maven/Angular builds and live render tests must be run in the target environment; the frontend dependencies are not installed in this packaging environment.
