import express from 'express';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import * as esbuild from 'esbuild';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const app = express();
app.use(express.json({ limit: '40mb' }));

const safe = v => String(v ?? '').replace(/[<>]/g, '').replace(/[\u0000-\u001f]/g, ' ').slice(0, 1200);
const clampInt = (v, lo, hi, d) => Math.max(lo, Math.min(hi, Number.isFinite(Number(v)) && Number(v) > 0 ? Math.round(Number(v)) : d));
const LOCAL_WASM_URL = 'https://rive.local/rive.wasm';

// ---------------------------------------------------------------- capabilities (reported honestly, never assumed)
// Motion Canvas has no supported headless exporter in this service, so it is reported unavailable and the backend never routes to it.
const CAPABILITIES = {
  MOTION_CANVAS: { available: false, reason: 'No supported headless Motion Canvas exporter is configured in this service.' },
  THREE_JS: { available: true, reason: 'Playwright Chromium + Three.js templates (orbit, molecule, crystal, layers, axes, lanes, nodes).' },
  RIVE: { available: true, reason: 'Needs a valid .riv file per scene; the runtime wasm is served locally.' },
};
app.get('/health', (_, res) => res.json({
  status: 'ok', version: 3,
  engines: Object.fromEntries(Object.entries(CAPABILITIES).map(([k, v]) => [k, v.available])),
  capabilities: CAPABILITIES,
}));

// ---------------------------------------------------------------- small concurrency gate: Chromium is heavy
const MAX_CONCURRENT = clampInt(process.env.RENDER_CONCURRENCY, 1, 6, 2);
let active = 0; const waiting = [];
async function withSlot(fn) {
  if (active >= MAX_CONCURRENT) await new Promise(r => waiting.push(r));
  active++;
  try { return await fn(); } finally { active--; const n = waiting.shift(); if (n) n(); }
}

const launchOptions = () => ({
  headless: true,
  executablePath: process.env.CHROMIUM_PATH || undefined,
  args: ['--no-sandbox', '--use-gl=angle', '--use-angle=swiftshader', '--disable-dev-shm-usage'],
});

// bundles are built once and reused
const bundleCache = new Map();
function bundle(name, contents) {
  if (!bundleCache.has(name)) {
    bundleCache.set(name, esbuild.build({ stdin: { contents, resolveDir: ROOT, sourcefile: name + '-entry.js', loader: 'js' }, bundle: true, format: 'iife', write: false })
      .then(r => r.outputFiles[0].text));
  }
  return bundleCache.get(name);
}

// A page may only talk to nothing but itself: no CDN, no tracking, no data leaving the container.
async function lockdown(page, allowWasm) {
  await page.route('**/*', async route => {
    const url = route.request().url();
    if (allowWasm && url.startsWith(LOCAL_WASM_URL)) {
      return route.fulfill({ status: 200, contentType: 'application/wasm', body: await fs.readFile(path.join(ROOT, 'node_modules/@rive-app/canvas/rive.wasm')) });
    }
    if (url.startsWith('data:') || url.startsWith('blob:') || url.startsWith('about:')) return route.continue();
    return route.abort();
  });
}

// ---------------------------------------------------------------- THREE_JS
async function threeFrames(req, count) {
  const width = clampInt(req.width, 320, 1920, 1920), height = clampInt(req.height, 240, 1920, 1080);
  const code = await bundle('three', `import * as THREE from 'three'; import { createStudio } from './three-scene.js'; window.THREE = THREE; window.createStudio = createStudio;`);
  const browser = await chromium.launch(launchOptions());
  try {
    const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
    await lockdown(page, false);
    await page.setContent(`<html><body style="margin:0;background:#07111f;overflow:hidden"><canvas id="c" style="width:${width}px;height:${height}px;display:block"></canvas><script>${code}</script></body></html>`);
    const info = await page.evaluate(({ title, narration, labels, kind, width, height, count }) => {
      window.studio = window.createStudio(window.THREE, { title, narration, labels, kind, width, height, count });
      return window.studio.kind;
    }, { title: safe(req.title), narration: safe(req.narration), labels: (req.labels || []).slice(0, 6).map(safe), kind: String(req.sceneKind || ''), width, height, count });
    const frames = [];
    for (let k = 0; k < count; k++) {
      // render the step twice, 0.7 s apart, so the newest element has finished easing in when the frame is captured
      await page.evaluate(({ k, t }) => { window.studio.renderStep(k, t); window.studio.renderStep(k, t + 0.7); }, { k, t: k * 1.1 });
      frames.push((await page.screenshot({ type: 'png' })).toString('base64'));
    }
    return { frames, template: info };
  } finally { await browser.close(); }
}

// ---------------------------------------------------------------- RIVE
async function riveFrames(req, count) {
  if (!req.riveAssetBase64) throw new Error('Rive scene requires a valid .riv asset; upload one before routing to Rive.');
  const width = clampInt(req.width, 320, 1920, 1920), height = clampInt(req.height, 240, 1920, 1080);
  const code = await bundle('rive', `import { Rive, RuntimeLoader } from '@rive-app/canvas'; RuntimeLoader.setWasmUrl('${LOCAL_WASM_URL}'); window.Rive = Rive;`);
  const browser = await chromium.launch(launchOptions());
  try {
    const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
    await lockdown(page, true);
    await page.setContent(`<body style="margin:0;background:#0b1323;overflow:hidden"><canvas id="c" width="${width}" height="${height}" style="width:${width}px;height:${height}px;display:block"></canvas><script>${code}</script></body>`);
    // wait for the file to load, or fail loudly: a file that does not parse must never come back as a "rendered" scene
    await page.evaluate(b64 => new Promise((resolve, reject) => {
      const raw = atob(b64), a = new Uint8Array(raw.length);
      for (let i = 0; i < raw.length; i++) a[i] = raw.charCodeAt(i);
      let settled = false;
      const done = (fn, v) => { if (settled) return; settled = true; clearTimeout(timer); fn(v); };
      const timer = setTimeout(() => done(reject, new Error('Timed out waiting for the Rive file to load')), 12000);
      const describe = e => { try { return (e && (e.message || e.data || e.type)) ? String(e.message || e.data || e.type) : JSON.stringify(e); } catch { return 'unknown error'; } };
      window.riveInstance = new window.Rive({
        buffer: a.buffer, canvas: document.querySelector('#c'), autoplay: true,
        onLoad: () => { try { window.riveInstance.resizeDrawingSurfaceToCanvas(); done(resolve, true); } catch (e) { done(reject, e); } },
        onLoadError: e => done(reject, new Error('Rive file failed to load: ' + describe(e))),
      });
    }), req.riveAssetBase64);

    // the canvas must actually contain drawing; sample it (2D canvas) and retry briefly while the first frame arrives
    const drewSomething = () => page.evaluate(() => {
      const c = document.querySelector('#c'); const ctx = c.getContext('2d');
      if (!ctx) return null;                                   // not readable: cannot judge from here
      const w = c.width, h = c.height, seen = new Set();
      for (let y = 0; y < h; y += Math.max(1, Math.floor(h / 40))) for (let x = 0; x < w; x += Math.max(1, Math.floor(w / 40))) {
        const d = ctx.getImageData(x, y, 1, 1).data; seen.add(d[0] + ',' + d[1] + ',' + d[2] + ',' + d[3]);
        if (seen.size > 1) return true;
      }
      return false;
    });
    let ok = null;
    for (let i = 0; i < 8; i++) { await page.waitForTimeout(250); ok = await drewSomething(); if (ok !== false) break; }
    if (ok === false) throw new Error('The Rive file loaded but drew nothing (blank canvas). Check the artboard / state machine in the .riv file.');

    const frames = [];
    for (let i = 0; i < count; i++) { await page.waitForTimeout(350); frames.push((await page.screenshot({ type: 'png' })).toString('base64')); }
    return { frames };
  } finally { await browser.close(); }
}

app.post('/render-frames', async (req, res) => {
  const p = req.body || {};
  const engine = String(p.engine || '').toUpperCase();
  const count = clampInt(p.steps, 2, 8, 4);
  try {
    if (engine === 'MOTION_CANVAS') throw new Error(CAPABILITIES.MOTION_CANVAS.reason);
    let out;
    if (engine === 'THREE_JS') out = await withSlot(() => threeFrames(p, count));
    else if (engine === 'RIVE') out = await withSlot(() => riveFrames(p, count));
    else return res.status(400).json({ error: 'Unsupported renderer ' + engine });
    res.json({ engine, rendererStatus: 'RENDERED', ...out });
  } catch (e) {
    res.status(503).json({ error: String(e?.message || e).split('\n')[0], engine, rendererStatus: 'FAILED' });
  }
});

const port = Number(process.env.PORT || 5020);
app.listen(port, '0.0.0.0', () => console.log(`visual-renderer listening on ${port}`));
