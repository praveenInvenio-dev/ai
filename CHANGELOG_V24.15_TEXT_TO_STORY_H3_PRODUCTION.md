# Increment 7 — Text-to-Story H3 Production

## Added
- Added a dedicated Story to video · H3 mode alongside image-to-video and single-scene text-to-video.
- Added 30-second, 1-minute, 3-minute and 5-minute story duration choices, mapped to approximately 6, 12, 36 and 60 five-second visual scenes.
- Added optional character-reference image upload. The image is saved through the existing locked Character Library API and the locked character ID is passed directly into the H3 sequence renderer so it is reused when creating each scene keyframe.
- Reused the existing Story Engine draft workflow and Video Sequence production pipeline.
- Added narration-track direction (humorous, logic-first, story-driven), genre, language, visual style and orientation controls.
- Added H3 production instructions requesting concrete camera angle/movement, shot size, lighting, atmosphere, dialogue acting direction, timed SFX, ambience and background-music cues.
- Added sequence polling, per-scene progress, final merged-video playback and download.
- Raised the manual sequence scene cap from 12 to 72 to accommodate longer productions.

## Notes / validation limits
- Reference conditioning improves character consistency but cannot guarantee pixel-identical identity in every generated frame.
- Five seconds is a target per scene, not a hard cut: if speech or dialogue is longer, the H3 renderer may extend or split shots to avoid truncating words.
- Three- and five-minute videos may require many GPU renders and can take a long time; the UI shows progress and preserves the backend sequence.
- Runtime validation requires configured Ollama/story model, Qwen image generation, MiniMax H3 ComfyUI workflow, and actual GPU rendering.
