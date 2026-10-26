import React from 'react';
import { renderToString } from 'react-dom/server';
import { Player } from '@remotion/player';
import { VideoExplainer } from '../remotion/VideoExplainer.jsx';
import sample from '../samples/sample-timeline.json';

const out = [];
for (const [W, H] of [[1920, 1080], [1080, 1920]]) {
  for (const themeId of ['dracula', 'cyberpunk', 'academic']) {
    const fps = 30;
    const total = Math.ceil((sample.totalMs / 1000) * fps);
    const marks = [0, 20, 60, 120, 240, 400, 520, 700, 900, 1100, 1300, 1500, 1650, total - 1];
    for (const f of marks) {
      const html = renderToString(
        <Player component={VideoExplainer} inputProps={{ timeline: sample, themeId }} durationInFrames={total} fps={fps} compositionWidth={W} compositionHeight={H} initialFrame={Math.min(f, total - 1)} controls={false} style={{ width: 400 }} acknowledgeRemotionLicense />,
      );
      out.push({ W, H, themeId, f, len: html.length, nan: /NaN|undefined|Infinity/.test(html), html });
    }
  }
}
export default out;
