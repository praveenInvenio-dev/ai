# Increment 58 — Explainer Studio: cleaner frame, double captions fixed, more varied visuals

Includes Increments 55-57.

## From the review of a 30 s "multithreading in Java" reel
- **Visualise / Animate / Explain pills removed.** On a phone they read as buttons. The header is now the video's real
  title (not the raw prompt "explain multithreading in java") with a small accent bar. Portrait stage is ~10 % taller.
- **Double captions fixed.**
  1. The last line was shown twice: as the caption AND as the end-card CTA pill ("…friend who writes slow code").
     Captions now hide while the end card is up; the pill is the caption.
  2. Caption words ran together ("downloadsa huge", "Twothreads.One"): the thick outline + active-word zoom ate the
     space. Spacing is now sized for both.
- **Tech notes no longer cut off** half-typed ("new Th…"): the typewriter finishes within ~35 % of its sentence.

## More varied visuals
- New primitives:
  - `race` — before/after race bars with live stopwatches; winner flashes ("1 thread 8 s vs 4 threads 2 s").
  - `stat` — one big number that counts up with a burst and optional progress ring ("4x", "86 %").
  - `kinetic` — kinetic typography punchline: words slam in, key word gets a marker swipe.
- Writer rules: every scene must look different; pick the visual that proves the sentence (faster → race, a number → stat,
  definition → kinetic, process → flow/icons); topics naming a language/framework must show one real 3-7 line snippet.
- Variety lint: a scene that repeats the previous scene's visuals is sent back for one rewrite with a different main primitive.
  The writer also sees which primitives earlier scenes used.
- Background: each scene gets its own light-pool layout and colour order.
- Subject packs list the new primitives near the top.

## Content review notes (the uploaded reel)
Hook + analogy (checkout lanes) + payoff were solid for 30 s, but: nothing Java-specific was shown (the "in Java" promise
was not kept), the thread lanes stayed on screen for ~20 s, and "four tasks move at once" needs "on four cores". The new
rules address the first two; the third depends on the model — larger models handle such nuance better.

## Verified
19/19 API tests; portrait (academic) and landscape (dracula) stills of race / stat / kinetic, header, captions and end card.
