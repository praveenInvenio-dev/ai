# Model licensing

AI Story Studio's **code** is open source (see the repository license). The **AI
models** it recommends or downloads are separate artifacts with their own
licenses, which are not always permissive. Downloadable weights are not
automatically "open source" in the OSI sense — always check the box below
before using generated content commercially.

None of the models below are bundled inside the Docker images. They are
downloaded on first run (Ollama pulls its model; ComfyUI/Piper download theirs
into a mounted volume the first time they're used), so you always get the
current terms from the model's own source.

| Component | Default model | Code/runtime license | Model weight license | Commercial use | Notes |
|---|---|---|---|---|---|
| Story LLM (Ollama) | `llama3.1` (8B) | Ollama: MIT | Llama 3.1 Community License | Allowed, with conditions (see Meta's license for the >700M MAU clause and acceptable-use policy) | Swap `OLLAMA_MODEL` for any Ollama-compatible model (Mistral, Qwen, Gemma, etc.) — check that model's own license. |
| Image generation (ComfyUI) | Stable Diffusion XL 1.0 base | ComfyUI: GPL-3.0 | CreativeML Open RAIL++-M | Allowed, with use-based restrictions in the RAIL license | Do not swap in a checkpoint whose license forbids commercial use without reading it first. |
| Local TTS (Piper) | `en_US-amy-medium` | Piper: MIT | Individual voices vary — most bundled Piper voices are MIT/CC0, some are CC-BY | Generally allowed | Check the specific voice's `MODEL_CARD` before shipping narration commercially. |

## What you must do before commercial use

1. Confirm the exact model/voice you actually configured (not just the
   default above) and read its license page directly.
2. If a model requires attribution (some CC-BY voices do), keep that
   attribution with your output.
3. If you swap in a different checkpoint, LLM, or voice, add a row to this
   file (or your own internal equivalent) rather than assuming it's fine
   because it downloaded without a login wall.

## Reporting inaccuracies

Model licenses change. If a term above is stale, treat the model's own
license page as authoritative, not this table.
