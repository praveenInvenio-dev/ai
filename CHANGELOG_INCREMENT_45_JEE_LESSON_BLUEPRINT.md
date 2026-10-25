# Increment 45 — JEE Concept Explainer Blueprint

## What changed
- Expanded the existing JEE track in Concept Studio with a dedicated JEE Learning Path section.
- Added controls for target exam (Main, Advanced, or both), syllabus level (Class 11, Class 12, combined, or foundation revision), explanation depth, and practice-question type.
- Added options for a formula/key-result recap and common-trap/checkpoint.
- Defined a topic-adaptive JEE teaching sequence: learning objective/hook, prerequisites, intuition, concept with diagram/equation, derivation/conditions/units where relevant, worked problem, original exam-style practice, common trap, and recap.
- Added separate guidance for Physics, Mathematics, and Chemistry.
- Added safeguards: do not fabricate formulas, derivations, answer keys, syllabus claims, or label generated questions as actual previous-year questions without a verified source. Encourage checking dimensions, units, assumptions and arithmetic.
- The JEE settings are appended to the existing `instructions` request field; no API contract or database migration is required.
- Existing tracks and non-JEE instructions remain unchanged.

## Validation
- Source-level checks performed.
- Full Angular/Java builds were not run in this environment; dependencies are not installed.
