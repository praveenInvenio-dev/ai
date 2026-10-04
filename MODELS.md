# Model stack (v18.16)

One model per job. Nothing else is needed - SDXL, SD1.5, LCM, IPAdapter, CLIP Vision
and the legacy Wan 2.1-style graph were removed from code, config and workflows.

| Job | Model files | Folder |
|---|---|---|
| Images (scenes + character refs) | qwen_image_2.1_int8_convrot | diffusion_models |
| | qwen3vl_8b_int8_convrot | text_encoders |
| | qwen_image_2.1_vae_bf16 | vae |
| | RealESRGAN_x2.pth | upscale_models |
| Video shared text encoder | umt5_xxl_fp8_e4m3fn_scaled | text_encoders |
| Video fast (T2V + I2V, 720p@24) | Wan2_2-TI2V-5B_fp8_e4m3fn_scaled_KJ | diffusion_models |
| | wan2.2_vae | vae |
| Video best (I2V, 480p@16) | wan2.2_i2v_high_noise_14B_fp8_scaled, wan2.2_i2v_low_noise_14B_fp8_scaled | diffusion_models |
| | wan_2.1_vae | vae |
| | (optional) wan2.2_i2v_lightx2v_4steps_lora_v1_{high,low}_noise | loras |
| Video + native audio (optional) | minimax_h3_fl2va_pruned_w6a8 | diffusion_models |
| H3 character/voice R2V | minimax_h3_ref2va_pruned_w6a8 | diffusion_models |
| H3 I2V Turbo | minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16 | loras |
| H3 R2V Turbo | minimax_h3_ref2v_turbo_4step_v0.1_comfyui_bf16 | loras |
| | qwen3vl_32b_minimax_h3_nvfp4_awq | text_encoders |
| | minimax_h3_video_vae_fp16, minimax_h3_audio_vae_fp32 | vae |

Custom node: ComfyUI-VideoHelperSuite (installed by pre-start.sh and download-models.sh).
ComfyUI must be recent enough to have the Qwen Image 2.1 and MiniMax H3 native nodes.

Download everything: `./download-models.sh` (SKIP_H3=1 / SKIP_WAN14B=1 / SKIP_LIGHTNING=1 to skip).

## Old files safe to delete from an existing ComfyUI volume
checkpoints/DreamShaperXL_Lightning.safetensors, checkpoints/DreamShaper8_LCM.safetensors,
checkpoints/v1-5-pruned-emaonly*.safetensors, ipadapter/*, clip_vision/clip_vision_h.safetensors,
custom_nodes/ComfyUI_IPAdapter_plus, any minimax_h3_*_int8_convrot / *_vae_int8_convrot files.
