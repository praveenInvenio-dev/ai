// One clip per process: `node render-clip.mjs params.json`. params: { out, title, labels, bg, portrait, reveals, total, width, height, port }.
// Telemetry is switched off; the page only ever talks to the local Vite server Revideo starts for the render.
process.env.DISABLE_TELEMETRY = 'true';
import fs from 'node:fs/promises';
import path from 'node:path';
import { renderVideo } from '@revideo/renderer';

const p = JSON.parse(await fs.readFile(process.argv[2], 'utf8'));
await renderVideo({
  projectFile: p.template === 'board' ? './src/board-project.ts' : p.template === 'code' ? './src/code-project.ts' : p.template === 'diagram' ? './src/diagram-project.ts' : p.template === 'formula' ? './src/formula-project.ts' : p.template === 'chart' ? './src/chart-project.ts' : ['quiz', 'forces', 'cycle', 'mascot'].includes(p.template) ? `./src/${p.template}-project.ts` : './src/project.ts',
  variables: p.template === 'mascot'
    ? { kicker: p.kicker || '', title: p.title, bg: p.bg, light: p.light ? 1 : 0, name: p.name || 'Ollie', mood: p.mood || 'happy', speeches: JSON.stringify(p.speeches || []), starts: JSON.stringify(p.starts || []), mouth: JSON.stringify(p.mouth || []), total: p.total }
    : p.template === 'quiz'
    ? { kicker: p.kicker || '', title: p.title, bg: p.bg, light: p.light ? 1 : 0, question: p.question || '', options: JSON.stringify(p.options || []), correct: p.correct || 0, revealAt: p.revealAt ?? 1, starts: JSON.stringify(p.starts || []), total: p.total }
    : p.template === 'forces'
    ? { kicker: p.kicker || '', title: p.title, bg: p.bg, light: p.light ? 1 : 0, object: p.object || 'Body', forces: JSON.stringify(p.forces || []), starts: JSON.stringify(p.starts || []), total: p.total }
    : p.template === 'cycle'
    ? { kicker: p.kicker || '', title: p.title, bg: p.bg, light: p.light ? 1 : 0, centre: p.centre || '', steps: JSON.stringify(p.steps || []), starts: JSON.stringify(p.starts || []), total: p.total }
    : p.template === 'formula'
    ? { kicker: p.kicker || '', title: p.title, bg: p.bg, formulas: JSON.stringify(p.formulas || []), starts: JSON.stringify(p.starts || []), total: p.total }
    : p.template === 'chart'
    ? { kicker: p.kicker || '', title: p.title, bg: p.bg, style: p.chartStyle || 'line', unit: p.unit || '', caption: p.caption || '', data: JSON.stringify(p.data || []), starts: JSON.stringify(p.starts || []), total: p.total }
    : p.template === 'diagram'
    ? { kicker: p.kicker || '', title: p.title, bg: p.bg, elements: JSON.stringify(p.elements || []), starts: JSON.stringify(p.starts || []), total: p.total }
    : p.template === 'code'
    ? { kicker: p.kicker || '', title: p.title, bg: p.bg, file: p.file || 'Example.java', language: p.language || 'java', code: p.code || '', starts: JSON.stringify(p.starts || []), total: p.total }
    : p.template === 'board'
    ? { kicker: p.kicker || '', title: p.title, bg: p.bg, elements: JSON.stringify(p.elements || []), starts: JSON.stringify(p.starts || []), total: p.total }
    : { kicker: p.kicker || '', title: p.title, labels: JSON.stringify(p.labels), bg: p.bg, portrait: p.portrait ? 1 : 0, reveals: JSON.stringify(p.reveals), total: p.total },
  settings: {
    outFile: path.basename(p.out), outDir: path.dirname(p.out), workers: 1, logProgress: false, viteBasePort: p.port,
    puppeteer: { headless: true, executablePath: process.env.REVIDEO_CHROME || undefined, args: ['--no-sandbox', '--disable-dev-shm-usage'] },
    projectSettings: { size: { x: p.width, y: p.height }, exporter: { name: '@revideo/core/ffmpeg', options: { format: 'mp4' } } },
  },
});
console.log('done');
process.exit(0);
