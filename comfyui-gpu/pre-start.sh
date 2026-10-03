#!/usr/bin/env bash
set -euo pipefail

# AI Story Studio: ComfyUI GPU pre-start hook.
# The yanwk/comfyui-boot image runs this after the persistent /root/ComfyUI
# bundle exists and before main.py starts. Install IPAdapter Plus once into
# the persistent custom_nodes directory. The node is required by the bundled
# SDXL character-reference workflow.

CN_DIR=/root/ComfyUI/custom_nodes
NODE_DIR="$CN_DIR/ComfyUI_IPAdapter_plus"

mkdir -p "$CN_DIR"

if [ ! -d "$NODE_DIR/.git" ]; then
  echo "[AI Story Studio] Installing ComfyUI_IPAdapter_plus..."
  rm -rf "$NODE_DIR"
  git clone --depth 1 https://github.com/cubiq/ComfyUI_IPAdapter_plus.git "$NODE_DIR"
else
  echo "[AI Story Studio] ComfyUI_IPAdapter_plus already installed."
fi

# The current IPAdapter Plus repository has no mandatory requirements.txt in
# normal installs, but install it if a future revision adds one.
if [ -f "$NODE_DIR/requirements.txt" ]; then
  python3 -m pip install --no-cache-dir -r "$NODE_DIR/requirements.txt"
fi

IPADAPTER_DIR=/root/ComfyUI/models/ipadapter
CLIP_DIR=/root/ComfyUI/models/clip_vision
mkdir -p "$IPADAPTER_DIR" "$CLIP_DIR"

# The custom node alone is not enough: ComfyUI validates loader dropdowns
# against files that are physically present. Download the exact files used by
# the bundled SDXL character-reference workflow, but only when missing.
IPADAPTER_FILE="$IPADAPTER_DIR/ip-adapter-plus_sdxl_vit-h.bin"
CLIP_FILE="$CLIP_DIR/clip_vision_h.safetensors"

if [ ! -s "$IPADAPTER_FILE" ]; then
  echo "[AI Story Studio] Downloading SDXL IPAdapter model (~1.01 GB)..."
  curl -fL --retry 3 --retry-delay 3 --continue-at - \
    "https://huggingface.co/h94/IP-Adapter/resolve/main/sdxl_models/ip-adapter-plus_sdxl_vit-h.bin?download=true" \
    -o "$IPADAPTER_FILE"
fi

if [ ! -s "$CLIP_FILE" ]; then
  echo "[AI Story Studio] Downloading CLIP Vision H model (~1.26 GB)..."
  curl -fL --retry 3 --retry-delay 3 --continue-at - \
    "https://huggingface.co/Comfy-Org/Wan_2.1_ComfyUI_repackaged/resolve/main/split_files/clip_vision/clip_vision_h.safetensors?download=true" \
    -o "$CLIP_FILE"
fi

echo "[AI Story Studio] IPAdapter Plus + required model files are ready."


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
