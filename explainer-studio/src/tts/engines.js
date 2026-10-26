import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { config } from '../config.js';

/**
 * Run a CLI, feed `stdinText` on stdin (NOT through a shell — narration text is LLM output and must never
 * be interpolated into a shell string; `exec` with user/LLM text is a command-injection hole).
 */
export function runCli(bin, args, { stdinText = '', env = {}, timeoutMs, signal } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(bin, args, { stdio: ['pipe', 'pipe', 'pipe'], env: { ...process.env, ...env } });
    let stderr = '';
    let settled = false;
    const done = (err) => { if (settled) return; settled = true; clearTimeout(timer); signal?.removeEventListener('abort', onAbort); err ? reject(err) : resolve({ stderr }); };
    const onAbort = () => { child.kill('SIGKILL'); done(Object.assign(new Error('aborted'), { name: 'AbortError' })); };
    const timer = setTimeout(() => { child.kill('SIGKILL'); done(new Error(`${path.basename(bin)} timed out after ${timeoutMs}ms`)); }, timeoutMs ?? config.tts.timeoutMs);
    signal?.addEventListener('abort', onAbort, { once: true });
    child.stderr.on('data', (d) => { stderr = (stderr + d).slice(-2000); });
    child.stdout.on('data', () => {});
    child.on('error', (e) => done(new Error(e.code === 'ENOENT' ? `${bin} not found. Install it or set PIPER_BIN / PYTHON_BIN.` : e.message)));
    child.on('close', (code) => (code === 0 ? done() : done(new Error(`${path.basename(bin)} exited ${code}: ${stderr.slice(-400)}`))));
    child.stdin.on('error', () => {}); // EPIPE if the process dies early; surfaced via 'close'
    child.stdin.end(`${stdinText}\n`);
  });
}

export async function synthPiper({ text, voice, params, outFile, signal }) {
  const model = path.join(config.tts.voicesDir, `${voice.model}.onnx`);
  if (!fs.existsSync(model)) throw new Error(`Piper voice model missing: ${model}. See README → Voices.`);
  const args = [
    '--model', model, '--output_file', outFile,
    '--length_scale', String(params.lengthScale), '--noise_scale', String(params.noiseScale), '--noise_w', String(params.noiseW),
    '--sentence_silence', '0',
  ];
  const binDir = path.dirname(config.tts.piperBin);
  await runCli(config.tts.piperBin, args, {
    stdinText: text, signal,
    env: binDir && binDir !== '.' ? { LD_LIBRARY_PATH: `${binDir}:${process.env.LD_LIBRARY_PATH || ''}` } : {},
  });
}

/** AI Story Studio's own Kokoro sidecar: POST {text, voice, speed} → audio/wav. No model inside this image. */
export async function synthKokoroHttp({ text, voice, params, outFile, signal }) {
  const speed = Math.min(1.3, Math.max(0.7, 1 / params.lengthScale));
  const sig = AbortSignal.any([...(signal ? [signal] : []), AbortSignal.timeout(Math.max(config.tts.timeoutMs, 120_000))]);
  let res;
  try {
    res = await fetch(`${config.tts.kokoroUrl}/api/tts`, {
      method: 'POST', headers: { 'content-type': 'application/json' }, signal: sig,
      body: JSON.stringify({ text, voice: voice.voice, speed: +speed.toFixed(3) }),
    });
  } catch (e) {
    if (signal?.aborted) throw Object.assign(new Error('aborted'), { name: 'AbortError' });
    throw new Error(`Kokoro service unreachable at ${config.tts.kokoroUrl} (${e.message}). Start it: COMPOSE_PROFILES=kokoro docker compose up -d tts-kokoro`);
  }
  if (!res.ok) throw new Error(`Kokoro service HTTP ${res.status}: ${(await res.text().catch(() => '')).slice(0, 200)}`);
  fs.writeFileSync(outFile, Buffer.from(await res.arrayBuffer()));
}

export async function synthKokoro({ text, voice, params, outFile, signal }) {
  if (config.tts.kokoroUrl) return synthKokoroHttp({ text, voice, params, outFile, signal });
  // Kokoro's `speed` is the inverse of Piper's length_scale.
  const speed = (1 / params.lengthScale).toFixed(3);
  await runCli(config.tts.pythonBin, [config.tts.kokoroScript, '--voice', voice.voice, '--speed', speed, '--out', outFile, '--model-dir', config.tts.kokoroModelDir], { stdinText: text, signal, timeoutMs: 120_000 });
}

export const synthesize = (engine, opts) => (engine === 'kokoro' ? synthKokoro(opts) : synthPiper(opts));
