import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { sanitizeBeat } from '../src/blueprint/beats.js';

test('blueprint beats: broken LLM beats become renderable', () => {
  assert.equal(sanitizeBeat({ type: 'nonsense', phrase: 'hello world' }).type, 'term');
  const d = sanitizeBeat({ type: 'devices', device: 'spaceship', count: 99, lit: 120 });
  assert.equal(d.device, 'box'); assert.equal(d.count, 24); assert.equal(d.lit, 24);
  const p = sanitizeBeat({ type: 'pipeline', steps: ['A'] });
  assert.equal(p.steps.length, 2);
  const c = sanitizeBeat({ type: 'checklist', items: ['a', 'b', 'c'], checked: 9, same: true });
  assert.equal(c.checked, 4); assert.equal(c.same, true);
});

test('blueprint science beats: forces, motion, graph, parts, reaction are sanitised', () => {
  const f = sanitizeBeat({ type: 'forces', object: 'plane', forces: [{ label: 'Lift', dir: 'up', size: 9 }, { label: 'Drag', dir: 'left' }, { label: 'N', dir: 30 }] });
  assert.equal(f.ground, false); assert.deepEqual(f.forces.map((x) => x.dir), [90, 180, 30]); assert.equal(f.forces[0].size, 3);
  assert.equal(sanitizeBeat({ type: 'motion', mode: 'teleport' }).mode, 'uniform');
  assert.equal(sanitizeBeat({ type: 'graph', curve: 'zigzag', point: 7 }).point, 1);
  assert.equal(sanitizeBeat({ type: 'parts', shape: 'cell', labels: ['Nucleus'] }).labels.length, 2);
  assert.equal(sanitizeBeat({ type: 'reaction', left: '2H₂ + O₂', right: '2H₂O' }).right, '2H₂O');
});

test('generateBlueprint: outline + chapters with one beat per sentence', async (t) => {
  const server = http.createServer((req, res) => {
    if (req.url === '/api/tags') return res.end(JSON.stringify({ models: [{ name: 'llama3.1:8b' }] }));
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      const b = JSON.parse(body);
      const sys = b.messages[0].content, user = b.messages.at(-1).content;
      let reply;
      if (sys.includes('You plan short narrated diagram explainers')) {
        const n = Number(/exactly (\d+) chapters/.exec(user)[1]);
        reply = JSON.stringify({ title: 'JioHotstar IPL', series: 'System design', question: { asker: 'INTERVIEWER', text: 'Build JioHotstar.', highlight: 'JioHotstar' },
          stakes: { value: '5.5', unit: 'crore', label: 'watching' }, journey: ['toss', '1st ball', 'ad break'],
          chapters: Array.from({ length: n }, (_, i) => ({ title: `Ch ${i}`, goal: 'g', milestone: Math.min(2, i) })),
          answer: { headline: 'Autoscaling', items: ['a', 'b', 'c'] }, endQuestion: 'Which was hardest?', analogy: 'cricket ground gates', hashtags: ['ipl', '#sd'] });
      } else {
        reply = JSON.stringify({ sentences: [
          { text: 'Socho 5.5 crore log ek saath.', speech: 'सोचो 5.5 करोड़ लोग एक साथ।', beat: { type: 'stat', value: '5.5', unit: 'CRORE', label: 'live', phrase: 'all at once', highlight: 'once' } },
          { text: 'Har phone video maangta hai.', speech: 'हर फ़ोन वीडियो माँगता है।', beat: { type: 'devices', device: 'phone', count: 12, lit: 4, phrase: 'phones ask', highlight: 'ask' } },
          { text: 'Ek server sabko copies bhejta hai.', speech: 'एक सर्वर सबको कॉपीज़ भेजता है।', beat: { type: 'fanout', source: { label: 'CDN' }, target: { device: 'phone', count: 10 } } },
        ] });
      }
      res.setHeader('content-type', 'application/x-ndjson');
      res.write(`${JSON.stringify({ message: { role: 'assistant', content: reply }, done: false })}\n`);
      res.end(`${JSON.stringify({ message: { role: 'assistant', content: '' }, done: true })}\n`);
    });
  });
  await new Promise((r) => server.listen(0, r));
  t.after(() => server.close());
  process.env.OLLAMA_HOST = `http://127.0.0.1:${server.address().port}`;
  const { config } = await import('../src/config.js');
  config.ollama.host = process.env.OLLAMA_HOST;
  const { generateBlueprint } = await import('../src/blueprint/generate.js');
  const { PLATFORMS } = await import('../src/presets.js');
  const { outline, scenes } = await generateBlueprint({ topic: 'IPL on JioHotstar', platform: PLATFORMS.blueprint, log: () => {}, progress: () => {}, lang: 'hinglish' });
  assert.deepEqual(outline.journey, ['TOSS', '1ST BALL', 'AD BREAK']);
  assert.ok(scenes.length >= 4);
  assert.equal(scenes[0].role, 'question');
  assert.equal(scenes.at(-1).role, 'answer');
  for (const sc of scenes) for (const s of sc.sentences) { assert.ok(s.beat?.type); assert.ok(s.speech); }
  assert.equal(scenes[1].sentences[1].beat.type, 'devices');
});
