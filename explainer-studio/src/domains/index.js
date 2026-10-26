/**
 * Domain packs. To support a new subject (JEE Maths, NEET Biology, Chemistry…)
 * add an entry here. Nothing else in the pipeline needs to change.
 *  - styleNotes     : injected into the writer prompt
 *  - preferred      : primitives the visual director should reach for first
 *  - pronunciations : spoken forms for the TTS normaliser (exact token -> spoken)
 */
export const DOMAINS = {
  cs: {
    id: 'cs', label: 'Programming / CS',
    audience: 'software engineering students and working developers',
    styleNotes: 'Use tiny concrete code snippets (max 4 lines). Name the exact line that causes the behaviour. Prefer "imagine two cashiers sharing one till" style analogies.',
    preferred: ['code', 'threads', 'race', 'flow', 'stat', 'memory', 'variable', 'lock', 'icons', 'kinetic', 'callout', 'bars'],
    pronunciations: { JVM: 'J V M', JIT: 'jit', API: 'A P I', CPU: 'C P U', GPU: 'G P U', RAM: 'ram', SQL: 'sequel', HTTP: 'H T T P', JSON: 'jason', OOP: 'O O P', 'i++': 'i plus plus', 'counter++': 'counter plus plus', 'x++': 'x plus plus' },
  },
  physics_jee: {
    id: 'physics_jee', label: 'Physics (JEE / NEET)',
    audience: 'Class 11-12 students preparing for JEE and NEET',
    styleNotes: 'Start from the physical picture, then the formula. Always say units aloud. Mention the common exam trap once. Keep numbers friendly (round values).',
    preferred: ['projectile', 'plot', 'equation', 'stat', 'race', 'solid3d', 'icons', 'kinetic', 'bars', 'flow', 'callout'],
    pronunciations: { 'm/s': 'meters per second', 'm/s²': 'meters per second squared', 'm/s^2': 'meters per second squared', 'N·m': 'newton meters', JEE: 'J E E', NEET: 'neet' },
  },
  math_jee: {
    id: 'math_jee', label: 'Mathematics (JEE)',
    audience: 'Class 11-12 students preparing for JEE',
    styleNotes: 'Build intuition with a graph before the algebra. Say expressions in natural speech ("x squared plus two x"). One trick per scene.',
    preferred: ['plot', 'equation', 'solid3d', 'stat', 'kinetic', 'callout', 'flow', 'bars'],
    pronunciations: { JEE: 'J E E', 'dy/dx': 'd y by d x', 'f(x)': 'f of x', 'f′(x)': 'f prime of x' },
  },
  chemistry_neet: {
    id: 'chemistry_neet', label: 'Chemistry (JEE / NEET)',
    audience: 'Class 11-12 students preparing for JEE and NEET',
    styleNotes: 'Show atoms and bonds forming before naming the reaction. Say formulas as spoken words (H two O).',
    preferred: ['molecule3d', 'molecule', 'flow', 'equation', 'stat', 'kinetic', 'bars', 'callout'],
    pronunciations: { H2O: 'H two O', CO2: 'C O two', NaCl: 'sodium chloride', O2: 'O two', H2: 'H two', CH4: 'C H four', NH3: 'N H three', NEET: 'neet', JEE: 'J E E' },
  },
  biology_neet: {
    id: 'biology_neet', label: 'Biology (NEET)',
    audience: 'Class 11-12 students preparing for NEET',
    styleNotes: 'Use process flows and everyday analogies. Teach the order of steps with a memorable hook. Mention the exam favourite once.',
    preferred: ['icons', 'flow', 'molecule3d', 'stat', 'kinetic', 'bars', 'equation', 'callout', 'plot'],
    pronunciations: { ATP: 'A T P', DNA: 'D N A', RNA: 'R N A', mRNA: 'm R N A', NEET: 'neet' },
  },
};
DOMAINS.general = {
  id: 'general', label: 'General concept (any topic)',
  audience: 'curious viewers on YouTube and Instagram with no special background',
  styleNotes: 'Explain like a great science communicator: one everyday analogy, one surprising fact, no jargon without a plain-words translation. Show the actors of the idea as icons before explaining how they interact.',
  preferred: ['icons', 'race', 'stat', 'kinetic', 'flow', 'bars', 'callout', 'equation'],
  pronunciations: { AI: 'A I', GDP: 'G D P', UPI: 'U P I', EV: 'E V' },
};
export const DOMAIN_IDS = Object.keys(DOMAINS);
