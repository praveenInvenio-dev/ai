# v13 - Studio tools completed + story pipeline finishing

## New studio tabs (Motion & effects studio)
| Tab | What | Where it runs |
|---|---|---|
| 🧹 Remove background | Image -> transparent PNG. Video (<= 15 s) -> transparent WebM, or MP4 on a colour / blurred background. Quality: fast (people), general, best (BiRefNet). | `video-worker` (rembg, CPU). Models download on first use into the `video-worker-models` volume. |
| 🗣️ Dub / re-voice | Lines (+ optional translation by the story LLM) -> **Re-voice**: new IndicF5 voice over the same picture, original sound ducked (lips unchanged, speech sped up to max 1.2x or last frame held). **Re-animate**: first frame + new lines through the shared H3 scene renderer -> lips match (H3 or Indic TTS speech). | backend + tts-indic / ComfyUI |
| 📈 Analyze | Hook (first shot change), cuts/min, length, orientation, resolution, loudness, silent start -> 0-100 score + LLM coach report with concrete fixes. | backend (FFmpeg + Ollama) |

## Improvements
- **Motion control up to 20 s** (`MOTION_CONTROL_MAX_TOTAL_SECONDS`): long driving videos render as chained
  ~4.8 s Wan Animate windows; each window continues from the previous one (`continue_motion`,
  `studio-motion-control-continue.json`), then joined with the driving video's audio.
- **Story -> studio handoff**: Story Approval "✦ Finished video -> studio"; Story Video Production final video
  has Upscale / Reframe / Dub / Analyze buttons. Studio accepts `episodeId` / `sequenceId` as source.
- **H3 story pipeline now also makes** thumbnail (JPG), subtitles (SRT, per spoken line, crossfade-aware) and
  records universe episode memory - like the old classic pipeline.
- **Translated story copies own their image files** (deleting the original no longer breaks the copy).
- Results list shows images (checkerboard for transparency), WebM, and text reports; results chain into
  upscale / reframe / dub / analyze.
- Removed the two cosmetic sidebar pages (Wan 2.2 workflow, MiniMax H3 workflow); old URLs redirect to
  Video Generation.

## Verification
- Angular production build passes.
- video-worker background removal ran for real on an H3 frame (clean cut-out) and a 1 s clip (MP4 + audio).
- Dub mix (tempo, held last frame, ducking, loudnorm), analyze probes, motion window concat: FFmpeg commands run OK.
- NOT compiled (no Maven here): `studio/*`, `StoryTranslationService`, `VideoSequenceService` changes.
- NOT run on GPU: motion-control windows/continuation, AI upscale, re-animate.

## Still not built
Publishing to YouTube/Instagram (needs your platform developer apps / OAuth keys), face video and character
replace mode for motion control, image outpaint, video restyle, per-scene camera presets in Story Approval,
persistent studio / skit / video-generation jobs (lost on backend restart).
