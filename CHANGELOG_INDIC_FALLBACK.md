# Indic TTS: no more failed renders when an Indic voice container is down

Error seen in Funny Skit: `speak:kn-Deepika ... Temporary failure in name resolution` - INDIC_ENGINE=indicspeak
picks Indic-Speak voices, but the tts-indicspeak container was not running.

- When the engine behind an Indic voice is unreachable (not running, 502/503, GPU out of memory), the same
  language + gender is tried on the OTHER local engine (Indic-Speak <-> IndicF5), then the Edge voice.
  e.g. speak:kn-Deepika -> indic:kn-in-sapna -> edge:kn-IN-SapnaNeural. Logged as a warning. Other errors still surface.
- Funny Skit: ticking "Indic TTS voice" now shows which Indic services are actually running before you render.
