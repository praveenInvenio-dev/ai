# Increment 14 — truthful visual-engine production diagnostics

## Included
- Concept Explainer scene API now reports `visualEngine` (planned engine) separately from `actualRenderer`, `rendererStatus`, and `rendererMessage`.
- Scene cards show the planned engine and the renderer that actually produced production frames.
- The backend no longer implies that a browser preview is the production export. The current production compositor remains the synchronized native diagram renderer unless a real server-side adapter is enabled.
- Router warnings now explicitly describe the fallback behavior.

## Important implementation boundary
This increment improves routing diagnostics and avoids falsely labeling a native diagram render as a Motion Canvas, Three.js, or Rive export. It does **not** yet implement server-side export adapters for all three engines. A future adapter must return rendered frame files and pass them to the existing synchronized video compositor before the UI can report `SUCCESS` for that engine.

## Verification
- ZIP integrity is checked after packaging.
- Java/Angular production builds require the project's Maven and npm dependencies and should be run in the project build environment.
