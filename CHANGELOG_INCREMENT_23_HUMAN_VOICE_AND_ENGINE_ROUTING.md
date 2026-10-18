# Increment 23 — human-sounding narration and topic-aware visual engines

## Voice: sound and explain like a person
- `speech/SpokenText` converts the written line into the spoken form before it reaches the TTS engine
  (`count++` -> "count plus plus", `O(n^2)` -> "big O of n squared", `3.5GHz` -> "3.5 gigahertz", `getUserName()` -> "get user name",
  `CPU` -> "C P U", `e.g.` -> "for example", `50%`, `$2.5M`, `km/h`, `1/2`, URLs, emoji, markdown). Captions keep the written form.
  Engine cues such as `[chuckle]` are preserved. Non-English text only gets the language-neutral clean-up.
- `speech/SpeechHumanizer` (applied in `NarrationScrub`) adds contractions and replaces stiff connectors
  ("Furthermore" -> "Also", "utilize" -> "use"). It never changes "do NOT", "Yes, it is.", or non-English / romanised text.
- `speech/SpeechPacing` replaces the single fixed speed/pitch/gap with bounded per-sentence variation: short lines land slower,
  questions lift and wait, lead-ins ("Here's the thing:") get a breath, the last line settles. Scaled down for toddlers and reels.
- Narration prompt: mixed rhythm (short beats between longer sentences), contractions, no symbols/code in speech, no "Furthermore/Moreover/In conclusion".
- The voice ENGINE is unchanged. If Chatterbox is down and Piper/Edge speak instead, quality is lower (a warning is already shown).

## Visual engines: chosen from the topic
- `EngineScorer` (pure Java) scores each scene with whole-word matching and the scene's own diagram devices. Defaults to the native diagram renderer.
  - MANIM: scene carries a validated maths/physics spec.  RIVE: uploaded/explicit `.riv` AND a character/UI scene.
  - THREE_JS: real depth only (orbits, molecules/crystals, layered structures, 3D shapes); kids lessons are penalised.
  - Process/code/API/comparison scenes now go to the NATIVE renderer (flowchart, sequence, code, chart devices).
- Router consults the renderer's `/health`; an engine that is down is never planned.
- `planJob` keeps a lesson varied: 3D limited to ~35% of scenes, no special engine three scenes in a row.
- Native scenes no longer sit at PLANNED/PENDING; Manim scenes are marked RENDERED/FALLBACK after the animation step.

## Renderer service
- Three.js templates driven by data: orbit, molecule, crystal lattice, layers, axes/3D shape, nodes. One frame per sentence, progressive reveal,
  the last frame always shows everything even when there are fewer frames than items.
- Rive: waits for load, fails on load error/timeout, fails on a blank canvas, wasm served locally, all other network blocked.
- Page lockdown for every render, concurrency limit (`RENDER_CONCURRENCY`, default 2), bundles cached.
- `/health` v3 reports Motion Canvas unavailable (no supported headless exporter). Unused Motion Canvas packages removed from this service.

## Security
- A Rive asset is accepted only as `rive-assets/<uuid>.riv` stored by the upload endpoint, in the controller, the router and the service.
  An absolute path or anything written into `planJson` is ignored.

## Verification
- Run here: 18 speech/scorer tests and 8 router tests (real Jackson + real job class, JUnit stand-in); NarrationScrub and render client compiled;
  live renderer run in headless Chromium 1194: all templates, garbage `.riv` rejected, missing asset rejected, Motion Canvas rejected.
- NOT run: Maven build of the whole backend, Docker build (Chromium 1248), Angular build, a valid `.riv`, real TTS audio.
- Motion Canvas export is still not implemented.
