import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const ROOT = path.resolve(here, '..');
const env = process.env;
const int = (v, d) => (Number.isFinite(parseInt(v, 10)) ? parseInt(v, 10) : d);

/**
 * Find a Chromium Remotion can drive WITHOUT downloading one at run time.
 * Inside AI Story Studio the image is based on the Playwright image (the same one visual-renderer uses), which ships
 * chromium-headless-shell under PLAYWRIGHT_BROWSERS_PATH. Remotion's own `browser ensure` download (remotion.media)
 * is the fragile step we avoid. Returns { executable, chromeMode } or null (= let Remotion manage its own browser).
 */
function detectBrowser() {
  if (env.BROWSER_EXECUTABLE) return { executable: env.BROWSER_EXECUTABLE, chromeMode: env.CHROME_MODE || 'headless-shell' };
  const roots = [env.PLAYWRIGHT_BROWSERS_PATH, '/ms-playwright', '/opt/pw-browsers', path.join(os.homedir(), '.cache', 'ms-playwright')].filter(Boolean);
  for (const root of roots) {
    let dirs = [];
    try { dirs = fs.readdirSync(root).sort().reverse(); } catch { continue; }
    for (const d of dirs.filter((x) => x.startsWith('chromium_headless_shell-'))) {
      for (const rel of ['chrome-linux/headless_shell', 'chrome-headless-shell-linux64/chrome-headless-shell', 'chrome-linux-arm64/headless_shell']) {
        const f = path.join(root, d, rel);
        if (fs.existsSync(f)) return { executable: f, chromeMode: 'headless-shell' };
      }
    }
    for (const d of dirs.filter((x) => /^chromium-\d+$/.test(x))) {
      for (const rel of ['chrome-linux/chrome', 'chrome-linux64/chrome']) {
        const f = path.join(root, d, rel);
        if (fs.existsSync(f)) return { executable: f, chromeMode: 'chrome-for-testing' };
      }
    }
  }
  return null;
}
const browser = detectBrowser();

export const config = {
  port: int(env.PORT, 4000),
  dataDir: path.resolve(env.DATA_DIR || path.join(ROOT, 'data')),
  // URL prefix the browser uses for finished files. Behind the AI Story Studio nginx this is /explainer-media.
  publicMediaBase: (env.PUBLIC_MEDIA_BASE || '/media').replace(/\/$/, ''),
  retentionDays: int(env.RETENTION_DAYS, 14),

  ollama: {
    // OLLAMA_BASE_URL is the name the rest of AI Story Studio uses; either works.
    host: (env.OLLAMA_HOST || env.OLLAMA_BASE_URL || 'http://127.0.0.1:11434').replace(/\/$/, ''),
    model: env.OLLAMA_MODEL || 'llama3.1:8b', // `mistral` also works; llama3.1 follows schemas better
    numCtx: int(env.OLLAMA_NUM_CTX, 8192),
    temperature: Number(env.OLLAMA_TEMPERATURE ?? 0.7),
    keepAlive: env.OLLAMA_KEEP_ALIVE || '15m',
    requestTimeoutMs: int(env.OLLAMA_TIMEOUT_MS, 8 * 60 * 1000),
  },

  tts: {
    engine: env.TTS_ENGINE || 'piper', // 'piper' | 'kokoro'
    // AI Story Studio's own Kokoro sidecar (tts-kokoro, POST /api/tts). When set, Kokoro voices use it over HTTP
    // instead of the in-image Python model, so nothing extra has to be downloaded into this image.
    kokoroUrl: (env.KOKORO_TTS_URL || '').replace(/\/$/, ''),
    defaultVoice: env.DEFAULT_VOICE || '',
    piperBin: env.PIPER_BIN || 'piper',
    voicesDir: env.PIPER_VOICES_DIR || path.join(ROOT, 'voices'),
    kokoroScript: env.KOKORO_SCRIPT || path.join(ROOT, 'scripts', 'kokoro_tts.py'),
    kokoroModelDir: env.KOKORO_MODEL_DIR || '/opt/kokoro',
    pythonBin: env.PYTHON_BIN || 'python3',
    timeoutMs: int(env.TTS_TIMEOUT_MS, 60_000),
  },

  ffmpeg: {
    ffmpegPath: env.FFMPEG_PATH || 'ffmpeg',
    ffprobePath: env.FFPROBE_PATH || 'ffprobe',
    musicDir: env.MUSIC_DIR || path.join(ROOT, 'assets', 'music'), // drop royalty-free .mp3/.wav here (optional)
  },

  render: {
    // Remotion refuses a concurrency above the core count, so clamp instead of failing the job.
    concurrency: Math.min(os.cpus().length, Math.max(1, int(env.RENDER_CONCURRENCY, Math.max(1, Math.floor(os.cpus().length / 2))))),
    intermediateCrf: int(env.RENDER_CRF, 10),        // near-lossless: the final FFmpeg pass is the only real compression
    finalCrf: int(env.FINAL_CRF, 18),
    finalPreset: env.FINAL_PRESET || 'slow',
    jpegQuality: int(env.RENDER_JPEG_QUALITY, 95),
    // WebGL backend for the 3D primitives (three.js). swangle = SwiftShader, works on any CPU-only container;
    // 'angle' / 'angle-egl' use the GPU when the container has one.
    gl: env.REMOTION_GL || 'swangle',
    timeoutMs: int(env.RENDER_FRAME_TIMEOUT_MS, 120_000),
    browserExecutable: browser?.executable || null,   // auto-detected Playwright Chromium; null = Remotion downloads its own
    chromeMode: browser?.chromeMode || 'headless-shell',
    scale: Number(env.RENDER_SCALE || 1),               // 0.5-1: render smaller for fast drafts
  },

  queue: {
    concurrency: int(env.MAX_CONCURRENT_JOBS, 1), // one job at a time: Ollama + Chromium are both heavy
    maxQueued: int(env.MAX_QUEUED_JOBS, 20),
  },
};

export const jobsDir = () => path.join(config.dataDir, 'jobs');
