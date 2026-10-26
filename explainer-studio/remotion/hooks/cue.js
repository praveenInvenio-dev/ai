import { createContext, useContext, useMemo } from 'react';
import { useCurrentFrame, useVideoConfig, spring } from 'remotion';
import { flatten } from '../../src/shared/cues.js';

/**
 * The sync engine. Events carry ABSOLUTE millisecond timestamps (resolved server-side from the real
 * TTS durations). Primitives read "what is the state at this frame?" — no timers, no drift.
 * Every piece of motion is a pure function of the frame, which is what makes Remotion renders deterministic.
 */
export const TimingCtx = createContext({ enterMs: 0 });

export function useCue(events) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const { enterMs } = useContext(TimingCtx);
  const prepared = useMemo(
    () => (events || []).map((e) => ({ atMs: e.atMs, set: flatten(e.set) })).sort((a, b) => a.atMs - b.atMs),
    [events],
  );
  const nowMs = (frame / fps) * 1000;
  return { frame, fps, nowMs, ageMs: nowMs - enterMs, events: prepared };
}

const fired = (cue, key) => cue.events.filter((e) => e.atMs <= cue.nowMs && key in e.set).map((e) => ({ atMs: e.atMs, value: e.set[key] }));

/** Discrete state (strings, ids, flags): last value set at or before now. */
export function valueAt(cue, key, initial) {
  const p = fired(cue, key);
  return p.length ? p[p.length - 1].value : initial;
}

/** Milliseconds since `key` last changed (Infinity if it never did). */
export function sinceMs(cue, key) {
  const p = fired(cue, key);
  return p.length ? cue.nowMs - p[p.length - 1].atMs : Infinity;
}

/** Continuous state: spring from the previous value to the newest one, starting at the event's frame. */
export function numAt(cue, key, initial = 0, { damping = 20, stiffness = 110, mass = 0.9 } = {}) {
  const p = fired(cue, key);
  if (!p.length) return initial;
  const last = p[p.length - 1];
  const to = Number(last.value);
  if (!Number.isFinite(to)) return initial;
  const prevRaw = p.length > 1 ? Number(p[p.length - 2].value) : initial;
  const from = Number.isFinite(prevRaw) ? prevRaw : initial;
  const k = spring({ frame: Math.max(0, cue.frame - Math.round((last.atMs / 1000) * cue.fps)), fps: cue.fps, config: { damping, stiffness, mass } });
  return from + (to - from) * k;
}

export const clamp01 = (x) => Math.min(1, Math.max(0, x));
export const popSpring = (cue, ms, cfg = { damping: 11, stiffness: 170 }) =>
  ms === Infinity ? 1 : spring({ frame: Math.max(0, (ms / 1000) * cue.fps), fps: cue.fps, config: cfg });
