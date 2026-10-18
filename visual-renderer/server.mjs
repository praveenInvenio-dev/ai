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
// Motion Canvas runs a PREBUILT player project (motion/): scenes are driven by plain variables, nothing generated is compiled. It is reported available only if that build exists.
const CAPABILITIES = {
  MOTION_CANVAS: { available: false, reason: 'The Motion Canvas player bundle was not built (motion/dist missing).' },
  THREE_JS: { available: true, reason: 'Playwright Chromium + Three.js templates (orbit, molecule, crystal, layers, axes, lanes, nodes).' },
  RIVE: { available: true, reason: 'Needs a valid .riv file per scene; the runtime wasm is served locally.' },
};
let motionFiles = null;
try {
  const dist = path.join(ROOT, 'motion/dist');
  const proj = (await fs.readdir(path.join(dist, 'src'))).find(f => /^project.*\.js$/.test(f));
  if (proj) {
    motionFiles = { project: await fs.readFile(path.join(dist, 'src', proj)), player: await fs.readFile(path.join(dist, 'player.bundle.js')) };
    CAPABILITIES.MOTION_CANVAS = { available: true, reason: 'Prebuilt Motion Canvas player project (animated step flow) driven by variables.' };
  }
} catch { /* not built: stays unavailable */ }

// page background per lesson theme, so a 3D / animated scene does not look like a different video
const THEME_BG = { reel: '#0b0b0c', reference: '#0c2433', neon: '#07070f', blueprint: '#0a1f3a', blueprint3d: '#08162b', chalkboard: '#1f2a24', sketchnote: '#1a1a1a',
  storyboard: '#1b1b1f', anime: '#10141f', clinical: '#0d2a2f', kids: '#1d3557', toddler: '#1d3557', teen: '#12122b' };
const themeBg = t => THEME_BG[String(t || '').toLowerCase()] || '#07111f';

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
    await page.setContent(`<html><body style="margin:0;background:${themeBg(req.theme)};overflow:hidden"><canvas id="c" style="width:${width}px;height:${height}px;display:block"></canvas><script>${code}</script></body></html>`);
    const info = await page.evaluate(({ title, narration, labels, kind, width, height, count, bg }) => {
      window.studio = window.createStudio(window.THREE, { title, narration, labels, kind, width, height, count, bg });
      return window.studio.kind;
    }, { title: safe(req.title), narration: safe(req.narration), labels: (req.labels || []).slice(0, 6).map(safe), kind: String(req.sceneKind || ''), width, height, count, bg: themeBg(req.theme) });
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
    await page.setContent(`<body style="margin:0;background:${themeBg(req.theme)};overflow:hidden"><canvas id="c" width="${width}" height="${height}" style="width:${width}px;height:${height}px;display:block"></canvas><script>${code}</script></body>`);
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


// ---------------------------------------------------------------- MOTION_CANVAS
const MC_FPS = 60, MC_PHASE = 1.2;   // must match motion/src/scenes/flow.tsx
async function motionFrames(req, count) {
  if (!motionFiles) throw new Error(CAPABILITIES.MOTION_CANVAS.reason);
  const width = clampInt(req.width, 320, 1920, 1920), height = clampInt(req.height, 240, 1920, 1080);
  const labels = (req.labels || []).map(safe).filter(Boolean).slice(0, 5);
  const n = Math.max(3, labels.length);
  const vars = JSON.stringify({ title: safe(req.title).slice(0, 90), labels: JSON.stringify(labels), bg: themeBg(req.theme), portrait: height > width ? 1 : 0, kind: 'flow' });
  const host = `<html><body style="margin:0;background:#000"><script src="/player.js"></script><motion-canvas-player id="p" src="https://mc.local/project.js" width="${width}" height="${height}" quality="1" variables='${vars.replace(/&/g, '&amp;').replace(/'/g, '&#39;')}'></motion-canvas-player></body></html>`;
  const browser = await chromium.launch(launchOptions());
  try {
    const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
    await page.route('**/*', route => {
      const u = route.request().url();
      if (u === 'https://mc.local/') return route.fulfill({ contentType: 'text/html', body: host });
      if (u === 'https://mc.local/player.js') return route.fulfill({ contentType: 'text/javascript', body: motionFiles.player });
      if (u === 'https://mc.local/project.js') return route.fulfill({ contentType: 'text/javascript', body: motionFiles.project });
      if (u.startsWith('data:') || u.startsWith('blob:') || u.startsWith('about:')) return route.continue();
      return route.abort();
    });
    await page.goto('https://mc.local/');
    await page.waitForFunction(() => { const e = document.querySelector('#p'); return e && e.player && e.state === 'ready'; }, null, { timeout: 25000 });
    const grab = frame => page.evaluate(async frame => {
      const e = document.querySelector('#p');
      e.player.requestSeek(frame);
      for (let i = 0; i < 60 && e.player.frame !== frame; i++) await new Promise(r => setTimeout(r, 50));
      await new Promise(r => setTimeout(r, 250));
      return e.shadowRoot.querySelector('canvas').toDataURL('image/png');
    }, frame);
    await grab(Math.round(n * MC_PHASE * MC_FPS));          // warm-up: fonts and the first paint must be done before the first real frame
    const frames = [];
    for (let k = 0; k < count; k++) {
      // one frame per narration sentence, evenly spaced over the animation; the last frame is the end with every step shown
      const t = ((k + 1) / count) * n * MC_PHASE - 0.04;
      frames.push((await grab(Math.round(t * MC_FPS))).split(',')[1]);
    }
    // a canvas that never drew would be one flat colour: refuse to call that a render
    if (new Set(frames).size < 2 && count > 1) throw new Error('Motion Canvas produced identical frames (the scene did not animate).');
    return { frames, template: 'flow' };
  } finally { await browser.close(); }
}

app.post('/render-frames', async (req, res) => {
  const p = req.body || {};
  const engine = String(p.engine || '').toUpperCase();
  const count = clampInt(p.steps, 2, 8, 4);
  try {
    let out;
    if (engine === 'MOTION_CANVAS') out = await withSlot(() => motionFrames(p, count));
    else if (engine === 'THREE_JS') out = await withSlot(() => threeFrames(p, count));
    else if (engine === 'RIVE') out = await withSlot(() => riveFrames(p, count));
    else return res.status(400).json({ error: 'Unsupported renderer ' + engine });
    res.json({ engine, rendererStatus: 'RENDERED', ...out });
  } catch (e) {
    res.status(503).json({ error: String(e?.message || e).split('\n')[0], engine, rendererStatus: 'FAILED' });
  }
});

const port = Number(process.env.PORT || 5020);
app.listen(port, '0.0.0.0', () => console.log(`visual-renderer listening on ${port}`));
