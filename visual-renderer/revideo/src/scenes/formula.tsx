import { makeScene2D, Latex, Rect, Txt } from '@revideo/2d';
import { all, createRef, easeOutCubic, useScene, waitFor } from '@revideo/core';

const FONT = 'Lato, Arial, Helvetica, "DejaVu Sans", sans-serif';
const MONO = '"DejaVu Sans Mono", monospace';
type F = { tex: string; label?: string; caption?: string; at?: number };

/** Formulas typeset with LaTeX (MathJax, bundled: no network) that appear one by one as the narration reaches them. Each formula sits in its own panel with an optional label above and a
 *  plain-words caption below; the previous formulas stay but dim, the newest one glows once. `starts` = sentence start times (seconds). */
export default makeScene2D('formula', function* (view) {
  const vars = useScene().variables;
  const bg = String(vars.get('bg', '#0c2433')());
  const kicker = String(vars.get('kicker', '')());
  const title = String(vars.get('title', '')());
  const total = Math.max(2, Number(vars.get('total', 8)()));
  let fs: F[] = []; let starts: number[] = [];
  try { fs = JSON.parse(String(vars.get('formulas', '[]')())); } catch { fs = []; }
  try { starts = JSON.parse(String(vars.get('starts', '[]')())); } catch { starts = []; }
  fs = fs.slice(0, 4);
  view.fill(bg);
  const W = view.size().x, H = view.size().y, u = W / 1280;
  view.add(<Txt text={kicker} fill="#8a9bb0" fontFamily={MONO} fontSize={15 * u} y={-H / 2 + 22 * u} />);
  view.add(<Txt text={title} fill="#eaf4ff" fontFamily={FONT} fontSize={36 * u} fontWeight={400} y={-H / 2 + 52 * u} />);

  const at = (n?: number) => Math.max(0, (starts.length ? starts[Math.min(Math.max(0, n ?? 0), starts.length - 1)] : (n ?? 0) * 3) - 0.15);
  const n = fs.length, slot = Math.min(200 * u, (H - 150 * u) / Math.max(1, n));
  const panels = fs.map(() => createRef<Rect>());
  fs.forEach((f, i) => {
    const y = (i - (n - 1) / 2) * slot + 30 * u;
    view.add(
      <Rect ref={panels[i]} layout direction="column" alignItems="center" gap={8 * u} padding={[18 * u, 40 * u]} x={0} y={y} radius={14 * u} fill="#0d2a3d" stroke="#3aa0d0" lineWidth={2 * u} opacity={0}>
        {f.label ? <Txt text={f.label} fill="#4fb3e8" fontFamily={FONT} fontSize={20 * u} fontWeight={700} /> : null}
        <Latex tex={'{\\color{#f1f5f9} ' + f.tex + '}'} height={(n > 2 ? 70 : n === 2 ? 96 : 120) * u} />
        {f.caption ? <Txt text={f.caption} fill="#9fb8c9" fontFamily={FONT} fontSize={19 * u} /> : null}
      </Rect>,
    );
    panels[i]().scale(0.92);
  });

  let now = 0;
  for (let i = 0; i < n; i++) {
    const t = at(fs[i].at ?? i);
    if (t > now) { yield* waitFor(t - now); now = t; }
    if (i > 0) yield panels[i - 1]().opacity(0.55, 0.5);
    yield* all(panels[i]().opacity(1, 0.5), panels[i]().scale(1, 0.5, easeOutCubic));
    yield panels[i]().scale(1.04, 0.3).to(1, 0.4);
    now += 0.5;
  }
  if (total > now) yield* waitFor(total - now);
});
