# Increment 70 — Story Quality, Wardrobe Continuity, Clean Composition, Exact Runtime

## Changes
- Strengthened automatic H3 story planning so scenes form a causal mini-film: immediate hook, concrete goal, obstacle, escalation, midpoint reveal/reversal, decisive climax, and visible resolution.
- Required every five-second scene to use one dominant filmable action, a short spoken line, relevant timed SFX, evolving music, and a clear motivation for the next scene.
- Added a non-negotiable wardrobe lock: the selected character reference image is the source of truth for the exact garments, colours, patterns, jewellery, footwear, and accessories. Story scenes may change pose, expression, and action, not clothing.
- Added single-frame composition constraints to reject split screens, side-by-side panels, collages, montages, contact sheets, picture-in-picture, borders, letterboxing, white bars, subtitles, and text overlays.
- Strengthened H3 visual prompts and negative prompts with identity, wardrobe, and composition consistency instructions on every rendered shot.
- Explicitly instructs later scenes to continue from the prior scene's final frame and preserve the same wardrobe and character design.
- Fixed five-second story mode now disables crossfades so scene transitions do not shorten the planned runtime.
- Added exact-duration fitting for fixed-five-second H3 clips. When H3 returns a slightly short clip, the final frame is held and audio is padded/trimmed to five seconds before merge. This should make the final runtime match the planned scene count much more closely.

## Scope
- Changes apply to automatic full-story H3 mode and the shared H3 scene renderer.
- Single-scene Text-to-Video and Image-to-Video UI flows are not changed.

## Validation
- Source-level changes reviewed.
- Maven and Angular production builds could not be run in this environment because Maven is not installed and frontend `node_modules` are not present. End-to-end H3 generation should be validated on the target server.
