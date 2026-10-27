# Increment 66 — "Blueprint explainer": the narrated-diagram format of the reference reels

Includes Increments 55-65.

## What the reference videos do (Few Shot Studio, ~160 s, Hinglish)
- ONE concrete scenario told in time order ("Build JioHotstar. Crores watch the IPL final live." / a 10-minute grocery order).
- `00 / THE QUESTION` card (interviewer) → the stakes as one huge number (**5.5 CRORE**) → chapters `01 / BEFORE THE TOSS`,
  `02 / THE CHAIN`, … each the next moment of the story → `06 / THE ANSWER` (question card again + ticked checklist) →
  a question for the comments.
- A journey bar (TOSS → 1ST BALL → AD BREAK) shows where in the story we are.
- **The picture changes with every sentence** and shows exactly what is said: outlined phones/servers lighting up, dots
  flowing along a pipeline, a server fanning out to crores of phones, LIVE vs YOUR PHONE buffer bars, a spike chart
  smoothed by jitter, a term chip ("Geofence", "Shard key") once the idea was shown.
- No karaoke subtitles: one key phrase per sentence under the diagram ("ENCODER / **shrinks** the camera video").
- Navy blueprint paper, mono labels, yellow accent.

## New in Explainer Studio
- Format **"Blueprint explainer — diagram story (≈2 min)"** (defaults to Hinglish).
- **Script generator** (`src/blueprint/generate.js`): outline = series, question, stakes, journey milestones, 4-7 chapters,
  answer (headline + 3 points), end question, analogy; then each chapter sentence by sentence, **each sentence with its own
  visual beat** built from the topic's real nouns and numbers. Lint: valid beat on every sentence, no 3 identical beats in a
  row unless building, Hinglish Roman captions + Devanagari speech, sentence length.
- **10 beat types** (`src/blueprint/beats.js`): question card (typing), stat (count-up), devices (17 outlined device glyphs:
  phone, server, store, bike, truck, bank, db, user, …, "lit" ones glow), pipeline (flowing dots, active step), fanout/fanin,
  bars (buffer/progress rows + marker), chart (spike / smooth / steady / rise), compare (✕ vs ✓ panels), checklist
  (ticks one per sentence), term chip. `"same": true` keeps building the previous picture.
- **Renderer** (`remotion/blueprint/Blueprint.jsx`): corner series label + episode number + your handle
  (`EXPLAINER_BRAND_HANDLE`), chapter label, journey bar, beat stage with cross-fade, key-phrase caption with underline swipe.
  Background painted once (fast render).

## Not copied
The 3D rendered scenes of the first reference (dark store aisles, bikes in a 3D city) are hand-made 3D assets; generating
those per topic needs a 3D asset pipeline. The flat blueprint diagrams of the second reference are reproduced.

Verified: 23/23 API tests (beat sanitising + full outline→chapters generation against a fake Ollama), Angular build,
stills of every beat type in 9:16 and 16:9.
