# Increment 65 — Explainer Studio: Hinglish, clearer content, no doubled text

Includes Increments 55-64.

## From the review of the UPI reel (47 s, Hindi)
- **Text shown twice at the start**: the hook card ("पैसे गायब... और पहुँच गए!") and the caption of the same spoken words
  were on screen together. Captions now wait until the hook card has faded (same rule as the end card).
- **Content not clear**: 15 s of hook over a meaningless "Idea → Result" visual, no on-screen analogy, the "how it works"
  never stated plainly, lots of drama words ("जादू", "बूम") without the fact behind them, bookish Hindi
  ("बहीखाता", "तालमेल").

## Changes
- **Hinglish** language: captions in Roman Hinglish ("Socho tum ₹100 bhejte ho…"), the voice reads a Devanagari copy of the
  same sentence (`speech`), so Hindi voices (IndicF5 / Edge Swara-Madhur / Indic-Speak / Chatterbox Hindi) sound natural.
  The writer is told to talk like Indian creators do: English technical words, casual spelling, Indian daily-life analogies.
  Lint makes sure captions are Roman and every sentence has a Devanagari `speech` (else one rewrite).
- **Reel structure** is now hook → **analogy** → demo → payoff. The analogy scene shows the analogy on screen and says which
  real part each analogy part stands for ("post office = NPCI").
- **Outline** now plans `analogyMap` (real ↔ analogy pairs) and `keyPoints` (the 3 facts a viewer must be able to repeat:
  what it is, how it works, why it matters); every scene is written against them.
- **Clarity rules**: say the idea in plain words; no drama words without the fact; concrete labels only.
- **Generic visuals rejected**: elements labelled "Idea / Result / Step 1 / Input / Output…" trigger a rewrite with real
  labels from the topic.

Verified: 21/21 API tests (Hinglish lint test added; generator test updated for the 4-scene reel); Angular build.
