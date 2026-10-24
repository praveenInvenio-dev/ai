# Increment 37 — Expressive Indian-English narration continuity

## Targeted changes
- Narration now synthesizes related sentences in short phrase chunks (maximum two sentences and 32 words) so expressive TTS receives connected context rather than one independent request per sentence.
- Sentence-level visual timing is preserved using per-sentence word-share estimates inside each phrase; this is an estimate rather than forced-alignment output and should be checked in rendered videos.
- Added scene-/sentence-aware acting directions for hooks, questions, reveals, technical terms, practical examples and recaps.
- Reduced over-stacking of fixed, semantic and planner pauses; the base breath remains while semantic beats add only a controlled amount.
- Chatterbox remains the recommended emotion-capable Indian-English route (`narrator-in-male` / `narrator-in-female`). If the configured voice is Edge, the system now clearly warns that emotional performance is unavailable in that engine.

## Validation
- Source-level inspection completed.
- Maven build and live TTS/render test were not available in this environment; phrase-level sentence timestamps are estimated by word share, so a runtime regression test is required before production deployment.
