import { makeScene2D } from '@motion-canvas/2d';
import { Circle, Line, Rect, Txt } from '@motion-canvas/2d/lib/components';
import { all, chain, createRef, waitFor } from '@motion-canvas/core';

/** Starter scene: a request travels through a queue to a worker. Use validated scene data in future templates, never generated code. */
export default makeScene2D(function* (view) {
  const title = createRef<Txt>();
  const request = createRef<Rect>(); const queue = createRef<Rect>(); const worker = createRef<Rect>();
  const packet = createRef<Circle>(); const linkA = createRef<Line>(); const linkB = createRef<Line>();
  view.add(<>
    <Txt ref={title} text="HOW A REQUEST MOVES" y={-300} fontSize={42} fontWeight={800} fill="#eaf4ff" />
    <Line ref={linkA} points={[[-340, 0],[-110,0]]} stroke="#65d8f5" lineWidth={6} endArrow arrowSize={18} end={0} />
    <Line ref={linkB} points={[[110,0],[340,0]]} stroke="#65d8f5" lineWidth={6} endArrow arrowSize={18} end={0} />
    <Rect ref={request} x={-340} y={0} width={190} height={130} radius={22} fill="#172c49" stroke="#60a5fa" lineWidth={4}><Txt text="REQUEST" fontSize={26} fill="#f2f8ff" /></Rect>
    <Rect ref={queue} x={0} y={0} width={190} height={130} radius={22} fill="#332b18" stroke="#fbbf24" lineWidth={4}><Txt text="QUEUE" fontSize={26} fill="#fff7d6" /></Rect>
    <Rect ref={worker} x={340} y={0} width={190} height={130} radius={22} fill="#15352e" stroke="#34d399" lineWidth={4}><Txt text="WORKER" fontSize={26} fill="#e7fff5" /></Rect>
    <Circle ref={packet} x={-340} y={0} size={24} fill="#ffffff" shadowColor="#67e8f9" shadowBlur={20} />
    <Txt text="The moving packet is the event — not just decoration." y={230} fontSize={22} fill="#a9bdd8" />
  </>);
  yield* all(linkA().end(1, .5), linkB().end(1, .5), request().scale(1, .4), queue().scale(1, .4), worker().scale(1, .4));
  yield* chain(packet().position([-110,0], .8), packet().position([0,0], .5), queue().stroke('#fff1a8', .2), waitFor(.15), packet().position([110,0], .8), packet().position([340,0], .5), worker().scale(1.08,.18), worker().scale(1,.18));
  yield* waitFor(.4);
});
