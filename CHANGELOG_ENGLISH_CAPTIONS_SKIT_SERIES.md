# English visuals + English subtitles, modern captions, comedy writers' room, Adventure Series

## Concept Explainer
- Language contract: ONLY the narration follows the chosen language. Headings, labels, cards, code,
  title/outro cards and subtitles are always English (prompts enforce it for lessons and shorts).
- The model returns an English "captions" line per narration sentence; anything missing is translated
  in one extra call, sentence for sentence, so subtitle timing stays exact.
- Captions restyled: Poppins ExtraBold, 4-word chunks, three states per word - already spoken = white,
  being spoken = green + 8 % larger, still to come = 37 % opacity - soft shadow, chunk fade-in.

## Funny Skit - writers' room (3 LLM passes instead of 1)
1. Pitch 5 genuinely different comic angles (engine, punchline, button) and score them.
2. Write the chosen angle: hook in 2 s, one engine escalated (rule of three), punchline that
   recontextualises with the funniest word last, silent reaction "button", <= 12 words per part,
   specific everyday details, physical comedy in every visual prompt, punch up not down.
3. Punch-up pass rewrites weak/generic/too-long lines. Each later pass falls back to the previous one.
- UI shows the skit title, comic engine, the ending button and live progress stages.

## Adventure Series (new mode in the same studio)
- Like the attached clip: cinematic 15-second episodes (3 x 5 s), golden-hour fantasy look
  ("Cinematic adventure" style), slow orbit / push-in / crane reveals, music + optional short voice-over.
- Series bible (hero, world, visual signature, 8-episode arc) written once and saved to disk
  (adventure-series/<id>.json) - series survive restarts and can be continued any day.
- Every episode: continues from the previous cliffhanger, gets the story-so-far digest, its first
  storyboard frame starts from the previous episode's LAST frame, same locked hero reference.
- Optional per-episode direction from the creator. Episode list with summary, cliffhanger, Watch.
- API: POST /api/adventure-series, GET /api/adventure-series[/id], POST /{id}/episodes, GET /{id}/episodes/{n}/video

## Verified here
- Caption rendering (libass) with the new style; preview short rebuilt. Concept package compiles;
  skit package shows no errors beyond the sandbox's missing Spring/JPA/Lombok jars; Angular build passes.
## Not verified here
- Real Ollama comedy quality, H3 episode rendering, full Maven build.
