#!/usr/bin/env bash
set -euo pipefail

# Print the URL to use from another device. This script does not create a
# firewall rule: the cloud provider (for example Vast.ai) must allow the host
# port first.
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

PORT="${PUBLIC_HTTP_PORT:-4200}"
BIND="${PUBLIC_BIND_ADDRESS:-0.0.0.0}"

if [ "$BIND" != "0.0.0.0" ] && [ "$BIND" != "::" ]; then
  echo "WARNING: PUBLIC_BIND_ADDRESS=$BIND is not a public bind address."
fi

PUBLIC_IP=""
for endpoint in https://api.ipify.org https://ifconfig.me/ip; do
  if command -v curl >/dev/null 2>&1; then
    PUBLIC_IP="$(curl -4 -fsS --max-time 5 "$endpoint" 2>/dev/null || true)"
    [ -n "$PUBLIC_IP" ] && break
  fi
done

if [ -n "$PUBLIC_IP" ]; then
  echo "AI Story Studio public URL: http://${PUBLIC_IP}:${PORT}"
else
  echo "AI Story Studio URL: http://<SERVER-PUBLIC-IP>:${PORT}"
fi

echo
echo "Local URL:   http://127.0.0.1:${PORT}"
echo "Public port: ${PORT}"
echo
echo "If the public URL does not open, allow TCP ${PORT} in the cloud provider's"
echo "instance/network firewall (Vast.ai) and make sure the instance has a"
echo "public IPv4 address. Do NOT expose 5432, 8080, 8188, 5002-5005, or 5010."
