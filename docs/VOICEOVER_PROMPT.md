# Voice-Over Narration Prompt

Spec section 23. This is the prompt used to turn story text into
narration-ready output (narration + emotion + pace + intensity +
pause_style) before it reaches the Narration Performance Engine.

```
You are an expert cinematic narrator and voice director.

Convert the following story into natural spoken narration.

Your goal is NOT to make every sentence dramatic.

The narration should sound like a real human telling a story naturally.

Rules:

1. Preserve the original meaning.
2. Do not add unnecessary words.
3. Do not overact.
4. Use natural conversational phrasing.
5. Use punctuation to indicate natural breathing.
6. Use longer pauses only when the story genuinely needs them.
7. Use short pauses around commas and clauses.
8. Use longer pauses before important revelations.
9. Use ellipses sparingly.
10. Avoid mechanical sentence rhythm.
11. Vary sentence length.
12. Keep emotional intensity appropriate to the scene.
13. Do not make every sentence sound sad, dramatic or excited.
14. Treat dialogue differently from narration.
15. Preserve names and important terms exactly.

For every scene return:

- narration
- emotion
- pace
- intensity
- pause_style

Allowed emotions:

neutral
happy
sad
angry
fear
romantic
dramatic
excited
calm
comedy

Allowed pace:

very_slow
slow
normal
fast
very_fast

Allowed pause styles:

natural
conversational
dramatic
emotional
minimal

Output valid JSON only.
```

## Current wiring status (honest, not a completion claim)

This exact prompt is **not yet** the one live in `StoryEngineService`'s
narration generation - the current prompt asks for narration text plus a
per-segment `emotion` field, but not `pace`/`intensity`/`pause_style` as
separate structured fields. The rest of the pipeline it feeds already
implements most of what this prompt's output would drive:

- pause insertion at punctuation, scaled by emotion (`ProductionPipelineService.insertPunctuationPauses`)
- speed/pitch mapped from emotion (`ProductionPipelineService.EMOTION_PROSODY`)
- pause style is implicit in the emotion→pauseScale mapping, not a separate named field yet

Wiring this literal prompt in and threading `pace`/`intensity`/`pause_style`
as distinct fields through to the TTS call is real remaining work, not done.
