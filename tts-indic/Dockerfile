# IndicF5 (AI4Bharat) on GPU.
#
# Version notes - these pins are deliberate, please do not "upgrade" them blindly:
#  - IndicF5's Hugging Face remote code imports its OWN fork of f5_tts
#    (github.com/AI4Bharat/IndicF5, package name "f5_tts" 0.1.0). The PyPI
#    "f5_tts" 1.x is a different, newer API and breaks IndicF5 at synthesis time.
#  - IndicF5 requires transformers < 4.50 and numpy <= 1.26.4 (upstream requirements.txt).
#  - torch and torchaudio MUST be the same release. torchaudio >= 2.9 also dropped its
#    built-in audio loading (needs torchcodec), which F5's reference-audio loader relies on.
#  - cu128 wheels are needed for RTX 50xx (Blackwell, sm_120) GPUs; they also run on older cards.
FROM python:3.10-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       libsndfile1 ffmpeg git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir \
      --index-url https://download.pytorch.org/whl/cu128 \
      torch==2.7.1 torchaudio==2.7.1

# IndicF5 runtime dependencies (from the fork's setup.py), with the upstream pins.
RUN pip install --no-cache-dir \
      "transformers==4.49.0" "numpy==1.26.4" "accelerate>=0.33.0" cached_path click \
      "ema_pytorch>=0.5.2" "hydra-core>=1.3.0" jieba librosa matplotlib pydub pypinyin \
      safetensors soundfile tomli torchdiffeq "tqdm>=4.65.0" transformers_stream_generator \
      vocos "x_transformers>=1.31.14" wandb datasets \
      flask==3.0.3 scipy==1.14.1

# The IndicF5 fork of f5_tts itself. --no-deps so it cannot pull a different torch.
RUN pip install --no-cache-dir --no-deps "git+https://github.com/ai4bharat/IndicF5.git" \
    && python -c "import torch, torchaudio, transformers, f5_tts; print('torch', torch.__version__, 'torchaudio', torchaudio.__version__, 'transformers', transformers.__version__)"

# Model weights are NOT baked into the image (gated repo, HF_TOKEN at runtime).
ENV HF_HOME=/hf-cache
ENV HUGGINGFACE_HUB_CACHE=/hf-cache
ENV INDICF5_REPO=ai4bharat/IndicF5
ENV INDICF5_PROMPTS_DIR=/prompts
ENV INDICF5_DEVICE=cuda
ENV INDICF5_PRELOAD=true

COPY server.py /app/server.py
WORKDIR /app

EXPOSE 5003
CMD ["python3", "server.py"]
