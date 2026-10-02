#!/usr/bin/env bash
#
# AI Story Studio - full bootstrap, fresh instance -> running app.
#
# Lives inside the project itself now (so it comes along with a git clone),
# and supports two ways of running it:
#
#   A) Git clone workflow (recommended if this project is in a repo):
#        git clone <your-repo-url> /workspace/ai
#        cd /workspace/ai
#        ./setup-ai-story-studio.sh
#      No argument needed - it detects it's already sitting inside the
#      project (docker-compose.yml present) and skips the unzip step.
#
#   B) Standalone zip workflow (no git, just the zip + this script):
#        ./setup-ai-story-studio.sh ai-story-studio-v18.4-reconciled.zip
#      Unzips into /workspace/ai first, same as before.
#
# What this does NOT do (by design, not an oversight):
#   - Does not provision the Vast.ai instance itself - rent it first
#     (template: "Ubuntu 22.04 VM", GPU with 10GB+ VRAM - see the runbook
#     for why that specific template matters).
#   - Does not sign in to Ollama Cloud (needs an interactive browser login -
#     can't be scripted). Defaults OLLAMA_MODEL to a normal pullable model
#     (llama3.1) so the WHOLE script runs unattended; switch to a *-cloud
#     model afterwards yourself if you want one - see the final printout.
#   - Does not back up/restore Voice Library recordings - those live in a
#     Docker volume that dies with the instance. Re-record after a fresh
#     setup, or keep your own backup of tts-chatterbox/voices/*.wav.
#
# Safe to re-run: every step checks "is this already done" before doing
# it, so interrupting partway through and re-running picks up where it
# left off rather than redoing multi-GB downloads.

set -euo pipefail

PROJECT_ZIP="${1:-}"
PROJECT_DIR="/workspace/ai"
OLLAMA_STARTUP_MODEL="${OLLAMA_MODEL_OVERRIDE:-llama3.1}"

log()  { echo -e "\n\033[1;36m==>\033[0m $*"; }
warn() { echo -e "\033[1;33m   !\033[0m $*"; }
die()  { echo -e "\033[1;31mFATAL:\033[0m $*" >&2; exit 1; }

# --- 0. Sanity checks -------------------------------------------------------
log "Checking GPU is visible to the host..."
nvidia-smi >/dev/null 2>&1 || die "nvidia-smi failed - this instance has no working GPU. Check your Vast.ai offer before going further."
nvidia-smi --query-gpu=name,memory.total --format=csv,noheader

command -v docker >/dev/null 2>&1 || die "docker not found - use the 'Ubuntu 22.04 VM' Vast.ai template, which ships it preinstalled."
docker compose version >/dev/null 2>&1 || die "'docker compose' (v2 plugin) not found - same template requirement as above."

# --- 1. Locate the project ---------------------------------------------------
if [ -f "docker-compose.yml" ]; then
    # Mode A: already sitting inside the project (git clone workflow) -
    # nothing to unpack, just use the current directory.
    PROJECT_DIR="$(pwd)"
    log "Running from inside the project already ($PROJECT_DIR) - skipping unzip."
elif [ -n "$PROJECT_ZIP" ]; then
    # Mode B: standalone zip workflow.
    [ -f "$PROJECT_ZIP" ] || die "Can't find '$PROJECT_ZIP' - is it in this directory?"
    log "Unpacking project into $PROJECT_DIR ..."
    mkdir -p "$PROJECT_DIR"
    unzip -o -q "$PROJECT_ZIP" -d "$PROJECT_DIR"
    cd "$PROJECT_DIR"
else
    die "Not inside a project (no docker-compose.yml here) and no zip given. Usage: $0 [project-zip-file]"
fi

[ -f docker-compose.yml ] || die "docker-compose.yml not found - something's wrong with the project directory."
[ -f .env ] || die ".env not found - this script relies on the project already containing a working .env (commit a real one, or .env.example + copy it yourself first)."
log "Project ready. Working directory: $(pwd)"

# --- 2. Start the base stack --------------------------------------------------
log "Starting containers (this builds images on first run - a few minutes)..."
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d

log "Waiting for backend to report healthy..."
for i in $(seq 1 30); do
    if curl -sf http://localhost:8080/api/health >/dev/null 2>&1; then
        log "Backend is up."
        break
    fi
    [ "$i" -eq 30 ] && die "Backend never became healthy after 2.5 minutes - check: docker compose logs backend --tail 50"
    sleep 5
done

# --- 3. GPU passthrough check (the fix for the known eclipse-temurin gap) ---
log "Confirming backend container can see the GPU..."
if ! docker compose exec -T backend nvidia-smi >/dev/null 2>&1; then
    die "Backend can't see the GPU. docker-compose.gpu.yml's backend service needs NVIDIA_DRIVER_CAPABILITIES=utility - check it's present, then: docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --force-recreate backend"
fi
log "GPU visible inside backend. Good."

# --- 4. Ollama: pull the default model (no interactive signin needed) ------
log "Pulling Ollama model '$OLLAMA_STARTUP_MODEL'..."
for i in $(seq 1 20); do
    docker compose exec -T ollama ollama list >/dev/null 2>&1 && break
    [ "$i" -eq 20 ] && die "Ollama never came up - check: docker compose logs ollama --tail 50"
    sleep 3
done
docker compose exec -T ollama ollama pull "$OLLAMA_STARTUP_MODEL"

# --- 5. ComfyUI model downloads (skips any file that already exists) -------
download_model() {
    local subdir="$1" filename="$2" url="$3"
    if docker compose exec -T comfyui test -f "/root/ComfyUI/models/${subdir}/${filename}"; then
        log "Already have ${filename}, skipping."
        return
    fi
    log "Downloading ${filename} (${subdir})..."
    docker compose exec -T comfyui aria2c -c -x4 -d "/root/ComfyUI/models/${subdir}" -o "$filename" "$url"
}

log "Downloading ComfyUI models (this is the slow part - ~15GB total)..."

download_model checkpoints DreamShaperXL_Lightning.safetensors \
  "https://huggingface.co/Lykon/dreamshaper-xl-lightning/resolve/main/DreamShaperXL_Lightning.safetensors"

download_model diffusion_models Wan2_2-TI2V-5B_fp8_e4m3fn_scaled_KJ.safetensors \
  "https://huggingface.co/Kijai/WanVideo_comfy_fp8_scaled/resolve/main/TI2V/Wan2_2-TI2V-5B_fp8_e4m3fn_scaled_KJ.safetensors"

download_model text_encoders umt5_xxl_fp8_e4m3fn_scaled.safetensors \
  "https://huggingface.co/Comfy-Org/Wan_2.1_ComfyUI_repackaged/resolve/main/split_files/text_encoders/umt5_xxl_fp8_e4m3fn_scaled.safetensors"

download_model vae wan2.2_vae.safetensors \
  "https://huggingface.co/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/main/split_files/vae/wan2.2_vae.safetensors"

download_model clip_vision clip_vision_h.safetensors \
  "https://huggingface.co/Comfy-Org/Wan_2.1_ComfyUI_repackaged/resolve/main/split_files/clip_vision/clip_vision_h.safetensors"

download_model ipadapter ip-adapter-plus_sdxl_vit-h.bin \
  "https://huggingface.co/h94/IP-Adapter/resolve/main/sdxl_models/ip-adapter-plus_sdxl_vit-h.safetensors"

# --- 6. Custom node packs (skips if already cloned) -------------------------
install_node_pack() {
    local dirname="$1" repo="$2"
    if docker compose exec -T comfyui test -d "/root/ComfyUI/custom_nodes/${dirname}"; then
        log "${dirname} already installed, skipping."
        return
    fi
    log "Installing ${dirname}..."
    docker compose exec -T comfyui bash -c "cd /root/ComfyUI/custom_nodes && git clone $repo"
}

install_node_pack ComfyUI-VideoHelperSuite https://github.com/Kosinkadink/ComfyUI-VideoHelperSuite
install_node_pack ComfyUI_IPAdapter_plus   https://github.com/cubiq/ComfyUI_IPAdapter_plus

# --- 7. Restart ComfyUI to pick up the new node packs, final full 'up' -----
log "Restarting ComfyUI to load new node packs..."
docker compose restart comfyui
sleep 5

log "Bringing the full stack up (idempotent - already-running services are untouched)..."
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d

# --- 8. Smoke tests ----------------------------------------------------------
log "Running smoke tests..."
echo -n "  backend health........ "; curl -sf http://localhost:8080/api/health >/dev/null && echo OK || echo "FAILED - check docker compose logs backend"
echo -n "  backend sees GPU....... "; docker compose exec -T backend nvidia-smi >/dev/null 2>&1 && echo OK || echo "FAILED"
echo -n "  video-gen status....... "; curl -sf http://localhost:8080/api/video-generation/status >/dev/null && echo OK || echo "FAILED"
echo -n "  ollama model list...... "; docker compose exec -T ollama ollama list >/dev/null 2>&1 && echo OK || echo "FAILED"

log "Done. Model file sizes (verify none are truncated):"
docker compose exec -T comfyui ls -lh \
  /root/ComfyUI/models/checkpoints /root/ComfyUI/models/diffusion_models \
  /root/ComfyUI/models/text_encoders /root/ComfyUI/models/vae \
  /root/ComfyUI/models/clip_vision /root/ComfyUI/models/ipadapter

cat <<EOF

========================================================================
Setup complete.

Access the app from your local machine:
  ssh -p <PORT> root@<HOST> -L 4200:localhost:4200
  then open http://localhost:4200

Optional manual step - switch to an Ollama Cloud model (needs a one-time
interactive login, can't be scripted):
  docker compose exec ollama ollama signin
  docker compose exec ollama ollama pull gemma4:31b-cloud
  # then set OLLAMA_MODEL=gemma4:31b-cloud in .env and:
  docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --force-recreate backend

When you're done for the session and want to stop paying:
  Destroy the instance from the Vast.ai console. Nothing here persists
  that isn't already safe to lose (models/voices just re-download/
  re-record next time this script runs on a fresh instance).
========================================================================
EOF
