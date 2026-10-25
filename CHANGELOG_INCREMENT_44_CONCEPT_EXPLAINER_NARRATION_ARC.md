# Increment 44 — Concept Explainer narration arc

## Changes
- Standardized the planning and scene-writing prompts around a causal six-stage teaching arc: definition, current problem, what it solves, how it works, implementation/worked example, and conclusion.
- Clarified that these are semantic stages, not mandatory title cards; lesson length and topic can merge or split scenes.
- Added requirements for a coherent end-to-end example, valid code and expected output for programming topics, explicit limitations, and a concise conclusion.
- Added `docs/concept-explainer/NARRATION_STRUCTURE.md` as the reusable reference and `java-multithreading-sample-narration.txt` as a review sample.

## Compatibility
- Prompt-only changes in `TutorialPrompts`; no API contracts, database schema, renderer routing, Angular components, or existing generation paths were changed.
- Build verification was not run in this environment because dependencies are not installed here.
