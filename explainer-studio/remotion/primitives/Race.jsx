import React from 'react';
import { useTheme } from '../themes.jsx';
import { useCue, valueAt, sinceMs, clamp01 } from '../hooks/cue.js';
import { fitFont, hexA } from './common.jsx';

/**
 * Before/after race: 2-4 lanes fill at speeds set by their value (lower = faster). A stopwatch counts in every
 * lane, the winner flashes. Great for "1 thread vs 4 threads", "linear vs binary search", "walking vs cycling".
 */
export const Race = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const lanes = props.lanes;
  // Starts by itself 0.5 s after it appears; a {go:0} event holds it, {go:1} (re)starts it on a sentence.
  const go = Number(valueAt(cue, 'go', 1)) > 0;
  const fired = sinceMs(cue, 'go');
  const since = !go ? -1 : Number.isFinite(fired) ? fired : cue.ageMs - 500;
  const maxV = Math.max(...lanes.map((l) => l.value));
  const raceMs = 3200;
  const minV = Math.min(...lanes.map((l) => l.value));
  const n = lanes.length;
  const rowH = h / (n + (props.title ? 0.7 : 0.2));
  const fs = fitFont('M'.repeat(12), w * 0.3, rowH * 0.32, { max: rowH * 0.3 });
  const top = props.title ? rowH * 0.7 : rowH * 0.1;
  const labelW = w * 0.28, barX = labelW + fs * 0.6, barW = w - barX - fs * 4.2, barH = rowH * 0.42;
  const cols = [t.accent, t.accent2, t.info, t.good];
  return (
    <svg width={w} height={h} style={{ fontFamily: t.font, overflow: 'visible' }}>
      {props.title && <text x={w / 2} y={rowH * 0.42} textAnchor="middle" fill={t.text} fontSize={fs * 1.15} fontWeight={800}>{props.title}</text>}
      {lanes.map((l, i) => {
        const y = top + i * rowH + (rowH - barH) / 2;
        const dur = (l.value / maxV) * raceMs;
        const p = since < 0 ? 0 : clamp01(since / dur);
        const done = p >= 1;
        const win = done && l.value === minV;
        const c = cols[i % cols.length];
        const shown = (l.value * p).toFixed(Number.isInteger(l.value) ? 0 : 1);
        const shake = !done && p > 0 ? Math.sin(cue.frame * 1.7 + i) * 1.2 : 0;
        return (
          <g key={i}>
            <text x={labelW} y={y + barH * 0.68} textAnchor="end" fill={win ? t.text : t.muted} fontSize={fs} fontWeight={800}>{l.label}</text>
            <rect x={barX} y={y} width={barW} height={barH} rx={barH / 2} fill={hexA(c, 0.12)} stroke={hexA(c, 0.5)} strokeWidth={2} />
            <rect x={barX} y={y + shake} width={Math.max(barH, barW * p)} height={barH} rx={barH / 2} fill={c} opacity={p > 0 ? 1 : 0}
              style={{ filter: win ? `drop-shadow(0 0 ${10 + 14 * t.glow}px ${c})` : undefined }} />
            {p > 0 && !done && [0, 1, 2].map((k) => (
              <line key={k} x1={barX + barW * p - barH * (0.6 + k * 0.5)} x2={barX + barW * p - barH * (1.1 + k * 0.5)} y1={y + barH * (0.3 + k * 0.2)} y2={y + barH * (0.3 + k * 0.2)} stroke={t.text} strokeWidth={2} opacity={0.35} strokeLinecap="round" />
            ))}
            <text x={barX + barW + fs * 0.5} y={y + barH * 0.7} fill={win ? c : t.text} fontSize={fs * (win ? 1.12 : 1)} fontWeight={900} fontFamily={t.mono}>
              {p > 0 ? `${shown}${l.unit ?? props.unit ?? ''}` : ''}{win ? ' ✓' : ''}
            </text>
          </g>
        );
      })}
    </svg>
  );
};
