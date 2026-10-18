# Increment 18 — visual renderer build failure

- Removed `@motion-canvas/cli@^3.17.2`, which fails installation with npm E404 in the provided build log.
- Added the official `@motion-canvas/ffmpeg` exporter package for the supported Motion Canvas v3 ecosystem.
- Removed the invalid `npx motion-canvas render ...` call. Motion Canvas route now fails explicitly until a supported headless player/export harness is configured; this avoids misreporting a scene as Motion Canvas-rendered.
- Three.js and Rive paths are unchanged.
- This increment addresses the reported Docker dependency installation failure; it does not claim the Motion Canvas production adapter is end-to-end complete.
