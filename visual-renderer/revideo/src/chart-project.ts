import { makeProject } from '@revideo/core';
import chart from './scenes/chart?scene';

export default makeProject({
  scenes: [chart],
  settings: { rendering: { fps: 30 } },
  variables: { kicker: '', title: '', bg: '#0c2433', style: 'line', unit: '', caption: '', data: '[]', starts: '[]', total: 8 },
});
