/**
 * Narration languages. `script` is the Unicode block the narration must mostly be written in (used to check the
 * LLM output), `notes` steer the writer towards how people actually TALK in that language on YouTube/Instagram.
 */
export const LANGUAGES = {
  en: { id: 'en', label: 'English', native: 'English', script: null, notes: '' },
  // Hinglish: how most Indian creators actually explain things. Captions in Roman script (what viewers type and read),
  // the voice reads a Devanagari copy of the same sentence (Hindi voices read Devanagari far better than Roman).
  hinglish: { id: 'hinglish', label: 'Hinglish', native: 'Hinglish', script: null, voiceLang: 'hi',
    notes: 'Everyday Hindi-English mix exactly as Indian YouTubers/Instagram creators speak ("Socho tum ₹100 bhejte ho... ab dekho kya hota hai"). Technical words stay English (payment, server, bank account, thread).' },
  hi: { id: 'hi', label: 'Hindi', native: 'हिन्दी', script: /[\u0900-\u097F]/, notes: 'Conversational Hindustani as spoken in explainer videos, not textbook Sanskritised Hindi.' },
  bn: { id: 'bn', label: 'Bengali', native: 'বাংলা', script: /[\u0980-\u09FF]/, notes: 'Natural spoken Bengali (Kolkata standard colloquial).' },
  mr: { id: 'mr', label: 'Marathi', native: 'मराठी', script: /[\u0900-\u097F]/, notes: 'Everyday spoken Marathi.' },
  ta: { id: 'ta', label: 'Tamil', native: 'தமிழ்', script: /[\u0B80-\u0BFF]/, notes: 'Friendly spoken Tamil as used by popular Tamil explainer channels; not literary Tamil.' },
  te: { id: 'te', label: 'Telugu', native: 'తెలుగు', script: /[\u0C00-\u0C7F]/, notes: 'Natural spoken Telugu.' },
  kn: { id: 'kn', label: 'Kannada', native: 'ಕನ್ನಡ', script: /[\u0C80-\u0CFF]/, notes: 'Natural spoken Kannada (Bengaluru/Mysuru usage).' },
  ml: { id: 'ml', label: 'Malayalam', native: 'മലയാളം', script: /[\u0D00-\u0D7F]/, notes: 'Natural spoken Malayalam.' },
  gu: { id: 'gu', label: 'Gujarati', native: 'ગુજરાતી', script: /[\u0A80-\u0AFF]/, notes: 'Natural spoken Gujarati.' },
  pa: { id: 'pa', label: 'Punjabi', native: 'ਪੰਜਾਬੀ', script: /[\u0A00-\u0A7F]/, notes: 'Natural spoken Punjabi in Gurmukhi script.' },
  or: { id: 'or', label: 'Odia', native: 'ଓଡ଼ିଆ', script: /[\u0B00-\u0B7F]/, notes: 'Natural spoken Odia.' },
  as: { id: 'as', label: 'Assamese', native: 'অসমীয়া', script: /[\u0980-\u09FF]/, notes: 'Natural spoken Assamese.' },
};
export const LANGUAGE_IDS = Object.keys(LANGUAGES);
/** Which voices can speak a language (Hinglish is spoken by the Hindi voices). */
export const voiceLangOf = (lang) => LANGUAGES[lang]?.voiceLang || lang || 'en';
const DEVANAGARI = /[\u0900-\u097F]/;
/** Share of letters in Latin script / in Devanagari (for the Hinglish checks). */
export function latinShare(text) {
  const letters = [...String(text)].filter((c) => /\p{L}/u.test(c));
  return letters.length ? letters.filter((c) => /[A-Za-z]/.test(c)).length / letters.length : 1;
}
export function devanagariShare(text) {
  const letters = [...String(text)].filter((c) => /\p{L}/u.test(c));
  return letters.length ? letters.filter((c) => DEVANAGARI.test(c)).length / letters.length : 0;
}
export const isIndic = (lang) => lang && lang !== 'en';

/** Share of letters written in the language's own script (0..1). English always returns 1. */
export function scriptShare(text, lang) {
  const L = LANGUAGES[lang];
  if (!L?.script) return 1;
  const letters = [...String(text)].filter((c) => /\p{L}/u.test(c));
  if (!letters.length) return 1;
  return letters.filter((c) => L.script.test(c)).length / letters.length;
}

/** Writing rules appended to every LLM call for a non-English video. */
export function languageBlock(lang) {
  const L = LANGUAGES[lang];
  if (!L || lang === 'en') return '';
  if (lang === 'hinglish') return `
LANGUAGE — the video is in HINGLISH: ${L.notes}
- "text" (captions) = the sentence in Hinglish written in ROMAN letters, casual spelling Indians use ("Socho", "kya hota hai", "bilkul"). Never formal/textbook Hindi words when people normally say the English word.
- "speech" (REQUIRED on every sentence) = the SAME sentence written in Devanagari for the Hindi voice, English words transliterated ("सोचो तुम ₹100 भेजते हो... अब देखो क्या होता है", "पेमेंट", "सर्वर"). Same words, same order - only the script changes.
- title, onScreenHook, ctaLine, headings: Roman Hinglish. Use "tum/aap" friendly tone, Indian daily-life analogy (chai stall, kirana shop, dabbawala, train ticket).
- The English style rules above (contractions etc.) do not apply; keep short spoken sentences and live pointing at the animation.
- hashtags: Hinglish/English, without '#'.`;
  return `
LANGUAGE — the whole video is in ${L.label} (${L.native}). ${L.notes}
- Every narration sentence ("text"), title, titleOptions, onScreenHook, ctaLine, heading, goal, analogy and logline MUST be written in ${L.label} using the ${L.native} script.
- English technical words that Indians normally say in English (CPU, thread, DNA, velocity, function) stay in English but are WRITTEN IN ${L.native} SCRIPT (transliterated), so the voice can read them. Never write Latin letters in "text".
- Write numbers as digits; never mix two scripts inside one word.
- The English style rules above (contractions etc.) do not apply; keep the same spirit: short spoken sentences, "you/we" (the friendly form), live pointing at the animation, one analogy from Indian daily life.
- On-screen visual labels (element props: labels, node names, code) may stay short English or symbols; "techNote" may use English and symbols.
- hashtags: mix ${L.label} and English, without '#'.`;
}
