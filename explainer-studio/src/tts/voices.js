/**
 * Voice catalog. Piper models are downloaded into PIPER_VOICES_DIR (see apps/api/Dockerfile).
 * Add a voice = add a row here and download the .onnx + .onnx.json. Check each voice's own licence
 * (model cards on huggingface.co/rhasspy/piper-voices) before commercial use.
 */
export const VOICES = [
  { id: 'ryan', label: 'Ryan — warm US male (Piper, high)', engine: 'piper', model: 'en_US-ryan-high', base: { lengthScale: 1.0, noiseScale: 0.7, noiseW: 0.85 } },
  { id: 'lessac', label: 'Lessac — clear US female (Piper, high)', engine: 'piper', model: 'en_US-lessac-high', base: { lengthScale: 1.0, noiseScale: 0.7, noiseW: 0.85 } },
  { id: 'jenny', label: 'Jenny — friendly UK female (Piper, medium)', engine: 'piper', model: 'en_GB-jenny_dioco-medium', base: { lengthScale: 1.0, noiseScale: 0.7, noiseW: 0.85 } },
  // Optional, more expressive (needs the Kokoro build flag — see README):
  { id: 'kokoro_michael', label: 'Michael — expressive US male (Kokoro)', engine: 'kokoro', voice: 'am_michael', base: { lengthScale: 1.0, noiseScale: 0.7, noiseW: 0.85 } },
  { id: 'kokoro_sarah', label: 'Sarah — expressive US female (Kokoro)', engine: 'kokoro', voice: 'af_sarah', base: { lengthScale: 1.0, noiseScale: 0.7, noiseW: 0.85 } },
];
export const getVoice = (id) => VOICES.find((v) => v.id === id) || VOICES[0];

/**
 * Which voices can actually speak in THIS container: Piper needs its .onnx file, Kokoro needs either the
 * AI Story Studio tts-kokoro service (KOKORO_TTS_URL) or the optional in-image model.
 */
import fs from 'node:fs';
import path from 'node:path';
import { config } from '../config.js';
export function voiceAvailable(v) {
  if (v.engine === 'kokoro') return !!config.tts.kokoroUrl || fs.existsSync(path.join(config.tts.kokoroModelDir, 'kokoro-v1.0.onnx'));
  return fs.existsSync(path.join(config.tts.voicesDir, `${v.model}.onnx`));
}
/** The voice used when a request names none (or an unavailable one): DEFAULT_VOICE, else the first that works. */
export function defaultVoiceId() {
  const pref = VOICES.find((v) => v.id === config.tts.defaultVoice && voiceAvailable(v));
  return (pref || VOICES.find(voiceAvailable) || VOICES[0]).id;
}
