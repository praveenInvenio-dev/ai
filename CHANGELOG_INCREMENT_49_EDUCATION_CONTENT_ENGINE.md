# Increment 49 — Education content engine and rich explanations

## Goal
Extend Increment 48's social-first concept generation to JEE/NEET, school tutoring, younger learners, YouTube long-form tutorials, and rich educational visual explanations.

## Changes
- Added `EDUCATION_CONTENT_PLAYBOOK` in `TutorialPrompts.java` covering audience lanes, exam integrity, learning design, short-form and long-form formats, rich visual accuracy, originality and responsible trend usage.
- Added `educationLane(...)` to infer exam-prep, child-focused, tutoring, long-form or general education guidance from existing topic, level, audience, duration and instruction text.
- Injected the playbook into concept pitches, long-form outlines and writing, short-form outlines and writing, breakdown reels, and story craft guidance.
- Added specific JEE/NEET safeguards: do not invent PYQ year/session/shift, official syllabus coverage, marks weightage or predicted questions. Distinguish verified PYQs from original syllabus-style practice.
- Added child-safety guidance and human review for platform audience designation; no manipulative engagement requests aimed at children.
- Added rich-visual rules: use generated imagery for context and deterministic renderers for equations, code, axes, graphs, labels, structures, units and numerical answers.
- Added `RESEARCH_EDUCATIONAL_VIDEO_STRATEGY_2026.md` with official platform guidance, current official syllabus references, content lanes and practical testing recommendations.

## Compatibility
Prompt and documentation changes only. Existing JSON schemas, DTOs, APIs, database migrations, Angular screens and renderer routes remain unchanged.

## Verification status
- Archive integrity verified after packaging.
- Java source changes were reviewed and targeted compile/test commands were attempted where the environment allowed.
- No claim of a complete end-to-end Ollama/ComfyUI/TTS render unless explicitly reported in the delivery response.

## Limitations
- No live trend feed, analytics ingestion, syllabus/PYQ retrieval service or automatic audience-designation decision was added in this increment.
- No prompt can guarantee virality. The intended improvement is more audience-fit, instructionally sound, original and visually demonstrable content.
