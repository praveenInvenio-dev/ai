import React from 'react';
import { useTheme } from '../themes.jsx';
import { useCue, numAt, valueAt, clamp01 } from '../hooks/cue.js';
import { hexA } from './common.jsx';

export const Equation = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const lines = props.lines;
  const hasReveal = cue.events.some((e) => 'reveal' in e.set);
  const reveal = hasReveal ? numAt(cue, 'reveal', 1, { damping: 22, stiffness: 70 }) : Math.min(lines.length, cue.ageMs / 700 + 1);
  const focus = Number(valueAt(cue, 'focus', -1));
  const maxLen = Math.max(...lines.map((l) => l.length), 6);
  const fs = Math.max(14, Math.min((h * 0.78) / (lines.length * 1.45), (w * 0.88) / (maxLen * 0.6), 96));
  return (
    <div style={{ width: w, height: h, display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', gap: fs * 0.3 }}>
      {lines.map((l, i) => {
        const o = clamp01(reveal - i), on = i === focus;
        return (
          <div key={i} style={{ opacity: o, transform: `translateY(${(1 - o) * 18}px) scale(${on ? 1.06 : 1})`, fontFamily: '"Cambria Math","STIX Two Math","DejaVu Serif",Georgia,serif', fontStyle: 'italic', fontWeight: 700, fontSize: fs, color: on ? t.accent2 : t.text, padding: `${fs * 0.1}px ${fs * 0.5}px`, borderRadius: fs * 0.3, background: on ? hexA(t.accent2, 0.14) : 'transparent', border: `${on ? t.stroke : 0}px solid ${t.accent2}` }}>{l}</div>
        );
      })}
    </div>
  );
};
