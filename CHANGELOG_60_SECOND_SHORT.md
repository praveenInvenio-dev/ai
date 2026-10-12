# Concept Explainer - "60-Second Short" section

Combines the two uploaded reels:
- Reel A (gradient descent): title card (topic pill, big green title, grey subtitle, "Learn one concept a day"),
  one tiny concrete example with real numbers, value pills (w 0.2), huge metric (11.4 LOSS), plots with error
  lines / loss curve, word-by-word captions (spoken white, upcoming grey), outro card. ~136 wpm male, no music.
- Reel B (container vs VM): near-black monochrome UI, mono section label + title per scene, wireframe cubes,
  chip rows (1 2 3 cpu/ram/disk), info card with numbered key/value rows, tabs (1 · speed / 2 · size),
  comparison bars, question-led structure. Quiet music bed.

## New
- Format SHORT (UI: "60-Second Short" card + "60-second content" structure box). 1080x1920.
- Reel theme + portrait canvas in DiagramRenderer; new elements: titlecard, outro, node, chips, table,
  bars, tabs, value, metric, plot (fit / residual / decay / given line). Wireframe cube icon.
- Short prompts: question -> tiny example -> 2-4 steps -> payoff with a number -> name the concept ->
  real-world scale -> "Learn one concept a day." 130-160 words, 7-9 scenes, written in one LLM call.
  Missing title card / outro are added automatically.
- ShortCaptions: ASS karaoke (\k) captions from exact per-sentence TTS timing, burned with libass;
  fonts (Lato + Indic Noto) copied out of the jar for libass.
- Pacing 0.28 s gaps, 0.3 s scene tail, music bed at 6 %. Same glide/fade/overview camera and GPU sharing.
- Portrait scene thumbnails in the UI.

## Verified here
- Renderer, captions and the exact clip filter graph (xfade + intro overlay + ass) produced a vertical
  preview; Java package compiles; Angular build passes.
## Not verified here
- Real Ollama output and real TTS in Docker.
