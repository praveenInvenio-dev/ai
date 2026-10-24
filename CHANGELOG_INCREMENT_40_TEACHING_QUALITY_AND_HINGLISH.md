# Increment 40 — Teaching quality and Hinglish pronunciation-safe scripts

## Goal
Raise the default quality bar for every generated lesson. Quality is judged by learner understanding, not the presence of terms and diagrams.

## Changes
- Added a mastery checklist: misconception, learning outcome, prerequisites, core rule, mechanism, worked example, application, boundary/common mistake and comprehension check.
- Required claim -> reason/mechanism -> demonstration with consistent values through one complete example.
- Tightened the standard for technical, math and systems explanations.
- Added Hinglish guidance: Hindi grammar can remain Latin-script Hinglish, but English technical terms retain canonical spelling; no invented phonetic spellings or exaggerated accent cues.
- Required explicit scene objectives and distinct conceptual progress.
- Required visuals to show meaningful state changes/relationships and narration to explain why they matter.

## Important limits
- This is prompt-level quality enforcement, not a proof of correctness. Models can still make mistakes; generation should surface QA findings and repair weak scenes.
- Canonical spelling helps the text pipeline but cannot guarantee pronunciation. Actual pronunciation depends on the selected TTS engine/voice and its mixed-language support.
- This package does not silently switch the active TTS provider.
- Full Maven tests and fresh rendered videos were not run here. Validate with the same Java Stack and Pythagorean lessons before production.

## Acceptance criteria
1. Every major concept has a plain-language explanation and a why/how.
2. One complete worked example has consistent inputs and outputs.
3. A beginner can state when the rule applies and one common mistake.
4. No unsupported claims or unexplained jargon.
5. Hinglish uses Hindi sentence grammar and canonical English spelling for technical terms.
6. English technical words are checked in actual audio, not only text.
7. Word count stays inside the configured budget.
8. Visual changes match narration; recap checks understanding rather than repeating headings.
