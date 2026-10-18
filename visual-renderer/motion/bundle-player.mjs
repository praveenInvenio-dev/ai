// Bundles the Motion Canvas player web component together with its core into one classic script the renderer serves to headless Chromium.
import * as esbuild from 'esbuild';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const r = await esbuild.build({ stdin: { contents: `import '@motion-canvas/player';`, resolveDir: here, loader: 'js' }, bundle: true, format: 'iife', write: false, minify: true });
await fs.mkdir(path.join(here, 'dist'), { recursive: true });
await fs.writeFile(path.join(here, 'dist/player.bundle.js'), r.outputFiles[0].text);
console.log('player bundle', r.outputFiles[0].text.length, 'bytes');
