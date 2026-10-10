# Indic-Speak (Bodhan AI / AI4Bharat) - TTS for 22 Indian languages + English, 95 voices, built for STEM and code-mixed speech.
# https://huggingface.co/bodhan-ai/indic-speak   (gated: accept the licence on Hugging Face with the account behind HF_TOKEN)
#
# Own container on purpose: it needs transformers>=5 (IndicF5 pins transformers<4.50) and a 3.8B-parameter model (~7.6 GB in bf16).
# torch from the PyTorch cu128 index (needed for RTX 50xx / Blackwell; also runs on older cards).
FROM python:3.11-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends libsndfile1 ffmpeg git ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir --upgrade pip setuptools wheel

RUN pip install --no-cache-dir \
      --index-url https://download.pytorch.org/whl/cu128 \
      --extra-index-url https://pypi.org/simple \
      "torch==2.7.1+cu128"

# Runtime dependencies from the model card: transformers>=5, torch, snac, soundfile (+ accelerate for device_map)
RUN pip install --no-cache-dir "transformers>=5" accelerate snac soundfile huggingface_hub flask==3.0.3 numpy \
    && python -c "import torch, transformers, snac, soundfile; print('torch', torch.__version__, 'transformers', transformers.__version__)"

ENV HF_HOME=/hf-cache
ENV HUGGINGFACE_HUB_CACHE=/hf-cache
ENV INDICSPEAK_REPO=bodhan-ai/indic-speak
ENV INDICSPEAK_DEVICE=cuda
# The model is ~7.6 GB of VRAM. Default: load on first request and give the GPU back after a quiet period,
# so ComfyUI (H3, Qwen, Wan) can use it in between. INDICSPEAK_PRELOAD=true keeps it hot instead.
ENV INDICSPEAK_PRELOAD=false
ENV INDICSPEAK_IDLE_UNLOAD_SECONDS=300

COPY server.py /app/server.py
WORKDIR /app
EXPOSE 5006
CMD ["python3", "server.py"]
