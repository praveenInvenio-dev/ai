FROM python:3.11-slim

# espeak-ng: Kokoro's fallback phonemiser; libsndfile1: WAV writing; curl: healthcheck
RUN apt-get update \
    && apt-get install -y --no-install-recommends espeak-ng libsndfile1 curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# CPU-only PyTorch keeps the image small (Kokoro is 82M parameters and runs in real time on a CPU)
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir "kokoro>=0.9.4" soundfile numpy flask==3.0.3

WORKDIR /app
COPY server.py /app/server.py
# model weights are fetched from Hugging Face on the first request and kept in this volume
ENV HF_HOME=/cache/huggingface PORT=5006
EXPOSE 5006
CMD ["python", "server.py"]
