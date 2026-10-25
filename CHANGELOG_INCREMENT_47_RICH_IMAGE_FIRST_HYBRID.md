# Increment 47 — Rich image-first hybrid visuals

## Change
- Made the Concept Explainer's Wan image-to-video prompt explicitly preserve the already-generated scene illustration as the source of truth.
- Wan is instructed to add only restrained motion, depth/parallax, light movement and a controlled camera move; it must not redesign the subject, alter key geometry, or generate text/equations.
- The deterministic overlay/composition stage remains responsible for accurate educational text, equations, axes, vectors, graph labels and narration-timed overlays. This avoids relying on diffusion models to render mathematical notation correctly.
- Kept the existing Wan workflow, existing storyboard approval/regeneration flow, fallback to camera movement when Wan fails, and existing Motion Canvas / Three.js / Manim routing. No schema or API change.

## Intended scene pipeline
1. Generate and approve a rich still illustration in ComfyUI (WAN is not used for this step; the configured image model creates the still).
2. Animate that exact still with Wan image-to-video, using the new preservation-first prompt.
3. Render accurate math/science overlays and reveal timing using the existing deterministic visual renderers.
4. Composite background motion, overlays, narration, captions and audio in the existing FFmpeg composition stage.

## Validation
- Source-level change only. Archive integrity and targeted diff reviewed.
- Full Spring/Angular builds and live ComfyUI/Wan generation were not run in this environment; runtime model availability and VRAM remain deployment-dependent.
