# ChatterBox voice references

Each voice is one file: `<voice-name>.wav`, a clean ~10-second reference clip
of the voice you want narration cloned from (a single speaker, minimal
background noise). No transcript file needed - unlike IndicF5's prompts,
ChatterBox's zero-shot cloning only needs the audio itself.

No voices ship bundled here. Record or source your own before enabling this
engine - see `TTS_PROVIDER=chatterbox` in `.env`.

## Consent

Same rule as `tts-indic/prompts/README.md`: only clone a voice you have
permission to use. The intended path is your own recorded reference, or a
voice actor's with their consent, not scraping audio of someone else without
asking.

## Adding a voice

1. Drop `mybunny.wav` into this folder (`tts-chatterbox/voices/` on the host,
   mounted into the container at `/voices`).
2. Restart the `tts-chatterbox` service:
   ```
   docker compose --profile chatterbox up -d --force-recreate tts-chatterbox
   ```
3. It appears as voice id `mybunny` in `/api/voices` and the Voice lab.

## Default voice

`CHATTERBOX_DEFAULT_VOICE` (default: `narrator`) is used when a request
doesn't name a voice. Add a `narrator.wav` reference here, or set that env
var to whichever voice name you do have.
