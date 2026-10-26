import React from 'react';
import { useTheme } from '../themes.jsx';
import { useCue, valueAt, sinceMs, popSpring } from '../hooks/cue.js';
import { fitFont, balanceLines, hexA, glow } from './common.jsx';

export const Callout = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const text = String(valueAt(cue, 'text', props.text));
  const tone = valueAt(cue, 'tone', props.tone || 'info');
  const color = { info: t.accent, warn: t.warn, good: t.good, bad: t.bad }[tone] || t.accent;
  const pop = popSpring(cue, Math.min(sinceMs(cue, 'text'), sinceMs(cue, 'tone')));
  const lines = text.length > 18 ? balanceLines(text, 2) : [text];
  const fs = fitFont(text, w * 0.84, h * 0.72, { lines: lines.length, charW: 0.55, max: h * 0.5 });
  return (
    <div style={{ width: w, height: h, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{
        transform: `scale(${0.88 + 0.12 * pop})`, opacity: Math.min(1, pop * 1.4),
        padding: `${fs * 0.28}px ${fs * 0.6}px`, borderRadius: fs * 0.45, textAlign: 'center',
        border: `${t.stroke}px solid ${color}`, background: hexA(color, t.dark ? 0.16 : 0.1), color: t.text,
        fontFamily: t.font, fontWeight: 800, fontSize: fs, lineHeight: 1.12, boxShadow: glow(t, color, 26),
      }}>
        {lines.map((l, i) => <div key={i}>{l}</div>)}
      </div>
    </div>
  );
};
