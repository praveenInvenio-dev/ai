import { makeProject } from '@revideo/core';
import flow from './scenes/flow?scene';

// Everything the scene needs arrives as plain data (variables). Nothing generated is compiled or executed.
export default makeProject({
  scenes: [flow],
  settings: { rendering: { fps: 30 } },
  variables: { kicker: '', title: '', labels: '[]', bg: '#0b0b0c', portrait: 0, reveals: '[]', total: 6 },
});
