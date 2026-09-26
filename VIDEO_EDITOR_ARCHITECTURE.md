# AI Video Editor — Architecture

Phase 2 deliverable. This document is written against the **actual** AI Story
Studio codebase as it exists today, not against assumptions. Everything in
"What already exists" was read from source.

---

## 1. Phase 1: what already exists

| Concern | Current state | Reuse for the editor? |
|---|---|---|
| Backend | Spring Boot 3.3.4, Java 21, Maven | Yes, same module |
| Frontend | Angular standalone components, lazy routes in `app.routes.ts` | Yes, new lazy route |
| Database | Postgres + Flyway, migrations at `V3__widen_free_text_columns.sql` | Yes, next migration is `V4` |
| Job model | `GenerationJob` entity + `domain/enums/JobStatus` (17 values, story-pipeline specific) | **Partially — see §6** |
| Live progress | `JobEventService`, an in-memory `SseEmitter` registry keyed by job UUID | Yes, directly |
| Storage | `studio.storage.root`, default `/data/projects`, `projects-data` volume | Yes, sibling path |
| FFmpeg | `FFmpegProcessor implements MediaProcessor` — `assembleVideo`, `muxSubtitles`, `renderShort`, `probeDurationSeconds` | **Extend, do not replace** |
| LLM | `OllamaLLMProvider` behind `StoryLLMProvider`, via `ProviderGateway` | Yes, new prompt + strict parse |
| Provider pattern | `ProviderGateway` centralises retry/fallback per provider type | Yes, same shape |
| Auth | **None.** No Spring Security dependency, no user table, `@CrossOrigin` on controllers | Nothing to reuse — see §9 |

Two corrections to the brief, both from reading the code:

- The brief says to reuse "existing authentication". There isn't any. The app is
  single-tenant and unauthenticated. The editor should not invent a login system
  as a side effect; it inherits the same trust model and that must be a conscious
  decision, not an accident.
- The brief lists "Video Generation", "Voice Studio", "Music Studio" and
  "Projects" as existing nav items. Present nav is Dashboard, Create Story,
  Production, Characters, Voice lab. The editor adds one item; it should not
  imply siblings that do not exist.

---

## 2. Scope reality

The brief specifies a professional NLE: 40+ editing techniques, scene detection,
Whisper transcription, beat detection, subject tracking for reframing, a
drag-and-drop timeline with undo/redo, preview rendering, and a full test suite.
That is a multi-month build for a team, and roughly 15–20k lines.

Section 47 rightly forbids scaffolding. The only way to honour both that and the
scope is to build **complete vertical slices** — each one fully working end to
end — rather than a wide shell of half-features. Sequencing is in §10.

---

## 3. Hardware constraints drive the design

Target machine: 12 CPU cores, 16 GB RAM, **2 GB VRAM** (GeForce MX450).

Measured on this machine earlier in the project: ComfyUI SD1.5 at 512×512 runs
~24 s/it on CPU and ~4 s/it on the GPU with 1.5 GB of the model offloaded to
system RAM. The card is genuinely small.

Consequences, which are not optional:

1. **Whisper must be `base` or smaller, CPU, int8.** `faster-whisper` with
   CTranslate2 int8 is ~4x faster than `openai-whisper` at the same accuracy and
   fits in RAM comfortably. `small`/`medium` on CPU are slower than real time on
   long clips. Expect roughly 0.3–0.6x real time for `base` on 8 cores.
2. **No vision model for subject detection.** The brief's "optional lightweight
   vision model" competes with ComfyUI for the same 2 GB. Smart reframing uses
   OpenCV face detection (Haar/DNN, CPU, milliseconds per frame on sampled
   frames) — not a transformer.
3. **Serialise heavy work.** ComfyUI already holds a semaphore permitting one
   in-flight prompt because concurrent jobs on this box make everything slower.
   The video worker needs the same discipline:
   `VIDEO_EDITOR_MAX_CONCURRENT_RENDERS=1` is a correctness setting here, not a
   tuning knob.
4. **CPU x264, not NVENC.** `-preset veryfast -crf 23` on 12 cores comfortably
   beats what a 2 GB MX450 offers, and avoids a hard CUDA dependency.
5. **Preview at 480p, not 720p.** The brief says 720p. On this CPU, 480p
   `ultrafast` preview render is roughly 2.5x faster and adequate for judging
   cuts and timing. Final stays 1080p.

---

## 4. Component layout

```
frontend/src/app/pages/video-editor/     Angular: upload, config, timeline, preview
        |
        v  REST + SSE
backend  controller/VideoEditorController      project/clip/plan/render endpoints
         service/VideoEditorProjectService     persistence, state machine
         service/EditPlannerService            rule engine + Ollama, produces EDL
         service/VideoAnalysisService          calls the worker, stores analysis
         pipeline/editing/TechniqueLibrary     loads technique + template JSON
         pipeline/editing/EdlValidator         strict schema + safety validation
         provider/FFmpegProcessor (extended)   renderTimeline(ValidatedEdl)
        |
        v  HTTP
video-worker (new container, Python)
         /api/analyze     ffprobe + PySceneDetect + OpenCV + faster-whisper
         /api/beats       librosa beat tracking
```

**Why a separate `video-worker` container.** PySceneDetect, OpenCV,
faster-whisper and librosa are Python, and pull ~1.5 GB of wheels. Putting them
in the backend image means every Java rebuild re-resolves them. This mirrors the
existing `tts` and `tts-indic` split, which already works this way.

FFmpeg stays in the **backend**, because `FFmpegProcessor` is already there and
already renders episodes. Splitting rendering across a network boundary would
mean shipping large intermediate files between containers for no benefit.

---

## 5. The AI boundary — non-negotiable

Brief §26 and §37 are the most important constraints in the document, and the
design enforces them structurally rather than by convention:

```
Ollama  ->  JSON EDL (clip ids, timecodes, technique ids ONLY)
              |
              v
        EdlValidator     rejects unknown technique ids, out-of-range
              |          timecodes, unknown clip ids, durations below a
              |          technique's declared minimum, total > target+tolerance
              v
        ValidatedEdl     an immutable typed object; the only thing the
              |          renderer will accept
              v
        FFmpegProcessor  builds argv from ValidatedEdl. Never from a string.
```

Three specific safeguards:

- The LLM emits **technique ids** (`"j_cut"`), never filter strings. A technique
  id that is not in `TechniqueLibrary` is a validation failure, not a
  pass-through.
- FFmpeg is invoked with an **argv array**, never a shell string, so filenames
  cannot inject arguments. `FFmpegProcessor` already does this.
- Clip paths are resolved from the **database by UUID**, never from anything the
  LLM produced. Path traversal is therefore not expressible.

The LLM is also not the only decision maker (brief §14). `EditPlannerService`
runs the deterministic rule engine **first** to produce candidate cut points
from the analysis (beats, scene boundaries, speech boundaries, quality scores),
and Ollama's job is narrative ordering and technique selection within those
candidates. If Ollama is unavailable or returns unparseable JSON, the rule engine
alone still yields a usable timeline. This matters because a 8B local model
returning malformed JSON is a routine event, not an exception.

---

## 6. Data model

New tables in `V4__video_editor.sql`. Names follow the brief; three of its
fourteen are deliberately merged, noted below.

| Table | Purpose |
|---|---|
| `video_editor_project` | name, category, style, intensity, target duration, aspect ratio, options, state |
| `video_clip` | uploaded source: path, original filename, order, ffprobe metadata |
| `video_scene` | scene boundaries per clip from PySceneDetect, with per-scene motion/brightness/quality |
| `video_analysis` | one row per clip, JSONB payload for speech segments, beats, faces |
| `editing_plan` | the validated EDL as JSONB, plus which planner produced it and the LLM rationale |
| `timeline_clip` | user-editable timeline rows: clip ref, source in/out, technique in/out, volume, speed |
| `audio_track` | music/voice-over: path, gain, ducking config |
| `caption_track` | style preset, generated SRT/ASS path |
| `render_job` | state, progress, output path, error, quality report |
| `music_asset` | user-uploaded music with BPM/beat analysis |
| `editing_template` | seeded from JSON resources, overridable per project |

Merged, with reasons: `VideoScene` absorbs `TimelineTrack` (a single video track
plus audio/caption tracks does not need a generic track table until multi-track
video exists); `Export` folds into `render_job` (an export *is* a render job with
a target format); `ProjectSetting` folds into `video_editor_project` columns
(a key-value settings table for a fixed set of known options costs clarity and
buys nothing).

**Job state.** The existing `JobStatus` enum has 17 values, and they are
story-pipeline specific: `GENERATING_SCENES`, `GENERATING_IMAGES`,
`ASSEMBLING_VIDEO`, `GENERATING_SHORTS`. A few (`ANALYZING`, `QUALITY_CHECK`,
`COMPLETED`, `FAILED`, `CANCELLED`) would fit the editor, but adding
`PREVIEW_RENDERING` and `PLAN_READY` to it would leak editor concepts into the
story pipeline's exhaustive switches. The editor gets its own
`VideoEditorState`. `JobEventService` is keyed by UUID and carries no status
type, so it is reused as-is for SSE progress.

---

## 7. Storage

```
/data/video-editor/                    VIDEO_EDITOR_STORAGE_PATH
  projects/<projectId>/
    uploads/     <clipId>.<ext>        originals, never modified
    proxies/     <clipId>.mp4          480p CPU-cheap proxies for analysis + preview
    thumbnails/  <clipId>-<t>.jpg
    analysis/    <clipId>.json
    audio/       music.<ext>, voiceover.wav
    captions/    captions.srt, captions.ass
    previews/    <planHash>.mp4        keyed by plan hash, so an unchanged plan
    renders/     <renderJobId>.mp4     is never re-rendered
```

Two decisions worth stating:

**Proxies are generated once, up front.** Analysis and preview both run against
480p proxies rather than 4K originals. On a 12-core CPU this is the difference
between scene detection taking seconds and taking minutes. Only the final render
touches originals.

**Previews are keyed by plan hash.** Brief §27 asks not to re-render on every
change. Hashing the timeline means an undo/redo round trip hits an existing file
instead of re-rendering, which is the common case while a user is fiddling.

Cleanup: previews older than 24h and orphaned uploads are swept by a scheduled
task. Renders persist until the project is deleted.

---

## 8. Technique library

`resources/video-editor/techniques/*.json`, one file per technique, loaded into
`TechniqueLibrary` at startup. Adding a technique is a resource file, not a code
change (brief §32).

```json
{
  "id": "j_cut",
  "name": "J-cut",
  "category": "AUDIO",
  "purpose": "Next clip's audio starts before its video, pulling the viewer forward.",
  "minSourceDuration": 1.5,
  "maxOverlap": 1.2,
  "parameters": { "audioLeadSeconds": { "min": 0.2, "max": 1.2, "default": 0.5 } },
  "requires": ["nextClipHasSpeech"],
  "incompatibleWith": ["smash_cut", "whip"],
  "intensity": "subtle",
  "render": { "kind": "AUDIO_LEAD", "audioLeadSeconds": "$audioLeadSeconds" }
}
```

`render.kind` is an enum the renderer switches on — `HARD_CUT`, `XFADE`,
`AUDIO_LEAD`, `AUDIO_TAIL`, `ZOOM`, `SPEED_RAMP`, `FREEZE`, `DIP`. There is no
free-text filter field anywhere in the schema, by design: it is what makes §5's
guarantee structural. A technique that cannot be expressed as a known kind
requires a code change, which is the correct amount of friction.

**On brief §8 (V/W transitions).** The brief is right that the terminology is
not standardised. These are modelled as parameterised `XFADE` + `ZOOM`
composites in `transitions/`, with duration, direction, easing, motion, blur and
audio overlap exposed as parameters — no canonical definition assumed.

---

## 9. Security

Path traversal, command injection and codec safety are handled as described in
§5 and §7: UUID-derived paths, argv arrays, whitelisted `render.kind`.

Upload validation: extension and container allowlist (`mp4`, `mov`, `mkv`,
`webm`), `ffprobe` verification that the file is decodable before it is
accepted, per-file and per-project size caps, and generated filenames — the
user's filename is stored as a display label only and never touches the
filesystem.

**What is not solved:** there is no authentication in this application, so the
editor is as open as the rest of it. Uploading arbitrary video and triggering
CPU-heavy renders is a meaningfully larger attack surface than the existing
text-generation endpoints. If this is ever exposed beyond localhost, auth is a
prerequisite, not a follow-up. Stated here rather than buried.

---

## 10. Build sequence

Each slice is independently shippable and fully working. No slice depends on a
later one being stubbed.

**Slice 1 — Upload, analyse, assemble.** Upload clips, ffprobe metadata, proxy
generation, PySceneDetect scene boundaries, quality scoring, deterministic rule
engine (no LLM), hard cuts and cross dissolves only, 1080p render, SSE progress,
project persistence. *This is a working automatic editor.*

**Slice 2 — Timeline UI.** Read-only timeline from slice 1's plan, then trim,
reorder, split, delete, change transition, undo/redo, 480p preview keyed by plan
hash.

**Slice 3 — Audio.** Music upload, librosa beat detection, beat-snapped cuts,
loudness normalisation, ducking under speech.

**Slice 4 — Speech and captions.** faster-whisper `base` int8, silence removal
for vlog mode, SRT/ASS generation, burned-in caption presets.

**Slice 5 — The LLM director.** Ollama EDL generation within rule-engine
candidates, strict validation, "why this edit?" rationale, `KIDS_STORY` mode,
custom natural-language style.

**Slice 6 — Smart reframing.** OpenCV face detection on sampled proxy frames,
dynamic crop for 9:16 from landscape.

**Slice 7 — Story Studio integration.** "Create Video From Story": pull episode
scenes, images, narration and music into a pre-populated editor project.

Slices 1–2 give the acceptance test in §48 minus captions, music and the LLM.
Slices 3–5 complete it.

---

## 11. Configuration

```
VIDEO_EDITOR_ENABLED=true
VIDEO_EDITOR_STORAGE_PATH=/data/video-editor
VIDEO_EDITOR_MAX_CONCURRENT_RENDERS=1     # see §3.3 - correctness, not tuning
VIDEO_EDITOR_MAX_CLIPS=20
VIDEO_EDITOR_MAX_UPLOAD_MB=512
VIDEO_EDITOR_PREVIEW_RESOLUTION=480       # brief says 720; see §3.5
VIDEO_EDITOR_DEFAULT_RESOLUTION=1080
VIDEO_EDITOR_X264_PRESET=veryfast
VIDEO_ANALYSIS_THREADS=8
WHISPER_MODEL=base                        # see §3.1
WHISPER_COMPUTE_TYPE=int8
VIDEO_WORKER_BASE_URL=http://video-worker:5010
```

`VIDEO_EDITOR_ENABLED=false` must fully disable the module — no nav item, no
endpoints, no worker container — so the story pipeline is unaffected by editor
problems.

---

## 12. Known limitations, up front

- **No GPU acceleration.** 2 GB VRAM is spoken for by ComfyUI. Everything here
  is CPU. A 60-second 1080p render on 12 cores is expected to take roughly
  1.5–4 minutes depending on transition count.
- **Whisper `base` misreads accented speech and proper nouns.** Captions will
  need editing. A larger model is a config change and a large speed cost.
- **Subject detection is face detection.** No faces means centre-weighted crop
  with motion bias. Products, animals and landscapes reframe poorly.
- **Beat detection assumes 4/4 with a steady tempo.** librosa handles most
  popular music and struggles with rubato, live recordings and tempo changes.
- **"Emotion" per scene is not real.** The brief's example analysis JSON has
  `"emotion": "happy"`. Nothing in this stack can determine that reliably.
  Emotion is inferred from story metadata in `KIDS_STORY` mode and is otherwise
  absent — rather than present and fabricated.
- **No multi-track video.** One video track, one music track, one voice track.
- **Single tenant, no auth.** See §9.
