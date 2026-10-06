# H3 long-scene + multilingual audio pipeline

## What changed

Story production now treats **TTS duration as the authoritative scene timeline**. H3 is only a visual-shot generator.

A 19-second scene is no longer forced into one 5/10-second H3 request. It is rendered as a continuous chain such as:

```text
19.0s scene
  -> H3 8s shot
  -> H3 8s continuation shot (starts from shot 1 last frame)
  -> H3 3s continuation shot
  -> concatenate
  -> exact saved TTS/dialogue track
```

The same environment is carried between shots using the previous shot's last frame plus a continuity contract that locks:

- character identity, face, hair and clothing
- props and their positions
- architecture / road / landscape layout
- weather and time of day
- lighting direction and shadows
- palette and atmosphere
- camera/action continuity

## Audio / pronunciation

H3 native speech is **not used for story narration**. The story's generated TTS WAV is the authoritative audio track.

This avoids H3 pronunciation errors in Hindi, Kannada, Tamil, Telugu and other Indian languages and guarantees that every word in the saved dialogue is retained.

For stories without an explicitly assigned character voice profile, the backend prefers language-matched local Edge neural voices for supported Indian languages. IndicF5 references are also available when the `indic` compose profile is enabled.

For Hinglish, story generation now prefers **Devanagari for Hindi words and Latin script for English/technical terms**, which produces substantially more reliable Hindi pronunciation than Romanized Hindi.

## Scene timing

The scene-duration estimator is no longer capped at 10 seconds. Actual TTS synthesis updates:

```text
scene.narrationSeconds = measured WAV duration
scene.imageDurationSeconds = narration duration + natural tail
```

The H3 shot cap is configured separately:

```text
LOCAL_AI_ANIMATION_MINIMAX_H3_SHOT_SECONDS=8.0
```

This is a **shot** limit, not a story-scene limit.

## Existing stories

Existing audio assets are intentionally reused by the idempotent production pipeline. If an old story was generated with poor Roman-Hindi pronunciation or an English-only voice, regenerate that scene's narration once after deploying this build; the new H3 rendering will then use the corrected WAV.
