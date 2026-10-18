# Story-to-Video H3 — Increment 16

## Intended workflow

Story-to-Video is now a one-click production flow and is deliberately separate from the Create Story approval workflow:

1. Enter a story premise and production options.
2. Upload/name one locked main-character reference.
3. Generate a complete story draft internally.
4. Automatically start the MiniMax H3 sequence using the uploaded character identity.
5. Render short five-second H3 shots with a five-second shot-duration override, chaining each scene from the preceding clip's final frame.
6. Automatically merge completed clips and expose the final video on the Video Generation page.

No navigation to the Create Story approval page, no manual keyframe approval, and no second manual Produce action are required.

## Implementation notes

- Frontend `video-generation.component.ts` now includes main-character name/photo fields in Story mode and chains upload -> project -> draft -> H3 sequence.
- `ApiService.produceEpisodeVideo` accepts optional `secondsPerScene` and `fixedFiveSecondShots` parameters.
- `VideoSequenceController` forwards the fixed-shot option and uses CHAIN continuity for this mode.
- `VideoSequenceService` persists the fixed-five-second mode, requests five-second scene clips, and passes the five-second maximum into the H3 scene renderer.
- `H3SceneRenderer.SceneSpec` now supports an optional max-shot override while preserving the previous constructor for existing callers. The override also constrains narration-tail retries and Indic-TTS splitting.
- Existing Create Story, Story Approval, single-scene video generation and classic image-based story production remain on their previous code paths.

## Build verification

The source archive is integrity-checked. A full Angular/Maven build was not completed in this environment: Maven and Docker are unavailable, and `npm ci` did not complete before timeout. Run the normal Docker Compose build on the target server before deploying.
