import { CATALOG, TYPES, SLOTS, PHASES, EXAMPLE_SCENE, SOLID_SHAPES } from '../shared/catalog.js';
import { resolveIcon } from '../shared/icons.js';
import { clamp } from '../shared/cues.js';

const ID = /^[A-Za-z0-9_]{1,16}$/;
const SAFE_CHARS = /^[0-9a-zA-Z_+\-*/^().,\s]{1,80}$/;
const MATH_IDENT = new Set(['x', 'sin', 'cos', 'tan', 'asin', 'acos', 'atan', 'sinh', 'cosh', 'tanh', 'sqrt', 'cbrt', 'abs', 'exp', 'log', 'log10', 'log2', 'pow', 'pi', 'e', 'floor', 'ceil', 'round', 'sign', 'min', 'max']);
/** Only x, numbers, operators and a whitelist of math functions/constants. No property access. */
const SAFE_EXPR = { test: (s) => SAFE_CHARS.test(s) && !/[A-Za-z_]\s*\./.test(s) && (s.match(/[A-Za-z_]\w*/g) || []).every((id) => MATH_IDENT.has(id)) };
const num = (v, d) => (Number.isFinite(Number(v)) ? Number(v) : d);
const str = (v, d = '', max = 80) => (typeof v === 'string' ? v.trim().slice(0, max) : v == null ? d : String(v).slice(0, max));
const arr = (v) => (Array.isArray(v) ? v : []);

/** Strip anything a TTS engine or caption renderer would choke on. */
export function cleanText(t) {
  return String(t ?? '')
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/[*_`#>]/g, '')
    .replace(/\[(.*?)\]\(.*?\)/g, '$1')
    .replace(/\s+/g, ' ')
    .trim();
}

function sanitizeProps(type, p = {}) {
  p = p && typeof p === 'object' ? p : {};
  switch (type) {
    case 'callout':
      return { text: str(p.text, 'Look closely', 48), tone: ['info', 'warn', 'good', 'bad'].includes(p.tone) ? p.tone : 'info' };
    case 'code': {
      const lines = arr(p.lines).map((l) => String(l).slice(0, 60)).slice(0, 6);
      return { language: str(p.language, 'text', 16), lines: lines.length ? lines : ['// …'] };
    }
    case 'threads': {
      let lanes = arr(p.lanes)
        .map((l, i) => ({ id: ID.test(l?.id) ? l.id : `T${i + 1}`, label: str(l?.label, `Thread ${i + 1}`, 18) }))
        .slice(0, 4);
      if (!lanes.length) lanes = [{ id: 'T1', label: 'Thread 1' }, { id: 'T2', label: 'Thread 2' }];
      const seen = new Set();
      lanes = lanes.map((l, i) => { while (seen.has(l.id)) l.id = `${l.id}${i}`; seen.add(l.id); return l; });
      const checkpoints = arr(p.checkpoints).map((c) => str(c, '', 12)).filter(Boolean).slice(0, 5);
      return { lanes, checkpoints };
    }
    case 'variable':
      return { label: str(p.label, 'value', 24), value: typeof p.value === 'number' ? p.value : str(p.value, '0', 16) };
    case 'lock':
      return { name: str(p.name, 'lock', 20) };
    case 'memory': {
      const maxCapacity = clamp(Math.round(num(p.maxCapacity, 16)), 4, 24);
      return { title: str(p.title, 'Memory', 24), capacity: clamp(Math.round(num(p.capacity, 4)), 2, maxCapacity), maxCapacity };
    }
    case 'flow': {
      let nodes = arr(p.nodes).map((n, i) => ({ id: ID.test(n?.id) ? n.id : `n${i}`, label: str(n?.label, `Step ${i + 1}`, 24) })).slice(0, 6);
      if (nodes.length < 2) nodes = [{ id: 'n0', label: 'Start' }, { id: 'n1', label: 'End' }];
      const ids = new Set(nodes.map((n) => n.id));
      const edges = arr(p.edges).filter((e) => Array.isArray(e) && ids.has(e[0]) && ids.has(e[1])).slice(0, 10);
      return { nodes, edges };
    }
    case 'plot': {
      let expr = str(p.expr, 'x^2', 80).replace(/\*\*/g, '^');
      if (!SAFE_EXPR.test(expr)) expr = 'x^2';
      const xMin = num(p.xMin, -5), xMax = num(p.xMax, 5);
      const yMin = num(p.yMin, -2), yMax = num(p.yMax, 10);
      return { expr, xMin: Math.min(xMin, xMax - 1), xMax: Math.max(xMax, xMin + 1), yMin: Math.min(yMin, yMax - 1), yMax: Math.max(yMax, yMin + 1), label: str(p.label, `y = ${expr}`, 32) };
    }
    case 'projectile':
      return { speed: clamp(num(p.speed, 25), 5, 60), angleDeg: clamp(num(p.angleDeg, 45), 5, 85) };
    case 'molecule': {
      const atoms = arr(p.atoms).map((a, i) => ({ id: ID.test(a?.id) ? a.id : `a${i}`, el: str(a?.el, 'C', 3), x: clamp(num(a?.x, 20 + i * 25), 5, 95), y: clamp(num(a?.y, 50), 5, 95) })).slice(0, 12);
      const ids = new Set(atoms.map((a) => a.id));
      const bonds = arr(p.bonds).filter((b) => Array.isArray(b) && ids.has(b[0]) && ids.has(b[1])).map((b) => [b[0], b[1], clamp(Math.round(num(b[2], 1)), 1, 3)]).slice(0, 16);
      return atoms.length >= 2 ? { atoms, bonds } : { atoms: [{ id: 'a0', el: 'H', x: 30, y: 50 }, { id: 'a1', el: 'O', x: 50, y: 50 }, { id: 'a2', el: 'H', x: 70, y: 50 }], bonds: [['a0', 'a1', 1], ['a1', 'a2', 1]] };
    }
    case 'bars': {
      let bars = arr(p.bars).map((b, i) => ({ id: ID.test(b?.id) ? b.id : `b${i}`, label: str(b?.label, `Bar ${i + 1}`, 14), value: num(b?.value, 0) })).slice(0, 6);
      if (bars.length < 2) bars = [{ id: 'b0', label: 'A', value: 3 }, { id: 'b1', label: 'B', value: 6 }];
      return { bars, unit: str(p.unit, '', 10), ...(Number.isFinite(Number(p.max)) ? { max: Number(p.max) } : {}) };
    }
    case 'equation': {
      const lines = arr(p.lines).map((l) => String(l).slice(0, 48)).slice(0, 5);
      return { lines: lines.length ? lines : ['y = f(x)'] };
    }
    case 'icons': {
      let items = arr(p.items).map((it, i) => {
        const label = str(it?.label, `Step ${i + 1}`, 18);
        return { icon: resolveIcon(str(it?.icon, '', 24).toLowerCase(), label), label };
      }).slice(0, 5);
      if (items.length < 2) items = [{ icon: 'lightbulb', label: 'Idea' }, { icon: 'check', label: 'Result' }];
      return { items, connect: p.connect !== false };
    }
    case 'race': {
      let lanes = arr(p.lanes).map((l, i) => ({ label: str(l?.label, `Option ${i + 1}`, 22), value: clamp(num(l?.value, (i + 1) * 2), 0.1, 1e6) })).slice(0, 4);
      if (lanes.length < 2) lanes = [{ label: 'Before', value: 8 }, { label: 'After', value: 2 }];
      return { title: str(p.title, '', 40), lanes, unit: str(p.unit, '', 8) };
    }
    case 'stat': {
      const out = { from: num(p.from, 0), prefix: str(p.prefix, '', 4), suffix: str(p.suffix, '', 8), decimals: clamp(Math.round(num(p.decimals, 0)), 0, 2), label: str(p.label, '', 48) };
      if (Number.isFinite(Number(p.max))) out.max = Number(p.max);
      return out;
    }
    case 'kinetic': {
      const lines = arr(p.lines).map((l) => String(l).split(/\s+/).slice(0, 5).join(' ').slice(0, 40)).filter(Boolean).slice(0, 3);
      return { lines: lines.length ? lines : ['Here\'s the idea'], highlight: str(p.highlight, '', 24) };
    }
    case 'molecule3d': {
      const atoms = arr(p.atoms).map((a, i) => ({ id: ID.test(a?.id) ? a.id : `a${i}`, el: str(a?.el, 'C', 3), x: clamp(num(a?.x, 20 + i * 25), 0, 100), y: clamp(num(a?.y, 50), 0, 100), z: clamp(num(a?.z, 50), 0, 100) })).slice(0, 16);
      const ids = new Set(atoms.map((a) => a.id));
      const bonds = arr(p.bonds).filter((b) => Array.isArray(b) && ids.has(b[0]) && ids.has(b[1]) && b[0] !== b[1]).map((b) => [b[0], b[1], clamp(Math.round(num(b[2], 1)), 1, 3)]).slice(0, 20);
      return atoms.length >= 2 ? { atoms, bonds } : { atoms: [{ id: 'O', el: 'O', x: 50, y: 42, z: 50 }, { id: 'H1', el: 'H', x: 30, y: 62, z: 50 }, { id: 'H2', el: 'H', x: 70, y: 62, z: 50 }], bonds: [['O', 'H1', 1], ['O', 'H2', 1]] };
    }
    case 'solid3d': {
      const shape = SOLID_SHAPES.includes(p.shape) ? p.shape : 'cube';
      const labels = arr(p.labels).map((l) => String(l).slice(0, 22)).filter(Boolean).slice(0, 3);
      return { shape, title: str(p.title, '', 28), labels };
    }
    default:
      return p;
  }
}

/**
 * Normalise an LLM scene into something the renderer can never choke on.
 * Returns { scene, warnings }.
 */
export function sanitizeScene(raw, { plan, heading }) {
  const warnings = [];
  let sentences = arr(raw.sentences)
    .map((s) => ({
      text: cleanText(s.text),
      phase: PHASES.includes(s.phase) ? s.phase : plan.phases[0],
      techNote: s.techNote ? cleanText(s.techNote).slice(0, 70) : undefined,
      emphasis: !!s.emphasis,
    }))
    .filter((s) => s.text.length > 1);
  if (!sentences.length) throw new Error('scene has no usable sentences');

  // Phase order must be visualise → animate → explain, and limited to the plan's phases.
  const rank = (p) => PHASES.indexOf(p);
  let floor = 0;
  sentences = sentences.map((s) => {
    let p = s.phase;
    if (!plan.phases.includes(p)) {
      p = plan.phases.reduce((best, cand) => (Math.abs(rank(cand) - rank(p)) < Math.abs(rank(best) - rank(p)) ? cand : best), plan.phases[0]);
    }
    if (rank(p) < floor) p = PHASES[floor];
    floor = Math.max(floor, rank(p));
    return { ...s, phase: p };
  });

  const n = sentences.length;
  const fixCue = (c, dflt) => {
    if (!c || typeof c !== 'object') return dflt;
    return { sentence: clamp(Math.round(num(c.sentence, 0)), 0, n - 1), offsetMs: clamp(Math.round(num(c.offsetMs, 0)), 0, 8000) };
  };

  // Elements
  const occupied = {}; // slot -> [fromSentence, toSentence][]
  const overlaps = (slot, a, b) => (occupied[slot] || []).some(([x, y]) => a <= y && b >= x);
  const elements = [];
  let idCounter = 0;
  for (const el of arr(raw.elements)) {
    if (!TYPES.includes(el?.type)) { warnings.push(`dropped unknown element type "${el?.type}"`); continue; }
    const enter = fixCue(el.enter, { sentence: 0, offsetMs: 0 });
    const exit = el.exit ? fixCue(el.exit) : undefined;
    const from = enter.sentence;
    const to = exit ? Math.max(exit.sentence, from) : n - 1;
    let slot = SLOTS.includes(el.slot) ? el.slot : 'main';
    const clash = (s) => overlaps(s, from, to) || (s === 'main' && Object.keys(occupied).some((k) => overlaps(k, from, to))) || (s !== 'main' && overlaps('main', from, to));
    if (clash(slot)) {
      const alt = ['a', 'b', 'c', 'banner'].find((s) => !clash(s));
      if (!alt) { warnings.push(`dropped ${el.type}: no free slot`); continue; }
      warnings.push(`moved ${el.type} from "${slot}" to "${alt}" (slot clash)`);
      slot = alt;
    }
    (occupied[slot] ||= []).push([from, to]);

    const props = sanitizeProps(el.type, el.props);
    const events = arr(el.events)
      .map((e) => ({ at: fixCue(e?.at, { sentence: 0, offsetMs: 0 }), set: e?.set && typeof e.set === 'object' ? e.set : {} }))
      .filter((e) => Object.keys(e.set).length)
      .sort((x, y) => x.at.sentence - y.at.sentence || x.at.offsetMs - y.at.offsetMs);
    elements.push({ id: `el${idCounter++}`, type: el.type, slot, props, enter, ...(exit ? { exit } : {}), events });
  }

  if (!elements.length) {
    warnings.push('no valid visuals — inserted fallback callout');
    elements.push({ id: 'el0', type: 'callout', slot: 'main', props: { text: heading || 'Watch this', tone: 'info' }, enter: { sentence: 0, offsetMs: 0 }, events: [] });
  }
  return { scene: { sentences, elements }, warnings };
}

/** Deterministic last-resort scene so a job never dies because the LLM had a bad day. */
export function fallbackScene({ heading, plan }) {
  const base = structuredClone(EXAMPLE_SCENE);
  base.sentences = base.sentences.map((s) => ({ ...s, phase: plan.phases.includes(s.phase) ? s.phase : plan.phases[0] }));
  base.elements[2].props.text = String(heading || 'Look closely').slice(0, 40);
  return sanitizeScene(base, { plan, heading }).scene;
}
