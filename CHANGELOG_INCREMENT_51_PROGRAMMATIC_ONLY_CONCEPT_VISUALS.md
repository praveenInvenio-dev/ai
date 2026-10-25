# Increment 51 — Programmatic-only Concept Explainer visuals

## Change
- Concept Explainer topic and scene prompts now route visuals only to Motion Canvas, Three.js, Manim, or native diagrams.
- Removed WAN / generated imagery as a recommended Concept Explainer visual engine.
- Concept Explainer's animated-video path explicitly skips Wan image-to-video even if the shared animated-cartoon flag is enabled.
- The separate story/cartoon workflow retains its existing Wan image-to-video support.
- No API, database, or frontend schema changes.

## Intended behavior
1. Topic planner chooses a visual proof and a supported programmatic engine.
2. Scene plan describes the visual type, labels, data, and animation steps.
3. Existing render services produce the animation and use native diagram fallback if an engine is unavailable.
4. Narration, captions, and scene timing are composed by the existing FFmpeg pipeline.

## Verification
- ZIP integrity checked.
- Source-level tests updated to assert that Concept Explainer prompts forbid Wan/generated imagery.
- Full Maven/Angular build and live render were not run in this environment.
