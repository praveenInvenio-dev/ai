# Concept Explainer — Rich Scene Production Engine

## Implemented in this source update
- Added **Reference technical tutorial** as an additional selectable visual style; all previous style choices remain in the UI.
- Changed scene production so every scene with a visual description receives its own generated artwork, using the selected style's art direction rather than generating artwork only for analogy scenes.
- Replaced the template-first visual composition path with a shared rich-scene composition for all styles: large scene-specific artwork, an explanatory rail, progressive annotations, and code/formula evidence when present.
- Kept technical text, labels and code rendered programmatically for spelling/readability; generated artwork is explicitly instructed not to include text.
- Added cover-fit image placement so the generated artwork fills the main visual area instead of appearing as a small square icon.
- Updated lesson planning and writing prompts to start directly with the topic/problem, develop a connected explanation of why/how/components/relationships, and plan concrete examples plus visuals that teach rather than decorate.
- Improved analogy/scene image prompts to request detailed concept-relevant compositions, texture, lighting, depth and strong visual hierarchy.
- Kept existing selectable style IDs and palettes; the shared composition uses each style's palette and typography.

## Validation
- `ConceptSlideRenderer.java` compiled successfully with `javac`.
- A renderer smoke test created four progressive frames and wrote a PNG successfully.
- Teaching planner Java sources compiled successfully with `javac`.
- Full Spring Boot build, Angular build and end-to-end generation using the configured ComfyUI model were not run here (Maven and frontend dependencies are unavailable).
- Actual generated-art quality, GPU memory use, throughput and final audio/video synchronization still require a Docker end-to-end test. This update changes the production architecture materially, but exact reference parity must be judged from a newly generated lesson.
