import fs from 'node:fs';
import path from 'node:path';
import { config } from '../config.js';
import { studioVoices } from './studio-voices.js';

/**
 * Voice catalog. Every voice has a `lang` (see shared/languages.js) and an `engine`:
 *  - piper       in-image fallback (English), .onnx files in PIPER_VOICES_DIR
 *  - kokoro      AI Story Studio tts-kokoro service (KOKORO_TTS_URL) or the optional in-image model
 *  - indicspeak  AI Story Studio tts-indicspeak service (INDICSPEAK_TTS_URL): every Indian language + Indian English
 *  - indicf5     AI Story Studio tts-indic service (INDICF5_TTS_URL): most natural, Indian scripts only
 * Check each voice's own licence before commercial use.
 */
const B = { lengthScale: 1.0, noiseScale: 0.7, noiseW: 0.85 };
const G = { F: 'female', M: 'male' };

const SPEAK = {
  hi: [['Kavya', 'F'], ['Amit', 'M']], bn: [['Ishita', 'F'], ['Sourav', 'M']], mr: [['Anagha', 'F'], ['Chinmay', 'M']],
  ta: [['Anitha', 'F'], ['Arun', 'M']], te: [['Sravani', 'F'], ['Vamsi', 'M']], kn: [['Deepika', 'F'], ['Adarsh', 'M']],
  ml: [['Lakshmi', 'F'], ['Kiran', 'M']], gu: [['Dhara', 'F'], ['Parth', 'M']], pa: [['Kaur', 'F'], ['Manpreet', 'M']],
  or: [['Itishree', 'F'], ['Akash', 'M']], as: [['Prastuti', 'F'], ['Ankur', 'M']],
  en: [['Kavya', 'F'], ['Amit', 'M'], ['Deepika', 'F'], ['Arun', 'M']],
};
const F5 = {
  hi: [['hi-in-swara', 'Swara', 'F'], ['hi-in-madhur', 'Madhur', 'M']], bn: [['bn-in-tanishaa', 'Tanishaa', 'F'], ['bn-in-bashkar', 'Bashkar', 'M']],
  mr: [['mr-in-aarohi', 'Aarohi', 'F'], ['mr-in-manohar', 'Manohar', 'M']], ta: [['ta-in-pallavi', 'Pallavi', 'F'], ['ta-in-valluvar', 'Valluvar', 'M']],
  te: [['te-in-shruti', 'Shruti', 'F'], ['te-in-mohan', 'Mohan', 'M']], kn: [['kn-in-sapna', 'Sapna', 'F'], ['kn-in-gagan', 'Gagan', 'M']],
  ml: [['ml-in-sobhana', 'Sobhana', 'F'], ['ml-in-midhun', 'Midhun', 'M']], gu: [['gu-in-dhwani', 'Dhwani', 'F'], ['gu-in-niranjan', 'Niranjan', 'M']],
};

const STATIC_VOICES = [
  { id: 'ryan', label: 'Ryan — warm US male (Piper)', engine: 'piper', model: 'en_US-ryan-high', lang: 'en', base: B },
  { id: 'lessac', label: 'Lessac — clear US female (Piper)', engine: 'piper', model: 'en_US-lessac-high', lang: 'en', base: B },
  { id: 'jenny', label: 'Jenny — friendly UK female (Piper)', engine: 'piper', model: 'en_GB-jenny_dioco-medium', lang: 'en', base: B },
  { id: 'kokoro_michael', label: 'Michael — expressive US male (Kokoro)', engine: 'kokoro', voice: 'am_michael', lang: 'en', base: B },
  { id: 'kokoro_sarah', label: 'Sarah — expressive US female (Kokoro)', engine: 'kokoro', voice: 'af_sarah', lang: 'en', base: B },
  { id: 'kokoro_heart', label: 'Heart — warm US female (Kokoro)', engine: 'kokoro', voice: 'af_heart', lang: 'en', base: B },
  { id: 'kokoro_adam', label: 'Adam — deep US male (Kokoro)', engine: 'kokoro', voice: 'am_adam', lang: 'en', base: B },
  { id: 'kokoro_george', label: 'George — UK male (Kokoro)', engine: 'kokoro', voice: 'bm_george', lang: 'en', base: B },
  { id: 'kokoro_emma', label: 'Emma — UK female (Kokoro)', engine: 'kokoro', voice: 'bf_emma', lang: 'en', base: B },
  ...Object.entries(F5).flatMap(([lang, list]) => list.map(([id, n, g]) => ({
    id: `if5_${id.replace(/-/g, '_')}`, label: `${n} — ${G[g]} · IndicF5 (most natural)`, engine: 'indicf5', voice: `indic:${id}`, lang, base: B }))),
  ...Object.entries(SPEAK).flatMap(([lang, list]) => list.map(([n, g]) => ({
    id: `isp_${lang}_${n.toLowerCase()}`, label: `${n} — ${lang === 'en' ? 'Indian English ' : ''}${G[g]} · Indic-Speak`, engine: 'indicspeak', voice: `speak:${lang}-${n}`, lang, base: B }))),
];
/**
 * All voices = this image's own (Piper/Kokoro + direct Indic sidecars) + the studio's other services (refreshed every
 * minute). A sidecar voice reachable both directly and through the studio tts service is listed once (direct wins).
 */
export function allVoices() {
  const direct = new Set(STATIC_VOICES.filter((v) => v.voice && voiceAvailable(v)).map((v) => v.voice));
  return [...STATIC_VOICES, ...studioVoices.filter((v) => !direct.has(v.voice))];
}
/** Kept for callers that iterate the catalogue. */
export const VOICES = new Proxy([], { get: (_, k) => { const a = allVoices(); const v = a[k]; return typeof v === 'function' ? v.bind(a) : v; } });
export const getVoice = (id) => allVoices().find((v) => v.id === id) || STATIC_VOICES[0];

/** Last health-probe result per HTTP engine: true / false / undefined (not probed yet = assume up if configured). */
export const serviceUp = {};
const engineUrl = () => ({ kokoro: config.tts.kokoroUrl, indicspeak: config.tts.indicSpeakUrl, indicf5: config.tts.indicF5Url });

/** Can this voice speak in THIS deployment right now? */
export function voiceAvailable(v) {
  if (v.engine === 'studio' || v.engine === 'chatterbox' || v.engine === 'profile') return true; // listed only while up
  if (v.engine === 'indicspeak' || v.engine === 'indicf5') return !!engineUrl()[v.engine] && serviceUp[v.engine] !== false;
  if (v.engine === 'kokoro') {
    if (config.tts.kokoroUrl) return serviceUp.kokoro !== false;
    return fs.existsSync(path.join(config.tts.kokoroModelDir, 'kokoro-v1.0.onnx'));
  }
  return fs.existsSync(path.join(config.tts.voicesDir, `${v.model}.onnx`));
}

/** Default-voice preference by naturalness: IndicF5 > Edge neural > Indic-Speak > Chatterbox > Piper. */
const ENGINE_ORDER = { en: ['kokoro', 'chatterbox', 'piper', 'edge', 'indicspeak'], indic: ['indicf5', 'edge', 'indicspeak', 'chatterbox', 'piper'] };
const kind = (v) => v.kind || v.engine;

/** Voice used when a request names none, or one that cannot speak this language / is not running. */
export function defaultVoiceId(lang = 'en') {
  const forLang = allVoices().filter((v) => v.lang === lang);
  const pref = forLang.find((v) => v.id === config.tts.defaultVoice && voiceAvailable(v));
  if (pref) return pref.id;
  for (const eng of ENGINE_ORDER[lang === 'en' ? 'en' : 'indic']) {
    const v = forLang.find((x) => kind(x) === eng && voiceAvailable(x));
    if (v) return v.id;
  }
  return (forLang[0] || STATIC_VOICES[0]).id;
}

/** Probe the HTTP voice services every 30 s so the UI only offers voices that can speak right now. */
export function startVoiceProbe() {
  const probe = async () => {
    for (const [engine, url] of Object.entries(engineUrl())) {
      if (!url) continue;
      try {
        const r = await fetch(`${url}/health`, { signal: AbortSignal.timeout(2500) });
        serviceUp[engine] = r.ok;
      } catch { serviceUp[engine] = false; }
    }
  };
  probe();
  return setInterval(probe, 30_000).unref();
}
