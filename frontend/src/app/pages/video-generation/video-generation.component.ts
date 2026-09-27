import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService, VideoGenerationStatus, VideoGenerationJob } from '../../services/api.service';
import { Project, Episode, SceneDto } from '../../models/models';

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
  imports: [CommonModule, FormsModule],
  template: `
    <header class="page-head">
      <h1>Video generation</h1>
      <p class="muted">
        Upload a still image and describe the motion (image to video), or describe a whole
        scene from scratch (text to video). This calls the same
        local AI video (Wan/ComfyUI) pipeline the story production pipeline uses -
        it needs a real GPU with the Wan models installed to actually run.
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

    <section class="panel" *ngIf="statusLoaded && status?.available">
      <div class="field">
        <label>Mode</label>
        <div class="mode-toggle">
          <button type="button" class="pill" [class.selected]="mode === 'i2v'" (click)="setMode('i2v')">Image to video</button>
          <button type="button" class="pill" [class.selected]="mode === 't2v'" (click)="setMode('t2v')">Text to video</button>
        </div>
      </div>

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
        <label for="prompt">{{ mode === 't2v' ? 'Scene description' : 'Motion prompt' }}</label>
        <textarea id="prompt" rows="3" [(ngModel)]="prompt"
                  [placeholder]="mode === 't2v'
                    ? 'e.g. a small cartoon rabbit waving in a sunny forest clearing, butterflies drifting past'
                    : 'e.g. the character waves and smiles, gentle camera push-in'"></textarea>
      </div>

      <div class="field">
        <label for="negative">Negative prompt (optional)</label>
        <input id="negative" type="text" [(ngModel)]="negativePrompt"
               placeholder="e.g. blurry, distorted, extra limbs">
      </div>

      <div class="field">
        <label for="duration">
          Duration &mdash; {{ durationSeconds.toFixed(1) }}s
          (max {{ status?.maxDurationSeconds?.toFixed(1) }}s)
        </label>
        <input id="duration" type="range" min="1" [max]="status?.maxDurationSeconds || 6"
               step="0.5" [(ngModel)]="durationSeconds">
      </div>

      <button class="btn btn-primary" (click)="submit()"
              [disabled]="submitting || (mode === 'i2v' && !selectedFile) || !prompt.trim()">
        {{ submitting ? 'Working...' : 'Generate video' }}
      </button>

      <p class="error" *ngIf="error">{{ error }}</p>

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
    .panel { max-width: 640px; display: flex; flex-direction: column; gap: 1.2rem; }
    .field { display: flex; flex-direction: column; gap: 0.4em; }
    .mode-toggle { display: flex; gap: 0.4rem; }
    .pill {
      cursor: pointer; font-size: 0.85rem; padding: 0.4rem 0.9rem; border-radius: 999px;
      background: var(--surface); color: inherit; border: 1px solid var(--border);
    }
    .pill:hover { border-color: var(--accent); }
    .pill.selected { border-color: var(--accent); color: var(--accent); }
    .picker-row { display: flex; gap: 0.5rem; flex-wrap: wrap; align-items: center; }
    .picker-row select { flex: 1; min-width: 120px; }
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

  selectedFile?: File;
  previewUrl?: string;
  mode: 'i2v' | 't2v' = 'i2v';
  prompt = '';
  negativePrompt = '';
  durationSeconds = 4;

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

  constructor(private api: ApiService) {}

  ngOnInit(): void {
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
    this.api.listProjects().subscribe({
      next: projects => { this.projects = projects; },
      error: () => { /* Picker just stays empty - not fatal to the page. */ }
    });
  }

  ngOnDestroy(): void {
    this.stopPolling();
    if (this.previewUrl) { URL.revokeObjectURL(this.previewUrl); }
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) { return; }
    this.selectedFile = file;
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
    this.api.listEpisodes(this.selectedProjectId).subscribe({
      next: episodes => { this.episodes = episodes; },
      error: () => { this.error = 'Could not load episodes for that project.'; }
    });
  }

  onEpisodeChange(): void {
    this.scenes = [];
    this.scenesLoaded = false;
    this.selectedSceneId = undefined;
    if (!this.selectedEpisodeId) { return; }
    this.api.getScenes(this.selectedEpisodeId).subscribe({
      next: scenes => {
        // Only scenes with a generated image are useful here - an
        // un-generated scene has nothing for "Load" to actually fetch.
        this.scenes = scenes.filter(s => !!s.imagePrompt);
        this.scenesLoaded = true;
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
        if (scene.motionPrompt) { this.prompt = scene.motionPrompt; }
        if (scene.motionNegativePrompt) { this.negativePrompt = scene.motionNegativePrompt; }
        this.loadingScene = false;
      },
      error: () => {
        this.error = 'Could not load that scene\'s image.';
        this.loadingScene = false;
      }
    });
  }

  setMode(mode: 'i2v' | 't2v'): void {
    this.mode = mode;
    // Switching to text-to-video: drop any selected image rather than
    // silently carrying it into a T2V submission where it would be ignored -
    // clearer than a stale preview implying the image still matters.
    if (mode === 't2v') {
      this.selectedFile = undefined;
      if (this.previewUrl) { URL.revokeObjectURL(this.previewUrl); }
      this.previewUrl = undefined;
    }
  }

  submit(): void {
    if (this.mode === 'i2v' && !this.selectedFile) { return; }
    if (!this.prompt.trim()) { return; }
    this.submitting = true;
    this.error = '';
    this.job = undefined;

    const image = this.mode === 'i2v' ? (this.selectedFile ?? null) : null;
    this.api.createVideoGenerationJob(image, this.prompt, this.negativePrompt, this.durationSeconds)
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
