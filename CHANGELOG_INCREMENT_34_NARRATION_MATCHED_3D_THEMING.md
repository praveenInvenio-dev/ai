# Increment 34 — Narration-matched 3D flow and teaching-theme consistency

## Changes
- Added a reusable Three.js `dataFlow` template for client/API/service/database/network/pipeline concepts, with 3D component blocks, connected paths, animated packets, a perspective camera and narration-step reveals.
- Added narration/topic-based routing to `dataFlow` when no explicit scene kind is supplied, while preserving existing explicit kinds and existing renderer fallbacks.
- Added theme-aware 3D palettes so light/kids, dark/reel, and blue/technical lesson themes are more visually consistent across the same lesson.
- Strengthened the teaching planner prompt to keep narration tone consistent with the selected teaching style and ensure narration, visual description, metaphor, and scene order remain aligned.
- No unrelated application features or API contracts were intentionally changed.

## Validation
- `node --check visual-renderer/three-scene.js` — passed.
- `node --check visual-renderer/server.mjs` — passed.
- Full render and backend build tests were not run in this environment because the ZIP does not include installed runtime dependencies/services.

## Notes
- This increment extends the existing Three.js clip path. It does not claim arbitrary narration can be understood perfectly; the planner still selects from validated templates, and renderer quality depends on the supplied labels, scene kind and sentence timestamps.
