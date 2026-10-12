# Concept Explainer - "Tech tutorial (reference video)" style

Matched to the uploaded reference lesson (24 min, MCP / agents course). Measured from it:
dark-teal canvas (#08202F), diagrams only (no artwork), one element fades in per spoken sentence,
male instructor ~160 wpm, median pause 0.34 s, no music bed, no camera motion.

## New
- `tutorial/DiagramRenderer` (pure Java2D): actor (user / suited agent), gradient heading, teal buttons,
  brand-style tiles (fictional names), outlined cards, green notes, quotes, pills, chat window,
  terminal, browser (JSON or page), code window with line numbers + dashed line frame, violet
  container layer, agent-to-agent message arrows, curved dashed connectors with labels, 37 icons,
  dim + highlight per sentence, overlap fixer, Indic/Unicode font fallback. Lato (OFL) bundled.
- `tutorial/TutorialPrompts`: reference teaching pattern (familiar -> problem -> concept -> how ->
  in practice -> ecosystem -> use cases -> recap), one running example with made-up names,
  instructor voice rules, scene-graph JSON schema. Outline once, scenes written in batches of 5-6.
- `tutorial/DiagramSpec`: alias normalising, "keep" continuity between scenes, arrow validation,
  resolved scene stored as plan JSON (re-render needs no LLM).
- Service: style `reference` routes to the new planner/renderer; steady TTS pace, 0.34 s gaps,
  0.45 s scene hand-over, no subtle zoom, no music.
- UI: style is first + default; new "10-Minute Full Lesson" (32-42 scenes, ~1,600 words).

## Fixed
- `ConceptExplainerService` did not compile (`needsIllustration(s)` -> `(job, s)`).

## Verified here
- Whole `conceptexplainer` package compiles against real Jackson 2.21 (Spring stubbed).
- Renderer + spec tested on model-style JSON (aliases, keep, broken arrow); preview video rendered.
- Angular production build passes.
## Not verified here
- Full Maven build, real Ollama output quality, real TTS voice, Docker end to end.

## Update: tutorial teaching in every visual style
- New "Lesson format": TUTORIAL (default) or CLASSIC. TUTORIAL = reference-video teaching + diagram
  engine, now drawn in any style: reference, neon (black + bloom glow), sketchnote / chalkboard
  (Kalam hand lettering), storyboard (clean light cards), blueprint (navy grid), anime (comic headings).
- CLASSIC keeps the old template slides + AI artwork.
- Fixed: light-style background gradient faded to grey.
