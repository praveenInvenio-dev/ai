import React, { useMemo } from 'react';
import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { useTheme } from '../themes.jsx';
import { hexA } from '../primitives/common.jsx';

export const PHASE_LABELS = { visualise: 'Visualise', animate: 'Animate', explain: 'Explain' };

export const Background = () => {
  const t = useTheme();
  const frame = useCurrentFrame();
  const { width, height } = useVideoConfig();
  const size = Math.round(Math.min(width, height) / 14);
  const drift = (frame * 0.35) % size;
  return (
    <AbsoluteFill style={{ background: `radial-gradient(120% 90% at 50% 0%, ${t.bg[0]} 0%, ${t.bg[1]} 100%)` }}>
      <svg width={width} height={height} style={{ position: 'absolute', inset: 0 }}>
        <defs>
          <pattern id="grid" width={size} height={size} patternUnits="userSpaceOnUse" patternTransform={`translate(${drift} ${drift})`}>
            <path d={`M ${size} 0 L 0 0 0 ${size}`} fill="none" stroke={t.grid} strokeWidth="1.5" />
          </pattern>
          <radialGradient id="vig" cx="50%" cy="45%" r="75%"><stop offset="60%" stopColor="#000" stopOpacity="0" /><stop offset="100%" stopColor="#000" stopOpacity={t.dark ? 0.45 : 0.08} /></radialGradient>
        </defs>
        <rect width={width} height={height} fill="url(#grid)" />
        <rect width={width} height={height} fill="url(#vig)" />
      </svg>
    </AbsoluteFill>
  );
};

export const Header = ({ L, title, phase, topic }) => {
  const t = useTheme();
  const { header: r, portrait, width } = L;
  const fs = portrait ? width * 0.034 : L.height * 0.032;
  const order = ['visualise', 'animate', 'explain'];
  return (
    <div style={{ position: 'absolute', left: r.x, top: r.y, width: r.w, height: r.h, display: 'flex', flexDirection: portrait ? 'column' : 'row', alignItems: portrait ? 'flex-start' : 'center', justifyContent: 'space-between', gap: fs * 0.6, fontFamily: t.font }}>
      <div style={{ color: t.text, fontWeight: 800, fontSize: fs * 1.1, letterSpacing: -0.2, maxWidth: portrait ? '100%' : '58%', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{topic}</div>
      <div style={{ display: 'flex', gap: fs * 0.5 }}>
        {order.map((p, i) => {
          const on = p === phase, passed = order.indexOf(phase) > i;
          return (
            <div key={p} style={{ padding: `${fs * 0.22}px ${fs * 0.75}px`, borderRadius: 999, fontSize: fs * 0.78, fontWeight: 700, border: `2px solid ${on ? t.accent2 : t.panelBorder}`, background: on ? hexA(t.accent2, 0.22) : passed ? hexA(t.accent, 0.12) : 'transparent', color: on ? t.text : t.muted, boxShadow: on && t.glow ? `0 0 16px ${hexA(t.accent2, t.glow)}` : 'none' }}>
              {PHASE_LABELS[p]}
            </div>
          );
        })}
      </div>
    </div>
  );
};

/** Chunked karaoke captions: 3 big words at a time on shorts, ~9-word lines on long-form. */
export const Captions = ({ L, sentences, nowMs, chunk }) => {
  const t = useTheme();
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const cur = useMemo(() => {
    let found = null;
    for (const s of sentences) if (s.startMs <= nowMs) found = s; else break;
    return found;
  }, [sentences, nowMs]);
  if (!cur || nowMs > cur.endMs + 380) return null;
  const words = cur.words;
  let active = 0;
  words.forEach((w, i) => { if (w.startMs <= nowMs) active = i; });
  const ci = Math.floor(active / chunk);
  const group = words.slice(ci * chunk, ci * chunk + chunk);
  const since = Math.max(0, nowMs - group[0].startMs);
  const pop = spring({ frame: (since / 1000) * fps, fps, config: { damping: 14, stiffness: 220 } });
  const { captions: r, portrait } = L;
  const fs = portrait ? L.width * 0.074 : L.height * 0.046;
  return (
    <div style={{ position: 'absolute', left: r.x, top: r.y, width: r.w, height: r.h, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ transform: `scale(${0.94 + 0.06 * pop})`, opacity: 0.4 + 0.6 * pop, textAlign: 'center', fontFamily: t.font, fontWeight: portrait ? 900 : 700, fontSize: fs, lineHeight: 1.18, padding: portrait ? 0 : `${fs * 0.3}px ${fs * 0.7}px`, borderRadius: fs * 0.4, background: portrait ? 'transparent' : t.captionBg, WebkitTextStroke: portrait ? `${fs * 0.12}px ${t.captionStroke}` : undefined, paintOrder: 'stroke fill', textShadow: portrait ? `0 ${fs * 0.06}px ${fs * 0.25}px rgba(0,0,0,0.5)` : 'none' }}>
        {group.map((w, i) => {
          const gi = ci * chunk + i;
          const isActive = gi === active;
          // Each word springs up the moment it is spoken (karaoke bounce), then settles.
          const wp = words[gi].startMs <= nowMs ? spring({ frame: ((nowMs - words[gi].startMs) / 1000) * fps, fps, config: { damping: 10, stiffness: 260 } }) : 0;
          const lift = isActive ? (1 - wp) * fs * 0.12 : 0;
          return <span key={gi} style={{ color: isActive ? t.captionActive : t.captionFg, opacity: gi > active ? 0.55 : 1, marginRight: fs * 0.26, display: 'inline-block', transform: `translateY(${lift.toFixed(2)}px) scale(${isActive ? 1 + 0.09 * Math.min(1, wp) : 1})`, textShadow: isActive && t.glow ? `0 0 ${fs * 0.35}px ${hexA(t.captionActive, t.glow)}` : undefined }}>{w.w}</span>;
        })}
      </div>
    </div>
  );
};

export const TechNote = ({ L, text, label = 'TECH', sinceMs = 1e9 }) => {
  const t = useTheme();
  const { fps } = useVideoConfig();
  const { tech: r, portrait } = L;
  if (!text) return null;
  const base = portrait ? L.width * 0.03 : L.height * 0.03;
  // Never clip a technical fact: shrink to fit one line (down to 62 %), then wrap to two lines.
  const avail = r.w * 0.92 - base * 5.5;
  const fs = Math.max(base * 0.62, Math.min(base, avail / (text.length * 0.6)));
  const p = spring({ frame: Math.max(0, (sinceMs / 1000) * fps), fps, config: { damping: 16, stiffness: 150 } });
  // Typewriter reveal of the note itself (≈ 45 chars/s), so the fact "writes itself" as it is spoken.
  const shown = Math.max(1, Math.min(text.length, Math.floor((sinceMs / 1000) * 45)));
  return (
    <div style={{ position: 'absolute', left: r.x, top: r.y, width: r.w, height: r.h, display: 'flex', alignItems: 'center', justifyContent: 'center', opacity: p, transform: `translateY(${(1 - p) * 18}px)` }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: fs * 0.7, maxWidth: '100%', padding: `${fs * 0.35}px ${fs * 0.9}px`, borderRadius: fs * 0.5, background: t.code.bg, border: `2px solid ${t.accent}`, boxShadow: t.glow ? `0 0 ${fs}px ${hexA(t.accent, t.glow * 0.6)}` : '0 6px 20px rgba(0,0,0,.12)', fontFamily: t.mono, fontSize: fs, color: t.text, fontWeight: 600, lineHeight: 1.25 }}>
        <span style={{ color: t.accent2, fontWeight: 800, fontSize: fs * 0.75, letterSpacing: 1 }}>{label}</span>
        <span style={{ display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
          {text.slice(0, shown)}<span style={{ opacity: 0 }}>{text.slice(shown)}</span>
        </span>
      </div>
    </div>
  );
};

/** Big text hook in the first ~2.5 s — the "3-second rule" for short-form, the promise line for long-form. Words rise in one by one. */
export const HookOverlay = ({ L, text, nowMs }) => {
  const t = useTheme();
  const { fps } = useVideoConfig();
  const frame = useCurrentFrame();
  const end = 2600;
  if (!text || nowMs > end + 400) return null;
  const out = interpolate(nowMs, [end - 350, end + 350], [1, 0], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const { content: c, portrait } = L;
  const fs = portrait ? L.width * 0.1 : L.height * 0.085;
  const words = String(text).split(/\s+/);
  const under = spring({ frame: frame - 4 - words.length * 3, fps, config: { damping: 18, stiffness: 90 } });
  return (
    <div style={{ position: 'absolute', left: c.x, top: c.y, width: c.w, height: c.h, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', pointerEvents: 'none', opacity: out }}>
      <div style={{ fontFamily: t.font, fontWeight: 900, fontSize: fs, lineHeight: 1.08, textAlign: 'center', color: t.text, textShadow: t.dark ? '0 6px 30px rgba(0,0,0,0.6)' : 'none', padding: '0 4%' }}>
        {words.map((w, i) => {
          const p = spring({ frame: frame - i * 3, fps, config: { damping: 13, stiffness: 150 } });
          return <span key={i} style={{ display: 'inline-block', marginRight: fs * 0.24, opacity: Math.min(1, p * 1.4), transform: `translateY(${(1 - p) * fs * 0.45}px) scale(${0.9 + 0.1 * p})`, filter: p < 0.95 ? `blur(${((1 - p) * 8).toFixed(2)}px)` : undefined }}>{w}</span>;
        })}
      </div>
      <div style={{ marginTop: fs * 0.28, height: Math.max(4, fs * 0.08), width: `${Math.round(under * 38)}%`, borderRadius: 99, background: `linear-gradient(90deg, ${t.accent}, ${t.accent2})`, boxShadow: t.glow ? `0 0 ${fs * 0.4}px ${hexA(t.accent2, t.glow)}` : 'none' }} />
    </div>
  );
};

export const EndCard = ({ L, text, nowMs, totalMs }) => {
  const t = useTheme();
  const start = totalMs - 3200;
  if (!text || nowMs < start) return null;
  const p = interpolate(nowMs, [start, start + 400], [0, 1], { extrapolateRight: 'clamp' });
  const { tech: r, portrait } = L;
  const fs = portrait ? L.width * 0.04 : L.height * 0.038;
  return (
    <div style={{ position: 'absolute', left: r.x, top: r.y - fs * 0.5, width: r.w, height: r.h + fs, display: 'flex', alignItems: 'center', justifyContent: 'center', opacity: p, transform: `translateY(${(1 - p) * 20}px)` }}>
      <div style={{ fontFamily: t.font, fontWeight: 800, fontSize: fs, color: t.dark ? t.bg[1] : '#fff', background: t.accent2, padding: `${fs * 0.4}px ${fs * 1.1}px`, borderRadius: 999, boxShadow: `0 8px 30px ${hexA(t.accent2, 0.45)}`, textAlign: 'center' }}>{text}</div>
    </div>
  );
};

export const ProgressBar = ({ nowMs, totalMs, L }) => {
  const t = useTheme();
  const hgt = Math.max(4, L.height * 0.004);
  return <div style={{ position: 'absolute', left: 0, top: 0, height: hgt, width: `${Math.min(100, (nowMs / totalMs) * 100)}%`, background: `linear-gradient(90deg, ${t.accent}, ${t.accent2})`, boxShadow: t.glow ? `0 0 ${hgt * 3}px ${hexA(t.accent2, t.glow)}` : 'none', borderRadius: `0 ${hgt}px ${hgt}px 0` }} />;
};
