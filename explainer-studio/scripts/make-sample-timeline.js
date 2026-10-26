/**
 * Builds samples/sample-timeline.json with ESTIMATED speech durations (no TTS needed).
 * Used by `npm run studio` (Remotion Studio preview) and tests.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildTimeline } from '../src/timeline/build.js';
import { pauseAfter } from '../src/tts/prosody.js';
import { buildSampleScenes } from './sample-scenes.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const { outline, scenes, platform, meta } = buildSampleScenes();
for (const scene of scenes) {
  scene.sentences.forEach((s, k) => {
    s.speechMs = 320 + s.text.split(/\s+/).length * 350; // ≈ 2.8 words/second + onset
    s.pauseMs = pauseAfter({ text: s.text, isLastOfScene: k === scene.sentences.length - 1, emphasis: s.emphasis, pauseScale: platform.pauseScale });
  });
}
const { timeline } = buildTimeline({ outline, scenes, platform, meta });
fs.mkdirSync(path.join(here, '../samples'), { recursive: true });
fs.writeFileSync(path.join(here, '../samples/sample-timeline.json'), JSON.stringify(timeline, null, 2));
console.log(`sample-timeline.json: ${timeline.scenes.length} scenes, ${timeline.totalSeconds}s`);
