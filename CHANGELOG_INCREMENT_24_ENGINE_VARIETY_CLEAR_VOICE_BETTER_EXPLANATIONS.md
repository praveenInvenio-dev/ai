# Increment 24 — engines no longer always native, clearer reels, better explanations

Found by checking a real generated lesson ("Java Multithreading for Beginners") and its 72 s reel.

## Why every scene went to NATIVE_DIAGRAM (bug in Increment 23's scorer)
- Common plan devices (photo, actor, analogy, compare, note, tile, button, layers, steps ...) appear on almost every scene. The scorer counted them as "this
  scene needs the native renderer", so every real lesson ended up 100% native. Now only specialised devices count (flowchart, sequence, datastruct, code,
  terminal, browser, formula, plot, ...), with a smaller bonus; tech words are only a weak hint.
- Two 3D kinds that fit programming lessons: `lanes` (parallel workers vs one-at-a-time, tasks as blocks, a cursor showing what runs) and a generic
  `layers` stack (app / JVM / OS / cores). The 3D scene takes its labels from the lesson's own plan (layers / steps / cycle device lines).
- On the 8 scenes of that lesson the real router now gives: native 1,2,3,6,7; Three.js lanes 4 and 8; Three.js layers 5. 3D stays at most ~35% of a lesson.
- Native stays the right choice for code, flows, comparisons and illustrated scenes. Manim still needs a physics/maths spec, Rive an uploaded `.riv`,
  Motion Canvas is still unavailable.

## Voice clarity (measured on the real reel)
- Measured: speech filled 88% of the reel; only 4 pauses over 0.25 s in 72 s (longest 0.42 s). Cause: a 60-second reel with too many words was
  sped up by up to 30% (`fitToBudget`) and reel gaps were 0.28 s. Spectral clarity and pitch variation were fine.
- Reel narration is now sped up at most 6% (only when over ~73 s), never 30%; reel pauses 0.34 / 0.46 s; a warning says when a reel is still long.

## Explanation quality
- The planner ignored the word budget (406 words for 140-180). The "too long" fix only shortened the 3 longest scenes, which cannot remove 226 words.
  Now every scene gets its own word target, and an automatic tightening pass runs before the script is shown when it is more than 30% over budget.
- Prompt: one idea per scene (idea, example, takeaway), name the real component that does the work, no blurry mechanisms, programming topics show one
  correct example plus the one common mistake and the one danger.
- Script doctor flags empty phrases ("the real magic happens", "game changer", "a nightmare") and the fix pass replaces them with the concrete fact.

## Vertical (reel) layout, reproduced with the real renderer
- The pinned step bar sat at y=175, on top of the heading (shown in the video). It now starts below the heading block, and content starts below the bar.
- Content that fits is centred in the free area of a vertical frame instead of hugging the top.
- NOT fixed: text on cards is still small for a phone screen (28 px on a 1080 px wide frame); card text that looked cut off in the video could not be
  reproduced and may come from the planner's own text.

## Verification
- 35 unit tests (speech, scorer, router with real Jackson, labels, script doctor) pass with a JUnit stand-in. Renderer smoke test passes live.
- Not run: Maven build of the whole backend, Docker build, Angular build, a valid `.riv`, real TTS audio.
