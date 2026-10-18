import { makeScene2D, Circle, Line, Rect, Txt } from '@motion-canvas/2d';
import { all, createRef, waitFor, useScene, Vector2 } from '@motion-canvas/core';

/** Seconds per phase: reveal a step, draw the arrow into it, send a packet along. The exporter seeks to the end of the phase it needs. */
export const PHASE = 1.2;
/** Same family the other renderers use, so an animated scene does not look like a different video. */
const FONT = 'Lato, Arial, Helvetica, "DejaVu Sans", sans-serif';

const COLORS = ['#60a5fa', '#fbbf24', '#34d399', '#f472b6', '#a78bfa'];
const FILLS = ['#16314f', '#3a2f12', '#143a2f', '#3d1a30', '#2a2150'];

export default makeScene2D(function* (view) {
  const vars = useScene().variables;
  const title = String(vars.get('title', '')());
  const bg = String(vars.get('bg', '#0b0b0c')());
  const portrait = Number(vars.get('portrait', 0)()) === 1;
  let labels: string[] = [];
  try { labels = JSON.parse(String(vars.get('labels', '[]')())); } catch { labels = []; }
  labels = labels.filter(Boolean).slice(0, 5);
  while (labels.length < 3) labels.push(['Start', 'Process', 'Result'][labels.length]);

  view.fill(bg);
  const W = view.size().x, H = view.size().y;
  const n = labels.length;

  view.add(<Txt text={title} fill="#eaf4ff" fontFamily={FONT} fontSize={portrait ? 64 : 52} fontWeight={800} y={-H / 2 + (portrait ? 220 : 90)} />);

  // lay the steps out along the long side of the frame
  const boxW = portrait ? Math.min(620, W - 200) : Math.min(380, (W - 160) / n - 60);
  const boxH = portrait ? 170 : 200;
  const pos: Vector2[] = [];
  for (let i = 0; i < n; i++) {
    pos.push(portrait
      ? new Vector2(0, -H * 0.18 + i * ((H * 0.5) / Math.max(1, n - 1)))
      : new Vector2(-((n - 1) * (boxW + 80)) / 2 + i * (boxW + 80), 20));
  }

  const boxes = labels.map(() => createRef<Rect>());
  const arrows = labels.map(() => createRef<Line>());
  const packet = createRef<Circle>();

  labels.forEach((text, i) => {
    view.add(
      <Rect ref={boxes[i]} position={pos[i]} width={boxW} height={boxH} radius={26} fill={FILLS[i % 5]} stroke={COLORS[i % 5]} lineWidth={5} opacity={0} scale={0.6}>
        <Txt text={text} fill="#f8fafc" fontFamily={FONT} fontSize={portrait ? 50 : 42} fontWeight={700} textWrap width={boxW - 40} textAlign="center" />
      </Rect>,
    );
    if (i > 0) {
      const a = pos[i - 1], b = pos[i];
      const dir = b.sub(a).normalized;
      const start = a.add(dir.mul(portrait ? boxH / 2 + 8 : boxW / 2 + 8));
      const end = b.sub(dir.mul(portrait ? boxH / 2 + 12 : boxW / 2 + 12));
      view.add(<Line ref={arrows[i]} points={[start, end]} stroke="#67e8f9" lineWidth={7} endArrow arrowSize={20} end={0} />);
    }
  });
  view.add(<Circle ref={packet} size={30} fill="#ffffff" shadowColor="#67e8f9" shadowBlur={28} position={pos[0]} opacity={0} />);

  // phase i lasts PHASE seconds and always leaves step i fully shown
  for (let i = 0; i < n; i++) {
    yield* all(boxes[i]().opacity(1, 0.35), boxes[i]().scale(1, 0.35));
    if (i > 0) {
      yield* arrows[i]().end(1, 0.3);
      const dir = pos[i].sub(pos[i - 1]).normalized;
      const half = portrait ? boxH / 2 : boxW / 2;
      packet().position(pos[i - 1].add(dir.mul(half + 8)));
      yield* packet().opacity(1, 0.05);
      yield* packet().position(pos[i].sub(dir.mul(half + 12)), 0.4);
      yield* all(packet().opacity(0, 0.1), boxes[i]().scale(1.06, 0.1));
      yield* boxes[i]().scale(1, 0.1);
      yield* waitFor(Math.max(0, PHASE - 0.35 - 0.3 - 0.05 - 0.4 - 0.2));
    } else {
      yield* waitFor(PHASE - 0.35);
    }
  }
  yield* waitFor(0.6);
});
