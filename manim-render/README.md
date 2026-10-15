# manim-render

Animated maths and physics scenes for AI Story Studio, rendered with [Manim Community](https://www.manim.community/) (MIT).
The studio never sends code: it picks a template and fills in parameters, which are validated here before anything renders.

| template | what it draws |
|---|---|
| `function_plot` | y = f(x) with marked points, shaded area, tangent line |
| `vector_add` | vectors tip to tail and the resultant |
| `projectile` | trajectory with H, R and t |
| `equation_steps` | algebra, line by line, with a note beside each step |
| `wave` | waves and their sum |
| `unit_circle` | sin and cos from the angle |
| `pythagoras` | squares on the sides of a right triangle |

Run with `docker compose --profile manim up -d --build manim` (add `manim` to `COMPOSE_PROFILES` in `.env` to keep it on).
`GET /health` shows the version; `POST /api/render` returns an MP4, or a PNG when `"preview": true`.
Formulas accept `x^2 - 4x + 3`, `2x(x+1)`, `sin(x)/x`, `exp(-x^2)`, `sqrt(abs(x))`; anything else is rejected.
