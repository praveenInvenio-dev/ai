/**
 * Visual primitive catalog — the single source of truth.
 *  - The LLM prompt is generated from it       (llm/prompts.js)
 *  - The validator/sanitiser uses it           (llm/validate.js)
 *  - The Remotion renderer implements each type (remotion/primitives/*)
 * Add a primitive: add an entry here + a React component in remotion/primitives + register it in
 * remotion/primitives/index.jsx. No other file changes.
 *
 * Elements are layout-agnostic: they claim a SLOT, and the renderer maps slots to
 * rectangles per aspect ratio (so one script renders as both 16:9 and 9:16).
 */

import { PLANNER_ICONS } from './icons.js';

export const SLOTS = ['main', 'a', 'b', 'c', 'banner'];
export const SOLID_SHAPES = ['cube', 'cuboid', 'sphere', 'cylinder', 'cone', 'pyramid', 'prism', 'torus', 'hemisphere'];
export const SLOT_HELP =
  'main = the whole stage; a + b = two panels (left/right in widescreen, top/bottom in vertical); c = lower strip; banner = top strip. Use either one "main" element, or "a" + "b" (+ optional "c"/"banner").';

export const PHASES = ['visualise', 'animate', 'explain'];

export const CATALOG = {
  callout: {
    desc: 'Big punchy text bubble: a key phrase, number or verdict.',
    props: { text: 'string (max 6 words)', tone: 'info|warn|good|bad' },
    state: { text: 'string — replace the text', tone: 'info|warn|good|bad' },
  },
  code: {
    desc: 'Syntax-highlighted code that types itself in. Highlights one line at a time.',
    props: { language: 'string', lines: 'string[] (max 6 short lines)' },
    state: { active: 'number — 0-based index of line to highlight, -1 for none' },
  },
  threads: {
    desc: 'Parallel execution lanes. A token moves left→right along each lane through named checkpoints.',
    props: { lanes: '[{id:"T1",label:"Thread 1"}] (1-4)', checkpoints: 'string[] e.g. ["read","add","write"]' },
    state: {
      '<laneId>.progress': 'number 0..1 — position of the token',
      '<laneId>.status': 'idle|running|blocked|waiting|done|conflict',
      '<laneId>.note': 'string (max 3 words) shown under the lane',
    },
  },
  variable: {
    desc: 'A named value in a big box (a shared counter, a balance, a register). Numbers count up/down smoothly.',
    props: { label: 'string', value: 'number|string' },
    state: { value: 'number|string', flash: 'good|bad|none' },
  },
  lock: {
    desc: 'A lock / mutex / semaphore chip that changes state and shows a waiting queue.',
    props: { name: 'string' },
    state: { state: 'free|held|waiting', holder: 'string', queue: 'string — comma separated waiters' },
  },
  memory: {
    desc: 'A memory pool drawn as slots inside a boundary that can expand (heap, buffer, cache, allocation).',
    props: { title: 'string', capacity: 'number 2..24', maxCapacity: 'number 4..24' },
    state: { capacity: 'number — slots currently inside the boundary (animates the expansion)', fill: 'number — slots filled' },
  },
  flow: {
    desc: 'Boxes connected by arrows (pipeline, algorithm steps, biological process, reaction path).',
    props: { nodes: '[{id,label}] (2-6, label max 3 words)', edges: '[[fromId,toId]] optional, default sequential' },
    state: { active: 'node id', done: 'string — comma separated node ids' },
  },
  plot: {
    desc: 'Function graph that draws itself, with a movable marker.',
    props: { expr: 'string in x e.g. "x^2 - 4*x + 3"', xMin: 'number', xMax: 'number', yMin: 'number', yMax: 'number', label: 'string' },
    state: { draw: 'number 0..1 — how much of the curve is drawn', marker: 'number — x position of the marker' },
  },
  projectile: {
    desc: 'Physics projectile with trajectory and velocity-component vectors.',
    props: { speed: 'number m/s (5..60)', angleDeg: 'number (5..85)' },
    state: { t: 'number 0..1 — fraction of the flight completed', vectors: '0|1 — show velocity components' },
  },
  molecule: {
    desc: 'Atoms and bonds. Bonds can form one by one.',
    props: { atoms: '[{id,el,x,y}] x,y in 0..100', bonds: '[[atomIdA,atomIdB,order 1..3]]' },
    state: { bonds: 'number — how many bonds have formed so far (animates)', highlight: 'atom id' },
  },
  bars: {
    desc: 'Animated bar chart for comparisons.',
    props: { bars: '[{id,label,value}] (2-6)', unit: 'string', max: 'number optional' },
    state: { 'bars.<id>': 'number — new bar value' },
  },
  equation: {
    desc: 'Formula lines that reveal one by one, with one line in focus.',
    props: { lines: 'string[] (max 5), plain unicode math e.g. "F = m × a"' },
    state: { reveal: 'number — how many lines are visible (animates)', focus: 'number — 0-based line to emphasise' },
  },
  icons: {
    desc: 'Row of 2-5 concept icons with short labels that pop in and light up in order: a process, the actors in a system, a cause → effect chain. Works for ANY subject.',
    props: { items: `[{icon,label}] (2-5, label max 2 words). icon one of: ${Object.keys(PLANNER_ICONS).join(', ')}`, connect: 'true|false — line between icons (default true)' },
    state: { reveal: 'number — how many icons are visible (animates)', active: 'number — 0-based index of the icon being talked about' },
  },
  race: {
    desc: 'Before/after RACE: 2-4 bars fill at speeds set by their value (lower value = finishes first), each with a live stopwatch; the winner flashes. Use for any "slow way vs fast way" comparison.',
    props: { title: 'string (max 5 words, optional)', lanes: '[{label (max 3 words), value: number (time/cost, lower wins)}] 2-4', unit: 'string e.g. "s", "ms", " steps"' },
    state: { go: '0|1 — 1 (re)starts the race on that sentence (starts by itself if omitted)' },
  },
  stat: {
    desc: 'One BIG number that counts to its value on cue, with a label and a burst: "4x", "86%", "300,000 km/s". Use for the single most memorable number.',
    props: { from: 'number start value', max: 'number (optional, draws a progress ring)', prefix: 'string', suffix: 'string e.g. "x", "%"', decimals: '0-2', label: 'string (max 6 words)' },
    state: { value: 'number — counts to it (animates)' },
  },
  kinetic: {
    desc: 'Kinetic typography: 1-3 short lines whose words slam in; one key word gets a marker highlight. Use for the punchline/definition, NOT for whole sentences of narration.',
    props: { lines: 'string[] 1-3 lines, max 4 words each', highlight: 'one word from the lines' },
    state: { reveal: 'number — lines visible (animates)' },
  },
  molecule3d: {
    desc: 'Real 3D ball-and-stick molecule that slowly rotates. Prefer over "molecule" whenever the 3D shape matters (tetrahedral CH4, bent H2O, pyramidal NH3, benzene ring).',
    props: { atoms: '[{id,el,x,y,z}] x,y,z in 0..100 (z = depth, 50 = centre)', bonds: '[[atomIdA,atomIdB,order 1..3]]' },
    state: { bonds: 'number — how many bonds have formed so far (animates)', highlight: 'atom id', spin: '0|1 — rotate' },
  },
  solid3d: {
    desc: 'Lit 3D solid that slowly rotates, with up to 3 measurement labels. For geometry, mensuration, volume/surface area, crystal shapes, physics objects.',
    props: { shape: SOLID_SHAPES.join('|'), title: 'string (max 4 words)', labels: 'string[] (max 3) e.g. ["r = 3 cm","h = 7 cm","V = πr²h"]' },
    state: { wire: '0|1 — x-ray / wireframe view', scale: 'number 0.5..1.6 — grow/shrink (animates)', reveal: 'number — how many labels are visible', label: 'string — caption under the solid' },
  },
};
export const TYPES = Object.keys(CATALOG);

/** Compact text description of the catalog for the LLM prompt. */
export function catalogPrompt(preferred = []) {
  const order = [...new Set([...preferred, ...TYPES])];
  return order
    .map((t) => {
      const c = CATALOG[t];
      const p = Object.entries(c.props).map(([k, v]) => `${k}: ${v}`).join('; ');
      const s = Object.entries(c.state).map(([k, v]) => `${k}: ${v}`).join('; ');
      return `- ${t}${preferred.includes(t) ? ' (preferred)' : ''}: ${c.desc}\n    props → ${p}\n    events.set keys → ${s}`;
    })
    .join('\n');
}

/**
 * Worked example used as (1) a few-shot in the prompt and (2) the sample/fallback scene.
 * Narration shows the target voice: contractions, short sentences, "you", live commentary.
 */
export const EXAMPLE_SCENE = {
  sentences: [
    { text: "Watch what happens when two threads grab the same counter at the exact same time.", phase: 'animate' },
    { text: "Thread one reads. It sees zero.", phase: 'animate' },
    { text: "But before it writes anything back, thread two jumps in... and reads zero too.", phase: 'animate' },
    { text: "Now they both add one — and they're both about to write one.", phase: 'animate' },
    { text: "Two increments, and the counter says one. We've just lost an update.", phase: 'explain', emphasis: true, techNote: 'counter++  =  read → add → write   (NOT atomic)' },
  ],
  elements: [
    {
      type: 'threads', slot: 'a',
      props: { lanes: [{ id: 'T1', label: 'Thread 1' }, { id: 'T2', label: 'Thread 2' }], checkpoints: ['read', 'add', 'write'] },
      enter: { sentence: 0, offsetMs: 0 },
      events: [
        { at: { sentence: 1, offsetMs: 0 }, set: { T1: { progress: 0.18, status: 'running' } } },
        { at: { sentence: 1, offsetMs: 1400 }, set: { T1: { progress: 0.34, note: 'read 0' } } },
        { at: { sentence: 2, offsetMs: 300 }, set: { T2: { progress: 0.34, status: 'running', note: 'read 0' } } },
        { at: { sentence: 3, offsetMs: 0 }, set: { T1: { progress: 0.84, note: 'write 1' }, T2: { progress: 0.84, note: 'write 1' } } },
        { at: { sentence: 3, offsetMs: 1800 }, set: { T1: { progress: 1, status: 'done' }, T2: { progress: 1, status: 'conflict' } } },
      ],
    },
    {
      type: 'variable', slot: 'b',
      props: { label: 'counter', value: 0 },
      enter: { sentence: 0, offsetMs: 0 },
      events: [
        { at: { sentence: 3, offsetMs: 1800 }, set: { value: 1, flash: 'bad' } },
      ],
    },
    {
      type: 'callout', slot: 'c',
      props: { text: 'Lost update!', tone: 'bad' },
      enter: { sentence: 4, offsetMs: 0 },
    },
  ],
};
