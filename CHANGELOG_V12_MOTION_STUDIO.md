# v12 - Motion & Effects Studio (Higgsfield-style, local) + Same story in another language

New page **Motion & effects studio** (`/motion-studio`). Jobs share the GPU queue with H3/Wan
(`ComfyUIVideoProvider.runStudioWorkflow`, same slot) and can be chained: motion -> upscale -> reframe.

| Tool | How | Notes |
|---|---|---|
| Motion control | Wan 2.2 Animate **move** mode: character image + driving video -> DWPose skeleton -> `WanAnimateToVideo` (+ LightX2V 4-step LoRA) | 480x832 @16 fps, one 77-frame window (~4.8 s). Opt-in download `DOWNLOAD_MOTION_CONTROL_MODELS=true` (~18 GB) + `comfyui_controlnet_aux`. Face video not used yet (expressions weaker than official template). |
| Upscale | AI: RealESRGAN per frame in ComfyUI (core nodes) -> 1080x1920 / 1920x1080. Fast: FFmpeg lanczos + unsharp | Optional "smooth to 24 fps" (FFmpeg motion interpolation) for 16 fps clips. AI limited to 30 s/clip (RAM). |
| Reframe | FFmpeg: 9:16, 16:9, 1:1, 4:5 with blurred fill / centre crop / bars | Any length, CPU. |
| Camera presets | 20 presets (Basic, Cinematic, Viral, Kids) in `studio/camera-presets.json` | Dropdown in Video Generation; appended to the motion prompt for H3 and Wan. |

**Same story in another language** (Story Approval -> "Same story in another language… / Create copy"):
copies the story (scenes, approved images, standalone characters, story bible) into a new episode and
translates narration + every voice-segment line with the story LLM (native script, names transliterated,
similar line length, numbers as words). Images are reused - no GPU. Then Produce video (H3 or Indic TTS).
`POST /api/episodes/{id}/translate?language=Hindi`.
Note: the copy points at the same image files; deleting the original story's files affects the copy.

## API
```
GET  /api/studio/status | /camera-presets | /jobs | /jobs/{id} | /jobs/{id}/video
POST /api/studio/motion-control  (image, video, prompt?, orientation, seconds)
POST /api/studio/upscale         (video | sourceJobId, mode=AI|FAST, smooth24)
POST /api/studio/reframe         (video | sourceJobId, aspect, mode=BLUR_FILL|CROP|BARS)
POST /api/episodes/{id}/translate?language=...
```

## Config
`.env`: `DOWNLOAD_MOTION_CONTROL_MODELS=true` then restart ComfyUI. Tunables (`application.yml`
`studio.motion-studio.*`): `MOTION_CONTROL_MODEL/LORA/TEXT_ENCODER/VAE/CLIP_VISION/WIDTH/HEIGHT/STEPS/CFG/FPS/MAX_SECONDS`,
`MOTION_STUDIO_UPSCALE_MODEL`, `MOTION_STUDIO_AI_UPSCALE_MAX_SECONDS`, `MOTION_STUDIO_RETENTION_HOURS`.

## Verification
- Angular production build passes.
- All studio FFmpeg commands executed on a real H3 clip (reframe 9:16/16:9, 24 fps smoothing, fast upscale
  to 1080x1920, driving-video prep -> 77 frames @16 fps).
- Workflow JSONs: valid, every placeholder filled by the service. Node names/inputs taken from ComfyUI
  source (`WanAnimateToVideo`, `TrimVideoLatent`, `LoadVideo`, `GetVideoComponents`, `CreateVideo`, `SaveVideo`)
  and comfyui_controlnet_aux (`DWPreprocessor`).
- NOT compiled here (no Maven): `studio/*`, `StoryTranslationService/Controller`, `ComfyUIVideoProvider` change.
- NOT run on GPU: motion control and AI upscale graphs. First run may need a node/model name fix.

## Not done (next)
Background removal, lip-sync on uploaded video, virality score, publishing, motion control > 5 s
(chained windows), face-video for expressions, replace ("mix") mode.
