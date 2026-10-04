# Changelog

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
