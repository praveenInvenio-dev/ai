import fs from 'node:fs/promises';
import path from 'node:path';
import { config } from '../config.js';
import { normalizeForSpeech } from './normalize.js';
import { shapeSentence, pauseAfter } from './prosody.js';
import { getVoice } from './voices.js';
import { synthesize } from './engines.js';
import { cleanSpeech } from '../media/ffmpeg.js';
import { readWav, bytesToMs } from '../media/wav.js';

/**
 * Synthesize every sentence of every scene as its own clip.
 * Per-sentence clips give us: (a) exact millisecond durations → exact visual cues,
 * (b) per-sentence prosody, (c) controlled pauses, (d) cheap retries.
 * Mutates each sentence: { spoken, audioFile, speechMs, pauseMs }.
 */
export async function synthesizeAll({ scenes, voiceId, platform, domain, workDir, log, progress, signal, lang = 'en' }) {
  const voice = getVoice(voiceId);
  const engine = voice.engine === 'piper' && config.tts.engine === 'kokoro' ? 'kokoro' : voice.engine;
  log(`TTS engine: ${engine} · voice: ${voice.label}`, 'tts');
  const total = scenes.reduce((a, s) => a + s.sentences.length, 0);
  let done = 0, idx = 0;

  for (const [si, scene] of scenes.entries()) {
    for (const [i, s] of scene.sentences.entries()) {
      signal?.throwIfAborted();
      const isLastOfScene = i === scene.sentences.length - 1;
      const spoken = normalizeForSpeech(s.text, { pronunciations: lang === 'en' ? domain.pronunciations : {}, lang });
      const params = shapeSentence({ text: s.text, index: idx, isFirstOfVideo: si === 0 && i === 0, isLastOfScene, emphasis: s.emphasis, base: voice.base });
      const raw = path.join(workDir, `raw_${String(idx).padStart(3, '0')}.wav`);
      const clean = path.join(workDir, `s_${String(idx).padStart(3, '0')}.wav`);

      let attempt = 0;
      for (;;) {
        try {
          await synthesize(engine, { text: spoken, voice, params, outFile: raw, signal });
          await cleanSpeech(raw, clean, { signal });
          break;
        } catch (e) {
          if (e.name === 'AbortError' || ++attempt >= 2) throw new Error(`TTS failed on scene ${si + 1} sentence ${i + 1}: ${e.message}`);
          log(`TTS retry (${e.message.slice(0, 120)})`, 'warn');
        }
      }
      await fs.rm(raw, { force: true });

      const wav = await readWav(clean);
      const speechMs = bytesToMs(wav.data.length, wav);
      if (speechMs < 150) throw new Error(`TTS produced near-empty audio for: "${spoken}"`);
      Object.assign(s, { spoken, audioFile: clean, speechMs, pauseMs: pauseAfter({ text: s.text, isLastOfScene, emphasis: s.emphasis, pauseScale: platform.pauseScale }) });
      done++; idx++;
      log(`TTS ${done}/${total} · ${(speechMs / 1000).toFixed(2)}s · “${spoken.slice(0, 60)}${spoken.length > 60 ? '…' : ''}”`, 'tts');
      progress(0.26 + 0.2 * (done / total));
    }
  }
}
