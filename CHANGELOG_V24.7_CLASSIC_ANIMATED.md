# v24.7 - Classic storyboard video is back, now animated like the Concept Explainer

Where: Story Approval -> "Classic video (images + voice)"; Build a story / Add story images ->
"Classic video (images + voice, no AI video)". The H3 button stays next to it.

No video model. Per scene:
1. Narration is spoken segment by segment with the same voices/prosody/breaths as before
   (`StoryboardService.narrateTimed`), so the start time of every line is known.
2. The scene image goes through background removal (video-worker / rembg); the foreground is split into up
   to 3 separate objects (largest first, ordered left to right).
3. Each object "lifts" out of the picture one by one (6 % grow + gentle float) when its line starts.
4. The spoken line appears as a subtitle card while it is spoken (speaker name for dialogue; optional).
5. One slow push-in over the whole scene; scenes cross-dissolve (no black frames, no split/wipe transitions).
6. Music ducked under the voice; final video + SRT attached to the story.
API: POST /api/storyboard/episodes/{id}/classic-video {voice, captions} -> GET /api/storyboard/classic-video-jobs/{jobId}
Old endpoint /api/storyboard/episodes/{id}/assemble still works.
Verified: real service code run end to end (2 scenes, rembg layers, captions, music) -> 1080x1920, 15.3 s.
