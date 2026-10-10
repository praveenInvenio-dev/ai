# tts-indicspeak - Indic-Speak (Bodhan AI / AI4Bharat)

Text-to-speech for 22 Indian languages + English, 95 voices, built for teaching: it reads maths, units and chemical formulae
the way a teacher does and keeps Indian-language + English sentences in one voice.
Model: https://huggingface.co/bodhan-ai/indic-speak (Llama-3.2-3B speech LM + SNAC quantizer + Vocos decoder, 24 kHz).

## Start
1. Accept the licence at https://huggingface.co/bodhan-ai/indic-speak with the Hugging Face account that owns `HF_TOKEN` (`.env`).
2. `docker compose -f docker-compose.yml -f docker-compose.gpu.yml --profile indicspeak up -d --build tts-indicspeak`
3. `curl localhost:5006/health` - the first request downloads ~7.6 GB and loads the model (about a minute), later ones are quick.
4. Voice Lab now has an **Indic-Speak** group; the voices (`speak:kn-Deepika`, ...) work everywhere a voice can be chosen.

## Make it the "Indic TTS" everywhere
`INDIC_ENGINE=indicspeak` in `.env` (default `indicf5`). It decides which voices are picked automatically for Kannada/Hindi/Tamil/... in
story narration, Funny Skits, Story Video Production, Video Generation (H3 "Indic TTS voice"), Studio dub and the Concept Explainer.
Explicitly chosen voices always win. English is never switched automatically - pick `speak:en-*` voices by hand.

## GPU sharing
The model needs ~7.6 GB of VRAM. It is loaded on first use and unloaded after `INDICSPEAK_IDLE_UNLOAD_SECONDS` (default 300) without requests,
so ComfyUI (H3, Qwen, Wan) can use the card in between. `INDICSPEAK_PRELOAD=true` keeps it loaded. `POST localhost:5006/api/unload` frees it now.
If a render and a narration collide, the narration fails with a clear "GPU is full" message instead of hanging.

## API
`GET /health`, `GET /api/voices`, `POST /api/tts {"text","voice":"speak:kn-Deepika","style"?,"speed"?}` -> WAV 24 kHz, `POST /api/unload`.
The `tts` service proxies it (and splits long text at sentence boundaries, ~600 characters per call), so clients only ever talk to `tts`.

## Licence (Indic Open Model License v1.0) - the short version
Free to run, change and self-host, commercial use included. Conditions: credit ("Built with Indic-Speak from Bodhan AI / AI4Bharat" wherever you
ship the model or its output to others - the app shows this line next to the voices); derivatives keep this licence; **running it as a
service that other people or companies call needs Bodhan AI's written sign-off**; no harmful use (e.g. impersonation, robocalls, disinformation);
upstream licences (Llama 3.2, SNAC, Vocos) also apply. This is a summary, not legal advice: read the full licence in the model repository.

## Known limits (from the model card)
Style control is preview quality; no laughs/breaths; no voice cloning (use Voice Lab clones / Chatterbox for that); unusual words can be mispronounced.
Judge it on your own sentences: `python3 scripts/tts-bakeoff.py` (see scripts/).
