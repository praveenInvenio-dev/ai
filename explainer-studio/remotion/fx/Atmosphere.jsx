import React, { useMemo } from 'react';
import { AbsoluteFill, useCurrentFrame, useVideoConfig } from 'remotion';
import { noise2D, noise3D } from '@remotion/noise';
import { useTheme } from '../themes.jsx';
import { hexA } from '../primitives/common.jsx';

/**
 * Living background, still a pure function of the frame (deterministic renders).
 *  - three soft "aurora" light pools that wander on Perlin noise (@remotion/noise)
 *  - slow perspective grid
 *  - floating dust particles (noise3D → organic drift, no two alike)
 *  - static film grain: breaks up 8-bit banding in dark gradients so the H.264 encode stays clean
 * `energy` (0..1) brightens everything a little during transitions / emphasis.
 */
export const Atmosphere = ({ energy = 0, seed = 0 }) => {
  const t = useTheme();
  const frame = useCurrentFrame();
  const { width, height, fps } = useVideoConfig();
  const sec = frame / fps;
  const minDim = Math.min(width, height);

  // Each scene gets its own arrangement + colour order of the light pools (eased in by the scene transition).
  const LAYOUTS = [[[0.18, 0.12], [0.86, 0.78], [0.72, 0.18]], [[0.82, 0.1], [0.14, 0.7], [0.5, 0.95]], [[0.5, 0.05], [0.9, 0.5], [0.1, 0.9]], [[0.1, 0.4], [0.7, 0.15], [0.85, 0.85]]];
  const PALETTES = [[t.accent, t.accent2, t.info], [t.accent2, t.info, t.accent], [t.info, t.accent, t.accent2], [t.accent, t.good, t.accent2]];
  const lay = LAYOUTS[seed % LAYOUTS.length], pal = PALETTES[seed % PALETTES.length];
  const blobs = [
    { c: pal[0], seed: 'a', r: 0.62, base: lay[0] },
    { c: pal[1], seed: 'b', r: 0.55, base: lay[1] },
    { c: pal[2], seed: 'c', r: 0.45, base: lay[2] },
  ];
  const particles = useMemo(
    () => Array.from({ length: 34 }, (_, i) => ({ i, x: (i * 0.6180339) % 1, y: (i * 0.4142135 + 0.13) % 1, s: 0.6 + ((i * 7) % 5) / 5, c: i % 3 === 0 ? 'accent2' : 'accent' })),
    [],
  );
  const size = Math.round(minDim / 14);
  const drift = (frame * 0.35) % size;
  const blobAlpha = (t.dark ? 0.22 : 0.10) + energy * 0.08;

  return (
    <AbsoluteFill style={{ background: `radial-gradient(120% 90% at 50% 0%, ${t.bg[0]} 0%, ${t.bg[1]} 100%)`, overflow: 'hidden' }}>
      {blobs.map((b) => {
        const x = (b.base[0] + 0.12 * noise2D(`${b.seed}x`, sec * 0.06, 0)) * width;
        const y = (b.base[1] + 0.12 * noise2D(`${b.seed}y`, 0, sec * 0.06)) * height;
        const r = b.r * minDim * (1 + 0.12 * noise2D(`${b.seed}r`, sec * 0.1, 1));
        return (
          <div key={b.seed} style={{ position: 'absolute', left: x - r, top: y - r, width: r * 2, height: r * 2, borderRadius: '50%', background: `radial-gradient(circle, ${hexA(b.c, blobAlpha)} 0%, ${hexA(b.c, 0)} 68%)` }} />
        );
      })}
      <svg width={width} height={height} style={{ position: 'absolute', inset: 0 }}>
        <defs>
          <pattern id="atm-grid" width={size} height={size} patternUnits="userSpaceOnUse" patternTransform={`translate(${drift} ${drift})`}>
            <path d={`M ${size} 0 L 0 0 0 ${size}`} fill="none" stroke={t.grid} strokeWidth="1.5" />
          </pattern>
          <radialGradient id="atm-gridfade" cx="50%" cy="45%" r="70%"><stop offset="0%" stopColor="#fff" stopOpacity="1" /><stop offset="100%" stopColor="#fff" stopOpacity="0.15" /></radialGradient>
          <mask id="atm-mask"><rect width={width} height={height} fill="url(#atm-gridfade)" /></mask>
          <radialGradient id="atm-vig" cx="50%" cy="45%" r="75%"><stop offset="58%" stopColor="#000" stopOpacity="0" /><stop offset="100%" stopColor="#000" stopOpacity={t.dark ? 0.5 : 0.08} /></radialGradient>
          <filter id="atm-grain" x="0" y="0" width="100%" height="100%">
            <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" seed="7" stitchTiles="stitch" />
            <feColorMatrix type="saturate" values="0" />
          </filter>
        </defs>
        <rect width={width} height={height} fill="url(#atm-grid)" mask="url(#atm-mask)" />
        {particles.map((p) => {
          const px = ((p.x + 0.04 * noise3D('px', p.i, 0, sec * 0.08) + sec * 0.004 * p.s) % 1) * width;
          const py = ((p.y - sec * 0.012 * p.s + 10) % 1) * height;
          const tw = 0.35 + 0.65 * (0.5 + 0.5 * noise3D('tw', p.i, sec * 0.5, 0));
          return <circle key={p.i} cx={px} cy={py} r={minDim * 0.0024 * p.s} fill={t[p.c]} opacity={(t.dark ? 0.55 : 0.3) * tw} />;
        })}
        <rect width={width} height={height} fill="url(#atm-vig)" />
        <rect width={width} height={height} filter="url(#atm-grain)" opacity={t.dark ? 0.045 : 0.03} style={{ mixBlendMode: 'overlay' }} />
      </svg>
    </AbsoluteFill>
  );
};

/**
 * Light sweep that crosses the frame when a scene changes: a soft diagonal band in the accent colours.
 * `p` 0..1 is the transition progress.
 */
export const SceneSweep = ({ p }) => {
  const t = useTheme();
  const { width, height } = useVideoConfig();
  if (p <= 0 || p >= 1) return null;
  const x = -0.6 * width + p * 2.2 * width;
  const a = Math.sin(Math.PI * p);
  return (
    <AbsoluteFill style={{ pointerEvents: 'none', overflow: 'hidden' }}>
      <div style={{ position: 'absolute', top: -height * 0.3, left: x, width: width * 0.35, height: height * 1.6, transform: 'rotate(14deg)', background: `linear-gradient(90deg, ${hexA(t.accent, 0)} 0%, ${hexA(t.accent, 0.16 * a)} 40%, ${hexA(t.accent2, 0.22 * a)} 55%, ${hexA(t.accent2, 0)} 100%)`, filter: 'blur(18px)' }} />
    </AbsoluteFill>
  );
};
