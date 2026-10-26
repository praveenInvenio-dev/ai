import React from 'react';

export const fitFont = (text, w, h, { charW = 0.56, lines = 1, max = 400, min = 10 } = {}) => {
  const perLine = Math.max(1, Math.ceil(String(text).length / lines));
  return Math.max(min, Math.min(max, (h / lines) * 0.82, w / (perLine * charW)));
};

export const balanceLines = (text, lines = 2) => {
  const words = String(text).split(/\s+/);
  if (lines < 2 || words.length < 2) return [String(text)];
  let best = null;
  for (let i = 1; i < words.length; i++) {
    const a = words.slice(0, i).join(' '), b = words.slice(i).join(' ');
    const diff = Math.abs(a.length - b.length);
    if (!best || diff < best.diff) best = { diff, l: [a, b] };
  }
  return best.l;
};

export const hexA = (hex, a) => {
  const h = String(hex).replace('#', '');
  if (h.length !== 6) return hex;
  const n = parseInt(h, 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
};

export const glow = (theme, color, r = 18) => (theme.glow > 0 ? `0 0 ${r}px ${hexA(color, theme.glow)}` : 'none');

export const Box = ({ w, h, children, style }) => (
  <div style={{ width: w, height: h, position: 'relative', ...style }}>{children}</div>
);
