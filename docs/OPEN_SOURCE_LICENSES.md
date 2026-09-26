# Open Source Component License Audit

Spec section 32. Per component actually used by this project - not a claim
that a public GitHub repo automatically means open source (spec's own
warning), each row states what was actually verified and where.

| Component | Version (pinned in this repo) | Repository | License | Used for | Commercial use |
|---|---|---|---|---|---|
| Spring Boot | 3.3.4 | spring-projects/spring-boot | Apache-2.0 | Backend framework | Yes |
| Angular | see frontend/package.json | angular/angular | MIT | Frontend framework | Yes |
| FFmpeg | container base image default | ffmpeg/ffmpeg | LGPL-2.1+ (GPL components NOT explicitly enabled in this project's build/run flags) | All audio/video rendering | Yes, under LGPL terms - verify your specific FFmpeg build's enabled components before commercial redistribution |
| OpenCV opencv-python-headless | 4.10.0.84 | opencv/opencv-python | Apache-2.0 | video-worker scene detection/quality scoring | Yes |
| NumPy | less than 2, video-worker | numpy/numpy | BSD-3-Clause | video-worker numeric ops | Yes |
| PySceneDetect | 0.6.4 | Breakthrough/PySceneDetect | BSD-3-Clause | Shot boundary detection | Yes |
| Flask | 3.0.3 | pallets/flask | BSD-3-Clause | video-worker, tts service HTTP layer | Yes |
| Piper | binary, downloaded at build | rhasspy/piper | MIT | Default local TTS engine | Yes |
| edge-tts | 7.2.8 to less than 8 | rany2/edge-tts | LGPL-3.0 for the Python package itself | Optional Edge-voice TTS, calls Microsoft's hosted service, NOT local; disabled entirely when OFFLINE_MODE=true | Package license permits it; the underlying Microsoft service's own terms are separate and not audited here |
| ComfyUI | external, user-installed | comfyanonymous/ComfyUI | GPL-3.0 | Image and optional local AI video generation | Yes under GPL-3.0 terms for ComfyUI itself; checkpoints loaded into it each carry their own separate license, see below |
| ComfyUI-Manager | external, user-installed | ltdrdata/ComfyUI-Manager | GPL-3.0 | Custom node management in ComfyUI | Yes, under GPL-3.0 |
| ComfyUI-VideoHelperSuite | external, user-installed | Kosinkadink/ComfyUI-VideoHelperSuite | GPL-3.0 | Video muxing for the Wan I2V workflow | Yes, under GPL-3.0 |
| PostgreSQL | container base image default | postgres/postgres | PostgreSQL License, permissive MIT-like | Application database | Yes |
| Ollama | external, user-installed | ollama/ollama | MIT | Local LLM inference | Yes |

## Model licenses (separate from the software above)

Not fixed by this project - every checkpoint is a user download, selected
via .env, and each carries its own license independent of the software that
loads it:

- DreamShaper8/DreamShaperXL checkpoints (Lykon, HuggingFace) - CreativeML Open RAIL-M family license as of last verification; RAIL licenses carry specific use restrictions (e.g. no generating unlawful content) that are not the same as a permissive license like MIT/Apache. Re-check the exact license on whichever checkpoint you actually download.
- Wan 2.1/2.2 diffusion models (Alibaba) - Apache-2.0 as of this project's own research this session - genuinely permissive if that holds for the exact checkpoint variant you use, but verify against the specific HuggingFace repo you download from, not this document.
- IPAdapter/CLIP vision models - typically Apache-2.0/MIT from their respective sources (OpenAI CLIP derivatives, h94/IP-Adapter) - verify per file.
- Piper voice en_US-amy-medium - see voices/LICENSES.md, which documents a real nuance: the repo carries an MIT badge, but the voice's own MODEL_CARD defers to its training dataset's license instead of claiming MIT itself.

## What this document does NOT claim

- That FFmpeg in this project's containers has been audited flag-by-flag for GPL-only components - the base images used are standard distro FFmpeg builds, typically LGPL-configured, but this has not been individually re-verified against the exact container tags in docker-compose.yml.
- That every checkpoint a user might choose to download carries a commercial-safe license - only Piper's default voice and the software components above have been checked here. Any checkpoint you add is your own license verification to do, per spec section 32's own instruction not to assume.
