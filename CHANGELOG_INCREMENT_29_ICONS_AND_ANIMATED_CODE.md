# Increment 29 — icons and an animated code window (Revideo)

## Added
- **Lucide icons (ISC licence, `revideo/LICENSE-lucide.txt`)** on animated flow steps and board card titles. 35 icons are inlined as path data (no network at render time) and picked by whole-word keyword
  (data -> wifi, cost / price -> coins, customers -> users, chip -> cpu, loop -> repeat, lock -> lock, ...). No keyword match means no icon. Revideo's own `SVG` component does not render in the headless renderer and its
  `Icon` component loads from the network, so icons are drawn with `Path` nodes (paths normalised with svgpathtools: Revideo's parser rejects Lucide's compact arc flags).
- **Animated code window (Revideo `Code` component):** `template: "code"`. The code from the plan's `code` device is split into one group of lines per sentence; each group is typed in when its sentence starts and its
  lines are highlighted while the rest dims; the whole listing lights up at the end. Chosen for scenes made of a code window (with optional cards / notes) when the renderer reports clips; a data structure or a
  button next to the code keeps the scene native. Native stills are the preview and the fallback.
- Backend: `renderCodeClip` + `codeOf` in the client, router rule (`code` kind), sync-stage clip like the other animated scenes.

## Limits
- The code is white on dark (no syntax colours: Revideo's `Code` needs a Lezer highlighter per language, none is bundled). Highlighting by lines works.
- Icon choice is keyword based and can pick a loosely related icon; labels with no keyword get none.
- Not done: D3-driven charts, LaTeX formulas (Revideo has a `Latex` component), a hand-drawn (Rough.js) look.

## Verification
- 59 unit tests (JUnit stand-in). A 75 s English sample (Kokoro voice) was assembled from real clips: animated boards with icons, two 3D lane scenes, the animated code window and animated bars.
  Not run: Docker builds, the full Maven build, a lesson through the Spring pipeline.
