# Increment 28 — animated boards and real 3D clips (more motion, less still pictures)

Feedback on the samples: they looked like the old integration (still pictures cross-faded). Only the two flow scenes were real animations; the 3D scene was 4 stills; cards, compare tables and bars were stills.

## What was added
- **3D clips (Three.js, timeline mode):** `three-scene.js` has a `renderTime(t, starts)` mode. Each element pops in when the narration reaches its sentence and the camera keeps moving. The renderer captures it at
  30 fps and encodes it with ffmpeg: `POST /render-clip {engine:"THREE_JS", starts, total ...}`. The image installs ffmpeg. Before, a 3D scene was the same pictures cross-faded.
- **Animated boards (Revideo, `revideo/src/scenes/board.tsx`):** cards / compare tables / bars from the plan's own elements, drawn like the native renderer but animated: each element slides in with its sentence,
  compare rows follow one by one, bars grow while their numbers count up. `POST /render-clip {engine:"MOTION_CANVAS", template:"board", elements, starts, total}` (elements are validated and trimmed).
- **Backend:** `renderBoardClip` / `renderThreeClip` in the client; the sync stage (`attachMotionClip`) now builds a clip for flow, board and 3D scenes once the real sentence start times are known and uses it instead of
  cross-faded stills. A failed clip falls back to native stills (boards) or animated stills (flow, 3D) with a lesson warning. Native stills are still rendered for boards (preview + fallback).
- **Router:** a scene made only of cards / compare / bars / tiles / notes becomes an animated board when the renderer reports clips (not for kids lessons; a code window next to the cards keeps it native).
  Boards are ranked below real flows, may form runs, and the "same special look three in a row" rule now compares engine + kind (a board and a flow are different looks).
- Fixed on the way: a number tween that set text reactively did not refresh in the renderer (set per frame instead); bar track width now leaves room for the number column; a demoted board no longer keeps a stale "board" kind.

## Still pictures remain for
Code windows, terminals, browsers, data structures, formulas, plots, photos and AI artwork (native renderer), and every 3D template other than orbit / molecule / crystal / layers / axes / lanes.

## Verification
- 57 unit tests (JUnit stand-in). Real clips rendered through the renderer service for flow, board (compare, cards, bars) and 3D; a full 100-second English sample with the real Kokoro voice was assembled with the router,
  speech code, native renderer, renderer service and ffmpeg scene assembly. Not run: Docker builds, the full Maven build, a lesson through the Spring pipeline.
