FROM nvidia/cuda:12.8.1-cudnn-devel-ubuntu24.04
ENV DEBIAN_FRONTEND=noninteractive PYTHONUNBUFFERED=1 HF_HOME=/hf-cache TOKENIZERS_PARALLELISM=false
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip libsndfile1 ffmpeg ca-certificates git && rm -rf /var/lib/apt/lists/*
COPY requirements.txt /app/requirements.txt
RUN python3 -m pip install --break-system-packages --no-cache-dir -r /app/requirements.txt
COPY server.py /app/server.py
WORKDIR /app
EXPOSE 5006
CMD ["python3", "-m", "uvicorn", "server:app", "--host", "0.0.0.0", "--port", "5006"]
