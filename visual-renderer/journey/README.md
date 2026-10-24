# Journey kit (prototype)

Story-style 3D explainer scenes for the "Journey Explainer" teaching style: a persistent HUD (series bar, slow-motion clock with a pill, caption / stat / name chips) over four scene types:
`globe` (dotted land, borders, lofted arcs with glowing heads, city tags), `network` (glowing pipes, a pulse and a camera riding along), `packet` (a message card, a lock, a burst of small cards),
`recap` (numbered stack and fireworks).

    node journey/make-dots.mjs                       # once: land dots from world-atlas + d3-geo
    node journey/render-journey.mjs spec.json outDir # frames (jpg); encode with ffmpeg

Not yet wired into the planner, router or service. Libraries: three (+ bloom), topojson-client, world-atlas, d3-geo (all ISC / MIT / BSD).
About 0.3 s per frame at 540x960 with software WebGL on one core.
