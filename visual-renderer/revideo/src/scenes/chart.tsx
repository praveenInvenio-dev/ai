import { makeScene2D, Circle, Line, Node, Path, Rect, Txt } from '@revideo/2d';
import { all, createRef, easeOutCubic, tween, useScene, waitFor } from '@revideo/core';
import { scaleBand, scaleLinear, scalePoint } from 'd3-scale';
import { arc as d3arc, area as d3area, curveMonotoneX, line as d3line, pie as d3pie } from 'd3-shape';
import { max as d3max } from 'd3-array';

const FONT = 'Lato, Arial, Helvetica, "DejaVu Sans", sans-serif';
const MONO = '"DejaVu Sans Mono", monospace';
const PALETTE = ['#60a5fa', '#fbbf24', '#34d399', '#f472b6', '#a78bfa', '#fb923c', '#67e8f9'];

/** One chart drawn with D3 scales and shapes, animated: a line draws itself point by point (with a soft area), bars grow with counting values, a donut sweeps round.
 *  Data: [{label, value}], style line | area | bar | donut, optional unit and caption. `starts[0]` is when it starts; the points / bars / slices are spread over the narration. */
export default makeScene2D('chart', function* (view) {
  const vars = useScene().variables;
  const bg = String(vars.get('bg', '#0c2433')());
  const kicker = String(vars.get('kicker', '')());
  const title = String(vars.get('title', '')());
  const style = String(vars.get('style', 'line')());
  const unit = String(vars.get('unit', '')());
  const caption = String(vars.get('caption', '')());
  const total = Math.max(2, Number(vars.get('total', 8)()));
  let data: { label: string; value: number }[] = []; let starts: number[] = [];
  try { data = JSON.parse(String(vars.get('data', '[]')())); } catch { data = []; }
  try { starts = JSON.parse(String(vars.get('starts', '[]')())); } catch { starts = []; }
  data = data.filter(d => Number.isFinite(d.value)).slice(0, 12);
  view.fill(bg);
  const W = view.size().x, H = view.size().y, u = W / 1280;
  view.add(<Txt text={kicker} fill="#8a9bb0" fontFamily={MONO} fontSize={15 * u} y={-H / 2 + 22 * u} />);
  view.add(<Txt text={title} fill="#eaf4ff" fontFamily={FONT} fontSize={36 * u} fontWeight={400} y={-H / 2 + 52 * u} />);
  if (caption) view.add(<Txt text={caption} fill="#9fb8c9" fontFamily={FONT} fontSize={21 * u} y={H / 2 - 40 * u} />);
  if (!data.length) { yield* waitFor(total); return; }

  const fmt = (v: number) => (Math.abs(v) >= 100 ? String(Math.round(v)) : String(Math.round(v * 10) / 10)) + (unit ? ' ' + unit : '');
  const t0 = Math.max(0, (starts.length ? starts[0] : 0) - 0.15);
  const drawTime = Math.min(Math.max(2.4, (starts.length > 1 ? starts[starts.length - 1] - t0 : 4)), Math.max(2.4, total - t0 - 1.2));
  const cw = W * 0.74, ch = H * 0.5, cx0 = -cw / 2, cyBase = H * 0.12 + ch / 2 - 30 * u;      // plot box: left edge, baseline

  if (style === 'donut') {
    const arcs = d3pie<{ label: string; value: number }>().value(d => Math.max(0, d.value)).sort(null)(data);
    const R = Math.min(ch, cw) * 0.52, r = R * 0.58, gen = d3arc<any>().innerRadius(r).outerRadius(R).cornerRadius(4 * u);
    const slices = arcs.map(() => createRef<Path>());
    arcs.forEach((a, i) => view.add(<Path ref={slices[i]} x={-cw * 0.2} y={H * 0.1} data={gen({ ...a, endAngle: a.startAngle + 0.001 }) || ''} fill={PALETTE[i % PALETTE.length]} />));
    const sum = data.reduce((s, d) => s + Math.max(0, d.value), 0) || 1;
    data.forEach((d, i) => view.add(
      <Node x={cw * 0.22} y={H * 0.1 + (i - (data.length - 1) / 2) * 44 * u}>
        <Rect width={20 * u} height={20 * u} radius={5 * u} fill={PALETTE[i % PALETTE.length]} x={-170 * u} />
        <Txt text={`${d.label}  ${Math.round((100 * d.value) / sum)}%`} fill="#e6f1f7" fontFamily={FONT} fontSize={22 * u} offsetX={-1} x={-140 * u} />
      </Node>));
    yield* waitFor(t0);
    yield* tween(drawTime, v => { const f = easeOutCubic(v); arcs.forEach((a, i) => slices[i]().data(gen({ ...a, endAngle: a.startAngle + (a.endAngle - a.startAngle) * f }) || '')); });
    if (total > t0 + drawTime) yield* waitFor(total - t0 - drawTime);
    return;
  }

  const labels = data.map(d => d.label);
  const vmax = (d3max(data, d => d.value) || 1) * 1.12, vmin = Math.min(0, ...data.map(d => d.value));
  const y = scaleLinear().domain([vmin, vmax]).nice(4).range([cyBase, cyBase - ch]);   // nice round axis values (0, 25, 50 ...), not 30.8 / 61.6
  // grid + axis labels
  y.ticks(4).forEach(v => {
    const yy = y(v);
    view.add(<Line points={[[cx0, yy], [cx0 + cw, yy]]} stroke={v === 0 ? '#5d7a8c' : '#1d3a4d'} lineWidth={(v === 0 ? 2 : 1) * u} />);
    view.add(<Txt text={String(v)} fill="#6f8b9c" fontFamily={MONO} fontSize={15 * u} x={cx0 - 14 * u} y={yy} offsetX={1} />);
  });

  if (style === 'bar') {
    const x = scaleBand().domain(labels).range([cx0, cx0 + cw]).padding(0.28);
    const bars = data.map(() => createRef<Rect>()); const vals = data.map(() => createRef<Txt>());
    data.forEach((d, i) => {
      const bx = (x(d.label) || 0) + x.bandwidth() / 2;
      view.add(<Rect ref={bars[i]} width={x.bandwidth()} height={2 * u} x={bx} y={cyBase - u} offsetY={1} radius={[8 * u, 8 * u, 0, 0]} fill={PALETTE[i % PALETTE.length]} />);
      view.add(<Txt ref={vals[i]} text="" fill="#ffffff" fontFamily={MONO} fontSize={18 * u} fontWeight={700} x={bx} y={cyBase - 14 * u} />);
      view.add(<Txt text={d.label} fill="#cfe6f5" fontFamily={FONT} fontSize={18 * u} x={bx} y={cyBase + 26 * u} />);
    });
    yield* waitFor(t0);
    yield* tween(drawTime, v => data.forEach((d, i) => {
      const f = easeOutCubic(Math.min(1, Math.max(0, v * 1.25 - (i / data.length) * 0.25)));      // bars start one after another
      const hh = Math.max(2 * u, (cyBase - y(Math.max(0, d.value))) * f);
      bars[i]().height(hh); bars[i]().y(cyBase - u); vals[i]().text(fmt(d.value * f)); vals[i]().y(cyBase - hh - 18 * u);
    }));
  } else {
    const x = scalePoint().domain(labels).range([cx0 + 30 * u, cx0 + cw - 30 * u]);
    const pts = data.map(d => [x(d.label) || 0, y(d.value)] as [number, number]);
    const lineD = d3line<[number, number]>().x(p => p[0]).y(p => p[1]).curve(curveMonotoneX)(pts) || '';
    const areaD = d3area<[number, number]>().x(p => p[0]).y0(cyBase).y1(p => p[1]).curve(curveMonotoneX)(pts) || '';
    const areaRef = createRef<Path>(); const lineRef = createRef<Path>();
    if (style === 'area' || style === 'line') view.add(<Path ref={areaRef} data={areaD} fill="#67e8f9" opacity={0} />);
    view.add(<Path ref={lineRef} data={lineD} stroke="#67e8f9" lineWidth={5 * u} lineCap="round" lineJoin="round" end={0} />);
    const dots = pts.map(() => createRef<Circle>()); const vlab = pts.map(() => createRef<Txt>());
    pts.forEach((p, i) => {
      view.add(<Circle ref={dots[i]} size={14 * u} fill="#0c2433" stroke="#ffffff" lineWidth={3.5 * u} x={p[0]} y={p[1]} scale={0} />);
      view.add(<Txt ref={vlab[i]} text={fmt(data[i].value)} fill="#ffffff" fontFamily={MONO} fontSize={17 * u} fontWeight={700} x={p[0]} y={p[1] - 26 * u} opacity={0} />);
      view.add(<Txt text={data[i].label} fill="#cfe6f5" fontFamily={FONT} fontSize={18 * u} x={p[0]} y={cyBase + 26 * u} />);
    });
    yield* waitFor(t0);
    yield* tween(drawTime, v => {
      const f = easeOutCubic(v); lineRef().end(f); if (areaRef()) areaRef().opacity(0.16 * f);
      pts.forEach((_, i) => { const k = Math.min(1, Math.max(0, f * (pts.length - 1) + 0.0001 - i + 1)); dots[i]().scale(k >= 1 ? 1 : k > 0 ? k : 0); vlab[i]().opacity(k >= 1 ? 1 : 0); });
    });
  }
  if (total > t0 + drawTime) yield* waitFor(total - t0 - drawTime);
});
