# Research: technical-topic narration for Reels and YouTube Shorts (25 October 2026)

## Executive conclusion

There is no reliable formula that guarantees a viral video. YouTube explicitly says Shorts are ranked from viewer personalization and performance rather than a favored content format. Its published performance signals include whether viewers choose to watch or ignore a Short, average view duration, average percentage viewed, likes and post-watch survey results. Instagram leadership has identified watch time, likes per reach and sends per reach as important Reels ranking signals, with sends especially relevant to reaching people who do not already follow the creator. These signals are reasons to make videos clear, useful and worth finishing or sharing—not reasons to manipulate viewers.

## What this means for technical explainers

1. **Win the first second with substance.** Begin with a specific question, common misconception, visible result, practical pain point or honest counterintuitive fact. Do not begin with a logo, greeting, channel introduction or "today we will learn".
2. **Make the promise legible.** The first frame, first spoken line and title should agree about what the viewer will learn. Do not promise a dramatic result that the video cannot demonstrate.
3. **Show a concrete example early.** Use a tiny scenario, real code/output, before/after, physical demonstration, or worked number. A jargon-heavy topic needs a familiar consequence before the formal term.
4. **Use progression instead of filler.** Every scene should add a causal step, evidence, state change, comparison or reveal. Keep a small curiosity loop open, then resolve it honestly before the ending; never delay the answer solely to inflate watch time.
5. **Teach one idea per beat.** Speak in short natural sentences, explain each technical term when introduced, align narration and visuals, and pause briefly after a formula, code output, graph or key reveal.
6. **Make a useful takeaway shareable.** End with a compact rule of thumb, misconception corrected, diagnostic question, useful comparison or memorable worked result. It should be genuinely useful to send to a classmate or colleague; do not substitute "share this" for value.
7. **Use original work.** Create original narration, examples, point of view and visuals. Do not rely on copied clips, another creator's phrasing, trend audio that does not fit, or superficial edits to reused material.
8. **Make it mobile-first and accessible.** Use high-contrast captions, short on-screen labels, large type and safe margins around platform controls. The visual must still make sense when watched muted, without cluttering the frame with paragraphs.
9. **Keep technical accuracy non-negotiable.** Programmatically render equations, code, graphs, data and labels; do not trust generative image models to spell or calculate correctly. Distinguish real measurements from illustrative values and verify factual claims.
10. **Adapt the length to the goal.** A Short/Reel should deliver one complete idea, example and payoff. Long-form can add prerequisites, causal depth, implementation, limitations, trade-offs and multiple examples. Do not squeeze a full course into a 60-second clip.
11. **Treat trends as context, not a strategy.** A relevant cultural reference can help a viewer enter a technical topic, but the topic and explanation must stand on their own. No hashtag, posting time, audio choice or hook guarantees distribution.
12. **Learn from creator analytics.** Compare performance by topic, hook type, audience and length. For Shorts, inspect viewed-versus-swiped, average view duration and average percentage viewed; for Instagram, inspect average watch time, likes per reach and sends per reach. Improve the next batch from actual outcomes instead of assuming one universal benchmark.

## Platform distinction

### YouTube Shorts

YouTube's official Shorts discovery guidance says there is no favored Shorts format. Recommendations depend on viewer personalization and performance. It calls out choice to view/ignore, average view duration, average percentage viewed, likes and post-watch survey results. Topic interest, competition and seasonality can also affect reach. For search, metadata relevance matters; title and thumbnail/packaging must accurately set expectations. Shorts should be standalone and useful, while longer videos can support deeper discovery and learning.

### Instagram Reels

Instagram does not have one universal ranking algorithm across every surface. In a January 2025 explanation, Instagram head Adam Mosseri identified watch time, likes per reach and sends per reach as top ranking signals, noting that likes matter slightly more for connected audiences and sends slightly more for reaching non-followers. Instagram's creator Best Practices hub separately provides account-specific guidance about creation, engagement, reach and guidelines. Originality and recommendation eligibility matter; copied or low-quality republishing is not a dependable growth strategy.

## How the project prompts were changed

- The concept pitch prompt now explicitly asks for three genuinely different, audience-specific concepts, with a concrete hook, honest payoff, original angle and a visual demonstration.
- It instructs the model not to promise virality or invent stats, and to avoid artificial suspense, misleading clickbait and generic hype.
- The Short narration prompt now prioritizes a first-second hook, early example, useful staged reveals, a share-worthy takeaway, mobile-safe captions and original value.
- The long-form outline prompt now asks for immediate relevance, early proof, causal progression, a complete worked example and a conclusion that resolves the opening question.
- CTA instructions were softened: the lesson payoff comes first; a CTA is only included when the publishing objective calls for it.
- Existing JSON fields, API contracts, database schema, renderer routing and visual-engine integrations are unchanged.

## Official and primary sources

1. YouTube Help — Search & discovery tips for Shorts: https://support.google.com/youtube/answer/11914225
2. YouTube Help — How YouTube recommendations work: https://support.google.com/youtube/answer/16089387
3. YouTube Help — Understand content performance for YouTube's recommendation system: https://support.google.com/youtube/answer/16559650
4. Instagram / Meta — Best Practices education hub for creators (creation, engagement, reach and guidelines): https://about.fb.com/news/2024/10/best-practices-education-hub-creators-instagram/
5. Social Media Today reporting on Adam Mosseri's January 2025 explanation of Instagram's top Reels signals: https://www.socialmediatoday.com/news/instagram-shares-algorithm-insights-2025/738034/
6. YouTube Blog — Shorts deep dive on hooks and short-form storytelling: https://blog.youtube/creator-and-artist-stories/youtube-shorts-deep-dive/

## Important limitation

This is a prompt-quality update based on public platform guidance, not a guarantee of reach and not an analytics-trained recommendation model. To improve future topic selection, the product should capture creator-approved metrics and test outcomes (topic, hook type, length, viewed/swiped, retention curve, likes/reach, sends/reach, saves/shares where available) and use those observations to propose—not blindly dictate—the next experiments. Avoid comparing raw counts across accounts or treating correlation as proof of an algorithmic cause.
