# AI Story Studio — Cardboard-style Video Editor Feature Parity

**Audit source:** `ai-story-studio-tutorial-style (13).zip` (2026-10-17)  
**Audit approach:** repository/code inspection. A status of `IMPLEMENTED_NOT_VERIFIED` means code exists but end-to-end runtime behavior has not been proven. Do not treat it as production-verified.

## Status definitions

- `VERIFIED_WORKING`: verified by an executable test or recorded end-to-end evidence.
- `IMPLEMENTED_NOT_VERIFIED`: relevant code path exists; runtime acceptance test still needed.
- `PARTIAL`: only part of the end-user capability is evidenced.
- `MISSING`: no complete implementation was found in this audit.
- `BLOCKED`: implementation requires an unavailable model/provider/environment.

## Baseline feature matrix

| Area | Feature | Baseline status | Evidence / gap |
|---|---|---|---|
| Core editing | Upload multiple video clips | IMPLEMENTED_NOT_VERIFIED | Upload controls and API integration exist; real upload test required. |
| Core editing | Rename, remove and reorder source clips | IMPLEMENTED_NOT_VERIFIED | Source list operations exist; test server persistence. |
| Core editing | Add clips to timeline | IMPLEMENTED_NOT_VERIFIED | Source-to-timeline drag/drop and add-shot operation exist. |
| Core editing | Trim and split clips | IMPLEMENTED_NOT_VERIFIED | Trim controls and split operation exist. |
| Core editing | Reorder, duplicate and delete timeline shots | IMPLEMENTED_NOT_VERIFIED | Timeline operation methods exist. |
| Core editing | Clip speed, volume, mute and lock | IMPLEMENTED_NOT_VERIFIED | Timeline fields and UI controls exist. |
| Core editing | Transitions | IMPLEMENTED_NOT_VERIFIED | Per-shot and apply-to-all transition controls exist. |
| Core editing | Undo/redo | PARTIAL | In-memory history existed; identity restoration and dirty-state handling required hardening. |
| Core editing | Keyboard shortcuts | IMPLEMENTED_NOT_VERIFIED | Delete, Shift+Arrow, Ctrl/Cmd+Z and redo handlers exist. |
| Core editing | Persistent autosave | PARTIAL | Explicit save and project persistence exist; continuous autosave/recovery guarantee not established. |
| Core editing | Version history/restore | IMPLEMENTED_NOT_VERIFIED | Version list and restore API exist. |
| Core editing | Multi-track professional timeline | PARTIAL | Timeline rows and audio infrastructure exist; complete interactive multitrack editing not evidenced. |
| Preview/render | Preview and final render | IMPLEMENTED_NOT_VERIFIED | Preview/render API and job progress handling exist. |
| Preview/render | Render progress and failure handling | IMPLEMENTED_NOT_VERIFIED | SSE/polling and error paths exist; worker-failure acceptance test required. |
| Preview/render | Download rendered video | IMPLEMENTED_NOT_VERIFIED | Rendered download URL exists. |
| Export | EDL export | IMPLEMENTED_NOT_VERIFIED | UI link and exporter exist; target editor compatibility not verified. |
| Export | FCPXML export | IMPLEMENTED_NOT_VERIFIED | UI link and exporter exist; target editor compatibility not verified. |
| Export | Resolution/FPS presets | PARTIAL | Settings/commands exist; actual output capabilities need runtime validation. |
| AI Director | Natural-language edit commands | IMPLEMENTED_NOT_VERIFIED | Deterministic commands and AI edit planner are present. |
| AI Director | Structured plan, preview, validate, apply and rollback | PARTIAL | Planner and timeline operations exist; complete closed-loop validation/rollback is not established. |
| AI Director | Explain edits | IMPLEMENTED_NOT_VERIFIED | Plan rationale is displayed when available. |
| Speech | Transcription | IMPLEMENTED_NOT_VERIFIED | Transcript service and faster-whisper workflow exist; real model/media test required. |
| Speech | Transcript search | IMPLEMENTED_NOT_VERIFIED | Transcript search service and tests exist. |
| Speech | Remove dead air | IMPLEMENTED_NOT_VERIFIED | Speech cleanup service exists; test real recordings. |
| Speech | Remove filler words | IMPLEMENTED_NOT_VERIFIED | English filler detection is documented; multilingual coverage is limited. |
| Speech | Retake detection | IMPLEMENTED_NOT_VERIFIED | Cleanup logic exists; edge-case validation required. |
| Speech | Highlight extraction | IMPLEMENTED_NOT_VERIFIED | Highlight service and tests exist; real-media validation required. |
| Captions | Automatic captions | IMPLEMENTED_NOT_VERIFIED | Caption service and style controls exist; sync/legibility validation required. |
| Captions | Editable word-level transcript/timing | PARTIAL | Transcript/timing services exist; fully integrated editing experience not verified. |
| Captions | Multilingual captions/translation | PARTIAL | Other studio language features exist; full editor workflow is not proven. |
| Visual | Filters, look strength and effects | IMPLEMENTED_NOT_VERIFIED | Finishing controls and render pipeline exist. |
| Visual | Text/title/lower-third overlays | IMPLEMENTED_NOT_VERIFIED | Overlay controls and command handling exist. |
| Visual | Animated text overlays (fade, slide, float, pop, pulse) | IMPLEMENTED_NOT_VERIFIED | Added per-overlay animation metadata and FFmpeg frame expressions; verify rendered footage. |
| Visual | Custom text placement | IMPLEMENTED_NOT_VERIFIED | Custom x/y percentage positioning is persisted and rendered. |
| Visual | Text between background and a person (subject-masked depth compositing) | MISSING | Requires per-frame foreground segmentation/mask generation and mask-aware compositing; ordinary drawtext layering cannot produce this effect. |
| Visual | Zoom moments | IMPLEMENTED_NOT_VERIFIED | Overlay and rendering path exist. |
| Visual | Stabilization | PARTIAL | UI/command and rendering path exist; real-footage quality unverified. |
| Visual | Aspect-ratio conversion | IMPLEMENTED_NOT_VERIFIED | Ratio controls and export presets exist. |
| Visual | Subject-aware face/speaker tracking | PARTIAL | Smart-reframing setting exists; changelog indicates center crop rather than proven tracking. |
| Visual | Visual scene detection/keyframes | PARTIAL | Analysis infrastructure exists; complete visual search not established. |
| Visual | Semantic visual search | MISSING | Transcript search does not establish semantic search over visual content. |
| Reference | Reference-video analysis | IMPLEMENTED_NOT_VERIFIED | Upload/analyze/apply UI and backend integration exist; profile accuracy needs tests. |
| Reference | Match pacing, colors, captions and transitions | PARTIAL | Profile-based suggestions exist; comprehensive style matching is not established. |
| Audio | Background music upload/mix | IMPLEMENTED_NOT_VERIFIED | Music upload and render finishing exist. |
| Audio | Music ducking | IMPLEMENTED_NOT_VERIFIED | Finishing/render logic exists; listening test required. |
| Audio | Beat-sync cuts | IMPLEMENTED_NOT_VERIFIED | BeatSyncService exists; actual alignment needs tests. |
| Audio | Voiceover generation | IMPLEMENTED_NOT_VERIFIED | Voiceover UI/service exists; provider-specific tests required. |
| Audio | Dubbing | IMPLEMENTED_NOT_VERIFIED | Dub flow exists; language-specific synchronization needs validation. |
| Audio | Noise reduction | PARTIAL | Command/settings exist; quality and engine support need validation. |
| Audio | Voice cloning inside editor | PARTIAL | Voice integrations exist elsewhere; end-to-end editor integration is not established. |
| Integration | Story-generated videos in editor | PARTIAL | Related generation systems exist; direct asset-to-timeline flow requires verification. |
| Integration | Character consistency | IMPLEMENTED_NOT_VERIFIED | Existing project workflow must be preserved; regression test required. |
| Integration | Generated images, video, narration, SFX and music on timeline | PARTIAL | Generators exist in AI Studio; complete unified insertion flow is not proven. |
| Media import | Stock B-roll search | MISSING | No complete permitted-source integration evidenced. |
| Media import | URL-based video import | MISSING | No complete supported, secure import flow evidenced. |
| Collaboration | Shareable review links | MISSING | No complete sharing flow evidenced. |
| Collaboration | Comments/team editing | MISSING | No complete collaboration flow evidenced. |
| Reliability | Backend project validation | IMPLEMENTED_NOT_VERIFIED | Backend services exist; adversarial/integration tests required. |
| Reliability | Worker restart/retry recovery | PARTIAL | Job infrastructure exists; failure/recovery semantics need acceptance tests. |
| Reliability | Secure media path handling | IMPLEMENTED_NOT_VERIFIED | Must audit actual upload, URL and process boundaries. |
| Reliability | Autosave/crash recovery | PARTIAL | Persistence/version history exist; guaranteed recovery not established. |

## Current implementation increments

### Increment 1 — Timeline history and unsaved-state reliability

- Preserve stable row identity and all editable row fields in local undo/redo snapshots.
- Mark timeline changes dirty after edits, undo and redo.
- Keep undo/redo history available after an explicit save rather than clearing it immediately.
- Clear the dirty indicator only after the backend confirms persistence.
- Show `Unsaved changes` / `Saved` state near timeline actions.
- Support Ctrl/Cmd+Shift+Z as redo in addition to Ctrl/Cmd+Y.

**Verification still required:** Angular production build, browser-level editing regression, backend save/reload behavior, and undo/redo across split/reorder/delete/save/reload.

## Implementation order

1. Stabilize current timeline editing, save state, preview, render and download.
2. Improve undo/redo and project recovery without changing project schema unnecessarily.
3. Complete AI Director plan validation and safe operation execution.
4. Complete speech/caption/highlight workflows and their real-media tests.
5. Implement genuine subject-aware reframing.
6. Improve reference-video analysis and style matching.
7. Wire all generated Story Studio assets directly into the timeline.
8. Complete multitrack/audio/export workflows.
9. Add optional B-roll/import/collaboration integrations only after the core workflow is reliable.

## Test evidence log

| Check | Result | Notes |
|---|---|---|
| ZIP integrity | PASS | Source ZIP extracted successfully. |
| Baseline Angular build | NOT RUN | Dependency installation/build to be attempted in this environment. |
| Maven compile/test | BLOCKED | Maven executable is not installed in this environment at audit time. |
| Editor command regression script | NOT RUN | Requires installed frontend dependencies, including TypeScript. |
| Real-media end-to-end | NOT RUN | Requires configured backend, workers, models, and test media. |

## Increment 2 — Core editor reliability

- **Fixed:** removing a source clip now removes only timeline rows that reference that clip; remaining shots are preserved and revalidated/re-versioned. Previously, removing any source clip cleared the entire project timeline.
- **Fixed:** the Angular editor reloads the authoritative project/timeline after source deletion instead of locally filtering timeline rows and potentially diverging from backend state.
- **Fixed:** timeline save requests capture the submitted payload and compare it with the current editor state when the response returns. If the user edits during an in-flight save, the newer local edits are preserved and the UI remains dirty instead of being overwritten by the older server response.
- **Safety:** preview/render now reports a clear error if the timeline is empty instead of continuing into an invalid save/render path. The backend currently requires at least one timeline entry.
- **Verification limitation:** code-level checks performed; full Angular/Maven builds and real-media runtime tests remain unverified in this environment.


## Increment 4 — foreground-mask text compositing

- **Core compositor:** `IMPLEMENTED_NOT_VERIFIED` — optional `video-worker/foreground_text_compositor.py` uses rembg alpha matte and composites text between background and foreground; see `video-worker/FOREGROUND_TEXT_COMPOSITOR.md`.
- **Angular timeline integration:** `MISSING` — the CLI is not yet wired to the existing render-job API or overlay persistence.
- **Runtime segmentation quality:** `BLOCKED` — requires optional rembg/ONNX dependencies, model weights, FFmpeg, and real footage tests.


## Increment 5 — behind-person overlay editor integration

- **Overlay control:** `IMPLEMENTED_NOT_VERIFIED` — adds `Behind person (AI mask)` to the existing overlay type selector; animation, time, position, and color controls are shared with standard text.
- **Chat command:** `IMPLEMENTED_NOT_VERIFIED` — accepts commands such as `add floating text JVM at 5 seconds behind person`.
- **Backend orchestration:** `IMPLEMENTED_NOT_VERIFIED` — finishing pass calls the internal video-worker `/api/depth-text` endpoint for these overlays before applying remaining standard overlays/voiceover.
- **Worker endpoint:** `IMPLEMENTED_NOT_VERIFIED` — restricts input/output paths to the shared `/data/video-editor` and `/data/projects` volumes and uses the optional rembg segmentation model.
- **Fallback behavior:** `IMPLEMENTED_NOT_VERIFIED` — failed depth overlays are skipped with a note; the rest of the finishing pipeline continues.
- **Runtime quality:** `BLOCKED` until the Docker worker is rebuilt and real moving-person footage is tested. Segmentation may show edge artifacts and the first run may download model weights.
