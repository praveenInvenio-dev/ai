# v24.17 — Open-source visual engine integration

- Added a Three.js animated 3D request/queue/worker preview to Concept Explainer.
- Added local-only Rive `.riv` preview using the open-source Rive web runtime.
- Added a Motion Canvas project with an animated cause/effect data-flow scene and an optional Docker Compose preview profile on localhost:5173.
- Preserved the existing Manim sidecar and production video pipeline.
- Documented engine roles, launch steps, and safe scene-spec guardrails.

## Scope note

Three.js and Motion Canvas in this increment are interactive preview/authoring integrations, not production MP4 exporters. Rive is preview-only and requires the user to supply a `.riv` file. The existing Manim renderer continues to export production animation clips for eligible math/physics scenes. Wiring Motion Canvas/Three.js/Rive timelines into the final narration-synced MP4 assembly is a separate follow-up; this increment does not claim that path is complete.
