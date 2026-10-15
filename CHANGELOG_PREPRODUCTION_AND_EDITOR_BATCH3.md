# Pre-production (story, narration, review) + editor batch 3 (Cardboard parity)

## What was taken from OpenMontage (ideas only, own wording - it is AGPL-3.0, no code or text copied)
- Story arc scaled to the video length (hook / tension / core / proof / close, in seconds), hook types, hook inside the first
  30 s, "but / therefore" links, misconception-first, guided discovery, one idea at a time, deliberate silence after the key insight.
- A voice plan per scene (pace, energy, words to stress, pause after each sentence) instead of "read naturally".
- Describe what is SEEN, not a mood ("epic", "magical" are banned in picture descriptions).
- A script self-review before spending GPU time; a quality check on the finished file; a brief + concepts-first step.
Not adopted: its agent runtime (it needs an AI coding assistant as orchestrator), Remotion rendering (separate licence), its tools (we already have them).

## Concept Explainer (lessons, 60-second shorts, breakdowns, kids)
- Brief: audience, "after watching they should...", tone; three creative dials (visual variety, motion, information density).
  Motion dial changes fade length and camera movement; the others steer the planner.
- Concepts first: "Suggest 3 story concepts" (different hook type and angle each); picking one makes the planner build on it.
- Story craft block in the outline, writer and 60-s lengthening prompts, with the arc timing for the chosen length.
- Voice plan per scene: model returns delivery {pace, energy, emphasis, pauseAfter, note} and camera; default silence beats after
  reveal sentences ("this is called ...", "that's why ...") if the model gives none; max 3 s added per scene.
  Pauses extend the gap after a sentence (never shorten it) in both the timing and the audio join; stress words go to the TTS request.
  Camera idea (push-in, pan, pull-out, rise, hold) picks the picture movement in illustrated (kids) scenes.
- Script review gate ("Let me read and edit the script before any pictures or voice are made", tutorial formats): status PLAN_REVIEW.
  Deterministic checks: word budget vs the chosen length, greeting / announcement hook (also leftovers like "And welcome back..."),
  slow start, "and then" chains, over-long sentences (stricter for kids), repeated openers, numbers and dates nobody verified,
  mood words in picture descriptions, too many new ideas, kids: talking to the child and a question to answer.
  Edit any scene's lines; "Rewrite the flagged scenes" asks the model to fix ONLY those (same sentence count, same facts); then Continue.
- Quality check on every finished lesson (notes show "Quality check passed" or what is wrong).
- API: POST /concept-explainer/concepts, POST /jobs/{id}/continue, POST /jobs/{id}/doctor/fix, PUT /jobs/{id}/scenes/{n}/narration
  (now also at the review step). Job view gains scriptIssues / scriptWords / scriptSeconds.

## Shared post-render quality check (RenderQa)
Silent gaps, black frames, clipping, too quiet, no audio track, length far from plan. Black detection is deliberately strict (near pure
black only) so the dark teal lesson look is not flagged. Used by lessons and by final editor renders.

## AI video editor
- Finishing pass after the cut is joined (before title and captions): text overlays / lower thirds (measured with the real font, wrapped and
  shrunk to fit, fade in/out), zoom moments (eased), sound effects at a time, AI voiceover with the rest of the sound ducked under it.
  Fixed during testing: the cut's audio vanished after the voiceover ended; long lower thirds ran off the frame.
- AI voiceover from a script (any voice the app has, incl. Indian voices and Voice Lab clones; sentence by sentence, joined with pauses).
- 23 transition presets covering Cardboard's 14 names (Fly / Push / Slide in 4 directions). They are FFmpeg look-alikes, not Cardboard's
  shaders: light leak = white flash, film burn = washed flash, chromatic aberration = horizontal smear, ink bleed = speckle dissolve,
  glitch = pixelate. Audio fade keeps picture and sound in step. Applied per cut or to all cuts; the automatic edit planner is unchanged.
- Look strength slider (blend of graded and original). Fixed during testing: the blend was inverted (0% gave the full look).
- Export: MP4 / WebM / MOV / MKV, 480p-4K, 24-60 fps (applied on final renders; the file extension and content type follow).
- Notes from the finishing / export / quality steps are merged instead of overwriting each other.
- Finish panel in the editor, and chat commands: "add text X at 0:05 for 4 seconds", "add lower third: Name at 10s", "zoom in at 0:12",
  "add a whoosh at 3s", "add light leak transitions between every clip", "push transition to the right after shot 2", "audio fade
  transitions between every clip", "look intensity 50%", "export as webm", "export in 4k at 60 fps", "voiceover: <script>", "remove all overlays".
- DB migration V25 (look_intensity, overlays_json, export_format, export_height, export_fps). Project settings store only sanitised values.

## How this was verified, and what was not
Verified with real FFmpeg / real service code: the finishing pass (audio length, ducking, overlay frames), all four export containers,
the audio-only fade sync, look strength blend, the quality check on broken and clean videos, the script review on weak / good / kids
scripts, 64 editor chat commands, and an end-to-end run of the new review flow with a fake model (21 checks: concepts, gate, brief and
dials reaching the planner, voice plan, edit, rewrite, continue, finished video, 1.50 s silence beat in the audio timing, quality check).
Not verified: a real Ollama model following the new prompts (about 450 extra words; smaller models may struggle), real TTS pauses,
the editor module inside Spring with a database (only a partial compile check in the sandbox), voiceover generation with a real voice.
Not built from Cardboard: keyframes, background removal.
