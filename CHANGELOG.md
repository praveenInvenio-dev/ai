# Changelog

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
- Old notes moved to docs/history/.

## v18.15 Wan 2.2 I2V-A14B   ## v18.14 Wan 720p fix   ## v18.13 H3 16 GB profile
## v18.12 FFmpeg audio-pad + chatterbox profile   ## v18.11 Qwen image quality
See docs/history/.
