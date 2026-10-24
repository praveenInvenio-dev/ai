import { makeProject } from '@revideo/core';
import scene from './scenes/forces?scene';

export default makeProject({
  scenes: [scene],
  settings: { rendering: { fps: 30 } },
  variables: { kicker: '', title: '', bg: '#0c2433', light: 0, starts: '[]', total: 8 },
});
