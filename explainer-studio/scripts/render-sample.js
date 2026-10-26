/**
 * Renders the sample video through the REAL pipeline (TTS → timeline → master audio → Remotion → FFmpeg),
 * skipping only the Ollama step (the script is hand-written in sample-scenes.js).
 *
 *   VOICE=kokoro_michael ASPECT=9:16 THEME=dracula FPS=30 node scripts/render-sample.js
 *   (BROWSER_EXECUTABLE / RENDER_SCALE / TTS_ENGINE etc. work as for the server)
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { config } from '../src/config.js';
import { ASPECTS } from '../src/presets.js';
import { DOMAINS } from '../src/domains/index.js';
import { synthesizeAll } from '../src/tts/index.js';
import { buildTimeline, toSubtitles } from '../src/timeline/build.js';
import { assembleWav } from '../src/media/wav.js';
import { masterVoice, muxFinal, probeDurationMs } from '../src/media/ffmpeg.js';
import { renderVisuals } from '../src/pipeline/render.js';
import { buildSampleScenes } from './sample-scenes.js';

const env = process.env;
const voice = env.VOICE || 'kokoro_michael';
const aspect = env.ASPECT || '9:16';
const theme = env.THEME || 'dracula';
const fps = Number(env.FPS || 30);
const outDir = path.resolve(env.OUT || path.join(config.dataDir, `sample-${aspect.replace(':', 'x')}-${theme}`));
const tmp = path.join(outDir, 'tmp');
await fs.mkdir(tmp, { recursive: true });

const t0 = Date.now();
const log = (m, l = 'info') => console.log(`[${((Date.now() - t0) / 1000).toFixed(0).padStart(4)}s] ${l.padEnd(6)} ${m}`);
const { outline, scenes, platform, meta } = buildSampleScenes();

let timeline, master;
if (env.REUSE) {
  // Re-render saved script + narration with new visual settings (aspect / theme / fps) — no TTS.
  timeline = JSON.parse(await fs.readFile(path.join(env.REUSE, 'timeline.json'), 'utf8'));
  master = path.join(env.REUSE, 'narration.wav');
  log(`Reusing narration from ${env.REUSE}`);
} else {
  await synthesizeAll({ scenes, voiceId: voice, platform, domain: DOMAINS.cs, workDir: tmp, log, progress: () => {} });
  const built = buildTimeline({ outline, scenes, platform, meta: { ...meta, voice } });
  timeline = built.timeline;
  const spliced = path.join(tmp, 'spliced.wav');
  master = path.join(outDir, 'narration.wav');
  const { totalMs } = await assembleWav(built.segments, spliced);
  await masterVoice(spliced, master);
  const masterMs = await probeDurationMs(master);
  log(`Narration mastered: ${(masterMs / 1000).toFixed(1)}s (timeline ${(totalMs / 1000).toFixed(1)}s, drift ${Math.abs(masterMs - totalMs).toFixed(1)}ms)`);
  await fs.writeFile(path.join(outDir, 'timeline.json'), JSON.stringify(timeline, null, 2));
}
await fs.writeFile(path.join(outDir, 'captions.srt'), toSubtitles(timeline, 'srt'));

const { width, height } = ASPECTS[aspect];
const silent = path.join(tmp, 'visuals.mp4');
let lastPct = -1;
await renderVisuals({
  timeline, themeId: theme, platformId: platform.id, fps, width, height, outFile: silent, thumbFile: path.join(outDir, 'thumbnail.jpg'),
  log: (m, l) => { if (!/^Rendering Frame/.test(m) || Number(/Frame (\d+)\//.exec(m)?.[1]) % 100 < 40) log(m, l); },
  onProgress: (p) => { const pct = Math.floor(p * 10) * 10; if (pct !== lastPct) { lastPct = pct; log(`render ${pct}%`); } },
});
const final = path.join(outDir, 'final.mp4');
await muxFinal({ video: silent, audio: master, music: null, durationSec: timeline.totalMs / 1000, out: final, crf: config.render.finalCrf });
await fs.rm(tmp, { recursive: true, force: true });
log(`DONE → ${final} (${((await fs.stat(final)).size / 1048576).toFixed(1)} MB)`);
