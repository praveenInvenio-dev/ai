import React from 'react';
import { useTheme } from '../themes.jsx';
import { useCue, valueAt, numAt, sinceMs, popSpring } from '../hooks/cue.js';
import { hexA, glow } from './common.jsx';

export const Variable = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const raw = valueAt(cue, 'value', props.value);
  const numeric = typeof props.value === 'number' || Number.isFinite(Number(raw));
  const shown = numeric ? (() => { const v = numAt(cue, 'value', Number(props.value) || 0); return Math.abs(v - Math.round(v)) < 0.02 ? String(Math.round(v)) : v.toFixed(1); })() : String(raw);
  const flash = String(valueAt(cue, 'flash', 'none'));
  const since = sinceMs(cue, 'flash');
  const color = flash === 'bad' ? t.bad : flash === 'good' ? t.good : t.accent;
  const amt = flash === 'none' ? 0 : Math.max(0, 1 - since / 1400);
  const pop = popSpring(cue, sinceMs(cue, 'value'), { damping: 9, stiffness: 190 });
  const fsVal = Math.min(h * 0.46, (w * 0.7) / Math.max(2, shown.length * 0.62));
  return (
    <div style={{ width: w, height: h, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ width: w * 0.78, height: h * 0.8, borderRadius: h * 0.12, border: `${t.stroke + amt * 3}px solid ${amt > 0 ? color : t.panelBorder}`, background: amt > 0 ? hexA(color, 0.12 + 0.2 * amt) : t.panel, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', boxShadow: amt > 0 ? glow(t, color, 40 * amt + 8) : 'none' }}>
        <div style={{ fontFamily: t.mono, fontSize: Math.min(h * 0.14, w * 0.06), color: t.muted, fontWeight: 700 }}>{props.label}</div>
        <div style={{ fontFamily: t.mono, fontWeight: 900, fontSize: fsVal, color: amt > 0 ? color : t.text, transform: `scale(${0.85 + 0.15 * pop})`, lineHeight: 1.05 }}>{shown}</div>
      </div>
    </div>
  );
};
