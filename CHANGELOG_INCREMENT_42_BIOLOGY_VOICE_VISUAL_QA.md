# Increment 42 — Biology teaching, visual consistency, pacing and black-frame QA

## Fixes
- Added a health/biology/NEET-specific instructional quality contract to the Concept Explainer generation path, not just the separate tutorial prompt path.
- Requires mechanism-first explanation, clear definitions, accurate anatomy, misconception checks, and careful treatment of the brain's interconnected systems.
- Requires a coherent clinical visual style, larger anatomical visuals, accurate label anchors, meaningful process animation, and narration-to-visual alignment.
- Added global visual contract to prevent random light/dark palette changes, tiny diagrams, empty frames, decorative-only animation, and recap content fading before the audio ends.
- Lowered script-review WPM assumptions for short lessons and younger audiences so estimated duration and word budget are more conservative.
- Tightened black-frame QA at video edges so multi-second black lead-ins/outros are reported rather than excused as normal fades.

## Known limits
- Prompts are not a guarantee of factual correctness; review anatomy/medical content before publication.
- The actual TTS service `hi_IN-priyamvada-medium` may not expose expressive emotion controls. Prompt changes cannot add capabilities the engine does not have.
- This source package has not been run through the user's full Docker/GPU deployment or a fresh end-to-end render in this environment. Re-render the same brain lesson and verify the report, audio, and every scene before calling it fixed.
