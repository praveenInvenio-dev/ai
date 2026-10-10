# v24.12 - Indic-Speak (bodhan-ai/indic-speak) as a TTS engine for every feature, plus a bake-off tool

## What it is (from the model card)
22 Indian languages + English, 95 voices, built for teaching: STEM content (equations, units, formulae), code-mixed
Indian-language + English sentences in one voice, unhurried explanatory pace. Llama-3.2-3B speech LM + SNAC + Vocos, 24 kHz,
3.8B parameters (~7.6 GB VRAM). Gated: accept the licence on Hugging Face with the HF_TOKEN account. No voice cloning, no laughs/breaths,
style control is "preview quality". Licence: free to self-host and use commercially; credit required; hosting it as a service for
others needs Bodhan AI's sign-off (see NOTICE-INDIC-SPEAK.md and tts-indicspeak/README.md).

## What was added
- `tts-indicspeak/` - own container (it needs transformers>=5; IndicF5 pins <4.50). Lazy load, frees the GPU after 5 idle minutes so ComfyUI
  can use the card, `/health`, `/api/voices`, `/api/tts`, `/api/unload`; clear errors for: gated repo, GPU full (HTTP 507), unknown voice
  (an unseen speaker name would silently give a worse averaged voice, so it is rejected), over-long text.
  `docker compose -f docker-compose.yml -f docker-compose.gpu.yml --profile indicspeak up -d --build tts-indicspeak`
- `tts` proxy: `speak:<lang>-<Name>` voices (e.g. `speak:kn-Deepika`) in the shared voice list and routed to the sidecar; long text is split at
  sentence boundaries (~600 chars per call) and joined; pitch works; the voices simply disappear from the list when the service is off.
- Backend: `speak:` voices work anywhere a voice can be chosen (story narration per speaker, Voice Lab preview, Concept Explainer, classic video,
  H3 flows, Funny Skits, Studio dub). New setting `INDIC_ENGINE=indicf5|indicspeak` (default indicf5): which engine "Indic TTS" means for
  automatic picks and the "Indic TTS voice" checkboxes; with indicspeak the automatic story-language voice is Indic-Speak first, Edge second.
  English is never switched automatically.
- Frontend: Voice Lab group "Indic-Speak" (+ native sample sentences), Concept Explainer voice group per language with the credit line.
- Credit line "Built with Indic-Speak from Bodhan AI / AI4Bharat" in Voice Lab, the Concept Explainer and NOTICE-INDIC-SPEAK.md.

## See how good it is (run on your machine)
`python3 scripts/tts-bakeoff.py` speaks the same sentences (plain narration, a STEM fact, a chemical formula, Indian-language + English mixed,
numbers/time/money; edit `scripts/bakeoff-texts.json`) with Edge, IndicF5 and Indic-Speak - whichever are running - for Kannada, Hindi, Tamil and
Telugu, and writes `bakeoff/<time>/report.html`: latency (cold start reported separately), real-time factor, loudness, silence share,
a speech-recognition round trip (CER/WER via faster-whisper in video-worker; relative between engines, not absolute) and a BLIND listening test
(shuffled, unlabeled voices, naturalness + pronunciation scores, "Show scores" tells you which engine you actually preferred).
Options: `--langs kn,hi --kinds mixed,formula --both-genders --no-asr --engines edge,indicspeak`.

## Tests (all run here)
Sidecar 9/9 (fake model), bake-off 14/14 (incl. an end-to-end run against fake services that must rank a perfect engine first), engine-aware voice
selection 6/6, plus the existing suites unchanged: teaching 24, speech cleanup 10, highlights 9, exporter 8, transcript search 8, editor commands 41.
Also: renderer run end to end - with INDIC_ENGINE=indicspeak a Kannada skit gets speak:kn-Deepika (narrator) and speak:kn-Adarsh (character).

## NOT verified (be sceptical until you have run it)
- The real model: it is gated, 7.6 GB and needs a GPU; none of that is available in the build sandbox. Audio quality, pronunciation, speed on your
  card, VRAM next to ComfyUI and whether `inference.TTS(local_dir)` matches the model card in your downloaded copy are all unmeasured.
  The sidecar follows the card's documented API (`TTS(dir)(text, speaker=..., style=...)` -> float32 @ 24 kHz) and the repo id is configurable
  (`INDICSPEAK_REPO`; the card shows both `bodhan-ai/indic-speak` and `...-preview-v2`).
- The Docker image build (pip resolution of transformers>=5 with torch 2.7.1 cu128 could not be checked offline; the build prints the versions).
- Maven compile of the Java changes (syntax-checked, and the touched logic exercised through test harnesses).
