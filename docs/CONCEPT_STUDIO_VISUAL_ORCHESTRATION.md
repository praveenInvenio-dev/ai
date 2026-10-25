# Concept Studio — Visual Intelligence routing contract

Concept Studio is the user-facing left-menu workspace. It calls the existing Concept Explainer backend and does not create a parallel video-generation pipeline.

## Planning
- Ollama may produce a schema-constrained concept graph and 3–7 scene ideas for the Concept Studio preview.
- The backend sanitizes lengths and identifiers, limits node/edge counts, validates engine IDs, and rejects unavailable renderer candidates.
- The deterministic plan is used if Ollama is offline, returns invalid JSON, or cannot provide enough eligible scene ideas.
- The production scene router remains the final authority. Model recommendations do not bypass renderer health, validated Manim specs, Rive asset requirements, or safe template constraints.
- Concept graph relationships are displayed in the Concept Studio preview; this graph is planning metadata, not a substitute for validated domain facts.

## Routing policy
- Manim: validated mathematical / physics specifications.
- Three.js: scenes where spatial depth is a material learning aid.
- PixiJS: continuous 2D particle, charge, wave, signal and packet movement; eligible only when renderer health confirms the adapter.
- Motion Canvas: ordered visual sequences and supported animated boards.
- Native diagram renderer: structured labels, code, comparisons, tables, flowcharts and reliable fallbacks.
- Rive: only when an approved asset exists and fits the scene.

The router scores a scene using its title, narration, visual description and plan device types. Capability checks are hard gates: an unavailable renderer cannot be selected. The planner explains its choices and fallback options. Do not treat scores as an educational truth metric; they are deterministic routing heuristics to be calibrated from real output and review signals.

## PixiJS continuous clips
- `/render-frames` produces progressive stills for scene previews.
- `/render-clip` produces a continuous PixiJS MP4 at 30 fps, animating particles over the whole duration and revealing labels at supplied narration sentence-start times.
- `/health` reports `clips.PIXIJS` independently based on FFmpeg availability.
- Production attachment only attempts PixiJS MP4 when PixiJS was the actual still renderer and clip export is available. If the MP4 fails, still frames remain available and a warning is recorded.

## Safety, validation and telemetry
- Model-generated content is treated as data. The backend does not execute arbitrary generated JavaScript.
- PixiJS renders from a fixed template with validated text, dimensions, theme, scene kind and bounded particle count. Renderer networking is locked down to local bundled assets.
- Frame responses validate minimum frame count and distinct-frame variation. Clip responses validate MP4 output. Per-engine in-memory metrics track successes, failures and render duration; these reset on restart.
- Technical checks cannot prove pedagogical accuracy. Semantic review remains a separate future quality layer.

## Deployment and verification
Rebuild the visual renderer image and restart the services after updating the project. Check `GET /health` for `engines.PIXIJS=true` and `clips.PIXIJS=true`, then run `node smoke.mjs http://localhost:5020`.
