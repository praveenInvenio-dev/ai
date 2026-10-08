import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService, StoryboardEpisode, StoryboardScene, Voice, VoiceSegment } from '../../services/api.service';

/**
 * Storyboard: build a video from images you supply.
 *
 * One component serves both flows because they converge after the first step:
 *
 *  - CUSTOM  — type a title, add scenes with narration, attach an image to each.
 *  - EXISTING — open an episode the LLM already drafted and fill in its images.
 *
 * From "scenes exist, attach images, generate" the two are identical, so
 * splitting them into separate components would duplicate the whole
 * per-scene upload grid and the assemble flow for the sake of one different
 * opening screen.
 *
 * ComfyUI is not involved at all here, which matters on hardware where a
 * generated image takes minutes - this path produces a finished video in about
 * the time the narration takes to synthesise.
 */
@Component({
  selector: 'app-storyboard',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <header class="page-head">
      <h1>{{ pageTitle }}</h1>
      <p class="muted">{{ pageBlurb }}</p>
    </header>

    <!-- Step 0: pick a flow (hidden when the route fixed it) -->
    <section class="panel" *ngIf="!episode">
      <h2>{{ modeLocked ? (mode === 'CUSTOM' ? 'New story' : 'Existing story') : 'Start from' }}</h2>
      <div class="mode-grid" *ngIf="!modeLocked">
        <button type="button" class="mode" [class.selected]="mode === 'CUSTOM'" (click)="mode = 'CUSTOM'">
          <strong>A new story</strong>
          <span>Type your own scenes and narration, then attach an image to each.</span>
        </button>
        <button type="button" class="mode" [class.selected]="mode === 'EXISTING'" (click)="mode = 'EXISTING'">
          <strong>An existing story</strong>
          <span>Use an episode already drafted in Create Story and upload its images.</span>
        </button>
      </div>

      <div class="start" *ngIf="mode === 'CUSTOM'">
        <label>
          Story title
          <input [(ngModel)]="newTitle" placeholder="Bobo and the golden footprints">
        </label>
        <label>
          Story language
          <select [(ngModel)]="newLanguage">
            <option *ngFor="let l of languages" [value]="l.value">{{ l.label }}</option>
          </select>
        </label>
        <button class="btn primary" [disabled]="!newTitle.trim() || busy" (click)="createStoryboard()">
          Create storyboard
        </button>
      </div>

      <div class="start" *ngIf="mode === 'EXISTING'">
        <label>
          Episode ID
          <input [(ngModel)]="existingEpisodeId" placeholder="Paste the episode ID from Create Story">
        </label>
        <button class="btn primary" [disabled]="!existingEpisodeId.trim() || busy" (click)="openExisting()">
          Load scenes
        </button>
        <p class="muted small">
          Open the episode in Create Story and copy the ID from the address bar. Its scenes and
          narration are already written; you only supply the pictures.
        </p>
      </div>
    </section>

    <p class="error" *ngIf="error">{{ error }}</p>

    <!-- Step 1: scenes -->
    <ng-container *ngIf="episode">
      <section class="panel">
        <h2>
          {{ episode.title }}
          <span class="count">{{ scenes.length }} scene(s)</span>
          <span class="count" [class.ready]="allImagesAttached()">
            {{ imagesAttached() }} / {{ scenes.length }} images
          </span>
        </h2>

        <ul class="scenes">
          <li *ngFor="let scene of scenes; let i = index">
            <div class="scene-head">
              <span class="num">{{ scene.sceneNumber }}</span>
              <span class="tick" [class.done]="scene.hasImage">{{ scene.hasImage ? '✓' : '○' }}</span>
              <div class="scene-actions">
                <button class="icon" (click)="move(i, -1)" [disabled]="i === 0">&uarr;</button>
                <button class="icon" (click)="move(i, 1)" [disabled]="i === scenes.length - 1">&darr;</button>
                <button class="icon danger" (click)="removeScene(scene)">&times;</button>
              </div>
            </div>

            <label class="h3-direction">
              🎬 Video direction
              <textarea rows="3" [(ngModel)]="scene.action"
                        (blur)="saveScene(scene)"
                        placeholder="Describe exactly what should happen in this image: camera move, character movement, expression, environment motion, and the cinematic action."></textarea>
            </label>

            <label class="h3-direction">
              🎙 Narration / dialogue
              <textarea rows="2" [(ngModel)]="scene.narration"
                        (blur)="saveScene(scene)"
                        placeholder="What should be spoken during this shot?"></textarea>
            </label>

            <div class="h3-meta">H3 native audio · automatic shot duration: ~{{ autoDuration(scene) | number:'1.1-1' }}s</div>

            <div class="voice-box">
              <div class="voice-title">🎙 Character voice tracks</div>
              <p class="muted small">Split narration into speakers. Each line can use a different voice, speed, pitch and emotion.</p>
              <div class="voice-row" *ngFor="let seg of scene.voiceSegments; let vi = index">
                <input class="char" [(ngModel)]="seg.character" [attr.list]="'characters-' + scene.id" placeholder="Character / Narrator" (blur)="saveScene(scene)">
                <datalist [id]="'characters-' + scene.id"><option *ngFor="let c of scene.characterNames" [value]="c"></option></datalist>
                <select [(ngModel)]="seg.voice" (change)="saveScene(scene)">
                  <option *ngFor="let v of voices" [value]="v.id">{{ v.label }} — {{ v.accent }} · {{ v.gender }} · {{ v.engine || 'tts' }}{{ v.installed ? '' : ' · download on first use' }}</option>
                </select>
                <textarea class="line" rows="2" [(ngModel)]="seg.text" placeholder="What this character says" (blur)="saveScene(scene)"></textarea>
                <select [(ngModel)]="seg.emotion" (change)="applyEmotion(seg); saveScene(scene)">
                  <option value="neutral">Neutral</option><option value="happy">Happy</option><option value="excited">Excited</option>
                  <option value="sad">Sad</option><option value="calm">Calm / gentle</option><option value="angry">Angry</option>
                  <option value="scared">Scared</option><option value="whisper">Whisper-like</option>
                </select>
                <label class="range">Speed <input type="range" min="0.65" max="1.45" step="0.01" [(ngModel)]="seg.speed" (change)="saveScene(scene)"> <span>{{ seg.speed | number:'1.2-2' }}×</span></label>
                <label class="range">Pitch <input type="range" min="0.70" max="1.35" step="0.01" [(ngModel)]="seg.pitch" (change)="saveScene(scene)"> <span>{{ seg.pitch | number:'1.2-2' }}×</span></label>
                <label class="pause">Pause before <input type="number" min="0" max="10000" step="50" [(ngModel)]="seg.pauseBeforeMs" (change)="saveScene(scene)"> ms</label>
                <label class="pause">Pause after <input type="number" min="0" max="10000" step="50" [(ngModel)]="seg.pauseAfterMs" (change)="saveScene(scene)"> ms</label>
                <button class="icon" type="button" title="Preview this line" [disabled]="previewing === seg" (click)="preview(seg)">▶</button>
                <button class="icon" type="button" title="Use this voice for this character in every scene" (click)="applyCharacterVoice(seg)">↳</button>
                <button class="icon danger" type="button" title="Remove voice line" (click)="removeVoiceSegment(scene, vi)">&times;</button>
              </div>
              <button class="btn small-btn" type="button" (click)="addVoiceSegment(scene)">+ Add character line</button>
              <p class="hint">Tip: You can also type <code>[pause:500]</code> inside a line for a half-second pause.</p>
            </div>

            <div class="scene-foot">
              <label class="file">
                {{ scene.hasImage ? 'Replace image' : 'Attach image' }}
                <input type="file" accept="image/*" hidden
                       (change)="onSceneImage(scene, $event)">
              </label>
              <span class="muted small" *ngIf="scene.narrationSeconds">
                narration {{ scene.narrationSeconds | number:'1.1-1' }}s
              </span>
            </div>
          </li>
        </ul>

        <button class="btn" (click)="addScene()" [disabled]="busy">+ Add scene</button>
      </section>

      <!-- Step 2: bulk upload -->
      <section class="panel" *ngIf="scenes.length">
        <h2>Bulk upload</h2>
        <p class="muted">
          Attach every image at once. Files are matched to scenes <strong>in the order you select
          them</strong>, not by filename &mdash; a folder sorted by name puts "scene10" before
          "scene2", so filename matching would quietly scramble the story. You need exactly
          {{ scenes.length }} image(s).
        </p>
        <label class="btn">
          Choose {{ scenes.length }} image(s)
          <input type="file" multiple accept="image/*" hidden (change)="onBulkImages($event)">
        </label>
      </section>

      <!-- Step 3: voice library -->
      <section class="panel voice-library" *ngIf="voices.length">
        <h2>Narration voice library <span class="count">{{ voices.length }} voices</span></h2>
        <p class="muted small">Each scene has its own speaker rows above. Voices marked as download-on-first-use are installed automatically when needed.</p>
      </section>

      <!-- Step 4: generate -->
      <section class="panel actions">
        <button class="btn primary" [disabled]="!canAssemble()" (click)="assemble()">
          {{ busy ? 'Starting…' : '🎬 Produce video (H3 voice + video)' }}
        </button>
        <button class="btn primary" [disabled]="!canAssemble() || classicBusy" (click)="produceClassic()">🎞️ Classic video (images + voice, no AI video)</button>
        <label class="check" style="display:flex;gap:.4rem;align-items:center"><input type="checkbox" [(ngModel)]="classicCaptions"> subtitles</label>
        <p class="muted small" *ngIf="classicStage"><strong>{{ classicStage }}</strong></p>
        <p class="muted small" *ngIf="classicError" style="color:#ff6b6b">{{ classicError }}</p>
        <video *ngIf="classicVideoUrl" [src]="classicVideoUrl" controls style="max-width:420px;border-radius:12px"></video>
        <label class="check" style="display:flex;gap:.5rem;align-items:flex-start">
          <input type="checkbox" [(ngModel)]="useIndicTts"> Use Indic TTS voice (IndicF5) instead of H3 speech
        </label>
        <p class="muted small">Every scene is animated by MiniMax H3, which also speaks the lines: Narrator rows are off-screen voice-over (no lip movement), character rows are lip-synced. Progress opens in Story Video Production.</p>
        <p class="muted reason" *ngIf="blockedReason()">{{ blockedReason() }}</p>
        <p class="done" *ngIf="resultFile">Video ready: <code>{{ resultFile }}</code></p>
      </section>

      <section class="panel generated-video" *ngIf="resultFile">
        <h2>🎬 Generated video</h2>
        <video class="video-preview" controls preload="metadata" [src]="videoUrl()"></video>
        <div class="video-actions">
          <a class="btn" [href]="videoUrl()" target="_blank" rel="noopener">Open video</a>
          <a class="btn" [href]="productionUrl()">Open production dashboard</a>
        </div>
      </section>
    </ng-container>
  `,
  styles: [`
    .page-head { margin-bottom: 1.25rem; }
    h1 { font-family: var(--font-display); margin: 0 0 .25rem; }
    h2 { font-size: 1rem; margin: 0 0 .8rem; display: flex; align-items: center; gap: .6rem; flex-wrap: wrap; }
    .muted { color: var(--muted); font-size: .9rem; }
    .small { font-size: .8rem; }
    .panel {
      background: var(--surface-raised); border: 1px solid var(--border);
      border-radius: var(--radius); padding: 1.1rem; margin-bottom: 1.1rem;
    }
    .error { color: var(--danger); font-size: .9rem; }
    .done { color: var(--teal); font-size: .9rem; margin: 0; }
    .mode-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: .7rem; }
    .mode {
      display: flex; flex-direction: column; gap: .3rem; text-align: left; cursor: pointer;
      background: var(--surface); border: 1px solid var(--border);
      border-radius: var(--radius); padding: .9rem; color: inherit;
    }
    .mode span { font-size: .82rem; color: var(--muted); }
    .mode:hover { border-color: var(--accent); }
    .mode.selected { border-color: var(--accent); box-shadow: inset 0 0 0 1px var(--accent); }
    .start { margin-top: 1rem; display: grid; gap: .7rem; max-width: 34rem; }
    label { display: block; font-size: .82rem; color: var(--muted); }
    input[type=text], input:not([type]), textarea {
      width: 100%; margin-top: .3rem; background: var(--surface); color: var(--text);
      border: 1px solid var(--border); border-radius: 8px; padding: .45rem; font: inherit;
    }
    .btn {
      display: inline-block; cursor: pointer; border: 1px solid var(--border);
      background: var(--surface); color: var(--text);
      border-radius: var(--radius); padding: .55rem 1.1rem; font: inherit;
    }
    .btn:hover { border-color: var(--accent); }
    .btn.primary {
      background: var(--accent); color: var(--accent-ink); border-color: var(--accent);
      font-weight: 600; justify-self: start;
    }
    .btn:disabled { opacity: .45; cursor: not-allowed; }
    .count {
      font-size: .75rem; background: var(--surface); border: 1px solid var(--border);
      border-radius: 999px; padding: .05rem .55rem; color: var(--muted); font-weight: 400;
    }
    .count.ready { color: var(--teal); border-color: var(--teal); }
    .scenes { list-style: none; margin: 0 0 1rem; padding: 0; display: grid; gap: .7rem; }
    .scenes li {
      background: var(--surface); border: 1px solid var(--border);
      border-radius: var(--radius); padding: .7rem;
    }
    .scene-head { display: flex; align-items: center; gap: .5rem; margin-bottom: .45rem; }
    .num { color: var(--muted); font-variant-numeric: tabular-nums; }
    .tick { color: var(--muted); }
    .tick.done { color: var(--teal); }
    .scene-actions { margin-left: auto; display: flex; gap: .25rem; }
    .icon {
      cursor: pointer; background: transparent; border: 1px solid var(--border);
      color: var(--muted); border-radius: 6px; width: 1.8rem; height: 1.8rem;
    }
    .icon:hover:not(:disabled) { border-color: var(--accent); color: var(--text); }
    .icon:disabled { opacity: .3; cursor: not-allowed; }
    .icon.danger:hover { border-color: var(--danger); color: var(--danger); }
    .h3-direction { display: block; margin: .45rem 0; color: var(--text); }
    .h3-direction textarea { margin-top: .3rem; }
    .h3-meta { font-size: .76rem; color: var(--teal); margin: .35rem 0 .55rem; }
    .scene-foot { display: flex; align-items: center; gap: .8rem; margin-top: .5rem; }
    .file {
      cursor: pointer; font-size: .82rem; color: var(--text);
      border: 1px solid var(--border); border-radius: 8px; padding: .3rem .7rem;
    }
    .file:hover { border-color: var(--accent); }
    .actions { display: flex; align-items: center; gap: 1rem; flex-wrap: wrap; }
    .reason { margin: 0; max-width: 44ch; }
    .voice-box { margin-top: .7rem; padding: .8rem; border: 1px solid var(--border); border-radius: 10px; background: color-mix(in srgb, var(--surface) 70%, var(--surface-raised)); }
    .voice-title { font-weight: 600; margin-bottom: .15rem; }
    .voice-row { display: grid; grid-template-columns: 11rem minmax(14rem, 1fr) minmax(18rem, 2fr) 9rem; gap: .45rem; align-items: center; margin-top: .65rem; padding: .6rem; border: 1px solid var(--border); border-radius: 8px; }
    .voice-row .line { grid-column: 2 / -1; }
    .voice-row select, .voice-row input, .voice-row textarea { min-width: 0; }
    .range { display: flex; align-items: center; gap: .35rem; font-size: .75rem; }
    .range input { flex: 1; }
    .pause { font-size: .75rem; display: flex; align-items: center; gap: .3rem; }
    .pause input { width: 5rem; }
    .small-btn { margin-top: .55rem; padding: .35rem .7rem; font-size: .8rem; }
    .hint { color: var(--muted); font-size: .75rem; margin: .55rem 0 0; }
    .generated-video { overflow: hidden; }
    .video-preview { display: block; width: 100%; max-height: 70vh; background: #000; border-radius: 10px; }
    .video-actions { display: flex; gap: .6rem; margin-top: .7rem; flex-wrap: wrap; }
    @media (max-width: 900px) { .voice-row { grid-template-columns: 1fr 1fr; } .voice-row .line { grid-column: 1 / -1; } }
  `]
})
export class StoryboardComponent implements OnInit {

  mode: 'CUSTOM' | 'EXISTING' = 'CUSTOM';
  /**
   * True when the route fixed the mode (the two nav entries do), which hides
   * the picker. One component serves both menus because everything after the
   * opening panel - the scene list, per-scene upload, bulk upload, assemble -
   * is identical; two components would duplicate all of it to change one
   * screen.
   */
  modeLocked = false;
  episode?: StoryboardEpisode;
  scenes: StoryboardScene[] = [];

  newTitle = '';
  newLanguage = 'English';
  languages = [
    { value: 'English', label: 'English (International)' },
    { value: 'Indian English', label: 'Indian English (English - India)' },
    { value: 'Kannada', label: 'ಕನ್ನಡ (Kannada)' },
    { value: 'Hindi', label: 'हिन्दी (Hindi)' },
    { value: 'Hinglish', label: 'Hinglish' },
    { value: 'Telugu', label: 'తెలుగు (Telugu)' },
    { value: 'Tamil', label: 'தமிழ் (Tamil)' },
    { value: 'Malayalam', label: 'മലയാളം (Malayalam)' },
    { value: 'Marathi', label: 'मराठी (Marathi)' },
    { value: 'Bengali', label: 'বাংলা (Bengali)' },
    { value: 'Gujarati', label: 'ગુજરાતી (Gujarati)' },
    { value: 'Odia', label: 'ଓଡ଼ିଆ (Odia)' },
    { value: 'Punjabi', label: 'ਪੰਜਾਬੀ (Punjabi)' },
    { value: 'Urdu', label: 'اردو (Urdu)' },
    { value: 'Assamese', label: 'অসমীয়া (Assamese)' },
    { value: 'Nepali', label: 'नेपाली (Nepali)' },
    { value: 'Sanskrit', label: 'संस्कृतम् (Sanskrit)' },
    { value: 'Maithili', label: 'मैथिली (Maithili)' },
    { value: 'Manipuri', label: 'মৈতৈলোন্ (Manipuri)' },
    { value: 'Bodo', label: 'बड़ो (Bodo)' },
    { value: 'Dogri', label: 'डोगरी (Dogri)' },
    { value: 'Konkani', label: 'कोंकणी (Konkani)' },
    { value: 'Santali', label: 'ᱥᱟᱱᱛᱟᱲᱤ (Santali)' },
    { value: 'Kashmiri', label: 'کٲشُر (Kashmiri)' },
    { value: 'Auto-detect', label: 'Auto-detect from text' }
  ];
  existingEpisodeId = '';

  busy = false;
  error = '';
  resultFile = '';
  /** INDIC_TTS instead of H3 speech - one or the other. */
  useIndicTts = false;
  classicBusy = false; classicCaptions = true; classicStage = ''; classicError = ''; classicVideoUrl = '';
  produceClassic(): void {
    if (!this.episode) return;
    const id = this.episode.id;
    this.classicBusy = true; this.classicError = ''; this.classicVideoUrl = ''; this.classicStage = 'Starting…';
    this.api.startClassicVideo(id, undefined, this.classicCaptions).subscribe({
      next: r => {
        const t = setInterval(() => this.api.classicVideoJob(r.jobId).subscribe({
          next: j => {
            this.classicStage = j.stage + (j.warnings?.length ? ' — ' + j.warnings.join(' ') : '');
            if (j.status === 'SUCCEEDED') { clearInterval(t); this.classicBusy = false; this.classicStage = `Classic video ready (${Math.round(j.seconds)} s)`; this.classicVideoUrl = this.api.episodeVideoUrl(id) + '?v=' + Date.now(); }
            if (j.status === 'FAILED') { clearInterval(t); this.classicBusy = false; this.classicStage = ''; this.classicError = j.error || 'Classic video failed.'; }
          },
          error: () => { clearInterval(t); this.classicBusy = false; }
        }), 3000);
      },
      error: e => { this.classicBusy = false; this.classicStage = ''; this.classicError = e?.error?.message || 'Could not start the classic video.'; }
    });
  }
  voices: Voice[] = [];
  previewing: VoiceSegment | null = null;
  defaultVoice = '';

  constructor(private api: ApiService, private route: ActivatedRoute, private router: Router) {}

  ngOnInit(): void {
    this.api.listVoices().subscribe({
      next: result => {
        this.voices = result.voices || [];
        this.defaultVoice = result.defaultVoice || (this.voices[0]?.id ?? '');
        if (this.scenes.length) this.scenes = this.scenes.map(scene => this.normalizeScene(scene));
      },
      error: err => { this.error = err?.error?.error || 'Could not load the available narration voices.'; }
    });
    // Each nav entry pins the mode through route data, so the two menus land
    // straight on their own opening panel instead of a chooser.
    const routeMode = this.route.snapshot.data['mode'] as 'CUSTOM' | 'EXISTING' | undefined;
    if (routeMode) {
      this.mode = routeMode;
      this.modeLocked = true;
    }

    // Deep link from the story approval screen: ?episodeId=...
    const episodeId = this.route.snapshot.queryParamMap.get('episodeId');
    if (episodeId) {
      this.mode = 'EXISTING';
      this.modeLocked = true;
      this.existingEpisodeId = episodeId;
      this.openExisting();
    }
  }

  /** Page heading, so each menu reads as its own screen. */
  get pageTitle(): string {
    return this.mode === 'CUSTOM' ? 'Build a story' : 'Add images to a story';
  }

  get pageBlurb(): string {
    return this.mode === 'CUSTOM'
      ? 'Write your own scenes, attach a picture to each, and the studio narrates and assembles the video.'
      : 'Take a story already drafted in Create Story, upload one image per scene, and generate the video.';
  }

  createStoryboard(): void {
    this.busy = true;
    this.error = '';
    this.api.createStoryboard({ title: this.newTitle.trim(), language: this.newLanguage }).subscribe({
      next: created => {
        this.episode = created;
        this.scenes = [];
        this.busy = false;
        this.addScene();
      },
      error: err => this.failed(err, 'Could not create the storyboard.')
    });
  }

  openExisting(): void {
    this.busy = true;
    this.error = '';
    const id = this.existingEpisodeId.trim();
    this.api.storyboardScenes(id).subscribe({
      next: scenes => {
        if (!scenes.length) {
          this.busy = false;
          this.error = 'That episode has no scenes yet. Draft the story first in Create Story.';
          return;
        }
        // The scenes endpoint does not return the episode, so a minimal shell is
        // built from what we know. Title is filled from the first load of the
        // production dashboard if the user goes there.
        this.episode = {
          id, title: 'Episode ' + id.slice(0, 8), status: 'APPROVED',
          language: null, visualStyle: null, durationTargetSec: null
        };
        this.scenes = scenes.map(s => this.normalizeScene(s));
        this.busy = false;
      },
      error: err => this.failed(err, 'Could not load that episode. Check the ID.')
    });
  }

  // ---- scenes ------------------------------------------------------------

  addScene(): void {
    if (!this.episode) { return; }
    this.api.addStoryboardScene(this.episode.id, { narration: '' }).subscribe({
      next: scene => this.scenes = [...this.scenes, this.normalizeScene(scene)],
      error: err => this.failed(err, 'Could not add a scene.')
    });
  }

  saveScene(scene: StoryboardScene): void {
    if (!this.episode) { return; }
    this.api.updateStoryboardScene(this.episode.id, scene.id, {
      narration: scene.narration, action: scene.action,
      location: scene.location, emotion: scene.emotion,
      voiceSegments: scene.voiceSegments
    }).subscribe({ error: err => this.failed(err, 'Could not save that scene.') });
  }

  removeScene(scene: StoryboardScene): void {
    if (!this.episode) { return; }
    this.api.deleteStoryboardScene(this.episode.id, scene.id).subscribe({
      next: () => this.reload(),
      error: err => this.failed(err, 'Could not remove that scene.')
    });
  }

  move(index: number, delta: number): void {
    const target = index + delta;
    if (!this.episode || target < 0 || target >= this.scenes.length) { return; }
    const next = [...this.scenes];
    [next[index], next[target]] = [next[target], next[index]];
    this.scenes = next;
    this.api.reorderStoryboardScenes(this.episode.id, next.map(s => s.id)).subscribe({
      next: updated => this.scenes = updated,
      // Optimistic reorder; on failure re-fetch rather than leaving the list in
      // an order the server rejected.
      error: () => this.reload()
    });
  }

  // ---- images ------------------------------------------------------------

  onSceneImage(scene: StoryboardScene, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || !this.episode) { return; }
    this.busy = true;
    this.api.uploadSceneImage(this.episode.id, scene.id, file).subscribe({
      next: () => { scene.hasImage = true; this.busy = false; },
      error: err => this.failed(err, 'Could not attach that image.')
    });
  }

  onBulkImages(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = '';
    if (!files.length || !this.episode) { return; }
    if (files.length !== this.scenes.length) {
      this.error = `This story has ${this.scenes.length} scene(s) but you selected ` +
                   `${files.length} image(s). Select exactly one per scene.`;
      return;
    }
    this.busy = true;
    this.error = '';
    this.api.uploadSceneImagesInOrder(this.episode.id, files).subscribe({
      next: () => { this.busy = false; this.reload(); },
      error: err => this.failed(err, 'Bulk upload failed.')
    });
  }

  // ---- voice direction ----------------------------------------------------

  private normalizeScene(scene: StoryboardScene): StoryboardScene {
    const segments = scene.voiceSegments?.length ? scene.voiceSegments : [{
      character: 'Narrator', text: scene.narration ?? '', voice: this.defaultVoice,
      speed: 1, pitch: 1, emotion: scene.emotion || 'neutral', pauseBeforeMs: 0, pauseAfterMs: 0
    }];
    return { ...scene, voiceSegments: segments.map(s => ({
      character: s.character || 'Narrator', text: s.text ?? '', voice: s.voice || this.defaultVoice,
      speed: s.speed > 0 ? s.speed : 1, pitch: s.pitch > 0 ? s.pitch : 1,
      emotion: s.emotion || 'neutral', pauseBeforeMs: s.pauseBeforeMs || 0, pauseAfterMs: s.pauseAfterMs || 0
    })) };
  }

  addVoiceSegment(scene: StoryboardScene): void {
    scene.voiceSegments = scene.voiceSegments || [];
    scene.voiceSegments.push({ character: 'Narrator', text: '', voice: this.defaultVoice || this.voices[0]?.id || '',
      speed: 1, pitch: 1, emotion: 'neutral', pauseBeforeMs: 0, pauseAfterMs: 0 });
    this.saveScene(scene);
  }

  removeVoiceSegment(scene: StoryboardScene, index: number): void {
    scene.voiceSegments.splice(index, 1);
    this.saveScene(scene);
  }

  /** Copy a character's chosen voice/prosody to every matching speaker line in this episode. */
  applyCharacterVoice(source: VoiceSegment): void {
    const name = source.character?.trim().toLowerCase();
    if (!name) return;
    for (const scene of this.scenes) {
      for (const seg of (scene.voiceSegments || [])) {
        if (seg.character?.trim().toLowerCase() === name) {
          seg.voice = source.voice; seg.speed = source.speed; seg.pitch = source.pitch; seg.emotion = source.emotion;
          seg.pauseBeforeMs = source.pauseBeforeMs; seg.pauseAfterMs = source.pauseAfterMs;
        }
      }
      this.saveScene(scene);
    }
  }

  applyEmotion(seg: VoiceSegment): void {
    const presets: Record<string, [number, number]> = {
      neutral: [1, 1], happy: [1.06, 1.06], excited: [1.14, 1.12], sad: [.90, .94],
      calm: [.92, .97], gentle: [.92, .97], angry: [1.10, 1.05], scared: [1.12, 1.10], whisper: [.90, .92]
    };
    const preset = presets[seg.emotion] || presets.neutral;
    seg.speed = preset[0]; seg.pitch = preset[1];
  }

  preview(seg: VoiceSegment): void {
    if (!seg.text.trim() || !seg.voice) return;
    this.previewing = seg;
    this.api.previewVoice(seg.text, seg.voice, seg.speed, seg.pitch).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        const audio = new Audio(url);
        audio.onended = () => { URL.revokeObjectURL(url); this.previewing = null; };
        audio.onerror = () => { URL.revokeObjectURL(url); this.previewing = null; };
        audio.play().catch(() => this.previewing = null);
      },
      error: () => this.previewing = null
    });
  }

  autoDuration(scene: StoryboardScene): number {
    const lines = (scene.voiceSegments || []).map(s => s.text || '').filter(Boolean);
    const text = (lines.length ? lines.join(' ') : (scene.narration || '')).trim();
    if (!text) return 3;
    const words = text.split(/\s+/).length;
    const pauses = (scene.voiceSegments || []).reduce((n, s) =>
      n + (s.pauseBeforeMs || 0) / 1000 + (s.pauseAfterMs || 0) / 1000, 0);
    return Math.max(3, Math.min(10, Math.round((words / 2.5 + pauses + 0.6) * 10) / 10));
  }

  videoUrl(): string {
    return this.episode ? this.api.episodeVideoUrl(this.episode.id) + '?v=' + encodeURIComponent(this.resultFile) : '';
  }

  productionUrl(): string {
    return this.episode ? `/episodes/${this.episode.id}/production` : '/dashboard';
  }

  // ---- assemble ----------------------------------------------------------

  imagesAttached(): number {
    return this.scenes.filter(s => s.hasImage).length;
  }

  allImagesAttached(): boolean {
    return this.scenes.length > 0 && this.imagesAttached() === this.scenes.length;
  }

  canAssemble(): boolean {
    return !this.busy && this.allImagesAttached() &&
           this.scenes.every(s => (s.narration ?? '').trim().length > 0);
  }

  /** Names the specific gap rather than leaving the button inert. */
  blockedReason(): string {
    if (!this.scenes.length) { return 'Add at least one scene.'; }
    const missingImages = this.scenes.filter(s => !s.hasImage).map(s => s.sceneNumber);
    if (missingImages.length) {
      return `No image yet for scene ${missingImages.join(', ')}.`;
    }
    const missingText = this.scenes.filter(s => !(s.narration ?? '').trim()).map(s => s.sceneNumber);
    if (missingText.length) {
      return `No narration yet for scene ${missingText.join(', ')}.`;
    }
    return '';
  }

  assemble(): void {
    if (!this.episode) { return; }
    this.busy = true;
    this.error = '';
    this.resultFile = '';
    this.api.produceEpisodeVideo(this.episode.id, this.useIndicTts ? 'INDIC_TTS' : 'H3').subscribe({
      next: seq => { this.busy = false; this.router.navigate(['/video-sequence'], { queryParams: { id: seq.id } }); },
      error: err => this.failed(err, 'Could not start H3 video production.')
    });
  }

  // ---- shared ------------------------------------------------------------

  private reload(): void {
    if (!this.episode) { return; }
    this.api.storyboardScenes(this.episode.id).subscribe({
      next: scenes => this.scenes = scenes.map(s => this.normalizeScene(s))
    });
  }

  /** Prefers the server's own message: it names the scene or the limit, which a
   *  generic fallback cannot. */
  private failed(err: any, fallback: string): void {
    this.busy = false;
    this.error = err?.error?.error ?? fallback;
  }
}
