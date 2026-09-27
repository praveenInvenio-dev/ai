FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends git ffmpeg libsndfile1 ca-certificates && rm -rf /var/lib/apt/lists/*

# CosyVoice has a large dependency stack. Keep it isolated in an optional
# compose profile so existing Piper/ChatterBox installations are unaffected.
ARG COSYVOICE_GPU=0
RUN pip install --no-cache-dir --upgrade pip setuptools wheel
RUN if [ "$COSYVOICE_GPU" = "1" ]; then \
      pip install --no-cache-dir torch==2.7.1 torchaudio==2.7.1 --index-url https://download.pytorch.org/whl/cu128; \
    else \
      pip install --no-cache-dir torch==2.7.1 torchaudio==2.7.1 --index-url https://download.pytorch.org/whl/cpu; \
    fi
RUN pip install --no-cache-dir flask==3.1.2 soundfile==0.13.1 numpy==2.2.6
# CosyVoice is installed from its upstream repository at build time. The model
# itself is deliberately NOT baked into the image; mount it under /models.
RUN git clone --depth 1 https://github.com/FunAudioLLM/CosyVoice.git /opt/CosyVoice && \
    cd /opt/CosyVoice && pip install --no-cache-dir -r requirements.txt

ENV PYTHONPATH=/opt/CosyVoice
ENV COSYVOICE_DEVICE=cpu
ENV COSYVOICE_MODEL_DIR=/models/Fun-CosyVoice3-0.5B-2512
ENV COSYVOICE_VOICE_DIR=/voice-profiles
ENV HF_HOME=/root/.cache

COPY server.py /app/server.py
WORKDIR /app
EXPOSE 5005
CMD ["python3", "server.py"]
