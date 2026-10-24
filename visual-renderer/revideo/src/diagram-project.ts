import { makeProject } from '@revideo/core';
import diagram from './scenes/diagram?scene';

export default makeProject({
  scenes: [diagram],
  settings: { rendering: { fps: 30 } },
  variables: { kicker: '', title: '', bg: '#0c2433', elements: '[]', starts: '[]', total: 8 },
});
