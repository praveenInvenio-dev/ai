# v24.17 — Scene overlap handling and Video Editor UX

## Scene layout
- Expanded the diagram renderer's overlap-resolution pass and added a deterministic free-space grid fallback when the ordinary downward/rightward nudge loop cannot resolve collisions.
- The fallback keeps all elements (it never silently drops content) and searches within the renderer's world-space layout that the camera can frame.
- Existing background layers and container semantics remain exempt from generic collision treatment; items may intentionally sit inside containers.
- This reduces unresolved collisions but cannot guarantee pixel-perfect collision-free rendering for every font, arrow, illustration, or animated state. A full raster/text-bound validator is still a separate future enhancement.

## Video Editor UX
- Replaced the loose, wrap-anywhere primary action row with three aligned workflow cards: AI Edit, Review, and Export.
- Made the main action labels descriptive, added supporting descriptions, consistent heights and spacing, responsive grid behavior, and visible progress status.
- Preserved the existing action handlers, project settings, timeline API, and editor behavior.
- Improved focus-visible styles and small-screen wrapping for primary workflow controls.

## Validation
- Existing editor regression script: 87/87 passed.
- ZIP integrity checked after packaging.
- Full Angular build was not run because frontend dependencies are not installed in this environment.
- Full Java/Maven tests were not run: Maven and Jackson dependencies are unavailable in this environment. A direct `javac` attempt was blocked by missing Jackson and IconLibrary dependencies, not by a reported syntax error in the edited method.
