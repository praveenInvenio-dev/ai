# Rive library

Rive animations are designed in the [Rive editor](https://rive.app); the studio cannot create them. Put `.riv` files here and list them in `manifest.json`:

```json
{ "assets": [
  { "file": "loading.riv", "title": "Loading spinner", "keywords": ["waiting", "loading", "freeze", "frozen"] },
  { "file": "mascot.riv",  "title": "Friendly mascot",  "keywords": ["mascot", "character", "meet"] }
] }
```

A scene whose title or narration contains one of a file's keywords (whole word) is planned as RIVE and plays that animation. File names must be plain
(letters, digits, dot, dash, underscore), files up to 25 MB. No manifest or no matching keyword simply means no Rive scenes; a file that fails to load or
draws nothing makes that scene fall back to the native renderer and says so.

To try the pipeline with public sample files: `sh scripts/fetch-rive-samples.sh` (downloads a few from the open-source `rive-react` examples into this folder
and writes a manifest). Those samples are for TESTING; check each file's licence before you publish a video that uses it.
