# Open-source visual engines in AI Story Studio

## Integrated in this increment

- **Three.js** (`three`, MIT): added a live 3D data-flow preview in Concept Explainer. It shows request → queue → worker with moving packets and animated nodes. It is an interactive preview, not yet a replacement for the production MP4 renderer.
- **Rive runtime** (`@rive-app/canvas`, MIT): added a local `.riv` file preview. The asset stays in the browser; no upload is sent to the backend. The Rive editor is a separate product and is not bundled.
- **Motion Canvas** (`@motion-canvas/2d`, `@motion-canvas/core`, MIT): added a source-controlled starter scene and an optional Vite preview service. It demonstrates synchronized cause/effect motion and is ready to extend into a named-template catalog. Start it with `docker compose --profile motion-canvas up -d --build motion-canvas`, then open `http://localhost:5173`.
- **Manim Community** remains the existing production sidecar for math/physics scenes (MIT), enabled with `docker compose --profile manim up -d --build manim`.

## Architecture and guardrails

- Keep narration timings and final FFmpeg assembly owned by the existing backend pipeline.
- Use Motion Canvas for explanatory vector scenes, timelines, UI mockups, request flows and causal diagrams.
- Use Manim for equations, plots, vectors and geometry.
- Use Three.js for intentional 3D system/network/spatial previews and later 3D scene templates.
- Use Rive for reusable character/UI state animations when a licensed `.riv` asset is available.
- The model must produce validated declarative scene specs, never arbitrary TypeScript/Python to execute.
- Rive `.riv` preview currently runs client-side only; no user asset is uploaded. Three.js and Motion Canvas previews are not yet exported into final lesson MP4s by this increment. Existing Manim MP4 rendering remains integrated in the production pipeline.
- Renderer licences do not automatically cover third-party models, fonts, characters, illustrations, sound effects or Rive assets; check each asset's licence separately.

## References

- Motion Canvas: https://github.com/motion-canvas/motion-canvas
- Manim Community: https://github.com/ManimCommunity/manim
- Three.js: https://github.com/mrdoob/three.js
- Rive runtime: https://github.com/rive-app/rive-wasm
