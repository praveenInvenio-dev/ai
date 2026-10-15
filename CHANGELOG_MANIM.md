# Animated maths / physics scenes (Manim)

New optional sidecar `manim-render/` (Manim Community 0.22, MIT, CPU only, compose profile `manim`).
Start: `docker compose --profile manim up -d --build manim` (or add `manim` to COMPOSE_PROFILES).

## How it works
- The model never writes code. It adds a scene-level `"manim": {"template", "params"}`; the studio validates it AT PLANNING TIME
  (bad formula -> that scene is left as a normal scene, with a note) and the sidecar validates again before rendering.
- Templates: function_plot (curve, labelled points, shaded area, tangent, caption), vector_add (tip to tail + resultant),
  projectile (path, H, R, t, formulas), equation_steps (algebra line by line with notes), wave (waves + sum),
  unit_circle (sin / cos / angle), pythagoras (squares on the sides).
- Formulas go through a whitelist evaluator (x, pi, e, + - * / **, sin cos tan exp log sqrt abs ...). Anything else is rejected
  (tested: 10 legitimate formulas accepted, 14 hostile ones such as __import__ / open / lambda rejected).
- Order in a lesson: slide + a fast still of the final frame (backdrop, storyboard, fallback) -> narration (its length is known) ->
  the animation is rendered paced to the narration -> clip = animation under the transparent overlays (same path as the kids
  animated pictures). If the animation fails the still is shown; if the service is down the lesson finishes without Manim scenes
  and the notes say how to start it.
- The planner prompt only mentions Manim when the service really answers. Manim scenes keep `elements` empty (the heading titles
  the scene; words go through the template's caption / notes) so nothing is drawn on top of the graph.
- UI: "Animated maths & physics scenes (Manim)" checkbox in Brief & story, with live service status (GET /concept-explainer/manim/status).
- Config: `studio.manim.url` / `MANIM_BASE_URL` (default http://manim:5008). No LaTeX: maths uses Unicode (x², √, θ).

## Verified
7 templates x landscape + portrait rendered and viewed; lengths within 0.1 s of the request; about 5 s render per 8 s clip.
Real HTTP: animation, cache hit, preview, 9 invalid / hostile requests rejected. Real lesson through the real service and real
client: 17 checks (hostile formula dropped, service-down fallback, animation length = narration length, quality check passed).
Regression: review-flow test 21/21, plain lessons 4/4 clips, Angular build.
Bugs found and fixed on the way: stretched portrait frames, overlapping text, white "page dip" firing on dark maths scenes (kids only now),
black-frame false alarm on very dark themes, scene silently dropped by an older sidecar.

## Not verified
Docker image build, a real model choosing good Manim scenes with correct numbers, portrait / kids / shorts lessons with Manim scenes.
