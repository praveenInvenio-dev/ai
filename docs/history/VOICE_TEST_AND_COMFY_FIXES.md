# Voice test and ComfyUI fixes

## Voice
- Reference validation now requires a hard minimum of 5 seconds; 5-15 seconds remains recommended.
- Voice Library can play the exact stored reference recording.
- Test voice uses a longer sentence because the previous seven-word sample naturally produced about 2 seconds.
- Generated test duration is shown separately from the original reference duration.
- This separates recording/storage problems from cloned-TTS generation problems.

## ComfyUI
- Reference/start-image upload responses are now validated for a non-empty filename.
- ComfyUI `subfolder/name` responses are preserved when needed.
- A directory-like response such as `input` is rejected before a LoadImage workflow is queued.
- This prevents `LoadImage: [Errno 21] Is a directory: '/root/ComfyUI/input/'`.
