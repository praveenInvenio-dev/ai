import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeForSpeech } from '../src/tts/normalize.js';
import { wordTimings } from '../src/timeline/build.js';
import { lintScene } from '../src/llm/lint.js';
import { scriptShare, LANGUAGE_IDS, languageBlock, voiceLangOf } from '../src/shared/languages.js';
import { VOICES } from '../src/tts/voices.js';

const HI = 'दो थ्रेड — एक ही काउंटर। दोनों 1 जोड़ते हैं... फिर क्या होगा?';

test('Indian-language narration is not run through the English number/unit expander', () => {
  assert.equal(normalizeForSpeech(HI, { lang: 'hi' }), 'दो थ्रेड, एक ही काउंटर। दोनों 1 जोड़ते हैं... फिर क्या होगा?');
  assert.equal(normalizeForSpeech('x = 5', { lang: 'en' }), 'x equals five');
});

test('word timings weigh Indic words by their real length (letters + vowel signs)', () => {
  const w = wordTimings('दो काउंटर।', 0, 3000);
  assert.ok(w[1].endMs - w[1].startMs > 2 * (w[0].endMs - w[0].startMs));
});

test('lint flags Latin-script narration in an Indian-language video, not English style rules', () => {
  const issues = lintScene([{ text: 'Two threads share one counter.' }, { text: HI }], { sentenceMaxWords: 14, role: 'hook', lang: 'hi' });
  assert.equal(issues.length, 1);
  assert.match(issues[0], /Hindi script/);
  assert.equal(scriptShare(HI, 'hi'), 1);
});

test('every Indian language has a voice and writer rules', () => {
  for (const id of LANGUAGE_IDS) {
    assert.ok(VOICES.some((v) => v.lang === voiceLangOf(id)), `no voice for ${id}`);
    if (id !== 'en') assert.match(languageBlock(id), /script/);
  }
});

test('Hinglish: Roman captions, Devanagari speech required', () => {
  const ok = lintScene([{ text: 'Socho tum 100 rupaye bhejte ho.', speech: 'सोचो तुम 100 रुपये भेजते हो।' }], { sentenceMaxWords: 14, role: 'hook', lang: 'hinglish' });
  assert.deepEqual(ok, []);
  const bad = lintScene([{ text: 'सोचो तुम पैसे भेजते हो।' }], { sentenceMaxWords: 14, role: 'hook', lang: 'hinglish' });
  assert.equal(bad.length, 2);
  assert.equal(voiceLangOf('hinglish'), 'hi');
});
