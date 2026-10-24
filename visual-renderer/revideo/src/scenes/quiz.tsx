import { makeScene2D, Circle, Rect, Txt } from '@revideo/2d';
import { all, createRef, easeOutCubic, tween, useScene, waitFor } from '@revideo/core';

const FONT = 'Lato, Arial, Helvetica, "DejaVu Sans", sans-serif';
const MONO = '"DejaVu Sans Mono", monospace';

/** A quiz that plays: the question appears, the options come in one by one, a 3-2-1 "think" countdown runs while the narration asks, and at the reveal sentence the right option turns
 *  green with a tick while the others dim. `correct` is 1-based, `revealAt` is the sentence index that gives the answer, `starts` are sentence start times (seconds). */
export default makeScene2D('quiz', function* (view) {
  const vars = useScene().variables;
  const bg = String(vars.get('bg', '#0c2433')());
  const light = Number(vars.get('light', 0)()) === 1;
  const kicker = String(vars.get('kicker', '')());
  const title = String(vars.get('title', '')());
  const question = String(vars.get('question', '')());
  const correct = Number(vars.get('correct', 0)());
  const revealAt = Number(vars.get('revealAt', 1)());
  const total = Math.max(3, Number(vars.get('total', 8)()));
  let options: string[] = []; let starts: number[] = [];
  try { options = JSON.parse(String(vars.get('options', '[]')())); } catch { options = []; }
  try { starts = JSON.parse(String(vars.get('starts', '[]')())); } catch { starts = []; }
  options = options.slice(0, 5);
  view.fill(bg);
  const W = view.size().x, H = view.size().y, u = W / 1280;
  const ink = light ? '#2a2540' : '#eaf4ff', sub = light ? '#7a7396' : '#8a9bb0';
  const cardFill = light ? '#ffffff' : '#0d2a3d', edge = light ? '#2a2540' : '#3aa0d0', accent = light ? '#f59e0b' : '#fbbf24';
  const rowFill = light ? '#ffffff' : '#12384f', rowEdge = light ? '#2a2540' : '#3aa0d0', good = '#2fbf71';
  view.add(<Txt text={kicker} fill={sub} fontFamily={MONO} fontSize={15 * u} y={-H / 2 + 22 * u} />);
  view.add(<Txt text={title} fill={ink} fontFamily={FONT} fontSize={36 * u} fontWeight={400} y={-H / 2 + 52 * u} />);

  const kz = light ? 1.28 : 1;   // the kids look is bigger
  const cw = W * 0.8, rowH = 62 * u * kz, gap = 14 * u, qH = 96 * u * kz, ch = qH + options.length * (rowH + gap) + 56 * u, cy = 34 * u;
  const card = createRef<Rect>(); const rows = options.map(() => createRef<Rect>()); const ticks = options.map(() => createRef<Txt>());
  const ring = createRef<Circle>(); const count = createRef<Txt>();
  view.add(
    <Rect ref={card} width={cw} height={ch} y={cy} radius={22 * u} fill={cardFill} stroke={edge} lineWidth={3 * u} opacity={0}>
      <Txt text="QUIZ" fill={accent} fontFamily={FONT} fontSize={20 * u} fontWeight={800} x={-cw / 2 + 40 * u} y={-ch / 2 + 34 * u} offsetX={-1} />
      <Txt text={question} fill={ink} fontFamily={FONT} fontSize={31 * u * kz} fontWeight={700} textWrap width={cw - 190 * u} x={-cw / 2 + 40 * u} y={-ch / 2 + 78 * u} offsetX={-1} textAlign="left" />
      <Circle ref={ring} size={70 * u} stroke={accent} lineWidth={7 * u} startAngle={-90} endAngle={270} x={cw / 2 - 66 * u} y={-ch / 2 + 62 * u} opacity={0} />
      <Txt ref={count} text="" fill={accent} fontFamily={FONT} fontSize={34 * u} fontWeight={800} x={cw / 2 - 66 * u} y={-ch / 2 + 62 * u} opacity={0} />
    </Rect>,
  );
  options.forEach((o, i) => {
    view.add(
      <Rect ref={rows[i]} width={cw - 80 * u} height={rowH} x={0} y={cy - ch / 2 + qH + 40 * u + i * (rowH + gap) + rowH / 2} radius={rowH / 2} fill={rowFill} stroke={rowEdge} lineWidth={2.5 * u} opacity={0}>
        <Txt text={String.fromCharCode(65 + i) + ')'} fill={accent} fontFamily={FONT} fontSize={26 * u * kz} fontWeight={800} x={-(cw - 80 * u) / 2 + 40 * u} />
        <Txt text={o} fill={ink} fontFamily={FONT} fontSize={27 * u * kz} fontWeight={600} x={-(cw - 80 * u) / 2 + 84 * u} offsetX={-1} />
        <Txt ref={ticks[i]} text="✓" fill="#ffffff" fontFamily={FONT} fontSize={32 * u} fontWeight={800} x={(cw - 80 * u) / 2 - 44 * u} opacity={0} />
      </Rect>,
    );
    rows[i]().x(40 * u);
  });

  const at = (n: number) => Math.max(0, (starts.length ? starts[Math.min(Math.max(0, n), starts.length - 1)] : n * 3) - 0.15);
  const tReveal = Math.max(at(revealAt), at(0) + 2.2), tStart = at(0);
  let now = 0;
  if (tStart > 0) { yield* waitFor(tStart); now = tStart; }
  yield* all(card().opacity(1, 0.45), card().y(cy, 0.45, easeOutCubic)); now += 0.45;
  for (const r of rows) { yield r().opacity(1, 0.4); yield r().x(0, 0.4, easeOutCubic); yield* waitFor(0.3); now += 0.3; }
  // think time: a 3-2-1 ring that empties until the reveal
  const think = Math.min(3.2, tReveal - now - 0.2);
  if (think > 1) {
    yield* waitFor(Math.max(0, tReveal - now - think)); now = Math.max(now, tReveal - think);
    ring().opacity(1); count().opacity(1);
    yield* tween(think, v => { ring().endAngle(270 - 360 * v); count().text(String(Math.max(1, Math.ceil(3 * (1 - v))))); });
    ring().opacity(0); count().opacity(0); now = tReveal;
  } else if (tReveal > now) { yield* waitFor(tReveal - now); now = tReveal; }
  // reveal: the right option turns green and pops, the rest dim
  options.forEach((_, i) => {
    if (i + 1 === correct) { rows[i]().fill(good); rows[i]().stroke(good); ticks[i]().opacity(1); }
    else rows[i]().opacity(0.35);
  });
  if (correct >= 1 && correct <= options.length) { yield* rows[correct - 1]().scale(1.04, 0.25, easeOutCubic); yield* rows[correct - 1]().scale(1, 0.35, easeOutCubic); now += 0.6; }
  if (total > now) yield* waitFor(total - now);
});
