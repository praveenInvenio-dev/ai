# Increment 25 — Motion Canvas works, Rive library, matching look, bar fix

From reviewing two more generated videos (a 82 s vertical reel and a 136 s lesson): only native + Three.js were ever used.

## Motion Canvas is now a real engine
- `visual-renderer/motion/`: a prebuilt Motion Canvas player project (animated step flow). The renderer drives the official `<motion-canvas-player>` in headless
  Chromium, seeks to evenly spaced frames and reads the canvas. Scenes are driven by variables only; nothing generated is compiled or run.
- Built in the Docker image (`npm run build` in `motion/`). `/health` reports `MOTION_CANVAS: true` only when that build exists.
- Chosen for scenes whose narration moves through ordered steps ("first ... then ... finally", "step two", "passes through", "hands off"), never over a code
  window, data structure, formula or chart. Capped at ~40% of a lesson.
- Verified: landscape and vertical, progressive frames (boxes fading in, arrows drawing, a packet mid-flight), a warm-up so the first frame is never blank.

## Rive: library + visibility
- Verified end to end with real `.riv` files (avatar and rating animations render and advance between frames). Earlier increments only tested rejection paths.
- `rive-library/manifest.json` lists `.riv` files with topic keywords (whole-word match). A matching scene is planned as RIVE with no per-lesson upload.
  `scripts/fetch-rive-samples.sh` installs two public sample files for TESTING (check licences before publishing).
- Rive files are designed in the Rive editor; none are bundled. When there is neither an upload nor a library, each lesson now says so in its warnings
  ("Rive animations are off ...") instead of silently never using Rive.

## Matching look
- Every engine takes the lesson theme; Three.js, Motion Canvas and Rive backgrounds match the rest of the video (the reel's Three.js scene was a different blue).
  Motion Canvas text uses the same sans font. Three.js pulls back in vertical frames.

## Renderer fix found in the reel
- "Wait Time" bars were empty: the planner wrote "Single: 30|s" and the value was read as "s" (zero). Bar rows now find the number wherever it is.

## Results on the real lesson (router)
- Native 1, 3, 6, 7 · Motion Canvas 2 · Three.js lanes 4 and 8, layers 5. Rive appears when a library or upload matches.

## Verification
- 44 unit tests pass (JUnit stand-in): scorer, router, labels, Rive library, speech, script doctor, bar rows. Renderer smoke test passes live (all templates,
  Motion Canvas, Rive rejection paths).
- Not run: Maven build of the whole backend, Docker build, Angular build, real TTS audio.
- Known, NOT fixed: card text is small on a phone; a clipped label ("Worker Th...") and overlapping "User Balance" cards in the videos; the reel's own
  pause settings in the videos you sent predate Increment 24 (speech filled 87% of the reel).

## Follow-up: "still only Three.js and native"
- Every lesson now carries one warning line: `Visual engines this run - Three.js: on, Motion Canvas: OFF (reason), Rive: needs a .riv file, Manim: off. Planned scenes: {...}`.
  If Motion Canvas shows OFF, the running renderer image is old or its motion build step failed (rebuild: `docker compose build --no-cache visual-renderer backend`).
- The plan's own ordered-step devices (journey / steps / timeline / flowchart / cycle / sequence) now count as a Motion Canvas hint, so stepper scenes use it.
  On scenes modelled on your two videos: stepper / flowchart / scheduler scenes -> Motion Canvas; code, tables, bars, metrics, callouts stay native.
- The Docker motion build step no longer breaks the whole image if it fails; the renderer then reports Motion Canvas OFF with the reason.
- Rive still cannot appear without a .riv file (upload or rive-library/).
- Variety floor: when Motion Canvas is available, a lesson of 5+ scenes uses it at least once if any scene fits it even weakly (score >= 2); never over code, formulas or charts.

## Follow-up from a real lesson ("What is RAG", 9 scenes; all engines on, plan was Motion 1 / native 8)
- Router: plain flow words ("the flow", "workflow", "loop", "stages") count for Motion Canvas; a plan "layers" device (a stack) is drawn by the 3D layers template with the
  device's own labels. On that lesson (devices guessed): scene 5 "The RAG Workflow" -> Motion Canvas, last scene -> Three.js layers, definitions/comparisons/code stay native.
- Chatterbox: the reference clip for `narrator-in-male` could not be created, so the voice fell back to Edge. The 400 error (and so the lesson warning) now says WHY the clip
  could not be made (the tts service's own error), or how to fix it (put a WAV in tts-chatterbox/voices/). Rebuild `tts-chatterbox` to get this.
- Script tightening: the prompt now requires complete natural sentences and says to drop the least important detail instead of turning every sentence into a headline.
- Open question: a 140-180 word budget for 9 scenes is about 18 words per scene, which is why tightened scripts read as fragments.
