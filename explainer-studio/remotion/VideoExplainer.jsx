import React, { useMemo } from 'react';
import { AbsoluteFill, Easing, Freeze, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { noise2D } from '@remotion/noise';
import { ThemeCtx, themes } from './themes.jsx';
import { getLayout, slotRects } from './layout.js';
import { PRIMITIVES } from './primitives/index.jsx';
import { TimingCtx } from './hooks/cue.js';
import { Header, Captions, TechNote, HookOverlay, EndCard, ProgressBar } from './components/Overlays.jsx';
import { Atmosphere, SceneSweep } from './fx/Atmosphere.jsx';

/**
 * Generic timeline → video. No topic-specific code lives here: every scene is a list of
 * layout-agnostic elements (type + slot + props + cue-driven state), resolved to rectangles per aspect ratio.
 * All timing is read from `timeline` (absolute ms) — frames are just ms × fps / 1000.
 *
 * Motion layer (AI Story Studio integration):
 *  - scene transitions: the outgoing scene is FROZEN on its last frame (<Freeze>) and pulled away with blur + drift
 *    while the new scene's elements spring in, with a light sweep across the frame. No empty frames between scenes.
 *  - camera: each scene slowly pushes in (Ken Burns) with a little Perlin-noise float, so nothing is ever static.
 *  - elements enter with spring + de-blur (a cheap, deterministic stand-in for motion blur).
 *  - the opening hook dims the stage behind it so the first 3 s read as one clean title card.
 */
const TRANS_MS = 650;

const ElementWrapper = ({ el, rect, nowMs, fps, sceneEnd, lastScene, index }) => {
  const Comp = PRIMITIVES[el.type];
  const frame = useCurrentFrame();
  if (!Comp) return null;
  if (nowMs < el.enterMs || nowMs >= el.exitMs + 300) return null;
  const enterFrame = Math.round((el.enterMs / 1000) * fps) + index * 3; // tiny stagger between siblings
  const p = spring({ frame: frame - enterFrame, fps, config: { damping: 15, stiffness: 120, mass: 0.9 } });
  const fadeOut = el.exitMs >= sceneEnd - 1 ? 1 : interpolate(nowMs, [el.exitMs - 200, el.exitMs + 280], [1, 0], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const blur = (1 - Math.min(1, p)) * 10;
  return (
    <TimingCtx.Provider value={{ enterMs: el.enterMs }}>
      <div style={{ position: 'absolute', left: rect.x, top: rect.y, width: rect.w, height: rect.h, opacity: Math.min(1, p * 1.3) * fadeOut, transform: `translateY(${(1 - p) * 34}px) scale(${0.93 + 0.07 * p})`, transformOrigin: '50% 60%', filter: blur > 0.3 ? `blur(${blur.toFixed(2)}px)` : undefined }}>
        <Comp w={rect.w} h={rect.h} props={el.props} events={el.events} />
      </div>
    </TimingCtx.Provider>
  );
};

const SceneLayer = ({ scene, rects, nowMs, fps, lastScene, style }) => (
  <div style={{ position: 'absolute', inset: 0, ...style }}>
    {scene.visualElements.map((el, i) => (
      <ElementWrapper key={el.id} el={el} index={i} rect={rects[el.slot] || rects.main} nowMs={nowMs} fps={fps} sceneEnd={scene.endMs} lastScene={lastScene} />
    ))}
  </div>
);

/** Slow push-in + noise float for one scene. `k` 0..1 = progress through the scene. */
const camera = (k, sec, seed, L) => {
  const s = 1 + 0.028 * k;
  const dx = noise2D(`cx${seed}`, sec * 0.12, 0) * L.width * 0.004;
  const dy = noise2D(`cy${seed}`, 0, sec * 0.12) * L.height * 0.004;
  return `translate(${dx.toFixed(2)}px, ${dy.toFixed(2)}px) scale(${s.toFixed(4)})`;
};

export const VideoExplainer = ({ timeline, themeId = 'dracula' }) => {
  const frame = useCurrentFrame();
  const { fps, width, height } = useVideoConfig();
  const theme = themes[themeId] || themes.dracula;
  const L = useMemo(() => getLayout(width, height), [width, height]);
  const nowMs = (frame / fps) * 1000;
  const sec = frame / fps;

  const flatSentences = useMemo(() => timeline.scenes.flatMap((s) => s.sentences), [timeline]);
  const sceneRects = useMemo(() => timeline.scenes.map((sc) => slotRects(L, new Set(sc.visualElements.map((e) => e.slot)))), [timeline, L]);

  let sceneIdx = timeline.scenes.findIndex((s) => nowMs >= s.startMs && nowMs < s.endMs);
  if (sceneIdx < 0) sceneIdx = nowMs < 0 ? 0 : timeline.scenes.length - 1;
  const scene = timeline.scenes[sceneIdx];
  const lastScene = sceneIdx === timeline.scenes.length - 1;

  let cur = scene.sentences[0];
  for (const s of scene.sentences) if (s.startMs <= nowMs) cur = s;
  const phase = cur.phase;
  const techNote = cur.techNote && nowMs <= cur.endMs + 900 ? cur.techNote : null;
  const showCta = nowMs >= timeline.totalMs - 3200;

  // ── transition from the previous scene ──────────────────────────────────
  const sinceStart = nowMs - scene.startMs;
  const inTrans = sceneIdx > 0 && sinceStart < TRANS_MS;
  const tp = inTrans ? Easing.inOut(Easing.cubic)(Math.min(1, sinceStart / TRANS_MS)) : 1;
  const prev = inTrans ? timeline.scenes[sceneIdx - 1] : null;
  const prevFreezeFrame = prev ? Math.max(0, Math.floor(((prev.endMs - 1) / 1000) * fps)) : 0;

  // ── hook card dims the stage for the first ~2.6 s ───────────────────────
  const hookVis = timeline.meta.onScreenHook ? interpolate(nowMs, [2200, 2950], [1, 0], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }) : 0;

  const k = Math.min(1, Math.max(0, sinceStart / Math.max(1, scene.endMs - scene.startMs)));
  // The last scene stays put at the very end so the end card sits on a calm frame.
  const fadeAtEnd = lastScene ? interpolate(nowMs, [timeline.totalMs - 900, timeline.totalMs], [1, 0.9], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }) : 1;

  return (
    <ThemeCtx.Provider value={theme}>
      <AbsoluteFill style={{ fontFamily: theme.font, color: theme.text }}>
        <Atmosphere energy={inTrans ? Math.sin(Math.PI * tp) : 0} seed={sceneIdx} />
        <Header L={L} title={timeline.meta.title || timeline.meta.topic} />

        {prev && (
          <Freeze frame={prevFreezeFrame}>
            <SceneLayer
              scene={prev} rects={sceneRects[sceneIdx - 1]} nowMs={prev.endMs - 1} fps={fps} lastScene={false}
              style={{ opacity: 1 - tp, filter: `blur(${(tp * 12).toFixed(2)}px)`, transform: `translateX(${(-tp * width * 0.05).toFixed(1)}px) scale(${(1.028 - 0.05 * tp).toFixed(4)})`, transformOrigin: '50% 45%' }}
            />
          </Freeze>
        )}
        <SceneLayer
          scene={scene} rects={sceneRects[sceneIdx]} nowMs={nowMs} fps={fps} lastScene={lastScene}
          style={{
            opacity: (inTrans ? tp : 1) * (1 - 0.78 * hookVis) * fadeAtEnd,
            filter: hookVis > 0.02 ? `blur(${(hookVis * 5).toFixed(2)}px)` : undefined,
            transform: `${inTrans ? `translateX(${((1 - tp) * width * 0.05).toFixed(1)}px) ` : ''}${camera(k, sec, sceneIdx, L)}`,
            transformOrigin: '50% 45%',
          }}
        />
        {inTrans && <SceneSweep p={Math.min(1, sinceStart / TRANS_MS)} />}

        <HookOverlay L={L} text={timeline.meta.onScreenHook} nowMs={nowMs} />
        {!showCta && <TechNote L={L} text={techNote} sinceMs={nowMs - cur.startMs} durMs={cur.endMs - cur.startMs} />}
        <EndCard L={L} text={timeline.meta.ctaLine} nowMs={nowMs} totalMs={timeline.totalMs} />
        {!(showCta && timeline.meta.ctaLine) && <Captions L={L} sentences={flatSentences} nowMs={nowMs} chunk={L.portrait ? Math.min(timeline.meta.captionChunk || 3, 3) : Math.max(timeline.meta.captionChunk || 9, 7)} />}
        <ProgressBar L={L} nowMs={nowMs} totalMs={timeline.totalMs} />
      </AbsoluteFill>
    </ThemeCtx.Provider>
  );
};
