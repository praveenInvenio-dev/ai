# Concept Explainer - "Real-World Breakdown" (3D blueprint reel)

Matched to the uploaded IRCTC system-design reel: a concept explained through one product people use,
deep-navy 3D room, glowing objects, yellow highlights, fast confident male narration (~178 wpm, no music).

## Story engine (format BREAKDOWN, ~2 min, 16-22 scenes, 380-470 words)
challenge ("Build a train booking system like IRCTC.") -> the naive answer in first person -> why it breaks
at real scale (real numbers only when certain, otherwise clearly illustrative) -> the user's journey step by
step with a tracker, each step -> technique -> "This is called X" + the real query/rule -> trade-off ->
"the best part" -> recap card -> a debate question for the comments.

## Visuals (blueprint3d theme, 1080x1920)
- 3D room: navy gradient, perspective floor grid, bokeh, vignette, soft bloom. Space Grotesk + JetBrains Mono (OFL).
- New elements: journey tracker, callout cards (*highlight*, ~~strike~~, trap / named / stat styles, recap
  lines), glowing 3D database, 3D server rack, phone with a real-looking app screen, 3D record slabs with
  BOOKED / FREE / LOCKED / HELD / BOOKED · YOU states + padlock / timer, request particle swarm (bots),
  cable bundles, SQL cards, 3D padlock, generated full-screen photo of the real-world example (Qwen, max 4).
- Same glide / fade / overview camera and English-only on-screen text.

## Verified here
- Renderer frames and a silent motion preview; concept package compiles; Angular build passes.
## Not verified here
- Real Ollama scripts, Qwen photos and TTS on the GPU box.

## Update: typewriter text + camera dolly
- Callout cards type themselves in (45 chars/s) with a yellow caret, like the reference. Sentence-0 cards
  type during the scene opening; later cards type at their sentence (an image-sequence overlay that also
  covers the cross-fade). Centred titles type from a fixed left edge (no sliding). Recap rows appear after
  the headline. Old cards clear before the next one types.
- Breakdown clips get a slow continuous dolly-in (4.5 % per scene), applied AFTER all overlays so nothing
  jumps when an overlay ends. Lessons and shorts stay still; classic slides keep their subtle push-in.
- Verified by running the real renderSlide + buildClip code (reflection harness, stub storage) through ffmpeg.
