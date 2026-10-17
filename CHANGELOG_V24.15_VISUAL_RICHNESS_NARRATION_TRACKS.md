# v24.15 — Visual richness and narration tracks

## Implemented
- Added three selectable narration tracks: Humorous Explainer, Logic-First Analyst, and Story-Driven Mentor. Existing styles and IDs remain supported.
- Injected track-specific script and voice-direction rules into the shared track instructions used by tutorial, short, and real-world-breakdown generation.
- Added a universal visual-quality contract for every subject: deliberate focal point, scene-to-narration alignment, coherent art direction, shot/composition variety, meaningful motion, mobile-safe typography, and no invented text/data.
- Improved illustration prompts to request specific scene action, subject-appropriate accuracy, depth, lighting, focal hierarchy and caption-safe space.
- Image generation now uses portrait or landscape dimensions based on the target video aspect ratio instead of always generating square artwork.
- Raised the non-kids scene-photo budget moderately and instructed the planner to use photos only when they teach more than a diagram.
- Made rich visual variety the default while keeping information density medium to protect readability.
- Added regression assertions for narration-track parsing.

## Validation status
- Source-level checks performed after patching.
- Full Angular production build, Maven/JUnit suite, TTS bake-off, GPU image generation, and end-to-end rendered-video review still need to run in the user's environment.
