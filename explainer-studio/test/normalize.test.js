import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeForSpeech, numberToWords } from '../src/tts/normalize.js';
import { DOMAINS } from '../src/domains/index.js';

const n = (t, d = 'cs') => normalizeForSpeech(t, { pronunciations: DOMAINS[d].pronunciations });

test('numbers', () => {
  assert.equal(numberToWords(0), 'zero');
  assert.equal(numberToWords(42), 'forty-two');
  assert.equal(numberToWords(1234), 'one thousand two hundred thirty-four');
  assert.equal(n('It takes 3.14 seconds'), 'It takes three point one four seconds');
  assert.equal(n('In 1999 it shipped'), 'In nineteen ninety-nine it shipped');
});
test('code and symbols are spoken', () => {
  assert.match(n('counter++ is not atomic'), /counter plus plus is not atomic/);
  assert.match(n('a && b'), /a and b/);
  assert.match(n('Runs in O(n^2) time'), /O of n squared/);
  assert.match(n('x² + y²'), /x squared plus y squared/);
});
test('units and acronyms', () => {
  assert.match(n('speed is 25 m/s', 'physics_jee'), /twenty-five meters per second/);
  assert.match(n('The CPU runs the JVM'), /C P U runs the J V M/);
  assert.match(n('Energy in ATP', 'biology_neet'), /A T P/);
});
test('no markdown, no stray symbols', () => {
  const out = n('Use `lock` — then **release** it (always)...');
  assert.ok(!/[`*()]/.test(out), out);
  assert.match(out, /\.\.\./);
});
