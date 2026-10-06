# AI Story & Content Production Studio

> **Current model stack (v18.16):** images = Qwen Image 2.1 only (character references
> passed natively); video = Wan 2.2 TI2V-5B (fast), Wan 2.2 I2V-A14B (best), MiniMax H3
> (optional, native audio). See **MODELS.md** and `./download-models.sh`.
> Older release notes are in `docs/history/`, changes in `CHANGELOG.md`.


A self-hosted creative studio that turns a short idea into a fully produced
video: story, characters, storyboard, images, narration, music, subtitles,
thumbnail and Shorts — with an explicit approval step before anything
expensive gets generated.

```
IDEA → DRAFT → APPROVE → PRODUCE → DOWNLOAD
```

Open source. Local-first. Docker-first. No required paid API.

---

## 1. What is AI Story Studio?

You describe an idea ("a funny 3-minute story about a rabbit who finds a
magical moon"), the studio writes a full draft — title, characters, scene
breakdown, image prompts — and shows it to you. Nothing is rendered yet.
Only after you click **Approve & start production** does it generate images,
narration, music timing, video, subtitles, a thumbnail and vertical Shorts.

Stories can be one-offs or live inside a persistent **Universe** with
recurring characters and continuity carried across episodes.

## 2. Architecture

```
Angular (4200) --REST/SSE--> Spring Boot (8080) ---> PostgreSQL
                                    |
                                    +--> Ollama (story LLM)
                                    +--> ComfyUI (Qwen Image 2.1 images,
                                    |             Wan 2.2 / MiniMax H3 video)
                                    +--> Chatterbox / Piper / Indic TTS (narration)
                                    +--> FFmpeg (video assembly)
                                    +--> Local disk storage (/data/projects)
```

The Angular app never talks to Ollama/ComfyUI/TTS directly — Spring Boot
orchestrates the whole pipeline behind one REST/SSE API. Every AI/media
integration sits behind a provider interface (`StoryLLMProvider`,
`ImageGenerationProvider`, `TextToSpeechProvider`, `MediaProcessor`) so a
provider can be swapped without touching pipeline code.

### Story production pipeline (spec section 38)

```
OLLAMA
   |
STORY DIRECTOR
   |
SCENE JSON  (per scene: emotion, camera, importance, animation mode)
   |
COMFYUI  Qwen Image 2.1 (locked character refs passed natively as <image1>/<image2>)
   |
IMAGE
   |
2.5D ANIMATION ENGINE (FFmpeg) or AI VIDEO (Wan 2.2 TI2V-5B / I2V-A14B, MiniMax H3)
   |
CHARACTER MOTION / PARALLAX / CAMERA / TALKING-CHARACTER LIP-SYNC
   |
CHATTERBOX / PIPER TTS
   |
NARRATION PERFORMANCE ENGINE  (punctuation + emotion pause/pace/breath)
   |
AUDIO MIXER  (narration + ambience + SFX + music, all ducked)
   |
FFMPEG
   |
FINAL VIDEO
```

### Open-source / offline mode

Every step above runs locally by default: Ollama, ComfyUI, Piper, FFmpeg,
and the animation engine all require no internet access once their models
are downloaded. `OFFLINE_MODE=true` additionally *enforces* this in code
(not just by default config) — it refuses Sarvam TTS, the generic cloud
animation provider, and Edge TTS's cloud calls, falling back to the local
equivalent instead of silently reaching out. See
`docs/OPEN_SOURCE_LICENSES.md` and `voices/LICENSES.md` for exactly what's
verified and what still needs your own review before commercial use.

## 3. Requirements

- Docker + Docker Compose v2, NVIDIA Container Toolkit
- NVIDIA GPU with 16 GB VRAM (the model stack is sized for this)
- 64 GB system RAM recommended (Wan 14B / H3 offload weights to RAM); 32 GB is
  enough for images + Wan TI2V-5B
- ~100 GB free disk for all models (~50 GB without Wan 14B and H3) - see `MODELS.md`

## 4. GPU requirements

All real image/video generation runs on the GPU through ComfyUI:
Qwen Image 2.1 (images), Wan 2.2 TI2V-5B / I2V-A14B and MiniMax H3 (video).
Start the stack with the GPU overlay:
`docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d`.

## 5. ComfyUI GPU image (important)

ComfyUI runs on `yanwk/comfyui-boot:cu130-slim-v2` (PyTorch 2.13+cu130, driver 580 /
CUDA 13.0, RTX 5060 Ti) with `gpus: all`. There is no CPU mode any more; use
`DEMO_MODE=true` to try the flow without GPU models.

> ComfyUI GPU image configuration is defined in both `docker-compose.yml` and
> `docker-compose.gpu.yml`. Any future change to the ComfyUI CUDA image MUST update both
> configurations (or set `COMFYUI_GPU_IMAGE` in `.env`, the single override) so the GPU
> override cannot silently downgrade the image. Check before every deploy:
>
> ```bash
> scripts/validate-comfyui-config.sh   # fails on cu128/cu124/:cpu, --cpu, or no GPU
> ```

Model volume `ai-story-studio_comfyui-gpu-root` (all of ComfyUI's `/root`) is reused as-is;
never run `docker volume prune`.

## 6. Docker installation

```bash
git clone <this-repo>
cd ai-story-studio
cp .env.example .env          # then set DB_PASSWORD, OLLAMA_MODEL etc.
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --build
```

Open http://localhost:4200. Or run `./setup-ai-story-studio.sh` for the
scripted server setup (stack + models + smoke tests).

## 7. First startup

`.env.example` ships with `DEMO_MODE=false`. Until the models are downloaded
(`./download-models.sh`), set `DEMO_MODE=true` to try the idea -> draft ->
approve -> produce -> download flow with placeholder images and timed silence.

## 8. Model installation

### Story LLM (Ollama) — using your existing local install (default)

By default the backend does **not** run its own Ollama container — it talks
to whatever Ollama you already have installed and running on your machine,
via `http://host.docker.internal:11434` (see `docker-compose.yml`). If you
already run `ollama serve` locally, you're done: just pull whichever models
you want and they'll show up in a dropdown on the Create Story screen.

```bash
ollama pull llama3.1
ollama pull mistral      # optional — every installed model appears in the UI dropdown
```

> **Important — loopback binding.** Ollama listens on `127.0.0.1` only by
> default, which refuses connections arriving from inside Docker even
> through `host.docker.internal`. Either restart it bound to all interfaces:
> ```bash
> OLLAMA_HOST=0.0.0.0 ollama serve
> ```
> or, if you installed Ollama as a system service, set `OLLAMA_HOST=0.0.0.0`
> in its environment (e.g. `systemctl edit ollama` on Linux) and restart it.

Prefer to run Ollama inside Docker instead? Start the optional bundled
service and repoint the backend at it:
```bash
docker compose --profile bundled-ollama up -d
# then in .env: OLLAMA_BASE_URL=http://ollama:11434
docker compose up -d --force-recreate backend
docker compose exec ollama ollama pull llama3.1
```

### Image and video models (ComfyUI)

One model per job - see `MODELS.md` for the full table:

| Job | Model |
|---|---|
| Scene images + character references | Qwen Image 2.1 int8 (+ Qwen3-VL 8B, VAE, RealESRGAN x2) |
| Video, fast (text or image to video, 720p@24) | Wan 2.2 TI2V-5B fp8 |
| Video, best (image to video, 480p@16) | Wan 2.2 I2V-A14B fp8 (high + low noise experts) |
| Video + native audio (optional) | MiniMax H3 pruned w6a8 (16gb profile) |

Download everything after ComfyUI finished its first start:

```bash
chmod +x download-models.sh
nohup ./download-models.sh > download.log 2>&1 &   # SKIP_H3=1 / SKIP_WAN14B=1 to skip parts
```

ComfyUI must be recent enough to include the native Qwen Image 2.1 nodes
(`TextEncodeQwenImage21`, `QwenImage21Cache`) and MiniMax H3 nodes. Check:

```bash
docker compose exec comfyui grep -c "class QwenImage21Cache\|class TextEncodeQwenImage21" /root/ComfyUI/comfy_extras/nodes_qwen.py   # -> 2
```

Character consistency: generate a reference in Character Studio and **lock** it.
Up to two locked references per scene are passed to Qwen as `<image1>`/`<image2>`.

### AI Video Editor

Upload clips, let the editor analyse and cut them, render an MP4. Independent of
the story pipeline - it needs no story and never touches ComfyUI.

Four steps in the UI, each a real backend job with SSE progress:

1. **Analyse** - ffprobe metadata, a 480p proxy per clip, PySceneDetect shot
   boundaries, and a quality score per shot (sharpness, exposure, motion).
2. **AI edit** - the rule engine picks shots and transitions from those
   measurements; Ollama may then reorder them. The rule engine always produces a
   complete timeline, so an unreachable or babbling model degrades the result
   rather than breaking it. Which planner ran is recorded on the plan.
3. **Preview** - 480p from the proxies, cached by timeline hash so undo/redo
   reuses an existing file instead of re-encoding.
4. **Render** - full resolution from the originals, with music ducked under speech.

Needs the extra worker container:

```bash
docker compose up -d --build video-worker backend frontend
```

**Transitions are never random.** Each one carries a reason shown in the
timeline - "both shots contain movement, so cutting mid-motion carries the eye
across". Category templates in
`backend/src/main/resources/video-editor/templates/` set shot lengths and ban
techniques: KIDS_STORY excludes whip pans, punch zooms and speed ramps, because
flashing and spinning is unpleasant for young viewers and an accessibility
concern.

Adding a technique is a JSON file in `video-editor/techniques/`, not a code
change. Each declares a `render.kind` from a fixed enum (`HARD_CUT`, `XFADE`,
`ZOOM`, `SPEED`, `AUDIO_LEAD`, `AUDIO_TAIL`) - there is no free-text FFmpeg
field anywhere in the schema, which is what makes "the model can never emit a
filter string" structural rather than a convention.

**Not built yet:** captions (needs Whisper), beat-synced cuts (needs librosa),
smart reframing (needs face detection), and the drag-and-drop timeline. The
timeline is read-only for now; `/capabilities` reports `captions: false` and the
UI disables that option rather than offering it.

### Voices and languages

The Voice lab (left nav) previews any narration voice before you commit an
episode to it. Voices download into the `tts-data` volume on first use.

**What Piper covers.** English (US/GB), and among Indian languages **Hindi**
(Pratham, Priyamvada, Rohan), **Telugu** (Tanuja) and **Malayalam** (Meera).
All are in the catalogue and install on demand.

**What Piper does not cover.** There is no Kannada voice, no Indian-accented
English, and no child voice in any language. Those are engine limitations, not
missing config.

**Child voices** are approximated by raising pitch while holding duration
(ffmpeg post-processes Piper's output). The Voice lab has presets for Young
girl, Young boy and Small creature. Duration is deliberately preserved because
scene lengths are derived from the narration audio - a voice that got higher
*and* faster would desync the video.

**Kannada and Indian English** come from two additional engines, both free:

**Edge voices** (built in, nothing to install). Free neural voices for Kannada,
Hindi, Tamil, Telugu, Malayalam, Marathi and Indian-accented English, listed in
the Voice lab alongside the Piper ones. Caveats worth knowing before you
publish: it is an unofficial use of an endpoint Microsoft ships for its browser,
it needs an internet connection, and it can break without warning. Piper stays
the default for those reasons.

**IndicF5** (AI4Bharat, optional profile). Near-human quality across 11 Indian
languages including Kannada, MIT-licensed so usable commercially - unlike
MMS-TTS, which is the other obvious free option but is CC-BY-NC. Start it with:

```bash
docker compose --profile indic up -d tts-indic
```

Three things to know before you do:

1. **No English.** It is Indic-only, so it supplements Piper rather than
   replacing it. English narration still goes through Piper or Edge.
2. **It needs a reference clip.** Every request takes a reference audio file and
   that clip's transcript, and copies prosody and speaker identity from it. See
   `tts-indic/prompts/README.md` - recording your own ten seconds is the
   intended route, and the model card prohibits cloning voices you have no
   permission to use.
3. **It is slow on CPU.** Flow matching runs many denoising steps over the whole
   utterance, so expect several seconds of compute per second of speech.

A 19-clip evaluation pack (9 languages, correct 24 kHz mono format) ships in
`tts-indic/prompts/`. It is synthesised from Edge voices, so it is for A/B
testing IndicF5 against Edge on your hardware — not for publishing. See the
README in that folder for why, and for how to record a reference you can ship.

The model is gated: accept the terms at
https://huggingface.co/ai4bharat/IndicF5 and put a read token in `HF_TOKEN`.

**If you would rather pay than deal with any of that**, two hosted options:

| | Indic Parler-TTS (local) | Sarvam AI Bulbul (hosted) |
|---|---|---|
| Kannada | yes | yes |
| Indian English accent | yes | yes |
| Child voices | via text voice description | limited |
| Languages | 21 Indic + English | 11 Indic + en-IN |
| Runs offline | yes | no |
| Cost | free | pay per character |
| Hardware | ~880M params; needs a real GPU | none |

Indic Parler-TTS keeps the project fully self-hosted but will not run usefully
on a 2 GB card - it is a transformer, not a 60 MB VITS model, and on CPU it is
far slower than real time. Sarvam works on any hardware and breaks the
offline-only property. Both plug in behind `TextToSpeechProvider` the same way
`LocalTTSProvider` does.

### Scene sequence (long, consistent videos)

Page **Scene sequence** (sidebar, PRODUCTION): give it several scenes and your locked characters and
it makes every scene one by one with the same faces and style, then joins them into one long video.

1. **Keyframes** (fast): each scene's first frame is drawn by Qwen Image 2.1 with the locked
   character reference(s) passed natively (up to two per scene), same style text for every scene.
2. **Review** (on by default): look at the faces, replace/redo any keyframe, edit scene text.
3. **Videos** (slow, GPU): the chosen engine animates each keyframe in order - MiniMax H3 (up to 10 s,
   with sound), Wan 2.2 14B or Wan 2.2 5B (5 s). A scene that runs out of GPU memory at 10 s is retried once at 5 s.
4. **Merge**: clips are normalised (size, 24 fps, audio) and joined, with an optional crossfade.

Continuity modes: **Keyframes** (default, most stable identity) or **Chain** (each scene starts from the last
frame of the previous clip: smoother motion between scenes, but drift can build up over many scenes).
Everything is saved under `video-sequences/<id>/` (kept `VIDEO_SEQUENCE_RETENTION_HOURS`, default 72);
finished scenes are never redone, and "Retry / continue" only makes what is missing.

### Generation speed

Watch the real numbers in `docker compose logs -f comfyui` (`s/it`). Levers:

1. **Qwen steps:** `COMFYUI_QWEN_STEPS` (default 30; FAST profile uses 20).
2. **Wan 14B:** `WAN14B_LIGHTNING=true` = 4-step LoRAs, about 5x faster, slightly softer.
3. **Clip size/length:** 14B at 480x832 is the safe 16 GB setting; 720p is much slower.
4. **One job at a time:** image and video share one ComfyUI slot; before 14B/H3 runs
   the backend calls ComfyUI `/free` so the card starts empty.
5. **Host RAM:** too little RAM for offload = swapping = everything slows down.

If a job exceeds its timeout (`COMFYUI_TIMEOUT_SECONDS`,
`LOCAL_AI_ANIMATION_TIMEOUT_SECONDS`) the backend interrupts and de-queues it.

### TTS

The `tts` service (`./tts`) is a small custom image: it wraps the real
[Piper](https://github.com/rhasspy/piper) binary in a plain HTTP endpoint,
because most ready-made Piper Docker images (including
`rhasspy/wyoming-piper`, which this project shipped with initially) speak
the Wyoming protocol - a binary/TCP protocol, not the simple REST contract
`LocalTTSProvider` expects. The default voice (`en_US-amy-medium`) is baked
into the image at build time. To use a different voice, drop its
`<name>.onnx` + `<name>.onnx.json` (from the
[Piper voices repo](https://huggingface.co/rhasspy/piper-voices)) into the
`tts-data` volume and set `TTS_VOICE=<name>`.

### Turning off demo mode

```
DEMO_MODE=false
```
```bash
docker compose up -d --force-recreate backend
```

### Picking a model per story

Once at least one model is installed and reachable, the **Create Story**
screen shows a **Model** dropdown populated live from `GET
/api/models/ollama` (whatever `ollama list` would show). Each episode
remembers which model it was drafted with, so **Regenerate** reuses the same
model automatically.

## 9. Creating a story

Dashboard → **Create new story** → describe the idea, pick duration/age/
genre/tone/visual style → **Create story draft**. Review the draft, click
**Regenerate** as many times as you like, then **Approve & start
production**.

## 10. Creating characters

Character Studio → pick a project/universe → **New character**, or let the
story engine propose characters in a draft first and promote them later.
Lock a character once you're happy with its canonical description so the
story engine can never quietly redraw it.

## 11. Creating episodes

Episodes belong to a project and, optionally, a universe. Give two episodes
the same universe and their characters, locations and continuity notes
carry forward automatically via a compact `EpisodeMemory` (never the full
prior transcript).

## 12. Troubleshooting

| Symptom | Likely cause |
|---|---|
| Draft creation fails / times out | Ollama isn't reachable. If using your local install, confirm it's bound to `0.0.0.0` (see section 8) and `curl http://localhost:11434/api/tags` works on the host. If using the bundled container, `docker compose logs ollama` |
| Model dropdown on Create Story is empty | `GET /api/models/ollama` returned `healthy: false` — same loopback-binding issue as above, or `OLLAMA_BASE_URL` points at the wrong host/port |
| Images look like colored placeholder cards with the prompt text printed on them | You're in `DEMO_MODE=true`, or ComfyUI failed and the pipeline fell back silently - check `docker compose logs backend \| grep -i "falling back"` |
| Narration is completely silent (not just quiet) | Same fallback logic as images, but for TTS specifically. `docker compose logs tts` and confirm `docker compose exec backend curl http://tts:5002/health` returns `{"status":"ok"}` |
| Story text/title/characters look like real, specific writing, but images/audio are placeholders | Normal - Ollama and the media providers (ComfyUI/TTS) are fully independent. This means the LLM is working; only image/TTS generation is falling back |
| Video assembly fails | Check `docker compose logs backend` for the `ffmpeg` invocation and exit code |
| `/api/health` shows `ollama: false` | Ollama container still starting, or `OLLAMA_BASE_URL` misconfigured |

`GET /api/health` shows demoMode plus live status for ollama/comfyui/tts/vision in one call. `/api/health/ollama`, `/api/health/comfyui`, `/api/health/tts` give the same checks individually. Note: comfyui/tts showing `healthy: true` means they're *reachable* - if `demoMode` is `true`, images/audio still use placeholders regardless, since demo mode skips calling them at all.

## 13. Storage

All generated files live under a single named volume mounted at
`/data/projects` inside the backend container, organized as
`/data/projects/{projectId}/{episodeId}/{images,audio,video,subtitles,thumbnail,shorts}`.
Metadata (prompts, scenes, job history) lives in PostgreSQL; binaries never
go into the database.

## 14. Configuration

All model/provider configuration is environment-driven — see
`.env.example` and `backend/src/main/resources/application.yml`. Nothing is
hard-coded: model names, workflow names, TTS voice, storage path and the
quality-gate threshold are all overridable without touching code.

## 15. Development

```bash
# Backend (needs a local Postgres or `docker compose up -d postgres`)
cd backend && mvn spring-boot:run

# Frontend
cd frontend && npm install && npm start   # http://localhost:4200, proxies /api itself in dev via environment.ts
```

Mock providers (`MockImageGenerationProvider`, `MockTTSProvider`) let you
exercise the full pipeline — timing, video assembly, subtitles, packaging —
without a GPU or any model downloads; they're what `DEMO_MODE` switches on.

## 16. Internet access / remote devices

The Docker stack uses a single public entry point: the Angular/Nginx `frontend`
container. Nginx proxies `/api/*` to Spring Boot over the private Docker network,
so browsers on another device do not need direct access to port 8080. PostgreSQL,
ComfyUI, TTS, and the video worker are bound to loopback/internal Docker networking
and are not intentionally exposed to the Internet.

### Vast.ai / public-IP setup

1. Start the GPU stack:

```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --build
```

2. Check the public URL:

```bash
./scripts/public-access.sh
```

3. In the Vast.ai instance/network settings, allow the TCP host port from
   `PUBLIC_HTTP_PORT` (default `4200`). The application binds that port to
   `0.0.0.0`.

4. From any phone/laptop on the Internet, open:

```text
http://<VAST-PUBLIC-IP>:4200
```

No domain is required. The same URL works for the Angular UI, REST API, uploads,
SSE progress, and generated media because they all travel through the frontend
Nginx entry point.

### Changing the public port

Set these in `.env` before recreating the frontend:

```dotenv
PUBLIC_BIND_ADDRESS=0.0.0.0
PUBLIC_HTTP_PORT=4200
```

Then run:

```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --force-recreate frontend
```

Do not expose PostgreSQL `5432`, Spring Boot `8080`, ComfyUI `8188`, TTS `5002-5005`,
or video-worker `5010` publicly.

> If your cloud provider does not permit inbound ports on the instance, a public
> IP alone is not sufficient; the provider firewall/network rule must allow the
> selected TCP port.

## 17. Production deployment

- Set `DEMO_MODE=false`, real DB credentials, and pull real models before
  going live.
- Put a reverse proxy with TLS in front of the `frontend` service.
- Back up the `postgres-data` and `projects-data` volumes.
- Consider a GPU host for `docker-compose.gpu.yml` — image generation is by
  far the slowest stage on CPU.

## 18. Licensing

The application code license is up to you to add (e.g. `LICENSE` at the
repo root). See [`MODEL_LICENSE.md`](./MODEL_LICENSE.md) for the licensing
terms of the AI models this project recommends — they are **not** the same
license as the code, and not all of them are unrestricted for commercial
use.

---

## 19. Docker commands

```bash
docker compose up -d              # start everything
docker compose down               # stop everything
docker compose logs -f backend    # tail backend logs
docker compose restart backend    # restart one service
docker compose pull               # update images
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d   # GPU mode
```

## 20. Known limitations of this initial scaffold

- The backend build now fails fast (via `backend/check-migrations.sh`,
  wired into the Maven `validate` phase) if two Flyway migration files ever
  claim the same version number - `docker compose up -d --build` will stop
  with a clear error instead of producing a jar that crashes on boot.
- ComfyUI graphs live in `backend/src/main/resources/comfyui-workflows/`
  (API format, `{{PLACEHOLDER}}` values filled by the backend; numeric
  placeholders become JSON numbers; top-level `_comment` keys are stripped).
- Scenes with 3+ characters pass at most two locked references to Qwen; the
  others are carried by the text description only.
- The Model Manager UI (spec section 50) and first-run setup wizard (section
  49) are not yet built; `/api/health` covers the same signal today.
- Continuity checking is a heuristic (regex-based prop tracking), not an LLM
  reasoner — good enough to flag obvious cases, not exhaustive.
- No authentication layer yet — add one before exposing this beyond your own
  machine.

## 21. Next recommended improvements

1. First-run setup wizard + Model Manager UI (install/delete models from the
   browser instead of `docker compose exec`).
2. Per-scene "Regenerate image / Regenerate narration" endpoints and UI
   wiring (the data model already supports asset versioning).
3. Optional SageAttention build of ComfyUI for faster sampling at the same quality.
4. Automated tests: mock-provider pipeline tests, FFmpeg argument tests,
   repository tests.
5. Drag-and-drop scene reordering in the storyboard.

### Character identity image references

In Story Approval → Character Building, use **Upload identity image** to attach a real character photo/reference. Lock the reference if it should be preferred for future scenes. Qwen Image 2.1 uses the reference while generating scene images, and MiniMax H3 uses the same image as a dedicated `<Picture 2>` identity reference during H3 Reference-to-Video generation; the selected storyboard image remains `<Picture 1>` and controls the scene composition/action.
