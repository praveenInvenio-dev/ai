FROM python:3.11-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends libsndfile1 ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# CPU-only torch from PyTorch's own index. The default PyPI wheel pulls the full
# CUDA runtime (~2.5 GB of nvidia-* packages) which is pure waste here: IndicF5
# runs on CPU in this setup, and even on a 2 GB card the model would not fit
# alongside ComfyUI. This keeps the image at roughly a third of the size.
RUN pip install --no-cache-dir \
      --index-url https://download.pytorch.org/whl/cpu \
      torch==2.4.1

RUN pip install --no-cache-dir \
      transformers==4.44.2 \
      flask==3.0.3 \
      soundfile==0.12.1 \
      numpy==1.26.4 \
      scipy==1.14.1

# Model weights are NOT baked in: ai4bharat/IndicF5 is a gated repo, so it can
# only be fetched with a token the user supplies. They download on first
# synthesis into the HF cache, which is mounted as a volume so the ~1.6 GB
# download survives container recreation.
ENV HF_HOME=/hf-cache
ENV INDICF5_REPO=ai4bharat/IndicF5
ENV INDICF5_PROMPTS_DIR=/prompts

COPY server.py /app/server.py
WORKDIR /app

EXPOSE 5003
CMD ["python3", "server.py"]
