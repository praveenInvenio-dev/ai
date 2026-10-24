import { makeScene2D, Code, Circle, LezerHighlighter, Rect, Txt, lines } from '@revideo/2d';
import { HighlightStyle } from '@codemirror/language';
import { tags as t } from '@lezer/highlight';
import { parser as javaParser } from '@lezer/java';
import { parser as jsParser } from '@lezer/javascript';
import { parser as pyParser } from '@lezer/python';
import { parser as cppParser } from '@lezer/cpp';
import { parser as jsonParser } from '@lezer/json';
import { DEFAULT, all, createRef, easeOutCubic, useScene, waitFor } from '@revideo/core';

const FONT = 'Lato, Arial, Helvetica, "DejaVu Sans", sans-serif';
const MONO = '"DejaVu Sans Mono", monospace';

/** Dark editor colours (keywords purple, types amber, strings green, numbers orange, comments grey, functions blue). */
const DARK = HighlightStyle.define([
  { tag: [t.keyword, t.modifier, t.controlKeyword, t.operatorKeyword, t.definitionKeyword], color: '#c792ea' },
  { tag: [t.typeName, t.className, t.namespace], color: '#ffcb6b' },
  { tag: [t.string, t.special(t.string)], color: '#c3e88d' },
  { tag: [t.number, t.bool, t.null, t.atom], color: '#f78c6c' },
  { tag: [t.comment, t.lineComment, t.blockComment], color: '#637777', fontStyle: 'italic' },
  { tag: [t.function(t.variableName), t.function(t.propertyName), t.definition(t.function(t.variableName))], color: '#82aaff' },
  { tag: [t.operator, t.punctuation, t.bracket], color: '#89ddff' },
  { tag: [t.variableName, t.propertyName, t.name], color: '#d6deeb' },
]);
/** The parser for a file name (java / js / ts / py / c / cpp / json); unknown extensions use the JavaScript grammar, which reads C-like code well enough. */
function highlighterFor(file: string) {
  const ext = (file.split('.').pop() || '').toLowerCase();
  const parser = ext === 'java' ? javaParser : ext === 'py' ? pyParser : ['c', 'h', 'cpp', 'hpp', 'cc'].includes(ext) ? cppParser : ext === 'json' ? jsonParser : jsParser;
  return new LezerHighlighter(parser, DARK);
}

/** A code window that is TYPED while the narration explains it: the code is split into one group of lines per sentence, each group types in when its sentence starts
 *  and its lines are highlighted (the rest dims), then everything lights up at the end. `starts` = sentence start times in seconds. */
export default makeScene2D('code', function* (view) {
  const vars = useScene().variables;
  const bg = String(vars.get('bg', '#0c2433')());
  const kicker = String(vars.get('kicker', '')());
  const title = String(vars.get('title', '')());
  const file = String(vars.get('file', 'Example.java')());
  const total = Math.max(2, Number(vars.get('total', 8)()));
  let src = String(vars.get('code', '')()); let starts: number[] = [];
  try { starts = JSON.parse(String(vars.get('starts', '[]')())); } catch { starts = []; }
  const codeLines = src.replace(/\r/g, '').split('\n').slice(0, 14);
  view.fill(bg);
  const W = view.size().x, H = view.size().y, u = W / 1280;
  view.add(<Txt text={kicker} fill="#8a9bb0" fontFamily={MONO} fontSize={15 * u} y={-H / 2 + 22 * u} />);
  view.add(<Txt text={title} fill="#eaf4ff" fontFamily={FONT} fontSize={36 * u} fontWeight={400} y={-H / 2 + 52 * u} />);

  const pw = W * 0.8, ph = Math.min(H * 0.62, (codeLines.length * 36 + 90) * u), cy = 20 * u;
  const win = createRef<Rect>(); const code = createRef<Code>();
  view.add(
    <Rect ref={win} width={pw} height={ph} y={cy} radius={14 * u} fill="#0b1520" stroke="#2f4a63" lineWidth={2 * u} opacity={0}>
      <Rect width={pw} height={42 * u} y={-ph / 2 + 21 * u} fill="#12202e" radius={[14 * u, 14 * u, 0, 0]} />
      <Circle size={13 * u} fill="#ff5f56" x={-pw / 2 + 26 * u} y={-ph / 2 + 21 * u} />
      <Circle size={13 * u} fill="#ffbd2e" x={-pw / 2 + 50 * u} y={-ph / 2 + 21 * u} />
      <Circle size={13 * u} fill="#27c93f" x={-pw / 2 + 74 * u} y={-ph / 2 + 21 * u} />
      <Txt text={file} fill="#8fa5b8" fontFamily={MONO} fontSize={16 * u} y={-ph / 2 + 21 * u} />
      <Code ref={code} code="" highlighter={highlighterFor(file)} fontFamily={MONO} fontSize={25 * u} x={-pw / 2 + 36 * u} offsetX={-1} y={22 * u} />
    </Rect>,
  );
  win().scale(0.96);

  // groups of lines, one per sentence (more sentences than lines: the last lines are shared)
  const n = Math.max(1, Math.min(starts.length || 3, codeLines.length));
  const groups: [number, number][] = [];
  for (let g = 0; g < n; g++) groups.push([Math.floor((g * codeLines.length) / n), Math.floor(((g + 1) * codeLines.length) / n) - 1]);
  const at = (g: number) => Math.max(0, (starts.length ? starts[Math.min(g, starts.length - 1)] : g * 2.5) - 0.15);

  let now = 0;
  yield* waitFor(Math.max(0, at(0)) - now); now = Math.max(now, at(0));
  yield* all(win().opacity(1, 0.4), win().scale(1, 0.4, easeOutCubic)); now += 0.4;
  for (let g = 0; g < groups.length; g++) {
    if (at(g) > now) { yield* waitFor(at(g) - now); now = at(g); }
    const text = codeLines.slice(0, groups[g][1] + 1).join('\n');
    yield* code().code(text, 0.9); now += 0.9;                       // the new lines are typed in
    yield* code().selection(lines(groups[g][0], groups[g][1]), 0.35); now += 0.35;   // and highlighted while the voice explains them
  }
  if (total > now + 0.5) { yield* waitFor(Math.max(0, total - now - 1.2)); now = total - 1.2; }
  yield* code().selection(DEFAULT, 0.5);                                   // finish with the whole listing lit
  if (total > now + 0.5) yield* waitFor(total - now - 0.5);
});
