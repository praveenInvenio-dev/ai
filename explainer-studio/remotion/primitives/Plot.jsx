import React, { useMemo } from 'react';
import { compile } from 'mathjs';
import { useTheme } from '../themes.jsx';
import { useCue, numAt, clamp01 } from '../hooks/cue.js';

export const Plot = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const { expr, xMin, xMax, yMin, yMax, label } = props;
  const f = useMemo(() => { try { const c = compile(expr); return (x) => { const v = c.evaluate({ x }); return typeof v === 'number' && Number.isFinite(v) ? v : NaN; }; } catch { return () => NaN; } }, [expr]);
  const pad = { l: w * 0.1, r: w * 0.04, t: h * 0.12, b: h * 0.14 };
  const pw = w - pad.l - pad.r, ph = h - pad.t - pad.b;
  const X = (x) => pad.l + ((x - xMin) / (xMax - xMin)) * pw;
  const Y = (y) => pad.t + (1 - (y - yMin) / (yMax - yMin)) * ph;
  const d = useMemo(() => {
    let s = '', pen = false;
    for (let i = 0; i <= 200; i++) {
      const x = xMin + ((xMax - xMin) * i) / 200, y = f(x);
      if (!Number.isFinite(y) || y < yMin - (yMax - yMin) || y > yMax + (yMax - yMin)) { pen = false; continue; }
      s += `${pen ? 'L' : 'M'}${X(x).toFixed(1)} ${Y(y).toFixed(1)} `; pen = true;
    }
    return s;
  }, [f, xMin, xMax, yMin, yMax, w, h]);
  const draw = clamp01(numAt(cue, 'draw', 0, { damping: 26, stiffness: 40 }));
  const hasMarker = cue.events.some((e) => 'marker' in e.set);
  const mx = numAt(cue, 'marker', xMin, { damping: 22, stiffness: 55 });
  const my = f(mx);
  const fs = Math.max(11, Math.min(h * 0.06, w * 0.032));
  const ticks = (a, b, n) => Array.from({ length: n + 1 }, (_, i) => a + ((b - a) * i) / n);
  const fmt = (v) => (Math.abs(v) >= 100 ? v.toFixed(0) : Number.isInteger(v) ? String(v) : v.toFixed(1));
  const x0 = yMin <= 0 && yMax >= 0 ? Y(0) : pad.t + ph;
  const y0 = xMin <= 0 && xMax >= 0 ? X(0) : pad.l;
  return (
    <svg width={w} height={h} style={{ fontFamily: t.mono }}>
      <text x={pad.l} y={pad.t * 0.62} fill={t.text} fontSize={fs * 1.15} fontWeight={800} fontFamily={t.font}>{label}</text>
      {ticks(xMin, xMax, 6).map((x, i) => <g key={`x${i}`}><line x1={X(x)} y1={pad.t} x2={X(x)} y2={pad.t + ph} stroke={t.grid} strokeWidth={1.5} /><text x={X(x)} y={pad.t + ph + fs * 1.4} fill={t.muted} fontSize={fs * 0.85} textAnchor="middle">{fmt(x)}</text></g>)}
      {ticks(yMin, yMax, 5).map((y, i) => <g key={`y${i}`}><line x1={pad.l} y1={Y(y)} x2={pad.l + pw} y2={Y(y)} stroke={t.grid} strokeWidth={1.5} /><text x={pad.l - fs * 0.5} y={Y(y) + fs * 0.3} fill={t.muted} fontSize={fs * 0.85} textAnchor="end">{fmt(y)}</text></g>)}
      <line x1={pad.l} y1={x0} x2={pad.l + pw} y2={x0} stroke={t.text} strokeWidth={2} opacity={0.7} />
      <line x1={y0} y1={pad.t} x2={y0} y2={pad.t + ph} stroke={t.text} strokeWidth={2} opacity={0.7} />
      <path d={d} pathLength={1} fill="none" stroke={t.accent} strokeWidth={t.stroke + 1.5} strokeLinecap="round" strokeDasharray="1" strokeDashoffset={1 - draw} style={{ filter: t.glow ? `drop-shadow(0 0 ${8 * t.glow}px ${t.accent})` : undefined }} />
      {hasMarker && Number.isFinite(my) && (
        <g>
          <line x1={X(mx)} y1={Y(my)} x2={X(mx)} y2={x0} stroke={t.accent2} strokeWidth={2} strokeDasharray="6 5" />
          <circle cx={X(mx)} cy={Y(my)} r={fs * 0.55} fill={t.accent2} />
          <text x={Math.min(X(mx) + fs, w - fs * 7)} y={Math.max(fs * 2, Y(my) - fs)} fill={t.accent2} fontSize={fs} fontWeight={800}>({fmt(+mx.toFixed(2))}, {fmt(+my.toFixed(2))})</text>
        </g>
      )}
    </svg>
  );
};
