import { makeProject } from '@revideo/core';
import board from './scenes/board?scene';

export default makeProject({
  scenes: [board],
  settings: { rendering: { fps: 30 } },
  variables: { kicker: '', title: '', bg: '#0c2433', elements: '[]', starts: '[]', total: 6 },
});
