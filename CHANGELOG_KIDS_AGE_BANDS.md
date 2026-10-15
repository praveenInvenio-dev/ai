# Kids learning: three age bands, illustrated scenes, research-grounded teaching

## Three tracks (Concept Explainer -> Learning track)
| | Ages 2-5 · Little Learners | Ages 5-10 · Young Explorers | Ages 10-15 · Tech Teens |
|---|---|---|---|
| Picture world | cozy playroom, toddler hero + round white robot buddy, soft morning light | evening study, starry window, light-bulb ideas, rocket, child hero + robot buddy | modern maker studio, holographic screens, teen maker (no robot) |
| Teaching | one big thing per scene, word said 3x, colours / shapes / counting to 5 / big-small, picture-only choices | a mystery with 3 clues, concrete everyday examples, quiz + badge | curiosity hook, levels + XP, challenges, myth vs fact, Idea-Plan-Build-Test-Improve |
| Voice | slow (0.82-0.86), 2.5 s pause after questions | unhurried (0.90-0.93), 1.4 s pause | normal pace, short rhetorical pauses |
| Transitions | 0.8 s calm fades | 0.5 s | 0.35 s |
| Overlay look | toddler (rounded, primary colours, no clutter) | kids | teen (dark game HUD, neon) |

## Illustrated scenes (like the reference poster)
- Every kids scene starts with a full-screen 3D animated-film style illustration (Qwen), per-band art direction,
  the recurring cast described identically ("characters" from the outline), and the FIRST picture used as the
  character reference for every later picture (consistent child + robot). Up to 16 pictures per lesson.
  Kids-specific negative prompt (nothing scary, no text, no distorted faces).
- Overlays on top: cloud title banner, round icon badges, speech bubbles, and the age's interaction device
  (choose / count / sizes for 2-5; quiz / badge for 5-10; level / challenge for 10-15). Pictures are a fixed
  underlay (never zoomed); overlays are placed clear of the faces; the top third of each picture is kept calm.

## Research behind it
- Fast, fantastical cartoons impaired 4-year-olds' executive function; slow realistic ones did not (Lillard & Peterson 2011, Pediatrics).
- At 2.5-4.5, parasocial interaction (a character talking WITH the child) improves learning from video; familiar
  characters teach better (Calvert; Aguiar). AAP: 2-5 year olds - high-quality programmes, co-viewed.
- Piaget: preoperational 2-7 (symbols, no logic yet), concrete operations 7-11 (logic on concrete things), formal 12+.
- Teens: more sensitive to respect and status; autonomy framing beats "you should" (Yeager, Dahl & Dweck 2018;
  Bryan, Yeager et al. 2016, 2019). Curiosity boosts memory via reward circuitry (Gruber et al. 2014, Neuron).
- Repetition -> mastery (Blue's Clues); segmenting / signalling / weeding (Mayer; Ibrahim et al. 2012).

## Update: picture-book flow (no image-to-video)
- Every kids scene now ALWAYS gets its own rich Qwen illustration (inserted by the app if the model forgets), described
  by a per-scene "picture" field: who, doing what, where, expression, camera, light. Same cast every time (first
  picture = character reference).
- Video is made like the Story flow: the still illustration is animated with a slow, eased camera move (push-in,
  pan right, pull-out, pan left, gentle rise - varied scene to scene; half the movement for ages 2-5), each scene
  opens with a soft dip from white, and the overlays (cloud title, icon badges, speech bubbles, picture choices,
  quiz / challenge cards) are separate transparent layers revealed sentence by sentence ON TOP, so they never move.
- Narration is a storybook, not a lecture: named hero + robot buddy (2-5, 5-10) or a named teen maker's project
  (10-15), "One sunny morning...", short quoted dialogue, sound words, feelings; the idea is discovered inside the
  story. The voice direction is a warm storyteller (2-5 slow and soft, 5-10 playful with character voices, 10-15
  a friendly young creator). The track rules now reach the scene writer, not just the outline.

## Update: Story-style two steps - pictures first, then the video
1. STORYBOARD: a kids lesson now stops after every scene's illustration is drawn (status STORYBOARD). The UI shows
   each page (picture + overlays) with its words and its picture description:
   - "Save words" edits what the scene says (one sentence per line),
   - "New picture" redraws that scene, optionally from a new description, with the same characters (the lesson
     keeps the first picture as the cast reference).
2. MAKE VIDEO:
   - "Make animated cartoon": every picture is animated with Wan image-to-video (the Story flow's engine) - blinks,
     smiles, small gestures, drifting light, the scene unchanged. Narration longer than one clip is covered by
     chaining clips from the previous clip's last frame (max 3), then holding the last frame. A failed animation
     falls back to the camera move for that scene (noted on the lesson).
   - "Quick video": slow pans / zooms over the stills.
   Narration is recorded first (it sets each scene's length), then all animations, then the clips - one GPU switch each.
   If the video step fails the pictures are kept and the lesson returns to the storyboard.
API: PUT /jobs/{id}/scenes/{n}/narration {sentences}, POST /jobs/{id}/scenes/{n}/picture {description},
     POST /jobs/{id}/video {animated}.
