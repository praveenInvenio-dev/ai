# Visual Engine Export Increment 14

## What changed in this increment
- Scene API now distinguishes the preferred/planned visual engine from the actual production renderer.
- Concept Explainer scene cards display planned engine, actual renderer, status, and a reason for fallback.
- Backend warnings explicitly state that browser previews are not equivalent to production video export.
- Sample UI image/video are supplied separately in the `ai-story-studio-renderer-samples` folder in the delivery, marked as illustrative.

## Current limitation (important)
The production compositor still renders synchronized native diagram frames. Motion Canvas, Three.js, and Rive server-side export adapters are **not yet wired** into the final MP4 pipeline in this increment. The diagnostics deliberately report `NATIVE_DIAGRAM / FALLBACK` rather than incorrectly claiming that one of those engines rendered the final scene.

## Required next implementation step
Add server-side adapters that render per-scene frame sequences for Motion Canvas, Three.js and Rive; store those frames in the project storage; then pass them into the existing FFmpeg composition path using the same narration timestamps. Only mark an adapter `SUCCESS` after frame generation succeeds. Rive requires a valid `.riv` asset and a supported runtime; Three.js needs a headless WebGL/browser runtime; Motion Canvas needs a headless project renderer.

## Validation
ZIP integrity check is performed after packaging. Maven and Angular production builds could not be run in this environment because Maven and `frontend/node_modules` are unavailable.
