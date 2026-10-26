import { clamp } from '../shared/cues.js';

/**
 * Resolve scenes + exact speech durations into the final timeline.
 * Output scene shape matches the requested schema:
 *   { startSecond, endSecond, visualElements: [], narrativeScript: "" } (+ richer fields used by the renderer)
 * Also returns the ordered audio segments used to splice the master narration track.
 *
 * `speechMs` per sentence is the ground truth; every visual cue is resolved against it, so visuals can
 * never drift away from the voice no matter how the TTS engine paces itself.
 */
export function buildTimeline({ outline, scenes, platform, meta = {} }) {
  const segments = [];
  let cursor = 0;
  const out = [];

  for (const [si, scene] of scenes.entries()) {
    const startMs = cursor;
    // The picture appears BEFORE the voice — "visualise it" literally comes first.
    const lead = si === 0 ? 650 : scene.phases[0] === 'visualise' ? 700 : 420;
    segments.push({ silenceMs: lead });
    cursor += lead;

    const sentences = scene.sentences.map((s) => {
      const sStart = cursor;
      const sEnd = sStart + s.speechMs;
      if (s.audioFile) segments.push({ file: s.audioFile });
      segments.push({ silenceMs: s.pauseMs });
      cursor = sEnd + s.pauseMs;
      return {
        text: s.text, spoken: s.spoken, phase: s.phase, techNote: s.techNote, emphasis: !!s.emphasis,
        startMs: sStart, endMs: sEnd, words: wordTimings(s.text, sStart, s.speechMs),
      };
    });
    const endMs = cursor;

    const at = (cue) => {
      const i = clamp(cue.sentence, 0, sentences.length - 1);
      return clamp(sentences[i].startMs + (cue.offsetMs || 0), startMs, endMs);
    };
    const visualElements = scene.elements.map((el) => ({
      id: `s${si}_${el.id}`, type: el.type, slot: el.slot, props: el.props,
      enterMs: at(el.enter),
      exitMs: el.exit ? at(el.exit) : endMs,
      events: el.events.map((e) => ({ atMs: at(e.at), set: e.set })),
    }));

    out.push({
      id: `scene_${si}`, index: si, role: scene.role, heading: scene.heading, phases: scene.phases,
      startMs, endMs, startSecond: +(startMs / 1000).toFixed(3), endSecond: +(endMs / 1000).toFixed(3),
      narrativeScript: sentences.map((s) => s.text).join(' '),
      sentences, visualElements,
    });
  }

  const tail = 900; // breathing room on the end card
  segments.push({ silenceMs: tail });
  const totalMs = cursor + tail;

  return {
    timeline: {
      meta: {
        title: outline.title, onScreenHook: outline.onScreenHook, ctaLine: outline.ctaLine,
        platformId: platform.id, captionChunk: platform.captionChunk, ...meta,
      },
      totalMs, totalSeconds: +(totalMs / 1000).toFixed(3), scenes: out,
    },
    segments,
  };
}

/** Distribute a sentence's speech time across its words, weighted by length (+ a little for punctuation). */
export function wordTimings(text, startMs, speechMs) {
  const tokens = text.split(/\s+/).filter(Boolean);
  const weights = tokens.map((t) => Math.max(2, t.replace(/[^\w]/g, '').length) + (/[,;:]$/.test(t) ? 1.2 : 0) + (/[.!?]$|\.\.\.$/.test(t) ? 1.6 : 0));
  const sum = weights.reduce((a, b) => a + b, 0);
  let t = startMs;
  return tokens.map((w, i) => {
    const d = (weights[i] / sum) * speechMs;
    const rec = { w, startMs: Math.round(t), endMs: Math.round(t + d) };
    t += d;
    return rec;
  });
}

const pad = (n, w = 2) => String(Math.floor(n)).padStart(w, '0');
const stamp = (ms, sep) => `${pad(ms / 3600000)}:${pad((ms / 60000) % 60)}:${pad((ms / 1000) % 60)}${sep}${pad(ms % 1000, 3)}`;

/** SRT / WebVTT sidecars (YouTube uses them for accessibility + search; Instagram ignores them). */
export function toSubtitles(timeline, kind = 'srt') {
  const sep = kind === 'vtt' ? '.' : ',';
  const cues = timeline.scenes.flatMap((sc) => sc.sentences);
  const body = cues
    .map((s, i) => `${kind === 'srt' ? `${i + 1}\n` : ''}${stamp(s.startMs, sep)} --> ${stamp(s.endMs, sep)}\n${s.text}\n`)
    .join('\n');
  return kind === 'vtt' ? `WEBVTT\n\n${body}` : body;
}

/** YouTube chapters block (first chapter must start at 0:00; needs ≥3 chapters of ≥10 s). */
export function toChapters(timeline) {
  const fmt = (ms) => { const s = Math.round(ms / 1000); return `${Math.floor(s / 60)}:${pad(s % 60)}`; };
  return timeline.scenes.map((sc, i) => `${fmt(i === 0 ? 0 : sc.startMs)} ${sc.heading}`).join('\n');
}
