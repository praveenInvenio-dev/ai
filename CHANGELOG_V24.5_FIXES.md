# v24.5 - Fixes: Concept Explainer ffmpeg failure, IndicF5 "Synthesis failed"

## 1. Concept Explainer: "ffmpeg failed ... Could not open encoder before EOF"
Root cause (reproduced): the v24.2 still-image motion ran `scale=2000:1125:force_original_aspect_ratio=decrease,
pad=2000:1125` on every build-step image. The scale rounds to a size 1 px bigger than the pad target, so pad
fails ("Padded dimensions cannot be smaller than input dimensions"), the filter graph never produces a frame and
ffmpeg only reports the generic "Could not open encoder before EOF".
Also: the zoom restarted at every reveal step (visible jump at each new element).
Fix (`ConceptExplainerService.buildClip`):
- steps cross-fade as before, then ONE smooth push-in over the whole scene (`scale ... eval=frame` + `crop`),
  default 1.5 % (`CONCEPT_EXPLAINER_SUBTLE_ZOOM`, 0 = off; Static motion = off).
- ffmpeg errors now show the real cause lines instead of the last 700 characters.
Verified: full Concept Explainer run (5 scenes, 62 s, scene regenerate) succeeds.

## 2. IndicF5 "Synthesis failed" (Voice Lab "Play sample", Concept Explainer, story narration)
Root causes in `tts-indic/Dockerfile`:
- installed PyPI `f5_tts==1.1.22` - a different, newer API. IndicF5's model code needs the AI4Bharat fork
  of `f5_tts` (github.com/AI4Bharat/IndicF5).
- `transformers==5.19.0` - IndicF5 requires `transformers<4.50`.
- `torch==2.14.1` with `torchaudio==2.11.0` - mismatched releases; torchaudio >= 2.9 also needs torchcodec to
  load audio, which F5's reference loader uses.
Fix: Python 3.10, torch/torchaudio 2.7.1 cu128 (RTX 50xx ready), transformers 4.49.0, numpy 1.26.4, the fork's
dependency list, fork installed with --no-deps, import check at build time.
Other fixes:
- model preloads in the background (`INDICF5_PRELOAD`), `/health` shows loading/device/error; requests during
  loading get a clear "still loading" answer instead of hanging.
- CUDA error / out of memory (ComfyUI holding the GPU) -> retries the line on CPU; VRAM released after each line.
- Latin-only text is refused with a clear message (IndicF5 has no English).
- tts proxy passes IndicF5's real error (token / gated / loading / language) instead of "service unavailable".
- backend: Indic voices get a 10 min timeout (was 60 s - every slow first call looked like a failure);
  `/api/tts/preview` returns the engine's reason; Voice Lab shows it.
- Voice Lab: an IndicF5 voice with the English sample text now uses a native sample sentence.
- Concept Explainer: IndicF5 voices offered per language; an explicitly chosen `indic:` voice is honoured
  (it was silently replaced by the Edge voice).

## Rebuild
```
docker compose -f docker-compose.yml -f docker-compose.gpu.yml --profile indic build --no-cache tts-indic
docker compose -f docker-compose.yml -f docker-compose.gpu.yml --profile indic up -d --force-recreate tts-indic tts backend frontend
curl -s localhost:5003/health   # wait for "modelLoaded": true
```

## v24.5.1 - tts-indic build fix
Build failed at the torch step: python:3.10-slim ships pip 23, which rejects the PyTorch index's
"Jinja2"/"typing_extensions" file names ("inconsistent Name") and then tries to build typing_extensions
from source, needing flit_core, which the PyTorch-only index does not have.
- pip/setuptools/wheel upgraded first.
- torch/torchaudio pinned as `2.7.1+cu128` from the PyTorch index with PyPI as extra index.
- `/constraints.txt` keeps torch/torchaudio/numpy/transformers fixed for every later pip step.
- Dependencies pinned to IndicF5's release period (accelerate 1.5.2, datasets 3.5.0, librosa 0.10.2.post1,
  x_transformers 1.44.4, vocos 0.1.0); the set was resolved for Python 3.10 against torch 2.7.1.

## v24.5.2 - IndicF5 vocoder weights
The IndicF5 checkpoint saves the vocoder under `vocoder._orig_mod.*` (torch.compile prefix); the model
expects `vocoder.*`, so transformers logged them as "not used" / "newly initialized" and the vocoder ran with
random weights (noise instead of voice). `tts-indic/server.py` now reloads those tensors with the prefix
stripped right after loading and logs "IndicF5 vocoder weights restored: N tensors loaded".
New `scripts/test-indicf5.sh` tests tts-indic directly, via the tts proxy and via the backend preview.
