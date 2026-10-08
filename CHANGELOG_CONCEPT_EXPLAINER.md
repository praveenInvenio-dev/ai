# Concept Explainer

Added as an isolated module; existing story/video pipelines are unchanged.

## Flow

Topic + optional instructions -> Ollama lesson plan -> one neon visual per scene -> local/self-hosted TTS -> lightweight FFmpeg 2.5D motion -> final MP4.

## UI

Open **Concept explainer** from the new **LEARN** section in the left navigation, or visit `/concept-explainer`.

Supports duration, language, difficulty, Ollama model selection, static/lightweight animation, scene previews, audio previews, and per-scene image/audio regeneration.

## Backend

New endpoints are under `/api/concept-explainer` and use isolated in-memory jobs plus `concept-explainers/<jobId>/` storage with 24-hour cleanup by default.

No database migration is required.

## GPU behavior

No AI text-to-video model is used. The image provider generates the scene artwork; FFmpeg performs inexpensive pan/zoom/fade motion and audio assembly. Static mode disables the camera motion.
