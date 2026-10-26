import path from 'node:path';
import fs from 'node:fs/promises';
import { bundle } from '@remotion/bundler';
import { renderMedia, renderStill, selectComposition, makeCancelSignal } from '@remotion/renderer';
import { config, ROOT } from '../config.js';

let bundlePromise = null;
let bundleDir = null;

/** Webpack-bundle the Remotion project once per process (≈10-30 s) and reuse for every job. */
export function getBundle(log) {
  if (!bundlePromise) {
    log?.('Bundling Remotion project (first run only)…', 'render');
    bundlePromise = bundle({
      entryPoint: path.join(ROOT, 'remotion', 'index.js'),
      onProgress: (p) => { if (p % 25 === 0) log?.(`  bundle ${p}%`, 'render'); },
    }).then((dir) => { bundleDir = dir; return dir; }).catch((e) => { bundlePromise = null; throw e; });
  }
  return bundlePromise;
}
export const bundleReady = () => !!bundleDir;

export const chromiumOptions = { disableWebSecurity: false, ignoreCertificateErrors: false, gl: config.render.gl };
const browserExecutable = config.render.browserExecutable || undefined;
const chromeMode = config.render.chromeMode;

/** Remotion needs integer output sizes: snap the draft scale to values that give whole, even dimensions for 1080/1920 sources. */
export const snapScale = (s) => [0.5, 0.75, 1].reduce((best, c) => (Math.abs(c - s) < Math.abs(best - s) ? c : best), 1);
const renderScale = snapScale(config.render.scale);

/** Render the SILENT visuals (audio is mastered + muxed with FFmpeg afterwards). */
export async function renderVisuals({ timeline, themeId, platformId, fps, width, height, outFile, thumbFile, onProgress, log, signal }) {
  const serveUrl = await getBundle(log);
  const inputProps = { timeline, themeId, platformId, fps, width, height };
  const composition = await selectComposition({ serveUrl, id: 'Explainer', inputProps, chromiumOptions, browserExecutable, chromeMode, timeoutInMilliseconds: config.render.timeoutMs });
  log?.(`Composition: ${composition.width}x${composition.height} @ ${composition.fps}fps · ${composition.durationInFrames} frames`, 'render');

  const { cancelSignal, cancel } = makeCancelSignal();
  const onAbort = () => cancel();
  signal?.addEventListener('abort', onAbort, { once: true });
  try {
    let lastLogged = -1;
    await renderMedia({
      composition, serveUrl, inputProps, chromiumOptions, browserExecutable, chromeMode, cancelSignal, scale: renderScale,
      codec: 'h264', outputLocation: outFile, muted: true, pixelFormat: 'yuv420p',
      crf: config.render.intermediateCrf, x264Preset: 'veryfast', imageFormat: 'jpeg', jpegQuality: config.render.jpegQuality,
      concurrency: config.render.concurrency, timeoutInMilliseconds: config.render.timeoutMs,
      onProgress: ({ renderedFrames, encodedFrames, progress }) => {
        onProgress?.(progress);
        const step = Math.max(1, Math.floor(composition.durationInFrames / 50));
        if (renderedFrames - lastLogged >= step || renderedFrames === composition.durationInFrames) {
          lastLogged = renderedFrames;
          log?.(`Rendering Frame ${renderedFrames}/${composition.durationInFrames} (encoded ${encodedFrames})`, 'render');
        }
      },
    });
    if (thumbFile) {
      // The thumbnail frame: ~2.2 s in, when the hook text + first visual are both on screen.
      const frame = Math.min(composition.durationInFrames - 1, Math.round(2.2 * composition.fps));
      await renderStill({ composition, serveUrl, inputProps, chromiumOptions, browserExecutable, chromeMode, scale: renderScale, frame, output: thumbFile, imageFormat: 'jpeg', jpegQuality: 90 });
    }
  } catch (e) {
    await fs.rm(outFile, { force: true }).catch(() => {});
    if (signal?.aborted) throw Object.assign(new Error('aborted'), { name: 'AbortError' });
    throw e;
  } finally {
    signal?.removeEventListener('abort', onAbort);
  }
}
