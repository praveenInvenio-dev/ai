import { makeScene2D, Circle, Line, Node, Rect, Txt } from '@revideo/2d';
import { all, createRef, easeOutCubic, useScene, waitFor } from '@revideo/core';
import { Glyph } from '../Glyph';
import { plannerIcon } from '../icons';

const FONT = 'Lato, Arial, Helvetica, "DejaVu Sans", sans-serif';
const MONO = '"DejaVu Sans Mono", monospace';
const COLORS: Record<string, string> = { teal: '#14b8a6', purple: '#a78bfa', blue: '#60a5fa', orange: '#fb923c', green: '#34d399', red: '#f87171', dark: '#64748b', yellow: '#fbbf24', white: '#e5e7eb', pink: '#f472b6', violet: '#a78bfa' };
type El = { id?: string; type: string; text?: string; title?: string; lines?: string[]; icon?: string; color?: string; style?: string; from?: string; to?: string;
            x?: number; y?: number; w?: number; h?: number; at?: number; dimAt?: number; highlightAt?: number[] };

/** The reference-video look, animated: the planner's own diagram (tiles with icons, actors, buttons, containers, notes, arrows with labels) where every element pops in when the
 *  narration reaches its sentence, arrows draw themselves and send a dot along, and an element glows while the voice talks about it (highlightAt) and fades back (dimAt). */
export default makeScene2D('diagram', function* (view) {
  const vars = useScene().variables;
  const bg = String(vars.get('bg', '#0c2433')());
  const kicker = String(vars.get('kicker', '')());
  const title = String(vars.get('title', '')());
  const total = Math.max(2, Number(vars.get('total', 8)()));
  let els: El[] = []; let starts: number[] = [];
  try { els = JSON.parse(String(vars.get('elements', '[]')())); } catch { els = []; }
  try { starts = JSON.parse(String(vars.get('starts', '[]')())); } catch { starts = []; }
  els = els.slice(0, 16);
  view.fill(bg);
  const W = view.size().x, H = view.size().y, u = W / 1280;
  view.add(<Txt text={kicker} fill="#8a9bb0" fontFamily={MONO} fontSize={15 * u} y={-H / 2 + 22 * u} />);
  view.add(<Txt text={title} fill="#eaf4ff" fontFamily={FONT} fontSize={36 * u} fontWeight={400} y={-H / 2 + 52 * u} />);

  const at = (n?: number) => Math.max(0, (starts.length ? starts[Math.min(Math.max(0, n ?? 0), starts.length - 1)] : (n ?? 0) * 2.5) - 0.15);
  const col = (c?: string, d = '#4fb3e8') => COLORS[String(c || '').toLowerCase()] || (c && /^#[0-9a-f]{6}$/i.test(c) ? c : d);
  const boxes: Record<string, { cx: number; cy: number; hw: number; hh: number; ref: any }> = {};
  const events: { t: number; run: () => Generator<any, void, any> }[] = [];
  const arrows: El[] = [];
  const containers: { id: string; cx: number; cy: number; hw: number; hh: number }[] = [];

  // everything but the heading lives in one stage so that a sparse diagram can be scaled up to fill the frame
  const stage = createRef<Node>(); view.add(<Node ref={stage} />);
  // containers first so that everything else sits on top of them
  const ordered = [...els].sort((a, b) => (a.type === 'container' ? 0 : 1) - (b.type === 'container' ? 0 : 1));
  ordered.forEach((e, idx) => {
    const id = e.id || `e${idx}`;
    if (e.type === 'arrow') { arrows.push({ ...e, id }); return; }
    const cx = ((e.x ?? 50) / 100 - 0.5) * W, cy = ((e.y ?? 50) / 100 - 0.5) * H;
    const ref = createRef<Node>();
    const icon = plannerIcon(e.icon);
    let hw = 90 * u, hh = 60 * u;
    let node: any = null;
    const tint = col(e.color);
    if (e.type === 'tile') {
      hw = (e.w ? (e.w / 100) * W : 168 * u) / 2; hh = (e.h ? (e.h / 100) * H : 118 * u) / 2;
      node = (
        <Rect ref={ref} layout direction="column" alignItems="center" justifyContent="center" gap={8 * u} width={hw * 2} height={hh * 2} x={cx} y={cy} radius={16 * u} fill={tint + '2e'} stroke={tint} lineWidth={3 * u} opacity={0}>
          {icon ? <Rect width={46 * u} height={46 * u}><Glyph name={icon} color={tint} size={46 * u} /></Rect> : null}
          <Txt text={e.text || ''} fill="#f8fafc" fontFamily={FONT} fontSize={Math.min(22, Math.max(15, (hw * 2 - 24 * u) / Math.max(6, (e.text || '').length) / u * 1.7)) * u} fontWeight={700} textWrap width={hw * 2 - 20 * u} textAlign="center" />
        </Rect>
      );
    } else if (e.type === 'actor') {
      const t = e.color ? col(e.color) : (e.icon === 'agent' || e.icon === 'robot') ? '#f472b6' : '#60a5fa'; hw = 62 * u; hh = 70 * u;
      node = (
        <Rect ref={ref} layout direction="column" alignItems="center" gap={8 * u} x={cx} y={cy} opacity={0}>
          <Circle size={86 * u} fill={t + '2e'} stroke={t} lineWidth={3 * u}><Glyph name={icon || 'user'} color={t} size={46 * u} /></Circle>
          <Txt text={e.text || ''} fill="#eaf4ff" fontFamily={FONT} fontSize={20 * u} fontWeight={700} />
        </Rect>
      );
    } else if (e.type === 'button') {
      const t = e.color ? col(e.color, '#0f766e') : '#0f766e'; const w = Math.max(150 * u, (e.text || '').length * 13 * u + 44 * u); hw = w / 2; hh = 26 * u;
      node = <Rect ref={ref} layout alignItems="center" justifyContent="center" width={w} height={52 * u} x={cx} y={cy} radius={12 * u} fill={t} opacity={0}><Txt text={e.text || ''} fill="#ffffff" fontFamily={FONT} fontSize={21 * u} fontWeight={700} /></Rect>;
    } else if (e.type === 'heading') {
      hw = Math.max(120 * u, (e.text || '').length * 13 * u); hh = 30 * u;
      node = <Txt ref={ref} text={e.text || ''} x={cx} y={cy} fill="#eaf4ff" fontFamily={FONT} fontSize={40 * u} fontWeight={800} opacity={0} />;
    } else if (e.type === 'pill') {
      const t = col(e.color, '#a78bfa'); const w = Math.max(80 * u, (e.text || '').length * 11.5 * u + 30 * u); hw = w / 2; hh = 17 * u; const solid = (e.style || 'solid') !== 'outline';
      node = <Rect ref={ref} layout alignItems="center" justifyContent="center" width={w} height={34 * u} x={cx} y={cy} radius={17 * u} fill={solid ? t : null} stroke={t} lineWidth={2 * u} opacity={0}><Txt text={e.text || ''} fill={solid ? '#0b1520' : t} fontFamily={FONT} fontSize={17 * u} fontWeight={700} /></Rect>;
    } else if (e.type === 'label') {
      const sz = e.style === 'large' ? 34 : e.style === 'small' ? 17 : 24; hw = Math.max(40 * u, (e.text || '').length * sz * 0.3 * u); hh = sz * 0.7 * u;
      node = <Txt ref={ref} text={e.text || ''} x={cx} y={cy} fill={col(e.color, '#cfe6f5')} fontFamily={FONT} fontSize={sz * u} fontWeight={600} opacity={0} />;
    } else if (e.type === 'note' || e.type === 'quote') {
      const w = e.w ? (e.w / 100) * W : 330 * u; hw = w / 2; hh = 40 * u; const t = e.type === 'note' ? '#34d399' : '#fbbf24';
      node = <Rect ref={ref} layout alignItems="center" justifyContent="center" padding={[14 * u, 18 * u]} width={w} x={cx} y={cy} radius={10 * u} fill={t + '1f'} stroke={t} lineWidth={2 * u} opacity={0}><Txt text={(e.type === 'quote' ? '“' + (e.text || '') + '”' : e.text || '')} fill="#f1f5f9" fontFamily={FONT} fontSize={21 * u} fontStyle={e.type === 'quote' ? 'italic' : 'normal'} textWrap width={w - 40 * u} textAlign="center" /></Rect>;
    } else if (e.type === 'card') {
      const w = e.w ? (e.w / 100) * W : 300 * u; const lines = (e.lines || []).slice(0, 4); hw = w / 2; hh = (34 + lines.length * 28) * u;
      node = <Rect ref={ref} layout direction="column" alignItems="center" gap={8 * u} padding={[16 * u, 20 * u]} width={w} x={cx} y={cy} radius={10 * u} fill="#0d2a3d" stroke="#3aa0d0" lineWidth={2 * u} opacity={0}>
        <Rect layout direction="row" alignItems="center" gap={10 * u}>{icon ? <Rect width={30 * u} height={30 * u}><Glyph name={icon} color="#4fb3e8" size={30 * u} /></Rect> : null}<Txt text={e.title || e.text || ''} fill="#4fb3e8" fontFamily={FONT} fontSize={24 * u} fontWeight={700} /></Rect>
        {lines.map(l => <Txt text={'•  ' + l} fill="#cfe6f5" fontFamily={FONT} fontSize={20 * u} textWrap width={w - 56 * u} textAlign="left" />)}
      </Rect>;
    } else if (e.type === 'container') {
      const w = ((e.w ?? 30) / 100) * W, h = ((e.h ?? 60) / 100) * H; hw = w / 2; hh = h / 2;
      node = <Rect ref={ref} width={w} height={h} x={cx} y={cy} radius={16 * u} fill={'#a78bfa14'} stroke="#a78bfa" lineWidth={2.5 * u} lineDash={[12 * u, 8 * u]} opacity={0}><Txt text={e.text || e.title || ''} fill="#c4b5fd" fontFamily={FONT} fontSize={20 * u} fontWeight={700} y={-h / 2 + 22 * u} /></Rect>;
    } else if (e.type === 'message') {
      const w = e.w ? (e.w / 100) * W : 360 * u; hw = w / 2; hh = 26 * u; const right = (e.style || 'right') !== 'left';
      node = <Rect ref={ref} layout alignItems="center" justifyContent="center" padding={[12 * u, 18 * u]} width={w} x={cx} y={cy} radius={14 * u} fill="#14304a" stroke="#4fb3e8" lineWidth={2 * u} opacity={0}><Txt text={(right ? '→  ' : '←  ') + (e.text || '')} fill="#e8f4fb" fontFamily={FONT} fontSize={20 * u} textWrap width={w - 44 * u} textAlign="center" /></Rect>;
    } else if (e.type === 'icon') {
      hw = hh = 36 * u; const t = col(e.color, '#fbbf24');
      node = <Node ref={ref} x={cx} y={cy} opacity={0}><Glyph name={icon || 'sparkles'} color={t} size={72 * u} /></Node>;
    }
    if (!node) return;
    stage().add(node);
    ref().scale(0.88);
    boxes[id] = { cx, cy, hw, hh, ref };
    if (e.type === 'container') containers.push({ id, cx, cy, hw, hh });
    events.push({ t: at(e.at), run: function* () { yield* all(ref().opacity(1, 0.4), ref().scale(1, 0.4, easeOutCubic)); } });
    for (const h of e.highlightAt || []) events.push({ t: at(h), run: function* () { yield* ref().scale(1.08, 0.25, easeOutCubic); yield* ref().scale(1, 0.4, easeOutCubic); } });
    if (e.dimAt !== undefined) events.push({ t: at(e.dimAt), run: function* () { yield* ref().opacity(0.3, 0.5); } });
  });


  // ---- automatic layout fix: the planner places elements as percentages, so two of them can end up on top of each other, or a container's members under its title.
  // Members are kept below the container title, overlapping elements are pushed apart along the axis of least overlap, everything stays inside the frame; nothing moves when there is no overlap.
  {
    const GAP = 14 * u, TOP = -H / 2 + 84 * u;
    const ids = Object.keys(boxes).filter(id => !containers.some(c => c.id === id));
    const parent: Record<string, { cy: number; hh: number; cx: number; hw: number } | undefined> = {};
    ids.forEach(id => { parent[id] = containers.find(c => Math.abs(boxes[id].cx - c.cx) < c.hw && Math.abs(boxes[id].cy - c.cy) < c.hh); });
    ids.forEach(id => { const c = parent[id], b = boxes[id]; if (c) b.cy = Math.max(b.cy, c.cy - c.hh + 46 * u + b.hh); });         // below the container title
    for (let it = 0; it < 60; it++) {
      let moved = false;
      for (let i = 0; i < ids.length; i++) for (let j = i + 1; j < ids.length; j++) {
        const a = boxes[ids[i]], b = boxes[ids[j]];
        const ox = a.hw + b.hw + GAP - Math.abs(b.cx - a.cx), oy = a.hh + b.hh + GAP - Math.abs(b.cy - a.cy);
        if (ox <= 0 || oy <= 0) continue;
        moved = true;
        if (ox < oy) { const d = (b.cx >= a.cx ? 1 : -1) * ox / 2; a.cx -= d; b.cx += d; } else { const d = (b.cy >= a.cy ? 1 : -1) * oy / 2; a.cy -= d; b.cy += d; }
      }
      ids.forEach(id => {
        const b = boxes[id], c = parent[id];
        b.cx = Math.min(W / 2 - b.hw - 10 * u, Math.max(-W / 2 + b.hw + 10 * u, b.cx));
        b.cy = Math.min(H / 2 - b.hh - 10 * u, Math.max(TOP + b.hh, b.cy));
        if (c) { b.cx = Math.min(c.cx + c.hw - b.hw - 6 * u, Math.max(c.cx - c.hw + b.hw + 6 * u, b.cx)); b.cy = Math.min(c.cy + c.hh - b.hh - 6 * u, Math.max(c.cy - c.hh + 46 * u + b.hh, b.cy)); }
      });
      if (!moved) break;
    }
    ids.forEach(id => boxes[id].ref().position([boxes[id].cx, boxes[id].cy]));
  }

  // arrows: drawn from the edge of one box to the edge of the other, with an optional label and a dot sent along the line
  arrows.forEach(a => {
    const A = a.from ? boxes[a.from] : undefined, B = a.to ? boxes[a.to] : undefined;
    if (!A || !B) return;
    const dx = B.cx - A.cx, dy = B.cy - A.cy, len = Math.max(1, Math.hypot(dx, dy)), ux = dx / len, uy = dy / len;
    const edge = (b: { hw: number; hh: number }) => Math.min(Math.abs(ux) > 1e-6 ? b.hw / Math.abs(ux) : 1e9, Math.abs(uy) > 1e-6 ? b.hh / Math.abs(uy) : 1e9) + 10 * u;
    const sx = A.cx + ux * edge(A), sy = A.cy + uy * edge(A), ex = B.cx - ux * edge(B), ey = B.cy - uy * edge(B);
    const line = createRef<Line>(); const dot = createRef<Circle>(); const lab = createRef<Rect>();
    const dashed = (a.style || 'dashed') !== 'solid';
    stage().add(<Line ref={line} points={[[sx, sy], [ex, ey]]} stroke="#67e8f9" lineWidth={3.5 * u} lineDash={dashed ? [12 * u, 9 * u] : []} endArrow startArrow={a.style === 'both'} arrowSize={15 * u} end={0} />);
    stage().add(<Circle ref={dot} size={13 * u} fill="#ffffff" shadowColor="#67e8f9" shadowBlur={16 * u} x={sx} y={sy} opacity={0} />);
    if (a.text) stage().add(<Rect ref={lab} layout alignItems="center" justifyContent="center" padding={[4 * u, 10 * u]} x={(sx + ex) / 2} y={(sy + ey) / 2 - 18 * u} radius={10 * u} fill="#0b1b28" stroke="#67e8f9" lineWidth={1.5 * u} opacity={0}><Txt text={a.text} fill="#bdf4ff" fontFamily={FONT} fontSize={16 * u} fontWeight={600} /></Rect>);
    events.push({ t: Math.max(at(a.at), 0), run: function* () {
      yield* all(line().end(1, 0.55, easeOutCubic), a.text ? lab().opacity(1, 0.4) : waitFor(0));
      dot().position([sx, sy]); yield* dot().opacity(1, 0.08); yield* dot().position([ex, ey], 0.6); yield* dot().opacity(0, 0.12);
    } });
  });

  { // zoom to fit: a diagram that covers little of the frame is scaled up (at most 1.5x) about the middle of what it covers
    const bs = Object.entries(boxes).filter(([id]) => !containers.some(c => c.id === id)).map(([, b]) => b);
    if (bs.length >= 2) {
      const x0 = Math.min(...bs.map(b => b.cx - b.hw)), x1 = Math.max(...bs.map(b => b.cx + b.hw)), y0 = Math.min(...bs.map(b => b.cy - b.hh)), y1 = Math.max(...bs.map(b => b.cy + b.hh));
      const k = Math.min(1.5, (0.86 * W) / Math.max(1, x1 - x0), (0.62 * H) / Math.max(1, y1 - y0));
      if (k > 1.12) { const mx = (x0 + x1) / 2, my = (y0 + y1) / 2; stage().scale(k); stage().position([mx * (1 - k), my * (1 - k) + 20 * u]); }
    }
  }

  events.sort((p, q) => p.t - q.t);
  let now = 0;
  for (const ev of events) {
    if (ev.t > now) { yield* waitFor(ev.t - now); now = ev.t; }
    yield ev.run();                 // started in parallel: later events are not held up by an animation still running
  }
  if (total > now) yield* waitFor(total - now);
});
