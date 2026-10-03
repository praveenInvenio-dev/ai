#!/usr/bin/env bash
set -euo pipefail

# AI Story Studio: ComfyUI GPU pre-start hook.
# The yanwk/comfyui-boot image runs this after the persistent /root/ComfyUI
# bundle exists and before main.py starts.
#
# Image engine is Qwen Image 2.1 only (no SDXL / IPAdapter any more).
# Video uses ComfyUI-VideoHelperSuite (VHS_VideoCombine) for the Wan graphs.

CN_DIR=/root/ComfyUI/custom_nodes
mkdir -p "$CN_DIR"
VHS_DIR="$CN_DIR/ComfyUI-VideoHelperSuite"
if [ ! -d "$VHS_DIR/.git" ]; then
  echo "[AI Story Studio] Installing ComfyUI-VideoHelperSuite..."
  rm -rf "$VHS_DIR"
  git clone --depth 1 https://github.com/Kosinkadink/ComfyUI-VideoHelperSuite "$VHS_DIR"
fi
if [ -f "$VHS_DIR/requirements.txt" ] && [ ! -f "$VHS_DIR/.deps-installed" ]; then
  python3 -m pip install --no-cache-dir -r "$VHS_DIR/requirements.txt" && touch "$VHS_DIR/.deps-installed"
fi

# -----------------------------------------------------------------------------
# 16GB cinematic image preset
# -----------------------------------------------------------------------------
# Qwen Image 2.1 INT8 is the primary high-detail T2I path for a 16GB GPU.
# The official ComfyUI workflow uses the INT8 diffusion model + INT8 Qwen3-VL
# text encoder + BF16 VAE. We intentionally do NOT install the optional 9B
# prompt-enhancer model here: its extra memory is not justified on a 16GB card.
QWEN_DIFFUSION=/root/ComfyUI/models/diffusion_models/qwen_image_2.1_int8_convrot.safetensors
QWEN_TEXT=/root/ComfyUI/models/text_encoders/qwen3vl_8b_int8_convrot.safetensors
QWEN_VAE=/root/ComfyUI/models/vae/qwen_image_2.1_vae_bf16.safetensors
UPSCALE_DIR=/root/ComfyUI/models/upscale_models
UPSCALE_FILE="$UPSCALE_DIR/RealESRGAN_x2.pth"
mkdir -p "$(dirname "$QWEN_DIFFUSION")" "$(dirname "$QWEN_TEXT")" "$(dirname "$QWEN_VAE")" "$UPSCALE_DIR"

if [ "${DOWNLOAD_16GB_IMAGE_MODELS:-true}" = "true" ]; then
  if [ ! -s "$QWEN_DIFFUSION" ]; then
    echo "[AI Story Studio] Downloading Qwen Image 2.1 INT8 diffusion model (~6.8GB)..."
    curl -fL --retry 3 --retry-delay 3 --continue-at - \
      "https://huggingface.co/Comfy-Org/Qwen-Image-2.1/resolve/main/diffusion_models/qwen_image_2.1_int8_convrot.safetensors?download=true" \
      -o "$QWEN_DIFFUSION"
  fi
  if [ ! -s "$QWEN_TEXT" ]; then
    echo "[AI Story Studio] Downloading Qwen3-VL 8B INT8 text encoder (~8.7GB)..."
    curl -fL --retry 3 --retry-delay 3 --continue-at - \
      "https://huggingface.co/Comfy-Org/Qwen-Image-2.1/resolve/main/text_encoders/qwen3vl_8b_int8_convrot.safetensors?download=true" \
      -o "$QWEN_TEXT"
  fi
  if [ ! -s "$QWEN_VAE" ]; then
    echo "[AI Story Studio] Downloading Qwen Image 2.1 BF16 VAE (~644MB)..."
    curl -fL --retry 3 --retry-delay 3 --continue-at - \
      "https://huggingface.co/Comfy-Org/Qwen-Image-2.1/resolve/main/vae/qwen_image_2.1_vae_bf16.safetensors?download=true" \
      -o "$QWEN_VAE"
  fi
  if [ ! -s "$UPSCALE_FILE" ]; then
    echo "[AI Story Studio] Downloading RealESRGAN x2 upscaler (~67MB)..."
    curl -fL --retry 3 --retry-delay 3 --continue-at - \
      "https://huggingface.co/ai-forever/Real-ESRGAN/resolve/main/RealESRGAN_x2.pth?download=true" \
      -o "$UPSCALE_FILE"
  fi
fi

# Qwen Image 2.1 support landed in current ComfyUI releases. Fail clearly on an
# old persistent ComfyUI checkout instead of letting the first scene fail with
# an opaque "unknown node type: TextEncodeQwenImage21" message.
if [ -f /root/ComfyUI/comfy_extras/nodes_qwen.py ] && grep -q "class TextEncodeQwenImage21" /root/ComfyUI/comfy_extras/nodes_qwen.py; then
  echo "[AI Story Studio] Qwen Image 2.1 support detected."
else
  echo "[AI Story Studio] WARNING: this ComfyUI checkout does not expose QwenImage21 nodes. Update ComfyUI before using the 16GB cinematic image workflow."
fi
