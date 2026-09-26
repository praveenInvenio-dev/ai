import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { Episode, JobStatusResponse } from '../../models/models';

const STAGES = [
  'GENERATING_IMAGES', 'VALIDATING_IMAGES', 'GENERATING_AUDIO',
  'ASSEMBLING_VIDEO', 'GENERATING_THUMBNAIL', 'GENERATING_SHORTS', 'QUALITY_CHECK'
];

@Component({
  selector: 'app-production-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div *ngIf="episode">
      <header class="page-head">
        <h1>{{ episode.title || 'Production' }}</h1>
        <span class="tag" [class.tag-amber]="job?.status === 'COMPLETED'">{{ job?.status || episode.status }}</span>
      </header>

      <section class="card stages" *ngIf="job">
        <div class="stage-row" *ngFor="let stage of stages">
          <span class="stage-name">{{ label(stage) }}</span>
          <div class="bar">
            <div class="bar-fill" [style.width.%]="stageProgress(stage)"></div>
          </div>
        </div>
      </section>

      <section class="card" *ngIf="job?.status === 'FAILED'">
        <h3>Production failed</h3>
        <p class="error">{{ job?.errorMessage }}</p>
        <p class="muted">Failed steps are isolated per scene — check individual step errors below and retry.</p>
      </section>

      <section class="card" *ngIf="job?.steps?.length">
        <h3>Generation log</h3>
        <ul class="steps">
          <li *ngFor="let s of job!.steps">
            <span class="step-name">{{ s.stepName }}</span>
            <span class="step-status" [class.fail]="s.status === 'FAILED'">
              {{ s.status }}<ng-container *ngIf="s.durationMs"> · {{ s.durationMs! / 1000 | number:'1.1-1' }}s</ng-container>
            </span>
          </li>
        </ul>
      </section>

      <section class="card done" *ngIf="job?.status === 'COMPLETED'">
        <h3>Production complete</h3>
        <video controls class="final-video" [src]="videoUrl" [poster]="thumbnailUrl"></video>
        <p class="muted">Everything — video, audio, images, subtitles, shorts and thumbnail — is packaged and ready.</p>
        <div class="done-actions">
          <a class="btn btn-ghost" [routerLink]="['/episodes', episode.id, 'scenes']">View scene-by-scene</a>
          <a class="btn btn-primary" [href]="downloadUrl" target="_blank">Download complete package</a>
        </div>
      </section>
      <section class="card empty-state" *ngIf="!job && checkedForJob">
        <h3>No production job yet</h3>
        <p class="muted">This episode hasn't been approved for production yet.</p>
        <a class="btn btn-primary" [routerLink]="['/episodes', episode.id, 'approve']">Review &amp; approve draft</a>
      </section>
    </div>
  `,
  styles: [`
    .page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.6rem; }
    .stages { margin-bottom: 1.4rem; }
    .stage-row { display: flex; align-items: center; gap: 1rem; padding: 0.5em 0; }
    .stage-name { width: 200px; font-size: 0.85rem; color: var(--muted); flex-shrink: 0; }
    .bar { flex: 1; height: 8px; border-radius: 4px; background: var(--surface-raised); overflow: hidden; }
    .bar-fill { height: 100%; background: var(--accent); transition: width 0.4s ease; }
    .steps { list-style: none; padding: 0; margin: 0.6rem 0 0; display: flex; flex-direction: column; gap: 0.3rem; }
    .steps li { display: flex; justify-content: space-between; font-size: 0.85rem; padding: 0.3em 0; border-bottom: 1px solid var(--border); }
    .step-status { color: var(--teal); }
    .step-status.fail { color: var(--danger); }
    .error { color: var(--danger); }
    .done { text-align: center; padding: 2.4rem; }
    .final-video { width: 100%; max-width: 480px; border-radius: 10px; background: #000; margin-bottom: 1rem; }
    .done-actions { display: flex; justify-content: center; gap: 0.8rem; margin-top: 1rem; }
    .muted { color: var(--muted); }
    .empty-state { text-align: center; padding: 2.4rem; }
    .empty-state .btn { margin-top: 1rem; }
  `]
})
export class ProductionDashboardComponent implements OnInit, OnDestroy {
  episode: Episode | null = null;
  job: JobStatusResponse | null = null;
  stages = STAGES;
  downloadUrl = '';
  videoUrl = '';
  thumbnailUrl = '';
  checkedForJob = false;
  private source?: EventSource;

  constructor(private route: ActivatedRoute, private api: ApiService) {}

  ngOnInit(): void {
    const episodeId = this.route.snapshot.paramMap.get('id')!;
    this.api.getEpisode(episodeId).subscribe(ep => {
      this.episode = ep;
      this.downloadUrl = this.api.downloadPackageUrl(ep.id);
      this.videoUrl = this.api.episodeVideoUrl(ep.id);
      this.thumbnailUrl = this.api.episodeThumbnailUrl(ep.id);
    });

    // The approve endpoint returns the job id; if the user navigated here directly
    // (bookmark, refresh, dashboard link) there's no job id in hand, so fetch the
    // episode's most recent job from the backend instead.
    const jobId = history.state?.jobId as string | undefined;
    if (jobId) {
      // Get its current status immediately (SSE only pushes on the *next* change,
      // so without this the page stays blank until something happens).
      this.api.getJobStatus(jobId).subscribe({
        next: job => { this.job = job; this.checkedForJob = true; },
        error: () => { this.checkedForJob = true; }
      });
      this.subscribeToJob(jobId);
    } else {
      this.loadLatestJob(episodeId);
    }
  }

  private loadLatestJob(episodeId: string): void {
    this.api.getLatestJobForEpisode(episodeId).subscribe({
      next: job => {
        this.job = job;
        this.checkedForJob = true;
        const terminal = job.status === 'COMPLETED' || job.status === 'FAILED' || job.status === 'CANCELLED';
        if (!terminal) {
          this.subscribeToJob(job.jobId);
        }
      },
      error: () => { this.checkedForJob = true; } // no job exists yet for this episode
    });
  }

  private subscribeToJob(jobId: string): void {
    this.source = this.api.streamJob(jobId);
    this.source.addEventListener('progress', (evt: MessageEvent) => {
      this.job = JSON.parse(evt.data);
    });
  }

  stageProgress(stage: string): number {
    if (!this.job) return 0;
    const idx = STAGES.indexOf(stage);
    const currentIdx = STAGES.indexOf(this.job.status);
    if (this.job.status === 'COMPLETED') return 100;
    if (currentIdx === -1) return 0;
    if (idx < currentIdx) return 100;
    if (idx === currentIdx) return Math.max(10, this.job.progressPercent);
    return 0;
  }

  label(stage: string): string {
    return stage.replace(/_/g, ' ').toLowerCase().replace(/^./, c => c.toUpperCase());
  }

  ngOnDestroy(): void {
    this.source?.close();
  }
}
