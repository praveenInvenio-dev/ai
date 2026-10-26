import React from 'react';
import { useTheme } from '../themes.jsx';
import { useCue, numAt } from '../hooks/cue.js';
import { hexA, glow } from './common.jsx';

/** Slots inside a boundary that smoothly expands as `capacity` grows. */
export const Memory = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const maxCap = props.maxCapacity;
  const cap = Math.max(1, Math.min(maxCap, numAt(cue, 'capacity', props.capacity, { damping: 16, stiffness: 60 })));
  const fill = Math.max(0, Math.min(cap, numAt(cue, 'fill', 0, { damping: 18, stiffness: 90 })));
  const colsMax = Math.ceil(Math.sqrt(maxCap * 1.7));
  const rowsMax = Math.ceil(maxCap / colsMax);
  const cell = Math.min((w * 0.86) / colsMax, (h * 0.66) / rowsMax);
  const cols = Math.min(colsMax, Math.ceil(cap));
  const rows = Math.max(1, Math.ceil(cap / colsMax));
  const bw = Math.min(colsMax, cap) * cell + cell * 0.3;
  const bh = Math.max(1, cap / colsMax) * cell + cell * 0.3;
  const bx = (w - bw) / 2, by = h * 0.17 + (h * 0.66 - rowsMax * cell) / 2;
  const growing = Math.abs(cap - props.capacity) > 0.05;
  const fs = Math.max(11, Math.min(h * 0.09, w * 0.045));
  return (
    <svg width={w} height={h} style={{ fontFamily: t.font }}>
      <text x={w / 2} y={h * 0.1} textAnchor="middle" fill={t.text} fontSize={fs * 1.2} fontWeight={800}>{props.title}</text>
      <rect x={bx} y={by} width={bw} height={bh} rx={cell * 0.2} fill={hexA(t.accent, 0.08)} stroke={t.accent} strokeWidth={t.stroke} strokeDasharray={growing ? '10 7' : '0'} style={{ filter: t.glow ? `drop-shadow(0 0 ${12 * t.glow}px ${t.accent})` : undefined }} />
      {Array.from({ length: Math.floor(cap + 0.001) }).map((_, i) => {
        const c = i % colsMax, r = Math.floor(i / colsMax);
        const filled = i < Math.round(fill);
        return <rect key={i} x={bx + cell * 0.15 + c * cell + cell * 0.06} y={by + cell * 0.15 + r * cell + cell * 0.06} width={cell * 0.88} height={cell * 0.88} rx={cell * 0.14} fill={filled ? t.accent2 : t.panel} stroke={filled ? t.accent2 : t.panelBorder} strokeWidth={1.5} opacity={filled ? 0.95 : 0.8} />;
      })}
      <text x={w / 2} y={by + bh + fs * 1.8} textAnchor="middle" fill={t.muted} fontSize={fs} fontFamily={t.mono} fontWeight={700}>{Math.round(fill)} / {Math.floor(cap)} slots used</text>
    </svg>
  );
};
