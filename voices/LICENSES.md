# Voice Licenses

Spec rule (section 33): every bundled Piper voice gets a license record here;
never bundle a voice whose license is unknown. This file covers the one
voice this project actually downloads by default
(`docker-compose.yml` / `tts/Dockerfile`, `TTS_VOICE=en_US-amy-medium`).

## en_US-amy-medium

| Field | Value |
|---|---|
| Source | https://huggingface.co/rhasspy/piper-voices/tree/main/en/en_US/amy/medium |
| Repo-level license badge | MIT (`rhasspy/piper-voices`, verified via HF repo page) |
| Voice's own MODEL_CARD | Says `License: See URL` — defers to the training dataset, not the repo badge |
| Training dataset | Finetuned from the "lessac" voice, sourced via https://github.com/MycroftAI/mimic3-voices |
| Piper project's own stance | Per the Piper maintainer (github.com/rhasspy/piper discussion #271): Piper imposes no license of its own on voice checkpoints; "it is the responsibility of the end user to make the ultimate judgement" on each voice |

**Honest status, not a green light:** the *repository* `rhasspy/piper-voices` carries an MIT badge, but that badge is a catalog-level label, not a per-voice guarantee — amy's own MODEL_CARD explicitly points to the source dataset's license instead of claiming MIT itself, and the Piper maintainers say the same thing generally: check per voice, don't assume the repo badge covers it. This project ships amy as the working default because it downloads and runs cleanly for development and non-commercial use; **verify the lessac/mimic3-voices dataset terms yourself before any commercial deployment.**

## Adding another voice

Before adding a new default voice to `docker-compose.yml`/`tts/Dockerfile`:
1. Open that voice's own `MODEL_CARD` on `rhasspy/piper-voices` (not just the repo's overall badge).
2. Add a row to this table with the same fields as above.
3. If the MODEL_CARD says "See URL" or similar, follow it and record what you actually find — don't leave it unresolved.
4. If you can't determine the license, don't bundle it as a default — offer it as an optional user-selected download instead (spec section 33's own instruction).
