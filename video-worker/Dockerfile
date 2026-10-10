FROM python:3.11-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# opencv-python-headless, not opencv-python: the full package pulls GTK/Qt for
# windowing that a container never uses, roughly doubling image size for nothing.
RUN pip install --no-cache-dir \
      flask==3.0.3 \
      opencv-python-headless==4.10.0.84 \
      "numpy<2" \
      scenedetect==0.6.4 \
      "rembg[cpu]==2.0.59" \
      pillow==10.4.0 \
      faster-whisper==1.0.3

# rembg downloads its ONNX models (isnet / birefnet / u2net) on first use into
# U2NET_HOME, which is a named volume so they survive rebuilds.
ENV U2NET_HOME=/models/rembg
# Auto captions (video editor): faster-whisper model cache, model size via WHISPER_MODEL
ENV HF_HOME=/models/hf
ENV WHISPER_MODEL=small
COPY server.py /app/server.py
WORKDIR /app

EXPOSE 5010
CMD ["python3", "server.py"]
