# AI Story & Content Production Studio

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
                                    +--> ComfyUI (images)
                                    +--> Piper/local TTS (narration)
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
COMFYUI  (character-reference-conditioned when a reference exists)
   |
IMAGE
   |
2.5D ANIMATION ENGINE  (FFmpeg-based - Ken Burns, parallax, particles)
   |
CHARACTER MOTION / PARALLAX / CAMERA / TALKING-CHARACTER LIP-SYNC
   |
PIPER TTS
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

- Docker + Docker Compose v2
- ~10 GB disk for model downloads (skip this if you stay in `DEMO_MODE`)
- Optional: an NVIDIA GPU + NVIDIA Container Toolkit for fast image generation

## 4. GPU requirements

Image generation is the only GPU-heavy stage. With a GPU, a scene image
takes seconds; on CPU it can take minutes. The app never fails without a
GPU — it just runs slower and a "CPU mode enabled" note is shown.

## 5. CPU mode

Nothing to configure — CPU is the default (`docker-compose.yml` alone). Use
`docker-compose.gpu.yml` as an override once you have the NVIDIA Container
Toolkit set up.

## 6. Docker installation

```bash
git clone <this-repo>
cd ai-story-studio
cp .env.example .env
docker compose up -d
```

Open http://localhost:4200.

## 7. First startup

On first boot `DEMO_MODE=true` (the `.env.example` default), so the studio
works immediately with fast placeholder images and silent-timed narration —
useful to try the full idea → draft → approve → produce → download flow
without downloading any models. Flip `DEMO_MODE=false` once you've installed
real models (see below) for actual generated content.

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

### Image model (ComfyUI)

Open the ComfyUI UI at http://localhost:8188 and use its built-in model
manager to download a checkpoint (e.g. SDXL base), or drop a `.safetensors`
file into the `comfyui-data` volume's `models/checkpoints` directory.

Whatever you download, set `COMFYUI_MODEL` in `.env` to the exact filename:

```bash
docker compose exec comfyui ls /root/ComfyUI/models/checkpoints
```

**On CPU, use SD1.5, not SDXL.** SDXL is another 5-10x slower again and needs
1024x1024 to look right, which compounds the cost. The default is
`v1-5-pruned-emaonly-fp16.safetensors` for that reason. Switch to SDXL (and
raise `COMFYUI_WIDTH`/`COMFYUI_HEIGHT` to 1024) only once you are on a GPU -
the GPU compose file already does both.

Note: an `fp16` checkpoint still loads as `torch.float32` on CPU. That is
correct, not a misconfiguration - CPU fp16 arithmetic is slower than fp32, so
ComfyUI upcasts deliberately.

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

### Image generation speed (read this before blaming the app)

Time per image = `steps x seconds-per-step`, and seconds-per-step scales with
`width x height`. Watch the real number in the ComfyUI log - the progress bar
prints `s/it`:

```bash
docker compose logs -f comfyui
```

Rough CPU-only numbers with an SD1.5 checkpoint:

| Settings           | Rate      | Per image  |
|--------------------|-----------|------------|
| 1024x576, 30 steps | ~90 s/it  | ~45 min    |
| 512x512, 8 steps   | ~25 s/it  | ~3-4 min   |
| 448x448, 6 steps   | ~18 s/it  | ~2 min     |

The defaults ship at 512x512 / 8 steps for that reason. Levers, in order of
how much they actually help:

1. **Use a GPU.** `docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d`.
   This is a 20-50x difference and no amount of CPU tuning approaches it.
2. **Lower `COMFYUI_WIDTH`/`COMFYUI_HEIGHT`.** Cost is roughly linear in pixel
   count. FFmpeg upscales to 1080p during video assembly regardless, and SD1.5
   generates *worse* images above ~768px (duplicated heads and limbs), so
   generating large is usually paying more for less.
3. **Lower `COMFYUI_STEPS`.** `dpmpp_2m` + `karras` holds together down to
   about 8 steps on a normal checkpoint.
4. **Use a low-step model.** An SD-Turbo / SDXL-Lightning checkpoint runs at
   4-6 steps with `COMFYUI_CFG=1.5`. Or keep your checkpoint and add the
   LCM-LoRA with the bundled `character-consistent-story-lcm` workflow:
   download `pytorch_lora_weights.safetensors` from
   [lcm-lora-sdv1-5](https://huggingface.co/latent-consistency/lcm-lora-sdv1-5)
   into `models/loras` as `lcm-lora.safetensors`, then set:
   ```
   COMFYUI_WORKFLOW=character-consistent-story-lcm
   COMFYUI_STEPS=6
   COMFYUI_CFG=1.5
   COMFYUI_SAMPLER=lcm
   COMFYUI_SCHEDULER=sgm_uniform
   ```
5. **Give Docker more CPU.** Docker Desktop > Settings > Resources, then set
   `COMFYUI_THREADS` to your physical core count. More threads than physical
   cores generally makes diffusion slower.
6. **`--preview-method none`** is already set for you in `docker-compose.yml`.
   Live previews VAE-decode the latent every step, which on CPU can cost more
   than the sampling itself.

   The compose file passes `--cpu --preview-method none` as a hard-coded
   string, and `--cpu` is deliberately not overridable: the container's flags
   are set through `CLI_ARGS`, which *replaces* the image's own defaults rather
   than adding to them, so any value missing `--cpu` sends ComfyUI looking for
   CUDA and it crash-loops on `AssertionError: Torch not compiled with CUDA
   enabled`. Extra flags go in `COMFYUI_EXTRA_CLI_ARGS`, which is appended.
   If your `.env` still has a `COMFYUI_CLI_ARGS` line from an earlier version,
   delete it - it is no longer read.

If an image still exceeds `COMFYUI_TIMEOUT_SECONDS`, the backend now
interrupts and de-queues that prompt rather than abandoning it, and only one
prompt is ever in flight at a time - an over-long scene can no longer starve
the scenes queued behind it. Set `COMFYUI_FALLBACK_PLACEHOLDER=false` while
debugging so failures surface as failed jobs instead of placeholder frames
quietly appearing in the finished video.

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

## 16. Production deployment

- Set `DEMO_MODE=false`, real DB credentials, and pull real models before
  going live.
- Put a reverse proxy with TLS in front of the `frontend` service.
- Back up the `postgres-data` and `projects-data` volumes.
- Consider a GPU host for `docker-compose.gpu.yml` — image generation is by
  far the slowest stage on CPU.

## 17. Licensing

The application code license is up to you to add (e.g. `LICENSE` at the
repo root). See [`MODEL_LICENSE.md`](./MODEL_LICENSE.md) for the licensing
terms of the AI models this project recommends — they are **not** the same
license as the code, and not all of them are unrestricted for commercial
use.

---

## Docker commands

```bash
docker compose up -d              # start everything
docker compose down               # stop everything
docker compose logs -f backend    # tail backend logs
docker compose restart backend    # restart one service
docker compose pull               # update images
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d   # GPU mode
```

## Known limitations of this initial scaffold

- The backend build now fails fast (via `backend/check-migrations.sh`,
  wired into the Maven `validate` phase) if two Flyway migration files ever
  claim the same version number - `docker compose up -d --build` will stop
  with a clear error instead of producing a jar that crashes on boot.
- ComfyUI integration assumes a text-to-image workflow shaped like
  `backend/src/main/resources/comfyui-workflows/character-consistent-story.json`;
  swap in your own workflow export and update node IDs if your setup differs.
  Templates support `{{POSITIVE_PROMPT}}`, `{{NEGATIVE_PROMPT}}`, `{{SEED}}`,
  `{{WIDTH}}`, `{{HEIGHT}}`, `{{STEPS}}`, `{{CFG}}`, `{{CHECKPOINT}}`,
  `{{SAMPLER}}` and `{{SCHEDULER}}`; numeric placeholders are injected as JSON
  numbers, not strings. Top-level keys starting with `_` are stripped before
  submission, so templates can carry `_comment` documentation.
- Reference-image-conditioned generation (upload a character photo → locked
  visual identity) is wired at the data-model level (`CharacterReference`)
  but the ComfyUI workflow template included here is text-to-image only —
  extend it with an image-conditioning workflow for full fidelity.
- The Model Manager UI (spec section 50) and first-run setup wizard (section
  49) are not yet built; `/api/health` covers the same signal today.
- Continuity checking is a heuristic (regex-based prop tracking), not an LLM
  reasoner — good enough to flag obvious cases, not exhaustive.
- No authentication layer yet — add one before exposing this beyond your own
  machine.

## Next recommended improvements

1. First-run setup wizard + Model Manager UI (install/delete models from the
   browser instead of `docker compose exec`).
2. Per-scene "Regenerate image / Regenerate narration" endpoints and UI
   wiring (the data model already supports asset versioning).
3. Image-conditioned ComfyUI workflow for character reference fidelity.
4. Automated tests: mock-provider pipeline tests, FFmpeg argument tests,
   repository tests.
5. Drag-and-drop scene reordering in the storyboard.
