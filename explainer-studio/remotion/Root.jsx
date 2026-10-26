import React from 'react';
import { Composition } from 'remotion';
import { VideoExplainer } from './VideoExplainer.jsx';
import sample from '../samples/sample-timeline.json';

/**
 * One composition. Duration, fps and size are computed from the props at render time
 * (calculateMetadata), so the same bundle renders 30/60 fps and 16:9 / 9:16 without rebuilding.
 */
export const Root = () => (
  <Composition
    id="Explainer"
    component={VideoExplainer}
    durationInFrames={900}
    fps={30}
    width={1920}
    height={1080}
    defaultProps={{ timeline: sample, themeId: 'dracula', platformId: 'yt_quick', fps: 30, width: 1920, height: 1080 }}
    calculateMetadata={async ({ props }) => ({
      durationInFrames: Math.max(1, Math.ceil((props.timeline.totalMs / 1000) * props.fps)),
      fps: props.fps,
      width: props.width,
      height: props.height,
    })}
  />
);
