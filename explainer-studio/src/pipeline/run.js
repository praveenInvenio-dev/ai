import fs from 'node:fs/promises';
import path from 'node:path';
import { config } from '../config.js';
import { PLATFORMS, ASPECTS } from '../presets.js';
import { DOMAINS } from '../domains/index.js';
import { ollamaStatus } from '../llm/ollama.js';
import { generateScript } from '../llm/generate.js';
import { synthesizeAll } from '../tts/index.js';
import { LANGUAGES } from '../shared/languages.js';
import { buildTimeline, toSubtitles, toChapters } from '../timeline/build.js';
import { assembleWav } from '../media/wav.js';
import { masterVoice, muxFinal, pickMusic, probeDurationMs } from '../media/ffmpeg.js';
import { renderVisuals } from './render.js';

/**
 * The whole lifecycle for one job:
 *  Ollama (outline → scenes) → TTS per sentence → exact timeline → master narration
 *  → Remotion frames (silent) → FFmpeg mux/compress → sidecars (srt, vtt, metadata, thumbnail) → cleanup
 *
 * `mode: 'rerender'` skips the LLM/TTS stages and rebuilds visuals from the saved script + narration
 * (change theme / fps / aspect without re-generating anything).
 */
export async function runJob({ store, job, signal }) {
  const req = job.request;
  const platform = PLATFORMS[req.platform];
  const domain = DOMAINS[req.domain];
  const dir = store.dir(job.id);
  const tmp = path.join(dir, 'tmp');
  const log = (msg, level = 'info') => store.log(job, msg, level);
  const stage = (name, progress) => store.update(job, { stage: name, progress });
  const progress = (p) => store.update(job, { progress: Math.max(job.progress, Math.min(0.99, p)) });
  const t0 = Date.now();

  store.update(job, { status: 'running', stage: 'starting', progress: 0.01, queuePosition: null });
  await fs.mkdir(tmp, { recursive: true });

  try {
    let timeline;
    let masterWav = path.join(dir, 'narration.wav');
    let outline;

    if (req.mode === 'rerender') {
      timeline = JSON.parse(await fs.readFile(path.join(dir, 'timeline.json'), 'utf8'));
      await fs.access(masterWav);
      log('Re-rendering from saved script and narration (no LLM / TTS).', 'info');
      progress(0.45);
    } else {
      // ── 1. LLM ────────────────────────────────────────────────────────────
      stage('script', 0.02);
      const model = req.model || config.ollama.model;
      const st = await ollamaStatus(model);
      if (!st.reachable) throw new Error(`Cannot reach Ollama at ${config.ollama.host} (${st.error}). Is it running? In Docker on Linux, set OLLAMA_HOST=0.0.0.0 on the host.`);
      if (!st.modelPresent) throw new Error(`Model "${model}" is not pulled. Run: ollama pull ${model}`);
      log(`Ollama ready · model ${model}`, 'llm');
      const lang = req.language || 'en';
      if (lang !== 'en') log(`Language: ${LANGUAGES[lang]?.label || lang}`, 'llm');
      const gen = await generateScript({ topic: req.topic, domain, platform, log, progress, signal, model, lang });
      outline = gen.outline;
      await fs.writeFile(path.join(dir, 'script.json'), JSON.stringify(gen, null, 2));

      // ── 2. TTS ────────────────────────────────────────────────────────────
      stage('voice', 0.26);
      await synthesizeAll({ scenes: gen.scenes, voiceId: req.voice, platform, domain, workDir: tmp, log, progress, signal, lang });

      // ── 3. Timeline + master narration ───────────────────────────────────
      stage('timeline', 0.47);
      const built = buildTimeline({ outline, scenes: gen.scenes, platform, meta: { topic: req.topic, domain: domain.id, voice: req.voice, language: lang } });
      timeline = built.timeline;
      const spliced = path.join(tmp, 'narration_spliced.wav');
      const { totalMs } = await assembleWav(built.segments, spliced);
      await masterVoice(spliced, masterWav, { signal });
      const masterMs = await probeDurationMs(masterWav);
      if (Math.abs(masterMs - totalMs) > 120) throw new Error(`Audio/timeline drift: timeline ${totalMs}ms vs master ${masterMs}ms`);
      log(`Narration mastered · ${(masterMs / 1000).toFixed(1)}s · timeline ${timeline.scenes.length} scenes · drift ${Math.abs(masterMs - totalMs).toFixed(1)}ms`, 'tts');
      await fs.writeFile(path.join(dir, 'timeline.json'), JSON.stringify(timeline, null, 2));
      outline = { ...outline, fromScript: true };
    }

    // ── 4. Remotion ─────────────────────────────────────────────────────────
    stage('render', 0.5);
    const { width, height } = ASPECTS[req.aspect];
    const silent = path.join(tmp, 'visuals.mp4');
    const thumb = path.join(dir, 'thumbnail.jpg');
    await renderVisuals({
      timeline, themeId: req.theme, platformId: platform.id, fps: req.fps, width, height,
      outFile: silent, thumbFile: thumb, log, signal, onProgress: (p) => progress(0.5 + 0.38 * p),
    });

    // ── 5. FFmpeg ───────────────────────────────────────────────────────────
    stage('assemble', 0.9);
    const music = await pickMusic(job.createdAt % 997);
    if (music) log(`Mixing background music (ducked): ${path.basename(music)}`, 'ffmpeg');
    const final = path.join(dir, 'final.mp4');
    await muxFinal({
      video: silent, audio: masterWav, music, durationSec: timeline.totalMs / 1000, out: final, crf: config.render.finalCrf, signal,
      onProgress: (p) => p.percent && progress(0.9 + 0.08 * (p.percent / 100)),
    });

    // ── 6. Sidecars ─────────────────────────────────────────────────────────
    await fs.writeFile(path.join(dir, 'captions.srt'), toSubtitles(timeline, 'srt'));
    await fs.writeFile(path.join(dir, 'captions.vtt'), toSubtitles(timeline, 'vtt'));
    const script = await fs.readFile(path.join(dir, 'script.json'), 'utf8').then(JSON.parse).catch(() => null);
    const o = script?.outline;
    const chapters = toChapters(timeline);
    const metadata = {
      title: o?.title ?? timeline.meta.title,
      titleOptions: o?.titleOptions ?? [],
      hashtags: (o?.hashtags ?? []).map((h) => `#${h}`),
      description: [o?.logline ?? '', '', platform.group === 'long' ? `Chapters:\n${chapters}\n` : '', (o?.hashtags ?? []).map((h) => `#${h}`).join(' ')].filter((x, i) => x !== '' || i === 1).join('\n').trim(),
      chapters,
      durationSeconds: +(timeline.totalMs / 1000).toFixed(1),
      platform: platform.id, aspect: req.aspect, fps: req.fps,
    };
    await fs.writeFile(path.join(dir, 'metadata.json'), JSON.stringify(metadata, null, 2));

    const stat = await fs.stat(final);
    store.update(job, {
      status: 'done', stage: 'done', progress: 1,
      result: {
        title: metadata.title, durationSeconds: metadata.durationSeconds, sizeMB: +(stat.size / 1048576).toFixed(1),
        video: `${config.publicMediaBase}/${job.id}/final.mp4`, thumbnail: `${config.publicMediaBase}/${job.id}/thumbnail.jpg`,
        srt: `${config.publicMediaBase}/${job.id}/captions.srt`, vtt: `${config.publicMediaBase}/${job.id}/captions.vtt`, metadata,
        elapsedSeconds: Math.round((Date.now() - t0) / 1000),
      },
    });
    log(`Done in ${Math.round((Date.now() - t0) / 1000)}s · ${(stat.size / 1048576).toFixed(1)} MB`, 'info');
  } catch (e) {
    if (e.name === 'AbortError' || signal?.aborted) {
      store.update(job, { status: 'cancelled', stage: 'cancelled' });
      log('Job cancelled.', 'warn');
    } else {
      log(e.message, 'error');
      store.update(job, { status: 'failed', stage: 'failed', error: e.message });
    }
  } finally {
    // Always remove temp chunks (raw/clean WAVs, silent video, spliced audio) — they can be hundreds of MB.
    await fs.rm(tmp, { recursive: true, force: true }).catch(() => {});
  }
}
