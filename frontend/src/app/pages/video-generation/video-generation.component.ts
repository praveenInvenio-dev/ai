import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService, VideoGenerationStatus, VideoGenerationJob } from '../../services/api.service';

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
        Upload a still image and describe the motion you want. This calls the same
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
        <label for="image">Starting image</label>
        <input id="image" type="file" accept="image/png,image/jpeg,image/webp"
               (change)="onFileSelected($event)">
        <img *ngIf="previewUrl" [src]="previewUrl" class="preview" alt="Starting frame preview">
      </div>

      <div class="field">
        <label for="prompt">Motion prompt</label>
        <textarea id="prompt" rows="3" [(ngModel)]="prompt"
                  placeholder="e.g. the character waves and smiles, gentle camera push-in"></textarea>
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
              [disabled]="submitting || !selectedFile || !prompt.trim()">
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
  prompt = '';
  negativePrompt = '';
  durationSeconds = 4;

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

  submit(): void {
    if (!this.selectedFile || !this.prompt.trim()) { return; }
    this.submitting = true;
    this.error = '';
    this.job = undefined;

    this.api.createVideoGenerationJob(this.selectedFile, this.prompt, this.negativePrompt, this.durationSeconds)
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
