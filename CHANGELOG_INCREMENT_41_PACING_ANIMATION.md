# Increment 41 — Slower teaching pace and clearer animation

## Changes
- Reduced short explainer narration target to 105–125 words rather than 125–145, leaving more time for actual teaching and visual holds.
- Added explicit 125–140 WPM pacing guidance, slower for Hinglish, code, formulas and unfamiliar concepts. Instructs the pipeline to cut repetition rather than rush speech.
- Increased sentence gaps and scene hand-over/visual hold time in ConceptExplainerService.
- Improved Three.js stack framing: larger blocks and labels, closer/stabler camera, and clearer operation arrows.
- Shortened element fade-in to keep important text readable sooner.

## Known limitations
- These changes do not directly slow a TTS engine if the provider ignores requested rate; check the rendered audio duration and actual word rate.
- The shown Java Stack video contains a black opening/ending frame. This package adjusts reveal timing but does not claim to have fixed every black frame/transition bug without a fresh render.
- No full Maven tests or new video render were run in this environment. Validate with the same lesson before deployment.
