/**
 * Voices that AI Story Studio's OTHER voice services offer (the same voices Concept Explainer lists), discovered at
 * run time and refreshed every minute:
 *  - tts (always on, STUDIO_TTS_URL)   Piper (Hindi/Telugu/Malayalam), Microsoft Edge neural voices for Hindi, Tamil,
 *                                       Telugu, Kannada, Malayalam, Marathi, Bengali, Gujarati + Indian English (needs
 *                                       internet), and - proxied - IndicF5 ("indic:") and Indic-Speak ("speak:") voices.
 *  - tts-chatterbox (CHATTERBOX_TTS_URL, profile "chatterbox")   reference voices; Multilingual V3 speaks English + Hindi.
 *  - backend Voice Lab (BACKEND_URL)    your cloned voices ("profile:<id>"), in the language they were made for.
 * Only voices whose service answered are listed, so nothing in the dropdown is a dead end.
 */
import { config } from '../config.js';
import { LANGUAGES } from '../shared/languages.js';

const B = { lengthScale: 1.0, noiseScale: 0.7, noiseW: 0.85 };
export const studioVoices = [];          // mutated in place, read by voices.js
export const studioStatus = { tts: undefined, chatterbox: undefined, profiles: undefined };

const LANG_NAMES = Object.values(LANGUAGES).map((l) => [l.label.toLowerCase(), l.id]);
/** "edge:hi-IN-SwaraNeural" / "indic:hi-in-swara" / "speak:hi-Kavya" / "hi_IN-rohan-medium" / accent "IN (Hindi)" → "hi". */
export function langOf(id, accent = '', fallbackText = '') {
  const m = String(id).replace(/^(edge|indic|speak):/i, '').match(/^([a-z]{2,3})[-_]/i);
  if (m && LANGUAGES[m[1].toLowerCase()]) return m[1].toLowerCase();
  const text = `${accent} ${fallbackText}`.toLowerCase();
  for (const [name, code] of LANG_NAMES) if (text.includes(name)) return code;
  if (/hinglish/.test(text)) return 'hi';
  return m && m[1].toLowerCase() === 'en' ? 'en' : null;
}
const kindOf = (id, engine) => (id.startsWith('indic:') ? 'indicf5' : id.startsWith('speak:') ? 'indicspeak' : id.startsWith('edge:') ? 'edge' : engine || 'piper');
const KIND_LABEL = { indicf5: 'IndicF5', indicspeak: 'Indic-Speak', edge: 'Edge neural · online', piper: 'Piper' };
const slug = (s) => String(s).toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '').slice(0, 60);

async function getJson(url) {
  const r = await fetch(url, { signal: AbortSignal.timeout(6000) });
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return r.json();
}

async function fromStudioTts() {
  const body = await getJson(`${config.tts.studioUrl}/api/voices`);
  const out = [];
  for (const v of body.voices || []) {
    if (!v?.id || v.installed === false) continue;
    const kind = kindOf(v.id, v.engine);
    const lang = v.languageCode && LANGUAGES[v.languageCode] ? v.languageCode : langOf(v.id, v.accent, v.label);
    if (!lang) continue;
    // IndicF5 refuses Latin-script text, so its "en-in-*" prompts cannot read an English script.
    if (kind === 'indicf5' && lang === 'en') continue;
    // English: only the Indian-accent voices here (US/UK English is already covered by Kokoro/Piper in this app).
    if (lang === 'en' && !/(^edge:en-IN|^speak:en-|\(IN|Indian)/i.test(`${v.id} ${v.accent} ${v.label}`)) continue;
    // "hi-in-swara (IndicF5)" → "Swara"
    const pretty = String(v.label).replace(/\s*\(IndicF5\)\s*$/, '').replace(/^[a-z]{2}-in-([a-z]+).*$/i, (_, n) => n[0].toUpperCase() + n.slice(1));
    out.push({ id: `st_${slug(v.id)}`, label: `${pretty}${v.gender ? ` — ${v.gender}` : ''} · ${KIND_LABEL[kind] || kind}`,
      engine: 'studio', kind, voice: v.id, lang, base: B });
  }
  return out;
}

async function fromChatterbox() {
  const body = await getJson(`${config.tts.chatterboxUrl}/api/voices`);
  const out = [];
  for (const v of body.voices || []) {
    const name = v.id || v.name;
    if (!name) continue;
    // Chatterbox Multilingual V3: English + Hindi among the languages this studio offers.
    for (const lang of ['en', 'hi']) {
      out.push({ id: `cb_${lang}_${slug(name)}`, label: `${name} · Chatterbox${lang === 'hi' ? ' Multilingual (Hindi)' : ''}`, engine: 'chatterbox', kind: 'chatterbox', voice: name, lang, base: B });
    }
  }
  return out;
}

async function fromVoiceLab() {
  const body = await getJson(`${config.tts.backendUrl}/api/tts/voices`);
  return (body.voices || []).filter((v) => String(v.id).startsWith('profile:')).map((v) => ({
    id: `vp_${slug(v.id.slice(8))}`, label: `${v.label} · my voice (Voice Lab)`, engine: 'profile', kind: 'profile', voice: v.id,
    lang: langOf('', v.accent, v.label) || 'en', base: B,
  }));
}

export async function refreshStudioVoices(log = () => {}) {
  const next = [];
  for (const [key, url, fn] of [['tts', config.tts.studioUrl, fromStudioTts], ['chatterbox', config.tts.chatterboxUrl, fromChatterbox], ['profiles', config.tts.backendUrl, fromVoiceLab]]) {
    if (!url) { studioStatus[key] = undefined; continue; }
    try { next.push(...(await fn())); studioStatus[key] = true; } catch (e) { studioStatus[key] = false; log(`voice catalogue: ${key} unavailable (${e.message})`); }
  }
  studioVoices.splice(0, studioVoices.length, ...next);
  return studioVoices;
}

export function startStudioVoiceRefresh() {
  refreshStudioVoices();
  return setInterval(() => refreshStudioVoices(), 60_000).unref();
}

/** Synthesis through the service that owns the voice. All three answer audio/wav. */
export async function synthStudio({ text, voice, params, outFile, signal, lang }) {
  const fs = await import('node:fs');
  const speed = +Math.min(1.25, Math.max(0.8, 1 / params.lengthScale)).toFixed(3);
  let url, body, name;
  if (voice.engine === 'chatterbox') {
    url = `${config.tts.chatterboxUrl}/api/tts`; name = 'Chatterbox (tts-chatterbox)';
    body = { text, voice: voice.voice, speed, language: voice.lang || lang || 'en' };
  } else if (voice.engine === 'profile') {
    url = `${config.tts.backendUrl}/api/tts/preview`; name = 'Voice Lab (backend)';
    body = { text, voice: voice.voice, speed };
  } else {
    url = `${config.tts.studioUrl}/api/tts`; name = `${KIND_LABEL[voice.kind] || 'tts'} (tts service)`;
    body = { text, voice: voice.voice, speed };
  }
  // IndicF5 / Indic-Speak / Chatterbox can be loading a multi-GB model: 503 = wait and retry, not fail.
  for (let attempt = 0; ; attempt++) {
    const sig = AbortSignal.any([...(signal ? [signal] : []), AbortSignal.timeout(600_000)]);
    let res;
    try {
      res = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body), signal: sig });
    } catch (e) {
      if (signal?.aborted) throw Object.assign(new Error('aborted'), { name: 'AbortError' });
      throw new Error(`${name} unreachable (${e.message})`);
    }
    if (res.status === 503 && attempt < 20) {
      await new Promise((resolve, reject) => {
        const t = setTimeout(resolve, 15_000);
        signal?.addEventListener('abort', () => { clearTimeout(t); reject(Object.assign(new Error('aborted'), { name: 'AbortError' })); }, { once: true });
      });
      continue;
    }
    if (!res.ok) {
      const raw = await res.text().catch(() => '');
      let msg = raw; try { msg = JSON.parse(raw).error || raw; } catch { /* plain */ }
      throw new Error(`${name} HTTP ${res.status}: ${String(msg).slice(0, 240)}`);
    }
    const buf = Buffer.from(await res.arrayBuffer());
    if (buf.length < 100) throw new Error(`${name} returned no audio`);
    fs.writeFileSync(outFile, buf);
    return;
  }
}
