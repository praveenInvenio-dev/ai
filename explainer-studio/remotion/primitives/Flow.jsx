import React from 'react';
import { useTheme } from '../themes.jsx';
import { useCue, valueAt, clamp01, popSpring, sinceMs } from '../hooks/cue.js';
import { hexA, balanceLines } from './common.jsx';
import { evolvePath, getPointAtLength, getLength } from '@remotion/paths';
import { spring } from 'remotion';

export const Flow = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const nodes = props.nodes;
  const edges = props.edges.length ? props.edges : nodes.slice(1).map((n, i) => [nodes[i].id, n.id]);
  const active = String(valueAt(cue, 'active', ''));
  const done = new Set(String(valueAt(cue, 'done', '')).split(',').map((s) => s.trim()));
  const horizontal = w >= h * 1.1;
  const n = nodes.length;
  const bw = horizontal ? Math.min(w / (n * 1.45), h * 0.5) * 1.5 : Math.min(w * 0.7, h / (n * 1.4) * 2.4);
  const bh = horizontal ? Math.min(h * 0.4, bw * 0.7) : Math.min(h / (n * 1.5), w * 0.2);
  const pos = nodes.map((_, i) => horizontal ? { x: (w / n) * (i + 0.5), y: h / 2 } : { x: w / 2, y: (h / n) * (i + 0.5) });
  const idx = Object.fromEntries(nodes.map((nd, i) => [nd.id, i]));
  const fs = Math.max(11, Math.min(bh * 0.28, bw * 0.15));
  const pulse = 0.5 + 0.5 * Math.sin(cue.frame * 0.25);
  const enterF = Math.round((cue.nowMs - cue.ageMs) / 1000 * cue.fps);
  const uid = nodes.map((x) => x.id).join('').slice(0, 24).replace(/[^A-Za-z0-9_]/g, '');
  return (
    <svg width={w} height={h} style={{ fontFamily: t.font }}>
      <defs>
        <marker id={`arr-${uid}`} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill={t.muted} /></marker>
        <marker id={`arrl-${uid}`} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill={t.accent} /></marker>
      </defs>
      {edges.map(([a, b], k) => {
        const A = pos[idx[a]], B = pos[idx[b]];
        const adjacent = Math.abs(idx[a] - idx[b]) === 1;
        const [sx, sy, ex, ey] = horizontal ? [A.x + bw / 2, A.y, B.x - bw / 2, B.y] : [A.x, A.y + bh / 2, B.x, B.y - bh / 2];
        const lit = active === a || done.has(a);
        const d = adjacent ? `M${sx} ${sy} L${ex} ${ey}` : horizontal ? `M${A.x} ${A.y - bh / 2} Q${(A.x + B.x) / 2} ${A.y - bh * 1.6} ${B.x} ${B.y - bh / 2}` : `M${A.x + bw / 2} ${A.y} Q${A.x + bw * 1.1} ${(A.y + B.y) / 2} ${B.x + bw / 2} ${B.y}`;
        // Edges DRAW themselves in (@remotion/paths evolvePath) as the diagram builds, then a glowing
        // packet travels along every lit edge so the direction of flow is unmistakable.
        const draw = spring({ frame: Math.max(0, cue.frame - enterF - k * 5), fps: cue.fps, config: { damping: 22, stiffness: 70 } });
        const ev = evolvePath(Math.min(1, draw), d);
        const len = getLength(d);
        const pk = lit ? getPointAtLength(d, ((cue.frame * 2.4 + k * 37) % 60) / 60 * len) : null;
        return (
          <g key={k}>
            <path d={d} fill="none" stroke={lit ? t.accent : t.muted} strokeWidth={lit ? t.stroke + 1 : 2} markerEnd={draw > 0.97 ? `url(#${lit ? 'arrl' : 'arr'}-${uid})` : undefined} strokeDasharray={lit || draw < 0.995 ? ev.strokeDasharray : '8 6'} strokeDashoffset={lit || draw < 0.995 ? ev.strokeDashoffset : 0} opacity={lit ? 1 : 0.8} />
            {pk && <circle cx={pk.x} cy={pk.y} r={Math.max(4, fs * 0.28)} fill={t.accent2} style={{ filter: `drop-shadow(0 0 ${6 + 10 * t.glow}px ${t.accent2})` }} />}
          </g>
        );
      })}
      {nodes.map((nd, i) => {
        const p = pos[i];
        const isActive = active === nd.id, isDone = done.has(nd.id);
        const c = isActive ? t.accent2 : isDone ? t.good : t.accent;
        const pop = isActive ? popSpring(cue, sinceMs(cue, 'active'), { damping: 9, stiffness: 190 }) : 1;
        const lines = nd.label.length > 12 ? balanceLines(nd.label, 2) : [nd.label];
        return (
          <g key={nd.id} transform={`translate(${p.x} ${p.y}) scale(${isActive ? 0.94 + 0.08 * pop : 1})`}>
            <rect x={-bw / 2} y={-bh / 2} width={bw} height={bh} rx={bh * 0.28} fill={hexA(c, isActive ? 0.3 : isDone ? 0.2 : 0.1)} stroke={c} strokeWidth={isActive ? t.stroke + 1.5 : t.stroke} style={{ filter: isActive && t.glow ? `drop-shadow(0 0 ${14 * t.glow * (0.6 + 0.4 * pulse)}px ${c})` : undefined }} />
            {lines.map((l, k) => <text key={k} x={0} y={(k - (lines.length - 1) / 2) * fs * 1.15 + fs * 0.35} textAnchor="middle" fill={t.text} fontSize={fs} fontWeight={800}>{l}</text>)}
          </g>
        );
      })}
    </svg>
  );
};
