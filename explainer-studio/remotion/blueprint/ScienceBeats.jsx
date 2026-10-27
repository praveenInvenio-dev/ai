import React from 'react';
import { spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { C, SANS, MONO } from './style.js';

/**
 * Board-diagram beats for physics, chemistry, biology and maths: what a good teacher draws while explaining.
 * Everything is driven by the frame (deterministic), sized from the stage (sw x sh) and the unit u.
 */
const TONE = { accent: C.accent, good: C.good, bad: C.bad };
const sp = (frame, start, fps, cfg = { damping: 15, stiffness: 140 }) => spring({ frame: frame - start, fps, config: cfg });

/** Simple outlined objects that things happen TO. (cx, cy) centre, s = size. */
export function Obj({ type, cx, cy, s, color = C.text, fill = 'rgba(10,32,66,0.9)', rot = 0, u }) {
  const P = { stroke: color, strokeWidth: 3 * u, fill, strokeLinejoin: 'round', strokeLinecap: 'round' };
  let body;
  switch (type) {
    case 'ball': body = <circle {...P} cx={0} cy={0} r={s * 0.45} />; break;
    case 'apple': body = <g><path {...P} d={`M0 ${-s * 0.3} C ${s * 0.5} ${-s * 0.55} ${s * 0.6} ${s * 0.25} 0 ${s * 0.45} C ${-s * 0.6} ${s * 0.25} ${-s * 0.5} ${-s * 0.55} 0 ${-s * 0.3}`} fill="rgba(255,93,108,0.25)" /><path {...P} d={`M0 ${-s * 0.3} L ${s * 0.08} ${-s * 0.52}`} /></g>; break;
    case 'car': body = <g><path {...P} d={`M${-s * 0.55} ${s * 0.1} L${-s * 0.55} ${-s * 0.1} L${-s * 0.3} ${-s * 0.12} L${-s * 0.15} ${-s * 0.32} L${s * 0.25} ${-s * 0.32} L${s * 0.4} ${-s * 0.12} L${s * 0.55} ${-s * 0.08} L${s * 0.55} ${s * 0.1} Z`} /><circle {...P} cx={-s * 0.3} cy={s * 0.14} r={s * 0.12} /><circle {...P} cx={s * 0.3} cy={s * 0.14} r={s * 0.12} /></g>; break;
    case 'plane': body = <path {...P} d={`M${-s * 0.6} 0 Q${-s * 0.55} ${-s * 0.08} ${-s * 0.3} ${-s * 0.08} L${s * 0.45} ${-s * 0.08} Q${s * 0.62} ${-s * 0.05} ${s * 0.62} 0 Q${s * 0.62} ${s * 0.05} ${s * 0.45} ${s * 0.08} L${-s * 0.3} ${s * 0.08} Q${-s * 0.55} ${s * 0.08} ${-s * 0.6} 0 Z M${-s * 0.05} ${-s * 0.06} L${-s * 0.22} ${-s * 0.42} L${-s * 0.05} ${-s * 0.42} L${s * 0.18} ${-s * 0.06} M${-s * 0.05} ${s * 0.06} L${-s * 0.15} ${s * 0.3} L${0} ${s * 0.3} L${s * 0.14} ${s * 0.06} M${-s * 0.5} ${-s * 0.06} L${-s * 0.6} ${-s * 0.28} L${-s * 0.48} ${-s * 0.28} L${-s * 0.38} ${-s * 0.07}`} />; break;
    case 'rocket': body = <g><path {...P} d={`M0 ${-s * 0.55} Q${s * 0.2} ${-s * 0.3} ${s * 0.16} ${s * 0.3} L${-s * 0.16} ${s * 0.3} Q${-s * 0.2} ${-s * 0.3} 0 ${-s * 0.55} Z`} /><path {...P} d={`M${-s * 0.16} ${s * 0.15} L${-s * 0.3} ${s * 0.4} L${-s * 0.14} ${s * 0.3} M${s * 0.16} ${s * 0.15} L${s * 0.3} ${s * 0.4} L${s * 0.14} ${s * 0.3}`} /><circle {...P} cx={0} cy={-s * 0.15} r={s * 0.07} /></g>; break;
    case 'person': body = <g><circle {...P} cx={0} cy={-s * 0.35} r={s * 0.13} /><path {...P} fill="none" d={`M0 ${-s * 0.22} L0 ${s * 0.15} M${-s * 0.25} ${-s * 0.08} L${s * 0.25} ${-s * 0.08} M0 ${s * 0.15} L${-s * 0.18} ${s * 0.5} M0 ${s * 0.15} L${s * 0.18} ${s * 0.5}`} /></g>; break;
    case 'satellite': body = <g><rect {...P} x={-s * 0.14} y={-s * 0.14} width={s * 0.28} height={s * 0.28} /><rect {...P} x={-s * 0.6} y={-s * 0.08} width={s * 0.38} height={s * 0.16} fill="rgba(127,166,217,0.25)" /><rect {...P} x={s * 0.22} y={-s * 0.08} width={s * 0.38} height={s * 0.16} fill="rgba(127,166,217,0.25)" /></g>; break;
    case 'bird': body = <path {...P} fill="none" d={`M${-s * 0.5} ${-s * 0.1} Q${-s * 0.25} ${-s * 0.35} 0 0 Q${s * 0.25} ${-s * 0.35} ${s * 0.5} ${-s * 0.1}`} />; break;
    case 'block': body = <rect {...P} x={-s * 0.4} y={-s * 0.4} width={s * 0.8} height={s * 0.8} rx={4 * u} />; break;
    default: body = <rect {...P} x={-s * 0.45} y={-s * 0.35} width={s * 0.9} height={s * 0.7} rx={6 * u} />;
  }
  return <g transform={`translate(${cx} ${cy}) rotate(${rot})`}>{body}</g>;
}

function Arrow({ x1, y1, x2, y2, color, u, w = 5, label, labelPos = 1.15, grow = 1 }) {
  const ex = x1 + (x2 - x1) * grow, ey = y1 + (y2 - y1) * grow;
  const a = Math.atan2(ey - y1, ex - x1), h = 18 * u;
  if (grow <= 0.02) return null;
  return (
    <g>
      <line x1={x1} y1={y1} x2={ex - Math.cos(a) * h * 0.6} y2={ey - Math.sin(a) * h * 0.6} stroke={color} strokeWidth={w * u} strokeLinecap="round" />
      <path d={`M${ex} ${ey} L${ex - Math.cos(a - 0.45) * h} ${ey - Math.sin(a - 0.45) * h} L${ex - Math.cos(a + 0.45) * h} ${ey - Math.sin(a + 0.45) * h} Z`} fill={color} />
      {label && grow > 0.8 && <text x={x1 + (x2 - x1) * labelPos} y={y1 + (y2 - y1) * labelPos + 10 * u} textAnchor="middle" fill={color} fontFamily={MONO} fontWeight={700} fontSize={26 * u}>{label}</text>}
    </g>
  );
}

function ForcesBeat({ b, sw, sh, u, start }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const cx = sw / 2, cy = sh * (b.ground ? 0.55 : 0.5);
  const s = Math.min(sw, sh) * (b.object === 'plane' ? 0.46 : 0.28);
  // where an arrow leaves the object: a plane is long and flat, everything else roughly round
  const half = b.object === 'plane' ? { x: s * 0.62, y: s * 0.1 } : { x: s * 0.45, y: s * 0.42 };
  const p = sp(frame, start, fps);
  const groundY = cy + s * 0.45;
  return (
    <svg width={sw} height={sh} style={{ overflow: 'visible' }}>
      {b.ground && <g><line x1={sw * 0.08} x2={sw * 0.92} y1={groundY} y2={groundY} stroke={C.line} strokeWidth={3 * u} />
        {Array.from({ length: 16 }, (_, i) => <line key={i} x1={sw * 0.1 + i * sw * 0.052} x2={sw * 0.08 + i * sw * 0.052} y1={groundY} y2={groundY + 16 * u} stroke={C.dim} strokeWidth={2 * u} />)}</g>}
      <g opacity={p}><Obj type={b.object} cx={cx} cy={cy} s={s} u={u} /></g>
      {b.forces.map((f, i) => {
        const g = Math.min(1, Math.max(0, (frame - start - 10 - i * 10) / 12));
        const rad = (f.dir * Math.PI) / 180;
        const len = (Math.min(sw, sh) * 0.11) * (0.6 + 0.45 * f.size);
        const x1 = cx + Math.cos(rad) * half.x, y1 = cy - Math.sin(rad) * half.y;
        const x2 = x1 + Math.cos(rad) * len, y2 = y1 - Math.sin(rad) * len;
        // label just beyond the arrow head, pushed outward along the arrow and kept inside the stage
        const lx = Math.min(sw - 60 * u, Math.max(60 * u, x2 + Math.cos(rad) * 44 * u)), ly = Math.min(sh - 6 * u, Math.max(28 * u, y2 - Math.sin(rad) * 34 * u + 9 * u));
        return <g key={i}><Arrow x1={x1} y1={y1} x2={x2} y2={y2} color={TONE[f.tone]} u={u} grow={g} w={6} />
          {g > 0.8 && <text x={lx} y={ly} textAnchor="middle" fill={TONE[f.tone]} fontFamily={MONO} fontWeight={800} fontSize={28 * u}>{f.label}</text>}</g>;
      })}
    </svg>
  );
}

function MotionBeat({ b, sw, sh, u, start }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const T = 3.2 * fps;
  const t = ((frame - start) % T) / T;
  const s = Math.min(sw, sh) * (b.mode === 'circular' ? 0.16 : 0.22);
  const x0 = sw * 0.12, x1 = sw * 0.88, gy = sh * 0.7;
  const pos = (tt) => {
    switch (b.mode) {
      case 'accelerate': return { x: x0 + (x1 - x0) * tt * tt, y: gy - s * 0.45 };
      case 'decelerate': return { x: x0 + (x1 - x0) * (1 - (1 - tt) * (1 - tt)), y: gy - s * 0.45 };
      case 'fall': return { x: sw / 2, y: sh * 0.08 + (gy - s * 0.45 - sh * 0.08) * tt * tt };
      case 'projectile': return { x: x0 + (x1 - x0) * tt, y: gy - s * 0.45 - sh * 0.55 * 4 * tt * (1 - tt) };
      case 'circular': { const a = tt * Math.PI * 2; const R = Math.min(sw, sh) * 0.32; return { x: sw / 2 + Math.cos(a) * R, y: sh * 0.48 + Math.sin(a) * R }; }
      default: return { x: x0 + (x1 - x0) * tt, y: gy - s * 0.45 };
    }
  };
  const cur = pos(t), next = pos(Math.min(1, t + 0.04));
  const trail = Array.from({ length: 8 }, (_, k) => pos(Math.max(0, t - (k + 1) * 0.08))).filter((_, k) => t - (k + 1) * 0.08 > 0);
  const vx = next.x - cur.x, vy = next.y - cur.y, vl = Math.hypot(vx, vy) || 1;
  const vlen = Math.min(sw * 0.2, vl * 7);
  return (
    <svg width={sw} height={sh} style={{ overflow: 'visible' }}>
      {b.mode === 'circular'
        ? <g><circle cx={sw / 2} cy={sh * 0.48} r={Math.min(sw, sh) * 0.32} fill="none" stroke={C.dim} strokeWidth={2 * u} strokeDasharray={`${8 * u} ${8 * u}`} /><circle cx={sw / 2} cy={sh * 0.48} r={Math.min(sw, sh) * 0.07} fill="rgba(127,166,217,0.2)" stroke={C.line} strokeWidth={3 * u} />
          <Arrow x1={cur.x} y1={cur.y} x2={cur.x + (sw / 2 - cur.x) * 0.35} y2={cur.y + (sh * 0.48 - cur.y) * 0.35} color={C.bad} u={u} w={4} /></g>
        : <line x1={sw * 0.05} x2={sw * 0.95} y1={gy} y2={gy} stroke={C.line} strokeWidth={3 * u} />}
      {trail.map((q, k) => <circle key={k} cx={q.x} cy={q.y} r={6 * u} fill={C.accent} opacity={0.5 - k * 0.05} />)}
      <Obj type={b.object} cx={cur.x} cy={cur.y} s={s} u={u} rot={b.mode === 'projectile' || b.mode === 'circular' ? (Math.atan2(vy, vx) * 180) / Math.PI * (b.object === 'plane' || b.object === 'rocket' ? 1 : 0) : 0} />
      <Arrow x1={cur.x} y1={cur.y - s * 0.7} x2={cur.x + (vx / vl) * vlen} y2={cur.y - s * 0.7 + (vy / vl) * vlen} color={C.accent} u={u} w={4} label={b.label || 'v'} labelPos={1.25} />
    </svg>
  );
}

function GraphBeat({ b, sw, sh, u, start }) {
  const frame = useCurrentFrame();
  const W = sw * 0.84, H = sh * 0.74, ox = sw * 0.12, oy = sh * 0.84;
  const f = (x) => {
    switch (b.curve) {
      case 'quadratic': return x * x;
      case 'inverse': return Math.min(1, 0.08 / (x + 0.04));
      case 'exp': return (Math.exp(3 * x) - 1) / (Math.exp(3) - 1);
      case 'decay': return Math.exp(-3.2 * x);
      case 'sine': return 0.5 + 0.42 * Math.sin(x * Math.PI * 4);
      case 'constant': return 0.55;
      case 'log': return Math.log(1 + 9 * x) / Math.log(10);
      default: return 0.05 + 0.85 * x;
    }
  };
  const grow = Math.min(1, Math.max(0, (frame - start - 6) / 36));
  const N = 80, pts = [];
  for (let i = 0; i <= N * grow; i++) { const x = i / N; pts.push(`${ox + x * W},${oy - f(x) * H}`); }
  const px = b.point != null ? b.point : null;
  return (
    <svg width={sw} height={sh} style={{ overflow: 'visible' }}>
      {[0.25, 0.5, 0.75].map((k) => <g key={k}><line x1={ox} x2={ox + W} y1={oy - H * k} y2={oy - H * k} stroke={C.dim} strokeWidth={1} strokeDasharray="4 6" /><line x1={ox + W * k} x2={ox + W * k} y1={oy} y2={oy - H} stroke={C.dim} strokeWidth={1} strokeDasharray="4 6" /></g>)}
      <Arrow x1={ox} y1={oy} x2={ox + W + 20 * u} y2={oy} color={C.line} u={u} w={3} />
      <Arrow x1={ox} y1={oy} x2={ox} y2={oy - H - 20 * u} color={C.line} u={u} w={3} />
      <text x={ox + W} y={oy + 44 * u} textAnchor="end" fill={C.muted} fontFamily={MONO} fontSize={24 * u}>{b.x}</text>
      <text x={ox - 14 * u} y={oy - H - 34 * u} fill={C.muted} fontFamily={MONO} fontSize={24 * u}>{b.y}</text>
      {pts.length > 1 && <polyline points={pts.join(' ')} fill="none" stroke={C.accent} strokeWidth={6 * u} strokeLinejoin="round" strokeLinecap="round" style={{ filter: 'drop-shadow(0 0 8px rgba(247,201,72,0.5))' }} />}
      {px != null && grow >= px && <g><line x1={ox + px * W} x2={ox + px * W} y1={oy} y2={oy - f(px) * H} stroke={C.text} strokeWidth={2 * u} strokeDasharray="6 6" /><circle cx={ox + px * W} cy={oy - f(px) * H} r={10 * u} fill={C.text} /></g>}
      {b.label && grow > 0.9 && <text x={ox + W * 0.5} y={oy - H * 0.92} textAnchor="middle" fill={C.accent} fontFamily={MONO} fontWeight={700} fontSize={28 * u}>{b.label}</text>}
    </svg>
  );
}

function EquationBeat({ b, sw, u, start }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const toks = b.expr.split(/\s+/);
  const fs = Math.min(140 * u, (sw / Math.max(4, b.expr.length)) * 1.7);
  return (
    <div style={{ textAlign: 'center', fontFamily: SANS }}>
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'baseline', gap: fs * 0.25, flexWrap: 'wrap' }}>
        {toks.map((tk, i) => {
          const p = sp(frame, start + i * 5, fps, { damping: 12, stiffness: 160 });
          const hl = b.eqHighlight && tk.replace(/[^\p{L}\p{N}²³]/gu, '') === b.eqHighlight.replace(/[^\p{L}\p{N}²³]/gu, '');
          return <span key={i} style={{ fontSize: fs, fontWeight: 900, color: hl ? C.accent : /^[=+\-×÷/·→]$/.test(tk) ? C.muted : C.text, opacity: Math.min(1, p * 1.5), display: 'inline-block', transform: `translateY(${(1 - p) * 30}px)`, textShadow: hl ? '0 0 24px rgba(247,201,72,0.6)' : undefined }}>{tk}</span>;
        })}
      </div>
      <div style={{ marginTop: 34 * u, display: 'inline-grid', gridTemplateColumns: 'auto auto', gap: `${12 * u}px ${24 * u}px`, textAlign: 'left' }}>
        {b.terms.map((t, i) => {
          const p = sp(frame, start + 20 + i * 8, fps);
          return <React.Fragment key={i}><span style={{ fontFamily: SANS, fontWeight: 900, fontSize: 40 * u, color: C.accent, opacity: p }}>{t.sym}</span><span style={{ fontFamily: MONO, fontSize: 28 * u, color: C.muted, opacity: p, alignSelf: 'center' }}>{t.meaning}</span></React.Fragment>;
        })}
      </div>
    </div>
  );
}

function FlowBeat({ b, sw, sh, u, start }) {
  const frame = useCurrentFrame();
  const cx = sw / 2, cy = sh * 0.5, L = sw * 0.46;
  const shapeY = (x) => {
    if (b.shape === 'ball') { const r = sh * 0.16; const d = Math.abs(x - cx); return d < r ? Math.sqrt(r * r - d * d) : 0; }
    if (b.shape === 'pipe') return 0;
    const t = (x - (cx - L / 2)) / L;
    return t > 0 && t < 1 ? sh * 0.13 * Math.sin(Math.PI * Math.pow(t, 0.7)) : 0;
  };
  const lines = [-3, -2, -1, 1, 2, 3];
  const path = (k) => {
    const pts = [];
    for (let x = 0; x <= sw; x += sw / 60) {
      let y = cy + k * sh * 0.08;
      if (b.shape === 'pipe') { const t = Math.exp(-Math.pow((x - cx) / (sw * 0.18), 2)); y = cy + k * sh * 0.08 * (1 - 0.55 * t); }
      else if (k < 0) y -= shapeY(x) * (1.2 / -k);
      else y += (b.shape === 'ball' ? shapeY(x) : shapeY(x) * 0.15) * (1.2 / k);
      pts.push([x, y]);
    }
    return pts;
  };
  const speed = (k) => (b.shape === 'pipe' ? 1 : k < 0 ? 1.7 : 1);
  return (
    <svg width={sw} height={sh} style={{ overflow: 'visible' }}>
      {lines.map((k) => {
        const pts = path(k);
        return <g key={k}>
          <polyline points={pts.map((p) => p.join(',')).join(' ')} fill="none" stroke={k < 0 ? 'rgba(247,201,72,0.55)' : 'rgba(127,166,217,0.55)'} strokeWidth={2.5 * u} />
          {[0, 0.33, 0.66].map((o, j) => { const t = ((frame - start) * 0.012 * speed(k) + o + k * 0.07 + 10) % 1; const p = pts[Math.floor(t * (pts.length - 1))]; return <circle key={j} cx={p[0]} cy={p[1]} r={5 * u} fill={k < 0 ? C.accent : C.line} />; })}
        </g>;
      })}
      {b.shape === 'airfoil' && <path d={`M${cx - L / 2} ${cy} C ${cx - L * 0.35} ${cy - sh * 0.17} ${cx + L * 0.15} ${cy - sh * 0.14} ${cx + L / 2} ${cy + sh * 0.02} C ${cx + L * 0.1} ${cy + sh * 0.03} ${cx - L * 0.3} ${cy + sh * 0.04} ${cx - L / 2} ${cy} Z`} fill="rgba(10,32,66,0.95)" stroke={C.text} strokeWidth={3 * u} />}
      {b.shape === 'ball' && <circle cx={cx} cy={cy} r={sh * 0.16} fill="rgba(10,32,66,0.95)" stroke={C.text} strokeWidth={3 * u} />}
      {b.shape === 'pipe' && <g fill="none" stroke={C.text} strokeWidth={3 * u}>{[-1, 1].map((k) => <path key={k} d={Array.from({ length: 61 }, (_, i) => { const x = (sw * i) / 60; const t = Math.exp(-Math.pow((x - cx) / (sw * 0.18), 2)); return `${i ? 'L' : 'M'}${x} ${cy + k * sh * 0.3 * (1 - 0.55 * t)}`; }).join(' ')} />)}</g>}
      {b.top && <text x={cx} y={sh * 0.08} textAnchor="middle" fill={C.accent} fontFamily={MONO} fontWeight={700} fontSize={26 * u}>{b.top}</text>}
      {b.bottom && <text x={cx} y={sh * 0.97} textAnchor="middle" fill={C.line} fontFamily={MONO} fontWeight={700} fontSize={26 * u}>{b.bottom}</text>}
      {b.arrow && <Arrow x1={cx} y1={cy - sh * 0.06} x2={cx} y2={cy - sh * 0.3} color={C.good} u={u} w={7} label={b.arrow} labelPos={1.18} grow={Math.min(1, Math.max(0, (frame - start - 20) / 15))} />}
    </svg>
  );
}

function Shape({ shape, cx, cy, R, u }) {
  const P = { stroke: C.text, strokeWidth: 3 * u, fill: 'rgba(10,32,66,0.9)' };
  switch (shape) {
    case 'cell': return <g><ellipse {...P} cx={cx} cy={cy} rx={R} ry={R * 0.72} /><circle cx={cx + R * 0.15} cy={cy} r={R * 0.24} fill="rgba(127,166,217,0.25)" stroke={C.line} strokeWidth={3 * u} /><ellipse cx={cx - R * 0.5} cy={cy - R * 0.25} rx={R * 0.16} ry={R * 0.07} fill="none" stroke={C.good} strokeWidth={2.5 * u} /><ellipse cx={cx - R * 0.45} cy={cy + R * 0.3} rx={R * 0.13} ry={R * 0.06} fill="none" stroke={C.good} strokeWidth={2.5 * u} />{[0, 1, 2].map((k) => <circle key={k} cx={cx + R * (0.55 - k * 0.1)} cy={cy + R * (0.35 - k * 0.2)} r={4 * u} fill={C.accent} />)}</g>;
    case 'atom': return <g><circle cx={cx} cy={cy} r={R * 0.16} fill="rgba(255,93,108,0.3)" stroke={C.bad} strokeWidth={3 * u} />{[0, 60, 120].map((a) => <ellipse key={a} cx={cx} cy={cy} rx={R} ry={R * 0.35} fill="none" stroke={C.line} strokeWidth={2 * u} transform={`rotate(${a} ${cx} ${cy})`} />)}</g>;
    case 'leaf': return <g><path {...P} fill="rgba(61,220,151,0.15)" stroke={C.good} d={`M${cx - R} ${cy} Q${cx} ${cy - R * 0.9} ${cx + R} ${cy} Q${cx} ${cy + R * 0.9} ${cx - R} ${cy} Z`} /><line x1={cx - R} y1={cy} x2={cx + R} y2={cy} stroke={C.good} strokeWidth={2 * u} /></g>;
    case 'heart': return <path {...P} fill="rgba(255,93,108,0.18)" stroke={C.bad} d={`M${cx} ${cy + R * 0.8} C ${cx - R * 1.3} ${cy} ${cx - R * 0.8} ${cy - R} ${cx} ${cy - R * 0.4} C ${cx + R * 0.8} ${cy - R} ${cx + R * 1.3} ${cy} ${cx} ${cy + R * 0.8} Z`} />;
    case 'earth': return <g>{[1, 0.75, 0.5, 0.25].map((k, i) => <circle key={k} cx={cx} cy={cy} r={R * k} fill={['rgba(61,220,151,0.12)', 'rgba(247,201,72,0.12)', 'rgba(255,150,80,0.18)', 'rgba(255,93,108,0.3)'][i]} stroke={C.line} strokeWidth={2 * u} />)}</g>;
    case 'eye': return <g><path {...P} d={`M${cx - R} ${cy} Q${cx} ${cy - R * 0.75} ${cx + R} ${cy} Q${cx} ${cy + R * 0.75} ${cx - R} ${cy} Z`} /><circle cx={cx} cy={cy} r={R * 0.3} fill="rgba(127,166,217,0.3)" stroke={C.line} strokeWidth={3 * u} /><circle cx={cx} cy={cy} r={R * 0.12} fill={C.text} /></g>;
    case 'neuron': return <g><circle {...P} cx={cx - R * 0.5} cy={cy} r={R * 0.25} />{[-50, -20, 20, 50, 160, 200].map((a) => <line key={a} x1={cx - R * 0.5} y1={cy} x2={cx - R * 0.5 + Math.cos((a * Math.PI) / 180) * R * 0.55 * (a > 90 ? 1 : 0.01)} y2={cy + Math.sin((a * Math.PI) / 180) * R * 0.55} stroke={C.text} strokeWidth={3 * u} />)}<line x1={cx - R * 0.25} y1={cy} x2={cx + R} y2={cy} stroke={C.text} strokeWidth={4 * u} />{[0.1, 0.35, 0.6].map((k) => <rect key={k} x={cx + R * k} y={cy - 8 * u} width={R * 0.18} height={16 * u} rx={8 * u} fill="rgba(247,201,72,0.3)" stroke={C.accent} strokeWidth={2 * u} />)}</g>;
    case 'box': return <rect {...P} x={cx - R} y={cy - R * 0.7} width={R * 2} height={R * 1.4} rx={12 * u} />;
    default: return <circle {...P} cx={cx} cy={cy} r={R} />;
  }
}

function PartsBeat({ b, sw, sh, u, start }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const cx = sw / 2, cy = sh * 0.5, R = Math.min(sw * 0.26, sh * 0.32);
  const p = sp(frame, start, fps);
  const n = b.labels.length;
  return (
    <svg width={sw} height={sh} style={{ overflow: 'visible' }}>
      <g opacity={p} transform={`translate(${cx} ${cy}) scale(${0.85 + 0.15 * p}) translate(${-cx} ${-cy})`}><Shape shape={b.shape} cx={cx} cy={cy} R={R} u={u} /></g>
      {b.labels.map((l, i) => {
        const left = i % 2 === 0, row = Math.floor(i / 2), rows = Math.ceil(n / 2);
        const ty = sh * 0.12 + (sh * 0.76 * (row + 0.5)) / rows;
        const ang = Math.atan2(ty - cy, left ? -R : R);
        const ax = cx + Math.cos(ang) * R * 0.75, ay = cy + Math.sin(ang) * R * 0.6;
        const tx = left ? sw * 0.02 : sw * 0.98;
        const q = sp(frame, start + 12 + i * 8, fps);
        const on = i === b.focus;
        return <g key={i} opacity={q}>
          <line x1={ax} y1={ay} x2={left ? tx + 170 * u : tx - 170 * u} y2={ty} stroke={on ? C.accent : C.dim} strokeWidth={2 * u} />
          <circle cx={ax} cy={ay} r={6 * u} fill={on ? C.accent : C.line} />
          <text x={tx} y={ty + 9 * u} textAnchor={left ? 'start' : 'end'} fill={on ? C.accent : C.text} fontFamily={MONO} fontWeight={on ? 800 : 600} fontSize={26 * u}>{l}</text>
        </g>;
      })}
      {b.title && <text x={cx} y={sh * 0.03} textAnchor="middle" fill={C.muted} fontFamily={MONO} fontSize={24 * u} letterSpacing={2}>{b.title.toUpperCase()}</text>}
    </svg>
  );
}

function CycleBeat({ b, sw, sh, u, start }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const n = b.stages.length, cx = sw / 2, cy = sh * 0.5, R = Math.min(sw * 0.33, sh * 0.36);
  const nodes = b.stages.map((_, i) => { const a = -Math.PI / 2 + (i / n) * Math.PI * 2; return { x: cx + Math.cos(a) * R, y: cy + Math.sin(a) * R, a }; });
  const t = ((frame - start) / (fps * 4)) % 1;
  const ta = -Math.PI / 2 + t * Math.PI * 2;
  const bw = Math.min(250 * u, sw * 0.3), bh = 74 * u;
  return (
    <svg width={sw} height={sh} style={{ overflow: 'visible' }}>
      <circle cx={cx} cy={cy} r={R} fill="none" stroke={C.dim} strokeWidth={3 * u} strokeDasharray={`${10 * u} ${8 * u}`} />
      <circle cx={cx + Math.cos(ta) * R} cy={cy + Math.sin(ta) * R} r={9 * u} fill={C.accent} style={{ filter: 'drop-shadow(0 0 8px rgba(247,201,72,0.8))' }} />
      {nodes.map((nd, i) => {
        const p = sp(frame, start + i * 6, fps);
        const on = i === b.active;
        return <g key={i} opacity={p}>
          <rect x={nd.x - bw / 2} y={nd.y - bh / 2} width={bw} height={bh} rx={12 * u} fill={on ? 'rgba(247,201,72,0.14)' : C.panel} stroke={on ? C.accent : C.line} strokeWidth={2.5 * u} />
          <text x={nd.x} y={nd.y + 9 * u} textAnchor="middle" fill={on ? C.accent : C.text} fontFamily={MONO} fontWeight={700} fontSize={Math.min(26 * u, (bw / Math.max(6, b.stages[i].length)) * 1.6)}>{b.stages[i].toUpperCase()}</text>
        </g>;
      })}
    </svg>
  );
}

function ReactionBeat({ b, sw, u, start }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const a = sp(frame, start, fps), arr = Math.min(1, Math.max(0, (frame - start - 10) / 14)), c = sp(frame, start + 22, fps, { damping: 11, stiffness: 150 });
  const fs = Math.min(84 * u, (sw / Math.max(10, b.left.length + b.right.length + 4)) * 1.8);
  return (
    <div style={{ textAlign: 'center', fontFamily: SANS }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 24 * u, flexWrap: 'wrap' }}>
        <span style={{ fontSize: fs, fontWeight: 900, color: C.text, opacity: a }}>{b.left}</span>
        <span style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'center', minWidth: 160 * u }}>
          <span style={{ fontFamily: MONO, fontSize: 24 * u, color: C.accent, opacity: arr }}>{b.over}</span>
          <svg width={160 * u} height={40 * u}><Arrow x1={6 * u} y1={20 * u} x2={150 * u} y2={20 * u} color={C.accent} u={u} w={5} grow={arr} /></svg>
        </span>
        <span style={{ fontSize: fs, fontWeight: 900, color: C.good, opacity: c, display: 'inline-block', transform: `scale(${0.8 + 0.2 * c})` }}>{b.right}</span>
      </div>
      {b.note && <div style={{ marginTop: 30 * u, fontFamily: MONO, fontSize: 28 * u, color: C.muted, opacity: c }}>{b.note}</div>}
    </div>
  );
}

function WaveBeat({ b, sw, sh, u, start }) {
  const frame = useCurrentFrame();
  const W = sw * 0.92, ox = sw * 0.04, cy = sh * 0.5, A = sh * 0.22, lam = W / 2.5;
  const ph = ((frame - start) * 0.05 * b.speed);
  const pts = Array.from({ length: 121 }, (_, i) => { const x = (W * i) / 120; return `${ox + x},${cy - A * Math.sin((2 * Math.PI * x) / lam - ph)}`; });
  const crest = (lam / 4 + (ph / (2 * Math.PI)) * lam) % lam;
  return (
    <svg width={sw} height={sh} style={{ overflow: 'visible' }}>
      <line x1={ox} x2={ox + W} y1={cy} y2={cy} stroke={C.dim} strokeWidth={2 * u} strokeDasharray="6 8" />
      <polyline points={pts.join(' ')} fill="none" stroke={C.accent} strokeWidth={6 * u} style={{ filter: 'drop-shadow(0 0 8px rgba(247,201,72,0.5))' }} />
      <g><line x1={ox + crest} x2={ox + crest + lam} y1={cy - A - 30 * u} y2={cy - A - 30 * u} stroke={C.text} strokeWidth={2 * u} />
        <line x1={ox + crest} x2={ox + crest} y1={cy - A - 42 * u} y2={cy - A - 18 * u} stroke={C.text} strokeWidth={2 * u} /><line x1={ox + crest + lam} x2={ox + crest + lam} y1={cy - A - 42 * u} y2={cy - A - 18 * u} stroke={C.text} strokeWidth={2 * u} />
        <text x={ox + crest + lam / 2} y={cy - A - 46 * u} textAnchor="middle" fill={C.text} fontFamily={SANS} fontWeight={800} fontSize={36 * u}>{b.wavelength}</text></g>
      <Arrow x1={ox + 40 * u} y1={cy} x2={ox + 40 * u} y2={cy - A} color={C.good} u={u} w={4} />
      <text x={ox + 60 * u} y={cy - A / 2} fill={C.good} fontFamily={SANS} fontWeight={800} fontSize={34 * u}>{b.amplitude}</text>
      {b.label && <text x={sw / 2} y={sh * 0.97} textAnchor="middle" fill={C.muted} fontFamily={MONO} fontSize={24 * u} letterSpacing={2}>{b.label}</text>}
    </svg>
  );
}

export const SCIENCE_BEATS = { forces: ForcesBeat, motion: MotionBeat, graph: GraphBeat, equation: EquationBeat, flow: FlowBeat, parts: PartsBeat, cycle: CycleBeat, reaction: ReactionBeat, wave: WaveBeat };
