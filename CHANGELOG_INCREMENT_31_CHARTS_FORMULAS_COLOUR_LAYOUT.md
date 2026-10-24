# Increment 31 — D3 charts, LaTeX formulas, syntax colours, automatic overlap fixing

## Charts (D3: d3-scale, d3-shape, d3-array; ISC / BSD)
- New planner device `chart`: `title`, `style` = line | area | bar | donut, `lines` = ["label|value", 3-8 points], `text` = unit. Real axes with round tick values. The line draws itself point by point over a soft area,
  bars grow one after another with counting values, a donut sweeps round with a legend and percentages. `POST /render-clip {template:"chart"}`.
- Without clips the same data draws on the native `bars` device (the unit becomes the third part of each line).

## Formulas (Revideo `Latex` component, MathJax bundled: no network)
- New planner device `formula`: `text` = LaTeX, `title` = label, `lines` = [one plain-words caption]. Up to four per scene, each revealed at its sentence; earlier ones dim, the newest glows once.
- The renderer removes TeX macros that load files or run code (`\require`, `\href`, `\url`, `\class`, `\style`, `\unicode`, `\input`, `\def`, ...) before typesetting.
- Without clips a formula becomes a readable note (`\frac{a}{b}` -> (a) / (b), `\cdot`, Greek letters, `^2` -> ²).

## Syntax colours in code windows
- Lezer parsers for Java, JavaScript / TypeScript, Python, C / C++ and JSON (MIT) with a dark theme (keywords purple, types amber, strings green, numbers orange, comments grey, functions blue).
  The parser is chosen from the file name's extension; unknown extensions use the JavaScript grammar.

## Automatic overlap fixing for animated diagrams
- Between creating the elements and drawing the arrows a layout pass keeps container members below the container title and inside it, pushes overlapping elements apart along the axis of least overlap
  (minimum gap 14 px at 1280 wide), and keeps everything inside the frame. Nothing moves when nothing overlaps. Arrows are drawn after, so they follow the moved boxes.

## Router
- A scene of `formula` (+ label / note / pill / heading) becomes an animated formula; `chart` (+ the same) an animated chart. Mixed scenes (a chart next to a tile) stay native. Both are 16:9 only (vertical keeps stills).

## Limits
- Invalid LaTeX makes that clip fail; the scene then keeps its native stills (the formula as readable text) and the lesson warns.
- The overlap pass cannot create space: if the planner puts two tiles so close that an arrow between them is a few pixels long, the arrow stays short.
- Still not animated: terminal / browser / chat windows, photos, and the planner's camera moves. Charts show the numbers the planner gives: it must mark illustrative data as illustrative.

## Verification
- 65 unit tests (JUnit stand-in). A 58 s sample (line chart, three formulas, Python code window, donut, diagram, bar chart; Kokoro voice) was assembled from real clips.
  Not run: Docker builds (the revideo image now also installs the Lezer, CodeMirror and D3 packages), the full Maven build, a lesson through the Spring pipeline. The sample plans were written by hand.
