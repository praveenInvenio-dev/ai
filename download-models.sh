#!/usr/bin/env bash
# Downloads every model the app uses into the ComfyUI GPU volume.
# Run from the project folder AFTER ComfyUI finished its first start.
#   chmod +x download-models.sh && nohup ./download-models.sh > download.log 2>&1 &
# Safe to re-run: aria2c -c resumes, finished files are skipped.
# Skip a block with: SKIP_H3=1 SKIP_WAN14B=1 SKIP_LIGHTNING=1 ./download-models.sh
set -e
DC="docker compose exec -T comfyui"
dl() {
  local dir="$1" file="$2" url="$3" target="/root/ComfyUI/models/$dir/$file"
  $DC mkdir -p "/root/ComfyUI/models/$dir"
  if $DC test -s "$target"; then
    echo "[AI Story Studio] already present: $dir/$file"
    return 0
  fi
  echo "[AI Story Studio] downloading: $dir/$file"
  # Prefer curl because it handles Hugging Face Xet/redirects reliably in the
  # current comfyui-boot image; fall back to aria2c/wget if needed.
  if $DC bash -lc 'command -v curl >/dev/null 2>&1'; then
    $DC curl -fL --retry 5 --retry-delay 3 --continue-at - "$url" -o "$target"
  elif $DC bash -lc 'command -v aria2c >/dev/null 2>&1'; then
    $DC aria2c -c -x4 --console-log-level=warn -d "/root/ComfyUI/models/$dir" -o "$file" "$url"
  else
    $DC wget -c --tries=5 "$url" -O "$target"
  fi
  $DC test -s "$target" || { echo "ERROR: download produced no file: $target" >&2; return 1; }
}
HF=https://huggingface.co

# --- custom node: VideoHelperSuite (Wan mp4 output) ---
$DC bash -c 'cd /root/ComfyUI/custom_nodes && ([ -d ComfyUI-VideoHelperSuite ] || git clone --depth 1 https://github.com/Kosinkadink/ComfyUI-VideoHelperSuite) && python3 -m pip install -q -r ComfyUI-VideoHelperSuite/requirements.txt'

# --- Images: Qwen Image 2.1 ---
dl diffusion_models qwen_image_2.1_int8_convrot.safetensors $HF/Comfy-Org/Qwen-Image-2.1/resolve/main/diffusion_models/qwen_image_2.1_int8_convrot.safetensors
dl text_encoders    qwen3vl_8b_int8_convrot.safetensors     $HF/Comfy-Org/Qwen-Image-2.1/resolve/main/text_encoders/qwen3vl_8b_int8_convrot.safetensors
dl vae              qwen_image_2.1_vae_bf16.safetensors     $HF/Comfy-Org/Qwen-Image-2.1/resolve/main/vae/qwen_image_2.1_vae_bf16.safetensors
dl upscale_models   RealESRGAN_x2.pth                       $HF/ai-forever/Real-ESRGAN/resolve/main/RealESRGAN_x2.pth

# --- Video shared: umt5 text encoder ---
dl text_encoders umt5_xxl_fp8_e4m3fn_scaled.safetensors $HF/Comfy-Org/Wan_2.1_ComfyUI_repackaged/resolve/main/split_files/text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors

# --- Wan 2.2 TI2V-5B ---
dl diffusion_models Wan2_2-TI2V-5B_fp8_e4m3fn_scaled_KJ.safetensors $HF/Kijai/WanVideo_comfy_fp8_scaled/resolve/main/TI2V/Wan2_2-TI2V-5B_fp8_e4m3fn_scaled_KJ.safetensors
dl vae              wan2.2_vae.safetensors                          $HF/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/main/split_files/vae/wan2.2_vae.safetensors

# --- Wan 2.2 I2V-A14B ---
if [ -z "$SKIP_WAN14B" ]; then
dl diffusion_models wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors $HF/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/main/split_files/diffusion_models/wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors
dl diffusion_models wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors  $HF/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/main/split_files/diffusion_models/wan2.2_i2v_low_noise_14B_fp8_scaled.safetensors
dl vae              wan_2.1_vae.safetensors                          $HF/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/main/split_files/vae/wan_2.1_vae.safetensors
if [ -z "$SKIP_LIGHTNING" ]; then
dl loras wan2.2_i2v_lightx2v_4steps_lora_v1_high_noise.safetensors $HF/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/main/split_files/loras/wan2.2_i2v_lightx2v_4steps_lora_v1_high_noise.safetensors
dl loras wan2.2_i2v_lightx2v_4steps_lora_v1_low_noise.safetensors  $HF/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/main/split_files/loras/wan2.2_i2v_lightx2v_4steps_lora_v1_low_noise.safetensors
fi
fi

# --- MiniMax H3 (16GB profile + official Turbo assets) -----------------------
if [ -z "${SKIP_H3:-}" ]; then
  # Base FL2VA used by T2V/I2V.
  dl diffusion_models minimax_h3_fl2va_pruned_w6a8.safetensors \
    $HF/Comfy-Org/MiniMax-H3/resolve/main/diffusion_models/minimax_h3_fl2va_pruned_w6a8.safetensors?download=true
  # Ref2VA is a DIFFERENT diffusion model and is required for character/voice R2V.
  dl diffusion_models minimax_h3_ref2va_pruned_w6a8.safetensors \
    $HF/Comfy-Org/MiniMax-H3/resolve/main/diffusion_models/minimax_h3_ref2va_pruned_w6a8.safetensors?download=true
  dl text_encoders qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors \
    $HF/Comfy-Org/MiniMax-H3/resolve/main/text_encoders/qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors?download=true
  dl vae minimax_h3_video_vae_fp16.safetensors \
    $HF/Comfy-Org/MiniMax-H3/resolve/main/vae/minimax_h3_video_vae_fp16.safetensors?download=true
  dl vae minimax_h3_audio_vae_fp32.safetensors \
    $HF/Comfy-Org/MiniMax-H3/resolve/main/vae/minimax_h3_audio_vae_fp32.safetensors?download=true
  # Official Lightning adapters. I2V uses 8-step FL2V; R2V uses 4-step Ref2V.
  dl loras minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16.safetensors \
    https://huggingface.co/lightx2v/Minimax-h3-Turbo/resolve/main/minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16.safetensors?download=true
  dl loras minimax_h3_ref2v_turbo_4step_v0.1_comfyui_bf16.safetensors \
    $HF/Comfy-Org/MiniMax-H3/resolve/main/loras/minimax_h3_ref2v_turbo_4step_v0.1_comfyui_bf16.safetensors?download=true
fi

docker compose restart comfyui
echo "ALL DONE"
