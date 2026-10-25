# Increment 50 — Engine-aware concept videos

## Goal
Use the Motion Canvas, Manim and Three.js integrations already present in the project more intentionally in newly generated concept-explainer videos, without adding a duplicate rendering stack or changing APIs/database schema.

## Changes
- Updated concept-pitch guidance to name the visual proof and likely engine family, and to distinguish generated cinematic imagery from precise instructional graphics.
- Updated lesson-outline guidance to select engine-activating devices by subject: physics/maths (forces, wave, lens, triangle, numberline, formula, plot), chemistry (atom, molecule, reaction, periodic, energy), biology (labelled diagrams and spatial 3D only where appropriate), and programming/system design (sequence, flowchart, data structure, layers, lanes, steps, code).
- Updated scene-writing guidance to preserve specialised devices rather than collapsing them into generic cards, to give Motion Canvas explicit ordered elements, to provide Three.js meaningful spatial cues/labels, and to leave maths/physics eligible for the existing validated Manim-spec pass.
- Added prompt-contract tests to protect these instructions.

## Existing render path confirmed in Increment 49 source
- `ConceptExplainerService.assignVisualRoutes()` calls `VisualRendererRouter.planJob()` for per-scene routing.
- `EngineScorer` chooses Manim only for validated Manim specs, Three.js for spatial topics, Motion Canvas when renderer capabilities are available, and native diagrams as a safe fallback.
- `suggestManimScenes()` selects a limited number of maths/physics scenes when the Manim service is enabled; `manimFor()` validates template names and parameters before rendering.
- `VisualEngineRenderClient` queries renderer `/health`; unavailable engines are not falsely advertised as active.
- Motion Canvas availability depends on its prebuilt player being present in the renderer image. Animated clip availability is separately reported; if a renderer is unavailable, the current pipeline falls back to native synchronized diagrams/stills.

## Verification
- ZIP integrity verified.
- Prompt test source added. Full Maven/Angular and live renderer end-to-end tests were not run in this environment; use the project's normal CI/compose setup to validate against the actual model and renderer services.

## Important
This is a prompt and routing-quality update that makes existing engines more likely to be selected when pedagogically appropriate. It does not guarantee that every scene uses every engine, and does not force an unavailable renderer to run. Generated equations, code and numerical graphs remain subject to deterministic rendering/validation.
