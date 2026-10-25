import express from 'express';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import os from 'node:os';
import { execFile } from 'node:child_process';
import * as esbuild from 'esbuild';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const app = express();
app.use(express.json({ limit: '40mb' }));
const renderMetrics = new Map();
function recordMetric(engine, success, elapsedMs) {
  const key = String(engine || 'UNKNOWN').toUpperCase();
  const m = renderMetrics.get(key) || { successes: 0, failures: 0, totalMs: 0, lastMs: 0 };
  if (success) m.successes++; else m.failures++;
  m.totalMs += Math.max(0, Number(elapsedMs) || 0); m.lastMs = Math.max(0, Number(elapsedMs) || 0);
  renderMetrics.set(key, m);
}

const safe = v => String(v ?? '').replace(/[<>]/g, '').replace(/[\u0000-\u001f]/g, ' ').slice(0, 1200);
const clampInt = (v, lo, hi, d) => Math.max(lo, Math.min(hi, Number.isFinite(Number(v)) && Number(v) > 0 ? Math.round(Number(v)) : d));
const LOCAL_WASM_URL = 'https://rive.local/rive.wasm';

// ---------------------------------------------------------------- capabilities (reported honestly, never assumed)
// Motion Canvas runs a PREBUILT player project (motion/): scenes are driven by plain variables, nothing generated is compiled. It is reported available only if that build exists.
const CAPABILITIES = {
  MOTION_CANVAS: { available: false, reason: 'the Motion Canvas player is not built in this renderer image (old image, or the motion build step failed): run docker compose build --no-cache visual-renderer' },
  THREE_JS: { available: true, reason: 'Playwright Chromium + Three.js templates (orbit, molecule, crystal, layers, axes, lanes, nodes).' },
  PIXIJS: { available: true, reason: 'PixiJS 2D renderer for particle flow, waves, charges and network traffic; rendered in isolated Chromium with no external network.' },
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
const LIGHT_THEMES = new Set(['kids', 'toddler']);   // bright cream look: the animated scenes switch to dark text
const themeLight = t => LIGHT_THEMES.has(String(t || '').toLowerCase());
const THEME_BG = { reel: '#0b0b0c', reference: '#0c2433', neon: '#07070f', blueprint: '#0a1f3a', blueprint3d: '#08162b', chalkboard: '#1f2a24', sketchnote: '#1a1a1a',
  storyboard: '#1b1b1f', anime: '#10141f', clinical: '#0d2a2f', kids: '#fff7e6', toddler: '#fff7e6', teen: '#12122b' };
const themeBg = t => THEME_BG[String(t || '').toLowerCase()] || '#07111f';

// Animated clips (Revideo, MIT): a headless render of the step flow timed to the narration. Needs the revideo/ project installed AND a Chrome for it.
const REVIDEO_DIR = path.join(ROOT, 'revideo');
const FFMPEG = process.env.FFMPEG_PATH || 'ffmpeg';
const runBin = (bin, args, opts = {}) => new Promise((resolve, reject) => execFile(bin, args, { ...opts, maxBuffer: 8 * 1024 * 1024 }, (err, stdout, stderr) => err ? reject(Object.assign(err, { stderr })) : resolve(stdout)));
const runNode = (args, opts) => new Promise((resolve, reject) => execFile(process.execPath, args, { ...opts, maxBuffer: 8 * 1024 * 1024 }, (err, stdout, stderr) => err ? reject(Object.assign(err, { stderr })) : resolve(stdout)));
let ffmpegOk = false;
try { await runBin(FFMPEG, ['-version']); ffmpegOk = true; } catch { /* 3D clips stay unavailable */ }
let clipsAvailable = false, clipsReason = 'the revideo/ project is not installed in this image';
try {
  await fs.access(path.join(REVIDEO_DIR, 'node_modules/@revideo/renderer'));
  let chrome = process.env.REVIDEO_CHROME;
  if (!chrome) { try { chrome = (await runNode(['-e', "import('puppeteer').then(p => console.log(p.default.executablePath()))"], { cwd: REVIDEO_DIR, timeout: 20000 })).trim(); } catch { chrome = ''; } }
  try { await fs.access(chrome); clipsAvailable = true; clipsReason = 'Revideo headless render with ' + path.basename(path.dirname(path.dirname(chrome))); } catch { clipsReason = 'no Chrome for Revideo (run: npx puppeteer browsers install chrome, or set REVIDEO_CHROME)'; }
} catch { /* stays unavailable */ }

app.get('/health', (_, res) => res.json({
  status: 'ok', version: 6, clips: { MOTION_CANVAS: clipsAvailable, THREE_JS: ffmpegOk, PIXIJS: ffmpegOk, reason: clipsReason },
  engines: Object.fromEntries(Object.entries(CAPABILITIES).map(([k, v]) => [k, v.available])),
  capabilities: CAPABILITIES,
  metrics: Object.fromEntries([...renderMetrics.entries()].map(([k, v]) => [k, { ...v, averageMs: Math.round(v.totalMs / Math.max(1, v.successes + v.failures)) }])),
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
    const info = await page.evaluate(({ kicker, title, narration, labels, kind, width, height, count, bg, light }) => {
      window.studio = window.createStudio(window.THREE, { kicker, title, narration, labels, kind, width, height, count, bg, light });
      return window.studio.kind;
    }, { kicker: safe(req.kicker).slice(0, 60), title: safe(req.title), narration: safe(req.narration), labels: (req.labels || []).slice(0, 6).map(safe), kind: String(req.sceneKind || ''), width, height, count, bg: themeBg(req.theme), light: themeLight(req.theme) });
    const frames = [];
    for (let k = 0; k < count; k++) {
      // render the step twice, 0.7 s apart, so the newest element has finished easing in when the frame is captured
      await page.evaluate(({ k, t }) => { window.studio.renderStep(k, t); window.studio.renderStep(k, t + 0.7); }, { k, t: k * 1.1 });
      frames.push((await page.screenshot({ type: 'png' })).toString('base64'));
    }
    return { frames, template: info };
  } finally { await browser.close(); }
}

// ---------------------------------------------------------------- PIXIJS (safe template renderer; never executes model-generated JS)
async function pixiFrames(req, count) {
  const width = clampInt(req.width, 320, 1920, 1920), height = clampInt(req.height, 240, 1920, 1080);
  const particles = clampInt(req.particleCount ?? req.count, 12, 180, 72);
  const code = await bundle('pixi', `import * as PIXI from 'pixi.js'; window.PIXI = PIXI;`);
  const browser = await chromium.launch(launchOptions());
  try {
    const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
    await lockdown(page, false);
    await page.setContent(`<html><body style="margin:0;overflow:hidden;background:${themeBg(req.theme)}"><canvas id="c"></canvas><script>${code}</script></body></html>`);
    await page.evaluate(async ({ width, height, title, kicker, labels, particleCount, bg, light, sceneKind }) => {
      const app = new window.PIXI.Application();
      await app.init({ canvas: document.querySelector('#c'), width, height, background: bg, antialias: true, autoStart: false, preference: 'webgl' });
      window.pixiApp = app;
      const P = window.PIXI, root = app.stage;
      const ink = light ? 0x17202a : 0xf2f6ff, muted = light ? 0x56616d : 0x91a4c7;
      const heading = new P.Text({ text: String(title || 'Concept in motion').slice(0, 90), style: { fontFamily: 'Arial', fontSize: Math.max(24, width * .026), fontWeight: '700', fill: ink } });
      heading.x = width * .06; heading.y = height * .075; root.addChild(heading);
      if (kicker) { const k = new P.Text({ text: String(kicker).slice(0,60), style: {fontFamily:'Arial',fontSize:Math.max(12,width*.009),fill:muted,letterSpacing:2} }); k.x=width*.06; k.y=height*.045; root.addChild(k); }
      const line = new P.Graphics().roundRect(width*.08,height*.48,width*.84,height*.055,height*.027).fill({color:light?0xdde5ef:0x20304b}); root.addChild(line);
      const point = new P.Graphics().circle(width*.5,height*.507,width*.018).fill({color:0xf7c948}); root.addChild(point);
      const objs=[]; const colors=[0x5eead4,0x60a5fa,0xc4b5fd,0xfbbf24,0xfb7185];
      for(let i=0;i<particleCount;i++) { const g=new P.Graphics().circle(0,0,Math.max(4,width*.004)).fill({color:colors[i%colors.length]}); g.y=height*.507 + Math.sin(i*1.7)*height*.012; g.x=width*.1+(i/particleCount)*width*.8; root.addChild(g); objs.push({g,phase:i/particleCount}); }
      const ls=(Array.isArray(labels)?labels:[]).slice(0,4);
      const labelObjects=[];
      ls.forEach((label,i)=>{const t=new P.Text({text:String(label).slice(0,42),style:{fontFamily:'Arial',fontSize:Math.max(17,width*.016),fontWeight:'600',fill:ink,wordWrap:true,wordWrapWidth:width*.32,align:'center'}}); t.anchor.set(.5); t.x=width*(.2+i*.2); t.y=height*.68; root.addChild(t); labelObjects.push(t);});
      const sub = new P.Text({text: sceneKind==='wave' || sceneKind==='wave_propagation' ? 'Propagation over time' : sceneKind==='network_packets' ? 'Packets move through the network' : 'Direction • movement • rate',style:{fontFamily:'Arial',fontSize:Math.max(14,width*.012),fill:muted}}); sub.anchor.set(.5); sub.x=width/2; sub.y=height*.84; root.addChild(sub);
      window.renderPixiAt = (t, starts=[]) => { for(const o of objs){const x=(o.phase+t*.18)%1; o.g.x=width*(.1+x*.8); o.g.alpha=.7+.3*Math.sin((x+t)*Math.PI*2); } point.x=width*(.1+((t*.18)%1)*.8); labelObjects.forEach((obj,i)=>{const reveal=Number(starts[i]); obj.visible=Number.isFinite(reveal) ? t>=reveal : t >= (i/Math.max(1,labelObjects.length))*Math.max(1,t);}); app.renderer.render(root); };
    }, { width, height, title: safe(req.title), kicker: safe(req.kicker).slice(0,60), labels: (req.labels||[]).slice(0,6).map(safe), particleCount: particles, bg: themeBg(req.theme), light: themeLight(req.theme), sceneKind: safe(req.sceneKind) });
    const frames=[];
    for(let k=0;k<count;k++){ await page.evaluate(({k,count})=>window.renderPixiAt((k+1)/count*4),{k,count}); frames.push((await page.screenshot({type:'png'})).toString('base64')); }
    if(new Set(frames).size<2 && count>1) throw new Error('PixiJS frames did not change; refusing blank/static animation output.');
    return {frames,template:'particle_flow'};
  } finally { await browser.close(); }
}

// ---------------------------------------------------------------- PIXIJS continuous clip: 30fps simulation, narration-timed label reveals, H.264 MP4.
async function pixiClip(req) {
  const width = clampInt(req.width, 320, 1920, 1920), height = clampInt(req.height, 240, 1920, 1080);
  const total = Math.min(120, Math.max(2, Number(req.total) || 6));
  const particleCount = clampInt(req.particleCount, 12, 180, 72);
  const starts = (Array.isArray(req.starts) ? req.starts : []).map(Number).filter(Number.isFinite).slice(0, 4);
  const labels = (Array.isArray(req.labels) ? req.labels : []).map(safe).filter(Boolean).slice(0, 4);
  const code = await bundle('pixi', `import * as PIXI from 'pixi.js'; window.PIXI = PIXI;`);
  const browser = await chromium.launch(launchOptions());
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'pixi-clip-'));
  try {
    const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
    await lockdown(page, false);
    await page.setContent(`<html><body style="margin:0;overflow:hidden;background:${themeBg(req.theme)}"><canvas id="c"></canvas><script>${code}</script></body></html>`);
    await page.evaluate(async ({ width, height, title, kicker, labels, particleCount, bg, light, sceneKind, total }) => {
      const P = window.PIXI, app = new P.Application();
      await app.init({ canvas: document.querySelector('#c'), width, height, background: bg, antialias: true, autoStart: false, preference: 'webgl' });
      window.pixiApp = app; const root = app.stage;
      const ink = light ? 0x17202a : 0xf2f6ff, muted = light ? 0x56616d : 0x91a4c7;
      const heading = new P.Text({text:String(title||'Concept in motion').slice(0,90),style:{fontFamily:'Arial',fontSize:Math.max(24,width*.026),fontWeight:'700',fill:ink}}); heading.x=width*.06; heading.y=height*.075; root.addChild(heading);
      if(kicker){const k=new P.Text({text:String(kicker).slice(0,60),style:{fontFamily:'Arial',fontSize:Math.max(12,width*.009),fill:muted,letterSpacing:2}});k.x=width*.06;k.y=height*.045;root.addChild(k);}
      const line=new P.Graphics().roundRect(width*.08,height*.48,width*.84,height*.055,height*.027).fill({color:light?0xdde5ef:0x20304b});root.addChild(line);
      const point=new P.Graphics().circle(width*.5,height*.507,width*.018).fill({color:0xf7c948});root.addChild(point);
      const colors=[0x5eead4,0x60a5fa,0xc4b5fd,0xfbbf24,0xfb7185], objs=[];
      for(let i=0;i<particleCount;i++){const g=new P.Graphics().circle(0,0,Math.max(4,width*.004)).fill({color:colors[i%colors.length]});g.y=height*.507+Math.sin(i*1.7)*height*.012;root.addChild(g);objs.push({g,phase:i/particleCount});}
      const labelObjects=[]; labels.forEach((label,i)=>{const t=new P.Text({text:String(label).slice(0,42),style:{fontFamily:'Arial',fontSize:Math.max(17,width*.016),fontWeight:'600',fill:ink,wordWrap:true,wordWrapWidth:width*.32,align:'center'}});t.anchor.set(.5);t.x=width*(.2+i*.2);t.y=height*.68;root.addChild(t);labelObjects.push(t);});
      const sub=new P.Text({text:sceneKind==='wave'||sceneKind==='wave_propagation'?'Propagation over time':sceneKind==='network_packets'?'Packets move through the network':'Direction • movement • rate',style:{fontFamily:'Arial',fontSize:Math.max(14,width*.012),fill:muted}});sub.anchor.set(.5);sub.x=width/2;sub.y=height*.84;root.addChild(sub);
      window.renderPixiAt=(t,starts)=>{for(const o of objs){const x=(o.phase+t*.18)%1;o.g.x=width*(.1+x*.8);o.g.alpha=.7+.3*Math.sin((x+t)*Math.PI*2);}point.x=width*(.1+((t*.18)%1)*.8);labelObjects.forEach((obj,i)=>{obj.visible=t>=(Number.isFinite(starts[i])?starts[i]:(i/Math.max(1,labelObjects.length))*total);});app.renderer.render(root);};
    }, {width,height,title:safe(req.title),kicker:safe(req.kicker).slice(0,60),labels,particleCount,bg:themeBg(req.theme),light:themeLight(req.theme),sceneKind:safe(req.sceneKind),total});
    const frameCount = Math.ceil(total * 30);
    const sampleFrames = [];
    for(let i=0;i<frameCount;i++){
      await page.evaluate(({t,starts})=>window.renderPixiAt(t,starts),{t:i/30,starts});
      const frame = await page.screenshot({type:'jpeg',quality:90});
      await fs.writeFile(path.join(dir,`f${String(i).padStart(5,'0')}.jpg`),frame);
      if (i === 0 || i === Math.floor(frameCount / 2) || i === frameCount - 1) sampleFrames.push(frame.toString('base64'));
    }
    if (new Set(sampleFrames).size < 2) throw new Error('PixiJS animation frames did not change; refusing to export a static clip.');
    const out=path.join(dir,'clip.mp4');
    await runBin(FFMPEG,['-y','-framerate','30','-i',path.join(dir,'f%05d.jpg'),'-c:v','libx264','-preset','veryfast','-crf','19','-pix_fmt','yuv420p','-movflags','+faststart',out],{timeout:240000});
    const bytes=await fs.readFile(out); if(bytes.length<1000) throw new Error('PixiJS produced an empty MP4.');
    return bytes;
  } finally { await browser.close(); await fs.rm(dir,{recursive:true,force:true}); }
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
  const vars = JSON.stringify({ kicker: safe(req.kicker).slice(0, 60), title: safe(req.title).slice(0, 90), labels: JSON.stringify(labels), bg: themeBg(req.theme), portrait: height > width ? 1 : 0, kind: 'flow' });
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



// ---------------------------------------------------------------- THREE_JS animated clip: the timeline renderer captured at 30 fps, encoded with ffmpeg
async function threeClip(req) {
  const width = clampInt(req.width, 320, 1920, 1920), height = clampInt(req.height, 240, 1920, 1080);
  const total = Math.min(120, Math.max(2, Number(req.total) || 6));
  const starts = (Array.isArray(req.starts) ? req.starts : []).map(Number).filter(Number.isFinite).slice(0, 12);
  const code = await bundle('three', `import * as THREE from 'three'; import { createStudio } from './three-scene.js'; window.THREE = THREE; window.createStudio = createStudio;`);
  const browser = await chromium.launch(launchOptions());
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 't3d-'));
  try {
    const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
    await lockdown(page, false);
    await page.setContent(`<html><body style="margin:0;background:${themeBg(req.theme)};overflow:hidden"><canvas id="c" style="width:${width}px;height:${height}px;display:block"></canvas><script>${code}</script></body></html>`);
    await page.evaluate(({ kicker, title, narration, labels, kind, width, height, count, bg, light }) => {
      window.studio = window.createStudio(window.THREE, { kicker, title, narration, labels, kind, width, height, count, bg, light });
    }, { kicker: safe(req.kicker).slice(0, 60), title: safe(req.title), narration: safe(req.narration), labels: (req.labels || []).slice(0, 6).map(safe), kind: String(req.sceneKind || ''), width, height, count: Math.max(1, starts.length), bg: themeBg(req.theme), light: themeLight(req.theme) });
    const frames = Math.ceil(total * 30);
    for (let i = 0; i < frames; i++) {
      await page.evaluate(({ t, starts }) => window.studio.renderTime(t, starts), { t: i / 30, starts });
      await fs.writeFile(path.join(dir, `f${String(i).padStart(5, '0')}.jpg`), await page.screenshot({ type: 'jpeg', quality: 92 }));
    }
    const out = path.join(dir, 'clip.mp4');
    await runBin(FFMPEG, ['-y', '-framerate', '30', '-i', path.join(dir, 'f%05d.jpg'), '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '18', '-pix_fmt', 'yuv420p', out], { timeout: 180000 });
    return await fs.readFile(out);
  } finally { await browser.close(); await fs.rm(dir, { recursive: true, force: true }); }
}

// ---------------------------------------------------------------- MOTION_CANVAS animated clip (Revideo)
async function renderClip(req) {
  const width = clampInt(req.width, 320, 1920, 1920), height = clampInt(req.height, 240, 1920, 1080);
  const labels = (Array.isArray(req.labels) ? req.labels : []).map(safe).filter(Boolean).slice(0, 5);
  const total = Math.min(120, Math.max(2, Number(req.total) || 6));
  const reveals = (Array.isArray(req.reveals) ? req.reveals : []).slice(0, 5).map(v => Math.min(total, Math.max(0, Number(v) || 0)));
  // template "board": cards / compare tables / bars drawn and animated from the plan's own elements (text only, validated and trimmed)
  const template = ['board', 'code', 'diagram', 'formula', 'chart', 'quiz', 'forces', 'cycle', 'mascot'].includes(String(req.template)) ? String(req.template) : 'flow';
  const codeText = template === 'code' ? String(req.code ?? '').replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '').split('\n').slice(0, 14).map(l => l.slice(0, 90)).join('\n') : '';
  const codeFile = safe(req.file).slice(0, 40) || 'Example.java';
  const elements = (Array.isArray(req.elements) ? req.elements : []).slice(0, 6).filter(e => e && ['card', 'tile', 'note', 'compare', 'bars'].includes(String(e.type))).map(e => ({
    type: String(e.type), title: safe(e.title).slice(0, 80), text: safe(e.text).slice(0, 120), lines: (Array.isArray(e.lines) ? e.lines : []).slice(0, 5).map(l => safe(l).slice(0, 90)),
    x: Math.min(95, Math.max(5, Number(e.x) || 50)), y: Math.min(90, Math.max(20, Number(e.y) || 46)), w: Math.min(94, Math.max(20, Number(e.w) || 60)), at: Math.max(0, Math.min(11, Math.floor(Number(e.at) || 0))) }));
  const starts = (Array.isArray(req.starts) ? req.starts : []).map(Number).filter(Number.isFinite).slice(0, 12);
  // template "diagram": the planner's own tiles / actors / arrows / containers, text and numbers only, every field trimmed and checked
  const DIAGRAM_TYPES = ['tile', 'actor', 'button', 'heading', 'pill', 'label', 'note', 'quote', 'card', 'container', 'arrow', 'message', 'icon'];
  const idOf = v => String(v ?? '').replace(/[^A-Za-z0-9_-]/g, '').slice(0, 24);
  const diagramEls = template !== 'diagram' ? [] : (Array.isArray(req.elements) ? req.elements : []).slice(0, 16).filter(e => e && DIAGRAM_TYPES.includes(String(e.type))).map(e => ({
    id: idOf(e.id), type: String(e.type), text: safe(e.text).slice(0, 120), title: safe(e.title).slice(0, 80), lines: (Array.isArray(e.lines) ? e.lines : []).slice(0, 5).map(l => safe(l).slice(0, 90)),
    icon: idOf(e.icon).slice(0, 20), color: String(e.color ?? '').replace(/[^A-Za-z0-9#]/g, '').slice(0, 12), style: String(e.style ?? '').replace(/[^A-Za-z0-9-]/g, '').slice(0, 12), from: idOf(e.from), to: idOf(e.to),
    x: Math.min(100, Math.max(0, Number(e.x ?? 50))), y: Math.min(100, Math.max(0, Number(e.y ?? 50))), w: e.w == null ? undefined : Math.min(100, Math.max(0, Number(e.w))), h: e.h == null ? undefined : Math.min(100, Math.max(0, Number(e.h))),
    at: Math.max(0, Math.min(11, Math.floor(Number(e.at) || 0))), dimAt: e.dimAt == null ? undefined : Math.max(0, Math.min(11, Math.floor(Number(e.dimAt) || 0))),
    highlightAt: (Array.isArray(e.highlightAt) ? e.highlightAt : []).slice(0, 4).map(h => Math.max(0, Math.min(11, Math.floor(Number(h) || 0)))) }));
  // template "formula": LaTeX typeset by MathJax inside the page: macros that load files or run code are removed first
  const texOf = v => String(v ?? '').replace(/[\u0000-\u001f]/g, ' ').replace(/\\(?:require|href|url|class|style|cssId|unicode|includegraphics|input|include|write|def|let|newcommand|renewcommand|DeclareMathOperator)\b/g, '').slice(0, 160);
  const formulas = template !== 'formula' ? [] : (Array.isArray(req.formulas) ? req.formulas : []).slice(0, 4).map(f => ({ tex: texOf(f.tex), label: safe(f.label).slice(0, 60), caption: safe(f.caption).slice(0, 130), at: Math.max(0, Math.min(11, Math.floor(Number(f.at) || 0))) })).filter(f => f.tex.trim());
  if (template === 'formula' && !formulas.length) throw new Error('formula clip needs at least one formula');
  // template "chart": numbers only, labels trimmed
  const chartData = template !== 'chart' ? [] : (Array.isArray(req.data) ? req.data : []).slice(0, 12).map(d => ({ label: safe(d.label).slice(0, 20), value: Number(d.value) })).filter(d => Number.isFinite(d.value));
  const chartStyle = ['line', 'area', 'bar', 'donut'].includes(String(req.chartStyle)) ? String(req.chartStyle) : 'line';
  if (template === 'chart' && chartData.length < 2) throw new Error('chart clip needs at least two data points');
  // science / exam templates: quiz (question, options, which one is right, which sentence gives the answer), forces (free-body diagram), cycle (a ring of stages)
  const options = template !== 'quiz' ? [] : (Array.isArray(req.options) ? req.options : []).slice(0, 5).map(o => safe(o).slice(0, 70)).filter(Boolean);
  if (template === 'quiz' && options.length < 2) throw new Error('quiz clip needs a question and at least two options');
  const DIRS = ['right', 'left', 'up', 'down', 'upright', 'upleft', 'downright', 'downleft'];
  const forceList = template !== 'forces' ? [] : (Array.isArray(req.forces) ? req.forces : []).slice(0, 6).map(f => ({ dir: String(f.dir ?? '').toLowerCase(), symbol: safe(f.symbol).slice(0, 8), name: safe(f.name).slice(0, 32), size: Math.min(2.5, Math.max(0.3, Number(f.size) || 1)) })).filter(f => DIRS.includes(f.dir));
  if (template === 'forces' && forceList.length < 2) throw new Error('forces clip needs at least two forces');
  const cycleSteps = template !== 'cycle' ? [] : (Array.isArray(req.steps) ? req.steps : []).slice(0, 8).map(x => safe(x).slice(0, 40)).filter(Boolean);
  if (template === 'cycle' && cycleSteps.length < 3) throw new Error('cycle clip needs at least three stages');
  // mascot: a name, a mood, the lines he says (each at a sentence) and optionally the loudness envelope of the narration (0..1 per frame)
  const speeches = template !== 'mascot' ? [] : (Array.isArray(req.speeches) ? req.speeches : []).slice(0, 8).map(x => ({ text: safe(x.text).slice(0, 90), at: Math.max(0, Math.min(11, Math.floor(Number(x.at) || 0))) })).filter(x => x.text);
  const mouthEnv = template !== 'mascot' ? [] : (Array.isArray(req.mouth) ? req.mouth : []).slice(0, 4000).map(v => Math.max(0, Math.min(1, Number(v) || 0)));
  const mood = ['happy', 'wow', 'thinking', 'wave'].includes(String(req.mood)) ? String(req.mood) : 'happy';
  if (template === 'mascot' && !speeches.length) throw new Error('mascot clip needs at least one line to say');
  if (template === 'diagram' && !diagramEls.length) throw new Error('diagram clip needs at least one tile, actor, container or arrow');
  if (template === 'board' && !elements.length) throw new Error('board clip needs at least one card, compare or bars element');
  if (template === 'code' && !codeText.trim()) throw new Error('code clip needs the code to show');
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'clip-'));
  try {
    const out = path.join(dir, 'clip.mp4');
    const params = path.join(dir, 'params.json');
    let lastError = null;
    // the very first render in a fresh image makes Vite re-optimise its dependencies and reload the page mid-navigation: a second attempt succeeds
    for (let attempt = 0; attempt < 3; attempt++) {
      await fs.writeFile(params, JSON.stringify({ template, mood, speeches, mouth: mouthEnv, name: safe(req.name).slice(0, 20) || 'Ollie', light: themeLight(req.theme), question: safe(req.question).slice(0, 140), options, correct: Math.max(0, Math.min(5, Math.floor(Number(req.correct) || 0))), revealAt: Math.max(0, Math.min(11, Math.floor(Number(req.revealAt ?? 1) || 0))), object: safe(req.object).slice(0, 30), forces: forceList, centre: safe(req.centre).slice(0, 40), steps: cycleSteps, elements: template === 'diagram' ? diagramEls : elements, formulas, data: chartData, chartStyle, unit: safe(req.unit).slice(0, 12), caption: safe(req.caption).slice(0, 90), starts, code: codeText, file: codeFile, out, kicker: safe(req.kicker).slice(0, 60), title: safe(req.title).slice(0, 90), labels, bg: themeBg(req.theme), portrait: height > width, reveals, total, width, height, port: 9100 + Math.floor(Math.random() * 800) }));
      try { await runNode([path.join(REVIDEO_DIR, 'render-clip.mjs'), params], { cwd: REVIDEO_DIR, timeout: 180000, env: { ...process.env, DISABLE_TELEMETRY: 'true' } }); return await fs.readFile(out); }
      catch (e) { lastError = e; }
    }
    throw new Error('Revideo render failed: ' + String(lastError?.stderr || lastError?.message || lastError).split('\n').filter(l => l.trim() && !l.includes('CJS build')).slice(-2).join(' ').slice(0, 300));
  } finally { await fs.rm(dir, { recursive: true, force: true }); }
}

app.post('/render-clip', async (req, res) => {
  const engine = String(req.body?.engine || 'MOTION_CANVAS').toUpperCase();
  const started = Date.now();
  try {
    if (['THREE_JS','PIXIJS'].includes(engine)) {
      if (!ffmpegOk) throw new Error('Animated visual clips need ffmpeg in the renderer image');
      const clip = await withSlot(() => engine === 'PIXIJS' ? pixiClip(req.body || {}) : threeClip(req.body || {}));
      recordMetric(engine + '_CLIP', true, Date.now() - started);
      return res.type('video/mp4').send(clip);
    }
    if (!clipsAvailable) throw new Error('Animated clips are not available: ' + clipsReason);
    if (engine !== 'MOTION_CANVAS') return res.status(400).json({ error: 'Only MOTION_CANVAS clips are supported' });
    const mp4 = await withSlot(() => renderClip(req.body || {}));
    recordMetric(engine + '_CLIP', true, Date.now() - started);
    res.type('video/mp4').send(mp4);
  } catch (e) {
    recordMetric(engine + '_CLIP', false, Date.now() - started);
    res.status(503).json({ error: String(e?.message || e).split('\n')[0], rendererStatus: 'FAILED' });
  }
});

app.post('/render-frames', async (req, res) => {
  const p = req.body || {};
  const engine = String(p.engine || '').toUpperCase();
  const count = clampInt(p.steps, 2, 8, 4);
  const started = Date.now();
  try {
    let out;
    if (engine === 'MOTION_CANVAS') out = await withSlot(() => motionFrames(p, count));
    else if (engine === 'THREE_JS') out = await withSlot(() => threeFrames(p, count));
    else if (engine === 'PIXIJS') out = await withSlot(() => pixiFrames(p, count));
    else if (engine === 'RIVE') out = await withSlot(() => riveFrames(p, count));
    else return res.status(400).json({ error: 'Unsupported renderer ' + engine });
    if (!Array.isArray(out.frames) || out.frames.length < 2) throw new Error('Renderer returned fewer than two frames.');
    if (new Set(out.frames).size < 2 && count > 1) throw new Error(engine + ' returned identical frames; refusing a static result for an animated scene.');
    recordMetric(engine + '_FRAMES', true, Date.now() - started);
    res.json({ engine, rendererStatus: 'RENDERED', quality: { frameCount: out.frames.length, distinctFrames: new Set(out.frames).size, validated: true }, ...out });
  } catch (e) {
    recordMetric(engine + '_FRAMES', false, Date.now() - started);
    res.status(503).json({ error: String(e?.message || e).split('\n')[0], engine, rendererStatus: 'FAILED', quality: { validated: false } });
  }
});

const port = Number(process.env.PORT || 5020);
app.listen(port, '0.0.0.0', () => console.log(`visual-renderer listening on ${port}`));
