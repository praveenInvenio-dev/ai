import ffmpeg from 'fluent-ffmpeg';
import fs from 'node:fs/promises';
import path from 'node:path';
import { config } from '../config.js';

ffmpeg.setFfmpegPath(config.ffmpeg.ffmpegPath);
ffmpeg.setFfprobePath(config.ffmpeg.ffprobePath);

function run(cmd, { signal, onProgress } = {}) {
  return new Promise((resolve, reject) => {
    const onAbort = () => { try { cmd.kill('SIGKILL'); } catch {} reject(Object.assign(new Error('aborted'), { name: 'AbortError' })); };
    signal?.addEventListener('abort', onAbort, { once: true });
    cmd
      .on('progress', (p) => onProgress?.(p))
      .on('error', (e, _o, stderr) => { signal?.removeEventListener('abort', onAbort); reject(new Error(`ffmpeg: ${e.message}\n${String(stderr || '').slice(-600)}`)); })
      .on('end', () => { signal?.removeEventListener('abort', onAbort); resolve(); })
      .run();
  });
}

export const probe = (file) => new Promise((res, rej) => ffmpeg.ffprobe(file, (e, d) => (e ? rej(e) : res(d))));
export const probeDurationMs = async (file) => Math.round(Number((await probe(file)).format.duration) * 1000);

export async function checkTools() {
  const out = {};
  for (const [name, fn] of [['ffmpeg', (cb) => ffmpeg.getAvailableFormats(cb)], ['ffprobe', (cb) => ffmpeg(undefined).ffprobe ? cb(null) : cb(null)]]) {
    out[name] = await new Promise((r) => fn((e) => r(!e)));
  }
  return out;
}

/** Trim leading/trailing silence, de-rumble, normalise to 44.1 kHz mono s16, micro-fade the edges (no clicks). */
export function cleanSpeech(inFile, outFile, opts) {
  const af = [
    'highpass=f=60',
    'silenceremove=start_periods=1:start_silence=0.02:start_threshold=-48dB:detection=peak',
    'areverse',
    'silenceremove=start_periods=1:start_silence=0.05:start_threshold=-48dB:detection=peak',
    'afade=t=in:d=0.012',
    'areverse',
    'afade=t=in:d=0.006',
    'aresample=44100',
  ].join(',');
  return run(ffmpeg(inFile).audioFilters(af).audioChannels(1).audioFrequency(44100).audioCodec('pcm_s16le').format('wav').output(outFile), opts);
}

/** Broadcast-ish voice chain: gentle compression + loudness normalisation (-16 LUFS suits YouTube & Reels). */
export function masterVoice(inFile, outFile, opts) {
  const af = 'highpass=f=70,acompressor=threshold=-20dB:ratio=2.5:attack=8:release=120:makeup=2,loudnorm=I=-16:TP=-1.5:LRA=9,aresample=48000';
  return run(ffmpeg(inFile).audioFilters(af).audioChannels(1).audioCodec('pcm_s16le').format('wav').output(outFile), opts);
}

export async function pickMusic(seed = 0) {
  try {
    const files = (await fs.readdir(config.ffmpeg.musicDir)).filter((f) => /\.(mp3|wav|m4a|ogg)$/i.test(f)).sort();
    return files.length ? path.join(config.ffmpeg.musicDir, files[seed % files.length]) : null;
  } catch { return null; }
}

/**
 * Final assembly: silent Remotion video + mastered narration (+ optional ducked music) → compressed MP4.
 * +faststart makes it stream in the browser <video> immediately; yuv420p/high profile is what IG & YouTube want.
 */
export function muxFinal({ video, audio, music, durationSec, out, crf, onProgress, signal }) {
  const cmd = ffmpeg(video).input(audio);
  // Motion graphics = big flat areas + soft gradients: tune=animation + aq-mode 3 keep gradients free of banding/blocks,
  // and explicit BT.709 tags stop players from guessing the colour space (washed-out colours on some phones).
  const common = ['-c:v libx264', `-preset ${config.render.finalPreset}`, `-crf ${crf}`, '-tune animation', '-x264-params aq-mode=3:aq-strength=0.9',
    '-pix_fmt yuv420p', '-profile:v high', '-colorspace bt709', '-color_primaries bt709', '-color_trc bt709', '-color_range tv',
    '-movflags +faststart', '-c:a aac', '-b:a 192k', '-ar 48000', `-t ${durationSec.toFixed(3)}`];
  if (music) {
    cmd.input(music);
    const fade = Math.max(0, durationSec - 2.5).toFixed(2);
    cmd.complexFilter([
      `[2:a]aloop=loop=-1:size=2147483647,atrim=0:${durationSec.toFixed(2)},volume=0.30,afade=t=in:d=1.5,afade=t=out:st=${fade}:d=2.5[m]`,
      '[m][1:a]sidechaincompress=threshold=0.03:ratio=10:attack=15:release=450[duck]',
      '[1:a][duck]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.95[aout]',
    ]);
    cmd.outputOptions(['-map 0:v', '-map [aout]', ...common]);
  } else {
    cmd.outputOptions(['-map 0:v', '-map 1:a', ...common]);
  }
  return run(cmd.output(out), { signal, onProgress });
}
