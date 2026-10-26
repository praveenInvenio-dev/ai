import React from 'react';
import { useTheme } from '../themes.jsx';
import { useCue, valueAt, numAt, clamp01 } from '../hooks/cue.js';
import { hexA, glow } from './common.jsx';

/** Parallel lanes; each lane is a pipeline of segments (the checkpoints) with a token moving through it. */
export const Threads = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const { lanes, checkpoints } = props;
  const n = Math.max(1, checkpoints.length);
  const padL = w * 0.17, padR = w * 0.04;
  const x0 = padL, x1 = w - padR, span = x1 - x0;
  const laneH = h / lanes.length;
  const barH = Math.min(laneH * 0.34, w * 0.07);
  const r = barH * 0.46;
  const fs = Math.max(11, Math.min(laneH * 0.17, w * 0.036));
  return (
    <svg width={w} height={h} style={{ overflow: 'visible', fontFamily: t.font }}>
      {lanes.map((lane, li) => {
        const color = t.lanes[li % t.lanes.length];
        const prog = clamp01(numAt(cue, `${lane.id}.progress`, 0, { damping: 18, stiffness: 70 }));
        const status = String(valueAt(cue, `${lane.id}.status`, 'idle'));
        const note = String(valueAt(cue, `${lane.id}.note`, ''));
        const cy = li * laneH + laneH * 0.46;
        const bad = status === 'conflict' || status === 'blocked';
        const shake = bad ? Math.sin(cue.frame * 1.1) * Math.min(5, w * 0.006) : 0;
        const pulse = 0.5 + 0.5 * Math.sin(cue.frame * 0.28);
        const tokenX = x0 + span * prog + shake;
        const stColor = { running: color, blocked: t.warn, waiting: t.muted, done: t.good, conflict: t.bad, idle: t.muted }[status] || color;
        return (
          <g key={lane.id}>
            <text x={0} y={cy + fs * 0.35} fill={color} fontSize={fs * 1.1} fontWeight={800}>{lane.label}</text>
            {Array.from({ length: n }).map((_, k) => {
              const sx = x0 + (span * k) / n + 2, sw = span / n - 4;
              const inside = prog >= k / n && prog < (k + 1) / n + 0.001;
              return (
                <g key={k}>
                  <rect x={sx} y={cy - barH / 2} width={sw} height={barH} rx={barH * 0.28} fill={inside ? hexA(color, 0.28) : t.panel} stroke={inside ? color : t.panelBorder} strokeWidth={inside ? t.stroke : 1.5} />
                  {checkpoints[k] && <text x={sx + sw / 2} y={cy + fs * 0.35} textAnchor="middle" fill={t.text} opacity={inside ? 1 : 0.6} fontSize={fs} fontWeight={inside ? 800 : 600} fontFamily={t.mono}>{checkpoints[k]}</text>}
                </g>
              );
            })}
            <line x1={x0} y1={cy + barH * 0.74} x2={tokenX} y2={cy + barH * 0.74} stroke={color} strokeWidth={t.stroke + 1} strokeLinecap="round" opacity={0.75} />
            <circle cx={tokenX} cy={cy - barH * 0.98} r={r} fill={stColor} style={{ filter: t.glow ? `drop-shadow(0 0 ${10 * t.glow}px ${stColor})` : undefined }} />
            {bad && <circle cx={tokenX} cy={cy - barH * 0.98} r={r * (1.25 + 0.35 * pulse)} fill="none" stroke={t.bad} strokeWidth={3} opacity={0.9 - 0.5 * pulse} />}
            {status === 'done' && <path d={`M ${tokenX - r * 0.45} ${cy - barH * 0.98} l ${r * 0.3} ${r * 0.35} l ${r * 0.65} ${-r * 0.7}`} stroke={t.bg[0]} strokeWidth={r * 0.28} fill="none" strokeLinecap="round" strokeLinejoin="round" />}
            {bad && <text x={tokenX} y={cy - barH * 0.98 + r * 0.38} textAnchor="middle" fill="#fff" fontSize={r * 1.1} fontWeight={900}>!</text>}
            {(note || status !== 'idle') && (
              <text x={x0} y={cy + barH * 0.74 + fs * 1.4} fill={stColor} fontSize={fs * 0.92} fontWeight={700} fontFamily={t.mono}>
                {status !== 'idle' ? status : ''}{note ? `  ·  ${note}` : ''}
              </text>
            )}
          </g>
        );
      })}
    </svg>
  );
};
