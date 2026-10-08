# v24.3 — Tutor narration + IndicF5 deployment hardening

This package is based on v24.2 and keeps the previously delivered Concept Explainer,
tutor voice, script export/copy, multilingual revoice, Chatterbox reference bootstrap,
and subtle still-image motion changes.

## Included in this release

- IndicF5 is enabled as an optional `indic` Docker Compose profile.
- IndicF5 model loading now passes `HF_TOKEN` explicitly to `AutoModel.from_pretrained`.
- Missing HF credentials produce a clear error instead of an opaque Hugging Face 401.
- Added `scripts/check-indicf5.sh` for safe diagnostics. It reports only token presence/length,
  never the token value.
- `.env.example` documents that IndicF5 is a gated Hugging Face model and that a read token is required.
- Existing IndicF5 reference WAV + transcript pairs are packaged for the supported voice catalogue.
- No real Hugging Face token is included in the package.

## Deploy/update

```bash
cp .env.example .env   # only if creating a new environment
# Set HF_TOKEN in .env; do not commit or paste the real token.

docker compose -f docker-compose.yml -f docker-compose.gpu.yml \
  --profile indic up -d --build --force-recreate tts-indic

scripts/check-indicf5.sh
```

A successful model-load test ends with `MODEL LOADED:`. The first load downloads the gated
IndicF5 weights into the persistent `indicf5-cache` volume.

## Important

The release cannot contain a working Hugging Face credential. If an old token has expired,
replace it in `.env` and recreate `tts-indic`; the application code itself does not need another
secret or hard-coded credential.


## GPU IndicF5 container fix

- `tts-indic/Dockerfile` now installs CUDA-enabled PyTorch and the permanent `pydub`/`f5_tts` dependencies.
- `docker-compose.gpu.yml` now passes one NVIDIA GPU to `tts-indic` and defaults `INDICF5_DEVICE` to `cuda`.
- `.env.example` documents `INDICF5_DEVICE=cuda`.
- IndicF5/HF model weights remain outside the image and use the persistent `indicf5-cache` volume.
