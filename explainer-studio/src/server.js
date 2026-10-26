import path from 'node:path';
import fsSync from 'node:fs';
import express from 'express';
import cors from 'cors';
import crypto from 'node:crypto';
import fs from 'node:fs';
import { config, jobsDir } from './config.js';
import { PLATFORMS, ASPECTS, FPS_OPTIONS, THEME_IDS } from './presets.js';
import { DOMAINS, DOMAIN_IDS } from './domains/index.js';
import { VOICES, allVoices, voiceAvailable, defaultVoiceId, startVoiceProbe, serviceUp } from './tts/voices.js';
import { startStudioVoiceRefresh, studioStatus } from './tts/studio-voices.js';
import { LANGUAGES, LANGUAGE_IDS } from './shared/languages.js';
import { TYPES } from './shared/catalog.js';
import { ollamaStatus } from './llm/ollama.js';
import { JobStore } from './pipeline/jobs.js';
import { JobQueue } from './pipeline/queue.js';
import { runJob } from './pipeline/run.js';
import { getBundle, bundleReady } from './pipeline/render.js';

const store = new JobStore();
await store.init();

const queue = new JobQueue({
  concurrency: config.queue.concurrency,
  maxQueued: config.queue.maxQueued,
  onPositions: (id, pos) => { const j = store.get(id); if (j) store.update(j, { queuePosition: pos }); },
  runner: async (id, signal) => { const job = store.get(id); if (job) await runJob({ store, job, signal }); },
});

const app = express();
app.disable('x-powered-by');
app.use(cors());
app.use(express.json({ limit: '256kb' }));

const wrap = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
const ID_RE = /^[0-9a-f-]{36}$/;

// ── metadata for the UI ─────────────────────────────────────────────────────
app.get('/api/meta', wrap(async (_req, res) => {
  res.json({
    platforms: Object.values(PLATFORMS).map((p) => ({ id: p.id, label: p.label, group: p.group, targetSec: p.targetSec, defaultAspect: p.aspect, scenes: p.plan.length })),
    aspects: Object.keys(ASPECTS), fps: FPS_OPTIONS, themes: THEME_IDS,
    domains: DOMAIN_IDS.map((id) => ({ id, label: DOMAINS[id].label })),
    languages: LANGUAGE_IDS.map((id) => ({ id, label: LANGUAGES[id].label, native: LANGUAGES[id].native, defaultVoice: defaultVoiceId(id) })),
    voices: allVoices().map((v) => ({ id: v.id, label: v.label, engine: v.kind || v.engine, lang: v.lang, available: voiceAvailable(v) })),
    defaultVoice: defaultVoiceId('en'),
    primitives: TYPES,
    model: config.ollama.model,
  });
}));

app.get('/api/health', wrap(async (_req, res) => {
  const ollama = await ollamaStatus();
  res.json({ ok: true, ollama, remotionBundle: bundleReady(), queue: queue.stats(), renderer: { browser: config.render.browserExecutable ? 'local' : 'remotion-download', gl: config.render.gl }, tts: { kokoroService: config.tts.kokoroUrl || null, services: Object.fromEntries(Object.entries({ kokoro: config.tts.kokoroUrl, indicspeak: config.tts.indicSpeakUrl, indicf5: config.tts.indicF5Url }).filter(([, u]) => u).map(([k]) => [k, serviceUp[k] !== false])), studio: studioStatus } });
}));

// ── jobs ────────────────────────────────────────────────────────────────────
function parseRequest(b = {}) {
  const topic = String(b.topic ?? '').replace(/\s+/g, ' ').trim();
  if (topic.length < 3 || topic.length > 200) throw Object.assign(new Error('topic must be 3–200 characters'), { status: 400 });
  const platform = PLATFORMS[b.platform] ? b.platform : 'reel';
  const aspect = ASPECTS[b.aspect] ? b.aspect : PLATFORMS[platform].aspect;
  const fps = FPS_OPTIONS.includes(Number(b.fps)) ? Number(b.fps) : 30;
  const theme = THEME_IDS.includes(b.theme) ? b.theme : 'dracula';
  const domain = DOMAINS[b.domain] ? b.domain : 'general';
  const language = LANGUAGES[b.language] ? b.language : 'en';
  const want = allVoices().find((v) => v.id === b.voice);
  // A voice only speaks its own language (an IndicF5 Tamil voice cannot read Hindi): fall back to that language's default.
  const voice = want && want.lang === language && voiceAvailable(want) ? want.id : defaultVoiceId(language);
  const model = typeof b.model === 'string' && /^[\w.:/-]{1,80}$/.test(b.model) ? b.model : undefined;
  return { topic, platform, aspect, fps, theme, domain, language, voice, mode: 'full', ...(model ? { model } : {}) };
}

app.post('/api/jobs', wrap(async (req, res) => {
  const request = parseRequest(req.body);
  const id = crypto.randomUUID();
  const job = store.create(id, request);
  const position = queue.enqueue(id);
  store.log(job, position > 1 ? `Queued (position ${position}).` : 'Queued — starting now.');
  res.status(202).json({ id, position });
}));

app.post('/api/jobs/:id/rerender', wrap(async (req, res) => {
  const old = store.get(req.params.id);
  if (!old || old.status !== 'done') return res.status(404).json({ error: 'Only finished jobs can be re-rendered.' });
  const patch = parseRequest({ ...old.request, ...req.body, topic: old.request.topic });
  const id = crypto.randomUUID();
  // Reuse the saved script + narration by copying them into the new job folder.
  const job = store.create(id, { ...patch, mode: 'rerender', model: old.request.model, language: old.request.language || 'en', voice: old.request.voice, domain: old.request.domain, platform: old.request.platform, sourceJob: old.id });
  for (const f of ['timeline.json', 'script.json', 'narration.wav']) fs.copyFileSync(`${store.dir(old.id)}/${f}`, `${store.dir(id)}/${f}`);
  queue.enqueue(id);
  store.log(job, `Re-render of ${old.id.slice(0, 8)} with new visual settings.`);
  res.status(202).json({ id });
}));

app.get('/api/jobs', (_req, res) => res.json(store.list()));

app.get('/api/jobs/:id', (req, res) => {
  const j = ID_RE.test(req.params.id) && store.get(req.params.id);
  if (!j) return res.status(404).json({ error: 'not found' });
  res.json(j);
});

app.get('/api/jobs/:id/events', (req, res) => {
  const j = ID_RE.test(req.params.id) && store.get(req.params.id);
  if (!j) return res.status(404).end();
  res.set({ 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache, no-transform', Connection: 'keep-alive', 'X-Accel-Buffering': 'no' });
  res.flushHeaders();
  const send = (e) => res.write(`data: ${JSON.stringify(e)}\n\n`);
  // Replay history so a page refresh shows the full log.
  j.logs.forEach((l) => send({ type: 'log', ...l }));
  send({ type: 'state', status: j.status, stage: j.stage, progress: j.progress, queuePosition: j.queuePosition, result: j.result, error: j.error });
  const unsub = store.subscribe(j.id, send);
  const hb = setInterval(() => res.write(': hb\n\n'), 15_000);
  req.on('close', () => { clearInterval(hb); unsub(); });
});

app.delete('/api/jobs/:id', wrap(async (req, res) => {
  const j = ID_RE.test(req.params.id) && store.get(req.params.id);
  if (!j) return res.status(404).json({ error: 'not found' });
  if (['queued', 'running'].includes(j.status)) {
    queue.cancel(j.id);
    if (j.status === 'queued') store.update(j, { status: 'cancelled', stage: 'cancelled' });
    return res.json({ cancelled: true });
  }
  await store.remove(j.id);
  res.json({ deleted: true });
}));

// ── media (static, with HTTP range support so <video> can seek) ──────────────
const PUBLIC_FILES = new Set(['final.mp4', 'thumbnail.jpg', 'captions.srt', 'captions.vtt', 'metadata.json']);
app.use('/media', (req, res, next) => {
  // Only whitelisted deliverables are served; script/timeline/narration/tmp stay private.
  const [, id, file, ...rest] = req.path.split('/');
  if (!ID_RE.test(id || '') || rest.length || !PUBLIC_FILES.has(file)) return res.status(404).json({ error: 'not found' });
  if (req.query.download !== undefined) res.attachment(file);
  next();
}, express.static(jobsDir(), { index: false, dotfiles: 'deny', maxAge: '1h' }));

app.use((err, _req, res, _next) => {
  const status = err.status || 500;
  if (status >= 500) console.error(err);
  res.status(status).json({ error: err.message || 'internal error' });
});

startVoiceProbe();
startStudioVoiceRefresh();
const server = app.listen(config.port, () => {
  console.log(`API listening on :${config.port} · Ollama ${config.ollama.host} (${config.ollama.model}) · queue concurrency ${config.queue.concurrency}`);
  getBundle((m) => console.log(m)).then(() => console.log('Remotion bundle ready.')).catch((e) => console.error('Remotion bundle failed:', e.message));
});

// ── why did the last run end? Recorded so an interrupted job can say WHY instead of just "server restarted" ──
const recordExit = (reason) => {
  try { fsSync.writeFileSync(path.join(config.dataDir, 'last-exit.json'), JSON.stringify({ at: Date.now(), reason: String(reason).slice(0, 800) })); } catch { /* best effort */ }
};
process.on('uncaughtException', (e) => {
  console.error('FATAL uncaught exception:', e);
  recordExit(`the API crashed: ${e?.stack || e}`);
  process.exit(1);
});
// A stray rejected promise must not kill every running job: log it, keep serving.
process.on('unhandledRejection', (e) => console.error('Unhandled promise rejection (ignored, server keeps running):', e));

const shutdown = () => {
  console.log('Shutting down…');
  recordExit('the container was stopped or recreated by Docker (docker compose up / --build / restart, or a COMPOSE_PROFILES change). Start jobs after the stack is up.');
  queue.abortAll();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 8000).unref();
};
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
