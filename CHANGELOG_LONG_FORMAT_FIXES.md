# Fixes from reviewing video (45) - 6.5-minute landscape lesson ("Why Java?", storyboard style)

- Arrow labels ("Same Code" x3 + "Machine Code") stacked on each other and were covered by boxes: labels are now
  drawn last and each slides along its own arrow (or just above/below it) until it hits neither a box nor another label.
- Elements half on a container border (".class file" on the JVM box, Windows/macOS/Linux on its bottom edge): anything
  mostly inside is moved fully inside, anything mostly outside fully outside.
- Overlapping windows (two "SwiftRoute.java" code windows on top of each other): when there is no room the later one
  is placed beside it and the camera pulls back, instead of leaving them overlapped.
- Light styles: the soft round background "lift" banded into visible rings - replaced with a plain vertical wash.
- Quotes wrapped twice (""Assign driver to package 101"") - existing quote marks are stripped.
- Small diagrams in big empty frames: lessons also frame them closer (up to 1.3x; reels 1.6x).
- Prompt: containers need a title and their members inside; update code/lists by re-declaring the same id;
  leave room between boxes for arrows.
- Voice: every lesson now records "Voice used: ..." in its notes (e.g. "Ryan - US English ... pick Arjun / Meera - Indian
  English for an Indian accent"). If the Indian-English clone is not ready, the Edge en-IN fallback is reported too.
