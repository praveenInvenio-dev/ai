import { makeScene2D, Circle, Line, Node, Path, Rect, Txt } from '@revideo/2d';
import { createRef, tween, useScene, waitFor } from '@revideo/core';

const FONT = '"DejaVu Sans", Arial, Helvetica, sans-serif';
const MONO = '"DejaVu Sans Mono", monospace';
type Speech = { text: string; at?: number };

/** A talking mascot: Ollie the owl. He bobs and breathes, blinks, flaps his wings (wave / wow), raises or lowers his brows for the mood (happy | wow | thinking | wave) and his beak
 *  opens with the narration: `mouth` is a per-frame loudness envelope (0..1 at 30 fps) of the scene's audio; without it he talks during each sentence (`starts`).
 *  The speech bubble types out each line when its sentence begins. */
export default makeScene2D('mascot', function* (view) {
  const vars = useScene().variables;
  const bg = String(vars.get('bg', '#fff7e6')());
  const light = Number(vars.get('light', 1)()) === 1;
  const kicker = String(vars.get('kicker', '')());
  const title = String(vars.get('title', '')());
  const name = String(vars.get('name', 'Ollie')());
  const mood = String(vars.get('mood', 'happy')());
  const total = Math.max(2, Number(vars.get('total', 8)()));
  let speeches: Speech[] = []; let starts: number[] = []; let mouth: number[] = [];
  try { speeches = JSON.parse(String(vars.get('speeches', '[]')())); } catch { speeches = []; }
  try { starts = JSON.parse(String(vars.get('starts', '[]')())); } catch { starts = []; }
  try { mouth = JSON.parse(String(vars.get('mouth', '[]')())); } catch { mouth = []; }
  view.fill(bg);
  const W = view.size().x, H = view.size().y, u = W / 1280;
  const ink = light ? '#2a2540' : '#eaf4ff', sub = light ? '#7a7396' : '#8a9bb0';
  view.add(<Txt text={kicker} fill={sub} fontFamily={MONO} fontSize={15 * u} y={-H / 2 + 22 * u} />);
  view.add(<Txt text={title} fill={ink} fontFamily={FONT} fontSize={36 * u} fontWeight={400} y={-H / 2 + 52 * u} />);

  const BROWN = '#8a5a2b', DARK = '#6b4220', CREAM = '#fbe7be', FACE = '#fff3d6', ORANGE = '#ff9d2e';
  const bx = -W * 0.22, by = H * 0.08, S = 1.18 * u;           // character size
  const root = createRef<Node>(), lidL = createRef<Circle>(), lidR = createRef<Circle>(), mouthC = createRef<Circle>(), wingL = createRef<Node>(), wingR = createRef<Node>();
  const browL = createRef<Line>(), browR = createRef<Line>(), pupL = createRef<Circle>(), pupR = createRef<Circle>(), head = createRef<Node>();
  view.add(
    <Node ref={root} x={bx} y={by} scale={0}>
      {/* soft ground shadow */}
      <Circle width={230 * S} height={34 * S} y={176 * S} fill={light ? '#e9d7b4' : '#06121c'} opacity={0.7} />
      {/* feet */}
      <Rect width={64 * S} height={26 * S} radius={13 * S} fill={ORANGE} x={-52 * S} y={168 * S} />
      <Rect width={64 * S} height={26 * S} radius={13 * S} fill={ORANGE} x={52 * S} y={168 * S} />
      {/* wings (behind the body) */}
      <Node ref={wingL} x={-122 * S} y={-5 * S} rotation={6}><Circle width={74 * S} height={170 * S} fill={DARK} y={50 * S} /></Node>
      <Node ref={wingR} x={122 * S} y={-5 * S} rotation={-6}><Circle width={74 * S} height={170 * S} fill={DARK} y={50 * S} /></Node>
      {/* body + belly with feather marks */}
      <Circle width={250 * S} height={300 * S} fill={BROWN} y={20 * S} />
      <Circle width={166 * S} height={206 * S} fill={CREAM} y={62 * S} />
      <Path data="M-34 40 q17 14 34 0 q17 14 34 0" stroke="#e0b878" lineWidth={4 * S} lineCap="round" y={0} scale={S} />
      <Path data="M-34 78 q17 14 34 0 q17 14 34 0" stroke="#e0b878" lineWidth={4 * S} lineCap="round" y={0} scale={S} />
      <Path data="M-30 116 q15 12 30 0 q15 12 30 0" stroke="#e0b878" lineWidth={4 * S} lineCap="round" y={0} scale={S} />
      {/* head */}
      <Node ref={head} y={-96 * S}>
        <Path data="M-84 -34 L-112 -96 L-48 -56 Z" fill={DARK} scale={S} />
        <Path data="M84 -34 L112 -96 L48 -56 Z" fill={DARK} scale={S} />
        <Circle width={206 * S} height={178 * S} fill={BROWN} y={0} />
        <Circle width={104 * S} height={104 * S} fill={FACE} x={-50 * S} y={2 * S} />
        <Circle width={104 * S} height={104 * S} fill={FACE} x={50 * S} y={2 * S} />
        <Circle width={74 * S} height={74 * S} fill="#ffffff" x={-50 * S} y={2 * S} />
        <Circle width={74 * S} height={74 * S} fill="#ffffff" x={50 * S} y={2 * S} />
        <Circle ref={pupL} width={36 * S} height={36 * S} fill="#241a12" x={-50 * S} y={4 * S}><Circle width={11 * S} height={11 * S} fill="#ffffff" x={7 * S} y={-8 * S} /></Circle>
        <Circle ref={pupR} width={36 * S} height={36 * S} fill="#241a12" x={50 * S} y={4 * S}><Circle width={11 * S} height={11 * S} fill="#ffffff" x={7 * S} y={-8 * S} /></Circle>
        <Circle ref={lidL} width={78 * S} height={0.01} fill={BROWN} x={-50 * S} y={-35 * S} />
        <Circle ref={lidR} width={78 * S} height={0.01} fill={BROWN} x={50 * S} y={-35 * S} />
        <Line ref={browL} points={[[-78 * S, -44 * S], [-22 * S, -44 * S]]} stroke={DARK} lineWidth={7 * S} lineCap="round" />
        <Line ref={browR} points={[[22 * S, -44 * S], [78 * S, -44 * S]]} stroke={DARK} lineWidth={7 * S} lineCap="round" />
        <Path data="M-17 18 L17 18 L0 46 Z" fill={ORANGE} stroke="#e57f12" lineWidth={3 * S} lineJoin="round" scale={S} y={0} />
        <Circle ref={mouthC} width={22 * S} height={4 * S} fill="#7a2f1d" y={50 * S} />
        <Circle width={30 * S} height={20 * S} fill="#ff8fa3" opacity={0.65} x={-86 * S} y={36 * S} />
        <Circle width={30 * S} height={20 * S} fill="#ff8fa3" opacity={0.65} x={86 * S} y={36 * S} />
      </Node>
    </Node>,
  );
  // name tag
  view.add(<Rect x={bx} y={by + 232 * S} width={Math.max(150 * u, name.length * 24 * u + 50 * u)} height={44 * u} radius={22 * u} fill={light ? '#ffffff' : '#12384f'} stroke={light ? '#2a2540' : '#3aa0d0'} lineWidth={3 * u}><Txt text={name} fill={ink} fontFamily={FONT} fontSize={26 * u} fontWeight={800} /></Rect>);

  // speech bubble: a rounded box with a tail towards the owl, text typed out line by line
  const bw = W * 0.5, bh = 190 * u, bxp = W * 0.2, byp = -H * 0.18;
  const bubble = createRef<Node>(), txt = createRef<Txt>();
  view.add(
    <Node ref={bubble} x={bxp} y={byp} scale={0} opacity={0}>
      <Rect width={bw} height={bh} radius={30 * u} fill={light ? '#ffffff' : '#12384f'} stroke={light ? '#2a2540' : '#3aa0d0'} lineWidth={4 * u} />
      <Path data="M-190 80 L-250 150 L-130 98 Z" fill={light ? '#ffffff' : '#12384f'} stroke={light ? '#2a2540' : '#3aa0d0'} lineWidth={4 * u} lineJoin="round" scale={u} />
      <Rect width={bw - 12 * u} height={bh - 12 * u} radius={26 * u} fill={light ? '#ffffff' : '#12384f'} />
      <Txt ref={txt} text="" fill={ink} fontFamily={FONT} fontSize={38 * u} fontWeight={800} textWrap width={bw - 70 * u} textAlign="center" />
    </Node>,
  );

  const at = (n?: number) => Math.max(0, (starts.length ? starts[Math.min(Math.max(0, n ?? 0), starts.length - 1)] : (n ?? 0) * 3) - 0.1);
  const speechAt = speeches.map(s => ({ ...s, t: at(s.at ?? 0) })).sort((a, b) => a.t - b.t);
  // is the narrator speaking at time t? (sentence windows when there is no envelope)
  const speaking = (t: number) => { for (let i = 0; i < starts.length; i++) { const e = i + 1 < starts.length ? starts[i + 1] - 0.4 : total - 0.7; if (t >= starts[i] && t <= e) return true; } return false; };
  const talk = (t: number) => {
    if (mouth.length) { const i = Math.floor(t * 30); const a = mouth[Math.min(mouth.length - 1, Math.max(0, i))] || 0; return Math.min(1, a * 1.4); }
    return speaking(t) ? 0.35 + 0.65 * Math.abs(Math.sin(t * 11.3) * Math.sin(t * 4.1 + 1)) : 0;
  };
  const ease = (k: number) => 1 - Math.pow(1 - Math.min(1, Math.max(0, k)), 3);
  const bounce = (k: number) => { const x = Math.min(1, Math.max(0, k)); return x < 1 ? 1 - Math.pow(1 - x, 3) * Math.cos(x * 9) * (1 - x) * 0 + Math.sin(x * Math.PI) * 0.12 * (1 - x) : 1; };

  yield* tween(total, v => {
    const t = v * total;
    // entrance with a little overshoot, then breathing and a gentle bob
    const enter = ease(t / 0.7), sc = enter * (1 + 0.12 * Math.sin(Math.min(1, t / 0.7) * Math.PI)) * (1 + Math.sin(t * 2.2) * 0.012);
    root().scale(sc); root().y(by + Math.sin(t * 2.2) * 5 * u);
    // blink every ~3.2 s
    const bp = (t + 0.8) % 3.2, blink = bp < 0.16 ? Math.sin((bp / 0.16) * Math.PI) : 0;
    lidL().height(Math.max(0.01, 74 * S * blink)); lidR().height(Math.max(0.01, 74 * S * blink));
    // mood
    const wow = mood === 'wow', think = mood === 'thinking', wave = mood === 'wave';
    browL().points([[-78 * S, (wow ? -58 : -44) * S], [-22 * S, (wow ? -64 : think ? -52 : -46) * S]]);
    browR().points([[22 * S, (wow ? -64 : think ? -34 : -46) * S], [78 * S, (wow ? -58 : think ? -48 : -44) * S]]);
    head().rotation(think ? -5 + Math.sin(t * 1.3) * 1.5 : Math.sin(t * 1.1) * 1.2);
    pupL().x(-50 * S + (think ? 8 : Math.sin(t * 0.9) * 3) * S); pupR().x(50 * S + (think ? 8 : Math.sin(t * 0.9) * 3) * S); pupL().y((think ? -6 : 4) * S); pupR().y((think ? -6 : 4) * S);
    // wings: wave = right wing waves; wow = both flap; otherwise they rest with a tiny sway
    const flap = Math.sin(t * 7) * 24;
    wingR().rotation(wave ? -62 + flap : wow ? -34 + flap : -6 + Math.sin(t * 2.2) * 2);
    wingL().rotation(wow ? 34 - flap : 6 - Math.sin(t * 2.2) * 2);
    // beak opens with the voice
    const m = talk(t); mouthC().height(4 * S + m * 40 * S); mouthC().width((22 + m * 10) * S);
    // speech bubble: the latest line whose sentence has begun, typed at ~26 characters per second
    let cur = -1; for (let i = 0; i < speechAt.length; i++) if (t >= speechAt[i].t) cur = i;
    if (cur >= 0) {
      const sp = speechAt[cur], shown = Math.floor((t - sp.t) * 26);
      txt().text(sp.text.slice(0, Math.max(0, shown)));
      const bin = ease((t - speechAt[0].t) / 0.35); bubble().opacity(bin); bubble().scale(bin * (1 + 0.04 * Math.sin(Math.min(1, (t - sp.t) / 0.25) * Math.PI)));
    }
  });
  if (total > 0) yield* waitFor(0.01);
});
