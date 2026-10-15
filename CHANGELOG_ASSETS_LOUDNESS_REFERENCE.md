# Richer assets, loudness, export presets, reference-video matching

## Asset audit (before)
75 element types, 13 themes, only 75 hand-drawn icons (tech + body), 4 sound effects, 3 music tracks.

## Icon library (new) - `backend/src/main/resources/icons/`, rebuilt by `tools/build_icon_library.py`
- 485 line icons (Tabler Icons, MIT): tech, science, maths, business, education, transport, navigation. White on transparent, tinted to the
  lesson's colours at draw time.
- 329 colour 3D emoji (Fluent UI Emoji, MIT): animals, food, vehicles, nature, shapes, objects, places (landmarks incl. Hindu temple).
- Kids looks prefer the colourful emoji; every other look keeps the hand-drawn icons first, then line icons, then emoji, so existing lessons
  look the same. Names are matched forgivingly ("Brand Docker", "node.js", "laptop", "firewall", "rupee").
- The planner is shown an icon catalogue matched to the lesson (KIDS, GK, SCIENCE, MATHS, BUSINESS, TECH, GENERAL), not all 800 names.
- Licences are kept in `icons/ATTRIBUTION.md` (MIT notices for both sets).

## Seven new diagram devices
flowchart (start / process / decision / end, yes-no branches, loops back), sequence diagram, data structures (array, stack, queue,
linked list, hash table), periodic table (highlight by symbol or group), reaction energy profile (Ea, delta H, catalyst route),
lens ray diagram (convex / concave, image position and kind from u and f), funnel (stage, value, conversion).
Model-style names map onto them (algorithm, sequence_diagram, linked_list, hash_table, periodic_table, energy_profile, ray_diagram,
sales_funnel ...); a "queue" / "linked_list" / "hash_table" keeps its shape. Documented in the planner's device catalogue.

## Sound effects: 4 -> 20
Sixteen synthesised by `tools/build_sfx.py` (sine waves, noise and envelopes; no third-party audio, so nothing to licence): pop, click,
tick, ding, success, error, swipe, riser, impact, typing, shutter, bubble, coin, notification, boing, tada. Stored as plain WAV (an AAC
encoder rang on sharp starts and clipped). "add a ding at 0:04" works in the editor chat.

## Loudness normalisation
Two-pass EBU R128 (FFmpeg loudnorm, linear gain, picture untouched): social -14 LUFS, podcast / lessons -16, broadcast -23. Lessons are
evened out by default (-14, or -16 for classroom); the editor has a picker and chat commands, applied last on a final render. A video
without audio, or silent, is skipped.

## Export presets, and "1080p" now means the short side
8 platform presets (YouTube, Shorts, Reels, TikTok, Instagram feed, LinkedIn, classroom, broadcast): resolution, frame rate, loudness; a hint
says when the project's shape differs from the platform's usual one. Resolution is the SHORT side, so a vertical video at 1080 is
1080 x 1920 (it used to be shrunk to 608 x 1080).

## Reference-video matching
`POST /api/reference/analyse`: measures cut pace, "something new appears" pace, loudness, silence, brightness, contrast, colourfulness,
warmth, palette and shape of a reference video (first 150 s) and suggests editor settings (pace, look, loudness, export) and a lesson look
and motion. Editor: Finish panel -> Match a reference video -> Apply. Lessons: Brief & story -> Take the style from a reference video.
Nothing is stored. Measured on real references: the Instagram reels at -14 LUFS (social), the IRCTC 3D reel at about 25 new things per
minute (dynamic, blueprint look), the MCP tutorial as balanced / reference look.

## Verified
Icons and every device rendered and viewed in dark and light looks; lens numbers checked (convex u=30 f=10 -> v=15 real inverted;
concave u=25 f=15 -> v=-9.4 virtual); 9 model-style names resolve to the right device and shape; loudness: quiet (-45) and loud (-15.6)
inputs land within 0.1 LU of all three targets, a real lesson went -29.8 -> -13.9 LUFS; export sizes for 9:16, 16:9, 4:5; reference
analysis on synthetic videos with known answers and on five real references; the controller's real code with a real upload (200, 400 for an
empty file and a text file, no temp files left); all 20 sound effects rendered through the finisher and audible at their times;
review-flow test 25/25, Manim lesson test 18/18, plain lessons 4/4 clips, editor commands 79/79, Angular build.

## Not verified / known gaps
Docker builds (manim image); the editor module inside Spring and a database (only partial compile here); a real model choosing icons and
devices well; how the sound effects sound (checked by measurement only); the planner prompt grew by about 1 k tokens (icon catalogue).
Flowchart and sequence diagrams are small in a full frame (the lesson camera zooms in a little). Faint banding rings remain in dark themes.
Still not built: Hindi dub in the editor, speaker-following reframe, caption editing, keyframes / multitrack, B-roll sourcing.
