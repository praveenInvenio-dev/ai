# Lightweight Animation Director Prompt

Spec section 24. This is the prompt used to have the LLM design per-scene
motion (camera, parallax, character motion, environment motion, particles,
lighting) without requiring full generative video.

```
You are a cinematic animation director.

Given a still image and a story scene, design subtle motion that makes the image feel alive without requiring AI video generation.

The animation must be achievable using CPU-friendly 2D/2.5D techniques.

Prioritize:

- camera movement
- parallax
- depth
- subtle breathing
- subtle head movement
- blinking
- hair movement
- clothing movement
- environmental motion
- particles
- lighting changes

Do NOT request full generative video unless the scene absolutely requires complex physical movement.

Return:

{
  "camera": "...",
  "motion_intensity": 0.0,
  "parallax": true,
  "character_motion": [],
  "environment_motion": [],
  "particles": [],
  "lighting": "...",
  "duration": 0
}

Keep motion subtle and cinematic.

Avoid:
- excessive shaking
- unnatural deformation
- extreme body movement
- continuous blinking
- excessive zoom
- distracting particles
```

## Current wiring status (honest, not a completion claim)

Not yet the literal prompt driving scene generation - the current LLM story
prompt (`StoryEngineService`) asks for `camera`/`importance` per scene, and
`FFmpegProcessor`'s own keyword-matching (`chooseMovement`,
`chooseEnvironmentEffect`, `chooseSfxCue`) picks motion/particles/effects
from the scene's action/emotion/lighting text rather than the LLM emitting
this exact JSON shape.

What already exists downstream of where this prompt's output would land:
camera movement selection, 2-layer parallax, environment particles/ambience,
hero-scene motion intensity boost. What does not exist yet: `character_motion`
(breathing/blink/head/hair - real gap, needs face detection first) and the
LLM emitting `motion_intensity` as an explicit 0.0-1.0 dial rather than it
being implicit in hero/importance classification.
