// node journey/render-journey.mjs <spec.json> <outDir>   spec: {kind, scene, hud, chips, width, height, duration, fps}
import * as esbuild from 'esbuild';
import { chromium } from 'playwright';
import fs from 'node:fs/promises';
import path from 'node:path';
const [specPath, outDir] = process.argv.slice(2);
const spec = JSON.parse(await fs.readFile(specPath, 'utf8'));
const here = path.dirname(new URL(import.meta.url).pathname);
const out = await esbuild.build({ entryPoints: [path.join(here, 'journey-scene.js')], bundle: true, format: 'iife', write: false, loader: { '.json': 'json' }, logLevel: 'error' });
const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM_PATH, args: ['--no-sandbox', '--use-gl=angle', '--use-angle=swiftshader', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: spec.width, height: spec.height } });
page.on('pageerror', e => console.log('pageerror', e.message));
await page.setContent(`<html><body style="margin:0;background:#050b1a;overflow:hidden;position:relative"><canvas id="c" style="width:${spec.width}px;height:${spec.height}px;display:block"></canvas><script>${out.outputFiles[0].text}</script></body></html>`);
await page.evaluate(o => { window.J = window.createJourney(o); }, { width: spec.width, height: spec.height, kind: spec.kind, scene: spec.scene, hud: spec.hud, chips: spec.chips, bloom: spec.bloom });
await fs.mkdir(outDir, { recursive: true });
const n = Math.ceil(spec.duration * spec.fps), t0 = Date.now();
for (let i = 0; i < n; i++) {
  await page.evaluate(([t, T, dt]) => window.J.render(t, T, dt), [i / spec.fps, spec.duration, 1 / spec.fps]);
  await fs.writeFile(path.join(outDir, `f${String(i).padStart(5, '0')}.jpg`), await page.screenshot({ type: 'jpeg', quality: 93 }));
}
console.log(`${spec.kind}: ${n} frames in ${Math.round((Date.now() - t0) / 1000)}s`);
await browser.close();
