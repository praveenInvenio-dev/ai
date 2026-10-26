# Increment 61 — H3 character reference: the uploaded character must actually appear

Includes Increments 55-60.

## What went wrong in the reported clip
Reference: curly brown hair, black Om-print shirt, black/gold bead bracelet, small gold earrings.
Generated: straight black hair, cream kurta, no accessories → the reference image never reached H3. Two causes:
1. **Ref2VA model file incomplete** (the earlier "extends past the end of the file" error). Ref2VA is the ONLY H3 model
   that accepts a character image. Increment 59's fallback then rendered the clip without references → a different child.
2. **Name matching only**: a scene written as "the toddler laughs" (no name) got no reference at all.

## Fixes
- **No silent identity loss**: the "render without references" fallback is now OFF by default. If Ref2VA is broken, the
  clip fails with "run ./verify-models.sh --fix" instead of producing someone else. (`STUDIO_H3_CAST_FALLBACK=true` to opt in.)
- **Hero pin** ("in every clip"): pinned characters are attached to every H3 clip, filling the free reference slots after
  the characters named in the scene. The first character you add is pinned automatically. The prompt then says
  "Reference image 1 shows <name>, the main character of this clip (the child/boy/girl the prompt mentions)".
- **Auto master prompt**: leave the master prompt empty and the vision model writes it from the upload (hair colour and
  texture, skin tone, eyes, age, outfit colours/print, accessories). "Auto-describe" re-runs it on an unlocked character.

## Check after updating
```bash
./verify-models.sh --fix && docker compose restart backend
docker compose logs -f backend | grep "H3 cast"
# expected per clip:  H3 cast: clip uses reference(s) for [Asha]
```

Verified: cast + provider compile against real Jackson/Spring jars (stub gaps only), resolver harness incl. pinned cases
(new JUnit test), Angular production build.
