# Increment 68 — H3 Story-to-Video duration and five-second scene contract

## What changed
- Kept the existing Video Generation modes and added a flexible story duration input to the existing `Story to video · H3` tab (30–360 seconds, snapped to five-second increments).
- The UI shows the planned scene count (`duration / 5`) before generation.
- Story production explicitly asks for exactly one chronological scene per five-second interval, with short speech, a clear visual action, continuity, and a resolved ending.
- Added a dedicated `[H3_STORY_MODE:FIXED_5S]` prompt marker. The story planner removes this internal marker from the displayed story idea and applies a fixed-scene-count rule only to this flow; ordinary Story Engine generation keeps its normal pacing rule.
- The existing pipeline remains in use: save/lock the selected character references, draft the story, send the same character IDs to the H3 sequence, render scene-by-scene with H3 reference scope, and automatically merge the clips.

## Character consistency
- Reuses the selected character-library reference images, not a newly invented character per scene.
- The existing H3 reference scope passes up to two locked references into every H3 clip, including chained clips.
- Existing Image-to-Video, Text-to-Video, Wan, and normal Story Engine flows are otherwise unchanged.

## Verification notes
- Reviewed the Angular Video Generation flow, Story Engine prompt builder, `VideoSequenceController`, and `VideoSequenceService` reference/merge path.
- Frontend build could not be run in this environment because the npm dependency install did not complete. No live ComfyUI/H3 render was available in this environment.
