# v24.11 - Editor batch 2 (Cardboard feature list, items 15, 23, 33/34, 52)

## Film effects (item 23)
Seven selectable effects, chainable, applied after the colour look: soft blur, soft glow, halation (warm highlight glow),
vignette, film grain, scanlines, cinematic bars. Migration V24 (`effects`). Chat: "add film grain", "add a vignette",
"remove effects". Verified by running the exact filter strings from the Java source, all seven chained, on a real video.

## Caption styles (item 15)
New `STACKED` (up to 3 words, one per line, each popping in on its own beat) and `LINE_REVEAL` (a line slides up and fades in
on a soft dark box) next to Bold reel / Clean / Kids / Cinematic / Minimal. Chat: "add stacked captions", "line reveal captions".

## Search what was said (items 10 partial, 14, 33, 34)
"Index speech" transcribes every clip once (cached), then a search box finds moments by their spoken words (all words must match,
prefix matching, accent folding for Latin letters, Indian scripts untouched), with timecodes. Each result can be previewed and
inserted on the timeline (after the selected shot or at the end; validated and versioned, so Undo works).
Chat: "find where I say new dashboard", "search for the cake", "where did I mention pricing?".

## Export for professional editors (item 52)
`GET /api/video-editor/projects/{id}/export/edl|fcpxml` -> buttons "EDL" and "FCPXML" next to Preview.
EDL = CMX3600 (frame-exact timecodes, V + A events, speed changes as M2 lines). FCPXML 1.9 with exact rationals for
23.976/29.97/59.94, one asset per source file, canvas format from the project aspect ratio, muted shots silenced.
Looks, effects, captions and titles are not part of interchange files (a note inside the file says so); FCPXML does not carry
speed changes (warned); media is linked by file name - relink to your originals if the editor asks.
NOT tested inside Premiere / DaVinci / Final Cut (not available here); the files are tested structurally.

## Tests run (JUnit stand-in, Maven not available in the build sandbox)
TimelineExporterTest 8/8, TranscriptSearchTest 8/8, SpeechCleanupTest 10/10, HighlightServiceTest 9/9, TeachingFrameworkTest 24/24;
editor chat commands 41/41 (`node frontend/tools/editor-command-check.js`); Angular build; Java syntax check of all new files.
Bugs found by the tests: EDL speed line must be zero-padded (`050.0`); `AspectRatio` uses `width()` not `getWidth()` (caught in review).

## Roadmap against the 55-item Cardboard list
Done 18 / partial 12 / not built but possible locally 18 / needs an external service or is out of scope for a local single-user app 7.
See the chat reply for the item-by-item table. Suggested next batches:
  A  voiceover from a script on the timeline (TTS incl. Indic voices + Voice Lab clones), detach audio, music/SFX library picker
  B  reference-video matching (pace + colour), named checkpoints, individual shot export, 4K export option
  C  subject-tracking reframe (face/person detection with smooth crop keyframes), speaker-aware variant
  D  caption editing (text, font, size, colour, position per caption), behind-subject captions
  E  keyframes + direct canvas editing + real multitrack (large: the renderer is single-track today)
