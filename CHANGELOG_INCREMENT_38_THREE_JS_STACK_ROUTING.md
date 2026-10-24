# Increment 38 — Meaningful Three.js coverage for technical lessons

## Problem observed
The supplied Java Stack lesson reported `THREE_JS: on` but routed every scene to native diagrams or Motion Canvas. The video contact sheet confirms the visuals were flat diagrams; the engine being available did not mean the planner selected it. The router also capped 3D at 35% and the Three.js renderer had no dedicated stack template.

## Changes
- Added a dedicated Three.js `stack` scene: dimensional stacked items, top-item highlight, PUSH/POP cues, subtle camera motion and narration-timed reveals.
- Added explicit stack/LIFO/push/pop/plate-pile scoring so data-structure scenes can route to 3D even when the planner includes a generic `datastruct` device.
- Kept actual code-window scenes, formula scenes and ordinary comparison scenes on their specialised renderers.
- Increased the maximum 3D share from 35% to 45% when enough scenes genuinely benefit from depth; this is a ceiling, not a quota.
- Preserved lesson-wide variety constraints and native fallback when Three.js is unavailable.
- Added unit tests for stack-template selection and mixed-engine lesson routing.

## Validation
- Source-level checks and ZIP integrity verification are performed for this increment.
- Runtime Java tests and a fresh video render must still be run in the user's Docker deployment to verify that the deployed renderer image includes these changes.
