# syntax=docker/dockerfile:1.7
# Explainer Studio API (Remotion + three.js renderer) for AI Story Studio.
# Same base image as visual-renderer: it is already cached on your machine AND it ships Chromium headless shell,
# so nothing has to be fetched from remotion.media at build or run time (the browser is auto-detected).
FROM mcr.microsoft.com/playwright:v1.64.1-noble

ARG TARGETARCH
# 1 = bundle Piper + 3 English voices as an offline fallback. Downloads are NON-fatal: if GitHub / Hugging Face
# move a file the image still builds, and the Kokoro voices (tts-kokoro service) keep working.
ARG WITH_PIPER=1
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
      ffmpeg curl ca-certificates tini fonts-inter fonts-firacode fonts-dejavu-core fonts-liberation \
    && rm -rf /var/lib/apt/lists/*

RUN set -u; mkdir -p /opt/piper/voices; \
    if [ "$WITH_PIPER" = "1" ]; then \
      case "${TARGETARCH:-amd64}" in arm64) A=aarch64;; *) A=x86_64;; esac; \
      if curl -fsSL --retry 3 "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_${A}.tar.gz" -o /tmp/piper.tgz \
         && tar -xzf /tmp/piper.tgz -C /opt; then echo "Piper installed"; else echo "WARNING: Piper download failed - Piper voices disabled"; fi; \
      rm -f /tmp/piper.tgz; \
      V=https://huggingface.co/rhasspy/piper-voices/resolve/main/en; \
      for spec in "en_US/ryan/high/en_US-ryan-high" "en_US/lessac/high/en_US-lessac-high" "en_GB/jenny_dioco/medium/en_GB-jenny_dioco-medium"; do \
        n=$(basename "$spec"); \
        curl -fsSL --retry 3 -o "/opt/piper/voices/$n.onnx" "$V/$spec.onnx" \
          && curl -fsSL --retry 3 -o "/opt/piper/voices/$n.onnx.json" "$V/$spec.onnx.json" \
          || { echo "WARNING: voice $n not downloaded"; rm -f "/opt/piper/voices/$n.onnx" "/opt/piper/voices/$n.onnx.json"; }; \
      done; \
    fi

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY . .
RUN mkdir -p /app/data /app/assets/music && chown -R pwuser:pwuser /app

ENV NODE_ENV=production \
    PORT=4000 \
    DATA_DIR=/app/data \
    PIPER_BIN=/opt/piper/piper \
    PIPER_VOICES_DIR=/opt/piper/voices \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    REMOTION_GL=swangle

USER pwuser
EXPOSE 4000
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD curl -fs http://localhost:4000/api/health || exit 1
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["node", "src/server.js"]
