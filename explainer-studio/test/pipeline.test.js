import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { sanitizeScene, fallbackScene } from '../src/llm/validate.js';
import { lintScene } from '../src/llm/lint.js';
import { checkSchema, sceneSchema } from '../src/llm/schemas.js';
import { EXAMPLE_SCENE, catalogPrompt } from '../src/shared/catalog.js';
import { PLATFORMS, budgetPlan } from '../src/presets.js';
import { buildTimeline, toSubtitles, toChapters, wordTimings } from '../src/timeline/build.js';
import { shapeSentence, pauseAfter } from '../src/tts/prosody.js';
import { assembleWav } from '../src/media/wav.js';
import { cleanSpeech, masterVoice, probeDurationMs } from '../src/media/ffmpeg.js';
import { JobQueue } from '../src/pipeline/queue.js';

const plan = budgetPlan(PLATFORMS.reel);

test('example scene satisfies the LLM schema and sanitiser', () => {
  assert.ok(checkSchema(sceneSchema, EXAMPLE_SCENE).ok);
  const { scene, warnings } = sanitizeScene(EXAMPLE_SCENE, { plan: { phases: ['animate', 'explain'] }, heading: 'x' });
  assert.equal(scene.elements.length, 3);
  assert.deepEqual(warnings, []);
});

test('sanitiser repairs hostile / sloppy LLM output', () => {
  const raw = {
    sentences: [{ text: '**Bold** `code` here', phase: 'explain' }, { text: 'Second.', phase: 'visualise' }],
    elements: [
      { type: 'nonsense', slot: 'a', props: {}, enter: { sentence: 0 } },
      { type: 'plot', slot: 'zzz', props: { expr: 'process.exit(1)', xMin: 5, xMax: 1 }, enter: { sentence: 99, offsetMs: -5 } },
      { type: 'callout', slot: 'main', props: { text: 'hi' }, enter: { sentence: 0 } },
    ],
  };
  const { scene, warnings } = sanitizeScene(raw, { plan: { phases: ['visualise', 'animate', 'explain'] }, heading: 'h' });
  assert.equal(scene.sentences[0].text, 'Bold code here');
  assert.equal(scene.sentences[1].phase, 'explain', 'phases never go backwards');
  const plot = scene.elements.find((e) => e.type === 'plot');
  assert.equal(plot.props.expr, 'x^2', 'unsafe expression replaced');
  assert.ok(plot.props.xMax > plot.props.xMin);
  assert.equal(plot.enter.sentence, 1, 'cue clamped into range');
  assert.ok(warnings.some((w) => /unknown element/.test(w)));
  const slots = scene.elements.map((e) => e.slot);
  assert.equal(new Set(slots).size, slots.length, 'no two overlapping elements share a slot');
});

test('fallback scene is always valid', () => {
  const s = fallbackScene({ heading: 'Anything', plan: plan[1] });
  assert.ok(s.sentences.length && s.elements.length);
});

test('lint catches robotic narration', () => {
  const bad = [
    { text: 'In this video we will furthermore delve into the topic of concurrency which is a very important concept in modern software.' },
    { text: 'It is important to note that threads exist.' },
  ];
  const issues = lintScene(bad, { sentenceMaxWords: 13, role: 'demo' });
  assert.ok(issues.length >= 3, issues.join('\n'));
  const good = EXAMPLE_SCENE.sentences;
  assert.deepEqual(lintScene(good, { sentenceMaxWords: 14, role: 'demo' }), []);
});

test('prompt catalog lists every primitive', () => {
  const p = catalogPrompt(['code']);
  for (const t of ['callout', 'threads', 'memory', 'plot', 'projectile', 'molecule']) assert.match(p, new RegExp(`- ${t}`));
});

test('prosody varies and stays in safe bounds', () => {
  const base = { lengthScale: 1, noiseScale: 0.7, noiseW: 0.85 };
  const a = shapeSentence({ text: 'Why does this fail?', index: 1, base });
  const b = shapeSentence({ text: 'It just does.', index: 2, base, emphasis: true });
  assert.notDeepEqual(a, b);
  for (const v of [a, b]) { assert.ok(v.lengthScale >= 0.82 && v.lengthScale <= 1.3); assert.ok(v.noiseScale >= 0.5 && v.noiseScale <= 0.9); }
  assert.ok(pauseAfter({ text: 'Ready?', isLastOfScene: false }) > pauseAfter({ text: 'Ready.', isLastOfScene: false }));
});

test('word timings tile the sentence exactly', () => {
  const w = wordTimings('Two threads. One counter.', 1000, 2000);
  assert.equal(w.length, 4);
  assert.equal(w[0].startMs, 1000);
  assert.ok(Math.abs(w.at(-1).endMs - 3000) <= 1);
  for (let i = 1; i < w.length; i++) assert.ok(w[i].startMs >= w[i - 1].endMs - 1);
});

test('queue honours concurrency, order, and cancellation', async () => {
  const log = [];
  let active = 0, peak = 0;
  const q = new JobQueue({ concurrency: 1, maxQueued: 2, runner: async (id) => { active++; peak = Math.max(peak, active); log.push(id); await new Promise((r) => setTimeout(r, 30)); active--; } });
  q.enqueue('a'); q.enqueue('b'); q.enqueue('c');
  assert.throws(() => q.enqueue('d'), /full/);
  assert.equal(q.cancel('c'), 'queued');
  await new Promise((r) => setTimeout(r, 150));
  assert.deepEqual(log, ['a', 'b']);
  assert.equal(peak, 1);
});

test('end-to-end audio: real FFmpeg clean → splice → master keeps timeline sample-accurate', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'tl-'));
  try {
    const sentences = [];
    for (let i = 0; i < 4; i++) {
      const raw = path.join(dir, `raw${i}.wav`), clean = path.join(dir, `s${i}.wav`);
      // synthetic "speech": a tone burst with silence padding on both ends (what TTS output looks like to the trimmer)
      execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'lavfi', '-i', `sine=frequency=${200 + i * 50}:duration=${0.8 + i * 0.3}`, '-af', 'adelay=300|300,apad=pad_dur=0.4', '-ar', '22050', '-ac', '1', raw]);
      await cleanSpeech(raw, clean);
      const dur = await probeDurationMs(clean);
      assert.ok(dur < (0.8 + i * 0.3) * 1000 + 250, `silence trimmed (got ${dur}ms)`);
      sentences.push({ text: `Sentence number ${i}?`, phase: 'animate', audioFile: clean, speechMs: dur, pauseMs: 250 });
    }
    const scenes = [{ role: 'demo', heading: 'h', phases: ['animate'], sentences, elements: [{ id: 'el0', type: 'callout', slot: 'main', props: { text: 'x', tone: 'info' }, enter: { sentence: 2, offsetMs: 100 }, events: [{ at: { sentence: 3, offsetMs: 0 }, set: { text: 'y' } }] }] }];
    const outline = { title: 't', onScreenHook: 'h', ctaLine: 'c' };
    const { timeline, segments } = buildTimeline({ outline, scenes, platform: PLATFORMS.reel });
    const spliced = path.join(dir, 'spliced.wav'), master = path.join(dir, 'master.wav');
    const { totalMs } = await assembleWav(segments, spliced);
    assert.ok(Math.abs(totalMs - timeline.totalMs) < 1, `splice ${totalMs} vs timeline ${timeline.totalMs}`);
    await masterVoice(spliced, master);
    const masterMs = await probeDurationMs(master);
    assert.ok(Math.abs(masterMs - timeline.totalMs) < 120, `master ${masterMs} vs timeline ${timeline.totalMs}`);
    // visuals are cued against real sentence starts
    const el = timeline.scenes[0].visualElements[0];
    assert.equal(Math.round(el.enterMs), Math.round(timeline.scenes[0].sentences[2].startMs + 100));
    assert.equal(Math.round(el.events[0].atMs), Math.round(timeline.scenes[0].sentences[3].startMs));
    assert.match(toSubtitles(timeline, 'srt'), /-->/);
    assert.match(toSubtitles(timeline, 'vtt'), /^WEBVTT/);
    assert.match(toChapters(timeline), /^0:00 h/);
  } finally { await fs.rm(dir, { recursive: true, force: true }); }
});
