import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { build } from 'esbuild';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');

// Renders the real VideoExplainer on the server (via @remotion/player SSR) at many timestamps.
// It cannot replace a visual check in Chromium, but it catches runtime errors and NaN/undefined layout math
// for every theme × aspect ratio without needing a browser.
test('composition renders without NaN/undefined across themes × aspects × timestamps', async () => {
  const out = path.join(root, '.ssr.test.bundle.mjs');
  await build({ entryPoints: [path.join(here, 'ssr.entry.jsx')], bundle: true, platform: 'node', format: 'esm', packages: 'external', outfile: out, loader: { '.jsx': 'jsx', '.json': 'json' }, jsx: 'automatic', logLevel: 'error' });
  const origErr = console.error;
  console.error = () => {}; // React's useLayoutEffect SSR warning from the Player is expected noise
  try {
    const mod = await import(`${pathToFileURL(out).href}?t=${Date.now()}`);
    const rows = mod.default;
    assert.equal(rows.length, 2 * 3 * 14);
    const bad = rows.filter((r) => r.nan);
    assert.equal(bad.length, 0, `NaN/undefined in ${bad.map((b) => `${b.W}x${b.H}/${b.themeId}/f${b.f}`).join(', ')}`);
    assert.ok(rows.some((r) => /Thread 1/.test(r.html)), 'threads primitive rendered');
    assert.ok(rows.some((r) => /Lock it\. Always\./.test(r.html)), 'callout primitive rendered');
  } finally {
    console.error = origErr;
    fs.rmSync(out, { force: true });
  }
});
