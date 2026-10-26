import { PLATFORMS, budgetPlan } from '../src/presets.js';
import { EXAMPLE_SCENE } from '../src/shared/catalog.js';
import { sanitizeScene } from '../src/llm/validate.js';

/** Hand-written reel script (what the LLM would return), run through the production sanitiser. */
export function buildSampleScenes() {
  const platform = PLATFORMS.reel;
  const plan = budgetPlan(platform);
  const hook = {
    sentences: [
      { text: 'Two threads. One shared counter. Both hit plus one at the exact same time.', phase: 'visualise' },
      { text: 'So... what does the counter say?', phase: 'visualise', emphasis: true },
    ],
    elements: [
      { type: 'threads', slot: 'a', props: { lanes: [{ id: 'T1', label: 'Thread 1' }, { id: 'T2', label: 'Thread 2' }], checkpoints: ['read', 'add', 'write'] }, enter: { sentence: 0 },
        events: [{ at: { sentence: 0, offsetMs: 1800 }, set: { T1: { progress: 0.1, status: 'running' }, T2: { progress: 0.1, status: 'running' } } }] },
      { type: 'variable', slot: 'b', props: { label: 'counter', value: 0 }, enter: { sentence: 0, offsetMs: 400 }, events: [] },
    ],
  };
  const payoff = {
    sentences: [
      { text: "That's a race condition. Counter plus plus is really three steps: read, add, write.", phase: 'explain', techNote: 'counter++  ≠  atomic  ·  read → add → write' },
      { text: 'The fix? Make them one unbreakable step. Wrap it in a lock.', phase: 'explain', techNote: 'synchronized  |  ReentrantLock  |  AtomicInteger' },
      { text: 'One thread holds the lock, the other waits. Now two increments really give two.', phase: 'explain', emphasis: true },
      { text: "Who's guarding your door? Send this to a Java friend who skips the lock.", phase: 'explain' },
    ],
    elements: [
      { type: 'code', slot: 'a', props: { language: 'java', lines: ['int counter = 0;', '', 'synchronized (lock) {', '    counter++;', '}'] }, enter: { sentence: 0 },
        events: [{ at: { sentence: 0, offsetMs: 1800 }, set: { active: 3 } }, { at: { sentence: 1, offsetMs: 500 }, set: { active: 2 } }, { at: { sentence: 2, offsetMs: 2200 }, set: { active: 3 } }] },
      { type: 'lock', slot: 'b', props: { name: 'lock' }, enter: { sentence: 1, offsetMs: 1200 },
        events: [{ at: { sentence: 2, offsetMs: 0 }, set: { state: 'held', holder: 'T1', queue: 'T2' } }, { at: { sentence: 2, offsetMs: 2400 }, set: { state: 'free', holder: '', queue: '' } }] },
      { type: 'callout', slot: 'c', props: { text: 'Lock it. Always.', tone: 'good' }, enter: { sentence: 3, offsetMs: 0 } },
    ],
  };


  const raws = [hook, EXAMPLE_SCENE, payoff];
  const heads = ['Same time, same counter', 'Watch the update vanish', 'Why it breaks, how to fix'];
  const scenes = raws.map((raw, i) => {
    const { scene, warnings } = sanitizeScene(raw, { plan: plan[i], heading: heads[i] });
    if (warnings.length) console.warn(`scene ${i}:`, warnings.join('; '));
    return { role: plan[i].role, heading: heads[i], phases: plan[i].phases, ...scene };
  });
  const outline = { title: 'Why counter++ breaks with two threads', onScreenHook: 'counter++ is NOT one step', ctaLine: 'Send this to a Java friend who skips the lock' };
  return { outline, scenes, platform, meta: { topic: 'Java Multithreading: Race Conditions', domain: 'cs', voice: 'ryan' } };
}
