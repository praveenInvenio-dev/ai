# Changelog

## v18.19 - audio fixes, cu130 ComfyUI, no-crop fit
- ComfyUI: image `yanwk/comfyui-boot:cu130-slim-v2` (was cu128-slim in the GPU overlay),
  `gpus: all`, `CLI_ARGS=--preview-method none`. COMFYUI_GPU_IMAGE in .env is the single
  override; compose files carry the same default. `gpus` is `!override` in the overlay
  (compose refuses to merge two `gpus` values). Volume layout `comfyui-gpu-root:/root`
  moved into the base file; volume name unchanged, nothing recreated; legacy CPU volumes
  stay declared. New scripts/validate-comfyui-config.sh (also run by setup script).
  Default COMFYUI_EXTRA_CLI_ARGS is now empty (xformers/pinned-memory flags removed; the
  known fallback is documented in .env).
- NARRATION was silent because Chatterbox received the story language as free text
  ("English"/"Hinglish") and used it as language_id -> every request failed -> silent
  placeholder. Sidecar now maps names to ISO codes (Hinglish/Hindi -> hi), rejects
  unsupported ones with a clear 400; backend retries on an Edge voice for the story language
  (Hindi/Hinglish -> hi-IN-Swara, Tamil, Telugu, ...) then Piper before any silence.
  Chatterbox request timeout 120 -> 300 s. [sigh]/[gasp] tags no longer read aloud by Piper/Edge.
- MUSIC/AMBIENCE/SFX beds were -51...-61 dB (inaudible after the 0.16 mix) -> normalised to
  -20 dB (music), -24 dB (ambience), -18 dB (sfx).
- Voice Lab: a muted/hiss-only recording (loudest peak <= -40 dB) is now rejected at preview
  and save; it used to save as a "voice" that cloned into silence. Verified the rest of the
  record -> save -> playback chain (see below in chat): webm/mp4 -> 24 kHz mono WAV, same
  duration and level, waveform correlation 0.999.
- Over-zoom: scene images whose aspect differs >8% from the video frame are now fitted whole
  over a blurred copy of themselves instead of center-cropped.

## v18.18 - final video fixes
- Final video now 1080x1920 vertical by default (VIDEO_ORIENTATION). It was hard-coded
  1920x1080 while Qwen scene images are 9:16, so each frame showed only the middle third
  of the image ("too zoomed"). VIDEO_ORIENTATION=horizontal renders 1920x1080 and makes
  Qwen draw 16:9 images (1344x768 -> 1920x1080).
- Shorts crop only when the source is landscape (no double crop on vertical).
- Gentler Ken Burns: max zoom ~1.07 instead of ~1.14.
- Narration: if Chatterbox/CosyVoice/Sarvam fails, retry on local Piper before falling
  back to silence (bracket tags like [gasp] stripped for Piper).
- docker-compose passes VIDEO_ORIENTATION, VIDEO_TRANSITIONS_ENABLED, AUTO_MUSIC_ENABLED.

## v18.17 - RTX 50-series + H3 checks
- .env: COMFYUI_EXTRA_CLI_ARGS adds --use-pytorch-cross-attention --disable-xformers
  (xformers in the image has no Blackwell kernels -> every Qwen step crashed) and
  --disable-pinned-memory (50 GB hosts).
- H3: canvas orientation follows the start image (MiniMaxH3ImageToVideo stretches
  first_frame without keeping aspect). All 4 H3 graphs re-checked against ComfyUI 0.38
  node source and official templates (inputs, Autogrow names ref_images.ref_image_N,
  SaveVideo, length grid 17k+5, size step 32).
- Frontend: no '@' in Angular templates (NG5002).

## v18.16 - single model stack cleanup
- Images: Qwen Image 2.1 for EVERY scene and every quality profile (FAST = 20 steps,
  others 30). Removed SD1.5 / SDXL / DreamShaper Lightning / LCM / IPAdapter workflows,
  their Java paths (WorkflowTemplateLoader, IPAdapter fallback, style checkpoints,
  QUALITY-only HQ switch, previous-scene continuity reference) and all their settings.
- ComfyUIImageProvider rewritten Qwen-only: t2i / 1-ref / 2-ref graph picked by uploaded refs.
  Missing Qwen nodes -> clear "update ComfyUI" error (no silent SDXL fallback).
- ImagePromptAssembler: single Qwen token budget.
- Video: removed legacy wan-image-to-video (Wan 2.1-style 14B graph + CLIP Vision).
  Default workflows now wan-ti2v-5b-*; I2V-A14B and MiniMax H3 unchanged.
- pre-start.sh: installs ComfyUI-VideoHelperSuite instead of IPAdapter Plus.
- New download-models.sh + MODELS.md; setup-ai-story-studio.sh uses it.
- .env / .env.example / docker-compose cleaned to the current settings only.
- Old notes moved to docs/history/. README rewritten for the current stack.

## v18.15 Wan 2.2 I2V-A14B   ## v18.14 Wan 720p fix   ## v18.13 H3 16 GB profile
## v18.12 FFmpeg audio-pad + chatterbox profile   ## v18.11 Qwen image quality
See docs/history/.
