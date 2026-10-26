import test from 'node:test';
import assert from 'node:assert/strict';
import { langOf } from '../src/tts/studio-voices.js';

test('studio voice ids / accents map to explainer languages', () => {
  assert.equal(langOf('edge:hi-IN-SwaraNeural'), 'hi');
  assert.equal(langOf('indic:ta-in-pallavi'), 'ta');
  assert.equal(langOf('speak:kn-Deepika'), 'kn');
  assert.equal(langOf('hi_IN-rohan-medium'), 'hi');
  assert.equal(langOf('profile:x', 'Marathi', 'My voice'), 'mr');
  assert.equal(langOf('edge:en-IN-NeerjaNeural'), 'en');
  assert.equal(langOf('weird', 'US', 'Guy'), null);
});
