import fs from 'node:fs/promises';

/** Minimal PCM WAV reader/writer. We splice narration in pure Node so timing is sample-exact. */
export async function readWav(file) {
  const buf = await fs.readFile(file);
  if (buf.toString('ascii', 0, 4) !== 'RIFF' || buf.toString('ascii', 8, 12) !== 'WAVE') throw new Error(`${file}: not a WAV`);
  let off = 12, fmt = null, data = null;
  while (off + 8 <= buf.length) {
    const id = buf.toString('ascii', off, off + 4);
    let size = buf.readUInt32LE(off + 4);
    const start = off + 8;
    if (id === 'fmt ') fmt = { format: buf.readUInt16LE(start), channels: buf.readUInt16LE(start + 2), sampleRate: buf.readUInt32LE(start + 4), bits: buf.readUInt16LE(start + 14) };
    if (id === 'data') { if (start + size > buf.length) size = buf.length - start; data = buf.subarray(start, start + size); break; }
    off = start + size + (size % 2);
  }
  if (!fmt || !data) throw new Error(`${file}: malformed WAV`);
  if (fmt.format !== 1 || fmt.bits !== 16) throw new Error(`${file}: expected 16-bit PCM, got format ${fmt.format}/${fmt.bits}`);
  return { ...fmt, data };
}

export function wavHeader({ sampleRate, channels = 1, bits = 16, dataBytes }) {
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + dataBytes, 4); h.write('WAVE', 8); h.write('fmt ', 12);
  h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(channels, 22); h.writeUInt32LE(sampleRate, 24);
  h.writeUInt32LE((sampleRate * channels * bits) / 8, 28); h.writeUInt16LE((channels * bits) / 8, 32); h.writeUInt16LE(bits, 34);
  h.write('data', 36); h.writeUInt32LE(dataBytes, 40);
  return h;
}

export const msToBytes = (ms, { sampleRate, channels = 1, bits = 16 }) => Math.round((ms / 1000) * sampleRate) * channels * (bits / 8);
export const bytesToMs = (bytes, { sampleRate, channels = 1, bits = 16 }) => (bytes / (channels * (bits / 8)) / sampleRate) * 1000;

/**
 * segments: [{silenceMs} | {file}] → one WAV. Returns { totalMs, format }.
 * All speech files must share one format (we normalise every chunk to 44.1k mono s16 first).
 */
export async function assembleWav(segments, outFile) {
  const chunks = [];
  let format = null;
  for (const seg of segments) {
    if (seg.file) {
      const w = await readWav(seg.file);
      if (!format) format = { sampleRate: w.sampleRate, channels: w.channels, bits: w.bits };
      else if (format.sampleRate !== w.sampleRate || format.channels !== w.channels) throw new Error('mixed WAV formats in assembly');
      chunks.push(w.data);
    }
  }
  if (!format) throw new Error('no speech segments to assemble');
  const parts = segments.map((seg, i) => (seg.file ? chunks.shift() : Buffer.alloc(msToBytes(seg.silenceMs, format))));
  const data = Buffer.concat(parts);
  await fs.writeFile(outFile, Buffer.concat([wavHeader({ ...format, dataBytes: data.length }), data]));
  return { totalMs: bytesToMs(data.length, format), format };
}
