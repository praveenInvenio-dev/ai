# Fixes from three real lessons (a 60 s reel, a JEE physics lesson in Hindi, a toddler alphabet lesson)

Measured from the uploaded videos first, then fixed. All three were at -14 LUFS with no clipping, so loudness was not the problem.

## What was wrong, and why
1. **Hindi sounded terrible**: the model wrote the narration in LATIN letters ("Ek cricket ball bowler ke haath se nikal kar boundary...", visible
   on screen in the first frame). A Hindi voice reads that like English gibberish. The lesson flow had no script check (only the skit flow had one).
2. **Muffled voice** in that lesson: 99 % of its energy below 3.5 kHz; 4-8 kHz was 25 dB below the voice body (a clear voice: about 15 dB).
3. **Speed requests were ignored**: the Chatterbox server parsed `speed` and never used it; IndicF5 had no speed handling either. So the toddler
   pace (0.86x), the reel's brisk pace and every other pacing request did nothing (toddler narration measured 6.7 syllables/s vs 7.5 for adults).
4. **The 60 s reel came out at 93 s**: a 155-175 word budget is about 67 s of speech, plus gaps, cards, my reveal pauses; nothing enforced the length.
5. **No animated physics (Manim)**: the form only sent "Manim on" when the service was reachable (so nothing said it was off), and a small model
   rarely volunteers the optional animation block.
6. **Flat look**: wide diagrams in a tall 9:16 frame fill the width but only 15-25 % of the height; timeline strips landed on the scene title.
7. **Toddler "find it" tiles** showed plain colour squares instead of the letters (the option's second part was read as an icon name).

## Fixes
- Hindi / Marathi / Kannada ... narration must be in its own script: a stronger prompt rule, automatic detection (reusing IndicSpeech), and an automatic
  repair pass (two tries, same line count). Romanised Hindi on screen (hook callouts) is translated to English. Anything unfixed is reported in the notes.
  Tests: the real sentence from the video is flagged; Devanagari, mixed text and English (incl. look-alike words) are not (20/20).
- Speed now works in the Chatterbox and IndicF5 servers (pitch-preserving: rubberband, atempo fallback). On real speech: durations exact, pitch kept.
- Dull voices get a treble shelf sized to their measured deficit (a Java FFT measurement identical to the Python one): the muffled voice -25.4 -> -16.0 dB,
  the toddler voice -23.6 -> -17.5, a clear voice untouched, true peak -0.7 dBFS. A lesson note appears if a voice is still dull.
- Reels: the draft is shortened automatically when far over its word budget; reveal pauses are cut to 30 %; once all narration is recorded the
  total is measured and, if over, every scene is sped up by the same factor (at most 1.3x) with timings rescaled. End-to-end: a draft twice too long
  finished at 65.5 s.
- Manim: the form sends the user's choice; the lesson notes say plainly when Manim was wanted but the service is not running (with the command);
  for physics / maths lessons that got no animations, ONE focused question to the model picks at most a quarter of the scenes and their numbers; every
  answer is validated against the service (end-to-end: graph and projectile scenes rendered, a hostile formula dropped).
- Layout: content stays below the heading zone (not in illustrated scenes); in portrait the analogy stacks vertically and the timeline runs vertically;
  the toddler `choose` tiles draw letters / digits / emoji as big glyphs (colour words are colours, not icons); the reel look has a soft spotlight.

## Verified (real service code, real FFmpeg, real Manim sidecar, a fake model that misbehaves like the real one)
Hindi script repair 7/7; short fits 65.5 s; physics + Manim 5/5 and Manim-down 2/2; old vs new renders side by side. Regression: review-flow 25/25,
Manim lesson 18/18, plain lessons 4/4 clips, editor commands 83/83, Angular build, full-backend compile (only old files fail in the sandbox).

## Not verified / still open
Real listening (everything about voices is measured, not heard); the two speech servers inside Docker (rubberband availability in their ffmpeg is
unverified, atempo is the fallback); which engine produced the muffled Hindi voice (the lesson notes name it); table / bars / compare devices are still
wide in portrait; the toddler title card is still sparse and the AI image model still draws gibberish letters on books; a very weak model can still fail
the Hindi repair (it is reported); the time-fit stops at 1.3x.
