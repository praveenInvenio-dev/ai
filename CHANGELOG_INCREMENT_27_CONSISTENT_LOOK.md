# Increment 27 — one consistent look across engines, bar fix, real-voice sample

Built while producing a full sample lesson with every engine and a real Kokoro voice (see the sample video delivered with this increment).

## Visual consistency
- 3D (Three.js) and animated (Motion Canvas / Revideo) scenes now use the same heading as the native renderer: a small grey kicker (the plan's own label, e.g. "02 · 1865")
  over a regular-weight title, scaled to the frame. Before, they used a large bold centred title and no kicker, so a lesson looked like three different videos.
  The backend sends the plan label as `kicker`.
- Animated step boxes size their text to the box width: with four steps, "Efficiency" used to overflow its box.
- Bars: the number column is measured and the bar track ends before it ("1000 rupees" used to run into the bar).

## Findings from the sample (not code changes)
- With a real voice (Kokoro am_michael via sherpa-onnx) 175 words ran 79.7 s (about 132 words per minute including pauses). "1-Minute Quick Learn" is budgeted at 140-180 words,
  which is about 65-80 s. Either budget about 120-135 words, or call the format "about 80 seconds".
- Final audio measured: -16.3 LUFS, 4-8 kHz clarity -13.3 dB (the app flags "muffled" below -21), 24 pauses of 0.2 s or more (median 0.67 s, 20% of the time).

## Verification
- 53 unit tests (JUnit stand-in). The sample was assembled with the real router, humanizer, spoken-text and pacing code, the real DiagramRenderer, the renderer service
  (Three.js template, two Revideo clips) and ffmpeg scene assembly equivalent to the backend's. The script and the native-scene plans in the sample were written by hand
  (in the product the planner model writes them). Not run: Docker builds, the full Maven build, a lesson through the Spring pipeline.

## Hindi / Indic narration (found while building a Hindi sample)
- Labels for 3D / animated scenes are never derived from non-Latin narration (they would have put Hindi words on an English-screen video); sentence splitting also knows the danda (।).
- Prompt rule: in Hindi / Indic narration write every number, year and amount as words, because the voice reads digits with English rules.
- Sample: Hindi script, Piper hi_IN-rohan (raw clarity -23 dB; with the app's chain: -16.8 dB at +4 dB treble, -15.5 dB at +6.5 dB), same scene plans and engines as the English sample
  (the router decides from the English plan devices and titles, so engine choice is the same for Hindi). 54 unit tests pass.
- Not possible in the test sandbox: Indian-accent ENGLISH (needs the Chatterbox clone of the Edge en-IN voice or Edge itself, both need models / internet) and the cloud Edge Hindi voices (Madhur / Swara).

## Entertaining narration + mixed languages (Hinglish, Kannada + English)
- "Humorous Explainer" track rewritten: a relatable real-world moment from Indian daily life (UPI, Jio data, Swiggy, chai, autos, cricket, Bengaluru / Mumbai traffic, Tatkal) that truly maps to
  the concept; setup -> spoken PAUSE -> small comic contrast -> immediate exact explanation; one joke per scene; humour never changes a fact or targets a person or community; mixed-language
  narration (Hinglish, Kannada + English ...) keeps the base language's grammar with English technical terms. Voice direction: a smile in the voice, a clear half-second pause before the punchline.
- A sentence ending in "..." is now a real beat: the voice slows slightly and the pacing adds 0.3 s of silence; "…" is kept as "..." for the voice instead of being turned into a full stop.
- Samples: Hinglish (Piper hi_IN-rohan, Jio data story) and Kannada + English (espeak-ng STAND-IN voice, Bengaluru traffic story). The Kannada voice is a robotic formant synthesizer used only because
  no Kannada neural voice could run in the test sandbox (it needs PyTorch); Kannada wording was written without a native review. 55 unit tests pass.
