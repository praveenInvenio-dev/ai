import { makeProject } from '@motion-canvas/core';
import flow from './scenes/flow?scene';

// Every value the scene needs arrives as plain data (variables); no model-written code is ever built or run.
export default makeProject({
  scenes: [flow],
  variables: { title: '', labels: '[]', bg: '#0b0b0c', portrait: 0, kind: 'flow' },
});
