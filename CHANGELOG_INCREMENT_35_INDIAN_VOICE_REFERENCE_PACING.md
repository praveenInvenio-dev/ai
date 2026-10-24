# Increment 35 — Indian-English narration and reference-video pacing

## Scope
Targeted narration-only changes. No visual renderer, API, database, editor, or unrelated feature changes. Existing explicit voice selections remain respected.

## Changes
- Changed the configured fallback voice from Edge en-IN-NeerjaNeural to en-IN-PrabhatNeural (Indian English male) for projects which do not explicitly choose a voice.
- Updated StoryboardService's fallback voice to use the same Indian-English male voice.
- Strengthened Concept Explainer tutorial delivery direction to request natural Indian-English pronunciation, a warm adult tutor, conversational classroom cadence, clear technical-term emphasis, and no exaggerated foreign accent.
- Kept reference-video pacing near a steady conversational rate; sentence gaps remain around 0.34 s, with slightly longer question/lead-in pauses. Short/reel pacing remains tighter.
- Existing user-selected voices and native Indian-language voice routing are not overridden.

## Important runtime requirements
- Edge en-IN voices require internet access in the local TTS sidecar. If the sidecar or network is unavailable, the existing provider fallback chain may select another voice; inspect the job's `Voice used:` / `Voice:` warnings rather than assuming the requested voice rendered.
- For an expressive local voice clone, `narrator-in-male` requires a configured Chatterbox reference voice and a running Chatterbox sidecar. The project does not ship a licensed Indian voice reference recording, so this increment does not fabricate or bundle one.

## Validation
Source-level changes only. Full Maven build and end-to-end listening tests must be run in the deployed environment with its actual TTS containers and selected voice. Compare the generated audio to the provided reference for accent, cadence, intelligibility, and timing.
