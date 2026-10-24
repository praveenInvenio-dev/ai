import { makeScene2D, Line, Node, Rect, Txt } from '@revideo/2d';
import { all, createRef, easeInOutCubic, easeOutCubic, tween, useScene, waitFor } from '@revideo/core';

const FONT = 'Lato, Arial, Helvetica, "DejaVu Sans", sans-serif';
const MONO = '"DejaVu Sans Mono", monospace';
const PALETTE = ['#60a5fa', '#f472b6', '#34d399', '#fb923c', '#a78bfa', '#67e8f9'];
type Force = { dir: string; symbol: string; name: string; size: number };
const DIR: Record<string, [number, number]> = { right: [1, 0], left: [-1, 0], up: [0, -1], down: [0, 1], upright: [0.7071, -0.7071], upleft: [-0.7071, -0.7071], downright: [0.7071, 0.7071], downleft: [-0.7071, 0.7071] };

/** A free-body diagram that plays: the forces grow out of the body one by one as the narration names them; equal and opposite forces are marked as cancelling; then the NET force appears in
 *  yellow and the body ACCELERATES in its direction while a growing velocity arrow shows it speeding up (x = ½at²). `starts` = sentence start times (seconds). */
export default makeScene2D('forces', function* (view) {
  const vars = useScene().variables;
  const bg = String(vars.get('bg', '#0c2433')());
  const light = Number(vars.get('light', 0)()) === 1;
  const kicker = String(vars.get('kicker', '')());
  const title = String(vars.get('title', '')());
  const body = String(vars.get('object', 'Body')());
  const total = Math.max(3, Number(vars.get('total', 8)()));
  let forces: Force[] = []; let starts: number[] = [];
  try { forces = JSON.parse(String(vars.get('forces', '[]')())); } catch { forces = []; }
  try { starts = JSON.parse(String(vars.get('starts', '[]')())); } catch { starts = []; }
  forces = forces.filter(f => DIR[String(f.dir).toLowerCase()]).slice(0, 6);
  view.fill(bg);
  const W = view.size().x, H = view.size().y, u = W / 1280;
  const ink = light ? '#2a2540' : '#eaf4ff', sub = light ? '#7a7396' : '#8a9bb0';
  view.add(<Txt text={kicker} fill={sub} fontFamily={MONO} fontSize={15 * u} y={-H / 2 + 22 * u} />);
  view.add(<Txt text={title} fill={ink} fontFamily={FONT} fontSize={36 * u} fontWeight={400} y={-H / 2 + 52 * u} />);

  const at = (n: number) => Math.max(0, (starts.length ? starts[Math.min(Math.max(0, n), starts.length - 1)] : n * 3) - 0.15);
  const bw = 170 * u, bh = 120 * u, x0 = -W * 0.2, y0 = 30 * u, UNIT = 150 * u;
  const group = createRef<Node>(); const block = createRef<Rect>();
  view.add(<Node ref={group} x={x0} y={y0}><Rect ref={block} width={bw} height={bh} radius={10 * u} fill={light ? '#fde68a' : '#24667f'} stroke={light ? '#2a2540' : '#9bd6ea'} lineWidth={3 * u} opacity={0}><Txt text={body} fill={ink} fontFamily={FONT} fontSize={24 * u} fontWeight={700} /></Rect></Node>);

  // one arrow + label per force, starting at the block's edge
  const arrows = forces.map(() => createRef<Line>()); const labels = forces.map(() => createRef<Txt>()); const subs = forces.map(() => createRef<Txt>());
  const ends: [number, number][] = [];
  let nx = 0, ny = 0;
  forces.forEach((f, i) => {
    const [dx, dy] = DIR[String(f.dir).toLowerCase()], s = Math.max(0.4, Math.min(2.2, Number(f.size) || 1)), col = PALETTE[i % PALETTE.length];
    const sx = dx * (bw / 2 + 6 * u), sy = dy * (bh / 2 + 6 * u), ex = sx + dx * UNIT * s, ey = sy + dy * UNIT * s;
    ends.push([ex, ey]); nx += dx * s; ny += dy * s;
    group().add(<Line ref={arrows[i]} points={[[sx, sy], [ex, ey]]} stroke={col} lineWidth={7 * u} endArrow arrowSize={22 * u} end={0} lineCap="round" />);
    group().add(<Txt ref={labels[i]} text={f.symbol} fill={col} fontFamily={MONO} fontSize={30 * u} fontWeight={800} x={ex + dx * 34 * u} y={ey + dy * 26 * u + (dx !== 0 ? -18 * u : 0)} opacity={0} />);
    group().add(<Txt ref={subs[i]} text={f.name} fill={sub} fontFamily={FONT} fontSize={19 * u} x={ex + dx * 34 * u} y={ey + dy * 26 * u + (dx !== 0 ? 14 * u : 28 * u * (dy < 0 ? -1 : 1) * 0 + (dy !== 0 ? 28 * u : 0))} opacity={0} />);
  });
  // net force + velocity arrows live in the group too, so they travel with the body
  const net = createRef<Line>(); const netLabel = createRef<Txt>(); const vel = createRef<Line>(); const velLabel = createRef<Txt>(); const cancel = createRef<Txt>();
  const nlen = Math.hypot(nx, ny), eps = 0.05;
  const hasNet = nlen > eps, ndx = hasNet ? nx / nlen : 0, ndy = hasNet ? ny / nlen : 0;
  const upDown = forces.some(f => f.dir === 'up') && forces.some(f => f.dir === 'down') && Math.abs(ny) < eps;
  group().add(<Line ref={net} points={[[bw / 2 + 24 * u, -bh / 2 - 56 * u], [bw / 2 + 24 * u + ndx * UNIT * Math.max(0.8, nlen), -bh / 2 - 56 * u + ndy * UNIT * Math.max(0.8, nlen)]]} stroke="#fde047" lineWidth={11 * u} endArrow arrowSize={30 * u} end={0} lineCap="round" />);
  group().add(<Txt ref={netLabel} text="net force" fill="#fde047" fontFamily={FONT} fontSize={22 * u} fontWeight={800} x={bw / 2 + 24 * u + ndx * UNIT * Math.max(0.8, nlen) * 0.5} y={-bh / 2 - 92 * u} opacity={0} />);
  group().add(<Txt ref={cancel} text="cancel out" fill={light ? '#2a2540' : '#cfe6f5'} fontFamily={FONT} fontSize={20 * u} fontWeight={700} x={-bw / 2 - 90 * u} y={0} opacity={0} />);
  group().add(<Line ref={vel} points={[[bw / 2 + 24 * u, bh / 2 + 56 * u], [bw / 2 + 25 * u, bh / 2 + 56 * u]]} stroke="#34d399" lineWidth={7 * u} endArrow arrowSize={20 * u} opacity={0} />);
  group().add(<Txt ref={velLabel} text="velocity v" fill="#34d399" fontFamily={FONT} fontSize={19 * u} fontWeight={700} x={bw / 2 + 24 * u} y={bh / 2 + 92 * u} opacity={0} />);

  let now = 0;
  const wait = function* (t: number) { if (t > now) { yield* waitFor(t - now); now = t; } };
  // schedule: the forces appear with the sentences when there are enough of them, otherwise they are spread evenly over the first part of the scene;
  // the last ~4.2 s are always kept for "cancel out", the net force and the acceleration, so a short scene never cuts the physics off
  const t0 = at(0), tail = 4.2, enough = starts.length >= forces.length + 1;
  const span = Math.max(1.2, total - tail - t0 - 0.5);
  const tForce = (i: number) => enough ? at(i) : t0 + 0.45 + (i * span) / Math.max(1, forces.length);
  yield* wait(t0); yield* block().opacity(1, 0.35); now += 0.35;
  for (let i = 0; i < forces.length; i++) {
    yield* wait(tForce(i));
    yield* all(arrows[i]().end(1, 0.4, easeOutCubic), labels[i]().opacity(1, 0.3), subs[i]().opacity(1, 0.3)); now += 0.4;
  }
  if (upDown) { yield cancel().opacity(1, 0.35); }
  const tNet = Math.max(now + 0.35, total - 3.7);
  yield* wait(tNet);
  if (hasNet) {
    yield* all(net().end(1, 0.5, easeOutCubic), netLabel().opacity(1, 0.35)); now += 0.5;
    // the body accelerates along the net force: position follows x = x0 + ½ a t², the velocity arrow grows with v = a t
    const dur = Math.min(4.5, Math.max(1.6, total - now - 0.3)), travel = Math.min(W * 0.34, W * 0.2 + 120 * u * nlen);
    const sx = ndx * travel, sy = ndy * travel * 0.6;
    yield* all(vel().opacity(1, 0.25), velLabel().opacity(1, 0.25));
    yield* tween(dur, v => {
      const p = v * v;
      group().x(x0 + sx * p); group().y(y0 + sy * p);
      const len = 12 * u + 190 * u * v, sg = ndx >= 0 ? 1 : -1;
      vel().points([[sg * (bw / 2 + 24 * u), bh / 2 + 56 * u], [sg * (bw / 2 + 24 * u) + len * sg, bh / 2 + 56 * u]]);
      velLabel().x(sg * (bw / 2 + 24 * u) + len * sg * 0.5);
    }); now += dur;
  }
  if (total > now) yield* waitFor(total - now);
});
