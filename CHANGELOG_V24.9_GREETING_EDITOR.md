# v24.9 - One simple greeting, teaching-framework UI + tests run, CapCut-style editor features

## Concept Explainer narration
- Greeting: the lesson now opens with ONE simple greeting - "Hello friends!" or "Hello everyone!" (alternates per topic) -
  followed by the required line: "Hello friends! What are we learning today? Java Variables!".
  Kannada, Hindi, Tamil, Telugu, Malayalam, Marathi, Bengali, Gujarati get their own single greeting; other languages get none.
- Stacked greetings ("Hello hello hello"), repeated words ("so so so"), and greetings in later scenes are still removed
  (`NarrationScrub`); the validator then adds back exactly one greeting. The planner prompt says the same.
- UI: new "Teaching style" (engaging tech tutor / storytelling / professional / simple beginner) and
  "Where will it be used?" (YouTube / classroom) selectors - the backend already supported both.
- Tests: `TeachingFrameworkTest` updated for the greeting and now actually executed: 24 passed, 0 failed
  (run with a small JUnit stand-in because Maven is not available in the build sandbox).

## AI video editor (CapCut-style)
Assumed "cardboard video editor" = CapCut. Added, each tested:
1. Beat sync - the option was disabled. video-worker `/api/beats` (numpy onset detection + tempo + beat grid; tested:
   119.5 BPM on a 120 BPM track, 92.2 on 92) and `BeatSyncService` nudges every cut onto the nearest beat
   (max 0.28 s shift, shot stays >= 0.7 s, only if the source clip has footage left, locked shots untouched).
2. Looks (filters) - Vivid, Warm, Cool, Golden hour, Moody, Vintage film, Black & white, Soft pastel, Fresh & bright, Dramatic
   (+ Auto = the template's grade, None). Changing the look keeps the plan; the preview cache key now includes it.
3. Title text - animated title over the first 3 s of the final render, styled like the chosen caption look.
4. Stabilize shaky footage (FFmpeg deshake; rx/ry must be multiples of 16 - caught by a test).
5. Auto captions (from v24.8) work together with the above.
DB: migration V23 (look, title_text, stabilize). API: `look`, `titleText`, `stabilize` on project create/update + view.

## Not done yet (editor)
Silence/dead-air removal, auto highlight selection, per-clip filters, stickers/emoji, keyframes, picture-in-picture,
freeze frame, reverse, voice effects, text animations beyond the title.
