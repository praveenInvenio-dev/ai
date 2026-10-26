import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin, Observable, of, switchMap, map } from 'rxjs';
import { H3CastPanelComponent } from '../../shared/h3-cast-panel/h3-cast-panel.component';
import { CastCharacter, CharacterCastService } from '../../shared/h3-cast-panel/character-cast.service';
import { ApiService, VideoGenerationStatus, VideoGenerationJob, CameraPreset, SequenceView } from '../../services/api.service';
import { ActivatedRoute, Router } from '@angular/router';
import { Project, Episode, SceneDto, VoiceProfile } from '../../models/models';

/**
 * Standalone "Video generation" page: animate one uploaded image from a text
 * prompt via the same Wan/ComfyUI pipeline the per-scene story pipeline uses
 * (ProviderGateway -> ComfyUIVideoProvider) - no project/episode required.
 * Good for testing prompts/settings, or animating a single still on its own,
 * before committing to it inside a full story.
 */
@Component({
  selector: 'app-video-generation',
  standalone: true,
  imports: [CommonModule, FormsModule, H3CastPanelComponent],
  template: `
    <header class="page-head">
      <h1>Video generation</h1>
      <p class="muted">
        Animate a still image, generate a single scene from text, or create a complete story.
        Story mode reuses the Story Engine and MiniMax H3 pipeline to plan narration, dialogue,
        camera directions, SFX and music, render scene clips, and merge the final video.
      </p>
    </header>

    <section class="panel" *ngIf="statusLoaded && !status?.available">
      <p class="tag tag-amber">Not available</p>
      <p class="muted">{{ status?.reason }}</p>
      <p class="muted">
        See <code>LOCAL_AI_ANIMATION_*</code> in your <code>.env</code> and
        <code>COMFYUI_MODEL_DOWNLOAD.md</code> to set this up.
      </p>
    </section>

    <section class="panel" *ngIf="statusLoaded">
      <div class="field workflow-field" *ngIf="mode !== 'story'">
        <label for="workflow">Generation workflow</label>
        <select id="workflow" [(ngModel)]="workflow" (ngModelChange)="clampDuration()">
          <option value="WAN_2_2">Wan 2.2 (ComfyUI)</option>
          <option value="WAN_2_2_14B">Wan 2.2 14B I2V - best quality, needs starting image (ComfyUI)</option>
          <option value="MINIMAX_H3">MiniMax H3 (ComfyUI)</option>
        </select>
        <p class="muted">Both options use the local ComfyUI server. MiniMax H3 is the native/open-weights ComfyUI workflow, not the MiniMax API.</p>
      </div>

      <div class="field">
        <label>Mode</label>
        <div class="mode-toggle">
          <button type="button" class="pill" [class.selected]="mode === 'i2v'" (click)="setMode('i2v')">Image to video</button>
          <button type="button" class="pill" [class.selected]="mode === 't2v'" (click)="setMode('t2v')">Text to video</button>
          <button type="button" class="pill story-pill" [class.selected]="mode === 'story'" (click)="setMode('story')">Story to video · H3</button>
        </div>
      </div>

      <section class="story-production" *ngIf="mode === 'story'">
        <div class="story-production-head">
          <strong>AI story production</strong>
          <span class="muted small">Story idea + named character references → 5-second H3 shots → automatic merge</span>
        </div>
        <p class="warning" *ngIf="showStoryProviderWarning">Story drafting and character-reference planning are available independently. H3 rendering will require the local video provider to be available after you review the story.</p>
        <p class="muted">
          Enter a story idea and add your characters (name + image + master prompt, up to 2 per scene). AI Story Studio writes the complete story internally, creates short scenes with narration/dialogue, camera movement, ambience, timed SFX and music, renders each as a 5-second MiniMax H3 clip, then merges the clips automatically. You do not need to open Create Story, approve keyframes, or start rendering a second time.
        </p>
        <app-h3-cast-panel [startOpen]="true" (castChange)="storyCast = $event"></app-h3-cast-panel>
        <p class="muted small" *ngIf="storyCast.length">
          Story will star: <strong>{{ castNames() }}</strong>. The writer is told to use these exact names, and every 5-second H3 clip that names them gets their locked reference image (up to 2 per clip).
        </p>
        <div class="story-options">
          <div class="field">
            <label for="storyDuration">Story length</label>
            <select id="storyDuration" [(ngModel)]="storyDurationSeconds">
              <option [ngValue]="30">30-second story · about 6 scenes</option>
              <option [ngValue]="60">1-minute story · about 12 scenes</option>
              <option [ngValue]="180">3-minute story · about 36 scenes</option>
              <option [ngValue]="300">5-minute story · about 60 scenes</option>
            </select>
          </div>
          <div class="field">
            <label for="storyGenre">Story genre</label>
            <select id="storyGenre" [(ngModel)]="storyGenre">
              <option value="Cinematic adventure">Cinematic adventure</option>
              <option value="Fantasy">Fantasy</option>
              <option value="Comedy">Comedy</option>
              <option value="Emotional drama">Emotional drama</option>
              <option value="Action thriller">Action thriller</option>
              <option value="Educational story">Educational story</option>
              <option value="Children's adventure">Children's adventure</option>
              <option value="Science fiction">Science fiction</option>
              <option value="Folklore and mythology">Folklore and mythology</option>
            </select>
          </div>
          <div class="field">
            <label for="storyLanguage">Language</label>
            <select id="storyLanguage" [(ngModel)]="storyLanguage">
              <option value="English">English</option>
              <option value="Indian English">Indian English</option>
              <option value="Hindi">Hindi</option>
              <option value="Kannada">Kannada</option>
              <option value="Telugu">Telugu</option>
              <option value="Tamil">Tamil</option>
              <option value="Malayalam">Malayalam</option>
              <option value="Marathi">Marathi</option>
              <option value="Bengali">Bengali</option>
              <option value="Hinglish">Hinglish</option>
            </select>
          </div>
          <div class="field">
            <label for="storyVisualStyle">Visual style</label>
            <select id="storyVisualStyle" [(ngModel)]="storyVisualStyle">
              <option value="cinematic 3D animation">Cinematic 3D animation</option>
              <option value="photorealistic cinematic">Photorealistic cinematic</option>
              <option value="stylized animated film">Stylized animated film</option>
              <option value="anime cinematic">Anime cinematic</option>
              <option value="storybook illustration">Storybook illustration</option>
              <option value="grounded live-action film">Grounded live-action film</option>
            </select>
          </div>
          <div class="field">
            <label for="storyNarrationTrack">Narration style</label>
            <select id="storyNarrationTrack" [(ngModel)]="storyNarrationTrack">
              <option value="STORY_DRIVEN_MENTOR">Story-driven narrator · emotional and cinematic</option>
              <option value="HUMOROUS_EXPLAINER">Humorous narrator · playful, with tasteful humor</option>
              <option value="LOGIC_FIRST_ANALYST">Logic-first narrator · clear cause and effect</option>
              <option value="PROFESSIONAL_INSTRUCTOR">Professional instructor · confident and concise</option>
              <option value="SIMPLE_BEGINNER">Beginner-friendly · patient and simple</option>
              <option value="STORYTELLING_TEACHER">Storytelling teacher · warm and curious</option>
            </select>
          </div>
          <div class="field">
            <label for="storyOrientation">Video format</label>
            <select id="storyOrientation" [(ngModel)]="storyOrientation">
              <option value="vertical">Vertical · Reels / Shorts</option>
              <option value="horizontal">Landscape · YouTube</option>
            </select>
          </div>
        </div>
      </section>

      <div class="field" *ngIf="mode === 'i2v'">
        <label>Use an image from a story (optional)</label>
        <div class="picker-row">
          <select [(ngModel)]="selectedProjectId" (ngModelChange)="onProjectChange()">
            <option [ngValue]="undefined">Project...</option>
            <option *ngFor="let p of projects" [ngValue]="p.id">{{ p.name }}</option>
          </select>
          <select [(ngModel)]="selectedEpisodeId" (ngModelChange)="onEpisodeChange()" [disabled]="!selectedProjectId">
            <option [ngValue]="undefined">Episode...</option>
            <option *ngFor="let e of episodes" [ngValue]="e.id">{{ e.title || 'Untitled episode' }}</option>
          </select>
          <select [(ngModel)]="selectedSceneId" [disabled]="!selectedEpisodeId">
            <option [ngValue]="undefined">Scene...</option>
            <option *ngFor="let s of scenes" [ngValue]="s.id">Scene {{ s.sceneNumber }} - {{ (s.purpose || '').slice(0, 40) }}</option>
          </select>
          <button type="button" class="btn" [disabled]="!selectedSceneId || loadingScene" (click)="loadFromScene()">
            {{ loadingScene ? 'Loading...' : 'Load' }}
          </button>
        </div>
        <p class="muted" *ngIf="scenesLoaded && scenes.length === 0">
          This episode has no generated scene images yet.
        </p>
      </div>

      <div class="field" *ngIf="mode === 'i2v'">
        <label for="image">Starting image</label>
        <input id="image" type="file" accept="image/png,image/jpeg,image/webp"
               (change)="onFileSelected($event)">
        <img *ngIf="previewUrl" [src]="previewUrl" class="preview" alt="Starting frame preview">
      </div>

      <div class="field">
        <label for="prompt">{{ mode === 'story' ? 'Story idea / premise' : mode === 't2v' ? 'Scene description' : 'Motion prompt' }}</label>
        <textarea id="prompt" rows="3" [(ngModel)]="prompt"
                  [placeholder]="mode === 'story'
                    ? 'Describe the characters, goal, conflict, setting and emotional ending. Example: Two siblings at a festival rescue a lost child with help from a mysterious guardian.'
                    : mode === 't2v'
                      ? 'e.g. a small cartoon rabbit waving in a sunny forest clearing, butterflies drifting past'
                      : 'e.g. the character waves and smiles, gentle camera push-in'"></textarea>
        <p class="muted small" *ngIf="mode === 'story'">This is the premise, not a full script. The system expands it internally into a complete story and starts H3 rendering automatically after using your character reference.</p>
        <ng-container *ngIf="mode !== 'story'">
        <label for="cameraPreset" style="margin-top:.5rem">Camera preset <span class="muted small">(Motion &amp; effects studio)</span></label>
        <select id="cameraPreset" [(ngModel)]="cameraPresetId">
          <option value="">None - use only the prompt</option>
          <optgroup *ngFor="let cat of presetCategories()" [label]="cat">
            <option *ngFor="let p of presetsIn(cat)" [value]="p.id">{{ p.name }}</option>
          </optgroup>
        </select>
        </ng-container>
      </div>

      <div class="field" *ngIf="mode !== 'story'">
        <label for="negative">Negative prompt (optional)</label>
        <input id="negative" type="text" [(ngModel)]="negativePrompt"
               placeholder="e.g. blurry, distorted, extra limbs">
      </div>

      <div class="field" *ngIf="mode !== 'story'">
        <label for="duration">
          Duration &mdash; {{ durationSeconds.toFixed(1) }}s
          (max {{ maxSeconds().toFixed(1) }}s for this engine)
        </label>
        <p class="warning" *ngIf="audioDurationWarning">{{ audioDurationWarning }}</p>
        <input id="duration" type="range" min="1" [max]="maxSeconds()"
               step="0.5" [(ngModel)]="durationSeconds">
      </div>

      <div class="field" *ngIf="workflow === 'MINIMAX_H3' && mode !== 'story'">
        <app-h3-cast-panel></app-h3-cast-panel>
        <p class="muted small">Name a character in the prompt (e.g. "Asha waves at Ravi") and H3 receives that character's locked reference automatically.</p>
      </div>
      <div class="field audio-panel" *ngIf="workflow === 'MINIMAX_H3' && mode !== 'story'">
        <label>Native H3 speech &amp; sound</label>
        <label class="check" style="display:flex;gap:.5rem;align-items:flex-start">
          <input type="checkbox" [(ngModel)]="useIndicTts">
          <span><strong>Use Indic TTS voice (IndicF5) instead of H3 speech</strong><br>
          <span class="muted small">IndicF5 speaks the lines (native Indian-language pronunciation); H3 only animates &mdash; dialogue lip-synced to the TTS voice &mdash; and adds ambience/SFX/music under it. Needs the <code>tts-indic</code> service.</span></span>
        </label>
        <p class="muted">H3 speaks every line. <strong>Narration is off-screen voice-over</strong> &mdash; characters do not move their lips for it. <strong>Dialogue</strong> lines are lip-synced by the named character. Long text is split into several continuous shots automatically, so the clip length follows the speech (the slider is only used when there is no speech).</p>
        <label for="narration">Narration / voice-over (no lip sync)</label>
        <textarea id="narration" rows="2" [(ngModel)]="narrationText" (ngModelChange)="markAudioEdited()" placeholder="Scene narration"></textarea>
        <label for="dialogue">On-screen dialogue (lip-synced) &mdash; one per line: <code>Name: line [delivery]</code></label>
        <textarea id="dialogue" rows="3" [(ngModel)]="dialogueText" (ngModelChange)="markAudioEdited()" placeholder="Advik: Amma, look! [excited]"></textarea>
        <label for="audioDirection">Background ambience / SFX / music</label>
        <textarea id="audioDirection" rows="4" [(ngModel)]="audioDirection" placeholder="Ambience: ...&#10;Sound effects: ...&#10;Music: mood, intensity 0.3"></textarea>
        <p class="muted small" *ngIf="loadedSceneId && !sceneAudioEdited">Using the scene's original line order (narration and dialogue interleaved).</p>
      </div>
      <div class="field" *ngIf="workflow !== 'MINIMAX_H3' && mode !== 'story'">
        <label for="narration">Narration (optional)</label>
        <textarea id="narration" rows="2" [(ngModel)]="narrationText" placeholder="Add a voiceover track to the generated video"></textarea>
      </div>
      <div class="field" *ngIf="narrationText && workflow !== 'MINIMAX_H3' && mode !== 'story'">
        <label for="voice">Voice</label>
        <select id="voice" [(ngModel)]="voiceProfileId">
          <option value="">Default voice</option>
          <option *ngFor="let vp of voiceProfiles" [value]="vp.id">{{ vp.name }} ({{ vp.provider }})</option>
        </select>
      </div>

      <button class="btn btn-primary" (click)="submit()"
              [disabled]="submitting || (mode !== 'story' && !status?.available) || (mode === 'i2v' && !selectedFile) || (mode === 'story' && !storyCast.length) || !prompt.trim()">
        {{ submitting ? (mode === 'story' ? storyStatusText || 'Starting story production…' : 'Working...') : (mode === 'story' ? 'Generate full H3 story video' : 'Generate video') }}
      </button>

      <p class="error" *ngIf="error">{{ error }}</p>

      <div class="job story-job" *ngIf="storySequence">
        <div class="story-progress-head">
          <strong>{{ storySequence.title || 'Story video production' }}</strong>
          <span class="pill">{{ storySequence.status }}</span>
        </div>
        <p class="muted">{{ storyStatusText }}</p>
        <div class="progress-track"><div class="progress-fill" [style.width.%]="storyProgress"></div></div>
        <p class="muted small">{{ storySequence.doneScenes }} / {{ storySequence.totalScenes }} scene clips complete · target {{ storyDurationSeconds }} seconds</p>
        <p class="error" *ngIf="storySequence.error">{{ storySequence.error }}</p>
        <ng-container *ngIf="storySequence.hasMerged && storyResultUrl">
          <video [src]="storyResultUrl" controls autoplay></video>
          <a class="btn" [href]="storyResultUrl" download="ai-story-studio-story.mp4">Download complete story video</a>
        </ng-container>
      </div>

      <div class="job" *ngIf="job">
        <p class="muted" *ngIf="job.status === 'QUEUED'">Queued - waiting for a free GPU slot...</p>
        <p class="muted" *ngIf="job.status === 'RUNNING'">
          Generating... this can take several minutes on a Wan video call.
        </p>
        <p class="error" *ngIf="job.status === 'FAILED'">{{ job.errorMessage }}</p>
        <ng-container *ngIf="job.status === 'SUCCEEDED'">
          <video [src]="resultUrl" controls autoplay loop></video>
          <p class="muted">seed: {{ job.seedUsed }} &middot; workflow: {{ job.workflowUsed }}</p>
          <a class="btn" [href]="resultUrl" download>Download clip</a>
        </ng-container>
      </div>
    </section>
  `,
  styles: [`
    .page-head { margin-bottom: 1.6rem; max-width: 720px; }
    .panel { max-width: 760px; display: flex; flex-direction: column; gap: 1.2rem; }
    .workflow-field { padding: .9rem; border: 1px solid var(--border); border-radius: 12px; background: var(--surface); }
    .field { display: flex; flex-direction: column; gap: 0.4em; }
    .mode-toggle { display: flex; gap: 0.4rem; flex-wrap: wrap; }
    .story-pill { border-color: rgba(73,201,189,.4); }
    .story-production { display:flex; flex-direction:column; gap:.9rem; padding:1rem; border:1px solid rgba(73,201,189,.35); border-radius:14px; background:linear-gradient(135deg,rgba(73,201,189,.07),rgba(183,94,255,.05)); }
    .story-production-head { display:flex; flex-direction:column; gap:.25rem; }
    .story-options { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:.85rem; }
    .character-preview { max-height:260px; object-fit:contain; }
    .story-progress-head { display:flex; align-items:center; justify-content:space-between; gap:.7rem; flex-wrap:wrap; }
    .progress-track { height:8px; border-radius:999px; overflow:hidden; background:var(--surface); border:1px solid var(--border); }
    .progress-fill { height:100%; background:var(--accent); transition:width .25s ease; }
    @media(max-width:640px) { .story-options { grid-template-columns:1fr; } }
    .pill {
      cursor: pointer; font-size: 0.85rem; padding: 0.4rem 0.9rem; border-radius: 999px;
      background: var(--surface); color: inherit; border: 1px solid var(--border);
    }
    .pill:hover { border-color: var(--accent); }
    .pill.selected { border-color: var(--accent); color: var(--accent); }
    .picker-row { display: flex; gap: 0.5rem; flex-wrap: wrap; align-items: center; }
    .picker-row select { flex: 1; min-width: 120px; }
    .audio-panel { padding: .9rem; border: 1px solid var(--border); border-radius: 12px; background: var(--surface); }
    .preview { max-width: 100%; max-height: 220px; border-radius: 8px; margin-top: 0.6em;
               border: 1px solid var(--border); object-fit: contain; }
    video { max-width: 100%; border-radius: 8px; border: 1px solid var(--border); }
    .job { display: flex; flex-direction: column; gap: 0.8em; margin-top: 0.6em; }
    .error { color: var(--danger); }
  `]
})
export class VideoGenerationComponent implements OnInit, OnDestroy {
  status?: VideoGenerationStatus;
  statusLoaded = false;

  /** Keep strict-template null checks in one TypeScript-narrowed expression. */
  get showStoryProviderWarning(): boolean {
    const status = this.status;
    return !status?.available || (status?.h3MaxSeconds != null && status.h3MaxSeconds <= 0);
  }

  selectedFile?: File;
  previewUrl?: string;
  mode: 'i2v' | 't2v' | 'story' = 'i2v';
  workflow: 'WAN_2_2' | 'WAN_2_2_14B' | 'MINIMAX_H3' = 'WAN_2_2';
  prompt = '';
  negativePrompt = '';
  durationSeconds = 4;
  narrationText = '';
  dialogueText = '';
  audioDirection = '';
  voiceProfileId = '';
  audioDurationWarning = '';
  /** Story scene whose lines were loaded; sent so the backend can use its ordered voice segments. */
  loadedSceneId?: string;
  /** INDIC_TTS instead of H3 speech - one or the other. */
  useIndicTts = false;
  sceneAudioEdited = false;
  voiceProfiles: VoiceProfile[] = [];

  // "Use an image from a story" picker
  projects: Project[] = [];
  episodes: Episode[] = [];
  scenes: SceneDto[] = [];
  scenesLoaded = false;
  loadingScene = false;
  selectedProjectId?: string;
  selectedEpisodeId?: string;
  selectedSceneId?: string;

  submitting = false;
  error = '';
  job?: VideoGenerationJob;
  resultUrl = '';

  private pollHandle?: ReturnType<typeof setInterval>;
  private storyPollHandle?: ReturnType<typeof setInterval>;
  storyDurationSeconds = 60;
  storyCharacterName = '';
  /** Enabled cast characters with a ready, locked reference (from the H3 character panel). */
  storyCast: CastCharacter[] = [];
  private readonly cast = inject(CharacterCastService);
  castNames(): string { return this.storyCast.map((c) => c.name).join(', '); }
  storyCharacterFile?: File;
  storyCharacterPreviewUrl?: string;
  storyGenre = 'Cinematic adventure';
  storyLanguage = 'English';
  storyVisualStyle = 'cinematic 3D animation';
  storyNarrationTrack: 'HUMOROUS_EXPLAINER' | 'LOGIC_FIRST_ANALYST' | 'STORY_DRIVEN_MENTOR' | 'PROFESSIONAL_INSTRUCTOR' | 'SIMPLE_BEGINNER' | 'STORYTELLING_TEACHER' = 'STORY_DRIVEN_MENTOR';
  storyOrientation: 'vertical' | 'horizontal' = 'vertical';
  storySequence?: SequenceView;
  storyResultUrl = '';
  storyStatusText = '';
  storyProgress = 0;

  constructor(private api: ApiService, private route: ActivatedRoute, private router: Router) {}

  /** Longest clip the selected engine will render (H3 10 s, Wan 5 s). */
  maxSeconds(): number {
    const s = this.status;
    const fallback = s?.maxDurationSeconds || 6;
    if (this.workflow === 'MINIMAX_H3') { return s?.h3MaxSeconds && s.h3MaxSeconds > 0 ? s.h3MaxSeconds : 10; }
    if (this.workflow === 'WAN_2_2_14B') { return s?.wan14bMaxSeconds && s.wan14bMaxSeconds > 0 ? s.wan14bMaxSeconds : fallback; }
    return s?.wanMaxSeconds && s.wanMaxSeconds > 0 ? s.wanMaxSeconds : fallback;
  }

  clampDuration(): void {
    this.durationSeconds = Math.min(this.durationSeconds, this.maxSeconds());
  }

  cameraPresets: CameraPreset[] = [];
  cameraPresetId = '';
  presetCategories(): string[] { return [...new Set(this.cameraPresets.map(p => p.category))]; }
  presetsIn(cat: string): CameraPreset[] { return this.cameraPresets.filter(p => p.category === cat); }

  ngOnInit(): void {
    this.api.cameraPresets().subscribe({ next: p => this.cameraPresets = p, error: () => {} });
    this.api.videoGenerationStatus().subscribe({
      next: res => {
        this.status = res;
        this.durationSeconds = Math.min(this.durationSeconds, res.maxDurationSeconds || 6);
        this.statusLoaded = true;
      },
      error: () => {
        this.status = { available: false, reason: 'Could not reach the backend.', defaultWidth: 0, defaultHeight: 0, maxDurationSeconds: 6 };
        this.statusLoaded = true;
      }
    });
    const query = this.route.snapshot.queryParamMap;
    const requestedProjectId = query.get('projectId') || undefined;
    const requestedEpisodeId = query.get('episodeId') || undefined;
    const requestedSceneId = query.get('sceneId') || undefined;
    this.api.listProjects().subscribe({
      next: projects => {
        this.projects = projects;
        if (requestedProjectId && projects.some(p => p.id === requestedProjectId)) {
          this.selectedProjectId = requestedProjectId;
          this.loadEpisodesForProject(requestedProjectId, requestedEpisodeId, requestedSceneId);
        }
      },
      error: () => { /* Picker just stays empty - not fatal to the page. */ }
    });
    this.api.listVoiceProfiles().subscribe({
      next: profiles => { this.voiceProfiles = profiles; },
      error: () => { /* Falls back to "Default voice" only - not fatal. */ }
    });
  }

  ngOnDestroy(): void {
    this.stopPolling();
    this.stopStoryPolling();
    if (this.previewUrl) { URL.revokeObjectURL(this.previewUrl); }
    if (this.storyCharacterPreviewUrl) { URL.revokeObjectURL(this.storyCharacterPreviewUrl); }
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) { return; }
    this.selectedFile = file;
    this.loadedSceneId = undefined; // a manual upload is no longer the story scene
    if (this.previewUrl) { URL.revokeObjectURL(this.previewUrl); }
    this.previewUrl = URL.createObjectURL(file);
  }


  onProjectChange(): void {
    this.episodes = [];
    this.scenes = [];
    this.scenesLoaded = false;
    this.selectedEpisodeId = undefined;
    this.selectedSceneId = undefined;
    if (!this.selectedProjectId) { return; }
    this.loadEpisodesForProject(this.selectedProjectId);
  }

  private loadEpisodesForProject(projectId: string, requestedEpisodeId?: string, requestedSceneId?: string): void {
    this.api.listEpisodes(projectId).subscribe({
      next: episodes => {
        this.episodes = episodes;
        if (requestedEpisodeId && episodes.some(e => e.id === requestedEpisodeId)) {
          this.selectedEpisodeId = requestedEpisodeId;
          this.loadScenesForEpisode(requestedEpisodeId, requestedSceneId);
        }
      },
      error: () => { this.error = 'Could not load episodes for that project.'; }
    });
  }

  onEpisodeChange(): void {
    this.scenes = [];
    this.scenesLoaded = false;
    this.selectedSceneId = undefined;
    if (!this.selectedEpisodeId) { return; }
    this.loadScenesForEpisode(this.selectedEpisodeId);
  }

  private loadScenesForEpisode(episodeId: string, requestedSceneId?: string): void {
    this.api.getScenes(episodeId).subscribe({
      next: scenes => {
        // Only scenes with a generated image are useful here - an
        // un-generated scene has nothing for "Load" to actually fetch.
        this.scenes = scenes.filter(s => !!s.imagePrompt);
        this.scenesLoaded = true;
        if (requestedSceneId && this.scenes.some(s => s.id === requestedSceneId)) {
          this.selectedSceneId = requestedSceneId;
          this.loadFromScene();
        }
      },
      error: () => { this.error = 'Could not load scenes for that episode.'; this.scenesLoaded = true; }
    });
  }

  /** Fetches the selected scene's generated image and its motion prompts
   *  (built during story generation - see MotionPromptBuilder), and drops
   *  them into the same fields manual upload/typing would fill. From here
   *  on this is indistinguishable from a manual upload - one upload path,
   *  not two. */
  loadFromScene(): void {
    if (!this.selectedSceneId) { return; }
    const scene = this.scenes.find(s => s.id === this.selectedSceneId);
    if (!scene) { return; }

    this.loadingScene = true;
    this.error = '';
    const imageUrl = this.api.sceneImageUrl(scene.id);
    this.api.fetchImageBlob(imageUrl).subscribe({
      next: blob => {
        const file = new File([blob], `scene-${scene.sceneNumber}.png`, { type: blob.type || 'image/png' });
        this.selectedFile = file;
        if (this.previewUrl) { URL.revokeObjectURL(this.previewUrl); }
        this.previewUrl = URL.createObjectURL(file);
        // Replace, rather than conditionally merge, every scene field. This prevents
        // a previous scene's narration/audio from disappearing or leaking into the next one.
        this.prompt = scene.motionPrompt || scene.action || '';
        this.negativePrompt = scene.motionNegativePrompt || scene.negativePrompt || '';
        this.narrationText = scene.narration || '';
        this.dialogueText = this.parseDialogue(scene.voiceSegments, scene.narration || '');
        this.audioDirection = this.parseAudioSpec(scene.audioSpecJson);
        this.workflow = 'MINIMAX_H3';
        this.loadedSceneId = scene.id;
        this.sceneAudioEdited = false;
        const estimatedAudioSeconds = this.estimateSceneAudioSeconds(scene.voiceSegments, scene.narration || '');
        const requestedSceneSeconds = Math.max(
          scene.imageDurationSeconds && scene.imageDurationSeconds > 0 ? scene.imageDurationSeconds : 0,
          scene.narrationSeconds && scene.narrationSeconds > 0 ? scene.narrationSeconds + 0.6 : 0,
          estimatedAudioSeconds
        );
        this.audioDurationWarning = '';
        if (requestedSceneSeconds > 0) {
          this.durationSeconds = Math.min(Math.max(requestedSceneSeconds, 1), this.maxSeconds());
          if (requestedSceneSeconds > this.maxSeconds()) {
            this.audioDurationWarning = `Speech is about ${requestedSceneSeconds.toFixed(1)}s - longer than one H3 shot (${this.maxSeconds().toFixed(1)}s). It will be rendered as several continuous shots; nothing is cut.`;
          }
        }
        // Prompt stays visual-only. Speech/sound are sent as separate fields and the
        // backend builds the H3 prompts (same renderer as Story Video Production).
        this.mode = 'i2v';
        this.loadingScene = false;
      },
      error: () => {
        this.error = 'Could not load that scene\'s image.';
        this.loadingScene = false;
      }
    });
  }

  private parseDialogue(segments?: any[], narration?: string): string {
    if (!segments || !segments.length) return '';
    const lines = segments
      .filter((x: any) => x && x.text && String(x.text).trim())
      .filter((x: any) => !/^(narrator|voiceover)$/i.test(String(x.character || '')))
      .map((x: any) => {
        const delivery = x.delivery || x.actingDirection || x.emotion || '';
        const suffix = delivery ? ` [${String(delivery).trim()}]` : '';
        return `${x.character || 'Speaker'}: ${String(x.text).trim()}${suffix}`;
      });
    return lines.join('\n');
  }

  private estimateSceneAudioSeconds(segments?: any[], narration?: string): number {
    const source = Array.isArray(segments) && segments.length
      ? segments
      : (narration ? [{ text: narration, pauseBeforeMs: 0, pauseAfterMs: 0 }] : []);
    if (!source.length) return 0;
    let seconds = 0;
    for (const x of source) {
      const text = String(x?.text || '').trim();
      if (!text) continue;
      const words = text.split(/\s+/).filter(Boolean).length;
      // Conversational H3 delivery is intentionally slower than raw TTS: ~2.35 words/s.
      seconds += Math.max(0.7, words / 2.35);
      seconds += Number(x?.pauseBeforeMs || 0) / 1000;
      seconds += Number(x?.pauseAfterMs || 0) / 1000;
    }
    return seconds + 0.6;
  }

  markAudioEdited(): void {
    this.sceneAudioEdited = true;
  }

  private parseAudioSpec(raw?: string): string {
    if (!raw) return '';
    try {
      const a = JSON.parse(raw);
      const lines: string[] = [];
      if (Array.isArray(a.ambience) && a.ambience.length) lines.push(`Ambience: ${a.ambience.join(', ')}.`);
      if (Array.isArray(a.sfx) && a.sfx.length) {
        const sfx = a.sfx.map((x: any) => typeof x === 'string' ? x : `${x.event || 'sound effect'}${x.timing ? ` (${x.timing})` : ''}`).join('; ');
        lines.push(`Sound effects: ${sfx}.`);
      }
      if (a.music) {
        const m = typeof a.music === 'string' ? a.music : `${a.music.mood || 'cinematic'} music${a.music.intensity != null ? `, intensity ${a.music.intensity}` : ''}`;
        lines.push(`Music: ${m}.`);
      }
      return lines.join('\n');
    } catch { return ''; }
  }

  onStoryCharacterSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    if (this.storyCharacterPreviewUrl) URL.revokeObjectURL(this.storyCharacterPreviewUrl);
    this.storyCharacterFile = file;
    this.storyCharacterPreviewUrl = URL.createObjectURL(file);
  }

  setMode(mode: 'i2v' | 't2v' | 'story'): void {
    this.mode = mode;
    if (mode !== 'i2v') {
      this.selectedFile = undefined;
      if (this.previewUrl) { URL.revokeObjectURL(this.previewUrl); }
      this.previewUrl = undefined;
    }
    if (mode === 'story') {
      this.workflow = 'MINIMAX_H3';
      this.durationSeconds = 5;
      this.stopPolling();
      this.job = undefined;
      this.storyStatusText = '';
    }
  }

  submit(): void {
    if (this.mode === 'i2v' && !this.selectedFile) { return; }
    if (!this.prompt.trim()) { return; }
    if (this.mode === 'story') {
      this.submitStoryVideo();
      return;
    }
    this.submitting = true;
    this.error = '';
    this.job = undefined;

    const image = this.mode === 'i2v' ? (this.selectedFile ?? null) : null;
    const h3 = this.workflow === 'MINIMAX_H3';
    const preset = this.cameraPresets.find(p => p.id === this.cameraPresetId);
    const basePrompt = this.prompt.split(/\n\n\[NATIVE H3 AUDIO\]/i)[0].trim();
    const visualPrompt = preset ? `${basePrompt}. Camera: ${preset.prompt}` : basePrompt;
    this.api.createVideoGenerationJob(image, visualPrompt, this.negativePrompt, this.durationSeconds,
      this.narrationText || undefined, h3 ? undefined : (this.voiceProfileId || undefined), this.workflow,
      h3 ? {
        sceneId: this.loadedSceneId,
        dialogueText: this.dialogueText || undefined,
        audioDirection: this.audioDirection || undefined,
        useSceneVoices: !!this.loadedSceneId && !this.sceneAudioEdited,
        speechEngine: this.useIndicTts ? 'INDIC_TTS' : 'H3'
      } : undefined)
      .subscribe({
        next: res => {
          this.submitting = false;
          this.resultUrl = this.api.videoGenerationResultUrl(res.jobId);
          this.startPolling(res.jobId);
        },
        error: err => {
          this.submitting = false;
          this.error = err?.error?.message || 'Could not start video generation.';
        }
      });
  }

  private submitStoryVideo(): void {
    if (!this.storyCast.length) {
      this.error = 'Add at least one character with a ready reference first.';
      return;
    }
    this.submitting = true;
    this.error = '';
    this.job = undefined;
    this.storySequence = undefined;
    this.storyResultUrl = '';
    this.storyProgress = 0;
    this.storyStatusText = 'Syncing locked character references…';
    this.stopStoryPolling();

    forkJoin(this.storyCast.map((c) => this.libraryIdFor(c))).subscribe({
      next: (characterIds) => {
        this.storyStatusText = 'Preparing the story workspace…';
        this.api.createProject(
          `H3 Story Video - ${this.prompt.trim().slice(0, 48)}`,
          `Automated ${this.storyDurationSeconds}-second ${this.storyGenre} MiniMax H3 video from Story-to-Video.`
        ).subscribe({
          next: project => this.createAndRenderStoryDraft(project.id, characterIds),
          error: err => {
            this.submitting = false;
            this.error = err?.error?.message || 'Could not create a story workspace.';
          }
        });
      },
      error: err => {
        this.submitting = false;
        this.error = err?.error?.message || err?.error?.error || 'Could not save the character references.';
      }
    });
  }

  /** Character-library copy of a cast reference (used for Qwen keyframes); reused while the reference is unchanged. */
  private libraryIdFor(c: CastCharacter): Observable<string> {
    if (c.libraryCharacterId && c.librarySyncedReference === c.referencePath) return of(c.libraryCharacterId);
    return this.cast.referenceFile(c).pipe(
      switchMap((file) => this.api.uploadCharacterLibrary(file, c.name,
        `${c.masterPrompt || c.name}. Locked reference: preserve the exact face, hair, skin tone, age, outfit and proportions across every scene; allow only scene-appropriate expressions, poses and movement.`)),
      switchMap((lib) => this.cast.linkLibrary(c.id, lib.characterId).pipe(map(() => lib.characterId))),
    );
  }

  /** Story-to-H3 is a one-click production flow, not the Create Story approval workflow. */
  private createAndRenderStoryDraft(projectId: string, characterIds: string[]): void {
    const sceneCount = Math.max(6, Math.round(this.storyDurationSeconds / 5));
    const styleDirections: Record<string, string> = {
      HUMOROUS_EXPLAINER: 'Narration style: humorous and playful with tasteful character-appropriate humor, expressive timing and short pauses after comic beats. At most one small joke per scene.',
      LOGIC_FIRST_ANALYST: 'Narration style: clear cause and effect, explicit goals, obstacles, decisions and consequences. No forced jokes or hype.',
      STORY_DRIVEN_MENTOR: 'Narration style: cinematic and emotional, with a compelling hook, rising stakes, varied rhythm and a satisfying payoff.',
      PROFESSIONAL_INSTRUCTOR: 'Narration style: confident, concise and polished with clear transitions.',
      SIMPLE_BEGINNER: 'Narration style: patient, simple, warm and accessible, with short spoken sentences.',
      STORYTELLING_TEACHER: 'Narration style: warm and curious, with vivid details, gentle suspense and an emotional insight.'
    };
    const style = styleDirections[this.storyNarrationTrack] || styleDirections.STORY_DRIVEN_MENTOR;
    const productionBrief = [
      `STORY PREMISE (expand this into a complete original story): ${this.prompt.trim()}`,
      `Create a complete ${this.storyDurationSeconds}-second ${this.storyGenre.toLowerCase()} story in ${this.storyLanguage}; visual style ${this.storyVisualStyle}. Plan exactly about ${sceneCount} story beats, one per five-second H3 clip.`,
      `CHARACTERS (use these EXACT names every time a character appears, acts or speaks - never a nickname, pronoun-only reference or a new name for them; each name is bound to a locked reference image): ${this.storyCast.map((c) => `${c.name}${c.masterPrompt ? ' - ' + c.masterPrompt : ''}`).join(' | ')}. ${this.storyCast[0].name} is the main character. Name the on-screen character(s) in every scene's visual and motion prompt. Keep at most two of these characters in any one scene. Other supporting characters may appear only when the plot needs them.`,
      'STRUCTURE: beginning, goal, conflict, escalating complication, climax, consequence and satisfying resolution. Every scene must advance the plot; no disconnected montage.',
      style,
      'FIVE-SECOND SCENE CONTRACT: Each scene must fit one five-second H3 shot. Keep each scene to one clear visual action and at most one short spoken line of about 6-9 words. Do not put long narration paragraphs into a five-second scene. If the requested story length is 30, 60, 180 or 300 seconds, plan the corresponding 6, 12, 36 or 60 scenes.',
      'FOR EACH SCENE provide: visual opening composition and character blocking; one concise motion prompt with shot size, angle and a single camera movement; short narration/dialogue with speaker, language and delivery; ambience; one or two timed sound effects tied to visible action; music mood/intensity and how it develops across scenes.',
      'H3 PROMPT FORMAT: use production directions in English. Describe timed visual action and camera movement, put exact spoken words in <d>[Language]spoken line</d>, describe ambience and physical SFX separately from non-diegetic music. Keep music below speech; no SFX may mask words.',
      'CONTINUITY: preserve character identity, wardrobe, props, geography, time of day and visual style. Vary shot composition while maintaining continuity. Avoid text overlays, subtitles, watermarks and abrupt resets.',
      'Return the complete production-ready scene plan, not only an outline. Keep spoken lines short enough to fit their five-second clip and resolve the central story question in the final scene.'
    ].join('\n');

    this.storyStatusText = 'Writing the complete story and preparing five-second scenes…';
    this.api.createDraft({
      projectId,
      prompt: productionBrief,
      durationSeconds: this.storyDurationSeconds,
      targetAge: this.storyGenre === "Children's adventure" ? 'children and family' : 'general audience',
      genre: this.storyGenre,
      tone: style,
      visualStyle: this.storyVisualStyle,
      language: this.storyLanguage,
      qualityProfile: 'QUALITY',
      characterIds
    }).subscribe({
      next: draft => {
        this.storyStatusText = 'Story created. Starting H3 clips automatically…';
        this.api.produceEpisodeVideo(draft.episodeId, 'H3', this.storyOrientation, characterIds, 5, true).subscribe({
          next: sequence => {
            this.storySequence = sequence;
            this.updateStoryProgress(sequence);
            this.startStoryPolling(sequence.id);
          },
          error: err => {
            this.submitting = false;
            this.error = err?.error?.message || 'The story was drafted, but H3 video rendering could not start.';
          }
        });
      },
      error: err => {
        this.submitting = false;
        this.error = err?.error?.message || 'Could not generate the complete story.';
      }
    });
  }

  private startStoryPolling(sequenceId: string): void {
    this.stopStoryPolling();
    this.storyPollHandle = setInterval(() => {
      this.api.getSequence(sequenceId).subscribe({
        next: sequence => {
          this.storySequence = sequence;
          this.updateStoryProgress(sequence);
          if (sequence.status === 'COMPLETED' || sequence.status === 'PARTIAL'
              || sequence.status === 'FAILED' || sequence.status === 'CANCELLED') {
            if (sequence.hasMerged) this.storyResultUrl = this.api.sequenceMergedUrl(sequence.id, sequence.mergedStamp);
            this.storyStatusText = sequence.status === 'COMPLETED'
              ? 'All scene clips are merged into the final video.'
              : sequence.status === 'PARTIAL'
                ? 'Some scenes failed. Review the error details before using the final video.'
                : sequence.status === 'CANCELLED'
                  ? 'Story video generation was cancelled.'
                  : sequence.error || 'Story video generation failed.';
            this.submitting = false;
            this.stopStoryPolling();
          }
        },
        error: () => {
          this.error = 'Lost track of story production. The sequence may still be running on the backend.';
          this.stopStoryPolling();
        }
      });
    }, 5000);
  }

  private updateStoryProgress(sequence: SequenceView): void {
    this.storyProgress = sequence.totalScenes > 0
      ? Math.round((sequence.doneScenes / sequence.totalScenes) * 100)
      : 0;
    if (sequence.status === 'KEYFRAMES_RUNNING') this.storyStatusText = 'Creating scene keyframes with character references…';
    else if (sequence.status === 'VIDEOS_RUNNING') this.storyStatusText = `Rendering H3 shots and audio: ${sequence.doneScenes} of ${sequence.totalScenes} scenes complete…`;
    else if (sequence.status === 'MERGING') this.storyStatusText = 'Merging all scene clips and normalizing the final video…';
    else if (sequence.status === 'COMPLETED') this.storyStatusText = 'Final story video is ready.';
  }

  private stopStoryPolling(): void {
    if (this.storyPollHandle) {
      clearInterval(this.storyPollHandle);
      this.storyPollHandle = undefined;
    }
  }

  private startPolling(jobId: string): void {
    this.stopPolling();
    // 5s, not sub-second: a Wan call runs minutes, not seconds, so polling
    // faster would only add load for no earlier signal.
    this.pollHandle = setInterval(() => {
      this.api.getVideoGenerationJob(jobId).subscribe({
        next: job => {
          this.job = job;
          if (job.status === 'SUCCEEDED' || job.status === 'FAILED') {
            this.stopPolling();
          }
        },
        error: () => {
          this.error = 'Lost track of the job (backend restarted?).';
          this.stopPolling();
        }
      });
    }, 5000);
  }

  private stopPolling(): void {
    if (this.pollHandle) {
      clearInterval(this.pollHandle);
      this.pollHandle = undefined;
    }
  }
}
