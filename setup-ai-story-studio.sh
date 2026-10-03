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

# --- 5+6. ComfyUI models + VideoHelperSuite (one script, see MODELS.md) ----
log "Downloading ComfyUI models via download-models.sh (slow part, ~100 GB with 14B + H3)..."
log "  Skip parts with SKIP_H3=1 SKIP_WAN14B=1 SKIP_LIGHTNING=1."
./download-models.sh

# --- 7. Restart ComfyUI to pick up the new node packs, final full 'up' -----

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
  /root/ComfyUI/models/diffusion_models /root/ComfyUI/models/text_encoders \
  /root/ComfyUI/models/vae /root/ComfyUI/models/loras /root/ComfyUI/models/upscale_models

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
