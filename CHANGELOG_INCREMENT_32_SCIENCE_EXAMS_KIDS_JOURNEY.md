# Increment 32 — kids / JEE / NEET audit, animated science scenes, Journey Explainer

## Audit (real device formats, native renderer)
- **Quiz** (all audiences): the question overlapped the first option. Fixed (more space, taller card).
- **Cycle**: long stage names were cut ("Regeneration..."). They now wrap onto two lines.
- **Kids "day and night"** was two tiles and an arrow; **JEE free-body diagram** and **formulas** were still pictures; the step flow dropped the numbers of a worked example.

## Fixes and additions
- **Animated quiz** (3-2-1 think ring, then the right option turns green, others dim; bigger in the kids look), **free-body diagram** (forces grow in, equal and opposite ones cancel, the net force appears and the body
  accelerates; the schedule always leaves the last ~4 s for the physics), **cycle** (stages on a ring, a dot chases round). Routed for kids too (not for the flow / board / diagram kinds, which have no light palette).
- **Lit, spinning Earth** (3D `earth` template: day and night, a sun, a "you" marker) with light-theme support in all 3D scenes; "Earth is spinning / rotates" selects it for kids; a quiz about it stays a quiz.
- **Step details**: steps with "label|detail" lines show the detail (the numbers of a worked numerical) under the label in the animated flow.
- **Formula legend**: "F|net force (N)" lines become the caption "F = net force (N) · m = mass (kg) · a = acceleration (m/s²)".
- **Diagrams** zoom to fit when they cover little of the frame (at most 1.5x); fixed a stage bug found while testing.
- **Kids** native scenes may zoom up to 1.9x (children need bigger pictures).
- **Journey Explainer** teaching style (cold open mid-journey, one tracked object, slow-motion clock, show first then name, one break, recap as a stack, closing question) + voice direction + UI option.
- **Journey kit** prototype (`visual-renderer/journey/`): HUD + globe / network / packet / recap scenes, Three.js + bloom, own dotted globe from world-atlas + d3-geo. Not wired into the planner / router / service yet.

## Limits
- The reference video could not be transcribed (no reliable Hindi ASR here); its narration pattern is inferred from on-screen text and structure.
- The undersea cable scene, data-centre racks and any character / hand / balcony art are not reproduced (need real 3D assets).
- Samples were built from hand-written lesson plans; the English Kokoro voice is used. Hindi / Hinglish / Kannada voices were not available at this quality.

## Verification
- 68 unit tests (JUnit stand-in). Samples built with the real router, native renderer, renderer service and ffmpeg assembly: JEE (Newton's second law), NEET (photosynthesis), kids (day and night), UPI journey.
  Not run: Docker builds, the full Maven build, a lesson through the Spring pipeline.
