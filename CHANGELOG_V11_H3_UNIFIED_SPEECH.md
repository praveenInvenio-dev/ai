# v11 - One H3 speech flow for Video Generation, Story Video Production and Storyboard

## Problem
Narrator lines were put into the H3 *video* prompt (`Narrator (off-screen): <d>...</d>`).
H3 generates picture and sound jointly, so any spoken text in the video prompt gets a
moving mouth - the on-screen character "said" the narration. Long lines were also cut
at the 10 s clip limit, and the three flows produced audio three different ways.

## Fix: shared `H3SceneRenderer` (backend/src/main/java/com/aistorystudio/h3/)
| Line type | Rendering |
|---|---|
| Narrator / voice-over | H3 **audio-only** pass gets the words (+ ambience/SFX/music). H3 video pass gets **no words** ("nobody talks, lips closed"). Muxed. |
| Character dialogue | One H3 I2V pass with the words -> voice + lip sync together. Only the named speaker moves lips. |
| No speech | One silent H3 I2V pass. |

- Lines split at sentence -> clause -> word boundaries into shots <= `max-shot-seconds` (8 s);
  shots chained from the previous shot's last frame. Speech is never cut by the clip limit.
- Same narrator seed for every scene of a story (more consistent narrator voice).
- Voice-over shot regenerated once (+1.5 s) if speech is still running at the end.
- Final audio: high-pass 60 Hz, loudnorm -16 LUFS / -1.5 dBTP, 48 kHz stereo.
- Music intensity capped at 0.35 in every prompt (also clamps "intensity 0.7" typed in the UI).

## Indian-language pronunciation (`IndicSpeech`)
- Language resolved from the **text script** when it disagrees with the story setting
  (story "English" + Kannada text -> `[Kannada]`). Accepts `kn`, `kn-IN`, `ಕನ್ನಡ`, Hinglish (romanized).
- Cleanup: NFC, removes ZWSP/BOM/soft hyphen, keeps ZWJ/ZWNJ, strips emoji/symbols,
  moves `[giggles]`-style cues into delivery, normalises ellipses/dashes, adds danda/full stop.
- Duration estimated by aksharas (syllables), not words.
- Prompt adds native-speaker / regional-accent / vowel-length / retroflex-dental / gemination
  instructions, number-reading and English-loanword instructions when needed.

## Wiring
- **Video Generation**: speech fields are sent separately (`sceneId`, `narrationText`, `dialogueText`,
  `audioDirection`, `useSceneVoices`); the prompt stays visual-only. Unedited scene = original
  narrator/dialogue order from the scene's voice segments.
- **Story Video Production**: MINIMAX_H3 scenes use the renderer and keep H3 speech
  (Wan engines still mux the saved TTS track - Wan has no audio).
- **Storyboard / Story Approval**: "Produce video" -> `POST /api/video-sequences/produce-episode/{id}`
  -> opens `/video-sequence?id=...`. The merged video is attached to the story as the active VIDEO asset.
- Old `assembleStoryboard` endpoint still exists (classic image + TTS) but the UI no longer calls it.

## Other fixes
- `minimax-h3-image-to-video.json` / `minimax-h3-text-to-video.json` sent literal
  `{{H3_TURBO_MODE}}` / `{{H3_TURBO_STRENGTH}}` / `{{H3_TURBO_STEPS}}` to ComfyUI. Now `false` / `1.0` / `8`.
- `createFromEpisode` now checks video-engine availability up front.

## Config (`application.yml` -> `studio.h3.scene.*`, env overrides)
```
H3_SCENE_MAX_SHOT_SECONDS=8          # falls back to LOCAL_AI_ANIMATION_MINIMAX_H3_SHOT_SECONDS
H3_SCENE_SPEECH_TAIL_SECONDS=0.8
H3_SCENE_MUSIC_INTENSITY_CAP=0.35
H3_SCENE_NARRATOR_VOICE="warm, clear, mature storyteller voice with gentle expression"
H3_SCENE_LOUDNESS_LUFS=-16
H3_SCENE_VO_TAIL_RETRY=true
```

## Cost
Voice-over shots = 2 H3 calls (audio-only pass is cheap: 32x32 latent). Long scenes = several shots.

## Verification
- Angular production build: passes.
- `IndicSpeech`, `H3ScenePrompts`, `H3SceneRenderer`: compiled and run end-to-end with a fake
  H3 provider (ffmpeg test clips). Narrator words only reach audio-only passes; video passes for
  narration contain no words; dialogue shot contains only the character's line. Output 48 kHz, -15.9 LUFS.
- NOT compiled here (no Maven access): `VideoSequenceService`, `VideoGenerationService`, both
  controllers. Run `mvn -q -DskipTests compile` before deploy.
- NOT run on a real GPU / H3 model.

---

# v11.1 - "Indic TTS voice instead of H3 speech" option + Funny Skits on the shared flow

## Speech engine switch (either / or)
Every H3 flow now has a checkbox **"Indic TTS voice (IndicF5) instead of H3 speech"**:
Video Generation, Story Video Production (Load Story + new sequence), Story Approval,
Storyboard (Build a story / Add story images) and Funny Skits. API: `speechEngine=H3|INDIC_TTS`.

| | H3 (default) | INDIC_TTS |
|---|---|---|
| Who speaks | H3 | IndicF5 (`tts-indic`), humanized (de-ess, -16 LUFS) |
| Narrator shot | H3 audio-only pass (words) + H3 video pass (no words) | TTS voice + H3 video pass (no words); that pass's own ambience/music is the bed, side-chain ducked |
| Dialogue shot | 1 H3 pass, words in prompt -> voice + lip sync | TTS clip passed to H3 as **reference audio** (+ character sheet when available) -> lips follow the TTS; separate speech-free H3 sound bed, ducked |
| Long lines | split into <= 8 s shots | TTS measured; visual split into equal chained parts |

Voices: narrator = language's female IndicF5 voice (e.g. `indic:kn-in-sapna`), characters alternate
male/female (`indic:kn-in-gagan`, ...). A voice segment that already names an `indic:*` voice
(Story Approval voice picker) wins. Override narrator: `H3_SCENE_INDIC_NARRATOR_VOICE`.
IndicF5 voices exist for Hindi, Kannada, Tamil, Telugu, Malayalam, Marathi, Bengali, Gujarati,
Indian English. Other languages -> clear error asking to use H3 speech.
No silent fallback: if `tts-indic` is down the job fails with "add 'indic' to COMPOSE_PROFILES".

## Funny Skits
Render now uses the shared renderer per approved storyboard frame (3 parts), lines are on-screen
dialogue. Same checkbox. Removed the old per-part TTS + H3 chunk code.

## Fix
H3 reference graphs (`minimax-h3-reference-to-video`, `-reference-character-to-video`) contain
optional LoadImage/LoadAudio nodes. An empty filename made ComfyUI reject the prompt (e.g. start
image + voice audio without a character sheet). `ComfyUIVideoProvider` now drops empty media
nodes and every input pointing at them.

## Setup for INDIC_TTS
```
COMPOSE_PROFILES=chatterbox,indic      # .env
HF_TOKEN=...                           # IndicF5 is gated on Hugging Face
LOCAL_AI_ANIMATION_MINIMAX_H3_REF2VA_MODEL=minimax_h3_ref2va_pruned_w6a8.safetensors   # lip sync to TTS
H3_SCENE_TTS_LIPSYNC_REFERENCE=true    # false = dialogue lips animated from text only
```

## Verification
- Angular production build passes.
- Renderer compiled + run end-to-end in both modes with fake H3/TTS (narrator words never reach a
  video pass; INDIC_TTS dialogue shot gets refAudio + charRef; output 48 kHz ~-16 LUFS).
- Template pruning simulated on `minimax-h3-reference-to-video.json`.
- NOT compiled (no Maven here): `FunnySkitService/Controller`, `VideoSequenceService`,
  `VideoGenerationService`, controllers, `ProviderGateway`, `ComfyUIVideoProvider`. Run `mvn -q compile`.
- Lip sync to reference audio depends on H3 ref2va behaviour; not verified on GPU.
