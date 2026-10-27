import React, { useMemo } from 'react';
import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';

/**
 * Narrated-diagram explainer ("blueprint" style): navy blueprint paper, outlined devices, flowing dots, one big number
 * or one key phrase at a time. The diagram changes with EVERY sentence (its `beat`), so the picture always shows what is
 * being said. Pure function of the frame; the background is painted once (software rendering is slow on full frames).
 */
import { C, SANS, MONO } from './style.js';
import { SCIENCE_BEATS } from './ScienceBeats.jsx';

// ── background (painted once) ───────────────────────────────────────────────
const bgCache = new Map();
function paintBg(w, h) {
  const key = `${w}x${h}`;
  if (bgCache.has(key) || typeof document === 'undefined') return bgCache.get(key) || null;
  const c = document.createElement('canvas');
  c.width = Math.round(w / 2); c.height = Math.round(h / 2);
  const g = c.getContext('2d');
  const W = c.width, H = c.height;
  const gr = g.createRadialGradient(W / 2, H * 0.35, 0, W / 2, H * 0.35, Math.hypot(W, H) * 0.7);
  gr.addColorStop(0, C.bg0); gr.addColorStop(1, C.bg1);
  g.fillStyle = gr; g.fillRect(0, 0, W, H);
  const s = Math.min(W, H) / 18;
  g.strokeStyle = C.grid; g.lineWidth = 1; g.beginPath();
  for (let x = 0; x < W; x += s) { g.moveTo(Math.round(x) + 0.5, 0); g.lineTo(Math.round(x) + 0.5, H); }
  for (let y = 0; y < H; y += s) { g.moveTo(0, Math.round(y) + 0.5); g.lineTo(W, Math.round(y) + 0.5); }
  g.stroke();
  const img = g.getImageData(0, 0, W, H);
  let n = 98765;
  for (let i = 0; i < img.data.length; i += 4) { n = (n * 1103515245 + 12345) & 0x7fffffff; const d = ((n & 255) / 255 - 0.5) * 7; img.data[i] += d; img.data[i + 1] += d; img.data[i + 2] += d; }
  g.putImageData(img, 0, 0);
  const url = c.toDataURL('image/png');
  bgCache.set(key, url);
  return url;
}

// ── helpers ─────────────────────────────────────────────────────────────────
const useSpring = (startFrame, cfg = { damping: 16, stiffness: 140 }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return spring({ frame: frame - startFrame, fps, config: cfg });
};
const hi = (text, words, color = C.accent) => {
  const keys = String(words || '').toLowerCase().split(/\s+/).filter((w) => w.length > 1);
  return String(text).split(/(\s+)/).map((w, i) => {
    const k = w.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
    return keys.some((x) => x.replace(/[^\p{L}\p{N}]/gu, '') === k && k) ? <span key={i} style={{ color }}>{w}</span> : <span key={i}>{w}</span>;
  });
};

/** Simple outlined glyphs: every "thing" in a diagram is one of these. */
function Glyph({ type, x, y, w, h, color, fill = 'none', sw = 2 }) {
  const r = Math.min(w, h) * 0.12;
  const cx = x + w / 2, cy = y + h / 2;
  const P = { stroke: color, strokeWidth: sw, fill, strokeLinecap: 'round', strokeLinejoin: 'round' };
  switch (type) {
    case 'phone': return <g><rect {...P} x={x + w * 0.2} y={y} width={w * 0.6} height={h} rx={r} /><line {...P} x1={cx - w * 0.08} x2={cx + w * 0.08} y1={y + h * 0.9} y2={y + h * 0.9} /></g>;
    case 'server': return <g><rect {...P} x={x} y={y} width={w} height={h} rx={r * 0.6} />{[0.28, 0.5, 0.72].map((k) => <g key={k}><line {...P} x1={x + w * 0.15} x2={x + w * 0.6} y1={y + h * k} y2={y + h * k} /><circle cx={x + w * 0.8} cy={y + h * k} r={Math.max(1.5, w * 0.04)} fill={color} /></g>)}</g>;
    case 'laptop': return <g><rect {...P} x={x + w * 0.12} y={y + h * 0.15} width={w * 0.76} height={h * 0.55} rx={r * 0.5} /><path {...P} d={`M${x} ${y + h * 0.85} L${x + w} ${y + h * 0.85} L${x + w * 0.88} ${y + h * 0.7} L${x + w * 0.12} ${y + h * 0.7} Z`} /></g>;
    case 'user': return <g><circle {...P} cx={cx} cy={y + h * 0.3} r={Math.min(w, h) * 0.2} /><path {...P} d={`M${x + w * 0.15} ${y + h} Q${cx} ${y + h * 0.45} ${x + w * 0.85} ${y + h}`} /></g>;
    case 'store': return <g><rect {...P} x={x + w * 0.08} y={y + h * 0.35} width={w * 0.84} height={h * 0.65} /><path {...P} d={`M${x} ${y + h * 0.35} L${x + w * 0.12} ${y + h * 0.08} L${x + w * 0.88} ${y + h * 0.08} L${x + w} ${y + h * 0.35} Z`} /><rect {...P} x={cx - w * 0.12} y={y + h * 0.6} width={w * 0.24} height={h * 0.4} /></g>;
    case 'truck': return <g><rect {...P} x={x} y={y + h * 0.25} width={w * 0.62} height={h * 0.5} /><path {...P} d={`M${x + w * 0.62} ${y + h * 0.4} L${x + w * 0.85} ${y + h * 0.4} L${x + w} ${y + h * 0.6} L${x + w} ${y + h * 0.75} L${x + w * 0.62} ${y + h * 0.75}`} /><circle {...P} cx={x + w * 0.22} cy={y + h * 0.82} r={h * 0.1} /><circle {...P} cx={x + w * 0.8} cy={y + h * 0.82} r={h * 0.1} /></g>;
    case 'bike': return <g><circle {...P} cx={x + w * 0.22} cy={y + h * 0.7} r={h * 0.22} /><circle {...P} cx={x + w * 0.78} cy={y + h * 0.7} r={h * 0.22} /><path {...P} d={`M${x + w * 0.22} ${y + h * 0.7} L${x + w * 0.45} ${y + h * 0.35} L${x + w * 0.78} ${y + h * 0.7} M${x + w * 0.45} ${y + h * 0.35} L${x + w * 0.62} ${y + h * 0.3}`} /><rect {...P} x={x + w * 0.05} y={y + h * 0.15} width={w * 0.3} height={h * 0.28} /></g>;
    case 'db': return <g><ellipse {...P} cx={cx} cy={y + h * 0.15} rx={w * 0.42} ry={h * 0.13} /><path {...P} d={`M${cx - w * 0.42} ${y + h * 0.15} L${cx - w * 0.42} ${y + h * 0.85} A${w * 0.42} ${h * 0.13} 0 0 0 ${cx + w * 0.42} ${y + h * 0.85} L${cx + w * 0.42} ${y + h * 0.15}`} /><path {...P} d={`M${cx - w * 0.42} ${y + h * 0.5} A${w * 0.42} ${h * 0.13} 0 0 0 ${cx + w * 0.42} ${y + h * 0.5}`} /></g>;
    case 'camera': return <g><rect {...P} x={x} y={y + h * 0.3} width={w * 0.7} height={h * 0.45} rx={r * 0.5} /><path {...P} d={`M${x + w * 0.7} ${y + h * 0.42} L${x + w} ${y + h * 0.3} L${x + w} ${y + h * 0.75} L${x + w * 0.7} ${y + h * 0.63}`} /></g>;
    case 'card': return <g><rect {...P} x={x} y={y + h * 0.2} width={w} height={h * 0.6} rx={r} /><line {...P} x1={x} x2={x + w} y1={y + h * 0.38} y2={y + h * 0.38} /></g>;
    case 'house': return <g><path {...P} d={`M${x} ${y + h * 0.45} L${cx} ${y} L${x + w} ${y + h * 0.45}`} /><rect {...P} x={x + w * 0.12} y={y + h * 0.42} width={w * 0.76} height={h * 0.58} /></g>;
    case 'bank': return <g><path {...P} d={`M${x} ${y + h * 0.3} L${cx} ${y} L${x + w} ${y + h * 0.3} Z`} />{[0.2, 0.43, 0.66].map((k) => <line key={k} {...P} x1={x + w * (k + 0.07)} x2={x + w * (k + 0.07)} y1={y + h * 0.38} y2={y + h * 0.88} />)}<line {...P} x1={x} x2={x + w} y1={y + h * 0.95} y2={y + h * 0.95} /></g>;
    case 'coin': return <g><circle {...P} cx={cx} cy={cy} r={Math.min(w, h) * 0.45} /><text x={cx} y={cy + Math.min(w, h) * 0.17} textAnchor="middle" fontSize={Math.min(w, h) * 0.5} fill={color} fontFamily={SANS} fontWeight={800}>₹</text></g>;
    case 'cloud': return <path {...P} d={`M${x + w * 0.25} ${y + h * 0.8} A${w * 0.2} ${w * 0.2} 0 0 1 ${x + w * 0.3} ${y + h * 0.42} A${w * 0.25} ${w * 0.25} 0 0 1 ${x + w * 0.72} ${y + h * 0.4} A${w * 0.2} ${w * 0.2} 0 0 1 ${x + w * 0.78} ${y + h * 0.8} Z`} />;
    case 'chip': return <g><rect {...P} x={x + w * 0.2} y={y + h * 0.2} width={w * 0.6} height={h * 0.6} rx={r * 0.4} />{[0.35, 0.5, 0.65].map((k) => <g key={k}><line {...P} x1={x + w * k} x2={x + w * k} y1={y + h * 0.05} y2={y + h * 0.2} /><line {...P} x1={x + w * k} x2={x + w * k} y1={y + h * 0.8} y2={y + h * 0.95} /></g>)}</g>;
    case 'doc': return <g><path {...P} d={`M${x + w * 0.15} ${y} L${x + w * 0.65} ${y} L${x + w * 0.85} ${y + h * 0.2} L${x + w * 0.85} ${y + h} L${x + w * 0.15} ${y + h} Z`} />{[0.4, 0.55, 0.7].map((k) => <line key={k} {...P} x1={x + w * 0.3} x2={x + w * 0.7} y1={y + h * k} y2={y + h * k} />)}</g>;
    default: return <rect {...P} x={x} y={y} width={w} height={h} rx={r} />;
  }
}

const Box = ({ x, y, w, h, label, sub, lit, u, appear = 1 }) => (
  <g opacity={appear}>
    <rect x={x} y={y} width={w} height={h} rx={10 * u} fill={lit ? 'rgba(247,201,72,0.10)' : C.panel} stroke={lit ? C.accent : C.line} strokeWidth={2.5 * u}
      style={{ filter: lit ? `drop-shadow(0 0 ${10 * u}px rgba(247,201,72,0.5))` : undefined }} />
    <text x={x + w / 2} y={y + h * (sub ? 0.45 : 0.6)} textAnchor="middle" fill={lit ? C.accent : C.text} fontFamily={MONO} fontWeight={700} fontSize={Math.min(28 * u, h * 0.3)}>{String(label).toUpperCase()}</text>
    {sub && <text x={x + w / 2} y={y + h * 0.75} textAnchor="middle" fill={C.muted} fontFamily={MONO} fontSize={Math.min(20 * u, h * 0.2)}>{String(sub).toUpperCase()}</text>}
  </g>
);

// ── beats ────────────────────────────────────────────────────────────────────
function QuestionBeat({ b, sw, sh, u, start }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = useSpring(start);
  const chars = Math.floor(((frame - start) / fps) * 38);
  const shown = b.text.slice(0, Math.max(0, chars));
  const w = Math.min(sw, 820 * u), fs = 50 * u;
  return (
    <div style={{ width: w, padding: `${28 * u}px ${34 * u}px`, borderRadius: 18 * u, border: `2px solid ${C.line}`, background: C.panel, opacity: p, transform: `translateY(${(1 - p) * 30}px)`, boxShadow: `0 ${20 * u}px ${60 * u}px rgba(0,0,0,.35)` }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontFamily: MONO, color: C.accent, fontSize: 22 * u, letterSpacing: 2 }}>
        <span>{b.asker}:</span><span style={{ color: C.muted }}><span style={{ color: C.accent, opacity: frame % 30 < 15 ? 1 : 0.3 }}>●</span> 00:{String(3 + Math.floor((frame - start) / fps)).padStart(2, '0')}</span>
      </div>
      <div style={{ marginTop: 16 * u, fontFamily: SANS, fontWeight: 800, fontSize: fs, lineHeight: 1.15, color: C.text }}>
        {hi(shown, b.qhighlight)}<span style={{ color: C.accent, opacity: frame % 20 < 10 ? 1 : 0 }}>|</span>
      </div>
    </div>
  );
}

function StatBeat({ b, u, start }) {
  const p = useSpring(start, { damping: 12, stiffness: 120 });
  const num = parseFloat(String(b.value).replace(/,/g, ''));
  const isNum = Number.isFinite(num);
  const shown = isNum ? (num * Math.min(1, p)).toFixed(String(b.value).includes('.') ? 1 : 0) : b.value;
  return (
    <div style={{ textAlign: 'center', opacity: Math.min(1, p * 1.4), transform: `scale(${0.85 + 0.15 * p})` }}>
      <div style={{ fontFamily: SANS, fontWeight: 900, lineHeight: 1 }}>
        <span style={{ color: C.accent, fontSize: 210 * u, letterSpacing: -4 }}>{shown}</span>
        {b.unit && <span style={{ color: C.text, fontSize: 92 * u, marginLeft: 18 * u }}>{b.unit}</span>}
      </div>
      {b.label && <div style={{ marginTop: 18 * u, fontFamily: MONO, color: C.muted, fontSize: 28 * u, letterSpacing: 1 }}><span style={{ color: C.accent }}>●</span> {b.label.toUpperCase()}</div>}
    </div>
  );
}

function DevicesBeat({ b, sw, sh, u, start, buildStart, portrait }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const cols = b.count <= 4 ? b.count : b.count <= 8 ? 4 : b.count <= 12 ? 6 : 8;
  const rows = Math.ceil(b.count / cols);
  // portrait: the tag sits ABOVE the grid so the devices can use the full width
  const tagTop = !!b.tag && portrait;
  const tagH = b.tag ? Math.min(sh * 0.3, 170 * u) : 0;
  const gw = sw * (b.tag && !portrait ? 0.62 : 0.96), gh = sh * (tagTop ? 0.86 : 0.86) - (tagTop ? tagH + 30 * u : 0);
  const cell = Math.min(gw / cols, gh / rows);
  const dw = cell * 0.8, dh = cell * 0.8;
  const ox = b.tag && !portrait ? 0 : (sw - cols * cell) / 2;
  const oy = (tagTop ? tagH + 30 * u : 0) + (gh - rows * cell) / 2;
  const litP = spring({ frame: frame - buildStart, fps, config: { damping: 20, stiffness: 120 } });
  return (
    <svg width={sw} height={sh} style={{ overflow: 'visible' }}>
      {Array.from({ length: b.count }, (_, i) => {
        const c = i % cols, r = Math.floor(i / cols);
        const p = spring({ frame: frame - start - i * 2, fps, config: { damping: 14, stiffness: 160 } });
        const lit = i < b.lit;
        const col = lit ? C.accent : C.line;
        const x = ox + c * cell + (cell - dw) / 2, y = oy + r * cell + (cell - dh) / 2 + (1 - p) * 20;
        return <g key={i} opacity={Math.min(1, p * 1.5)} style={{ filter: lit ? `drop-shadow(0 0 ${6 * u}px rgba(247,201,72,${0.6 * litP}))` : undefined }}>
          <Glyph type={b.device} x={x} y={y} w={dw} h={dh} color={col} fill={lit ? 'rgba(247,201,72,0.12)' : 'rgba(10,32,66,0.6)'} sw={2.5 * u} />
        </g>;
      })}
      {b.tag && (() => {
        const tw = tagTop ? Math.min(sw * 0.6, 460 * u) : sw * 0.34, th = tagTop ? tagH : Math.min(sh * 0.36, 190 * u);
        const tx = tagTop ? (sw - tw) / 2 : sw * 0.66, ty = tagTop ? 0 : oy;
        const p = spring({ frame: frame - start - 10, fps, config: { damping: 16 } });
        return <g opacity={p}>
          <rect x={tx} y={ty} width={tw} height={th} rx={10 * u} fill={C.panel} stroke={C.accent} strokeWidth={2.5 * u} />
          <text x={tx + 18 * u} y={ty + th * 0.26} fill={C.muted} fontFamily={MONO} fontSize={20 * u}>{b.tag.title}</text>
          <text x={tx + 18 * u} y={ty + th * 0.58} fill={C.accent} fontFamily={MONO} fontWeight={800} fontSize={Math.min(44 * u, tw / Math.max(4, b.tag.value.length) * 1.5)}>{b.tag.value}</text>
          <text x={tx + 18 * u} y={ty + th * 0.84} fill={C.text} fontFamily={MONO} fontSize={20 * u}>{b.tag.sub}</text>
        </g>;
      })()}
      {b.label && <text x={sw / 2} y={sh * 0.93} textAnchor="middle" fill={C.muted} fontFamily={MONO} fontSize={24 * u} letterSpacing={2}>{b.label.toUpperCase()}</text>}
    </svg>
  );
}

function Dots({ x1, y1, x2, y2, n = 4, speed = 1, u, color = C.accent }) {
  const frame = useCurrentFrame();
  return Array.from({ length: n }, (_, k) => {
    const t = ((frame * 0.025 * speed + k / n) % 1);
    return <circle key={k} cx={x1 + (x2 - x1) * t} cy={y1 + (y2 - y1) * t} r={4.5 * u} fill={color} opacity={Math.sin(Math.PI * t)} />;
  });
}

function PipelineBeat({ b, sw, sh, u, start, portrait }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const n = b.steps.length;
  const vertical = portrait && n > 2;
  const bw = vertical ? Math.min(sw * 0.62, 520 * u) : Math.min((sw - (n - 1) * 60 * u) / n, 300 * u);
  const bh = vertical ? Math.min((sh * 0.9 - (n - 1) * 50 * u) / n, 130 * u) : 130 * u;
  const pos = b.steps.map((_, i) => vertical
    ? { x: (sw - bw) / 2, y: (sh - (n * bh + (n - 1) * 50 * u)) / 2 + i * (bh + 50 * u) }
    : { x: (sw - (n * bw + (n - 1) * 60 * u)) / 2 + i * (bw + 60 * u), y: (sh - bh) / 2 });
  return (
    <svg width={sw} height={sh} style={{ overflow: 'visible' }}>
      {pos.slice(1).map((p, i) => {
        const a = pos[i];
        const x1 = vertical ? a.x + bw / 2 : a.x + bw, y1 = vertical ? a.y + bh : a.y + bh / 2;
        const x2 = vertical ? p.x + bw / 2 : p.x, y2 = vertical ? p.y : p.y + bh / 2;
        const grow = Math.min(1, Math.max(0, (frame - start - (i + 1) * 8) / 12));
        return <g key={i}><line x1={x1} y1={y1} x2={x1 + (x2 - x1) * grow} y2={y1 + (y2 - y1) * grow} stroke={C.dim} strokeWidth={2 * u} strokeDasharray={`${6 * u} ${6 * u}`} />
          {grow >= 1 && i < b.active && <Dots x1={x1} y1={y1} x2={x2} y2={y2} u={u} />}</g>;
      })}
      {b.steps.map((s, i) => {
        const p = spring({ frame: frame - start - i * 8, fps, config: { damping: 15, stiffness: 140 } });
        return <Box key={i} {...pos[i]} w={bw} h={bh} label={s.label} sub={s.sub} lit={i === b.active} u={u} appear={Math.min(1, p * 1.4)} />;
      })}
    </svg>
  );
}

function FanoutBeat({ b, sw, sh, u, start }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const n = b.target.count;
  const cols = Math.min(8, Math.ceil(n / 2)), rows = Math.ceil(n / cols);
  const cell = Math.min((sw * 0.92) / cols, (sh * 0.38) / rows);
  const sw0 = Math.min(sw * 0.5, 380 * u), sh0 = 110 * u;
  const sx = (sw - sw0) / 2, sy = sh * 0.06;
  const ty0 = sh * 0.56;
  const targets = Array.from({ length: n }, (_, i) => ({ x: (sw - cols * cell) / 2 + (i % cols) * cell + cell * 0.11, y: ty0 + Math.floor(i / cols) * cell + cell * 0.11 }));
  const p = spring({ frame: frame - start, fps, config: { damping: 16 } });
  return (
    <svg width={sw} height={sh} style={{ overflow: 'visible' }}>
      <Box x={sx} y={sy} w={sw0} h={sh0} label={b.source.label} sub={b.source.sub} lit u={u} appear={p} />
      {p > 0.6 && targets.filter((_, i) => i % 2 === 0).map((t, i) => {
        const from = { x: sx + sw0 / 2, y: sy + sh0 }, to = { x: t.x + cell * 0.39, y: t.y };
        return b.mode === 'fanin'
          ? <Dots key={i} x1={to.x} y1={to.y} x2={from.x} y2={from.y} n={3} speed={1.2} u={u} color={C.bad} />
          : <Dots key={i} x1={from.x} y1={from.y} x2={to.x} y2={to.y} n={3} speed={1.2} u={u} />;
      })}
      {targets.map((t, i) => {
        const q = spring({ frame: frame - start - 6 - i, fps, config: { damping: 15 } });
        return <g key={i} opacity={q}><Glyph type={b.target.device} x={t.x} y={t.y} w={cell * 0.78} h={cell * 0.78} color={C.line} fill="rgba(10,32,66,0.6)" sw={2.2 * u} /></g>;
      })}
      {b.target.label && <text x={sw / 2} y={sh * 0.98} textAnchor="middle" fill={C.muted} fontFamily={MONO} fontSize={24 * u} letterSpacing={2}>{b.target.label.toUpperCase()}</text>}
    </svg>
  );
}

function BarsBeat({ b, sw, sh, u, start }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const lw = 170 * u, rowH = Math.min(sh / (b.rows.length + 1), 110 * u);
  const maxT = Math.max(...b.rows.map((r) => r.total));
  const seg = (sw - lw) / maxT;
  return (
    <svg width={sw} height={sh} style={{ overflow: 'visible' }}>
      {b.rows.map((r, ri) => {
        const y = (sh - b.rows.length * rowH) / 2 + ri * rowH + rowH * 0.2;
        return <g key={ri}>
          <text x={0} y={y + rowH * 0.42} fill={C.muted} fontFamily={MONO} fontSize={24 * u}>{r.label}</text>
          {Array.from({ length: r.total }, (_, k) => {
            const p = spring({ frame: frame - start - k * 2 - ri * 4, fps, config: { damping: 18 } });
            const filled = k < r.filled;
            return <rect key={k} x={lw + k * seg + 3 * u} y={y} width={seg - 8 * u} height={rowH * 0.5} rx={6 * u} opacity={p}
              fill={filled ? (k === r.filled - 1 ? 'rgba(247,201,72,0.35)' : 'rgba(127,166,217,0.22)') : 'none'} stroke={filled && k === r.filled - 1 ? C.accent : C.dim} strokeWidth={2 * u} strokeDasharray={filled ? '0' : `${5 * u} ${5 * u}`} />;
          })}
        </g>;
      })}
      {b.mark && (() => {
        const y0 = (sh - b.rows.length * rowH) / 2 + b.mark.row * rowH;
        const x = lw + b.mark.at * seg;
        return <g><line x1={x} x2={x} y1={y0} y2={y0 + rowH * 0.85} stroke={C.text} strokeWidth={3 * u} />
          {b.mark.label && <text x={x} y={y0 + rowH * 1.05} textAnchor="middle" fill={C.accent} fontFamily={MONO} fontSize={22 * u}>{b.mark.label}</text>}</g>;
      })()}
    </svg>
  );
}

function ChartBeat({ b, sw, sh, u, start }) {
  const frame = useCurrentFrame();
  const n = 26;
  const grow = Math.min(1, Math.max(0, (frame - start) / 40));
  const val = (i) => {
    const t = i / (n - 1);
    if (b.mode === 'spike') return 0.08 + (i === Math.round(n * 0.55) ? 0.92 : 0.04 * Math.sin(i * 1.7) + 0.05);
    if (b.mode === 'smooth') return 0.22 + 0.06 * Math.sin(i * 0.9);
    if (b.mode === 'rise') return 0.08 + 0.85 * t * t;
    return 0.3 + 0.03 * Math.sin(i * 1.3);
  };
  const W = sw, H = sh * 0.82, bw = (W - 40 * u) / n;
  const col = b.mode === 'spike' ? C.bad : b.mode === 'rise' ? C.accent : C.good;
  return (
    <svg width={sw} height={sh} style={{ overflow: 'visible' }}>
      <rect x={0} y={0} width={W} height={H} rx={12 * u} fill={C.panel} stroke={col} strokeWidth={2.5 * u} />
      <text x={20 * u} y={42 * u} fill={col} fontFamily={MONO} fontWeight={700} fontSize={26 * u}>{b.title}</text>
      {b.badge && <text x={W - 20 * u} y={42 * u} textAnchor="end" fill={col} fontFamily={MONO} fontWeight={800} fontSize={24 * u}>{b.badge}</text>}
      {Array.from({ length: Math.floor(n * grow) }, (_, i) => {
        const v = val(i), bh = (H - 90 * u) * v;
        return <rect key={i} x={20 * u + i * bw + 2 * u} y={H - 24 * u - bh} width={bw - 5 * u} height={bh} rx={3 * u} fill={col} opacity={0.85} />;
      })}
    </svg>
  );
}

function CompareBeat({ b, sw, sh, u, start }) {
  const p = useSpring(start);
  const q = useSpring(start + 10);
  const pw = (sw - 30 * u) / 2, ph = Math.min(sh * 0.7, 380 * u);
  const panel = (s, x, good, a) => (
    <g opacity={a} transform={`translate(0 ${(1 - a) * 20})`}>
      <rect x={x} y={(sh - ph) / 2} width={pw} height={ph} rx={14 * u} fill={C.panel} stroke={good ? C.good : C.bad} strokeWidth={3 * u} />
      <text x={x + pw / 2} y={(sh - ph) / 2 + 60 * u} textAnchor="middle" fill={good ? C.good : C.bad} fontFamily={SANS} fontWeight={800} fontSize={Math.min(42 * u, pw / Math.max(6, s.title.length + 2) * 1.8)}>{good ? '✓ ' : '✕ '}{s.title}</text>
      {s.lines.map((l, i) => <text key={i} x={x + pw / 2} y={(sh - ph) / 2 + (130 + i * 60) * u} textAnchor="middle" fill={C.text} fontFamily={MONO} fontSize={Math.min(30 * u, pw / Math.max(8, l.length) * 1.7)}>{l}</text>)}
    </g>
  );
  return (
    <svg width={sw} height={sh} style={{ overflow: 'visible' }}>
      {panel(b.left, 0, b.winner === 'left', p)}
      {panel(b.right, pw + 30 * u, b.winner === 'right', q)}
    </svg>
  );
}

function ChecklistBeat({ b, sw, u, start, buildStart }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return (
    <div style={{ width: Math.min(sw, 860 * u), fontFamily: SANS }}>
      {b.title && <div style={{ display: 'flex', alignItems: 'center', gap: 16 * u, marginBottom: 26 * u }}>
        <span style={{ fontSize: 50 * u, fontWeight: 900, color: C.text }}>{b.title}</span>
      </div>}
      {b.items.map((it, i) => {
        const on = i < b.checked;
        const p = spring({ frame: frame - (on && i === b.checked - 1 ? buildStart : start) - i * 6, fps, config: { damping: 14, stiffness: 150 } });
        return <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 20 * u, marginBottom: 22 * u, opacity: Math.min(1, 0.35 + p) }}>
          <span style={{ width: 52 * u, height: 52 * u, borderRadius: '50%', flexShrink: 0, display: 'grid', placeItems: 'center', border: `3px solid ${on ? C.good : C.dim}`, background: on ? 'rgba(61,220,151,0.15)' : 'transparent', color: C.good, fontSize: 32 * u, fontWeight: 900, transform: `scale(${on ? 0.8 + 0.2 * p : 1})` }}>{on ? '✓' : i + 1}</span>
          <span style={{ fontSize: 40 * u, fontWeight: 800, color: on ? C.text : C.muted }}>{it}</span>
        </div>;
      })}
    </div>
  );
}

function TermBeat({ b, u, start }) {
  const p = useSpring(start, { damping: 13, stiffness: 150 });
  return (
    <div style={{ textAlign: 'center', opacity: Math.min(1, p * 1.4), transform: `scale(${0.8 + 0.2 * p})` }}>
      <div style={{ display: 'inline-block', padding: `${18 * u}px ${40 * u}px`, borderRadius: 16 * u, border: `3px solid ${C.accent}`, background: 'rgba(247,201,72,0.10)', fontFamily: SANS, fontWeight: 900, fontSize: 70 * u, color: C.text, boxShadow: `0 0 ${30 * u}px rgba(247,201,72,0.25)` }}>{b.term}</div>
      {b.definition && <div style={{ marginTop: 22 * u, fontFamily: MONO, color: C.muted, fontSize: 28 * u, maxWidth: 820 * u }}>{b.definition}</div>}
    </div>
  );
}

const BEAT = { ...SCIENCE_BEATS, question: QuestionBeat, stat: StatBeat, devices: DevicesBeat, pipeline: PipelineBeat, fanout: FanoutBeat, bars: BarsBeat, chart: ChartBeat, compare: CompareBeat, checklist: ChecklistBeat, term: TermBeat };

// ── frame furniture ─────────────────────────────────────────────────────────
function Journey({ steps, active, x, y, w, u }) {
  if (!steps?.length) return null;
  const gap = w / Math.max(1, steps.length - 1);
  return (
    <svg width={w + 40 * u} height={70 * u} style={{ position: 'absolute', left: x - 20 * u, top: y, overflow: 'visible' }}>
      <line x1={20 * u} x2={20 * u + w} y1={14 * u} y2={14 * u} stroke={C.dim} strokeWidth={2 * u} />
      <line x1={20 * u} x2={20 * u + gap * active} y1={14 * u} y2={14 * u} stroke={C.accent} strokeWidth={3 * u} />
      {steps.map((s, i) => (
        <g key={i}>
          <circle cx={20 * u + gap * i} cy={14 * u} r={(i === active ? 12 : 8) * u} fill={i <= active ? C.accent : C.bg1} stroke={i <= active ? C.accent : C.line} strokeWidth={2.5 * u} />
          <text x={20 * u + gap * i} y={56 * u} textAnchor={i === 0 ? 'start' : i === steps.length - 1 ? 'end' : 'middle'} dx={i === 0 ? -12 * u : i === steps.length - 1 ? 12 * u : 0}
            fill={i === active ? C.accent : C.muted} fontFamily={MONO} fontWeight={i === active ? 700 : 400} fontSize={22 * u} letterSpacing={1}>{s}</text>
        </g>
      ))}
    </svg>
  );
}

function Phrase({ beat, start, u, width }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  if (!beat?.phrase) return null;
  const p = spring({ frame: frame - start, fps, config: { damping: 16, stiffness: 150 } });
  const swipe = spring({ frame: frame - start - 8, fps, config: { damping: 20, stiffness: 90 } });
  const hl = String(beat.highlight || '').toLowerCase();
  return (
    <div style={{ width, textAlign: 'center', opacity: Math.min(1, p * 1.4), transform: `translateY(${(1 - p) * 18}px)` }}>
      {beat.kicker && <div style={{ fontFamily: MONO, color: C.accent, fontSize: 24 * u, letterSpacing: 3, marginBottom: 10 * u }}>{beat.kicker}</div>}
      <div style={{ fontFamily: SANS, fontWeight: 800, fontSize: 54 * u, color: C.text, lineHeight: 1.15 }}>
        {beat.phrase.split(/(\s+)/).map((w, i) => {
          const isHl = hl && w.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '') === hl.replace(/[^\p{L}\p{N}]/gu, '');
          return isHl ? <span key={i} style={{ color: C.accent, position: 'relative', display: 'inline-block' }}>{w}
            <span style={{ position: 'absolute', left: 0, right: 0, bottom: -6 * u, height: 4 * u, background: C.accent, transformOrigin: 'left', transform: `scaleX(${swipe})` }} /></span> : <span key={i}>{w}</span>;
        })}
      </div>
    </div>
  );
}

// ── composition ─────────────────────────────────────────────────────────────
export const BlueprintExplainer = ({ timeline }) => {
  const frame = useCurrentFrame();
  const { fps, width, height } = useVideoConfig();
  const nowMs = (frame / fps) * 1000;
  const portrait = height > width;
  const u = Math.min(width / 1080, height / (portrait ? 1920 : 1080));
  const bp = timeline.blueprint || {};
  const bg = useMemo(() => paintBg(width, height), [width, height]);

  // flatten sentences, each with its chapter
  const flat = useMemo(() => timeline.scenes.flatMap((sc, ci) => sc.sentences.map((s) => ({ ...s, ci }))), [timeline]);
  let idx = 0;
  for (let i = 0; i < flat.length; i++) if (flat[i].startMs <= nowMs) idx = i;
  const cur = flat[idx];
  // a "same": true beat keeps building the visual of the sentence it continues
  let anchor = idx;
  while (anchor > 0 && flat[anchor].beat?.same && flat[anchor - 1].beat?.type === flat[anchor].beat?.type) anchor--;
  const msToFrame = (ms) => Math.round((ms / 1000) * fps);
  const beatStart = msToFrame(flat[anchor].startMs), buildStart = msToFrame(cur.startMs);
  const scene = timeline.scenes[cur.ci];

  // previous beat fades out for 300 ms after a change
  let prev = null;
  if (anchor > 0 && nowMs - flat[anchor].startMs < 320) {
    let pa = anchor - 1;
    while (pa > 0 && flat[pa].beat?.same && flat[pa - 1].beat?.type === flat[pa].beat?.type) pa--;
    prev = { beat: flat[anchor - 1].beat, start: msToFrame(flat[pa].startMs), build: msToFrame(flat[anchor - 1].startMs), out: 1 - (nowMs - flat[anchor].startMs) / 320 };
  }

  const sw = Math.min(width * 0.9, 1000 * u * (portrait ? 1 : 1.5));
  const stageTop = portrait ? height * 0.2 : height * 0.2;
  const sh = portrait ? height * 0.46 : height * 0.56;
  const renderBeat = (beat, start, build, style) => {
    const B = BEAT[beat?.type] || TermBeat;
    return (
      <div style={{ position: 'absolute', left: (width - sw) / 2, top: stageTop, width: sw, height: sh, display: 'flex', alignItems: 'center', justifyContent: 'center', ...style }}>
        <B b={beat} sw={sw} sh={sh} u={u} start={start} buildStart={build} portrait={portrait} />
      </div>
    );
  };
  const chapterNo = String(cur.ci).padStart(2, '0');
  const endFade = interpolate(nowMs, [timeline.totalMs - 700, timeline.totalMs], [1, 0], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const pad = width * 0.055;

  return (
    <AbsoluteFill style={{ background: C.bg1, fontFamily: SANS, color: C.text, opacity: endFade }}>
      {bg && <img src={bg} alt="" style={{ position: 'absolute', inset: 0, width, height }} />}
      {/* corner furniture */}
      <div style={{ position: 'absolute', left: pad, right: pad, top: height * (portrait ? 0.035 : 0.04), display: 'flex', justifyContent: 'space-between', fontFamily: MONO, fontSize: 22 * u, color: C.muted, letterSpacing: 2 }}>
        <span>{(bp.series || 'EXPLAINED').toUpperCase()} · <span style={{ color: C.accent }}>{String(bp.number || 1).padStart(3, '0')}</span></span>
        <span>{bp.brand || ''}</span>
      </div>
      <div style={{ position: 'absolute', left: pad, top: height * (portrait ? 0.07 : 0.1), display: 'flex', alignItems: 'center', gap: 14 * u, fontFamily: MONO, color: C.accent, fontSize: 24 * u, letterSpacing: 2 }}>
        <span style={{ width: 40 * u, height: 2 * u, background: C.accent, display: 'inline-block' }} />{chapterNo} / {scene.heading}
      </div>
      {scene.role !== 'question' && scene.role !== 'answer' && <Journey steps={bp.journey} active={scene.milestone ?? 0} x={pad + 20 * u} y={height * (portrait ? 0.105 : 0.15)} w={width - 2 * pad - 40 * u} u={u} />}

      {prev && renderBeat(prev.beat, prev.start, prev.build, { opacity: Math.max(0, prev.out), transform: `scale(${0.97 + 0.03 * prev.out})` })}
      {renderBeat(cur.beat, beatStart, buildStart, {})}

      <div style={{ position: 'absolute', left: (width - sw) / 2, top: stageTop + sh + height * 0.03, width: sw, display: 'flex', justifyContent: 'center' }}>
        <Phrase key={idx} beat={cur.beat} start={buildStart} u={u} width={sw} />
      </div>
      <div style={{ position: 'absolute', left: 0, bottom: 0, height: 6 * u, width: `${Math.min(100, (nowMs / timeline.totalMs) * 100)}%`, background: C.accent }} />
    </AbsoluteFill>
  );
};
