FROM python:3.11-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends libsndfile1 ffmpeg ca-certificates git \
    && rm -rf /var/lib/apt/lists/*

# CPU wheel by default - same reasoning as tts-indic/Dockerfile: the CUDA
# wheel drags in ~2.5GB of nvidia-* packages that only matter if this
# container actually gets GPU access (CHATTERBOX_DEVICE=cuda), and Turbo's
# whole selling point (350M params, single-step decoder) is that it is usable
# on CPU in the first place. If you set CHATTERBOX_DEVICE=cuda, rebuild with
# a CUDA torch wheel instead - this base image will not see the GPU.
ARG TORCH_INDEX_URL=https://download.pytorch.org/whl/cpu
ARG TORCH_PACKAGES="torch==2.4.1 torchaudio==2.4.1"
RUN pip install --no-cache-dir --index-url ${TORCH_INDEX_URL} ${TORCH_PACKAGES}

# Real pip package, confirmed at https://pypi.org/project/chatterbox-tts/
# (Resemble AI). The service uses the current chatterbox.tts / chatterbox.mtl_tts APIs for
# standard and Multilingual V3 voice cloning.
RUN pip install --no-cache-dir \
      chatterbox-tts \
      flask==3.0.3 \
      soundfile==0.12.1 \
      numpy==1.26.4

# Weights are NOT baked in - ChatterboxTurboTTS.from_pretrained() downloads
# from Hugging Face on first use (a few hundred MB, matching the model's
# 350M-parameter size) into this cache, which is mounted as a volume so the
# download survives container recreation.
ENV HF_HOME=/hf-cache
ENV CHATTERBOX_DEVICE=cpu
ENV CHATTERBOX_VOICES_DIR=/voices

COPY server.py /app/server.py
WORKDIR /app

EXPOSE 5004
CMD ["python3", "server.py"]
