# Increment 62 — Story productions finally send the character reference to H3

Includes Increments 55-61.

## Root cause (found in code, confirmed by two identical off-model clips)
Story → H3 production (`VideoSequenceService` → `H3SceneRenderer` → `ComfyUIVideoProvider`) passed only the scene
keyframe to H3. The story's library characters and their locked reference images were used for the Qwen keyframe but
were **never handed to the H3 clip**. The keyframes were wide shots with the child tiny in frame, so when H3 moved in
it invented a new child (black hair, cream kurta) — the same "default" child every time.

## Fix
- New `H3ReferenceScope`: while a sequence scene renders, its characters with reference images (max 2, hero first, name +
  canonical description) are visible to the video provider. Every H3 clip of that scene now goes through Ref2VA with those
  references (merged with the H3 character panel's cast, de-duplicated by name/image).
- Keyframes with a referenced hero are now **medium shots**: the hero in the foreground, at least a third of the frame,
  facing camera, "exactly like the reference image" (face, hair colour/texture, skin tone, age, outfit, print, accessories).
- Unchanged from 61: if Ref2VA is broken the clip fails with a clear message instead of rendering someone else.

## Check
```bash
./verify-models.sh            # Ref2VA must be OK
docker compose logs -f backend | grep "H3 cast"
# every clip:  H3 cast: clip uses reference(s) for [<character name>]
```
Verified: provider + scope compile against real Jackson/Spring jars (stub gaps only); VideoSequenceService change has no
syntax errors (full Maven build not possible in the sandbox). No real H3 render was possible here.
