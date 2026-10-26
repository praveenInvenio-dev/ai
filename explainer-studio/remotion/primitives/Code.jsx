import React from 'react';
import { useTheme } from '../themes.jsx';
import { useCue, valueAt } from '../hooks/cue.js';
import { hexA } from './common.jsx';

const KW = new Set(['public', 'private', 'protected', 'class', 'int', 'long', 'void', 'static', 'final', 'synchronized', 'return', 'if', 'else', 'for', 'while', 'new', 'def', 'import', 'from', 'let', 'const', 'var', 'function', 'this', 'try', 'catch', 'volatile', 'boolean', 'lock', 'unlock', 'await', 'async', 'self', 'in', 'not', 'and', 'or']);
const tokenize = (s) => s.match(/\s+|\/\/.*|#.*|"[^"]*"|'[^']*'|\w+|./g) || [];

export const Code = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const lines = props.lines;
  const active = Number(valueAt(cue, 'active', -1));
  const maxLen = Math.max(...lines.map((l) => l.length), 8);
  const fs = Math.max(12, Math.min((h * 0.8) / (lines.length * 1.5), (w * 0.88) / (maxLen * 0.62), 64));
  let budget = Math.floor(Math.max(0, cue.ageMs) / 1000 * 80); // 80 chars/s typing
  return (
    <div style={{ width: w, height: h, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ background: t.code.bg, border: `${t.stroke}px solid ${t.panelBorder}`, borderRadius: fs * 0.7, padding: `${fs * 0.7}px ${fs * 0.9}px`, fontFamily: t.mono, fontSize: fs, lineHeight: 1.5, color: t.code.fg, minWidth: w * 0.6, boxShadow: '0 12px 40px rgba(0,0,0,0.25)' }}>
        <div style={{ display: 'flex', gap: fs * 0.35, marginBottom: fs * 0.5 }}>
          {[t.bad, t.warn, t.good].map((c, i) => <div key={i} style={{ width: fs * 0.5, height: fs * 0.5, borderRadius: '50%', background: c }} />)}
        </div>
        {lines.map((line, i) => {
          const shown = line.slice(0, Math.max(0, budget));
          budget -= line.length;
          return (
            <div key={i} style={{ whiteSpace: 'pre', minHeight: fs * 1.5, borderRadius: fs * 0.25, padding: `0 ${fs * 0.3}px`, margin: `0 -${fs * 0.3}px`, background: i === active ? hexA(t.accent, 0.28) : 'transparent', borderLeft: `${fs * 0.15}px solid ${i === active ? t.accent2 : 'transparent'}` }}>
              {tokenize(shown).map((tok, k) => {
                let c = t.code.fg;
                if (/^(\/\/|#)/.test(tok)) c = t.code.com;
                else if (/^["']/.test(tok)) c = t.code.str;
                else if (/^\d+$/.test(tok)) c = t.code.num;
                else if (KW.has(tok)) c = t.code.kw;
                return <span key={k} style={{ color: c }}>{tok}</span>;
              })}
            </div>
          );
        })}
      </div>
    </div>
  );
};
