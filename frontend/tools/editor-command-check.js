// Regression check for the editor's chat commands (no browser needed).
//   cd frontend && npm install && node tools/editor-command-check.js
// Extracts interpretSmartCommand from the component, transpiles it and runs it on phrases with expected outcomes.
const fs = require('fs'), path = require('path'), ts = require('typescript');
const file = path.join(__dirname, '../src/app/pages/video-editor/video-editor.component.ts');
const src = fs.readFileSync(file, 'utf8');
const a = src.indexOf('  private static readonly LOOK_WORDS'), b = src.indexOf('  exportUrl(fmt');
const c0 = src.indexOf('  private commandOnRow('), c1 = src.indexOf('\n  onMusicPicked', c0);
const cls = `class VideoEditorComponent {
  calls=[]; form={}; timeline=[]; clips=[]; captionStyle='BOLD_REEL'; chatLog=[]; project={id:'p'}; history=[]; redoStack=[]; timelineDirty=false;
  snapshot(){return this.timeline.map(r=>({...r}));} pushHistory(s){this.history.push(s);}
  api={videoEditorVersions:()=>({subscribe:(o)=>o.next([{},{}])})};
  analysisDone(){return true} regenerateWithInstruction(){this.calls.push('regen')} saveSettingsThen(f){this.calls.push('saveSettings')}
  saveTimeline(){this.calls.push('saveTL')} cleanupSpeech(){this.calls.push('cleanup')} findMoments(){this.calls.push('moments')} restoreVersion(){this.calls.push('restore')} searchSpeech(){this.calls.push('search')}
  selectRow(){} deleteSelected(){}
  finish={lookIntensity:1,overlays:[],exportFormat:'',exportHeight:0,exportFps:0,loudness:''}; project={aspectRatio:'LANDSCAPE_16_9'}; finishDirty=false; caps=null; voScript=''; generateVoiceover(){this.calls.push('voiceover')}
${src.slice(a, b)}
${src.slice(c0, c1)}
}`;
const js = ts.transpileModule(cls, { compilerOptions: { target: 'ES2020' } }).outputText;
const C = new Function(js + '\nreturn VideoEditorComponent;')();
C.prototype.generateVoiceover = function () { this.calls.push('voiceover'); };
C.prototype.startDub = function (l) { this.calls.push('dub:' + l); };   // the real one calls the API
// [phrase, expected call or null (= not handled here), optional form check]
const cases = [
  ['add captions', 'saveSettings', f => f.autoCaptions === true], ['remove the captions', 'saveSettings', f => f.autoCaptions === false],
  ['make the captions bigger', null], ['make it vertical for reels', 'regen', f => f.aspectRatio === 'VERTICAL_9_16'],
  ['make it for youtube', 'regen', f => f.aspectRatio === 'LANDSCAPE_16_9'], ['make it for youtube shorts', 'regen', f => f.aspectRatio === 'VERTICAL_9_16'],
  ['convert to square', 'regen', f => f.aspectRatio === 'SQUARE_1_1'], ['warm look', 'saveSettings', f => f.look === 'warm'],
  ['make it black and white', 'saveSettings', f => f.look === 'bw'], ['make it warmer', 'saveSettings', f => f.look === 'warm'],
  ['no filter', 'saveSettings', f => f.look === 'none'], ['this looks great', null],
  ['add title: My trip to Goa', 'saveSettings', f => f.titleText === 'My trip to Goa'],
  ['add floating text JVM at 5 seconds behind person', 'saveSettings', (f,t) => t.finish.overlays.some(o => o.type === 'text_behind_person' && o.text === 'JVM')], ['the title is great', null],
  ['remove the title', 'saveSettings', f => f.titleText === ''], ['speed up shot 2', 'saveTL'], ['slow motion shot 3', 'saveTL'],
  ['trim shot 1 to 2 seconds', 'saveTL'], ['make the intro faster', 'saveTL'], ['tighten the pacing', 'saveTL'],
  ['remove the dead air', 'cleanup'], ['cut the ums and uhs', 'cleanup'], ['clean up the audio', 'saveSettings', f => f.audioEnhancement === true],
  ['find the best moments', 'moments'], ['make shorts from this video', 'moments'], ['undo', 'restore'], ['find where I say new dashboard', 'search'], ['search for the cake', 'search'], ['where did I mention pricing?', 'search'],
  ['stabilize the video', 'saveSettings', f => f.stabilize === true], ['sync cuts to the beat', 'regen', f => f.beatSync === true],
  ['turn off beat sync', 'regen', f => f.beatSync === false],
  ['add film grain', 'saveSettings', f => f.effects === 'film_grain'], ['add a vignette', 'saveSettings', f => f.effects === 'vignette'],
  ['remove effects', 'saveSettings', f => f.effects === ''], ['add stacked captions', 'saveSettings', f => f.autoCaptions === true],
  ['no vignette', 'saveSettings', f => f.effects === ''], ['add halation', 'saveSettings', f => f.effects === 'halation'], ['make it 20 seconds', null], ['focus on the cake shots', null],
  // ---- finishing commands (batch 3)
  ['add text hello world at 0:05 for 4 seconds', 'saveSettings', (f,t) => t.finish.overlays.length === 1 && t.finish.overlays[0].start === 5 && t.finish.overlays[0].end === 9 && t.finish.overlays[0].text === 'hello world'],
  ['add lower third: Priya Raghunathan at 10s', 'saveSettings', (f,t) => t.finish.overlays[0].style === 'lower_third' && t.finish.overlays[0].start === 10 && t.finish.overlays[0].text === 'Priya Raghunathan'],
  ['add floating text JVM at 5 seconds', 'saveSettings', (f,t) => t.finish.overlays[0].animation === 'float' && t.finish.overlays[0].text === 'JVM' && t.finish.overlays[0].start === 5],
  ['add text Hello at 2 seconds with slide up', 'saveSettings', (f,t) => t.finish.overlays[0].animation === 'slide_up' && t.finish.overlays[0].text === 'Hello'],
  ['zoom in at 0:12', 'saveSettings', (f,t) => t.finish.overlays[0].type === 'zoom' && t.finish.overlays[0].start === 12],
  ['zoom in a lot on 8 seconds', 'saveSettings', (f,t) => t.finish.overlays[0].amount === 1.6 && t.finish.overlays[0].start === 8],
  ['add a whoosh at 3s', 'saveSettings', (f,t) => t.finish.overlays[0].sfx === 'whoosh' && t.finish.overlays[0].start === 3],
  ['add a sparkle sound effect at 0:08', 'saveSettings', (f,t) => t.finish.overlays[0].sfx === 'sparkle' && t.finish.overlays[0].start === 8],
  ['add a ding at 0:04', 'saveSettings', (f,t) => t.finish.overlays[0].sfx === 'ding' && t.finish.overlays[0].start === 4],
  ['play a camera shutter at 7s', null],
  ['add a shutter sound effect at 7s', 'saveSettings', (f,t) => t.finish.overlays[0].sfx === 'shutter' && t.finish.overlays[0].start === 7],
  ['drop the tada at 0:20', 'saveSettings', (f,t) => t.finish.overlays[0].sfx === 'tada'],
  ['dub this into hindi', 'dub:Hindi'],
  ['dub the video into Kannada', 'dub:Kannada'],
  ['replace the original voice with the voiceover', 'saveSettings', (f,t) => t.finish.voiceoverReplace === true],
  ['keep the original voice', 'saveSettings', (f,t) => t.finish.voiceoverReplace === false],
  ['remove all overlays', 'saveSettings', (f,t) => t.finish.overlays.length === 0],
  ['look intensity 50%', 'saveSettings', (f,t) => t.finish.lookIntensity === 0.5],
  ['reduce the look to half', 'saveSettings', (f,t) => t.finish.lookIntensity === 0.5],
  ['set the filter to 30%', 'saveSettings', (f,t) => t.finish.lookIntensity === 0.3],
  ['export as webm', 'saveSettings', (f,t) => t.finish.exportFormat === 'webm'],
  ['export in 4k at 60 fps', 'saveSettings', (f,t) => t.finish.exportHeight === 2160 && t.finish.exportFps === 60],
  ['export 1080p', 'saveSettings', (f,t) => t.finish.exportHeight === 1080],
  ['add light leak transitions between every clip', 'saveTL', (f,t) => t.timeline[0].techniqueOut === 'cb_light_leak' && !t.timeline[t.timeline.length - 1].techniqueOut],
  ['use a push transition to the right between all shots', 'saveTL', (f,t) => t.timeline[0].techniqueOut === 'cb_push_right'],
  ['add a fade transition after shot 1', 'saveTL', (f,t) => t.timeline[0].techniqueOut === 'cb_fade' && !t.timeline[1].techniqueOut],
  ['audio fade transitions between every clip', 'saveTL', (f,t) => t.timeline[0].techniqueOut === 'cb_audio_fade'],
  ['add a 1 second dissolve transition between all clips', 'saveTL', (f,t) => t.timeline[0].techniqueOut === 'cb_dissolve' && t.timeline[0].transitionSec === 1],
  ['remove the transitions', 'saveTL', (f,t) => !t.timeline[0].techniqueOut],
  ['voiceover: Welcome to my channel. Today we cook.', 'voiceover'],
  ['zoom transitions between every clip', 'saveTL', (f,t) => t.timeline[0].techniqueOut === 'cb_zoom'],
  // ---- loudness + platform presets
  ['export for youtube shorts', 'saveSettings', (f,t) => t.finish.exportHeight === 1080 && t.finish.exportFps === 30 && t.finish.loudness === 'social' && t.platformHint.includes('9:16')],
  ['optimise for the classroom', 'saveSettings', (f,t) => t.finish.exportHeight === 720 && t.finish.loudness === 'podcast'],
  ['export for broadcast', 'saveSettings', (f,t) => t.finish.exportFps === 25 && t.finish.loudness === 'broadcast'],
  ['render for tiktok', 'saveSettings', (f,t) => t.finish.loudness === 'social' && t.finish.exportHeight === 1080],
  ['publish to linkedin', 'saveSettings', (f,t) => t.finish.exportHeight === 1080],
  ['export for youtube', 'saveSettings', (f,t) => t.finish.exportFps === 30 && t.platformHint === ''],
  ['normalise the audio', 'saveSettings', (f,t) => t.finish.loudness === 'social'],
  ['even out the volume', 'saveSettings', (f,t) => t.finish.loudness === 'social'],
  ['set loudness to -16 lufs', 'saveSettings', (f,t) => t.finish.loudness === 'podcast'],
  ['broadcast loudness -23 lufs', 'saveSettings', (f,t) => t.finish.loudness === 'broadcast'],
  ['turn off loudness normalisation', 'saveSettings', (f,t) => t.finish.loudness === ''],
  // must stay unchanged: these are not finishing commands
  ['add title: My trip to Goa', 'saveSettings', f => f.titleText === 'My trip to Goa'],
  ['add floating text JVM at 5 seconds behind person', 'saveSettings', (f,t) => t.finish.overlays.some(o => o.type === 'text_behind_person' && o.text === 'JVM')],
  ['this transition is nice', null],
 ['remove shot 3', null]
];
let bad = 0;
for (const [p, call, check] of cases) {
  const t = new C();
  t.timeline = [{ sourceStartSec: 0, sourceEndSec: 5, speed: 1, locked: false, clipId: 'c' }, { sourceStartSec: 2, sourceEndSec: 6, speed: 1, locked: false, clipId: 'c' }, { sourceStartSec: 0, sourceEndSec: 4, speed: 1, locked: false, clipId: 'c' }];
  t.clips = [{ id: 'c', durationSec: 30 }];
  const r = t.interpretSmartCommand(p, p.toLowerCase());
  const ok = call === null ? r === null : (r !== null && t.calls.includes(call) && (!check || check(t.form, t)));
  if (!ok) { bad++; console.log('FAIL', JSON.stringify(p), '->', r, t.calls, JSON.stringify(t.form)); }
}
// Regression: direct row mutations routed through commandOnRow must keep a
// pre-edit snapshot so Undo can restore them, even when the command doesn't use commit().
const historyCase = new C();
historyCase.timeline = [{ id:'row-1', sourceStartSec:0, sourceEndSec:5, speed:1, locked:false, clipId:'c' }];
historyCase.clips = [{ id:'c', durationSec:30 }];
historyCase.commandOnRow(1, 'locked', i => { historyCase.timeline[i].locked = true; });
const historyOk = historyCase.history.length === 1 && historyCase.history[0][0].locked === false && historyCase.timeline[0].locked === true && historyCase.timelineDirty === true;
if (!historyOk) { bad++; console.log('FAIL direct row command should record undo history and mark dirty', historyCase.history, historyCase.timeline, historyCase.timelineDirty); }
console.log(`${cases.length - (bad - (historyOk ? 0 : 1))}/${cases.length} editor commands behave as expected; direct-mutation history ${historyOk ? 'PASS' : 'FAIL'}`);
process.exit(bad ? 1 : 0);
