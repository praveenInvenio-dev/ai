# tts-kokoro

Optional open-source narrator voice: [Kokoro](https://huggingface.co/hexgrad/Kokoro-82M) (Apache-2.0, 82M parameters). Fast, clear, runs on CPU, needs no reference clip.
English only (American `a*`, British `b*`). Pick it in Concept Explainer under "English — Kokoro".

    COMPOSE_PROFILES=...,kokoro docker compose up -d --build tts-kokoro

The first request downloads the weights (internet needed once) into the `kokoro-cache` volume. If the service is down the lesson falls back to the local Piper voice of the
same gender and says so in the lesson warnings. Other languages ignore this voice and use their native voice chain.
Voice ids are from the Kokoro model card; `GET /api/voices` lists those this image knows.
