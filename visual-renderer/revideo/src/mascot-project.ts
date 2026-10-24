import { makeProject } from '@revideo/core';
import scene from './scenes/mascot?scene';

export default makeProject({
  scenes: [scene],
  settings: { rendering: { fps: 30 } },
  variables: { kicker: '', title: '', bg: '#fff7e6', light: 1, name: 'Ollie', mood: 'happy', speeches: '[]', starts: '[]', mouth: '[]', total: 8 },
});
