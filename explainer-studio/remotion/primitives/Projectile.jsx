import React from 'react';
import { useTheme } from '../themes.jsx';
import { useCue, numAt, valueAt, clamp01 } from '../hooks/cue.js';

const G = 9.8;
const Arrow = ({ x1, y1, x2, y2, color, width, label, fs }) => {
  const a = Math.atan2(y2 - y1, x2 - x1), hs = width * 3.2;
  return (
    <g>
      <line x1={x1} y1={y1} x2={x2} y2={y2} stroke={color} strokeWidth={width} strokeLinecap="round" />
      <path d={`M${x2} ${y2} L${x2 - hs * Math.cos(a - 0.45)} ${y2 - hs * Math.sin(a - 0.45)} L${x2 - hs * Math.cos(a + 0.45)} ${y2 - hs * Math.sin(a + 0.45)} Z`} fill={color} />
      {label && <text x={x2 + 8} y={y2 - 8} fill={color} fontSize={fs} fontWeight={800}>{label}</text>}
    </g>
  );
};

export const Projectile = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const th = (props.angleDeg * Math.PI) / 180, v = props.speed;
  const T = (2 * v * Math.sin(th)) / G, R = (v * v * Math.sin(2 * th)) / G, H = (v * v * Math.sin(th) ** 2) / (2 * G);
  const pad = { l: w * 0.07, b: h * 0.14, t: h * 0.1 };
  const s = Math.min((w * 0.84) / R, ((h - pad.b - pad.t) * 0.9) / H);
  const gx = pad.l, gy = h - pad.b;
  const tau = clamp01(numAt(cue, 't', 0, { damping: 30, stiffness: 28 }));
  const showV = Number(valueAt(cue, 'vectors', 0)) > 0;
  const time = tau * T;
  const px = v * Math.cos(th) * time, py = v * Math.sin(th) * time - 0.5 * G * time * time;
  const X = gx + px * s, Y = gy - py * s;
  const path = Array.from({ length: 61 }, (_, i) => { const tt = (T * i) / 60; return `${i ? 'L' : 'M'}${gx + v * Math.cos(th) * tt * s} ${gy - (v * Math.sin(th) * tt - 0.5 * G * tt * tt) * s}`; }).join(' ');
  const done = Array.from({ length: Math.round(tau * 60) + 1 }, (_, i) => { const tt = (T * i) / 60; return `${i ? 'L' : 'M'}${gx + v * Math.cos(th) * tt * s} ${gy - (v * Math.sin(th) * tt - 0.5 * G * tt * tt) * s}`; }).join(' ');
  const k = Math.min(w, h) * 0.0062;
  const vx = v * Math.cos(th), vy = v * Math.sin(th) - G * time;
  const fs = Math.max(11, Math.min(h * 0.05, w * 0.03));
  const r = Math.max(7, Math.min(w, h) * 0.022);
  return (
    <svg width={w} height={h} style={{ fontFamily: t.font }}>
      <line x1={gx - 10} y1={gy} x2={w - pad.l * 0.4} y2={gy} stroke={t.text} strokeWidth={3} opacity={0.8} />
      <path d={path} fill="none" stroke={t.muted} strokeWidth={2} strokeDasharray="7 7" opacity={0.7} />
      <path d={done} fill="none" stroke={t.accent} strokeWidth={t.stroke + 1} strokeLinecap="round" />
      <circle cx={gx} cy={gy} r={r * 0.5} fill={t.muted} />
      <text x={gx + R * s} y={gy + fs * 1.6} textAnchor="middle" fill={t.muted} fontSize={fs} fontFamily={t.mono}>R ≈ {R.toFixed(0)} m</text>
      <text x={gx + (R * s) / 2} y={gy - H * s - fs * 0.8} textAnchor="middle" fill={t.muted} fontSize={fs} fontFamily={t.mono}>H ≈ {H.toFixed(0)} m</text>
      <circle cx={X} cy={Y} r={r} fill={t.accent2} style={{ filter: t.glow ? `drop-shadow(0 0 ${10 * t.glow}px ${t.accent2})` : undefined }} />
      {showV && (
        <g>
          <Arrow x1={X} y1={Y} x2={X + vx * k} y2={Y} color={t.good} width={4} label="vₓ" fs={fs} />
          <Arrow x1={X} y1={Y} x2={X} y2={Y - vy * k} color={t.warn} width={4} label="vᵧ" fs={fs} />
          <Arrow x1={X} y1={Y} x2={X + vx * k} y2={Y - vy * k} color={t.info} width={4} label="v" fs={fs} />
        </g>
      )}
      <text x={w - pad.l * 0.4} y={pad.t} textAnchor="end" fill={t.text} fontSize={fs} fontFamily={t.mono} fontWeight={700}>u = {v} m/s · θ = {props.angleDeg}°</text>
    </svg>
  );
};
