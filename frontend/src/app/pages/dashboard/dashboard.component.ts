import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { Project, Episode, ResourceStatus } from '../../models/models';
import { forkJoin, of } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';

interface ProjectRow {
  project: Project;
  episodes: Episode[];
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <header class="page-head">
      <div>
        <h1>Your projects</h1>
        <p class="sub">Every story starts as an idea. Approve it before anything expensive gets made.</p>
      </div>
      <a routerLink="/create" class="btn btn-primary">+ Create new story</a>
    </header>

    <div class="resource-strip" *ngIf="resources as r" [title]="r.gpuAvailable ? 'GPU detected - AI animation/image generation can use it' : (r.gpuUnavailableReason || 'No GPU detected')">
      <span>RAM {{ r.ramUsedMb }}/{{ r.ramTotalMb }} MB</span>
      <span>{{ r.cpuCores }} CPU core{{ r.cpuCores === 1 ? '' : 's' }}</span>
      <span *ngIf="r.gpuAvailable">GPU {{ r.gpuVramUsedMb }}/{{ r.gpuVramTotalMb }} MB VRAM</span>
      <span *ngIf="!r.gpuAvailable" class="muted">No GPU detected — running on CPU</span>
    </div>

    <section *ngIf="rows.length; else empty" class="rows">
      <article class="card row" *ngFor="let row of rows">
        <div class="row-head">
          <h3>{{ row.project.name }}</h3>
          <span class="muted">{{ row.episodes.length }} episode{{ row.episodes.length === 1 ? '' : 's' }}</span>
        </div>
        <p class="muted" *ngIf="row.project.description">{{ row.project.description }}</p>
        <div class="episode-list" *ngIf="row.episodes.length; else noEpisodes">
          <a class="episode" *ngFor="let ep of row.episodes" [routerLink]="episodeLink(ep)">
            <span class="episode-title">{{ ep.title || ep.userPrompt }}</span>
            <span class="tag" [class.tag-amber]="ep.status === 'PRODUCTION_COMPLETE'">{{ statusLabel(ep.status) }}</span>
          </a>
        </div>
        <ng-template #noEpisodes>
          <p class="muted small">No episodes yet in this project.</p>
        </ng-template>
      </article>
    </section>

    <ng-template #empty>
      <div class="card empty">
        <h3>No projects yet</h3>
        <p class="muted">Start your first story — the studio will spin up a project for it automatically.</p>
        <a routerLink="/create" class="btn btn-primary">Create your first story</a>
      </div>
    </ng-template>
  `,
  styles: [`
    .page-head { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 2rem; gap: 1rem; flex-wrap: wrap; }
    .sub { color: var(--muted); margin-top: 0.3rem; }
    .rows { display: flex; flex-direction: column; gap: 1rem; }
    .row-head { display: flex; justify-content: space-between; align-items: baseline; }
    .muted { color: var(--muted); }
    .muted.small { font-size: 0.85rem; }
    .episode-list { display: flex; flex-direction: column; gap: 0.4rem; margin-top: 0.9rem; }
    .episode {
      display: flex; justify-content: space-between; align-items: center;
      padding: 0.6em 0.8em; border-radius: 8px; text-decoration: none; color: var(--text);
      border: 1px solid transparent;
    }
    .episode:hover { border-color: var(--border); background: var(--surface-raised); }
    .episode-title { font-size: 0.92rem; }
    .empty { text-align: center; padding: 3rem 2rem; }
    .empty .btn { margin-top: 1rem; }
  `]
})
export class DashboardComponent implements OnInit {
  rows: ProjectRow[] = [];
  resources: ResourceStatus | null = null;

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    this.api.resourceStatus().subscribe({ next: r => this.resources = r, error: () => {} });
    this.api.listProjects().pipe(
      switchMap(projects => {
        if (!projects.length) return of([]);
        return forkJoin(projects.map(project =>
          this.api.listEpisodes(project.id).pipe(
            catchError(() => of([] as Episode[])),
            switchMap(episodes => of({ project, episodes }))
          )
        ));
      })
    ).subscribe(rows => this.rows = rows as ProjectRow[]);
  }

  episodeLink(ep: Episode): string {
    if (ep.status === 'DRAFT_READY' || ep.status === 'DRAFTING') return `/episodes/${ep.id}/approve`;
    if (ep.status === 'IN_PRODUCTION' || ep.status === 'APPROVED') return `/episodes/${ep.id}/production`;
    if (ep.status === 'PRODUCTION_COMPLETE') return `/episodes/${ep.id}/production`;
    return `/episodes/${ep.id}/approve`;
  }

  statusLabel(status: string): string {
    return status.replace(/_/g, ' ').toLowerCase().replace(/^./, c => c.toUpperCase());
  }
}
