#!/usr/bin/env bash
# Fails if the MERGED compose config would run ComfyUI on the wrong image, on CPU,
# or without GPU access. Does not start any container (needs only `docker compose
# config`, which does not talk to the Docker daemon).
#
#   scripts/validate-comfyui-config.sh              # checks base + gpu overlay
#   COMFYUI_EXPECTED_IMAGE=... scripts/validate-comfyui-config.sh
#
# Why: docker-compose.gpu.yml overrides docker-compose.yml. If its image default
# drifts (it used to be cu128-slim) every deploy silently runs the wrong CUDA stack.
set -euo pipefail
cd "$(dirname "$0")/.."

EXPECTED_IMAGE="${COMFYUI_EXPECTED_IMAGE:-yanwk/comfyui-boot:cu130-slim-v2}"
FILES=(-f docker-compose.yml -f docker-compose.gpu.yml)

fail() { echo "FAIL: $*" >&2; exit 1; }

if docker compose version >/dev/null 2>&1; then COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then COMPOSE=(docker-compose)
else fail "neither 'docker compose' nor 'docker-compose' found"; fi
command -v python3 >/dev/null 2>&1 || fail "python3 is required to parse the compose config"

# 1) Files that define the default must agree with the expected image.
for f in docker-compose.yml docker-compose.gpu.yml; do
  grep -q "COMFYUI_GPU_IMAGE:-${EXPECTED_IMAGE}}" "$f" \
    || fail "$f: comfyui image default is not '\${COMFYUI_GPU_IMAGE:-${EXPECTED_IMAGE}}'"
done

# 2) An explicit .env / environment override must not point at a retired image.
override="${COMFYUI_GPU_IMAGE:-}"
if [ -z "$override" ] && [ -f .env ]; then
  override="$(grep -E '^COMFYUI_GPU_IMAGE=' .env | tail -n1 | cut -d= -f2- || true)"
fi
if [ -n "$override" ] && [ "$override" != "$EXPECTED_IMAGE" ]; then
  fail "COMFYUI_GPU_IMAGE override is '$override' (expected '$EXPECTED_IMAGE')"
fi

# 3) The resolved (merged) comfyui service.
json="$("${COMPOSE[@]}" "${FILES[@]}" config --format json 2>&1)" \
  || fail "'compose config' failed: $json"

CONFIG_JSON="$json" EXPECTED_IMAGE="$EXPECTED_IMAGE" python3 - <<'PY'
import json, os, sys
cfg = json.loads(os.environ["CONFIG_JSON"])
exp = os.environ["EXPECTED_IMAGE"]
svc = cfg.get("services", {}).get("comfyui")
errors = []
if svc is None:
    errors.append("no 'comfyui' service in the merged config")
else:
    image = svc.get("image", "")
    if image != exp:
        errors.append(f"merged comfyui image is '{image}', expected '{exp}'")
    for bad in ("cu128", "cu124", "cu121", "cu126", ":cpu"):
        if bad in image:
            errors.append(f"merged comfyui image '{image}' is a retired/CPU tag ({bad})")
    cli = (svc.get("environment") or {}).get("CLI_ARGS", "") or ""
    if "--cpu" in cli.split():
        errors.append(f"CLI_ARGS contains --cpu: '{cli}'")
    gpu_ok = bool(svc.get("gpus"))
    for dev in (((svc.get("deploy") or {}).get("resources") or {}).get("reservations") or {}).get("devices") or []:
        if dev.get("driver") == "nvidia" or "gpu" in (dev.get("capabilities") or []):
            gpu_ok = True
    if not gpu_ok:
        errors.append("merged comfyui service has no GPU access (set 'gpus: all')")
    for flag in ("--lowvram", "--novram", "--fast"):
        if flag in cli.split():
            print(f"WARN: CLI_ARGS has '{flag}' - not needed on a 16 GB RTX 5060 Ti", file=sys.stderr)
if errors:
    for e in errors:
        print("FAIL: " + e, file=sys.stderr)
    sys.exit(1)
print(f"OK: comfyui image={svc['image']} gpus={svc.get('gpus')} CLI_ARGS='{(svc.get('environment') or {}).get('CLI_ARGS','').strip()}'")
PY
