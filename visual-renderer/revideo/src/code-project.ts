import { makeProject } from '@revideo/core';
import code from './scenes/code?scene';

export default makeProject({
  scenes: [code],
  settings: { rendering: { fps: 30 } },
  variables: { kicker: '', title: '', file: 'Example.java', language: 'java', code: '', bg: '#0c2433', starts: '[]', total: 8 },
});
