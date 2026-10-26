# Increment 64 — Explainer Studio is ~5-10x faster to render

Includes Increments 55-63.

## Where the time went (measured, 1080x1920 frame, Chromium software rendering, 1 core)
| Version | ms / frame |
|---|---|
| Increment 63 (animated SVG background: masked grid pattern + feTurbulence grain, repainted every frame) | **4637** |
| same frame without the background at all | 243 |
| **Increment 64** (background painted once per scene, only moved per frame) | **489** |

The "living" background was ~90 % of render time: a 30 s video = 900 frames ≈ 70 CPU-minutes just for it.

## Changes
- **Background**: light pools, grid, vignette and film grain are painted ONCE per scene (canvas → image, cached); each frame
  only drifts/zooms that image on Perlin noise and moves the dust particles. Looks the same, ~9.5x cheaper per frame.
- **Render concurrency**: default = all CPU cores but one (max 8) instead of 2 (compose `EXPLAINER_RENDER_CONCURRENCY`, empty = auto).
- **Final encode**: x264 preset `medium` instead of `slow` (~2x faster encode, ~3 % bigger file, same quality). `FINAL_PRESET=slow` to go back.
- **Fewer LLM calls**: a scene is rewritten only for problems the viewer would notice (sentence too long, wrong script,
  markdown, repeated visuals). Style nits (contractions, a missing question…) are logged, not re-generated — saves up to
  one full LLM call per scene.
- **Timing report**: every job ends with `Time per stage: script 95s · voice 40s · timeline 2s · render 70s · assemble 12s`.

Verified: 20/20 API tests; benchmark above; stills in dracula + academic themes look unchanged.
