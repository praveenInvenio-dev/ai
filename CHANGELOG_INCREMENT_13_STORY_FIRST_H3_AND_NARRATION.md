# Increment 13 — Story-first H3 workflow and narration reliability

## Story-to-Video
- Story mode now generates a complete editable story draft first and navigates to Story Approval instead of immediately starting expensive GPU video rendering.
- The story idea is explicitly treated as a seed. Draft requirements now call for a beginning/middle/ending, character motivations and identities, scene-by-scene narration/dialogue, camera movement, timed SFX, ambience, and a coherent music arc.
- H3 prompt requirements use the official `integrated_multimodal_description`, `overall_soundscape`, and `non_diegetic_music` field structure, with `<d>[Language]...` speech markers where applicable.
- Removed the single-character upload-before-story step. After the complete cast is generated, Story Approval exposes per-character master prompt editing, generate/regenerate, optional identity-photo upload, reference selection, and locking before scene keyframes and H3 production.
- Added six story narration styles: Story-driven Mentor, Humorous Explainer, Logic-First Analyst, Professional Instructor, Simple Beginner, and Storytelling Teacher.
- Story draft creation is no longer blocked merely because local H3 video rendering is unavailable; actual rendering still requires a configured/available provider.
- Story Approval passes the selected video orientation and episode character IDs into H3 production.

## Voice reliability
- Chatterbox reference bootstrap now supports a lazy on-demand attempt when a narration request arrives before the asynchronous startup bootstrap has finished.
- Missing references return an explicit error stating that the requested voice was not substituted. Existing provider fallback behavior may still return a visible warning from the backend when configured.

## Validation
- Python syntax validation passed for `tts-chatterbox/server.py`.
- Angular production build and Java/Maven tests were not run in this environment because frontend dependencies and Maven are not installed here.
