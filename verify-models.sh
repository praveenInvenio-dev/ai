#!/usr/bin/env bash
# Checks every .safetensors model in the ComfyUI volume for truncated/corrupt downloads
# (the cause of "Tensor '...' extends past the end of the file").
#   ./verify-models.sh          report only
#   ./verify-models.sh --fix    resume/re-download every broken file whose URL is known
# Reads only each file's header, so a full scan takes seconds.
set -uo pipefail
cd "$(dirname "$0")"
DC="docker compose exec -T comfyui"
FIX=0; [ "${1:-}" = "--fix" ] && FIX=1

read -r -d '' PYCHECK <<'PY'
import json, os, struct, sys
bad = []
for root, _, files in os.walk('/root/ComfyUI/models'):
    for name in files:
        if not name.endswith('.safetensors'):
            continue
        f = os.path.join(root, name)
        size = os.path.getsize(f)
        try:
            with open(f, 'rb') as h:
                n = struct.unpack('<Q', h.read(8))[0]
                if n <= 0 or n > 200_000_000 or 8 + n > size:
                    raise ValueError('header')
                header = json.loads(h.read(n))
            end = max((v['data_offsets'][1] for k, v in header.items() if k != '__metadata__'), default=0)
            need = 8 + n + end
            if size < need:
                print(f'BAD\t{f}\t{size / 1e9:.2f} GB of {need / 1e9:.2f} GB ({100 * size / need:.0f}%)')
            else:
                print(f'OK\t{f}\t{size / 1e9:.2f} GB')
        except Exception as e:
            print(f'BAD\t{f}\tunreadable header ({type(e).__name__}) - {size / 1e6:.1f} MB')
PY

echo "Scanning /root/ComfyUI/models ..."
REPORT=$($DC python3 -c "$PYCHECK") || { echo "Could not run the check - is the comfyui container running? (docker compose ps comfyui)"; exit 1; }
echo "$REPORT" | grep -c '^OK' | xargs -I{} echo "{} model file(s) OK"
BAD=$(echo "$REPORT" | grep '^BAD' || true)
if [ -z "$BAD" ]; then echo "No broken model files."; exit 0; fi
echo; echo "Broken / partial files:"; echo "$BAD" | cut -f2,3 | sed 's/^/  /'

[ $FIX -eq 1 ] || { echo; echo "Run ./verify-models.sh --fix to repair them."; exit 2; }

echo
echo "$BAD" | cut -f2 | while read -r f; do
  name=$(basename "$f")
  url=$(grep -ho "https://[^\" ]*${name}[^\" ]*" download-models.sh comfyui-gpu/pre-start.sh 2>/dev/null | head -1)
  if [ -z "$url" ]; then echo "SKIP $name - no download URL known; delete it and download it again manually."; continue; fi
  echo "Repairing $name (resuming) ..."
  $DC curl -fL --retry 5 --retry-delay 3 --continue-at - "$url" -o "$f" || true
  if $DC python3 -c "$PYCHECK" | grep -q "^BAD	$f	"; then
    echo "  still broken - downloading it again from the start ..."
    $DC rm -f "$f"
    $DC curl -fL --retry 5 --retry-delay 3 "$url" -o "$f" || true
  fi
  if $DC python3 -c "$PYCHECK" | grep -q "^OK	$f	"; then echo "  OK: $name"; else echo "  FAILED: $name - check disk space (df -h) and network, then re-run."; fi
done
echo; echo "Done. Restart the backend so H3 character references switch back on: docker compose restart backend"
