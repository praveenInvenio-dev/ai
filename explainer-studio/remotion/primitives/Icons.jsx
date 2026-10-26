import React from 'react';
import { spring } from 'remotion';
import { useTheme } from '../themes.jsx';
import { useCue, valueAt, numAt, sinceMs, popSpring } from '../hooks/cue.js';
import { ICON_PATHS, resolveIcon } from '../../src/shared/icons.js';
import { fitFont, hexA } from './common.jsx';

/**
 * A row (or grid) of concept icons — the same Lucide set AI Story Studio's visual-renderer uses — that pop in
 * one by one and light up as the narration reaches them. Works for any subject: "cell → tissue → organ",
 * "client → server → database", "sun → leaf → sugar".
 */
export const Icons = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const items = props.items;
  const n = items.length;
  const reveal = numAt(cue, 'reveal', n, { damping: 26, stiffness: 60 });
  const active = Number(valueAt(cue, 'active', -1));
  const cols = w / h > 1.6 || n <= 3 ? n : Math.ceil(n / 2);
  const rows = Math.ceil(n / cols);
  const cellW = w / cols, cellH = h / rows;
  const r = Math.min(cellW * 0.3, cellH * 0.3);
  const fs = fitFont('M'.repeat(10), cellW * 0.9, cellH * 0.16, { max: r * 0.55 });
  const connect = props.connect !== false && rows === 1 && n > 1;
  const pulse = 0.5 + 0.5 * Math.sin(cue.frame * 0.22);
  return (
    <svg width={w} height={h} style={{ fontFamily: t.font, overflow: 'visible' }}>
      {connect && items.slice(1).map((_, i) => {
        const x1 = cellW * (i + 0.5) + r * 1.1, x2 = cellW * (i + 1.5) - r * 1.1, y = cellH * 0.42;
        const grow = Math.max(0, Math.min(1, reveal - (i + 1)));
        return <line key={`l${i}`} x1={x1} y1={y} x2={x1 + (x2 - x1) * grow} y2={y} stroke={active > i ? t.accent : t.muted} strokeWidth={t.stroke} strokeDasharray={active > i ? '0' : '6 7'} strokeLinecap="round" opacity={0.85} />;
      })}
      {items.map((it, i) => {
        const col = i % cols, row = Math.floor(i / cols);
        const cx = cellW * (col + 0.5), cy = cellH * row + cellH * 0.42;
        const appear = Math.max(0, Math.min(1, reveal - i));
        if (appear <= 0) return null;
        const pop = spring({ frame: Math.max(0, cue.frame - Math.round(((cue.nowMs - cue.ageMs) / 1000) * cue.fps) - i * 6), fps: cue.fps, config: { damping: 10, stiffness: 160 } });
        const on = active === i, past = active > i;
        const c = on ? t.accent2 : past ? t.good : t.accent;
        const bounce = on ? popSpring(cue, sinceMs(cue, 'active'), { damping: 8, stiffness: 200 }) : 1;
        const s = (0.55 + 0.45 * Math.min(1, pop)) * (on ? 1.02 + 0.08 * bounce : 1);
        const paths = ICON_PATHS[resolveIcon(it.icon, it.label)] || ICON_PATHS.sparkles;
        const k = (r * 1.15) / 24;
        return (
          <g key={i} transform={`translate(${cx} ${cy}) scale(${s})`} opacity={Math.min(1, appear * 1.5)}>
            {on && <circle r={r * (1.18 + 0.08 * pulse)} fill="none" stroke={c} strokeWidth={3} opacity={0.55} />}
            <circle r={r} fill={hexA(c, on ? 0.28 : 0.14)} stroke={c} strokeWidth={t.stroke + (on ? 1 : 0)} style={{ filter: on && t.glow ? `drop-shadow(0 0 ${14 * t.glow}px ${c})` : undefined }} />
            <g transform={`translate(${-12 * k} ${-12 * k}) scale(${k})`} fill="none" stroke={on ? t.text : c} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
              {paths.map((d, j) => <path key={j} d={d} />)}
            </g>
            <text y={r + fs * 1.35} textAnchor="middle" fill={on ? t.text : t.muted} fontSize={fs} fontWeight={on ? 800 : 700}>{it.label}</text>
          </g>
        );
      })}
    </svg>
  );
};
