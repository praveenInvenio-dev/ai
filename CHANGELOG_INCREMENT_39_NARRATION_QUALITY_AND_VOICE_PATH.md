# Increment 39 — Narration quality and voice-path correction

## What the report exposed
The previous report selected `en-IN-PrabhatNeural` through Edge. That engine provides good pronunciation but does not implement the project's emotion/acting-direction controls. `VoiceDirection` can only make very small speed/pitch adjustments on Edge, so labels such as `dramatic`, `curious`, or `playful` do not create real acted performances. Prompt changes alone cannot fix that engine limitation.

## Changes in this increment
- Tightened the TeachingPlanner writing contract: hard word ceiling, fewer generic creator phrases, stronger cause-and-effect explanations, spoken-language sentence design, and scene-specific changes in emotional delivery.
- Tightened ScriptDoctor's long-script warning from 15% above the selected maximum to 5% above it. This is a warning/repair trigger, not a guarantee that the LLM rewrite will hit the target; verify the final word count.
- Kept the selected voice provider configurable. No forced provider switch was made because Chatterbox requires its container/model and language support differs from Edge/Piper/Indic engines.

## Required deployment/configuration for expressive English
For a real emotional performance, select the Chatterbox provider in the deployment `.env` and ensure its container is healthy; do not rely on Edge voice IDs alone:
`TTS_PROVIDER=chatterbox`
`CHATTERBOX_VOICE=narrator-in-male`

Then rebuild/recreate the backend and TTS container and inspect the generation report. If the report still says the active engine is Edge, the configuration/voice routing is not using Chatterbox. For Indian regional languages, select a language-capable engine instead; Chatterbox Turbo is not the universal multilingual route.

## Honest validation status
Source changes only. A live voice sample, full Maven test suite, and rendered lesson were not available in this environment, so audio improvement is not claimed as verified. Compare the same 20-30 second script in Voice Lab using the active provider before rendering a full lesson.
