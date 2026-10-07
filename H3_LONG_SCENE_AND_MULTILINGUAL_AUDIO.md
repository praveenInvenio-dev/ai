# H3 long-scene and multilingual audio pipeline

## Classic Story Production (V20)

Classic Story Production is **image-only for visuals**. MiniMax H3 is used only as the soundtrack engine. It does not generate or animate the story images in this path.

```text
Story scene
  -> generated scene image
  -> narration + dialogue + audioSpec
  -> MiniMax H3 audio-only workflow (32x32 disposable visual latent)
  -> voice + ambience + SFX + background music
  -> FFmpeg image/video assembly
  -> final story video
```

The Story Engine remains authoritative for the spoken words and structured audio direction. H3 supplies the performance and synchronized soundscape. Existing TTS remains a per-scene fallback if H3 audio cannot run.

### Long scenes

H3 audio requests are split by the Story Studio voice segments when a scene is longer than the configured H3 audio window. Each chunk contains only its own dialogue/narration, then the resulting audio segments are concatenated before the image video is assembled. This prevents a long scene from repeating the same dialogue across multiple H3 calls.

### Audio ownership

When H3 audio mode is enabled, the classic renderer does not add the old automatic music bed on top of H3's soundtrack. H3 is responsible for:

- narration
- character dialogue
- ambience
- sound effects
- background music

The subtitle track is still generated from the Story Studio scene text.

## Standalone H3 video

The separate **Video Generation** and **Video Sequence** flows continue to use MiniMax H3 for actual video generation with native audio. That is intentionally separate from Classic Story Production.

## Configuration

```text
LOCAL_AI_AUDIO_H3_ENABLED_IN_STORY_PIPELINE=true
LOCAL_AI_AUDIO_H3_TURBO=true
LOCAL_AI_AUDIO_H3_MAX_DURATION_SECONDS=10
```

The existing H3 model stack is reused: diffusion model, Qwen3-VL text encoder, video VAE and audio VAE. No second TTS model is required for the preferred classic path.
