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
