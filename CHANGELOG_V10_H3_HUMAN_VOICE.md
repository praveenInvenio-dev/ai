# v10 — H3 Human Voice Pipeline

## What changed

- MiniMax H3 is now explicitly **soundscape/SFX only** in Classic Story Production. It is no longer responsible for spoken dialogue.
- Removed the Rumik dependency from the normal Classic Story Production path. The legacy Rumik container is now behind the `legacy-rumik` Compose profile.
- Dialogue is generated through the existing configured story-language TTS/voice-profile pipeline.
- Added a production voice-processing chain:
  - 48 kHz stereo normalization
  - high-pass cleanup
  - gentle 250 Hz mud reduction
  - 3.3 kHz presence lift
  - moderate compression
  - de-essing
  - -16 LUFS normalization
  - -1 dB true-peak safety ceiling
- H3 soundscape is now **side-chain ducked under speech**, rather than simply mixed at a fixed level.
- Final scene audio remains 48 kHz stereo and is suitable for the existing AAC final-video mux.
- Existing old Rumik/H3 active narration assets are not silently reused when the new H3 story pipeline is enabled; scenes are regenerated through the new `tts+humanized-voice+h3-soundscape` path.
- Funny Skit Studio also uses the configured TTS path and the same voice-humanization stage before H3 sound design.

## Intended flow

`Story dialogue -> configured TTS -> humanize voice -> H3 ambience/SFX -> duck ambience under speech -> final mix -> video mux`

H3 prompts remain explicitly non-verbal (`NO SPEECH`, `NO VOICES`, `NO DIALOGUE`, `NO SINGING`).

## Compatibility

- Standalone Video Generation / H3 video generation is unchanged.
- Existing story/image generation flow is unchanged.
- Rumik files/configuration are retained only as a legacy compatibility option.
