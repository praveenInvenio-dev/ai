/**
 * Prosody shaping. Piper has no SSML, but it exposes three knobs per call that we vary per sentence,
 * plus we control the silence BETWEEN sentences ourselves. Result: rhythm instead of a metronome.
 *   lengthScale  > 1 slower, < 1 faster
 *   noiseScale   phoneme variability (more = more "alive", too much = mumbly)
 *   noiseW       phoneme-duration variability
 */
const rand01 = (i) => { const x = Math.sin((i + 1) * 12.9898) * 43758.5453; return x - Math.floor(x); }; // deterministic jitter

export function shapeSentence({ text, index, isFirstOfVideo, isLastOfScene, emphasis, base }) {
  let length = base.lengthScale;
  let noise = base.noiseScale;
  let noiseW = base.noiseW;
  const q = /\?\s*$/.test(text);
  const bang = /!\s*$/.test(text);
  const short = text.trim().split(/\s+/).length <= 5;

  length += (rand01(index) - 0.5) * 0.05;           // ±2.5% natural drift
  if (q) { length += 0.04; noise += 0.04; }          // questions: slightly slower, more melodic
  if (bang) { length -= 0.03; noise += 0.06; }       // excitement
  if (emphasis) { length += 0.09; noiseW += 0.05; }  // land the important line
  if (short) length += 0.03;                         // punchy lines get room
  if (isFirstOfVideo) length -= 0.05;                // energetic opener
  if (isLastOfScene) length += 0.02;

  return {
    lengthScale: Math.min(1.3, Math.max(0.82, +length.toFixed(3))),
    noiseScale: Math.min(0.9, Math.max(0.5, +noise.toFixed(3))),
    noiseW: Math.min(1.1, Math.max(0.6, +noiseW.toFixed(3))),
  };
}

/** Silence after a sentence, in ms. This is where the "thinking" and the "landing" happen. */
export function pauseAfter({ text, isLastOfScene, emphasis, pauseScale = 1 }) {
  let ms = 230;
  if (/\?\s*$/.test(text)) ms += 300;      // let the question hang
  if (/\.\.\.\s*$/.test(text)) ms += 260;
  if (/!\s*$/.test(text)) ms += 80;
  if (emphasis) ms += 260;                 // let the key line land
  if (text.trim().split(/\s+/).length <= 4) ms += 90;
  if (isLastOfScene) ms += 330;
  return Math.round(ms * pauseScale);
}
