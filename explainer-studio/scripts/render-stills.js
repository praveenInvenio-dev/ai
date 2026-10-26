/**
 * Quick visual QA: renders a handful of stills from a timeline (default: samples/sample-timeline.json)
 * without TTS / Ollama / FFmpeg.  THEME=dracula ASPECT=16:9 OUT=./data/stills TIMELINE=path node scripts/render-stills.js
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { selectComposition, renderStill } from '@remotion/renderer';
import { config, ROOT } from '../src/config.js';
import { ASPECTS } from '../src/presets.js';
import { getBundle, chromiumOptions } from '../src/pipeline/render.js';

const env = process.env;
const timeline = JSON.parse(await fs.readFile(env.TIMELINE || path.join(ROOT, 'samples', 'sample-timeline.json'), 'utf8'));
const theme = env.THEME || 'dracula';
const aspect = env.ASPECT || '16:9';
const fps = Number(env.FPS || 30);
const { width, height } = ASPECTS[aspect];
const out = path.resolve(env.OUT || path.join(config.dataDir, 'stills'));
await fs.mkdir(out, { recursive: true });
const serveUrl = await getBundle((m) => console.log(m));
const inputProps = { timeline, themeId: theme, platformId: 'yt_quick', fps, width, height };
const browserExecutable = config.render.browserExecutable || undefined;
const chromeMode = config.render.chromeMode;
const composition = await selectComposition({ serveUrl, id: 'Explainer', inputProps, chromiumOptions, browserExecutable, chromeMode });
const secs = (env.AT || '1,4,9,15,22,30,40,50').split(',').map(Number).filter((s) => s * 1000 < timeline.totalMs);
for (const s of secs) {
  const frame = Math.min(composition.durationInFrames - 1, Math.round(s * fps));
  const file = path.join(out, `${theme}-${aspect.replace(':', 'x')}-${String(s).padStart(3, '0')}s.jpg`);
  await renderStill({ composition, serveUrl, inputProps, chromiumOptions, browserExecutable, chromeMode, frame, output: file, imageFormat: 'jpeg', jpegQuality: 88, scale: Number(env.SCALE || 0.5) });
  console.log('still', file);
}
