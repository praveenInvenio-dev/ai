# Increment 26 — animated Motion Canvas clips (Revideo) and an open-source Kokoro voice

## Animated clips instead of stills (Motion Canvas scenes)
- `visual-renderer/revideo/` (Revideo 0.11.0, MIT) renders a headless mp4 of the step flow. `POST /render-clip` takes the labels, theme, size, per-step reveal times and total length.
- The backend asks for the clip in the sync stage, when the real sentence start times are known (`MotionTiming`): step i appears 0.15 s before the sentence about it starts.
  The clip replaces the cross-faded stills in the scene video and is held on its last frame if the narration runs longer. Stills stay as the fallback and the preview.
- Never used for narration it was not timed to (clip is tied to the scene's audio version); a re-rendered slide drops its old clip.
- If the renderer has no clips (no Chrome, not installed) or a render fails, the scene keeps its stills and a lesson warning says why.
- Revideo specifics handled: `makeScene2D` needs a scene name; the ffmpeg exporter must be set; the first render in a fresh install retries (Vite re-optimises); telemetry off
  (`DISABLE_TELEMETRY`); it forces Chromium `--single-process`, so the image installs Puppeteer's own Chrome (`npx puppeteer browsers install chrome`).

## Kokoro narrator voice (optional, Apache-2.0, CPU)
- New sidecar `tts-kokoro/` (compose profile `kokoro`): `POST /api/tts` -> 24 kHz WAV, lazy model load, voice/speed validation. `KokoroTTSProvider` + gateway route for `kokoro:<id>`
  (English only; other languages keep their native chain). If the sidecar is down: the local Piper voice of the same gender, with a lesson warning.
- Concept Explainer voice list: "English - Kokoro" (Michael, Heart, George, Emma). Enable with `COMPOSE_PROFILES=...,kokoro` and `docker compose up -d --build tts-kokoro`.
- Voice ids are from the Kokoro model card. Not set as the default: compare it with Chatterbox on the same script first.

## Verification
- 52 unit tests (JUnit stand-in) pass, including motion timing and scene labels. Renderer: headless clip render run for real (1280x720 mp4 returned by `/render-clip`); the exact ffmpeg
  filter used in the scene video was run on a real clip. Kokoro sidecar tested with a stubbed model (WAV, speed, validation, lazy load). Java files parse-checked.
- NOT run: Docker builds, the Maven build of the whole backend, a full lesson with a Motion Canvas clip, the real Kokoro model (weights cannot be downloaded in the test sandbox,
  so no audio was heard), Revideo with Puppeteer's own Chrome (tested here with Playwright's Chromium and the forced single-process flag removed from a local copy).

## Not done
- Mermaid / D2 diagram-as-text (needs the planner to write diagram text; not verifiable without running your model).
- Whisper word-timed captions (needs model weights).
