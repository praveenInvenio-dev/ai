# Increment 21 — capability-aware visual routing

## Fixes
- Scene routing no longer defaults to Motion Canvas while the server-side Motion Canvas export adapter is unavailable.
- Spatial scenes route to Three.js; code, workflow, threading, and system-flow scenes route to the verified Three.js renderer; ordinary explanatory scenes use the existing native synchronized diagram renderer.
- Rive is selected only when the lesson plan explicitly references a `.riv` asset. The backend still validates that the referenced file exists and enforces the existing 25 MB limit before sending it to the renderer.
- Renderer health now reports Motion Canvas as unavailable, Three.js as available, and Rive as available when a valid per-scene asset is supplied. This avoids treating a configured but nonfunctional Motion Canvas export path as healthy.
- Rive rendering now waits for the asset's load callback, reports load errors/timeouts, and uses the requested canvas dimensions instead of always creating a 1920×1080 canvas.
- Added router unit tests for code/process, spatial, Rive-asset, and general-scene routing.
- Added `@motion-canvas/ui` to the Motion Canvas authoring project's dependencies to address the observed Vite plugin startup error `Cannot find module '@motion-canvas/ui'`.

## Validation
- `node --check visual-renderer/server.mjs`: passed.
- JSON syntax validation for both modified package manifests: passed.
- Maven tests could not be run in the packaging environment because Maven is not installed.
- Motion Canvas dependency installation/build could not be completed in the packaging environment (npm install timed out).
- Motion Canvas server-side headless video/frame export remains explicitly unavailable; the adapter still returns a truthful failure so the backend can use its native fallback. This increment does not claim to implement Motion Canvas export.
