# Rumik OSS-1 + MiniMax H3 Classic Story Audio

Classic Story Production remains image-based. This mode does **not** generate H3 video.

For speech-first Indian-language stories:

1. Story Engine remains the source of truth for narration/dialogue, emotion and audio spec.
2. Rumik OSS-1 generates the spoken narration/dialogue at 24 kHz.
3. MiniMax H3 runs through the existing ComfyUI H3 audio path as a sound-design engine only.
4. H3 is explicitly instructed to generate non-verbal ambience, foley/SFX and instrumental music, with no speech or vocals.
5. FFmpeg mixes Rumik speech over the H3 soundscape.
6. The mixed WAV becomes the scene narration/audio track used by the existing image-based video renderer.

## Story language selection

The existing **Story language** dropdown in Create Story is reused for Rumik speech. It now includes the 22 languages advertised by the Rumik OSS-1 model card (plus English/Indian English/Hinglish and Auto-detect). The selected value is carried into the episode/story language and used by the speech pipeline. Auto-detect continues to use the Story Engine script detection where supported.

Rumik currently documents delivery-control accent presets for Hindi, Telugu, Tamil, Kannada, Bengali, Punjabi and Indian English; for other supported languages the model still receives the selected language through the story text, while the delivery description uses a natural accent.

## Configuration

```env
LOCAL_AI_AUDIO_H3_ENABLED_IN_STORY_PIPELINE=true
RUMIK_SPEECH_ENABLED=true
RUMIK_TTS_BASE_URL=http://tts-rumik:5006
RUMIK_SPEAKER=Ira
RUMIK_MODEL=rumik-ai/rumik-oss-1
RUMIK_DEVICE=cuda
```

The first Rumik request downloads the model into the persistent `rumik-cache` volume.

## Language coverage

Rumik OSS-1 advertises 22 Indic languages plus English. Its released delivery controls explicitly include Hindi, Telugu, Tamil, Kannada, Bengali, Punjabi and Indian English accents. The model should still be tested per language/story style before production rollout.

## Fallback

If Rumik or H3 fails for a scene, the existing story TTS fallback remains available. The standalone Video Generation / Video Sequence H3 visual flows are unchanged.

## Hardware

Rumik OSS-1 is a 3B BF16 model. It shares the GPU with ComfyUI. The application runs speech and H3 sound generation sequentially per scene; avoid concurrent Rumik/H3 inference on a 16 GB GPU unless the runtime has been verified for memory headroom.

## License

Rumik OSS-1 is released under CC-BY-NC-4.0 with an acceptable-use addendum. This integration is therefore suitable for research/non-commercial use unless the model's licensing terms are separately cleared for the intended deployment.
