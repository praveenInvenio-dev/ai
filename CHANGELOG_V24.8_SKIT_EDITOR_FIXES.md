# v24.8 - Funny Skit quality + styles, character library, editor auto captions, fixes from samples

## Fixes from the three sample videos
- Funny Skit in Kannada did not speak Kannada: H3 cannot speak Indian languages reliably. The shared H3 renderer
  now switches to the IndicF5 voice for Indian-script lines (H3 lip-syncs to it) - Funny Skit, Story Video
  Production and Video Generation. `H3_SCENE_INDIC_AUTO_TTS=false` restores H3 speech.
- Funny Skit output was 480x832 (raw H3): now delivered as a 1080x1920 reel with light sharpening.
- Concept Explainer "Chatterbox voice -> HTTP 500": Chatterbox voices were sent to the Piper/Edge `tts` service
  (unknown voice) unless TTS_PROVIDER=chatterbox. Narrator/tutor/chatterbox: voices now always go to tts-chatterbox
  (also Voice Lab preview, with a clear message if the container is not running).
- Concept Explainer light styles (sketchnote/storyboard/anime): AI illustrations were washed out. Background is now
  keyed out by the image's own corner colour (works for white, cream, pastel or black backgrounds).
- Classic animated video: camera now pushes in towards the main character or pans slowly across the scene
  (varies per scene), images lightly sharpened.

## Funny Skit
- Visual style: realistic, cinematic, 3D cartoon, anime, claymation, comic (storyboard frames + H3 video).
- Character source "My locked characters": every locked character from Create Story, Character Studio or a saved
  upload. Uploads can be saved ("Save to my characters") as a locked, reusable DB character.
- Per part: edit dialogue and picture description, "Save text", "Save & redraw" (optionally also later parts),
  "New script" (same idea, character and style). Redrawing one part no longer wipes the other parts.

## Character lock
Locks were saved, but story characters have no universe, so other tools never listed them. New
`/api/character-library` lists every character with a locked reference (any story / studio / upload) and
saves uploads as locked characters.

## AI video editor
- "Auto captions" now works: faster-whisper (video-worker) word timings -> CapCut/Higgsfield-style captions,
  2-4 words at a time, the spoken word lights up, pop-in; styles Bold reel, Clean, Kids, Cinematic, Minimal;
  burned into the final render. First use downloads the whisper model (WHISPER_MODEL=small).

## v24.8.1 - "hello hello hello" removed
- Lesson narration is cleaned (`NarrationScrub`): leading greetings (Hello/Hi/Hey/Welcome/Namaste/Namaskara/Vanakkam and
  Indian-script equivalents) are stripped and any word repeated 3+ times in a row is collapsed. Greetings in the middle of a
  sentence ("a server says hello to the client") are kept.
- Planner rule added: no greeting/filler openers, no repeated words.
- Chatterbox reference clips for narrator-*/tutor-* voices used to say "Hello. I am your tutor..." and a cloned voice can echo its
  reference; the reference text no longer contains a greeting and the clips are rebuilt once on start (marker file `.ref2`).
