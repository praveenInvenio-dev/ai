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
- Honest pre-plan defaults: before the visual plan exists a scene now says `PENDING` ("Waiting for the visual plan") instead of "NATIVE_DIAGRAM · FALLBACK". A page opened
  mid-planning (while the script is being tightened) used to look like every scene had failed over to native.

## Follow-up: why the recommended Indian-English voice never worked
- Cause: the shipped `.env.example` set `CHATTERBOX_BOOTSTRAP_VOICES` to only the two US voices. That env value replaced the server's built-in list, so `narrator-in-male` /
  `narrator-in-female` ("Arjun / Meera - Indian English", the recommended voices) were never created and every lesson fell back to a plain Edge voice.
- Fix: the built-in four voices are always created; the env value only adds or overrides. `.env.example` lists all four. Rebuild `tts-chatterbox` (no .env change needed).
  The first lesson after the rebuild may pause while the clip is created from the Edge voice (it needs internet from the `tts` container).
- Animated / 3D scenes whose plan has no list to show now label themselves with the scene's own words instead of template placeholders ("Start / Process / Result", "Take order / Brew tea").

## Follow-up from a finished 60-second reel (86 s, 197 words)
- Length: the reel budget said 155-175 words, but a real reel measured 0.43 s per word (pauses, title, outro): 197 words = 86 s. Budget is now 125-145 words (about 60 s),
  8-10 scenes; a reel more than 12% over budget is tightened automatically; the script doctor estimates reel time at 138 wpm.
- Clarity: the treble correction was capped at 4 dB with a shallow slope, so a voice measured at -22 dB (the Edge en-IN voice) was still -22 dB after correction.
  It now corrects toward the target (up to 6.5 dB) when the voice is below -21 dB.
- Vertical 3D: the scene is pulled back until its width fits a tall frame (per template), and the camera keeps looking at its subject; the layer discs and lane labels
  were cut off at the edges of a 1080x1920 reel.
- Correction to an earlier analysis: reels play a quiet music bed that fills the pauses, so a pause detector based on silence saw "no pauses". The pauses are there
  (about 0.25-0.34 s between sentences); the earlier "speech fills 87-90% of the reel" figure was an artefact of the bed.
