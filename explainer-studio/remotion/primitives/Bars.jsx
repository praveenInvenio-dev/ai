import React from 'react';
import { useTheme } from '../themes.jsx';
import { useCue, numAt } from '../hooks/cue.js';
import { hexA, glow } from './common.jsx';

export const Bars = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const vals = props.bars.map((b) => numAt(cue, `bars.${b.id}`, b.value, { damping: 18, stiffness: 80 }));
  const mx = props.max || Math.max(1, ...props.bars.map((b) => b.value), ...cue.events.flatMap((e) => Object.entries(e.set).filter(([k]) => k.startsWith('bars.')).map(([, v]) => Number(v) || 0)));
  const n = props.bars.length;
  const pad = { l: w * 0.04, r: w * 0.04, t: h * 0.14, b: h * 0.2 };
  const slot = (w - pad.l - pad.r) / n, bw = slot * 0.6, ph = h - pad.t - pad.b;
  const fs = Math.max(11, Math.min(slot * 0.2, h * 0.07));
  return (
    <svg width={w} height={h} style={{ fontFamily: t.font }}>
      <line x1={pad.l} y1={pad.t + ph} x2={w - pad.r} y2={pad.t + ph} stroke={t.text} strokeWidth={2.5} opacity={0.7} />
      {props.bars.map((b, i) => {
        const c = t.lanes[i % t.lanes.length];
        const bh = Math.max(2, (Math.max(0, vals[i]) / mx) * ph), x = pad.l + slot * i + (slot - bw) / 2;
        return (
          <g key={b.id}>
            <rect x={x} y={pad.t + ph - bh} width={bw} height={bh} rx={bw * 0.12} fill={hexA(c, 0.85)} stroke={c} strokeWidth={t.stroke} style={{ filter: t.glow ? `drop-shadow(0 0 ${10 * t.glow}px ${c})` : undefined }} />
            <text x={x + bw / 2} y={pad.t + ph - bh - fs * 0.5} textAnchor="middle" fill={t.text} fontSize={fs * 1.15} fontWeight={800} fontFamily={t.mono}>{Math.abs(vals[i]) >= 100 ? Math.round(vals[i]) : (+vals[i].toFixed(1))}{props.unit}</text>
            <text x={x + bw / 2} y={pad.t + ph + fs * 1.7} textAnchor="middle" fill={t.text} fontSize={fs} fontWeight={700}>{b.label}</text>
          </g>
        );
      })}
    </svg>
  );
};
