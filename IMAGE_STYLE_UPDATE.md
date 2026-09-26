# Image Style & Consistency Update

The Create Story screen now provides story-level image style choices. The selected style
is stored on the episode and applied to every scene.

## Styles
3D Realistic, 3D Animated Feature, Cinematic Realistic, Anime, Cartoon, Storybook,
Watercolor, 2D Animation, Claymation, Comic Book, Fantasy Illustration, Realistic.

## 3D Realistic
Uses realistic proportions, physically based materials, detailed skin/fur/cloth, believable
environment detail, volumetric lighting, global illumination, cinematic lens language and
natural expressions.

## Consistency improvements
- One locked style profile is reused for all scene prompts.
- Style-aware negative prompts reduce outfit/color/anatomy drift.
- Character identity remains first in the positive prompt.
- Existing IPAdapter-capable character-consistency workflow is used when a primary character
  reference exists.
- Character reference images use the same selected style profile.
- The story LLM is explicitly instructed to keep style, character appearance and recurring
  locations consistent.

No new model is required. Existing ComfyUI workflows/checkpoints remain compatible.
