# v24.10 - Cardboard-style smart editing (cardboard.ai)

Cardboard (cardboard.ai) is an agentic AI video editor: describe the edit, refine it by chat, cut dead air /
filler words / retakes, pull the best moments from long recordings, caption and reframe. What this release adds
to the editor, built to run locally (no cloud, no paid API):

## 1. Chat-directed editing (extends the existing "Edit assistant")
Deterministic commands (instant, nothing to hallucinate); anything else still goes to the AI planner as before.
- settings: "add captions" (+ bold/clean/kids/cinematic/minimal), "remove captions", "warm look" / "make it black and white" /
  "vintage filter" / "no filter", "add title: ...", "remove the title", "make it vertical for reels" / "for youtube" / "square",
  "sync cuts to the beat", "stabilize", "reduce background noise"
- shots: "speed up shot 2", "slow motion shot 3", "slow down clip 1 2x", "trim shot 3 to 4 seconds",
  "make the intro faster", "tighten the pacing", "slower pacing" (+ existing remove/lock/mute/move/duplicate/duration)
- jobs: "remove the dead air", "cut the ums", "find the best moments", "make shorts from this video"
- "undo" goes back to the previous saved version.
`frontend/tools/editor-command-check.js` runs 32 phrases with expected outcomes (all pass).

## 2. Clean up speech (button + chat)
Transcribes with faster-whisper (word timings, cached next to the upload) and rebuilds the timeline from the speech:
- removes filler words (um, uh, er, hmm ... and "you know" / "I mean" when used as a tic);
- shortens silences longer than 0.7 s to a 0.25 s breath, trims leading/trailing silence;
- drops retakes: an earlier take of a repeated sentence, and "sorry, let me redo that" talk itself;
- subtle punch-in on alternate jump cuts. Fillers are English only; dead-air and retake detection work in any language.
- needs "Analyse" first (it measures the clips). A problem is reported on the job, the project is never flipped to FAILED.

## 3. Find best moments (button + chat)
15-60 s self-contained moments from long recordings: the story model chooses by transcript line number (so times are exact),
snapped to sentence boundaries; a transparent scoring fallback (questions, numbers, hook words, pace, never starting with
"and/so/but") runs when no model answers. "Use as vertical short" builds the timeline, sets 9:16 and captions.

## Tests (actually run)
- `SpeechCleanupTest` 10/10, `HighlightServiceTest` 9/9, `TeachingFrameworkTest` 24/24 (run with a small JUnit stand-in
  because Maven is not available in the build sandbox); editor commands 32/32; Java files syntax-checked.
- Found and fixed by the tests: "sorry, let me redo that" removed only "sorry"; "make the captions bigger" switched captions on;
  "make it for youtube", "warmer" and "sync cuts to the beat" were not recognised.
- NOT verified: real transcription (faster-whisper model download is not reachable from the build sandbox), real footage,
  Maven compile.

## Cardboard features NOT built (and why)
Generate video/images/music/SFX inside the editor (the app has these generators but they are not wired to the timeline yet),
web B-roll sourcing (needs a stock-footage API key), clip from a YouTube/Instagram link (needs a downloader),
match pacing/colour of a reference video (planned: worker analysis of a reference file), voice-clone narration into the timeline,
smart reframing that follows the speaker (needs face tracking; current reframing is a centre crop), share links / team comments,
Claude Code / Codex integration.
