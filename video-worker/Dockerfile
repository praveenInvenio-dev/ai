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
      scenedetect==0.6.4

COPY server.py /app/server.py
WORKDIR /app

EXPOSE 5010
CMD ["python3", "server.py"]
