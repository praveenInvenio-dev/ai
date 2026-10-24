import { makeScene2D, Circle, Line, Rect, Txt } from '@revideo/2d';
import { all, createRef, easeOutCubic, tween, useScene, waitFor } from '@revideo/core';

const FONT = 'Lato, Arial, Helvetica, "DejaVu Sans", sans-serif';
const MONO = '"DejaVu Sans Mono", monospace';
const PALETTE = ['#60a5fa', '#fbbf24', '#34d399', '#f472b6', '#a78bfa', '#fb923c', '#67e8f9', '#f87171'];

/** A cycle that turns: the stages appear on a ring one by one as the narration reaches them, arrows join them, and a dot then chases round the ring for as long as the scene lasts
 *  (the Calvin cycle, the water cycle, the cell cycle, a Carnot cycle). The centre label names the cycle. */
export default makeScene2D('cycle', function* (view) {
  const vars = useScene().variables;
  const bg = String(vars.get('bg', '#0c2433')());
  const light = Number(vars.get('light', 0)()) === 1;
  const kicker = String(vars.get('kicker', '')());
  const title = String(vars.get('title', '')());
  const centre = String(vars.get('centre', '')());
  const total = Math.max(3, Number(vars.get('total', 8)()));
  let steps: string[] = []; let starts: number[] = [];
  try { steps = JSON.parse(String(vars.get('steps', '[]')())); } catch { steps = []; }
  try { starts = JSON.parse(String(vars.get('starts', '[]')())); } catch { starts = []; }
  steps = steps.slice(0, 8);
  view.fill(bg);
  const W = view.size().x, H = view.size().y, u = W / 1280;
  const ink = light ? '#2a2540' : '#f8fafc', sub = light ? '#7a7396' : '#8a9bb0';
  view.add(<Txt text={kicker} fill={sub} fontFamily={MONO} fontSize={15 * u} y={-H / 2 + 22 * u} />);
  view.add(<Txt text={title} fill={ink} fontFamily={FONT} fontSize={36 * u} fontWeight={400} y={-H / 2 + 52 * u} />);
  const n = steps.length, R = Math.min(H * 0.3, W * 0.22), cy = 34 * u;
  const at = (i: number) => Math.max(0, (starts.length ? starts[Math.min(i, starts.length - 1)] : i * 2.5) - 0.15);
  const nodeW = Math.min(250 * u, 2 * R * Math.sin(Math.PI / Math.max(3, n)) * 1.05), nodeH = 74 * u;
  const pos = steps.map((_, i) => { const a = -Math.PI / 2 + (i * 2 * Math.PI) / n; return [Math.cos(a) * R * 1.35, cy + Math.sin(a) * R] as [number, number]; });
  const ring = createRef<Circle>();
  view.add(<Circle ref={ring} width={R * 2.7} height={R * 2} x={0} y={cy} stroke={light ? '#d8d2ee' : '#1d3a4d'} lineWidth={4 * u} lineDash={[10 * u, 10 * u]} opacity={0} />);
  const nodes = steps.map(() => createRef<Rect>()); const arrows = steps.map(() => createRef<Line>());
  steps.forEach((s, i) => {
    const col = PALETTE[i % PALETTE.length];
    const j = (i + 1) % n, [ax, ay] = pos[i], [bx, by] = pos[j], dx = bx - ax, dy = by - ay, len = Math.hypot(dx, dy), ux = dx / len, uy = dy / len;
    const trim = (dxn: number, dyn: number) => Math.min(Math.abs(dxn) > 1e-6 ? (nodeW / 2) / Math.abs(dxn) : 1e9, Math.abs(dyn) > 1e-6 ? (nodeH / 2) / Math.abs(dyn) : 1e9) + 8 * u;
    view.add(<Line ref={arrows[i]} points={[[ax + ux * trim(ux, uy), ay + uy * trim(ux, uy)], [bx - ux * trim(ux, uy), by - uy * trim(ux, uy)]]} stroke={light ? '#9a93b8' : '#5d7a8c'} lineWidth={3.5 * u} endArrow arrowSize={16 * u} end={0} />);
    view.add(
      <Rect ref={nodes[i]} layout alignItems="center" justifyContent="center" padding={[8 * u, 14 * u]} width={nodeW} height={nodeH} x={ax} y={ay} radius={nodeH / 2.4} fill={light ? '#ffffff' : '#0d2a3d'} stroke={col} lineWidth={3.5 * u} opacity={0}>
        <Txt text={s} fill={col} fontFamily={FONT} fontSize={(s.length > 16 ? 19 : 23) * u} fontWeight={800} textWrap width={nodeW - 28 * u} textAlign="center" />
      </Rect>,
    );
    nodes[i]().scale(0.85);
  });
  if (centre) view.add(<Txt text={centre} fill={ink} fontFamily={FONT} fontSize={30 * u} fontWeight={700} y={cy} textWrap width={R * 1.3} textAlign="center" />);
  const dot = createRef<Circle>();
  view.add(<Circle ref={dot} size={20 * u} fill="#fde047" shadowColor="#fde047" shadowBlur={18 * u} x={pos[0][0]} y={pos[0][1]} opacity={0} />);

  let now = 0;
  yield* waitFor(at(0)); now = at(0); yield* ring().opacity(1, 0.5);
  for (let i = 0; i < n; i++) {
    if (at(i) > now) { yield* waitFor(at(i) - now); now = at(i); }
    yield* all(nodes[i]().opacity(1, 0.4), nodes[i]().scale(1, 0.4, easeOutCubic)); now += 0.4;
    if (i > 0) { yield arrows[i - 1]().end(1, 0.5, easeOutCubic); }
  }
  yield* arrows[n - 1]().end(1, 0.5, easeOutCubic); now += 0.5;
  // the dot chases round the ring until the scene ends
  yield dot().opacity(1, 0.3);
  const lap = 3.2; let t = 0;
  while (now + lap < total - 0.2) {
    yield* tween(lap, v => { const a = -Math.PI / 2 + v * 2 * Math.PI; dot().position([Math.cos(a) * R * 1.35, cy + Math.sin(a) * R]); });
    now += lap; t += lap;
  }
  if (total > now) yield* waitFor(total - now);
});
