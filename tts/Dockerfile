FROM python:3.11-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tar ca-certificates ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# Real Piper binary (static build), not the Wyoming-protocol server image.
# NOTE: piper's actual release asset names are x86_64 / aarch64, not amd64 / arm64.
ARG TARGETARCH
RUN ARCH_NAME=$([ "$TARGETARCH" = "arm64" ] && echo "aarch64" || echo "x86_64") \
    && curl -fsSL -o /tmp/piper.tar.gz \
       "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_${ARCH_NAME}.tar.gz" \
    && mkdir -p /opt/piper \
    && tar -xzf /tmp/piper.tar.gz -C /opt/piper --strip-components=1 \
    && ln -s /opt/piper/piper /usr/local/bin/piper \
    && rm -f /tmp/piper.tar.gz

# Default voice, matching studio.tts.voice's default (en_US-amy-medium).
# Additional voices can be dropped into the tts-voices volume at
# /voices/<name>.onnx + <name>.onnx.json without rebuilding this image -
# just set TTS_VOICE to that name.
RUN mkdir -p /voices \
    && curl -fsSL -o /voices/en_US-amy-medium.onnx \
       "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx" \
    && curl -fsSL -o /voices/en_US-amy-medium.onnx.json \
       "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx.json"

# edge-tts gives free Kannada / Indian-English / Hindi neural voices that Piper
# does not have. It is a thin client for Microsoft's browser read-aloud endpoint,
# so it adds no model weight to the image - but it does need internet at runtime.
# edge-tts must be 7.x. Microsoft added a signed Sec-MS-GEC token to the
# read-aloud endpoint, and 6.x has no idea about it - every request comes back
# 403 Invalid response status. 7.x generates the token.
RUN pip install --no-cache-dir flask==3.0.3 "edge-tts>=7.2.8,<8"

COPY server.py /app/server.py
WORKDIR /app

ENV PIPER_DEFAULT_VOICE=en_US-amy-medium
EXPOSE 5002
CMD ["python3", "server.py"]
