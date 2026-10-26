# Increment 57 — Explainer Studio in Indian languages

Includes Increments 55 (Explainer Studio) and 56 (H3 character references).

## What's new
- **Language picker** in Explainer Studio: English, Hindi, Bengali, Marathi, Tamil, Telugu, Kannada, Malayalam,
  Gujarati, Punjabi, Odia, Assamese.
- **51 voices**, filtered by language, available ones first:
  - **IndicF5** (`tts-indic`, profile `indic`) — 16 voices, male + female for hi, bn, mr, ta, te, kn, ml, gu. Most natural; default when running.
  - **Indic-Speak** (`tts-indicspeak`, profile `indicspeak`) — male + female for all 11 Indian languages, plus 4 Indian-accent English voices.
  - Kokoro (6) and Piper (3) English voices as before.
- Status pills show Kokoro / IndicF5 / Indic-Speak on/off (health probe every 30 s). Voices of a stopped service are disabled.
  A voice can only speak its own language; a mismatched voice falls back to that language's default.

## How the language is handled
- **Script writer:** outline + every scene are written in the chosen language and script (titles, hook, CTA, headings too).
  English technical terms are transliterated into the native script so the voice can read them; on-screen labels and
  tech notes may stay English/symbols. Lint checks that ≥85 % of narration letters are in the right script and triggers
  the rewrite pass otherwise; English-only style rules (contractions, stock phrases) are skipped.
- **Speech:** the English number/unit/operator expander is skipped (it would insert English words into, say, Tamil).
  Indic services answering 503 while loading their model are waited for (up to ~5 min) instead of failing the job.
- **Captions:** word timing is Unicode-aware (letters + vowel signs), `।` / `॥` count as sentence ends.
- **Rendering:** `fonts-noto-core` in the image + Noto Indic fonts in every theme stack. Hashtags keep Indic letters.
- Re-render keeps the original language and voice.

## Run
```bash
COMPOSE_PROFILES=kokoro,indic,indicspeak docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --build explainer-studio frontend
```
Both Indic services need `HF_TOKEN` (gated Hugging Face repos) — same as the rest of AI Story Studio.

## Verified
19/19 API tests (4 new). Angular production build. Hindi stills rendered in Chromium (Devanagari shaping OK).
Hindi TTS path through the Indic-Speak HTTP contract (mock service). Not run here: real IndicF5 / Indic-Speak models,
a real Ollama script in an Indian language.

## Known limits
- Phase pills (Visualise / Animate / Explain) stay English.
- Script quality depends on the Ollama model: llama3.1 8B writes Hindi well, smaller Indian languages less so —
  a larger/multilingual model (e.g. qwen2.5 14B, gemma2) gives noticeably better Tamil/Telugu/Kannada/Malayalam.
- Urdu and Hinglish (Roman script) are not offered: the Indian voices here read native scripts.
