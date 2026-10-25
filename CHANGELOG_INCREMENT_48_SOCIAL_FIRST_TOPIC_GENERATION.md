# Increment 48 — Research-informed social-first topic generation

## Research basis
Reviewed official YouTube Shorts discovery and recommendation guidance, YouTube's content-performance guidance and Shorts storytelling material, Instagram's creator Best Practices hub, and reporting of Instagram leadership's stated Reels signals.

## Changes
- Updated `TutorialPrompts.conceptsSystemPrompt()` to pitch three distinct, audience-specific, original concepts with clear hooks, concrete proof and an honest payoff.
- Added explicit safeguards against false virality promises, fabricated stats, manipulative delay, misleading clickbait, copied creator content and unsafe overconfidence.
- Updated short-form narration/visual guidance to start with substance, show an example early, progress through useful reveals, support muted viewing, and include a useful share-worthy takeaway without forced engagement bait.
- Updated long-form outline guidance to use an immediate hook, early evidence, causal progression, coherent worked example and an ending that resolves the opening question.
- Made CTAs subordinate to the lesson payoff and conditional on the publishing goal.
- Added `RESEARCH_SOCIAL_VIDEO_TECHNICAL_NARRATION_2026.md` with findings, sources, implications and limitations.

## Compatibility
Prompt-only changes in `TutorialPrompts.java` plus documentation. No DTO/JSON schema additions, API changes, database migrations, Angular changes, renderer routing changes or external services added.

## Verification
- ZIP archive integrity checked after packaging.
- Java source structure reviewed; full Maven/Angular build and end-to-end generation were not run in this environment. Validate with the project's normal build and test commands before deployment.

## Expected outcome
Better topic framing and narration plans for Reels/Shorts: clearer opening value, faster demonstration, stronger educational payoff, original examples and more shareable takeaways. Reach remains audience- and platform-dependent; no prompt can guarantee virality.
