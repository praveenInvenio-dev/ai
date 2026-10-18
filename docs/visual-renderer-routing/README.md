# Topic-aware visual renderer routing

The Concept Explainer now assigns a visual engine recommendation to each scene using its title, narration, code, visual description, and validated scene plan. The chosen engine and reason are returned in each scene's API view and shown in the storyboard UI.

## Routing rules

- **Manim**: only when the scene contains a validated Manim spec and the optional Manim service is available.
- **Three.js**: spatial/3D concepts such as orbits, molecules, anatomy, and rotation in space.
- **Motion Canvas**: default 2D explainer; especially effective for ordered actions, thread timelines, API/data flow, code, state transitions, and before/after comparisons.
- **Rive**: only when the scene explicitly names a Rive asset in its validated plan. The renderer never invents a `.riv` filename or silently substitutes a random character.
- **Native diagram compositor**: safe, existing narration-synchronized renderer and fallback.

## Important production behavior

Motion Canvas, Three.js and Rive currently provide interactive authoring/previews, not server-side MP4 exporters in this project. For these routes, the router explicitly uses `SYNCHRONIZED_DIAGRAM_FALLBACK`; this keeps the final video functional, narration timing intact, and avoids falsely claiming that browser-only previews were rendered into the video. Manim remains the only optional external renderer currently used by the backend for production output.

The next adapter contract is `scene-spec.schema.json`: bounded declarative scene data, sentence-indexed animation beats, asset references and renderer selection. Never execute LLM-generated TypeScript/JavaScript. Renderer adapters must return a clip plus a timing manifest; the existing FFmpeg assembler remains the source of truth for audio timing and final concatenation.

## Example

A Java multithreading scene containing “two threads read the same shared value” routes to Motion Canvas because the learning goal is causal ordering. A scene showing a 3D molecule routes to Three.js. A quadratic graph with a validated Manim `function_plot` spec routes to Manim when the service is up. An arbitrary scene does not route to Rive unless a specific `.riv` asset is provided.
