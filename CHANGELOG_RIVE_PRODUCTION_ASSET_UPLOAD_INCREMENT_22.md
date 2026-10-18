# Increment 22 — Rive asset upload and production routing

## Added
- Added `POST /api/concept-explainer/rive-assets` multipart upload for `.riv` files, capped at 25 MB, stored under a generated `rive-assets/<uuid>.riv` path.
- Added the optional `riveAsset` field to Concept Explainer job creation. The backend validates the relative path and confirms the asset exists before queuing the job.
- Connected the Rive preview component to upload a selected `.riv` file and emit its stored asset path to the Concept Explainer form.
- Added a job-level Rive asset setting and route matching so an uploaded asset is used for character/avatar/mascot/talking-head/UI-animation scenes; unrelated code/process scenes continue using Three.js/native diagrams.
- Added routing tests for uploaded Rive assets and unrelated code scenes.

## Rive implementation status
- Rive runtime dependency exists in both the frontend preview and server-side renderer.
- Server-side Rive rendering accepts base64 `.riv` data, waits for load callbacks, and returns screenshot frames; backend validates the asset and only marks the scene rendered after receiving valid frames.
- No `.riv` asset is bundled with the project. End-to-end production rendering still requires a valid user-supplied asset and a live test with that asset.

## Validation
- Source edits and JSON syntax were inspected; `server.mjs` syntax and archive integrity are checked during packaging.
- Maven and Angular production builds were not run in this environment (Maven is unavailable and frontend dependencies are not installed here).
