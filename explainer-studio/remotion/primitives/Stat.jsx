import React from 'react';
import { useTheme } from '../themes.jsx';
import { useCue, numAt, sinceMs, popSpring, clamp01 } from '../hooks/cue.js';
import { fitFont, hexA } from './common.jsx';

/** One big number that counts up/down on cue, with a label and a burst on every change ("4x faster", "86 %"). */
export const Stat = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const v = numAt(cue, 'value', props.from ?? 0, { damping: 26, stiffness: 40 });
  const changed = sinceMs(cue, 'value');
  const pop = popSpring(cue, changed);
  const burst = changed >= 0 ? clamp01(changed / 700) : 1;
  const text = `${props.prefix ?? ''}${v.toFixed(props.decimals ?? 0)}${props.suffix ?? ''}`;
  const hasRing = props.max != null;
  // Ring sized so the label always sits BELOW it, never across it.
  const ringR = Math.min(w * 0.4, h * 0.3);
  const cx = w / 2, cy = hasRing ? Math.min(h * 0.42, ringR * 1.15) : h * 0.46;
  let fs = fitFont(`${props.prefix ?? ''}${Number(props.max ?? props.from ?? 0).toFixed(props.decimals ?? 0)}${props.suffix ?? ''}` + 'M', w * 0.86, h * 0.5, { max: h * 0.48, charW: 0.62 });
  if (hasRing) fs = Math.min(fs, ringR * 0.9);
  const lfs = fitFont(props.label || 'M', w * 0.9, h * 0.12, { max: Math.max(fs * 0.3, h * 0.06) });
  const labelY = hasRing ? cy + ringR + lfs * 1.5 : cy + fs * 0.35 + lfs * 1.6;
  const frac = props.max ? clamp01(v / props.max) : null;
  return (
    <svg width={w} height={h} style={{ fontFamily: t.font, overflow: 'visible' }}>
      {frac !== null && (
        <g transform={`rotate(-90 ${cx} ${cy})`}>
          <circle cx={cx} cy={cy} r={ringR} fill="none" stroke={hexA(t.accent, 0.15)} strokeWidth={ringR * 0.07} />
          <circle cx={cx} cy={cy} r={ringR} fill="none" stroke={t.accent2} strokeWidth={ringR * 0.07} strokeLinecap="round"
            strokeDasharray={`${2 * Math.PI * ringR * frac} ${2 * Math.PI * ringR}`} />
        </g>
      )}
      {burst < 1 && Array.from({ length: 12 }, (_, i) => {
        const a = (i / 12) * Math.PI * 2, r1 = fs * (0.55 + burst * 0.5), r2 = r1 + fs * 0.25 * (1 - burst);
        return <line key={i} x1={cx + Math.cos(a) * r1} y1={cy + Math.sin(a) * r1 * 0.7} x2={cx + Math.cos(a) * r2} y2={cy + Math.sin(a) * r2 * 0.7} stroke={t.accent2} strokeWidth={4} strokeLinecap="round" opacity={1 - burst} />;
      })}
      <text x={cx} y={cy + fs * 0.35} textAnchor="middle" fontSize={fs} fontWeight={900} fill={t.text} letterSpacing={-1}
        transform={`translate(${cx} ${cy}) scale(${0.92 + 0.08 * pop}) translate(${-cx} ${-cy})`}
        style={{ filter: t.glow ? `drop-shadow(0 0 ${18 * t.glow}px ${hexA(t.accent2, 0.8)})` : undefined }}>{text}</text>
      {props.label && <text x={cx} y={labelY} textAnchor="middle" fontSize={lfs} fontWeight={700} fill={t.muted}>{props.label}</text>}
    </svg>
  );
};
