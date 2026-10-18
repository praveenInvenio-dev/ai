# Story to Video (MiniMax H3)

The Video Generation page now has three modes:
- Image to video: animate one starting image.
- Text to video: generate one standalone scene.
- Story to video: draft a multi-scene story, optionally lock an uploaded character reference, render each scene with the existing H3 sequence pipeline, and merge the result.

Duration presets are 30 seconds, 1 minute, 3 minutes, and 5 minutes. Approximate planning uses one five-second visual scene per interval (6, 12, 36, 60 scenes). Narration is scene-aware and may extend a scene if required to avoid clipping speech.

The story pipeline uses the existing Story Engine and Video Sequence APIs. It requests consistent character descriptions and visual style; explicit camera angle, shot size, movement, lighting and atmosphere; concise narration and character dialogue with acting/emotion directions; ambience, timed sound effects and background music cues; and a coherent story arc with hook, escalation, climax and resolution.

Required services: backend, configured Ollama/story-generation model, image-generation provider for keyframes, MiniMax H3 ComfyUI workflow, and shared storage for generated media.
