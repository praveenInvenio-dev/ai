# v24.6 - Visual styles, less repetitive lessons, Indian voices

## Visual style dropdown (Concept Explainer)
neon | sketchnote | storyboard | chalkboard | blueprint | anime. Each style changes background, panel drawing
(glow / hand-drawn wobbly ink / soft cards with shadow / chalk / blueprint grid / comic outlines), fonts
(Barlow, Kalam handwritten incl. Devanagari, Bangers comic titles), palette, and the AI illustration prompt
(neon line art, sketch doodle, glossy 3D clay icon, chalk drawing, blueprint lines, anime cel-shaded).
Light styles cut illustrations out of a white background; dark styles out of black.

## Less repetitive (about half of every lesson differs)
- Lesson id seeds colour order, header layout (badge-left / centred) and per-scene mirrored or alternative
  layouts: definition (diagram left/right), analogy (image left/right), example list (rows / card grid),
  checklist (one column / two-column cards), summary (stacked rows / mind-map hub).
- Planner: >= 5 templates, no template twice in a row, code ONLY for programming topics.
- Fixed: the definition slide always drew a "JAVA CODE" window and "TYPE + NAME + VALUE = Java variable"
  on every topic (e.g. an ear-anatomy lesson). Code window / REMEMBER line now only from the scene's data.
- Fixed: definition callouts disappeared when the code appeared.
- Non-code list items (e.g. "Malleus") use the normal font, not code colouring. Lists and checklists are
  centred vertically (no empty lower half).

## Voices
- Voice menu: Indian English (Edge Neerja / Prabhat), Edge voices for the chosen Indian language, IndicF5
  voices for the language, Chatterbox Arjun/Maya (US accent), and your Voice Lab clones.
- Explicitly chosen edge:/profile: voices are used exactly (were replaced before).
- Humanising: Edge voices slowed 6 % with the existing per-sentence pitch/pace variation; IndicF5 2 %.
- Optional expressive Indian-accent Chatterbox tutors (`tutor-in-female/male`) via CHATTERBOX_BOOTSTRAP_VOICES
  in .env.example (or your own 10 s recording in Voice Lab).
