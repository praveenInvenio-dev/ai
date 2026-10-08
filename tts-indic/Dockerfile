FROM python:3.11-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       libsndfile1 \
       ffmpeg \
       ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# NVIDIA / CUDA PyTorch for IndicF5 GPU inference.
# The container receives the NVIDIA GPU from docker-compose.gpu.yml.
RUN pip install --no-cache-dir \
      --index-url https://download.pytorch.org/whl/cu130 \
      torch==2.14.1 \
      torchaudio==2.11.0

# IndicF5 and F5-TTS runtime dependencies.
RUN pip install --no-cache-dir \
      transformers==5.19.0 \
      flask==3.0.3 \
      soundfile==0.12.1 \
      numpy==1.26.4 \
      scipy==1.14.1 \
      pydub==0.25.1 \
      f5_tts==1.1.22

# Model weights are NOT baked into the image.
# IndicF5 is gated and uses HF_TOKEN supplied by docker-compose/.env.
# The HF cache is mounted as a persistent Docker volume so model downloads
# survive container recreation.
ENV HF_HOME=/hf-cache
ENV TRANSFORMERS_CACHE=/hf-cache
ENV HUGGINGFACE_HUB_CACHE=/hf-cache
ENV INDICF5_REPO=ai4bharat/IndicF5
ENV INDICF5_PROMPTS_DIR=/prompts
ENV INDICF5_DEVICE=cuda

COPY server.py /app/server.py
WORKDIR /app

EXPOSE 5003
CMD ["python3", "server.py"]
