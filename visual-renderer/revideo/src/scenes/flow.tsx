import { makeScene2D, Circle, Line, Rect, Txt } from '@revideo/2d';
import { all, createRef, waitFor, useScene, Vector2 } from '@revideo/core';
import { Glyph } from '../Glyph';
import { pickIcon } from '../icons';

const COLORS = ['#60a5fa', '#fbbf24', '#34d399', '#f472b6', '#a78bfa'];
const FILLS = ['#16314f', '#3a2f12', '#143a2f', '#3d1a30', '#2a2150'];
const FONT = 'Lato, Arial, Helvetica, "DejaVu Sans", sans-serif';
const REVEAL = 1.05;   // seconds one step takes to appear: box in, arrow drawn, packet sent

/** An animated step flow timed to the narration: step i appears when the sentence about it starts (`reveals[i]`, seconds), the clip lasts `total` seconds. */
export default makeScene2D('flow', function* (view) {
  const vars = useScene().variables;
  const title = String(vars.get('title', '')());
  const bg = String(vars.get('bg', '#0b0b0c')());
  const kicker = String(vars.get('kicker', '')());
  const portrait = Number(vars.get('portrait', 0)()) === 1;
  const total = Math.max(2, Number(vars.get('total', 6)()));
  let labels: string[] = []; let reveals: number[] = [];
  try { labels = JSON.parse(String(vars.get('labels', '[]')())); } catch { labels = []; }
  try { reveals = JSON.parse(String(vars.get('reveals', '[]')())); } catch { reveals = []; }
  labels = labels.filter(Boolean).slice(0, 5);
  while (labels.length < 3) labels.push(['Start', 'Process', 'Result'][labels.length]);
  const n = labels.length;
  // a reveal time for every step, in order, never later than the clip allows
  const times: number[] = [];
  for (let i = 0; i < n; i++) {
    const t = Number.isFinite(reveals[i]) ? reveals[i] : (i * (total - 1.2)) / Math.max(1, n);
    times.push(Math.min(Math.max(t, i === 0 ? 0 : times[i - 1] + 0.2), Math.max(0, total - REVEAL - 0.1)));
  }

  view.fill(bg);
  const W = view.size().x, H = view.size().y;
  // same heading look as the native renderer: a small grey kicker ("02 · 1865") over a regular-weight title
  view.add(<Txt text={kicker} fill="#8a9bb0" fontFamily="DejaVu Sans Mono, monospace" fontSize={portrait ? 26 : 15} y={-H / 2 + (portrait ? 190 : 22)} />);
  view.add(<Txt text={title} fill="#eaf4ff" fontFamily={FONT} fontSize={portrait ? 64 : 36} fontWeight={400} y={-H / 2 + (portrait ? 262 : 52)} />);

  const boxW = portrait ? Math.min(620, W - 200) : Math.min(380, (W - 160) / n - 60);
  const hasDetail = labels.some(l => l.includes('|') && l.split('|').slice(1).join(' ').trim());   // "label|detail": the detail (often the numbers) is shown under the label
  const boxH = portrait ? (hasDetail ? 250 : 170) : (hasDetail ? 270 : 200);
  const pos: Vector2[] = [];
  for (let i = 0; i < n; i++) {
    pos.push(portrait ? new Vector2(0, -H * 0.18 + i * ((H * 0.5) / Math.max(1, n - 1))) : new Vector2(-((n - 1) * (boxW + 80)) / 2 + i * (boxW + 80), 20));
  }
  const boxes = labels.map(() => createRef<Rect>());
  const arrows = labels.map(() => createRef<Line>());
  const packet = createRef<Circle>();
  labels.forEach((full, i) => {
    const text = full.split('|')[0], detail = full.split('|').slice(1).join(' ').trim();
    view.add(
      <Rect ref={boxes[i]} layout direction="column" alignItems="center" justifyContent="center" gap={portrait ? 12 : 8} position={pos[i]} width={boxW} height={boxH} radius={26} fill={FILLS[i % 5]} stroke={COLORS[i % 5]} lineWidth={5} opacity={0} scale={0.6}>
        {pickIcon(text) ? <Rect width={portrait ? 64 : 48} height={portrait ? 64 : 48}><Glyph name={pickIcon(text) as string} color={COLORS[i % 5]} size={portrait ? 64 : 48} /></Rect> : null}
        <Txt text={text} fill="#f8fafc" fontFamily={FONT} fontSize={Math.max(24, Math.min(portrait ? 50 : 42, (boxW - 40) / 6.2)) * (pickIcon(text) ? 0.9 : 1)} fontWeight={700} textWrap width={boxW - 40} textAlign="center" />
        {detail ? <Txt text={detail} fill="#cfe6f5" fontFamily={FONT} fontSize={Math.max(17, Math.min(portrait ? 30 : 26, (boxW - 36) / 11))} textWrap width={boxW - 36} textAlign="center" /> : null}
      </Rect>,
    );
    if (i > 0) {
      const dir = pos[i].sub(pos[i - 1]).normalized;
      const half = portrait ? boxH / 2 : boxW / 2;
      view.add(<Line ref={arrows[i]} points={[pos[i - 1].add(dir.mul(half + 8)), pos[i].sub(dir.mul(half + 12))]} stroke="#67e8f9" lineWidth={7} endArrow arrowSize={20} end={0} />);
    }
  });
  view.add(<Circle ref={packet} size={30} fill="#ffffff" shadowColor="#67e8f9" shadowBlur={28} position={pos[0]} opacity={0} />);

  let now = 0;
  for (let i = 0; i < n; i++) {
    if (times[i] > now) { yield* waitFor(times[i] - now); now = times[i]; }
    yield* all(boxes[i]().opacity(1, 0.35), boxes[i]().scale(1, 0.35));
    let spent = 0.35;
    if (i > 0) {
      const dir = pos[i].sub(pos[i - 1]).normalized;
      const half = portrait ? boxH / 2 : boxW / 2;
      yield* arrows[i]().end(1, 0.3);
      packet().position(pos[i - 1].add(dir.mul(half + 8)));
      yield* packet().opacity(1, 0.05);
      yield* packet().position(pos[i].sub(dir.mul(half + 12)), 0.4);
      yield* all(packet().opacity(0, 0.1), boxes[i]().scale(1.06, 0.1));
      yield* boxes[i]().scale(1, 0.1);
      spent += 0.3 + 0.05 + 0.4 + 0.2;
    }
    now += spent;
  }
  if (total > now) yield* waitFor(total - now);   // hold the finished diagram until the narration ends
});
