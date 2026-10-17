# v24.12 — Motion text overlays

## Added
- Text overlays can choose fade, static, slide-up, slide-from-left, floating drift, pop-in, or gentle pulse motion.
- Custom percentage-based X/Y placement is available for text overlays.
- Animation and placement are sanitized and persisted in the existing overlay JSON, preserving compatibility with older projects (missing fields default to fade and center percentages).
- The UI labels each text overlay with its selected motion style.

## Explicit limitation
Text motion is rendered by FFmpeg and sits on top of the rendered footage. True text-behind-person compositing is not implemented by this change; it requires a foreground segmentation mask or equivalent subject matte, tracked across frames, and a mask-aware composite pipeline. The UI calls out this limitation instead of implying the effect is already supported.

## Verification
- Editor command checks: 85/85 passed, including new floating-text and slide-up command cases.
- TypeScript transpilation: zero diagnostics.
- FFmpeg animated drawtext expression smoke test: passed on a synthetic video source.
- ZIP integrity and preservation of all original file entries: passed.
- Full Angular build and Maven/JUnit suite remain unverified because dependencies/Maven are unavailable in this environment.
