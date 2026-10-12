# Every voice came out as the same female voice

Both uploaded lessons had the identical voice (median pitch 188 Hz): the local Piper default en_US-amy-medium,
i.e. the last-resort fallback. Two routing bugs sent every choice there:

1. Edge voices (Prabhat / Neerja, Edge-<language>) were sent to the configured TTS provider. With
   TTS_PROVIDER=chatterbox (the .env.example default) Chatterbox cannot speak an Edge voice -> every line failed
   -> female Piper fallback. Edge voices now always go to the local tts service, which is where Edge lives.
2. When Chatterbox is not reachable, Arjun / Meera fell back to Edge through that same broken route, and the US
   voices failed. Now: Arjun/Meera -> Edge en-IN via the tts service; Ryan/Amy -> local Piper Ryan/Amy.
3. Every fallback keeps the GENDER of the chosen voice (male -> en_US-ryan-high, female -> en_US-amy-medium)
   and leaves a note on the lesson saying what failed and what spoke instead.

## Hindi (and every Indian-language) narration was always female
The native-voice chain took the first voice of each list, and every list is [female, male] (Swara/Madhur,
Kavya/Amit, swara/madhur). Picking Arjun / Ryan / Prabhat with Hindi therefore gave a female Hindi voice.
Now the native voice keeps the gender of the voice you picked: male -> Madhur (Edge) / Amit (Indic-Speak) /
madhur (IndicF5), female -> Swara / Kavya / swara. Same for Kannada, Tamil, Telugu, Malayalam, Marathi, ...

## More narrator voices in the Concept Explainer
- New "Local offline voices (Piper)" group: every catalogued Piper voice for the narration language
  (English: Ryan, Lessac, Amy, Kristin, HFC male/female, Alba, Northern English male, Jenny; Hindi: Rohan,
  Pratham, Priyamvada; Telugu, Malayalam ...). No GPU, no internet after the first download.
- Edge group for English now lists US/GB voices (Guy, Jenny, Ryan GB, Sonia); added the missing male Edge voices
  (Valluvar, Mohan, Midhun, Manohar) and Bengali / Gujarati to the tts service catalog.
- Piper voice ids are routed straight to the local tts service.
- Hint under the dropdown explains where the other groups come from (language, Indic-Speak service, Voice Lab).

## "IndicF5 Swara sounds exactly like Edge Swara"
- By design of the bundled clips: the "xx-in-name" IndicF5 references (swara, madhur, sapna ...) were synthesised FROM
  the Edge voice of the same name (tts-indic/prompts/README.md: evaluation clips, do not ship), so IndicF5 clones that
  voice. The dropdown now says so ("copy of the Edge voice") and lists the real human recordings first
  (hi-agri-m, hi-health-m-1).
- A failed Indic voice that falls back to another one now leaves a note on the lesson (it only went to the log before).
- "Test this voice" button: speaks a sample in the narration language with exactly the selected engine (no fallback)
  and shows the engine's error (e.g. IndicF5 needs HF_TOKEN, service not running) before you make a lesson.
