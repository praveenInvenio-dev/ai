import React from 'react';
import { useTheme } from '../themes.jsx';
import { useCue, numAt, valueAt, clamp01, popSpring } from '../hooks/cue.js';

const EL = { H: ['#ffffff', 0.62], C: ['#9aa0a6', 1], N: ['#4f7cff', 1], O: ['#ff4b4b', 1], Cl: ['#3ddc84', 1.1], Na: ['#b46bff', 1.3], S: ['#ffd33d', 1.1], P: ['#ff9f43', 1.1], K: ['#d291ff', 1.4] };

export const Molecule = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const { atoms, bonds } = props;
  const formed = numAt(cue, 'bonds', bonds.length, { damping: 24, stiffness: 50 });
  const hl = String(valueAt(cue, 'highlight', ''));
  const P = Object.fromEntries(atoms.map((a) => [a.id, { x: (a.x / 100) * w, y: (a.y / 100) * h }]));
  const base = Math.min(w, h) * 0.085;
  const fs = base * 0.8;
  const appear = clamp01(cue.ageMs / 500);
  return (
    <svg width={w} height={h} style={{ fontFamily: t.font }}>
      {bonds.map(([a, b, order], i) => {
        const prog = clamp01(formed - i);
        if (prog <= 0 || !P[a] || !P[b]) return null;
        const A = P[a], B = P[b];
        const ex = A.x + (B.x - A.x) * prog, ey = A.y + (B.y - A.y) * prog;
        const dx = B.x - A.x, dy = B.y - A.y, L = Math.hypot(dx, dy) || 1;
        const nx = (-dy / L) * base * 0.16, ny = (dx / L) * base * 0.16;
        const offs = order === 1 ? [0] : order === 2 ? [-1, 1] : [-1.6, 0, 1.6];
        return <g key={i}>{offs.map((o, k) => <line key={k} x1={A.x + nx * o} y1={A.y + ny * o} x2={ex + nx * o} y2={ey + ny * o} stroke={t.text} strokeWidth={Math.max(3, base * 0.09)} strokeLinecap="round" opacity={0.85} />)}</g>;
      })}
      {atoms.map((a) => {
        const [c, rs] = EL[a.el] || ['#cccccc', 1];
        const r = base * rs;
        const lit = hl === a.id;
        return (
          <g key={a.id} transform={`translate(${P[a.id].x} ${P[a.id].y}) scale(${0.6 + 0.4 * appear})`} opacity={appear}>
            {lit && <circle r={r * 1.45} fill="none" stroke={t.accent2} strokeWidth={4} opacity={0.5 + 0.5 * Math.sin(cue.frame * 0.3)} />}
            <circle r={r} fill={c} stroke={t.dark ? '#ffffff55' : '#000000aa'} strokeWidth={2} />
            <text textAnchor="middle" y={fs * 0.35} fontSize={fs * (rs < 1 ? 0.9 : 1)} fontWeight={800} fill={a.el === 'H' || a.el === 'S' ? '#111' : '#fff'}>{a.el}</text>
          </g>
        );
      })}
    </svg>
  );
};
