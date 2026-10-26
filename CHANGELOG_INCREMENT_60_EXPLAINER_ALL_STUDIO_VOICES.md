# Increment 60 — Explainer Studio uses every voice the studio already has

Includes Increments 55-59.

Explainer Studio now discovers voices from the studio's running services (refreshed every 60 s) instead of only its own
list — the same voices Concept Explainer offers:

| Service | Profile | What Explainer gets |
|---|---|---|
| `tts` (always on) | — | Piper Hindi (Pratham, Priyamvada, Rohan), Telugu (Tanuja), Malayalam (Meera); **Edge neural** Hindi, Tamil, Telugu, Kannada, Malayalam, Marathi, Bengali, Gujarati, Indian English (needs internet); IndicF5 + Indic-Speak voices proxied (incl. extra IndicF5 prompts like hi-agri-m, hi-health-m-1) |
| `tts-chatterbox` | `chatterbox` | every reference voice in English **and Hindi** (Chatterbox Multilingual V3) |
| backend Voice Lab | — | your cloned voices (`profile:`), in the language they were made for |
| `tts-indic`, `tts-indicspeak` | `indic`, `indicspeak` | direct, as before (a voice reachable both ways is listed once) |

Voice counts when all services run: Hindi 12+ (plus every Chatterbox voice), Tamil/Kannada/Marathi/Bengali/Gujarati 6,
Telugu/Malayalam 7, Punjabi/Odia/Assamese 2 (Indic-Speak only), Indian English 4 (Edge + Indic-Speak).

- Only voices whose service answered are listed; the dropdown is grouped: IndicF5 → Edge neural → Indic-Speak → Kokoro →
  Chatterbox → My voices → Piper. Default per language follows that order (most natural first).
- Pills for "Studio voices" and "Chatterbox" next to Kokoro / IndicF5 / Indic-Speak.
- Synthesis goes to the owning service (`tts` /api/tts, `tts-chatterbox` /api/tts with `language`, backend /api/tts/preview);
  503 while a model loads is waited for.
- Not used: Rumik (legacy profile), CosyVoice (no Indian languages); IndicF5 "en-in" prompts are hidden for English
  (IndicF5 cannot read Latin script).

Verified: 20/20 API tests (1 new), Angular build, catalogue + Hindi synthesis through all three routes against stand-in
services (request bodies checked: Edge voice id, Chatterbox language "hi", Voice Lab profile id).
