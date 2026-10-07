## V20 H3 Classic Story Audio

- Classic Story Production is now strictly image-based; H3 video generation is not used by this path.
- Added a MiniMax H3 audio-only ComfyUI workflow using a disposable 32x32 visual latent and the existing H3 audio VAE.
- H3 audio is the preferred classic soundtrack engine: narration/dialogue + ambience + SFX + background music are generated together.
- Existing TTS remains an automatic per-scene fallback if H3 audio generation fails.
- Existing standalone H3 Video Generation and Video Sequence H3 flows are unchanged.
- Classic production no longer adds a second automatic music bed when H3 owns the soundtrack.

## v20.1 — Optional Story Modes & Creative Formats
- Normal Story is now the default; creating a classic story no longer injects Entertainment Learning instructions.
- Entertainment Learning is opt-in and can explain any user-requested topic with an explicit simple explanation and concrete example, not just a story metaphor.
- Creative Format is optional and independent of the topic: Rap, Song, Musical, Comedy, Mystery, Detective, Action, Office Comedy, Bedtime, Absurd, Cinematic and multiple satire formats.
- Learning and Creative Format can be combined (for example, explain Azure as a rap or explain DNS as a detective mystery).
- Satire presets are now optional rather than the primary story-creation path.
- Backend prompt handling now recognizes explicit story-mode and creative-format tags while preserving the existing legacy Entertainment Learning tag for compatibility.

## v18.26 — Entertainment Learning Engine
- Added Entertainment Learning mode for programming, technology/AI, medical/biology, construction/civil, law/Constitution, UPSC, NEET, JEE, science, finance/economics and general knowledge.
- Added 30/60/90-second and 3-minute learning durations, difficulty levels and story-based teaching styles.
- Learning prompts require accurate concepts, natural story-first teaching, memorable punchlines and no lecture-style openings.
- Added stricter guardrails for medical, legal, exam and construction topics.
- Legal prompts distinguish historical IPC/CrPC/IEA from current BNS/BNSS/BSA where relevant.

# v18.9 — MiniMax H3 Storyboard Pipeline

- Added image-first MiniMax H3 storyboard video generation with native synchronized audio.
- Added dedicated storyboard Video direction input and narration/dialogue prompt construction.
- Added automatic 3–10 second shot duration based on narration text and pauses.
- Added faster 10-step H3 storyboard rendering, with 20-step QUALITY mode.
- Preserved native H3 audio through final FFmpeg assembly and retained per-scene fallback to TTS + 2.5D.
- Enabled H3-all-scenes story mode in the packaged deployment configuration.

# Changelog

## v18.20 - H3 10 s + Scene sequence
- MiniMax H3 longest clip 5 s -> 10 s (243 frames; LOCAL_AI_ANIMATION_MINIMAX_H3_MAX_DURATION_SECONDS,
  default 10). Video Generation page slider now follows the selected engine (H3 10 s, Wan 5 s).
  LOCAL_AI_ANIMATION_TIMEOUT_SECONDS 3600 -> 5400 in .env. Not benchmarked on 16 GB: 10 s is heavy.
- NEW "Scene sequence" page + /api/video-sequences: N scenes -> locked-character keyframes (Qwen) ->
  optional review -> clips one by one (H3 / Wan 14B / Wan 5B) -> merged long video (ClipMerger:
  normalise + concat or xfade). KEYFRAMES or CHAIN continuity, per-scene redo / edit / upload keyframe,
  retry only missing scenes, cancel between steps, manifest on disk (survives restarts), own executor.
- Out-of-memory at >5 s retries once at 5 s so a sequence still completes.

## v18.19 - audio fixes, cu130 ComfyUI, no-crop fit
- ComfyUI: image `yanwk/comfyui-boot:cu130-slim-v2` (was cu128-slim in the GPU overlay),
  `gpus: all`, `CLI_ARGS=--preview-method none`. COMFYUI_GPU_IMAGE in .env is the single
  override; compose files carry the same default. `gpus` is `!override` in the overlay
  (compose refuses to merge two `gpus` values). Volume layout `comfyui-gpu-root:/root`
  moved into the base file; volume name unchanged, nothing recreated; legacy CPU volumes
  stay declared. New scripts/validate-comfyui-config.sh (also run by setup script).
  Default COMFYUI_EXTRA_CLI_ARGS is now empty (xformers/pinned-memory flags removed; the
  known fallback is documented in .env).
- NARRATION was silent because Chatterbox received the story language as free text
  ("English"/"Hinglish") and used it as language_id -> every request failed -> silent
  placeholder. Sidecar now maps names to ISO codes (Hinglish/Hindi -> hi), rejects
  unsupported ones with a clear 400; backend retries on an Edge voice for the story language
  (Hindi/Hinglish -> hi-IN-Swara, Tamil, Telugu, ...) then Piper before any silence.
  Chatterbox request timeout 120 -> 300 s. [sigh]/[gasp] tags no longer read aloud by Piper/Edge.
- MUSIC/AMBIENCE/SFX beds were -51...-61 dB (inaudible after the 0.16 mix) -> normalised to
  -20 dB (music), -24 dB (ambience), -18 dB (sfx).
- Voice Lab: a muted/hiss-only recording (loudest peak <= -40 dB) is now rejected at preview
  and save; it used to save as a "voice" that cloned into silence. Verified the rest of the
  record -> save -> playback chain (see below in chat): webm/mp4 -> 24 kHz mono WAV, same
  duration and level, waveform correlation 0.999.
- Over-zoom: scene images whose aspect differs >8% from the video frame are now fitted whole
  over a blurred copy of themselves instead of center-cropped.

## v18.18 - final video fixes
- Final video now 1080x1920 vertical by default (VIDEO_ORIENTATION). It was hard-coded
  1920x1080 while Qwen scene images are 9:16, so each frame showed only the middle third
  of the image ("too zoomed"). VIDEO_ORIENTATION=horizontal renders 1920x1080 and makes
  Qwen draw 16:9 images (1344x768 -> 1920x1080).
- Shorts crop only when the source is landscape (no double crop on vertical).
- Gentler Ken Burns: max zoom ~1.07 instead of ~1.14.
- Narration: if Chatterbox/CosyVoice/Sarvam fails, retry on local Piper before falling
  back to silence (bracket tags like [gasp] stripped for Piper).
- docker-compose passes VIDEO_ORIENTATION, VIDEO_TRANSITIONS_ENABLED, AUTO_MUSIC_ENABLED.

## v18.17 - RTX 50-series + H3 checks
- .env: COMFYUI_EXTRA_CLI_ARGS adds --use-pytorch-cross-attention --disable-xformers
  (xformers in the image has no Blackwell kernels -> every Qwen step crashed) and
  --disable-pinned-memory (50 GB hosts).
- H3: canvas orientation follows the start image (MiniMaxH3ImageToVideo stretches
  first_frame without keeping aspect). All 4 H3 graphs re-checked against ComfyUI 0.38
  node source and official templates (inputs, Autogrow names ref_images.ref_image_N,
  SaveVideo, length grid 17k+5, size step 32).
- Frontend: no '@' in Angular templates (NG5002).

## v18.16 - single model stack cleanup
- Images: Qwen Image 2.1 for EVERY scene and every quality profile (FAST = 20 steps,
  others 30). Removed SD1.5 / SDXL / DreamShaper Lightning / LCM / IPAdapter workflows,
  their Java paths (WorkflowTemplateLoader, IPAdapter fallback, style checkpoints,
  QUALITY-only HQ switch, previous-scene continuity reference) and all their settings.
- ComfyUIImageProvider rewritten Qwen-only: t2i / 1-ref / 2-ref graph picked by uploaded refs.
  Missing Qwen nodes -> clear "update ComfyUI" error (no silent SDXL fallback).
- ImagePromptAssembler: single Qwen token budget.
- Video: removed legacy wan-image-to-video (Wan 2.1-style 14B graph + CLIP Vision).
  Default workflows now wan-ti2v-5b-*; I2V-A14B and MiniMax H3 unchanged.
- pre-start.sh: installs ComfyUI-VideoHelperSuite instead of IPAdapter Plus.
- New download-models.sh + MODELS.md; setup-ai-story-studio.sh uses it.
- .env / .env.example / docker-compose cleaned to the current settings only.
- Old notes moved to docs/history/. README rewritten for the current stack.

## v18.15 Wan 2.2 I2V-A14B   ## v18.14 Wan 720p fix   ## v18.13 H3 16 GB profile
## v18.12 FFmpeg audio-pad + chatterbox profile   ## v18.11 Qwen image quality
See docs/history/.

## v18.12 — H3 Character Identity Upload

- Added **Upload identity image** to Story Approval's character builder.
- Uploaded images are stored as reusable `CharacterReference` assets and can be locked/selected as the character's preferred reference.
- Existing Qwen Image 2.1 scene-image generation continues to use locked/primary character references.
- MiniMax H3 now switches to native **Reference-to-Video (R2V)** when a character identity image is available.
- H3 receives the storyboard frame as `<Picture 1>` and the character identity image as `<Picture 2>`.
- Added a dedicated H3 character-reference workflow so existing voice-reference generation is unchanged.
- Character identity prompt explicitly protects facial structure, eyes, nose, mouth, hair, skin tone, age, proportions, clothing and defining features.

## v18.12 - H3 download fix + native audio/Turbo wiring
- Fixed H3 model download script: the previous package did not actually download the Turbo assets.
- Added the missing `minimax_h3_ref2va_pruned_w6a8.safetensors` required by character/voice R2V on the 16GB profile.
- Added official H3 I2V 8-step Turbo LoRA and official H3 R2V 4-step Turbo LoRA download entries.
- Switched H3 I2V fast storyboard renders to the official 8-step Turbo workflow when no character/voice reference is present; Quality remains 20 steps.
- Kept character-reference R2V on the stable 20-step graph because the official 4-step R2V path requires the newer sampler/conditioning template and should not be approximated by simply reducing steps.
- H3 storyboard prompts now consume the story engine's `audioSpec`: ambience, physical SFX, music mood/intensity, narration and dialogue are all passed to H3 native audio generation.
- Added H3 model pre-start downloads with curl resume/retry support.

## v18.13 — Storyboard/video separation + identity-aware character generation

- Story Approval/Storyboard no longer auto-starts video generation after scene-image generation.
- Replaced the Story Approval final-video action with navigation to the standalone Video Generation page.
- Video Generation accepts project/episode/scene query parameters and can open directly on a selected story scene.
- Loading a story scene now imports its generated storyboard image, motion prompt, negative motion prompt, narration, duration and structured audio direction.
- Selecting a story scene automatically switches the Video Generation page to MiniMax H3 image-to-video.
- MiniMax H3 narration is now embedded as native audiovisual generation; the old external TTS mux path remains for Wan.
- Character "Generate Reference" now uses the most recent uploaded identity image as a Qwen reference input when available.
- Generated character references are new images combining the uploaded facial identity with the canonical/custom character prompt and selected visual style, rather than returning/copying the uploaded photo.

## v18.16
- Restored the classic Story Approval **Generate Final Video (Images + Audio)** action.
- Kept the new dedicated **Video Generation (H3 / AI Video)** flow alongside it.
- Classic production continues to use the approved scene-image + narration/audio/2.5D pipeline; it does not redirect to H3.

## v18.17 — Multilingual Story Engine + Viral-Friendly Story Pacing
- Create Story and custom Storyboard now offer Indian-language choices including Kannada, Hindi, Hinglish, Telugu, Tamil, Malayalam, Marathi, Bengali, Gujarati, Odia, Punjabi and Urdu.
- Story Engine now treats the selected language as a hard requirement for title, logline, narration and dialogue, while keeping image/video production prompts in English for model reliability.
- Added Auto-detect for Create Story using Unicode-script detection for common Indian scripts.
- Story generation now explicitly optimizes for genuine retention and shareability: strong opening hooks, curiosity gaps, escalating surprises, memorable lines, visual set-pieces and satisfying endings without deceptive clickbait.

## v18.18 — Persistent Character Reference Library
- Added a character identity-reference dropdown beside the upload control in Story Approval.
- Lists all uploaded/generated references for each character and preserves the selected reference across regenerations.
- Regeneration now accepts the selected reference ID and uses it as the primary identity source while the prompt can change attire, styling, accessories and presentation.
- Newly generated variants are saved to the character reference library without silently replacing the selected identity reference.
- Locked references remain the preferred canonical reference for future generation when no explicit selection is supplied.

## v18.19 — H3 Dialogue Completeness & Human Delivery
- Scene Load now estimates duration from the complete voice-segment script, including dialogue, narration and pauses, instead of relying only on the image duration.
- H3 duration selection now uses the H3 10-second ceiling even before backend status metadata is loaded.
- Added a clear warning when a scene's complete spoken script is longer than H3 can fit in one clip; users are told to shorten or split the scene rather than silently losing dialogue.
- H3 prompt assembly now preserves delivery metadata (emotion/acting direction) for dialogue.
- H3 native audio instructions now explicitly require complete lines, natural conversational pacing, breaths, pauses, hesitation where appropriate, varied intonation, natural emphasis, realistic turn-taking and no skipped/rephrased speech.
- Removed the duplicate H3 narration injection when the prompt already contains the native H3 audio block.
- Removed the hard-coded English audio tag from the generic Video Generation H3 path so multilingual scene text is not mislabeled.

## v18.20
- Added distinct Indian English story language alongside International English and Hinglish.
- Indian English uses en-IN-NeerjaNeural when Edge TTS is selected/available.
- Story generation explicitly preserves natural contemporary Indian-English phrasing and conversational rhythm.

## v18.23 — Persistent Story Video Production
- Added Project → Story loader to Story Video Production.
- Snapshots story scene images/audio and stores narration, dialogue, music, ambience/SFX and language in the sequence manifest.
- Added PostgreSQL persistence for sequence manifests/status/expiry so browser/server reconnects can resume completed scenes.
- Generate All Scenes runs clips one-by-one, persists each completed clip, and resumes without redoing finished scenes.
- Final merge is blocked until every scene has a successful video.
- Added per-scene MP4 download and final merged preview/download.
- Added 24-hour automatic sequence/media retention (configurable with VIDEO_SEQUENCE_RETENTION_HOURS).
- Added xfade failure fallback to production-safe hard-cut concat.

## v5 – Rumik voice quality
- Removed bracketed vocal/SFX markers such as [gasp], [laugh], [chuckle] from Rumik speech input; these remain sound-design concepts for H3 instead of being spoken by Rumik.
- Reduced Rumik sampling temperature to 0.62 and top_k to 20 for more stable Indian-language pronunciation.
- Improved Funny Skit Rumik voice description for natural conversational comedy delivery and consistent speaker identity.
- Added voice mastering to Funny Skit and Classic Story Rumik+H3 mixes: high-pass filtering, gentle compression, limiting/loudness normalization.
- Reduced H3 soundscape mix level to 15% so speech remains intelligible.
- Funny Skit mixed audio is now 48 kHz stereo and final concatenated MP4 is explicitly encoded at 48 kHz stereo AAC 192 kbps.
- Classic Story Rumik+H3 scene audio is now mastered to 48 kHz stereo WAV.

## v6 - narration timeline synchronization
- Classic Story Production now measures the actual generated narration WAV before setting each scene's visual duration.
- Storyboard assembly no longer relies on word-count duration estimates when real narration audio is available.
- Final scene timelines include a small post-speech end beat so the last syllables are never cut by the next scene.
- Production pipeline re-synchronizes scene duration from the actual Rumik/H3 mixed audio immediately before FFmpeg assembly, protecting against stale database/story-engine duration values.
- Existing image-based Classic Story architecture is unchanged; H3 remains the audio/soundscape engine and standalone H3 video generation remains separate.
