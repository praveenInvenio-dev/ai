import React from 'react';
import { spring } from 'remotion';
import { useTheme } from '../themes.jsx';
import { useCue, numAt } from '../hooks/cue.js';
import { fitFont, hexA } from './common.jsx';

/**
 * Kinetic typography: 1-3 short lines whose words slam in one by one; the key word gets the accent colour and a
 * marker swipe. For the punchline / definition beat ("Threads share memory", "Light bends, it doesn't slow").
 */
export const Kinetic = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const lines = props.lines;
  const reveal = numAt(cue, 'reveal', lines.length, { damping: 30, stiffness: 80 });
  const hl = String(props.highlight || '').toLowerCase().replace(/[^\p{L}\p{N}+#.-]/gu, '');
  const longest = lines.reduce((a, l) => (l.length > a.length ? l : a), '');
  const fs = fitFont(longest, w * 0.92, (h / lines.length) * 0.62, { max: h * 0.2, charW: 0.6 });
  const enterF = Math.round(((cue.nowMs - cue.ageMs) / 1000) * cue.fps);
  let k = 0;
  return (
    <div style={{ width: w, height: h, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: fs * 0.18, fontFamily: t.font }}>
      {lines.map((line, li) => {
        if (reveal <= li - 0.02) return <div key={li} style={{ height: fs * 1.1 }} />;
        return (
          <div key={li} style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'center', gap: `0 ${fs * 0.28}px`, lineHeight: 1.1 }}>
            {line.split(/\s+/).map((word, wi) => {
              const idx = k++;
              const p = spring({ frame: cue.frame - enterF - idx * 4 - li * 6, fps: cue.fps, config: { damping: 12, stiffness: 180 } });
              const isHl = hl && word.toLowerCase().replace(/[^\p{L}\p{N}+#.-]/gu, '') === hl;
              const swipe = isHl ? spring({ frame: cue.frame - enterF - idx * 4 - li * 6 - 8, fps: cue.fps, config: { damping: 20, stiffness: 90 } }) : 0;
              return (
                <span key={wi} style={{ position: 'relative', display: 'inline-block', fontSize: fs * (isHl ? 1.08 : 1), fontWeight: 900, color: isHl ? t.accent2 : t.text, letterSpacing: -0.5,
                  opacity: Math.min(1, p * 1.5), transform: `translateY(${(1 - p) * fs * 0.6}px) rotate(${(1 - p) * (wi % 2 ? 6 : -6)}deg) scale(${0.7 + 0.3 * p})` }}>
                  {isHl && <span style={{ position: 'absolute', left: -fs * 0.08, right: -fs * 0.08, bottom: fs * 0.08, height: fs * 0.32, background: hexA(t.accent2, 0.28), transformOrigin: 'left', transform: `scaleX(${swipe})`, borderRadius: fs * 0.08, zIndex: -1 }} />}
                  {word}
                </span>
              );
            })}
          </div>
        );
      })}
    </div>
  );
};
