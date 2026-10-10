// Regression check for the editor's chat commands (no browser needed).
//   cd frontend && npm install && node tools/editor-command-check.js
// Extracts interpretSmartCommand from the component, transpiles it and runs it on phrases with expected outcomes.
const fs = require('fs'), path = require('path'), ts = require('typescript');
const file = path.join(__dirname, '../src/app/pages/video-editor/video-editor.component.ts');
const src = fs.readFileSync(file, 'utf8');
const a = src.indexOf('  private static readonly LOOK_WORDS'), b = src.indexOf('  exportUrl(fmt');
const c0 = src.indexOf('  private commandOnRow('), c1 = src.indexOf('\n  onMusicPicked', c0);
const cls = `class VideoEditorComponent {
  calls=[]; form={}; timeline=[]; clips=[]; captionStyle='BOLD_REEL'; chatLog=[]; project={id:'p'};
  api={videoEditorVersions:()=>({subscribe:(o)=>o.next([{},{}])})};
  analysisDone(){return true} regenerateWithInstruction(){this.calls.push('regen')} saveSettingsThen(f){this.calls.push('saveSettings')}
  saveTimeline(){this.calls.push('saveTL')} cleanupSpeech(){this.calls.push('cleanup')} findMoments(){this.calls.push('moments')} restoreVersion(){this.calls.push('restore')} searchSpeech(){this.calls.push('search')}
  selectRow(){} deleteSelected(){}
${src.slice(a, b)}
${src.slice(c0, c1)}
}`;
const js = ts.transpileModule(cls, { compilerOptions: { target: 'ES2020' } }).outputText;
const C = new Function(js + '\nreturn VideoEditorComponent;')();
// [phrase, expected call or null (= not handled here), optional form check]
const cases = [
  ['add captions', 'saveSettings', f => f.autoCaptions === true], ['remove the captions', 'saveSettings', f => f.autoCaptions === false],
  ['make the captions bigger', null], ['make it vertical for reels', 'regen', f => f.aspectRatio === 'VERTICAL_9_16'],
  ['make it for youtube', 'regen', f => f.aspectRatio === 'LANDSCAPE_16_9'], ['make it for youtube shorts', 'regen', f => f.aspectRatio === 'VERTICAL_9_16'],
  ['convert to square', 'regen', f => f.aspectRatio === 'SQUARE_1_1'], ['warm look', 'saveSettings', f => f.look === 'warm'],
  ['make it black and white', 'saveSettings', f => f.look === 'bw'], ['make it warmer', 'saveSettings', f => f.look === 'warm'],
  ['no filter', 'saveSettings', f => f.look === 'none'], ['this looks great', null],
  ['add title: My trip to Goa', 'saveSettings', f => f.titleText === 'My trip to Goa'], ['the title is great', null],
  ['remove the title', 'saveSettings', f => f.titleText === ''], ['speed up shot 2', 'saveTL'], ['slow motion shot 3', 'saveTL'],
  ['trim shot 1 to 2 seconds', 'saveTL'], ['make the intro faster', 'saveTL'], ['tighten the pacing', 'saveTL'],
  ['remove the dead air', 'cleanup'], ['cut the ums and uhs', 'cleanup'], ['clean up the audio', 'saveSettings', f => f.audioEnhancement === true],
  ['find the best moments', 'moments'], ['make shorts from this video', 'moments'], ['undo', 'restore'], ['find where I say new dashboard', 'search'], ['search for the cake', 'search'], ['where did I mention pricing?', 'search'],
  ['stabilize the video', 'saveSettings', f => f.stabilize === true], ['sync cuts to the beat', 'regen', f => f.beatSync === true],
  ['turn off beat sync', 'regen', f => f.beatSync === false],
  ['add film grain', 'saveSettings', f => f.effects === 'film_grain'], ['add a vignette', 'saveSettings', f => f.effects === 'vignette'],
  ['remove effects', 'saveSettings', f => f.effects === ''], ['add stacked captions', 'saveSettings', f => f.autoCaptions === true],
  ['no vignette', 'saveSettings', f => f.effects === ''], ['add halation', 'saveSettings', f => f.effects === 'halation'], ['make it 20 seconds', null], ['focus on the cake shots', null], ['remove shot 3', null]
];
let bad = 0;
for (const [p, call, check] of cases) {
  const t = new C();
  t.timeline = [{ sourceStartSec: 0, sourceEndSec: 5, speed: 1, locked: false, clipId: 'c' }, { sourceStartSec: 2, sourceEndSec: 6, speed: 1, locked: false, clipId: 'c' }, { sourceStartSec: 0, sourceEndSec: 4, speed: 1, locked: false, clipId: 'c' }];
  t.clips = [{ id: 'c', durationSec: 30 }];
  const r = t.interpretSmartCommand(p, p.toLowerCase());
  const ok = call === null ? r === null : (r !== null && t.calls.includes(call) && (!check || check(t.form)));
  if (!ok) { bad++; console.log('FAIL', JSON.stringify(p), '->', r, t.calls, JSON.stringify(t.form)); }
}
console.log(`${cases.length - bad}/${cases.length} editor commands behave as expected`);
process.exit(bad ? 1 : 0);
