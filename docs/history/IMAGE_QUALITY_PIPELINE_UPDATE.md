# Image Quality & Story Consistency Update

This release tightens the complete story -> scene -> image -> video path without removing the existing fallback behaviour.

## Image generation

- The selected Create Story visual style is now treated as a hard rendering contract.
- The LLM cannot replace the selected style in the Story Bible; the episode style is authoritative.
- The scene's generated `imagePrompt` is now actually sent to ComfyUI. Previously it was persisted but the image assembler mostly relied on short action/location fields.
- Character canon, scene intent, continuity, and style are composed in a bounded prompt budget.
- Style-specific positive and negative prompts explicitly suppress cross-style drift. Anime, for example, now requests unmistakable Japanese 2D anime rendering and rejects photorealistic/3D/cartoon rendering.
- Scene continuity JSON records locked style, palette, location, characters, objects, and action.
- Optional style-specific checkpoints can be configured without breaking installations that only have one checkpoint:
  - `COMFYUI_ANIME_MODEL`
  - `COMFYUI_CARTOON_MODEL`
  - `COMFYUI_3D_ANIMATED_MODEL`
  - `COMFYUI_STORYBOOK_MODEL`
  - `COMFYUI_WATERCOLOR_MODEL`
  - `COMFYUI_COMIC_BOOK_MODEL`
  - `COMFYUI_FANTASY_MODEL`
- Blank style-checkpoint variables always fall back to `COMFYUI_MODEL`.

### Important model note

Prompt text alone cannot reliably turn a photographic SD1.5 checkpoint into a high-quality anime checkpoint. For the strongest Anime/Cartoon results, install a checkpoint intended for that visual style and set its filename in `.env`. The application does not bundle model weights.

## Image QA

- Cheap integrity checks always run (non-empty, decodable, minimum resolution).
- Optional semantic QA is available through an Ollama vision model.
- To enable it:

```env
VISION_PROVIDER=ollama
VISION_MODEL=<your-installed-vision-model>
IMAGE_VALIDATION_ENABLED=true
IMAGE_VALIDATION_RETRIES=1
```

If the vision model is unavailable, the image is accepted rather than breaking the production pipeline. With the default `VISION_PROVIDER=mock`, there is no extra model call.

## Voice consistency

- One TTS call is now used per voice segment instead of splitting every sentence at punctuation and restarting the synthesizer repeatedly.
- Existing explicit pauses remain.
- Per-scene loudness normalization/compression remains in place.
- Default local Piper voice is now `en_US-lessac-high`; the compose file already preloads it.
- Voice selection remains compatible with the existing Voice Lab, Edge, Sarvam and IndicF5 paths.

## Video effects

- Scene transitions are now real FFmpeg `xfade`/`acrossfade` transitions when every scene has audio. Previously the assembly used a concat demuxer, which produced hard cuts despite the intended crossfade behaviour.
- Transition choice is derived from the scene purpose/emotion: magical/reveal moments use a more visible transition, emotional scenes use a softer fade, and normal scenes use a restrained fade/wipe rotation.
- Existing Ken Burns, heuristic parallax, particles, ambience, SFX, music ducking and optional AI-I2V paths remain intact.

## Backward compatibility

No database migration is required for these changes. Existing scene/episode fields are reused. Existing ComfyUI workflow files remain in place. Existing provider fallbacks remain enabled.
