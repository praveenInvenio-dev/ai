import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import {
  ApiService, SequenceSceneView, SequenceStatusInfo, SequenceView
} from '../../services/api.service';
import { Character, CharacterReference, Project, Universe } from '../../models/models';

/**
 * Scene sequence: give N scenes + locked characters, get N consistent clips made one by one,
 * then one merged long video.
 *
 * Consistency: every scene's keyframe image is drawn by Qwen Image 2.1 with the locked
 * character reference(s) passed natively, so all keyframes show the same faces and outfits.
 * The video engine (H3 / Wan) only animates that keyframe. Optional "chain" continuity starts
 * each scene from the last frame of the previous clip instead.
 */
@Component({
  selector: 'app-video-sequence',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <header class="page-head">
      <h1>Story Video Production</h1>
      <p class="muted">
        Write your scenes, pick your locked characters, and the studio makes every scene one by one
        with the same faces and style, then joins them into one long video.
        Step 1 draws a keyframe for each scene (fast), step 2 animates them (slow, GPU).
      </p>
    </header>

    <section class="panel" *ngIf="statusLoaded && !status?.available">
      <p class="tag tag-amber">Not available</p>
      <p class="muted">{{ status?.reason }}</p>
    </section>

    <ng-container *ngIf="statusLoaded && status?.available">

      <!-- ===================== CREATE ===================== -->
      <section class="panel" *ngIf="!seq || showForm">
        <h2>New sequence</h2>

        <div class="field">
          <label for="title">Title</label>
          <input id="title" type="text" [(ngModel)]="title" placeholder="e.g. Milo and Momo - the mango tree">
        </div>

        <div class="field story-loader">
          <label>Load Project → Story</label>
          <div class="picker-row">
            <select [(ngModel)]="projectId" (ngModelChange)="onProject()">
              <option [ngValue]="undefined">Select project...</option>
              <option *ngFor="let p of projects" [ngValue]="p.id">{{ p.name }}</option>
            </select>
            <select [(ngModel)]="episodeId" [disabled]="!projectId">
              <option [ngValue]="undefined">Select story...</option>
              <option *ngFor="let e of episodes" [ngValue]="e.id">{{ e.title }}</option>
            </select>
            <button class="btn btn-primary" type="button" [disabled]="!episodeId || loadingStory" (click)="loadStory()">{{ loadingStory ? 'Loading...' : 'Load Story' }}</button>
          </div>
          <p class="muted">Loads all scenes in order with their saved images, narration/dialogue, music, ambience and SFX. Loading does not start the GPU.</p>
        </div>

        <div class="field">
          <label>Characters (locked references keep them identical in every scene)</label>
          <div class="picker-row">
            <select [(ngModel)]="projectId" (ngModelChange)="onProject()">
              <option [ngValue]="undefined">Project...</option>
              <option *ngFor="let p of projects" [ngValue]="p.id">{{ p.name }}</option>
            </select>
            <select [(ngModel)]="universeId" (ngModelChange)="onUniverse()" [disabled]="!projectId">
              <option [ngValue]="undefined">Universe...</option>
              <option *ngFor="let u of universes" [ngValue]="u.id">{{ u.name }}</option>
            </select>
          </div>
          <div class="chars" *ngIf="characters.length">
            <label class="char" *ngFor="let c of characters">
              <input type="checkbox" [checked]="selectedChars.has(c.id)" (change)="toggleChar(c.id)">
              <span>{{ c.name }}</span>
              <select *ngIf="selectedChars.has(c.id) && (referenceOptions[c.id]?.length || 0)" [ngModel]="selectedReferenceIds[c.id]" (ngModelChange)="selectedReferenceIds[c.id]=$event" (click)="$event.stopPropagation()">
                <option *ngFor="let r of referenceOptions[c.id]" [value]="r.id">{{ referenceLabel(r) }}</option>
              </select>
              <span class="tag" [class.tag-green]="hasRef[c.id]" [class.tag-amber]="hasRef[c.id] === false">
                {{ hasRef[c.id] === undefined ? '...' : (hasRef[c.id] ? 'reference ready' : 'no reference yet') }}
              </span>
            </label>
          </div>
          <p class="muted" *ngIf="selectedChars.size > 2">
            Only the first two selected characters get a locked reference image per scene; extra ones
            are described in text only.
          </p>
          <p class="muted warn" *ngIf="selectedWithoutRef()">
            A selected character has no reference image. Generate and lock one in Character studio first,
            otherwise the face will change between scenes.
          </p>
        </div>

        <div class="field">
          <label for="style">Style (same for every scene)</label>
          <input id="style" type="text" [(ngModel)]="style"
                 placeholder="e.g. 3D animated feature-film look, warm golden light, rich detailed backgrounds">
        </div>

        <div class="grid3">
          <div class="field">
            <label for="engine">Video engine</label>
            <select id="engine" [(ngModel)]="engine" (ngModelChange)="onEngine()">
              <option value="MINIMAX_H3">MiniMax H3 - up to {{ maxFor('MINIMAX_H3') }}s, with sound</option>
              <option value="WAN_2_2_14B">Wan 2.2 14B - best picture, {{ maxFor('WAN_2_2_14B') }}s</option>
              <option value="WAN_2_2">Wan 2.2 5B - fastest, {{ maxFor('WAN_2_2') }}s</option>
            </select>
            <label class="check" *ngIf="engine === 'MINIMAX_H3'" style="display:flex;gap:.5rem;align-items:flex-start">
              <input type="checkbox" [(ngModel)]="useIndicTts">
              <span>Indic TTS voice (IndicF5) instead of H3 speech<br><span class="muted small">Applies to "Load Story" and new sequences.</span></span>
            </label>
          </div>
          <div class="field">
            <label for="secs">Visual shot target &mdash; {{ secondsPerScene }}s</label>
            <input id="secs" type="range" min="3" [max]="maxFor(engine)" step="1" [(ngModel)]="secondsPerScene">
            <p class="muted small">With H3, story scenes are timed by their speech: long lines are split into continuous shots so nothing is cut. This value is only used for scenes without speech.</p>
          </div>
          <div class="field">
            <label for="orient">Shape</label>
            <select id="orient" [(ngModel)]="orientation">
              <option value="vertical">Vertical 9:16 (Shorts / Reels)</option>
              <option value="horizontal">Horizontal 16:9</option>
            </select>
          </div>
        </div>

        <div class="grid3">
          <div class="field">
            <label for="cont">Continuity</label>
            <select id="cont" [(ngModel)]="continuity">
              <option value="KEYFRAMES">Keyframes - each scene starts from its own locked-character image</option>
              <option value="CHAIN">Chain - each scene starts from the last frame of the previous clip</option>
            </select>
          </div>
          <div class="field">
            <label for="xf">Crossfade between scenes &mdash; {{ crossfade }}s</label>
            <input id="xf" type="range" min="0" max="1.2" step="0.1" [(ngModel)]="crossfade">
          </div>
          <div class="field">
            <label class="check">
              <input type="checkbox" [(ngModel)]="review">
              Pause after the keyframes so I can check the faces
            </label>
            <p class="muted">Recommended: videos take a long time, keyframes take minutes.</p>
          </div>
        </div>

        <div class="field">
          <label for="scenes">Scenes - one per line: <code>what the frame looks like | what moves (optional)</code></label>
          <textarea id="scenes" rows="9" [(ngModel)]="scenesText"
                    [placeholder]="example"></textarea>
          <div class="row">
            <span class="muted">{{ parsedScenes().length }} scene(s), max {{ status?.maxScenes }}</span>
            <button type="button" class="btn" (click)="scenesText = example">Insert example</button>
          </div>
          <p class="muted">
            Tip: with MiniMax H3, H3 speaks the story. Narrator lines are off-screen voice-over (characters do not move their lips), character lines are lip-synced. Wan engines have no audio and keep using the saved TTS track.
          </p>
        </div>

        <p class="muted" *ngIf="parsedScenes().length">
          Total: about {{ totalSeconds() }}s of video ({{ parsedScenes().length }} x {{ secondsPerScene }}s).
          Each clip takes many minutes on a 16 GB card, so a long sequence can run for hours.
        </p>

        <div class="row">
          <button class="btn btn-primary" (click)="create()" [disabled]="creating || !canCreate()">
            {{ creating ? 'Starting...' : 'Create sequence' }}
          </button>
          <button class="btn" *ngIf="seq" (click)="showForm = false">Cancel</button>
        </div>
        <p class="error" *ngIf="error">{{ error }}</p>
      </section>

      <!-- ===================== PAST SEQUENCES ===================== -->
      <section class="panel" *ngIf="list.length && (!seq || showForm)">
        <h2>Your sequences</h2>
        <div class="past" *ngFor="let s of list">
          <button class="link" (click)="open(s.id)">{{ s.title }}</button>
          <span class="tag">{{ label(s.status) }}</span>
          <span class="muted">{{ s.doneScenes }}/{{ s.totalScenes }} clips</span>
        </div>
      </section>

      <!-- ===================== ACTIVE SEQUENCE ===================== -->
      <section class="panel" *ngIf="seq && !showForm">
        <div class="row between">
          <div>
            <h2>{{ seq.title }}</h2>
            <p class="muted">
              {{ engineName(seq.engine) }} &middot; {{ seq.secondsPerScene }}s per scene &middot;
              {{ seq.orientation }} &middot; {{ seq.continuity === 'CHAIN' ? 'chained' : 'keyframes' }}
              <ng-container *ngIf="seq.engine === 'MINIMAX_H3'"> &middot; voice: {{ seq.speechEngine === 'INDIC_TTS' ? 'Indic TTS (IndicF5)' : 'H3 speech' }}</ng-container>
            </p>
          </div>
          <div class="row">
            <span class="tag" [class.tag-green]="seq.status === 'COMPLETED'"
                  [class.tag-amber]="seq.status === 'PARTIAL' || seq.status === 'AWAITING_APPROVAL'"
                  [class.tag-red]="seq.status === 'FAILED'">{{ label(seq.status) }}</span>
            <button class="btn" (click)="showForm = true">New sequence</button>
          </div>
        </div>

        <div class="progress" *ngIf="seq.totalScenes">
          <div class="bar" [style.width.%]="(seq.doneScenes / seq.totalScenes) * 100"></div>
        </div>
        <p class="muted">{{ seq.doneScenes }} of {{ seq.totalScenes }} clips finished
          <span *ngIf="seq.busy"> &middot; working...</span></p>

        <p class="error" *ngIf="seq.error">{{ seq.error }}</p>

        <div class="row">
          <button class="btn btn-primary" *ngIf="!seq.busy && seq.status !== 'COMPLETED'" (click)="startVideos()">
            {{ seq.doneScenes ? 'Continue / Generate Remaining Scenes' : 'Generate All Scenes' }}
          </button>
          <button class="btn" *ngIf="seq.doneScenes === seq.totalScenes && !seq.busy" (click)="merge()">
            {{ seq.hasMerged ? 'Merge Final Again' : 'Merge Final Production Video' }}
          </button>
          <button class="btn" *ngIf="seq.busy" (click)="cancel()">Cancel after this step</button>
          <button class="btn" *ngIf="!seq.busy" (click)="remove()">Delete</button>
        </div>
        <p class="muted" *ngIf="seq.busy">
          A scene already in the GPU cannot be interrupted; cancel takes effect when it finishes.
        </p>

        <div class="final" *ngIf="seq.hasMerged">
          <h3>Final video <span class="muted" *ngIf="seq.mergedSeconds">({{ seq.mergedSeconds.toFixed(1) }}s)</span></h3>
          <video [src]="api.sequenceMergedUrl(seq.id, seq.mergedStamp)" controls [class.tall]="seq.orientation === 'vertical'"></video>
          <a class="btn" [href]="api.sequenceMergedUrl(seq.id, seq.mergedStamp)" download="sequence.mp4">Download</a>
          <span class="studio-links">
            <a class="btn" [routerLink]="['/motion-studio']" [queryParams]="{ sequenceId: seq.id, tab: 'upscale' }">✦ Upscale 720p&ndash;4K</a>
            <a class="btn" [routerLink]="['/motion-studio']" [queryParams]="{ sequenceId: seq.id, tab: 'reframe' }">Reframe</a>
            <a class="btn" [routerLink]="['/motion-studio']" [queryParams]="{ sequenceId: seq.id, tab: 'dub' }">Dub</a>
            <a class="btn" [routerLink]="['/motion-studio']" [queryParams]="{ sequenceId: seq.id, tab: 'analyze' }">Analyze</a>
          </span>
        </div>

        <h3>Scenes</h3>
        <div class="scenes">
          <article class="scene" *ngFor="let sc of seq.scenes" [class.failed]="sc.step === 'FAILED'">
            <div class="scene-head">
              <strong>Scene {{ sc.index + 1 }}</strong>
              <span class="tag" [class.tag-green]="sc.step === 'DONE'" [class.tag-red]="sc.step === 'FAILED'"
                    [class.tag-amber]="sc.step === 'KEYFRAME_RUNNING' || sc.step === 'VIDEO_RUNNING'">
                {{ stepLabel(sc.step) }}
              </span>
            </div>

            <div class="media">
              <img *ngIf="sc.hasKeyframe" [src]="api.sequenceKeyframeUrl(seq.id, sc.index, sc.keyframeStamp)"
                   alt="Keyframe" [class.wide]="seq.orientation === 'horizontal'">
              <div class="placeholder" *ngIf="!sc.hasKeyframe">
                {{ sc.step === 'KEYFRAME_RUNNING' ? 'drawing keyframe...' : 'no keyframe yet' }}
              </div>
              <video *ngIf="sc.hasClip" [src]="api.sequenceClipUrl(seq.id, sc.index, sc.clipStamp)" controls
                     [class.wide]="seq.orientation === 'horizontal'"></video>
              <a class="btn scene-download" *ngIf="sc.hasClip" [href]="api.sequenceClipUrl(seq.id, sc.index, sc.clipStamp)" [download]="'scene-' + (sc.index + 1) + '.mp4'">Download scene video</a>
              <div class="placeholder" *ngIf="!sc.hasClip && sc.step === 'VIDEO_RUNNING'">making video... (many minutes)</div>
            </div>

            <p class="text">{{ sc.visual }}</p>
            <p class="text muted" *ngIf="sc.motion">&#9654; {{ sc.motion }}</p>
            <div class="audio-info" *ngIf="sc.narration || sc.dialogue || sc.audioSpecJson || sc.musicPreset">
              <strong>Scene audio</strong>
              <p *ngIf="sc.narration"><b>Narration:</b> {{ sc.narration }}</p>
              <p *ngIf="sc.dialogue"><b>Dialogue:</b> {{ sc.dialogue }}</p>
              <p *ngIf="sc.musicPreset"><b>Music:</b> {{ sc.musicPreset }}</p>
              <p *ngIf="sc.audioSpecJson"><b>Ambience / SFX:</b> {{ sc.audioSpecJson }}</p>
            </div>
            <p class="muted" *ngIf="sc.hasClip">
              {{ sc.clipSeconds ? sc.clipSeconds.toFixed(1) + 's' : '' }}
              <span *ngIf="sc.requestedSeconds && sc.requestedSeconds < seq.secondsPerScene">
                (shortened from {{ seq.secondsPerScene }}s - GPU ran out of memory)</span>
              <span *ngIf="sc.videoMillis"> &middot; took {{ (sc.videoMillis / 60000).toFixed(1) }} min</span>
            </p>
            <p class="error small" *ngIf="sc.error">{{ sc.error }}</p>

            <div class="edit" *ngIf="editing === sc.index">
              <textarea rows="3" [(ngModel)]="editVisual" placeholder="What the frame looks like"></textarea>
              <textarea rows="2" [(ngModel)]="editMotion" placeholder="What moves"></textarea>
              <div class="row">
                <button class="btn btn-primary" (click)="saveEdit(sc)">Save text</button>
                <button class="btn" (click)="editing = -1">Close</button>
              </div>
            </div>

            <div class="row actions" *ngIf="!seq.busy">
              <button class="btn" (click)="startEdit(sc)">Edit text</button>
              <button class="btn" (click)="newKeyframe(sc)">New keyframe</button>
              <label class="btn upload">Upload keyframe
                <input type="file" accept="image/png,image/jpeg,image/webp" (change)="uploadKeyframe(sc, $event)" hidden>
              </label>
              <button class="btn" *ngIf="sc.hasKeyframe && seq.status !== 'AWAITING_APPROVAL'" (click)="redoVideo(sc)">
                Redo video
              </button>
            </div>
          </article>
        </div>
      </section>
    </ng-container>
  `,
  styles: [`
    .page-head { margin-bottom: 1.4rem; max-width: 760px; }
    .panel { max-width: 1100px; display: flex; flex-direction: column; gap: 1.1rem; margin-bottom: 1.4rem; }
    .field { display: flex; flex-direction: column; gap: .4em; }
    .grid3 { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 1rem; }
    .picker-row { display: flex; gap: .5rem; flex-wrap: wrap; }
    .picker-row select { flex: 1; min-width: 140px; }
    .chars { display: flex; flex-wrap: wrap; gap: .5rem; margin-top: .4rem; }
    .char { display: flex; align-items: center; gap: .5rem; padding: .35rem .7rem; border: 1px solid var(--border);
            border-radius: 999px; background: var(--surface); cursor: pointer; }
    .char select { max-width: 210px; font-size: .72rem; padding: .18rem .3rem; }
    .check { display: flex; gap: .5rem; align-items: center; }
    .row { display: flex; gap: .6rem; align-items: center; flex-wrap: wrap; }
    .row.between { justify-content: space-between; align-items: flex-start; }
    .warn { color: var(--danger); }
    .error { color: var(--danger); }
    .error.small { font-size: .85rem; }
    .past { display: flex; gap: .8rem; align-items: center; padding: .3rem 0; }
    .link { background: none; border: none; color: var(--accent); cursor: pointer; font-size: 1rem; padding: 0; text-align: left; }
    .progress { height: 8px; background: var(--surface); border: 1px solid var(--border); border-radius: 999px; overflow: hidden; }
    .bar { height: 100%; background: var(--accent); transition: width .4s; }
    .final { display: flex; flex-direction: column; gap: .7rem; align-items: flex-start; }
    .final video { max-width: 100%; border-radius: 10px; border: 1px solid var(--border); max-height: 70vh; }
    .final video.tall { height: 70vh; }
    .scenes { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 1rem; }
    .scene { border: 1px solid var(--border); border-radius: 12px; padding: .8rem; background: var(--surface);
             display: flex; flex-direction: column; gap: .5rem; }
    .scene.failed { border-color: var(--danger); }
    .scene-head { display: flex; justify-content: space-between; align-items: center; }
    .media { display: flex; flex-direction: column; gap: .5rem; }
    .media img, .media video { width: 100%; border-radius: 8px; border: 1px solid var(--border); background: #000;
                               aspect-ratio: 9 / 16; object-fit: contain; }
    .media img.wide, .media video.wide { aspect-ratio: 16 / 9; }
    .placeholder { display: flex; align-items: center; justify-content: center; text-align: center; padding: 1rem;
                   border: 1px dashed var(--border); border-radius: 8px; color: var(--muted, #888); font-size: .85rem; min-height: 60px; }
    .text { font-size: .9rem; margin: 0; }
    .edit { display: flex; flex-direction: column; gap: .5rem; }
    .actions .btn { font-size: .8rem; padding: .3rem .6rem; }
    .upload { cursor: pointer; }
    .story-loader { padding: 1rem; border: 1px solid var(--border); border-radius: 12px; background: var(--surface); }
    .audio-info { padding: .7rem; border-radius: 9px; background: rgba(255,255,255,.025); border: 1px solid var(--border); font-size: .82rem; }
    .audio-info p { margin: .25rem 0; }
  `]
})
export class VideoSequenceComponent implements OnInit, OnDestroy {
  status?: SequenceStatusInfo;
  statusLoaded = false;

  // form
  title = '';
  style = '';
  engine: 'MINIMAX_H3' | 'WAN_2_2_14B' | 'WAN_2_2' = 'MINIMAX_H3';
  // Maximum visual shot target for H3. Existing stories ignore this and use
  // their measured narration/TTS duration; long scenes are split automatically.
  secondsPerScene = 8;
  orientation: 'vertical' | 'horizontal' = 'vertical';
  continuity: 'KEYFRAMES' | 'CHAIN' = 'KEYFRAMES';
  crossfade = 0.4;
  review = true;
  /** INDIC_TTS instead of H3 speech - one or the other (H3 engine only). */
  useIndicTts = false;
  scenesText = '';
  readonly example =
    'Milo, a cheerful boy in a yellow shirt, points up at a glowing golden mango hanging in a big sunny tree, Momo the puppy beside him | Milo points and gasps, Momo wags her tail, leaves sway, slow push-in\n' +
    'The mango zooms across the garden like a comet leaving golden sparkles, Milo and Momo run after it | Milo and Momo run along the garden path, flowers bounce, camera tracks sideways\n' +
    'Milo and Momo crouch at a big hollow in the tree trunk where the mango glows inside | They lean in curiously, glowing light flickers on their faces, slow push-in';

  // pickers
  projects: Project[] = [];
  universes: Universe[] = [];
  characters: Character[] = [];
  projectId?: string;
  episodeId?: string;
  episodes: any[] = [];
  loadingStory = false;
  universeId?: string;
  selectedChars = new Set<string>();
  hasRef: Record<string, boolean | undefined> = {};
  referenceOptions: Record<string, CharacterReference[]> = {};
  selectedReferenceIds: Record<string, string> = {};

  creating = false;
  error = '';
  showForm = false;

  // sequences
  list: SequenceView[] = [];
  seq?: SequenceView;
  editing = -1;
  editVisual = '';
  editMotion = '';

  private pollHandle?: ReturnType<typeof setInterval>;

  constructor(public api: ApiService, private route: ActivatedRoute) {}

  ngOnInit(): void {
    this.api.sequenceStatus().subscribe({
      next: s => { this.status = s; this.secondsPerScene = Math.min(this.secondsPerScene, this.maxFor(this.engine)); this.statusLoaded = true; },
      error: () => {
        this.status = { available: false, reason: 'Could not reach the backend.', wanMaxSeconds: 5, wan14bMaxSeconds: 5, h3MaxSeconds: 10, maxScenes: 12 };
        this.statusLoaded = true;
      }
    });
    this.api.listProjects().subscribe({ next: p => { this.projects = p; }, error: () => { /* picker stays empty */ } });
    // Storyboard / Story Approval "Produce video" links here with ?id=<sequence>.
    const openId = this.route.snapshot.queryParamMap.get('id');
    if (openId) { this.open(openId); }
    this.refreshList(!openId);
  }

  ngOnDestroy(): void { this.stopPolling(); }

  referenceLabel(r: CharacterReference): string {
    const state = r.locked ? 'locked' : (r.primary ? 'primary' : 'saved');
    const when = r.createdAt ? new Date(r.createdAt).toLocaleString() : '';
    return when ? `${state} · ${when}` : state;
  }

  // ---- form helpers
  maxFor(engine: string): number {
    const s = this.status;
    const v = engine === 'MINIMAX_H3' ? s?.h3MaxSeconds : engine === 'WAN_2_2_14B' ? s?.wan14bMaxSeconds : s?.wanMaxSeconds;
    return v && v > 0 ? Math.floor(v) : (engine === 'MINIMAX_H3' ? 10 : 5);
  }

  speechEngine(): 'H3' | 'INDIC_TTS' { return this.engine === 'MINIMAX_H3' && this.useIndicTts ? 'INDIC_TTS' : 'H3'; }

  onEngine(): void { this.secondsPerScene = Math.min(this.secondsPerScene, this.maxFor(this.engine)); }

  parsedScenes(): { visual: string; motion: string }[] {
    return this.scenesText.split('\n').map(l => l.trim()).filter(l => l.length > 0).map(l => {
      const i = l.indexOf('|');
      return i < 0 ? { visual: l, motion: '' } : { visual: l.slice(0, i).trim(), motion: l.slice(i + 1).trim() };
    }).filter(s => s.visual.length > 0);
  }

  totalSeconds(): number { return this.parsedScenes().length * this.secondsPerScene; }

  canCreate(): boolean {
    const n = this.parsedScenes().length;
    return n > 0 && n <= (this.status?.maxScenes || 12);
  }

  onProject(): void {
    this.universes = []; this.characters = []; this.universeId = undefined; this.episodes = []; this.episodeId = undefined;
    if (!this.projectId) { return; }
    this.api.listUniverses(this.projectId).subscribe({ next: u => { this.universes = u; }, error: () => { this.error = 'Could not load universes.'; } });
    this.api.listEpisodes(this.projectId).subscribe({ next: e => { this.episodes = e; }, error: () => { this.error = 'Could not load stories.'; } });
  }

  onUniverse(): void {
    this.characters = [];
    if (!this.universeId) { return; }
    this.api.listCharacters(this.universeId).subscribe({
      next: chars => {
        this.characters = chars;
        chars.forEach(c => {
          this.api.listCharacterReferences(c.id).subscribe({
            next: refs => { const list=refs||[]; this.referenceOptions[c.id]=list; this.hasRef[c.id]=list.length>0; const preferred=list.find((r:CharacterReference)=>!!r.locked)||list.find((r:CharacterReference)=>!!r.primary)||list[0]; if(preferred) this.selectedReferenceIds[c.id]=preferred.id; },
            error: () => { this.hasRef[c.id] = false; }
          });
        });
      },
      error: () => { this.error = 'Could not load characters.'; }
    });
  }

  toggleChar(id: string): void {
    if (this.selectedChars.has(id)) { this.selectedChars.delete(id); } else { this.selectedChars.add(id); }
  }

  selectedWithoutRef(): boolean {
    return [...this.selectedChars].some(id => this.hasRef[id] === false);
  }

  loadStory(): void {
    if (!this.episodeId) return;
    this.loadingStory = true; this.error = '';
    this.api.createSequenceFromEpisode(this.episodeId, { engine: this.engine, orientation: this.orientation, crossfadeSeconds: this.crossfade, continuity: this.continuity, reviewKeyframes: false, speechEngine: this.speechEngine() }).subscribe({
      next: s => { this.loadingStory = false; this.showForm = false; this.seq = s; this.refreshList(false); this.startPolling(); },
      error: err => { this.loadingStory = false; this.error = err?.error?.message || 'Could not load the story.'; }
    });
  }

  create(): void {
    this.creating = true; this.error = '';
    this.api.createSequence({
      title: this.title.trim(), style: this.style.trim(), characterIds: [...this.selectedChars], characterReferenceIds: Object.fromEntries([...this.selectedChars].filter(id=>!!this.selectedReferenceIds[id]).map(id=>[id,this.selectedReferenceIds[id]])),
      engine: this.engine, secondsPerScene: this.secondsPerScene, orientation: this.orientation,
      crossfadeSeconds: this.crossfade, continuity: this.continuity, reviewKeyframes: this.review, speechEngine: this.speechEngine(),
      scenes: this.parsedScenes()
    }).subscribe({
      next: s => { this.creating = false; this.showForm = false; this.seq = s; this.refreshList(false); this.startPolling(); },
      error: err => { this.creating = false; this.error = err?.error?.message || 'Could not create the sequence.'; }
    });
  }

  // ---- list / open
  refreshList(openLatestBusy: boolean): void {
    this.api.listSequences().subscribe({
      next: l => {
        this.list = l;
        if (openLatestBusy && !this.seq) {
          const active = l.find(s => s.busy) ?? undefined;
          if (active) { this.seq = active; this.startPolling(); }
        }
      },
      error: () => { /* list is optional */ }
    });
  }

  open(id: string): void {
    this.api.getSequence(id).subscribe({
      next: s => { this.seq = s; this.showForm = false; this.error = ''; this.startPolling(); },
      error: () => { this.error = 'Could not open that sequence.'; }
    });
  }

  // ---- actions
  startVideos(): void { this.act(this.api.startSequenceVideos(this.seq!.id)); }
  merge(): void { this.act(this.api.mergeSequence(this.seq!.id)); }
  cancel(): void { this.act(this.api.cancelSequence(this.seq!.id)); }
  newKeyframe(sc: SequenceSceneView): void { this.act(this.api.regenerateSequenceKeyframe(this.seq!.id, sc.index)); }
  redoVideo(sc: SequenceSceneView): void { this.act(this.api.regenerateSequenceVideo(this.seq!.id, sc.index)); }

  remove(): void {
    if (!this.seq || !confirm('Delete this sequence and all its files?')) { return; }
    const id = this.seq.id;
    this.api.deleteSequence(id).subscribe({
      next: () => { this.seq = undefined; this.stopPolling(); this.refreshList(false); },
      error: err => { this.error = err?.error?.message || 'Could not delete it.'; }
    });
  }

  startEdit(sc: SequenceSceneView): void { this.editing = sc.index; this.editVisual = sc.visual; this.editMotion = sc.motion; }

  saveEdit(sc: SequenceSceneView): void {
    this.api.updateSequenceScene(this.seq!.id, sc.index, this.editVisual, this.editMotion).subscribe({
      next: () => { this.editing = -1; this.reload(); },
      error: err => { this.error = err?.error?.message || 'Could not save.'; }
    });
  }

  uploadKeyframe(sc: SequenceSceneView, event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) { return; }
    this.api.uploadSequenceKeyframe(this.seq!.id, sc.index, file).subscribe({
      next: () => { this.reload(); },
      error: err => { this.error = err?.error?.message || 'Could not upload that image.'; }
    });
  }

  private act(call: ReturnType<ApiService['mergeSequence']>): void {
    this.error = '';
    call.subscribe({
      next: () => { this.reload(); this.startPolling(); },
      error: err => { this.error = err?.error?.message || 'That action failed.'; }
    });
  }

  private reload(): void {
    if (!this.seq) { return; }
    this.api.getSequence(this.seq.id).subscribe({ next: s => { this.seq = s; }, error: () => { /* keep last view */ } });
  }

  // ---- polling (3 s while something is running)
  private startPolling(): void {
    this.stopPolling();
    this.pollHandle = setInterval(() => {
      if (!this.seq) { this.stopPolling(); return; }
      this.api.getSequence(this.seq.id).subscribe({
        next: s => {
          this.seq = s;
          if (!s.busy && !['KEYFRAMES_RUNNING', 'VIDEOS_RUNNING', 'MERGING'].includes(s.status)) {
            this.stopPolling();
            this.refreshList(false);
          }
        },
        error: () => { this.error = 'Lost track of the sequence (backend restarted?).'; this.stopPolling(); }
      });
    }, 3000);
  }

  private stopPolling(): void {
    if (this.pollHandle) { clearInterval(this.pollHandle); this.pollHandle = undefined; }
  }

  // ---- labels
  label(s: string): string {
    const m: Record<string, string> = {
      KEYFRAMES_RUNNING: 'drawing keyframes', AWAITING_APPROVAL: 'check keyframes', VIDEOS_RUNNING: 'making videos',
      MERGING: 'merging', COMPLETED: 'done', PARTIAL: 'needs attention', FAILED: 'failed', CANCELLED: 'cancelled'
    };
    return m[s] || s;
  }

  stepLabel(s: string): string {
    const m: Record<string, string> = {
      PENDING: 'waiting', KEYFRAME_RUNNING: 'keyframe...', KEYFRAME_READY: 'keyframe ready', VIDEO_RUNNING: 'video...',
      DONE: 'done', FAILED: 'failed'
    };
    return m[s] || s;
  }

  engineName(e: string): string {
    return e === 'MINIMAX_H3' ? 'MiniMax H3' : e === 'WAN_2_2_14B' ? 'Wan 2.2 14B' : 'Wan 2.2 5B';
  }
}
