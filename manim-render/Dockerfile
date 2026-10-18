# Manim render service: animated maths / physics scenes (MIT licence, CPU only, no GPU needed).
# LaTeX is not installed: the templates use Unicode maths (x^2, sqrt, pi, theta ...), which keeps the image small.
FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential pkg-config libcairo2-dev libpango1.0-dev ffmpeg fontconfig fonts-dejavu-core curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .
ENV MANIM_PORT=5008 PYTHONUNBUFFERED=1
EXPOSE 5008
HEALTHCHECK --interval=30s --timeout=20s --start-period=40s CMD curl -fs http://localhost:5008/health || exit 1
CMD ["python", "server.py"]
