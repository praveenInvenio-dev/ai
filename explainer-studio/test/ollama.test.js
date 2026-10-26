import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

// A tiny fake Ollama: streams NDJSON like the real /api/chat, and misbehaves on purpose.
const calls = [];
let sceneAttempt = 0;
const server = http.createServer((req, res) => {
  if (req.url === '/api/tags') return res.end(JSON.stringify({ models: [{ name: 'llama3.1:8b' }] }));
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    const b = JSON.parse(body);
    const user = b.messages.at(-1).content;
    calls.push({ format: typeof b.format, user: user.slice(0, 60) });
    let reply;
    if (b.messages[1].content.includes('Plan exactly')) {
      const n = Number(/Plan exactly (\d+) scenes/.exec(b.messages[1].content)[1]);
      reply = JSON.stringify({
        title: 'Why counter++ breaks', titleOptions: ['A', 'B'], onScreenHook: 'counter++ is a lie', ctaLine: 'Send this to a friend',
        analogy: 'two cashiers sharing one till', logline: 'Shared state needs a guard.', hashtags: ['java', '#threads'],
        scenes: Array.from({ length: n }, (_, i) => ({ heading: `Scene ${i}`, goal: 'g', keyVisual: 'k' })),
      });
    } else if (sceneAttempt++ === 0) {
      reply = '{"sentences":[{"text":"Truncated output...'; // broken JSON → must trigger repair
    } else {
      reply = JSON.stringify({
        sentences: [{ text: "Watch this, it's wild.", phase: 'visualise' }, { text: "Two threads, one counter.", phase: 'visualise' }],
        elements: [{ type: 'callout', slot: 'main', props: { text: 'Look!' }, enter: { sentence: 0 } }],
      });
    }
    res.setHeader('content-type', 'application/x-ndjson');
    // split the reply into odd-sized chunks, and split a JSON line across TCP writes
    for (let i = 0; i < reply.length; i += 37) res.write(`${JSON.stringify({ message: { role: 'assistant', content: reply.slice(i, i + 37) }, done: false })}\n`);
    res.end(`${JSON.stringify({ message: { role: 'assistant', content: '' }, done: true })}\n`);
  });
});

test('generateScript: streams, validates, repairs bad JSON, and returns clean scenes', async (t) => {
  await new Promise((r) => server.listen(0, r));
  process.env.OLLAMA_HOST = `http://127.0.0.1:${server.address().port}`;
  t.after(() => server.close());
  const { generateScript } = await import('../src/llm/generate.js');
  const { ollamaStatus } = await import('../src/llm/ollama.js');
  const { PLATFORMS } = await import('../src/presets.js');
  const { DOMAINS } = await import('../src/domains/index.js');

  const st = await ollamaStatus();
  assert.ok(st.reachable && st.modelPresent);

  const logs = [];
  const { outline, scenes } = await generateScript({
    topic: 'Java Multithreading Race Conditions', domain: DOMAINS.cs, platform: PLATFORMS.reel,
    log: (m, l) => logs.push([l, m]), progress: () => {},
  });
  assert.equal(outline.scenes.length, 4); // hook, analogy, demo, payoff
  assert.deepEqual(outline.hashtags, ['java', 'threads']);
  assert.equal(scenes.length, 4);
  assert.ok(logs.some(([l, m]) => l === 'warn' && /rejected/.test(m)), 'repair attempt was logged');
  assert.equal(scenes[0].role, 'hook');
  assert.equal(scenes[0].sentences[0].phase, 'visualise');
  assert.equal(scenes[1].role, 'analogy');
  assert.equal(scenes[2].sentences[0].phase, 'animate', 'phase coerced into the scene plan');
  assert.ok(calls.every((c) => c.format === 'object'), 'schema was sent as `format`');
});
