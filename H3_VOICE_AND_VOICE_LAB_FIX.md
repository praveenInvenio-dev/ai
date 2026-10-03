# v18.6 — Voice Lab audio fix + MiniMax H3 voice references

## Voice Lab fix

The Voice Lab no longer turns a failed ChatterBox/CosyVoice clone synthesis into a silent mock WAV for the **Test cloned voice** action. Production narration keeps its existing graceful fallback, but the explicit voice test now uses the real provider path so a provider/container/model failure is surfaced instead of looking like an audio-player problem.

The recorded/uploaded reference continues to be converted to mono 24 kHz WAV and stored in the shared `voice-profile-audio` volume. The exact stored WAV can be played from **Play recorded voice**.

## MiniMax H3 voice integration

When Video Generation uses **MiniMax H3** and a saved VoiceProfile is selected:

- The stored voice reference WAV is uploaded to ComfyUI's input media folder.
- H3 automatically switches from FL2VA to the native **Reference-to-Video (Ref2VA)** workflow.
- The reference audio is connected to `ref_audios.ref_audio_0`.
- If an input image is supplied, it is also connected to `ref_images.ref_image_0`.
- H3 generates the video and native stereo audio together in one MP4.
- The old post-generation TTS mux is skipped for H3, preventing duplicate/double audio.
- Wan 2.2 keeps the existing TTS narration/mux behavior unchanged.

## H3 workflows

- `minimax-h3-text-to-video.json`
- `minimax-h3-image-to-video.json`
- `minimax-h3-reference-to-video.json` — image + voice reference
- `minimax-h3-voice-to-video.json` — voice reference without a starting image

The reference workflow uses the dotted ComfyUI API input names (`ref_images.ref_image_0`, `ref_audios.ref_audio_0`). This is the correct API representation for the dynamic reference inputs.

## Important runtime requirement

H3 Ref2VA needs the separate Ref2VA diffusion checkpoint:

`minimax_h3_ref2va_pruned_int8_convrot.safetensors`

Set it with:

`LOCAL_AI_ANIMATION_MINIMAX_H3_REF2VA_MODEL`

ComfyUI should be updated to a version with native MiniMax H3 Reference-to-Video support.

H3's native audio is part of the video generation model rather than a conventional standalone TTS engine. Therefore H3 is integrated as a **voice reference for video generation**, while Voice Lab's standalone generated-audio test remains ChatterBox/CosyVoice.
