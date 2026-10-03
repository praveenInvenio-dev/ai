# 16GB Cinematic Pipeline

This release targets a **16GB NVIDIA GPU** for local image/video generation.

## Image generation

The QUALITY profile now uses **Qwen Image 2.1 INT8** through native ComfyUI:

- diffusion: `qwen_image_2.1_int8_convrot.safetensors`
- text encoder: `qwen3vl_8b_int8_convrot.safetensors`
- VAE: `qwen_image_2.1_vae_bf16.safetensors`
- cache: `QwenImage21Cache`, `dtype=int8`
- base generation: **576x1024**
- steps: **20**
- CFG: **1.0**
- sampler/scheduler: **euler/simple**
- final: RealESRGAN x2 then resize to **864x1536**

The optional Qwen prompt-enhancer model is deliberately not used on a 16GB card because it adds another large text-encoder footprint. The application instead gives Qwen a longer, scene-focused prompt budget.

ComfyUI's current Qwen Image 2.1 template documents the INT8 diffusion/text-encoder pair and the BF16 VAE. A current 16GB deployment measurement reports a peak around 15.8GB at 1024x1024, so this application deliberately uses a smaller 9:16 base size and reserves VRAM for the rest of the process.

## Character references

Qwen T2I is not used when a locked character reference must be honored. Those scenes use the existing SDXL + IPAdapter workflow with:

`DreamShaperXL_Lightning.safetensors`

This prevents a reference image from being silently ignored.

## Video generation

For 16GB hardware, the default local video profile is Wan 2.2 TI2V-5B with conservative settings:

- 480x832
- 16 fps
- 16 steps
- maximum 6 seconds per generation
- one ComfyUI job at a time
- 14GB minimum VRAM gate

The goal is reliable 5–6 second cinematic shots rather than trying to push a 14B workflow into a 16GB card.

## MiniMax H3

The H3 ComfyUI integration remains present, but this build has a separate **24GB minimum VRAM guard**. On a 16GB GPU it fails fast with an explanatory message instead of starting a job that is expected to OOM.

Move H3 to a 24GB+ GPU when you want to use it.

## First startup

The GPU pre-start script can download the Qwen INT8 image models and the RealESRGAN x2 model automatically. This is several GB of downloads and requires persistent GPU-volume storage.

Set:

`DOWNLOAD_16GB_IMAGE_MODELS=false`

if you want to manage those model downloads manually.

## ComfyUI version

Qwen Image 2.1 requires a current ComfyUI build with the `TextEncodeQwenImage21` and `QwenImage21Cache` nodes. If the persistent ComfyUI checkout is too old, the startup script prints a clear warning rather than silently accepting the Qwen workflow.
