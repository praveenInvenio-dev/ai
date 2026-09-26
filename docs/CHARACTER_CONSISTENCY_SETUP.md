# Character Consistency Setup

The production pipeline now uses three levels of identity continuity:

1. **Character Bible** — immutable canonical appearance is included in the image prompt.
2. **Character Reference** — if a Character Studio reference exists, it is preferred.
3. **Previous-scene reference** — when the same character continues into the next scene and no approved reference exists, the previous scene image can be used as a continuity reference.

## IPAdapter

For the strongest consistency, install the ComfyUI IPAdapter Plus custom node pack and the matching models.

### SD1.5
Use:
- `clip_vision_h.safetensors`
- `ip-adapter-plus_sd15.bin`

### SDXL
Use:
- `clip_vision_h.safetensors`
- `ip-adapter-plus_sdxl_vit-h.bin`

The backend automatically selects the SDXL workflow when the checkpoint filename contains `xl`.

If IPAdapter is unavailable, the backend retries the same deterministic scene with the normal text-to-image workflow rather than intentionally producing a placeholder.

## Practical MX450 advice

IPAdapter adds memory pressure. On a 2 GB MX450, keep generation at 512x512 with DreamShaper 8 LCM where possible. For DreamShaperXL-Lightning, 768x768 is a quality-oriented option but will be substantially slower because SDXL must offload to system RAM.

Character consistency should be improved by using one approved reference per recurring character instead of generating a new character sheet for every scene.
