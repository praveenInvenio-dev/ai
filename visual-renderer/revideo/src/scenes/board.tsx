import { makeScene2D, Rect, Txt } from '@revideo/2d';
import { all, createRef, easeOutCubic, tween, useScene, waitFor } from '@revideo/core';
import { Glyph } from '../Glyph';
import { pickIcon } from '../icons';

const FONT = 'Lato, Arial, Helvetica, "DejaVu Sans", sans-serif';
const MONO = '"DejaVu Sans Mono", monospace';
type El = { type: string; title?: string; lines?: string[]; text?: string; x?: number; y?: number; w?: number; at?: number };

/** Cards, compare tables and bars drawn the way the native renderer draws them, but ANIMATED: each element slides in when the narration reaches its sentence,
 *  compare rows follow one by one, bars grow while their numbers count up. `starts` = sentence start times (seconds); element `at` = the sentence it belongs to. */
export default makeScene2D('board', function* (view) {
  const vars = useScene().variables;
  const bg = String(vars.get('bg', '#0c2433')());
  const kicker = String(vars.get('kicker', '')());
  const title = String(vars.get('title', '')());
  const total = Math.max(2, Number(vars.get('total', 6)()));
  let els: El[] = []; let starts: number[] = [];
  try { els = JSON.parse(String(vars.get('elements', '[]')())); } catch { els = []; }
  try { starts = JSON.parse(String(vars.get('starts', '[]')())); } catch { starts = []; }
  els = els.slice(0, 6);
  view.fill(bg);
  const W = view.size().x, H = view.size().y, u = W / 1280;
  view.add(<Txt text={kicker} fill="#8a9bb0" fontFamily={MONO} fontSize={15 * u} y={-H / 2 + 22 * u} />);
  view.add(<Txt text={title} fill="#eaf4ff" fontFamily={FONT} fontSize={36 * u} fontWeight={400} y={-H / 2 + 52 * u} />);

  const when = (e: El) => Math.max(0, (starts.length ? starts[Math.min(e.at ?? 0, starts.length - 1)] : (e.at ?? 0) * 2.5) - 0.15);
  const part = (s: string, i: number) => String(s || '').split('|')[i] ?? '';
  const num = (s: string) => { const m = String(s).match(/-?\d+(?:[.,]\d+)?/); return m ? parseFloat(m[0].replace(',', '.')) : 0; };
  const nodes: { ref: ReturnType<typeof createRef<Rect>>; at: number; run: () => Generator<any, void, any> }[] = [];

  els.forEach((e) => {
    const cx = ((e.x ?? 50) / 100 - 0.5) * W, cy = ((e.y ?? 46) / 100 - 0.5) * H, pw = ((e.w ?? 60) / 100) * W;
    const ref = createRef<Rect>();
    if (e.type === 'card' || e.type === 'tile' || e.type === 'note') {
      const lines = (e.lines && e.lines.length ? e.lines : e.text ? [e.text] : []).slice(0, 4);
      view.add(
        <Rect ref={ref} layout direction="column" alignItems="center" gap={10 * u} padding={[18 * u, 22 * u]} width={pw} x={cx} y={cy} radius={10 * u} fill="#0d2a3d" stroke="#3aa0d0" lineWidth={2 * u} opacity={0}>
          <Rect layout direction="row" alignItems="center" gap={12 * u}>
            {pickIcon((e.title || '') + ' ' + lines.join(' ')) ? <Rect width={34 * u} height={34 * u}><Glyph name={pickIcon((e.title || '') + ' ' + lines.join(' ')) as string} color="#4fb3e8" size={34 * u} /></Rect> : null}
            <Txt text={e.title || ''} fill="#4fb3e8" fontFamily={FONT} fontSize={26 * u} fontWeight={700} />
          </Rect>
          {lines.map(l => <Txt text={'•  ' + l} fill="#cfe6f5" fontFamily={FONT} fontSize={22 * u} textWrap width={pw - 60 * u} textAlign="left" />)}
        </Rect>,
      );
      nodes.push({ ref, at: when(e), run: function* () { yield* all(ref().opacity(1, 0.5), ref().y(cy, 0.5, easeOutCubic), ref().scale(1, 0.5, easeOutCubic)); } });
      ref().y(cy + 34 * u); ref().scale(0.94);
    } else if (e.type === 'compare') {
      const head = String(e.title || '|').split('|');
      const rows = (e.lines || []).slice(0, 5);
      const rowRefs = rows.map(() => createRef<Rect>());
      view.add(
        <Rect ref={ref} layout direction="column" gap={14 * u} padding={[20 * u, 26 * u]} width={pw} x={cx} y={cy} radius={10 * u} fill="#24667f" opacity={0}>
          <Rect layout direction="row" width={pw - 52 * u}>
            <Txt text="" width={(pw - 52 * u) * 0.34} />
            <Txt text={head[0] || ''} fill="#5cc0f0" fontFamily={FONT} fontSize={26 * u} fontWeight={700} width={(pw - 52 * u) * 0.33} />
            <Txt text={head[1] || ''} fill="#7ddc7a" fontFamily={FONT} fontSize={26 * u} fontWeight={700} width={(pw - 52 * u) * 0.33} />
          </Rect>
          {rows.map((r, i) => (
            <Rect ref={rowRefs[i]} layout direction="row" width={pw - 52 * u} opacity={0}>
              <Txt text={part(r, 0)} fill="#b9d3df" fontFamily={FONT} fontSize={22 * u} width={(pw - 52 * u) * 0.34} />
              <Txt text={part(r, 1)} fill="#ffffff" fontFamily={FONT} fontSize={23 * u} fontWeight={700} width={(pw - 52 * u) * 0.33} />
              <Txt text={part(r, 2)} fill="#ffffff" fontFamily={FONT} fontSize={23 * u} fontWeight={700} width={(pw - 52 * u) * 0.33} />
            </Rect>
          ))}
        </Rect>,
      );
      nodes.push({ ref, at: when(e), run: function* () {
        yield* all(ref().opacity(1, 0.45), ref().y(cy, 0.45, easeOutCubic), ref().scale(1, 0.45, easeOutCubic));
        for (const r of rowRefs) { yield* all(r().opacity(1, 0.3), r().x(0, 0.3, easeOutCubic)); yield* waitFor(0.12); }
      } });
      ref().y(cy + 34 * u); ref().scale(0.94); rowRefs.forEach(r => r().x(-30 * u));
    } else if (e.type === 'bars') {
      const rows = (e.lines || []).slice(0, 5).map(l => ({ label: part(l, 0), value: num(part(l, 1)), unit: part(l, 2) }));
      const max = Math.max(1, ...rows.map(r => r.value));
      const trackW = pw - 52 * u - 0.32 * pw - 170 * u - 44 * u;   // label + track + number + the two gaps must fit one row
      const fills = rows.map(() => createRef<Rect>()); const nums = rows.map(() => createRef<Txt>());
      view.add(
        <Rect ref={ref} layout direction="column" gap={22 * u} padding={[22 * u, 26 * u]} width={pw} x={cx} y={cy} radius={10 * u} fill="#24667f" opacity={0}>
          <Txt text={e.title || ''} fill="#ffffff" fontFamily={FONT} fontSize={26 * u} fontWeight={700} />
          {rows.map((r, i) => (
            <Rect layout direction="row" alignItems="center" gap={18 * u} width={pw - 52 * u}>
              <Txt text={r.label} fill="#d8e9f2" fontFamily={FONT} fontSize={22 * u} width={0.32 * pw} textWrap />
              <Rect width={trackW} height={14 * u} radius={7 * u} fill="#27272a"><Rect ref={fills[i]} width={8 * u} height={14 * u} radius={7 * u} fill={i % 2 ? '#7ddc7a' : '#ffffff'} x={-(trackW - 8 * u) / 2} /></Rect>
              <Txt ref={nums[i]} text={'0' + (r.unit ? ' ' + r.unit : '')} fill="#ffffff" fontFamily={MONO} fontSize={22 * u} fontWeight={700} width={170 * u} />
            </Rect>
          ))}
        </Rect>,
      );
      nodes.push({ ref, at: when(e), run: function* () {
        yield* all(ref().opacity(1, 0.45), ref().y(cy, 0.45, easeOutCubic), ref().scale(1, 0.45, easeOutCubic));
        // the bars grow and the numbers count up in ONE tween that sets every value on every frame (reactive text does not refresh while rendering)
        const target = rows.map(r => Math.max(8 * u, trackW * (r.value / max)));
        yield* tween(1.1, v => {
          const f = easeOutCubic(v);
          rows.forEach((r, i) => {
            const w = 8 * u + (target[i] - 8 * u) * f;
            fills[i]().width(w); fills[i]().x(-(trackW - w) / 2);
            nums[i]().text(String(Math.round(r.value * f)) + (r.unit ? ' ' + r.unit : ''));
          });
        });
      } });
      ref().y(cy + 34 * u); ref().scale(0.94);
    }
  });

  nodes.sort((a, b) => a.at - b.at);
  let now = 0;
  for (const n of nodes) {
    if (n.at > now) { yield* waitFor(n.at - now); now = n.at; }
    yield* n.run();            // animations are short (<2 s); a later element simply starts when its sentence does or right after
    now += 1.2;
  }
  if (total > now) yield* waitFor(total - now);
});
