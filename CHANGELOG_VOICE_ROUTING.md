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
