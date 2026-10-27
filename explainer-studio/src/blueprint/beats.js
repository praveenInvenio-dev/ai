/**
 * "Blueprint" explainer beats - the visual vocabulary of the narrated diagram style (navy blueprint, outlined devices,
 * flowing dots, big numbers, one key phrase on screen). Every narration sentence carries ONE beat, so the picture
 * changes with what is being said. Beats are generic and parametric: the LLM fills them with the topic's own nouns.
 */
export const OBJECTS = ['box', 'ball', 'car', 'plane', 'rocket', 'person', 'apple', 'satellite', 'block', 'bird'];
export const SHAPES = ['cell', 'atom', 'circle', 'leaf', 'heart', 'earth', 'eye', 'neuron', 'box'];
export const DEVICES = ['phone', 'server', 'laptop', 'user', 'store', 'truck', 'db', 'box', 'camera', 'card', 'bike', 'house', 'bank', 'coin', 'cloud', 'chip', 'doc'];

export const BEATS = {
  question: { use: 'the opening/closing question card (who asks + the question)', props: '{asker:"INTERVIEWER"|"YOUR FRIEND"|"EXAM", text, highlight: words from text}' },
  stat: { use: 'one big number that sets the stakes or the scale', props: '{value:"5.5", unit:"CRORE", label:"people watching at once"}' },
  devices: { use: 'many of the same thing (phones, servers, shops, users); "lit" of them highlighted', props: `{device: ${DEVICES.join('|')}, count:1-24, lit:0-24, label, tag?:{title,value,sub}}` },
  pipeline: { use: 'a chain of steps / a path data or goods take, with dots flowing along it', props: '{steps:[{label, sub}] 2-5, active: index of the step being talked about}' },
  fanout: { use: 'one thing serving many (a server to crores of phones) or many hitting one ("fanin")', props: `{source:{label, sub}, target:{device, count, label}, mode:"fanout"|"fanin"}` },
  bars: { use: 'progress / buffer / time rows compared (LIVE vs YOUR PHONE)', props: '{rows:[{label, total:4-12, filled:0-12}], mark?:{row, at, label}}' },
  chart: { use: 'load or value over time: a spike, a smooth curve, or steady', props: '{title, mode:"spike"|"smooth"|"steady"|"rise", badge?: "STEADY"|"OVERLOAD"|...}' },
  compare: { use: 'two options side by side (bad way vs good way)', props: '{left:{title, lines:[1-3]}, right:{title, lines:[1-3]}, winner:"left"|"right"}' },
  checklist: { use: 'the answer / recap points ticked one by one', props: '{title, items:[2-4], checked: how many ticked so far}' },
  term: { use: 'naming the technical term once it was shown ("Geofence": one-line meaning)', props: '{term, definition}' },
  // ── science / maths (JEE, NEET, school) ──
  forces: { use: 'free-body diagram: an object with labelled force arrows (laws of motion, lift/weight/thrust/drag, friction, tension)', props: `{object: ${OBJECTS.join('|')}, ground: true|false, forces:[{label:"Weight mg", dir:"up|down|left|right|up-right|up-left|down-right|down-left" or angle in degrees, size:1-3, tone:"accent|good|bad"}] 1-5}` },
  motion: { use: 'an object actually moving: uniform, accelerating, slowing, falling, projectile, circular orbit', props: `{object: ${OBJECTS.join('|')}, mode:"uniform|accelerate|decelerate|fall|projectile|circular", label:"v = 20 m/s"}` },
  graph: { use: 'a plotted relation drawn live (v-t, x-t, F-x, pressure-volume, y = x²...)', props: '{x:"TIME (s)", y:"VELOCITY (m/s)", curve:"linear|quadratic|inverse|exp|decay|sine|constant|log", label:"slope = acceleration", point: 0..1 position of a marked point}' },
  equation: { use: 'the formula, built and explained symbol by symbol', props: '{expr:"F = m × a" (spaces between tokens), highlight:"a", terms:[{sym:"F", meaning:"force in newtons"}] 0-4}' },
  flow: { use: 'fluid/air flowing around or through something (wing lift, Bernoulli, blood in a vessel, pipe narrowing)', props: '{shape:"airfoil|ball|pipe", top:"FAST · LOW PRESSURE", bottom:"SLOW · HIGH PRESSURE", arrow:"LIFT"}' },
  parts: { use: 'a labelled diagram: the thing and its parts with callouts (cell organelles, atom, leaf, heart, eye, Earth layers, neuron)', props: `{shape: ${SHAPES.join('|')}, title, labels:[2-6 part names], focus: index of the part being talked about}` },
  cycle: { use: 'a repeating process in stages around a loop (water cycle, Krebs, heart beat, carbon cycle, engine strokes)', props: '{stages:[3-6 short names], active: index}' },
  reaction: { use: 'a chemical reaction / transformation: reactants -> products with the condition over the arrow', props: '{left:"2H₂ + O₂", right:"2H₂O", over:"spark", note:"energy released"}' },
  wave: { use: 'a wave: sound, light, AC, SHM; shows wavelength and amplitude', props: '{label:"sound wave", wavelength:"λ", amplitude:"A", speed: 1-3}' },
};
export const BEAT_TYPES = Object.keys(BEATS);

const DIRS = { right: 0, 'up-right': 45, up: 90, 'up-left': 135, left: 180, 'down-left': 225, down: 270, 'down-right': 315 };
const str = (v, d = '', max = 60) => (typeof v === 'string' ? v.trim().slice(0, max) : v == null ? d : String(v).slice(0, max));
const int = (v, d, lo, hi) => { const n = Math.round(Number(v)); return Number.isFinite(n) ? Math.min(hi, Math.max(lo, n)) : d; };
const arr = (v) => (Array.isArray(v) ? v : []);

/** Make any LLM beat renderable. Unknown/broken beats become a `term` card from the phrase so a frame is never empty. */
export function sanitizeBeat(b, fallbackText = '') {
  const base = { kicker: str(b?.kicker, '', 28).toUpperCase(), phrase: str(b?.phrase, '', 48), highlight: str(b?.highlight, '', 24), same: !!b?.same };
  const type = BEAT_TYPES.includes(b?.type) ? b.type : 'term';
  switch (type) {
    case 'question': return { ...base, type, asker: str(b.asker, 'QUESTION', 20).toUpperCase(), text: str(b.text, fallbackText, 80), qhighlight: str(b.qhighlight ?? b.highlight, '', 40) };
    case 'stat': return { ...base, type, value: str(b.value, '1', 10), unit: str(b.unit, '', 12).toUpperCase(), label: str(b.label, '', 48) };
    case 'devices': {
      const count = int(b.count, 6, 1, 24);
      const tag = b.tag && typeof b.tag === 'object' ? { title: str(b.tag.title, '', 16).toUpperCase(), value: str(b.tag.value, '', 14), sub: str(b.tag.sub, '', 20).toUpperCase() } : null;
      return { ...base, type, device: DEVICES.includes(b.device) ? b.device : 'box', count, lit: int(b.lit, 0, 0, count), label: str(b.label, '', 36), tag };
    }
    case 'pipeline': {
      let steps = arr(b.steps).map((s) => ({ label: str(s?.label ?? s, 'Step', 18), sub: str(s?.sub, '', 22) })).slice(0, 5);
      if (steps.length < 2) steps = [{ label: 'Input', sub: '' }, { label: 'Output', sub: '' }];
      return { ...base, type, steps, active: int(b.active, steps.length - 1, 0, steps.length - 1) };
    }
    case 'fanout': return { ...base, type, mode: b.mode === 'fanin' ? 'fanin' : 'fanout',
      source: { label: str(b.source?.label ?? b.source, 'Server', 18), sub: str(b.source?.sub, '', 22) },
      target: { device: DEVICES.includes(b.target?.device) ? b.target.device : 'phone', count: int(b.target?.count, 12, 3, 24), label: str(b.target?.label, '', 30) } };
    case 'bars': {
      let rows = arr(b.rows).map((r) => { const total = int(r?.total, 8, 2, 12); return { label: str(r?.label, '', 14).toUpperCase(), total, filled: int(r?.filled, total, 0, total) }; }).slice(0, 3);
      if (!rows.length) rows = [{ label: 'NOW', total: 8, filled: 5 }];
      const mark = b.mark && typeof b.mark === 'object' ? { row: int(b.mark.row, 0, 0, rows.length - 1), at: int(b.mark.at, 0, 0, 12), label: str(b.mark.label, '', 18).toUpperCase() } : null;
      return { ...base, type, rows, mark };
    }
    case 'chart': return { ...base, type, title: str(b.title, '', 24).toUpperCase(), mode: ['spike', 'smooth', 'steady', 'rise'].includes(b.mode) ? b.mode : 'spike', badge: str(b.badge, '', 14).toUpperCase() };
    case 'compare': {
      const side = (s, t) => ({ title: str(s?.title, t, 18), lines: arr(s?.lines).map((l) => str(l, '', 30)).filter(Boolean).slice(0, 3) });
      return { ...base, type, left: side(b.left, 'Before'), right: side(b.right, 'After'), winner: b.winner === 'left' ? 'left' : 'right' };
    }
    case 'checklist': {
      const items = arr(b.items).map((i) => str(i, '', 40)).filter(Boolean).slice(0, 4);
      return { ...base, type, title: str(b.title, '', 30), items: items.length ? items : [fallbackText.slice(0, 40)], checked: int(b.checked, items.length || 1, 0, 4) };
    }
    case 'forces': {
      let forces = arr(b.forces).map((f) => ({ label: str(f?.label, '', 22), dir: typeof f?.dir === 'number' ? f.dir : DIRS[f?.dir] ?? (Number.isFinite(Number(f?.dir)) ? Number(f.dir) : 90), size: int(f?.size, 2, 1, 3), tone: ['accent', 'good', 'bad'].includes(f?.tone) ? f.tone : 'accent' })).slice(0, 5);
      if (!forces.length) forces = [{ label: 'Force', dir: 0, size: 2, tone: 'accent' }];
      return { ...base, type, object: OBJECTS.includes(b.object) ? b.object : 'box', ground: b.ground !== false && b.object !== 'plane' && b.object !== 'bird' && b.object !== 'satellite' ? !!(b.ground ?? true) : false, forces };
    }
    case 'motion': return { ...base, type, object: OBJECTS.includes(b.object) ? b.object : 'ball', mode: ['uniform', 'accelerate', 'decelerate', 'fall', 'projectile', 'circular'].includes(b.mode) ? b.mode : 'uniform', label: str(b.label, '', 24) };
    case 'graph': return { ...base, type, x: str(b.x, 'x', 20).toUpperCase(), y: str(b.y, 'y', 22).toUpperCase(), curve: ['linear', 'quadratic', 'inverse', 'exp', 'decay', 'sine', 'constant', 'log'].includes(b.curve) ? b.curve : 'linear', label: str(b.label, '', 30), point: Number.isFinite(Number(b.point)) ? Math.min(1, Math.max(0, Number(b.point))) : null };
    case 'equation': return { ...base, type, expr: str(b.expr, 'y = f(x)', 40), eqHighlight: str(b.eqHighlight ?? b.highlight, '', 12), terms: arr(b.terms).map((t) => ({ sym: str(t?.sym, '', 8), meaning: str(t?.meaning, '', 34) })).filter((t) => t.sym).slice(0, 4) };
    case 'flow': return { ...base, type, shape: ['airfoil', 'ball', 'pipe'].includes(b.shape) ? b.shape : 'airfoil', top: str(b.top, '', 26).toUpperCase(), bottom: str(b.bottom, '', 26).toUpperCase(), arrow: str(b.arrow, '', 12).toUpperCase() };
    case 'parts': {
      const labels = arr(b.labels).map((l) => str(l, '', 18)).filter(Boolean).slice(0, 6);
      return { ...base, type, shape: SHAPES.includes(b.shape) ? b.shape : 'circle', title: str(b.title, '', 24), labels: labels.length >= 2 ? labels : ['Part A', 'Part B'], focus: int(b.focus, -1, -1, 5) };
    }
    case 'cycle': {
      const stages = arr(b.stages).map((l) => str(l, '', 16)).filter(Boolean).slice(0, 6);
      return { ...base, type, stages: stages.length >= 3 ? stages : ['Step 1', 'Step 2', 'Step 3'], active: int(b.active, 0, 0, 5) };
    }
    case 'reaction': return { ...base, type, left: str(b.left, 'A + B', 30), right: str(b.right, 'C', 30), over: str(b.over, '', 16), note: str(b.note, '', 40) };
    case 'wave': return { ...base, type, label: str(b.label, '', 24).toUpperCase(), wavelength: str(b.wavelength, 'λ', 10), amplitude: str(b.amplitude, 'A', 10), speed: int(b.speed, 2, 1, 3) };
    default: return { ...base, type: 'term', term: str(b?.term, base.phrase || 'Key idea', 24), definition: str(b?.definition, '', 60) };
  }
}

export function beatsPrompt() {
  return BEAT_TYPES.map((t) => `- ${t}: ${BEATS[t].use}. props ${BEATS[t].props}`).join('\n');
}
