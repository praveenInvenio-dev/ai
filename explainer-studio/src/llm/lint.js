/**
 * Narration lint. Cheap deterministic checks that catch the "machine reading lines" failure modes
 * before we spend minutes on TTS + rendering. Issues are fed back to the LLM for ONE rewrite pass.
 */
const BANNED = [
  /\bin this video\b/i, /\blet'?s dive\b/i, /\bwelcome (back )?to\b/i, /\bas an ai\b/i, /\bdelve\b/i,
  /\bcertainly\b/i, /\bin conclusion\b/i, /\bunleash\b/i, /\bgame[- ]?changer\b/i, /\bin today'?s (fast|digital|modern)\b/i,
  /\bfurthermore\b/i, /\bmoreover\b/i, /\bit is important to note\b/i, /\bsimply put\b/i, /\bto summari[sz]e\b/i,
  /\bhey (guys|everyone)\b/i,
];
const CONTRACTION = /\b(\w+)('s|'re|'ll|'ve|'d|n't|'m)\b/i;

import { LANGUAGES, scriptShare } from '../shared/languages.js';

const words = (t) => t.trim().split(/\s+/).filter(Boolean);

export function lintScene(sentences, { sentenceMaxWords, role, lang = 'en' }) {
  const issues = [];
  const english = lang === 'en';
  sentences.forEach((s, i) => {
    const n = words(s.text).length;
    if (n > sentenceMaxWords + 6) issues.push(`Sentence ${i} has ${n} words (max ${sentenceMaxWords}). Split it.`);
    if (english) for (const re of BANNED) if (re.test(s.text)) issues.push(`Sentence ${i} uses a stock phrase (${re.source.replace(/\\b/g, '')}). Say it like a person.`);
    // Indian-language voices cannot read Latin letters reliably: the narration must be in the language's own script.
    if (!english && scriptShare(s.text, lang) < 0.85) issues.push(`Sentence ${i} is not written in ${LANGUAGES[lang].label} script (${LANGUAGES[lang].native}). Write it in ${LANGUAGES[lang].label}; transliterate English technical words into ${LANGUAGES[lang].native} script.`);
    if (/[*_`#]/.test(s.text)) issues.push(`Sentence ${i} contains markdown characters. Plain speech only.`);
    if (/\b(\w+)\s+\1\b/i.test(s.text)) issues.push(`Sentence ${i} repeats a word back-to-back.`);
  });
  const total = sentences.length;
  const withContraction = sentences.filter((s) => CONTRACTION.test(s.text)).length;
  if (english && total >= 4 && withContraction < Math.floor(total / 4)) issues.push('Too formal: use contractions (it\'s, don\'t, we\'ll, here\'s) like a person talking.');
  const starts = sentences.map((s) => words(s.text)[0]?.toLowerCase());
  for (let i = 2; i < starts.length; i++) if (starts[i] && starts[i] === starts[i - 1] && starts[i] === starts[i - 2]) issues.push(`Sentences ${i - 2}-${i} all start with "${starts[i]}". Vary the openings.`);
  const avg = sentences.reduce((a, s) => a + words(s.text).length, 0) / Math.max(1, total);
  const hasShort = sentences.some((s) => words(s.text).length <= 6);
  if (total >= 5 && avg > sentenceMaxWords * 0.7 && !hasShort) issues.push('Every sentence is long. Mix in some very short ones (3-6 words) for rhythm.');
  if (role !== 'recap' && total >= 6 && !sentences.some((s) => /[?？]/.test(s.text))) issues.push('Add at least one genuine question to the viewer.');
  return issues;
}
