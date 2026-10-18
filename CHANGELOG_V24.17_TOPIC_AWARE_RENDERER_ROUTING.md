# v24.17 — Topic-aware visual renderer routing

- Added a deterministic per-scene visual router using the planned scene's title, narration, code, visual description, validated plan and available Manim capability.
- Route choice and explanation are included in each Concept Explainer scene API view and displayed in the scene storyboard.
- Routing rules prefer Manim for validated maths/physics specs when the sidecar is available; Three.js for spatial/3D topics; Motion Canvas for causal diagrams, code, systems, timelines and as the 2D default; Rive only for explicitly referenced `.riv` assets.
- Added a bounded declarative scene-spec schema for future renderer export adapters. No model-generated code is executed.
- Preserved the current narration-synchronized renderer and FFmpeg assembly as safe fallback. Motion Canvas/Three.js/Rive browser previews are not misrepresented as production renderers; production export adapters remain a follow-up.
- Added sample visual frames and a short illustrative MP4 for the Java concurrency story flow.

## Validation

- ZIP integrity and static routing checks pass.
- Existing frontend editor regression check remains part of the validation.
- Full Maven/Angular dependency builds and GPU-backed end-to-end generation require the user's project runtime and are not claimed here.
