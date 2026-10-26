import React from 'react';
import { useTheme } from '../themes.jsx';
import { useCue, valueAt, sinceMs, popSpring } from '../hooks/cue.js';
import { hexA, glow } from './common.jsx';

export const Lock = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const state = String(valueAt(cue, 'state', 'free'));
  const holder = String(valueAt(cue, 'holder', ''));
  const queue = String(valueAt(cue, 'queue', '')).split(',').map((s) => s.trim()).filter(Boolean).slice(0, 4);
  const color = { free: t.good, held: t.bad, waiting: t.warn }[state] || t.muted;
  const pop = popSpring(cue, sinceMs(cue, 'state'), { damping: 8, stiffness: 200 });
  const s = Math.min(h * 0.5, w * 0.22);
  const open = state === 'free';
  return (
    <div style={{ width: w, height: h, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: s * 0.35 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: s * 0.3, padding: `${s * 0.25}px ${s * 0.5}px`, borderRadius: s * 0.5, border: `${t.stroke}px solid ${color}`, background: hexA(color, 0.14), boxShadow: glow(t, color, 24), transform: `scale(${0.9 + 0.1 * pop})` }}>
        <svg width={s} height={s} viewBox="0 0 24 24">
          <path d={open ? 'M7 11V7a5 5 0 0 1 9.5-2.2' : 'M7 11V7a5 5 0 0 1 10 0v4'} fill="none" stroke={color} strokeWidth="2.4" strokeLinecap="round" />
          <rect x="4.5" y="11" width="15" height="10" rx="2.5" fill={color} />
          <circle cx="12" cy="16" r="1.6" fill={t.bg[0]} />
        </svg>
        <div style={{ fontFamily: t.mono, color: t.text }}>
          <div style={{ fontSize: s * 0.42, fontWeight: 800 }}>{props.name}</div>
          <div style={{ fontSize: s * 0.3, color, fontWeight: 700 }}>{state}{holder ? ` · ${holder}` : ''}</div>
        </div>
      </div>
      {queue.length > 0 && (
        <div style={{ display: 'flex', gap: s * 0.12, alignItems: 'center' }}>
          <span style={{ fontFamily: t.font, color: t.muted, fontSize: s * 0.3 }}>waiting</span>
          {queue.map((q, i) => <span key={i} style={{ fontFamily: t.mono, fontSize: s * 0.3, fontWeight: 700, color: t.text, padding: `${s * 0.06}px ${s * 0.2}px`, border: `2px dashed ${t.warn}`, borderRadius: s * 0.3 }}>{q}</span>)}
        </div>
      )}
    </div>
  );
};
