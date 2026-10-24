# Increment 33 — Ollie the owl mascot, a clearer and smoother journey kit

## Mascot
- New animated mascot scene (`revideo/src/scenes/mascot.tsx`): Ollie the owl blinks, bobs and breathes, flaps or waves his wings, changes his brows for the mood (happy | wow | thinking | wave), and his beak opens
  with the narration (`mouth` = a 0..1 loudness envelope per frame; without it he talks during each sentence). The speech bubble types each line when its sentence begins.
- Planner: the `mascot` + `speech` devices become this scene (router kind `mascot`, client `mascotOf`, service, renderer validation). Replaces the plain 2D robot in kids lessons.

## Journey kit: clearer and smoother (UPI sample was blurry and choppy)
- Cause: rendered at 540x960 / 12 fps and then stretched and frame-duplicated, with a dark network scene. Now rendered natively at 720x1280 / 30 fps, crf 17.
- Network: brighter pipes, the route lights up behind the pulse, ripples at each machine, an unlit ground, camera smoothing that does not depend on the frame rate. Bigger caption / stat / name chips, tags and clock.
- Bug found while doing it: a `//` comment in the middle of a line commented out the camera update (camera stuck at the origin). Fixed.
- `make-journey.py` renders a five-scene journey in English or Hinglish (Kokoro English voice; Kokoro Hindi voices 32-35 via sherpa-onnx `kokoro-multi-lang-v1_0`, Piper Hindi as fallback).

## Limits
- Hindi / Hinglish pronunciation of the Kokoro Hindi voice was not judged by ear. The script and on-screen Hinglish need a Hindi speaker's review.
- The mascot's mouth envelope is computed by the sample script; in the product the service does not compute it yet (the sentence-window fallback is used).
- 70 unit tests (JUnit stand-in). Not run: Docker builds, the full Maven build, a lesson through the Spring pipeline.
