# Increment 30 — animated diagrams (tiles, actors, arrows, containers), not just cards

The lesson planner already draws the reference-video kit (tiles with icons, actors, buttons, notes, containers, arrows between them). The native renderer draws it as stills.
`revideo/src/scenes/diagram.tsx` animates it: `POST /render-clip {template:"diagram", elements, starts, total}`.

## What it does
- Every element pops in when the narration reaches its sentence (`at`); containers sit behind everything.
- Arrows (`from` / `to`, solid / dashed / both) are drawn from the edge of one box to the edge of the other, with their label, and send a dot along the line.
- An element glows while the voice talks about it (`highlightAt`) and fades back (`dimAt`).
- Supported: tile (icon + colour), actor (user / agent / robot), button, heading, pill, label, note, quote, card, container, message, icon, arrow.
- Icons: the planner's whole icon vocabulary (74 names: user, agent, server, database, cloud, brain, gear, phone, cart, shield, heart, ...) is mapped to 89 inlined Lucide icons (ISC).
- Router: a scene made only of these elements (with an arrow, tile, actor or container) becomes an animated diagram when the renderer reports clips; not for kids lessons; a terminal, browser, chat, photo
  or code window keeps the scene native (code windows have their own typed template). Diagrams, boards and code windows are exempt from the "same look three in a row" rule.
- Vertical videos keep native stills (the planner lays diagrams out on a 16:9 canvas).
- Renderer: every field of every element is validated and trimmed (ids, text, numbers, colours); at most 16 elements.

## Limits
- Positions come from the planner: overlapping boxes (e.g. a container title under its first member) are not auto-corrected.
- The planner's camera moves (`keep` / `move` / overview beyond the frame) are not animated: elements beyond the frame are clamped to its edge.
- Not done: D3 charts, LaTeX formulas, syntax colours in code windows, chat / terminal / browser windows as animations.

## Verification
- 61 unit tests (JUnit stand-in). A 66 s sample ("What happens when you tap Order", five diagram scenes, Kokoro voice) was assembled from real clips.
  Not run: Docker builds, the full Maven build, a lesson through the Spring pipeline. The sample's diagram plans were written by hand (the planner model writes them in the product).
