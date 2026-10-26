# Increment 56 — Named character references for MiniMax H3

Includes everything from Increment 55 (Explainer Studio + build fixes).

## What it does
1. **Upload once.** Video generation → (MiniMax H3 or "Story to video · H3") → *Character references* → **+ Add character**:
   name, optional other names (aliases), **master prompt**, image.
2. **Reference sheet.** By default a clean reference is generated from the upload with the Qwen reference workflow:
   same face, hair, skin tone, body, outfit, colours and accessories; plain grey background, full body.
   "Use upload as-is" skips generation (for images that already are clean character sheets).
   If ComfyUI image generation is unavailable, generation fails with a message — a placeholder is never used as a reference.
3. **Locked.** A finished reference is locked automatically. Locked characters cannot be regenerated, re-uploaded or have
   their master prompt changed until you unlock them.
4. **Used everywhere H3 renders.** Any H3 clip whose prompt mentions a character's name or alias (whole-word, case-insensitive)
   gets that character's reference image, plus a prompt prefix: "Reference image 1 shows Asha — <master prompt> … keep them identical".
   - Max **2 characters per clip** (H3 reference slots), chosen by first mention. More names → first two, logged.
   - 1 character + a start image/keyframe → slot 2 holds the keyframe. 2 characters → keyframe not used as a reference for that clip.
   - Uses the H3 Ref2VA model (`minimax-h3-reference-cast-to-video.json`). Clips without cast names are unchanged.
5. **Story to video · H3** now takes the cast instead of one photo: the story writer is told to use the exact names (and at most
   two per scene); the references are mirrored into the character library so Qwen keyframes use them too (cached per reference).

## Files
- Backend: `cast/` (CastCharacter, CharacterCastStore, CharacterCastService, CharacterCastController), `ComfyUIVideoProvider`
  (setter-injected cast, cast template, ref-slot compaction), `comfyui-workflows/minimax-h3-reference-cast-to-video.json`,
  test `CharacterCastStoreTest`.
- Storage: `{storage root}/character-cast/cast.json` + images. No database migration.
- Frontend: `shared/h3-cast-panel/*`, `video-generation` page (story mode + H3 single-clip mode).
- Config: `STUDIO_H3_CAST_ENABLED` (default true).

## API
`GET/POST /api/character-cast`, `PUT /{id}`, `POST /{id}/image|reference|use-upload|lock?locked=|library-link?characterId=`,
`DELETE /{id}`, `GET /{id}/image/upload|reference`.

## Verification
Angular production build passes. Cast classes and ComfyUIVideoProvider compile against Jackson/SLF4J/Spring jars
(the only errors were stub gaps for Spring 6 APIs). Name/alias resolution, 2-per-clip cap, disabled/not-ready filtering
and persistence checked with a harness (same cases as the JUnit test). Not run here: Maven build, a real ComfyUI H3 render.
