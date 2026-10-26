/**
 * Text → "how a person would say it". TTS engines read what they are given, so we give them
 * speakable text: numbers as words, symbols as words, acronyms spelled, code operators spoken.
 */
const ONES = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve', 'thirteen', 'fourteen', 'fifteen', 'sixteen', 'seventeen', 'eighteen', 'nineteen'];
const TENS = ['', '', 'twenty', 'thirty', 'forty', 'fifty', 'sixty', 'seventy', 'eighty', 'ninety'];
const SCALES = [[1e9, 'billion'], [1e6, 'million'], [1e3, 'thousand']];

export function numberToWords(n) {
  n = Math.trunc(n);
  if (n < 0) return `minus ${numberToWords(-n)}`;
  if (n < 20) return ONES[n];
  if (n < 100) return TENS[Math.floor(n / 10)] + (n % 10 ? `-${ONES[n % 10]}` : '');
  if (n < 1000) return `${ONES[Math.floor(n / 100)]} hundred${n % 100 ? ` ${numberToWords(n % 100)}` : ''}`;
  for (const [v, name] of SCALES) if (n >= v) return `${numberToWords(Math.floor(n / v))} ${name}${n % v ? ` ${numberToWords(n % v)}` : ''}`;
  return String(n);
}
const digitsToWords = (s) => [...s].map((d) => ONES[+d]).join(' ');
const yearToWords = (y) => {
  const a = Math.floor(y / 100), b = y % 100;
  if (y >= 2000 && y < 2010) return numberToWords(y);
  return `${numberToWords(a)} ${b === 0 ? 'hundred' : b < 10 ? `oh ${ONES[b]}` : numberToWords(b)}`;
};

const SYMBOLS = [
  [/×/g, ' times '], [/÷/g, ' divided by '], [/−/g, ' minus '], [/≈/g, ' is approximately '], [/≠/g, ' is not equal to '],
  [/≤/g, ' is less than or equal to '], [/≥/g, ' is greater than or equal to '], [/→/g, ' to '], [/⇒/g, ' which gives '],
  [/√/g, ' square root of '], [/π/g, ' pi '], [/θ/g, ' theta '], [/Δ/g, ' delta '], [/μ/g, ' mu '], [/λ/g, ' lambda '], [/ω/g, ' omega '], [/α/g, ' alpha '], [/β/g, ' beta '],
  [/²/g, ' squared'], [/³/g, ' cubed'], [/°C/g, ' degrees Celsius'], [/°/g, ' degrees'],
];
const CODE_OPS = [
  [/(\w)\+\+/g, '$1 plus plus'], [/(\w)--/g, '$1 minus minus'], [/===/g, ' strictly equals '], [/==/g, ' equals equals '], [/!=/g, ' not equal to '],
  [/>=/g, ' greater than or equal to '], [/<=/g, ' less than or equal to '], [/&&/g, ' and '], [/\|\|/g, ' or '], [/=>/g, ' arrow '], [/->/g, ' to '], [/::/g, ' '],
  [/\bO\(1\)/g, 'O of one'], [/\bO\(n\^2\)/g, 'O of n squared'], [/\bO\(n log n\)/gi, 'O of n log n'], [/\bO\(log n\)/gi, 'O of log n'], [/\bO\(n\)/g, 'O of n'],
];
const UNITS = [
  [/(\d)\s?m\/s\b/g, '$1 meters per second'], [/(\d)\s?km\/h\b/g, '$1 kilometers per hour'], [/(\d)\s?kg\b/g, '$1 kilograms'],
  [/(\d)\s?ms\b/g, '$1 milliseconds'], [/(\d)\s?GB\b/g, '$1 gigabytes'], [/(\d)\s?MB\b/g, '$1 megabytes'], [/(\d)\s?KB\b/g, '$1 kilobytes'],
  [/(\d)\s?Hz\b/g, '$1 hertz'], [/(\d)\s?J\b/g, '$1 joules'], [/(\d)\s?N\b/g, '$1 newtons'], [/(\d)\s?mol\b/g, '$1 moles'],
];
const WORDS = [[/\be\.g\./gi, 'for example'], [/\bi\.e\./gi, 'that is'], [/\bvs\.?\b/gi, 'versus'], [/\betc\.?/gi, 'and so on'], [/\bapprox\./gi, 'approximately']];
const SPELL = new Set(['CPU', 'GPU', 'API', 'HTTP', 'HTTPS', 'URL', 'HTML', 'CSS', 'SSD', 'USB', 'DNS', 'TCP', 'UDP', 'IDE', 'JDK', 'SDK', 'ATP', 'DNA', 'RNA', 'ADP', 'NADH', 'IUPAC', 'MCQ', 'OS', 'IO', 'UI']);

const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export function normalizeForSpeech(input, { pronunciations = {}, lang = 'en' } = {}) {
  let t = String(input).replace(/[`*_#]/g, '').replace(/\s+/g, ' ').trim();
  // Indian languages: the English number/unit/operator expansion would inject English words into, say, Tamil.
  // The Indic voices read digits and the native script themselves; only tidy punctuation that trips them up.
  if (lang && lang !== 'en') {
    return t.replace(/\s*[—–]\s*/g, ', ').replace(/["“”‘’«»]/g, '').replace(/\s*\.\.\.\s*/g, '... ').replace(/\s+/g, ' ').trim();
  }

  // 1) domain pronunciations, longest token first
  const keys = Object.keys(pronunciations).sort((a, b) => b.length - a.length);
  for (const k of keys) {
    const word = /^\w+$/.test(k);
    const re = new RegExp(word ? `\\b${escapeRe(k)}\\b` : escapeRe(k), 'g');
    t = t.replace(re, ` ${pronunciations[k]} `);
  }
  // 2) code operators and complexity classes
  for (const [re, to] of CODE_OPS) t = t.replace(re, to);
  // 3) units attached to numbers, then math symbols
  for (const [re, to] of UNITS) t = t.replace(re, to);
  for (const [re, to] of SYMBOLS) t = t.replace(re, to);
  for (const [re, to] of WORDS) t = t.replace(re, to);
  t = t.replace(/(\d)\s?%/g, '$1 percent').replace(/(\w)\s?=\s?(\w)/g, '$1 equals $2').replace(/\s=\s/g, ' equals ').replace(/\s\+\s/g, ' plus ').replace(/(\d)\s?\+\s?(\d)/g, '$1 plus $2').replace(/(\d)\s?\/\s?(\d)/g, '$1 over $2');
  t = t.replace(/(\d)\s?-\s?(\d)/g, '$1 to $2');
  t = t.replace(/\b(\d+)(st|nd|rd|th)\b/g, (_, n) => {
    const w = numberToWords(+n);
    return w.replace(/one$/, 'first').replace(/two$/, 'second').replace(/three$/, 'third').replace(/five$/, 'fifth').replace(/eight$/, 'eighth').replace(/nine$/, 'ninth').replace(/twelve$/, 'twelfth').replace(/y$/, 'ieth').replace(/([^aeiouy])$/, '$1th');
  });
  // 4) numbers
  t = t.replace(/\b(\d{1,3}(?:,\d{3})+)\b/g, (m) => String(m.replace(/,/g, '')));
  t = t.replace(/(\d+)\.(\d+)/g, (_, a, b) => `${numberToWords(+a)} point ${digitsToWords(b)}`);
  t = t.replace(/\b(1[1-9]\d\d|20\d\d)\b(?!\s*(?:kg|m|s|joules|newtons))/g, (m) => yearToWords(+m));
  t = t.replace(/\b\d+\b/g, (m) => (m.length > 12 ? digitsToWords(m) : numberToWords(+m)));
  // 5) acronyms we want spelled out
  t = t.replace(/\b[A-Z]{2,6}\b/g, (m) => (SPELL.has(m) ? [...m].join(' ') : m));
  // 6) punctuation for breath
  t = t.replace(/\s*[—–]\s*/g, ', ').replace(/\s*\.\.\.\s*/g, '... ').replace(/…/g, '...').replace(/[()\[\]{}<>]/g, ' ').replace(/\s+([,.;!?])/g, '$1').replace(/\s+/g, ' ').trim();
  return t;
}
