# Increment 19 — Motion Canvas startup and scene-directory fixes

- Replaced `motion-canvas-render/vite.config.ts` with `vite.config.js` using `createRequire` and explicit CommonJS default-export handling for `@motion-canvas/vite-plugin`, addressing `TypeError: motionCanvas is not a function`.
- Ensured the temporary scene's `src/scenes` directory exists before writing `data-flow.tsx`, addressing `ENOENT` at scene creation.
- Corrected the Compose comment: the visual renderer does not currently have a supported headless Motion Canvas export harness. This patch does **not** claim Motion Canvas production exports are working; the renderer should explicitly report failure and allow the existing native-diagram fallback.
- Chatterbox startup is designed to bootstrap reference clips from the configured TTS service. The uploaded logs do not include the bootstrap warning lines, so this patch does not invent or bundle cloned voice audio.

Validation: source-level changes and archive integrity only. Docker/npm builds and live rendering were not run in this environment.
