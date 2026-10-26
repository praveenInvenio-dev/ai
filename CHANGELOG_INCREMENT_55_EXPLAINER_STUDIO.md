# Increment 55 — Explainer Studio + build fixes

## Build fixes
- backend/Dockerfile: ffmpeg from `mwader/static-ffmpeg:7.1` (multi-stage COPY) instead of the johnvansickle.com
  download that failed with `xz: File format not recognized`.
- frontend/angular.json: `anyComponentStyle` budget 8/16 kB → 24/48 kB (concept-explainer styles are 19.9 kB and
  failed the production build).
- frontend/Dockerfile: `COPY package-lock.json` + `npm ci` (reproducible); frontend/.dockerignore added.

## Explainer Studio (new service `explainer-studio`, nav LEARN → Explainer Studio)
Topic → Ollama (outline + per-scene JSON, schema-checked, self-repairing) → per-sentence TTS (exact durations)
→ cue-resolved timeline → Remotion frames → FFmpeg master (-16 LUFS, optional ducked music) → MP4 + SRT/VTT + thumbnail.

- Ollama: uses OLLAMA_BASE_URL; model picker per job from installed models.
- Voices: Kokoro via the existing `tts-kokoro` service (`COMPOSE_PROFILES=kokoro`), Piper as offline fallback.
- Image: Playwright base (same as visual-renderer) — Chromium headless shell auto-detected, no remotion.media download.
- nginx: `/explainer-api/`, `/explainer-media/`, resolved per request so a stopped explainer never stops the frontend.
- Motion/quality: frozen-frame scene transitions with blur + light sweep, Ken-Burns camera, @remotion/noise aurora +
  particles + grain, spring/de-blur element entrances, word-by-word hook, karaoke caption bounce, typewriter tech notes,
  @remotion/paths self-drawing flow edges with travelling packets.
- New primitives: `icons` (shared Lucide set from visual-renderer), `molecule3d` and `solid3d` (three.js via
  @remotion/three, 2D fallback if WebGL is missing). New subject `general`.
- Encode: intermediate CRF 10 / JPEG 95; final CRF 18, `-tune animation`, `aq-mode=3`, BT.709 tags.
- Render concurrency clamped to core count.

Verified: 15 API tests, Angular production build, API smoke test, stills rendered in Chromium (2D + 3D),
TTS → timeline → mastered narration (0 ms drift). A full-length MP4 render was not completed in the build sandbox.
Licence: Remotion is free for individuals/small companies; larger companies need a company licence.
