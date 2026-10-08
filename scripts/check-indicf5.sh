#!/usr/bin/env bash
set -euo pipefail

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.gpu.yml)

printf '\n== IndicF5 health ==\n'
"${COMPOSE[@]}" exec tts-indic python3 -c \
'import urllib.request; print(urllib.request.urlopen("http://localhost:5003/health").read().decode())'

printf '\n== HF token presence (value is never printed) ==\n'
"${COMPOSE[@]}" exec tts-indic python3 -c \
'import os; t=os.getenv("HF_TOKEN") or os.getenv("HUGGING_FACE_HUB_TOKEN") or ""; print("present:", bool(t), "length:", len(t))'

printf '\n== IndicF5 model load test ==\n'
"${COMPOSE[@]}" exec tts-indic python3 -c \
'from transformers import AutoModel; print("Loading IndicF5..."); m=AutoModel.from_pretrained("ai4bharat/IndicF5", trust_remote_code=True, token=(__import__("os").getenv("HF_TOKEN") or __import__("os").getenv("HUGGING_FACE_HUB_TOKEN"))); print("MODEL LOADED:", type(m))'
