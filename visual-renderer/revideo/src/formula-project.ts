import { makeProject } from '@revideo/core';
import formula from './scenes/formula?scene';

export default makeProject({
  scenes: [formula],
  settings: { rendering: { fps: 30 } },
  variables: { kicker: '', title: '', bg: '#0c2433', formulas: '[]', starts: '[]', total: 8 },
});
