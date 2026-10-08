# Concept Explainer v3 - reference-quality neon slides, synced reveal

## Look: one scene = one panel of the approved neon infographic
- New `ConceptSlideRenderer` (pure Java2D) draws every slide at 1920x1080: black background, glowing neon
  panel, numbered header, crisp text, syntax-coloured code, 3D labelled boxes, tables, checklists, icon rows,
  summary mappings. Text/code is drawn, never AI-generated -> always sharp and spelled correctly.
- Ten slide templates (the planner picks one per scene): definition, analogy, analogy_code, code_anatomy,
  table, code_visual (incl. before -> after), code_block, example_list (20 neon icons), checklist, summary.
- AI image model is used ONLY for the real-world object in analogy slides (bottle with price tag,
  delivery bag ...), generated as neon line art on black and screen-blended into the panel.
- Fonts bundled (`resources/concept-fonts`): Barlow Semi Condensed, Fira Mono, Noto Sans for 9 Indian
  scripts (Kannada, Devanagari, Tamil, Telugu, Malayalam, Bengali, Gujarati, Gurmukhi, Odia) - no boxes.
  Text wraps; long titles shrink/ellipsis; code auto-fits its box (nothing silently cut).

## Motion: zoom/pan removed, synced reveal instead
- Ken Burns zoom/pan removed (it softened text and code).
- Narration is planned as ordered sentences and spoken sentence by sentence, so every sentence start time
  is measured. Slide element k fades in (0.35 s) when sentence k starts: box, then labels, then each code
  part, table rows, list items, checklist items.
- "Static" option shows the full slide for the whole scene.
- Own FFmpeg assembly: no story particles/SFX (fixes "firewall" -> fire crackle), no black blink between
  scenes, soft fade in/out of the whole lesson, calm music at 10 % ducked under the voice.

## Process fixes
- "My lessons" list + `?job=` in the URL: leaving or refreshing the page no longer loses a lesson.
- Image/audio/video URLs carry versions; no broken images while generating, audio previews no longer
  restart every poll, regenerated video shows immediately.
- Regenerate keeps the button busy until done, can't double-start; failed lessons get "Retry lesson".
- Lesson length checked (word count) with one automatic re-plan; warnings shown. Time estimate on cards.
- AI reply parsing tolerates ```json fences. Inline errors instead of browser alerts.
- TTS fallback to the mock voice is shown as a warning.
- Dockerfile: fontconfig + libfreetype6 for Java2D.

## Verified
- Angular production build passes.
- Renderer: all 12 panels of the reference rendered as separate slides (incl. Kannada).
- Real `ConceptExplainerService` run end to end with stubbed AI/TTS: 5 scenes, 143 words -> 62.1 s,
  1920x1080 30 fps, build steps appear at sentence starts, regenerate scene -> new video version.
- NOT verified: real Qwen illustrations, real TTS voices, Spring compile (no Maven here).
