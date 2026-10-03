# MiniMax H3 — native ComfyUI integration

This release adds MiniMax H3 as a **separate local ComfyUI workflow** alongside Wan 2.2.
It does not call the MiniMax API.

## UI

The left menu now contains:

- Video generation
- Wan 2.2 workflow
- MiniMax H3 workflow

The Video Generation page has a **Generation workflow** dropdown. Selecting Wan 2.2 keeps the existing Wan path; selecting MiniMax H3 routes the job to the H3 ComfyUI graph.

## ComfyUI requirements

Native MiniMax H3 support requires a recent ComfyUI release (the official documentation currently says 0.30.0+). The official native workflows are available from the ComfyUI workflow template library.

The app includes native ComfyUI API-prompt graphs:

- `backend/src/main/resources/comfyui-workflows/minimax-h3-text-to-video.json`
- `backend/src/main/resources/comfyui-workflows/minimax-h3-image-to-video.json`

These use the native nodes `MiniMaxH3ImageToVideo`, `KSamplerSelect`, `BasicScheduler`, `BasicGuider`, `SamplerCustomAdvanced`, `VAEDecodeAudio`, `CreateVideo`, and `SaveVideo`.

## Models

Default H3 filenames in `application.yml` are:

```text
minimax_h3_fl2va_pruned_int8_convrot.safetensors
qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors
minimax_h3_video_vae_int8_convrot.safetensors
minimax_h3_audio_vae_fp32.safetensors
```

Override them with:

```text
LOCAL_AI_ANIMATION_MINIMAX_H3_MODEL
LOCAL_AI_ANIMATION_MINIMAX_H3_TEXT_ENCODER
LOCAL_AI_ANIMATION_MINIMAX_H3_VIDEO_VAE
LOCAL_AI_ANIMATION_MINIMAX_H3_AUDIO_VAE
LOCAL_AI_ANIMATION_MINIMAX_H3_STEPS
```

The official ComfyUI H3 documentation lists the current model files and storage locations. The base diffusion model and text encoder are very large, so **do not assume an RTX 3060 12 GB can run the full-quality H3 graph without aggressive offloading/quantization**. Validate the actual ComfyUI build and memory behavior on the target instance before enabling production generation.

## Audio note

H3 generates native audio, but ComfyUI H3 audio has had version-specific issues. If `VAEDecodeAudio`/`SaveVideo` fails on a particular ComfyUI build, update ComfyUI first and test the H3 video branch separately. The application architecture keeps H3 isolated from Wan so an H3 issue does not alter the Wan workflow.

## Environment

Example:

```env
LOCAL_AI_ANIMATION_ENABLED=true
LOCAL_AI_ANIMATION_MINIMAX_H3_MODEL=minimax_h3_fl2va_pruned_int8_convrot.safetensors
LOCAL_AI_ANIMATION_MINIMAX_H3_TEXT_ENCODER=qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors
LOCAL_AI_ANIMATION_MINIMAX_H3_VIDEO_VAE=minimax_h3_video_vae_int8_convrot.safetensors
LOCAL_AI_ANIMATION_MINIMAX_H3_AUDIO_VAE=minimax_h3_audio_vae_fp32.safetensors
LOCAL_AI_ANIMATION_MINIMAX_H3_STEPS=8
```

The workflow is selected by the Video Generation dropdown; there is no external MiniMax API key or API endpoint involved.
