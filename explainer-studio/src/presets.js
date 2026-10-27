/**
 * Platform presets. Everything platform-specific lives here so a new target
 * (LinkedIn, Facebook Reels, 10-min deep dives…) is a data change, not a code change.
 *
 * Research basis (see docs/PLAYBOOK.md):
 *  - Instagram Reels: watch time / completion + replay, sends-per-reach (DM shares), likes-per-reach.
 *    -> 3-second hook, short runtime, loopable ending, a "send this to…" payoff.
 *  - YouTube long-form: viewer satisfaction + first-30-second retention are core inputs.
 *    -> promise + proof in the first 30 s, chapters, recap, and a clear next-video handoff.
 */

const concept = (n) => ({ role: `concept_${n}`, phases: ['visualise', 'animate', 'explain'], weight: 1.4,
  guide: 'Teach ONE idea: show the picture, run the animation step by step, then name it and give the intuition.' });

export const PLATFORMS = {
  // Narrated-diagram format (navy blueprint, a new diagram for every sentence, question -> chapters -> answer).
  // Uses its own script generator (src/blueprint) and renderer (remotion/blueprint).
  blueprint: {
    id: 'blueprint', label: 'Blueprint explainer — diagram story (≈2 min)', group: 'short', style: 'blueprint',
    targetSec: 110, aspect: '9:16', wps: 2.4, captionChunk: 3, sentenceMaxWords: 16, pauseScale: 0.7,
    plan: [{ role: 'question', phases: ['explain'], weight: 1 }, { role: 'chapter', phases: ['explain'], weight: 1 }, { role: 'answer', phases: ['explain'], weight: 1 }],
  },
  reel: {
    id: 'reel', label: 'Instagram Reel / YouTube Short', group: 'short',
    targetSec: 40, aspect: '9:16', wps: 2.3, captionChunk: 3, sentenceMaxWords: 13, pauseScale: 0.75,
    plan: [
      { role: 'hook', phases: ['visualise'], weight: 2, guide: 'Open ON the picture. First sentence is a bold claim, a surprising result, or a tight question. No greeting, no context-setting.' },
      { role: 'analogy', phases: ['visualise', 'animate'], weight: 2.5, guide: 'Explain the idea with ONE everyday analogy the viewer already knows, SHOWN on screen (icons/flow of the analogy). Say explicitly which real part each analogy part stands for ("the post office = NPCI").' },
      { role: 'demo', phases: ['animate'], weight: 3.5, guide: 'Now the real thing: run the actual mechanism step by step, one action per sentence, using the real names. Narration describes what the viewer is literally seeing right now.' },
      { role: 'payoff', phases: ['explain'], weight: 3, guide: 'Name the idea in plain words, give the one-line rule of thumb, then end with a line that loops back to the opening question and invites a send ("send this to the friend who...").' },
    ],
  },
  short_plus: {
    id: 'short_plus', label: 'Extended Reel / Short (~90 s)', group: 'short',
    targetSec: 90, aspect: '9:16', wps: 2.3, captionChunk: 3, sentenceMaxWords: 14, pauseScale: 0.8,
    plan: [
      { role: 'hook', phases: ['visualise'], weight: 1.5, guide: 'Bold claim or surprising result over a striking visual, in the first 3 seconds.' },
      { role: 'problem', phases: ['animate'], weight: 3, guide: 'Show the problem happening step by step.' },
      { role: 'why', phases: ['animate', 'explain'], weight: 3, guide: 'Slow the animation down; explain exactly why it happens.' },
      { role: 'fix_payoff', phases: ['visualise', 'animate', 'explain'], weight: 3.5, guide: 'Show the fix, run it, state the rule of thumb, loop back to the hook and invite a send.' },
    ],
  },
  yt_quick: {
    id: 'yt_quick', label: 'YouTube explainer (~3 min)', group: 'long',
    targetSec: 180, aspect: '16:9', wps: 2.4, captionChunk: 9, sentenceMaxWords: 18, pauseScale: 1,
    plan: [
      { role: 'hook', phases: ['visualise'], weight: 1.2, guide: 'Within 20 seconds: show the picture, state the promise ("by the end you will be able to..."), and give a reason to stay. Deliver a tiny taste of the payoff immediately.' },
      { role: 'setup', phases: ['visualise', 'explain'], weight: 1.4, guide: 'Introduce the pieces on screen with a real-world analogy before any jargon.' },
      concept(1), concept(2),
      { role: 'pitfall', phases: ['visualise', 'animate', 'explain'], weight: 1.4, guide: 'The classic mistake or edge case, shown live, then fixed.' },
      { role: 'recap', phases: ['explain'], weight: 1, guide: 'Three-sentence recap in plain words, then hand off to a next topic.' },
    ],
  },
  yt_deep: {
    id: 'yt_deep', label: 'YouTube deep-dive (~6 min)', group: 'long',
    targetSec: 360, aspect: '16:9', wps: 2.4, captionChunk: 9, sentenceMaxWords: 18, pauseScale: 1.05,
    plan: [
      { role: 'hook', phases: ['visualise'], weight: 1.1, guide: 'Picture + promise + taste of payoff inside 25 seconds.' },
      { role: 'setup', phases: ['visualise', 'explain'], weight: 1.3, guide: 'Analogy first, then the pieces on screen.' },
      concept(1), concept(2), concept(3), concept(4),
      { role: 'pitfall', phases: ['visualise', 'animate', 'explain'], weight: 1.4, guide: 'The classic mistake, shown live, then fixed.' },
      { role: 'real_world', phases: ['visualise', 'explain'], weight: 1.2, guide: 'Where this shows up in real systems / exams / labs.' },
      { role: 'recap', phases: ['explain'], weight: 1, guide: 'Plain-language recap and handoff to the next video.' },
    ],
  },
};

export const ASPECTS = {
  '16:9': { width: 1920, height: 1080 },
  '9:16': { width: 1080, height: 1920 },
};

export const FPS_OPTIONS = [30, 60];
export const THEME_IDS = ['dracula', 'cyberpunk', 'academic'];

/** Resolve the word budget for every scene in a plan. */
export function budgetPlan(platform) {
  const totalWords = Math.round(platform.targetSec * platform.wps);
  const sum = platform.plan.reduce((a, s) => a + s.weight, 0);
  return platform.plan.map((s) => {
    const words = Math.max(12, Math.round((totalWords * s.weight) / sum));
    return { ...s, words, sentences: Math.max(2, Math.round(words / 11)) };
  });
}
