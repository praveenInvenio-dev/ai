import React, { useMemo } from 'react';
import { AbsoluteFill, useCurrentFrame, useVideoConfig } from 'remotion';
import { noise2D, noise3D } from '@remotion/noise';
import { useTheme } from '../themes.jsx';
import { hexA } from '../primitives/common.jsx';

/**
 * Living background, still a pure function of the frame (deterministic renders).
 * Light pools, grid, vignette and film grain are painted ONCE per scene into a half-resolution image; each frame only
 * moves that image (slow noise-driven drift + zoom) and the dust particles.
 * PERFORMANCE (measured, 1080x1920, Chromium software rendering): repainting full-frame gradients/patterns every
 * frame cost 1.3-4.4 s per frame (≈90 % of a 30 s render). Moving one pre-painted bitmap costs a few tens of ms.
 * `energy` (0..1) brightens a little during transitions; `seed` gives each scene its own light layout.
 */
const PAINT_SCALE = 0.5;
const cache = new Map();

function paint(t, seed, width, height) {
  const key = `${t.id}|${seed % 4}|${width}x${height}`;
  if (cache.has(key)) return cache.get(key);
  if (typeof document === 'undefined') return null;
  // painted 8 % larger than the frame so drift/zoom never shows an edge
  const W = Math.round(width * 1.08 * PAINT_SCALE), H = Math.round(height * 1.08 * PAINT_SCALE);
  const c = document.createElement('canvas');
  c.width = W; c.height = H;
  const g = c.getContext('2d');
  const base = g.createRadialGradient(W / 2, 0, 0, W / 2, 0, Math.hypot(W, H));
  base.addColorStop(0, t.bg[0]); base.addColorStop(1, t.bg[1]);
  g.fillStyle = base; g.fillRect(0, 0, W, H);
  const LAYOUTS = [[[0.18, 0.12], [0.86, 0.78], [0.72, 0.18]], [[0.82, 0.1], [0.14, 0.7], [0.5, 0.95]], [[0.5, 0.05], [0.9, 0.5], [0.1, 0.9]], [[0.1, 0.4], [0.7, 0.15], [0.85, 0.85]]];
  const PALETTES = [[t.accent, t.accent2, t.info], [t.accent2, t.info, t.accent], [t.info, t.accent, t.accent2], [t.accent, t.good, t.accent2]];
  const lay = LAYOUTS[seed % 4], pal = PALETTES[seed % 4];
  const minDim = Math.min(W, H);
  const alpha = t.dark ? 0.24 : 0.11;
  [0.62, 0.55, 0.45].forEach((rr, i) => {
    const x = lay[i][0] * W, y = lay[i][1] * H, r = rr * minDim;
    const gr = g.createRadialGradient(x, y, 0, x, y, r * 0.7);
    gr.addColorStop(0, hexA(pal[i], alpha)); gr.addColorStop(1, hexA(pal[i], 0));
    g.fillStyle = gr; g.fillRect(x - r, y - r, r * 2, r * 2);
  });
  const size = minDim / 14;
  g.strokeStyle = t.grid; g.lineWidth = 1; g.globalAlpha = 0.85;
  g.beginPath();
  for (let x = 0; x < W; x += size) { g.moveTo(Math.round(x) + 0.5, 0); g.lineTo(Math.round(x) + 0.5, H); }
  for (let y = 0; y < H; y += size) { g.moveTo(0, Math.round(y) + 0.5); g.lineTo(W, Math.round(y) + 0.5); }
  g.stroke(); g.globalAlpha = 1;
  const vg = g.createRadialGradient(W / 2, H * 0.45, minDim * 0.35, W / 2, H * 0.45, Math.hypot(W, H) * 0.6);
  vg.addColorStop(0, 'rgba(0,0,0,0)'); vg.addColorStop(1, `rgba(0,0,0,${t.dark ? 0.5 : 0.08})`);
  g.fillStyle = vg; g.fillRect(0, 0, W, H);
  // film grain (deterministic) - dithers dark gradients so the H.264 encode does not band
  const img = g.getImageData(0, 0, W, H);
  let n = 1234567;
  const amt = t.dark ? 9 : 6;
  for (let i = 0; i < img.data.length; i += 4) {
    n = (n * 1103515245 + 12345) & 0x7fffffff;
    const d = ((n & 255) / 255 - 0.5) * amt;
    img.data[i] += d; img.data[i + 1] += d; img.data[i + 2] += d;
  }
  g.putImageData(img, 0, 0);
  const url = c.toDataURL('image/png');
  cache.set(key, url);
  return url;
}

export const Atmosphere = ({ energy = 0, seed = 0 }) => {
  const t = useTheme();
  const frame = useCurrentFrame();
  const { width, height, fps } = useVideoConfig();
  const sec = frame / fps;
  const minDim = Math.min(width, height);
  const url = useMemo(() => paint(t, seed, width, height), [t, seed, width, height]);
  const particles = useMemo(
    () => Array.from({ length: 30 }, (_, i) => ({ i, x: (i * 0.6180339) % 1, y: (i * 0.4142135 + 0.13) % 1, s: 0.6 + ((i * 7) % 5) / 5, c: i % 3 === 0 ? 'accent2' : 'accent' })),
    [],
  );
  // slow wander of the whole painted background (the "living light")
  const dx = noise2D('bgx', sec * 0.05, seed) * width * 0.03;
  const dy = noise2D('bgy', seed, sec * 0.05) * height * 0.03;
  const z = 1 + 0.02 * (0.5 + 0.5 * noise2D('bgz', sec * 0.04, seed));
  return (
    <AbsoluteFill style={{ background: t.bg[1], overflow: 'hidden' }}>
      {url && (
        <img src={url} alt="" style={{ position: 'absolute', left: -width * 0.04, top: -height * 0.04, width: width * 1.08, height: height * 1.08,
          transform: `translate(${dx.toFixed(1)}px, ${dy.toFixed(1)}px) scale(${z.toFixed(4)})`, opacity: 1 }} />
      )}
      {energy > 0.01 && <AbsoluteFill style={{ background: hexA(t.accent, 0.06 * energy) }} />}
      {particles.map((p) => {
        const px = ((p.x + 0.04 * noise3D('px', p.i, 0, sec * 0.08) + sec * 0.004 * p.s) % 1) * width;
        const py = ((p.y - sec * 0.012 * p.s + 10) % 1) * height;
        const tw = 0.35 + 0.65 * (0.5 + 0.5 * noise3D('tw', p.i, sec * 0.5, 0));
        const r = minDim * 0.0024 * p.s;
        return <div key={p.i} style={{ position: 'absolute', left: px - r, top: py - r, width: r * 2, height: r * 2, borderRadius: '50%', background: t[p.c], opacity: (t.dark ? 0.55 : 0.3) * tw }} />;
      })}
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
