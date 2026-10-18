import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { SceneDto } from '../../models/models';

@Component({
  selector: 'app-scene-editor',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <header class="page-head">
      <h1>Scenes</h1>
      <a class="btn btn-ghost" [routerLink]="['/episodes', episodeId, 'production']">Back to production</a>
    </header>
    <div class="storyboard">
      <article class="card filmstrip-edge scene-card" *ngFor="let s of scenes">
        <div class="scene-num">Scene {{ s.sceneNumber }}</div>

        <div class="media">
          <img [src]="imageUrl(s)" [alt]="'Scene ' + s.sceneNumber" (error)="onImageError($event)" />
        </div>

        <audio controls class="narration-audio" [src]="audioUrl(s)"></audio>

        <p class="scene-narration">"{{ s.narration }}"</p>
        <dl>
          <dt>Prompt</dt><dd>{{ s.imagePrompt || '—' }}</dd>
          <dt>Camera</dt><dd>{{ s.cameraMovement || s.camera || '—' }}</dd>
          <dt>Duration</dt><dd>{{ s.imageDurationSeconds | number:'1.1-1' }}s</dd>
        </dl>
      </article>
    </div>
  `,
  styles: [`
    .page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.6rem; }
    .storyboard { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 1rem; }
    .scene-card { padding: 1rem 1.2rem 1.4rem; }
    .scene-num { font-size: 0.75rem; color: var(--muted); margin-bottom: 0.5em; }
    .media {
      width: 100%; aspect-ratio: 16/9; border-radius: 8px; overflow: hidden;
      background: var(--surface-raised); margin-bottom: 0.6em;
    }
    .media img { width: 100%; height: 100%; object-fit: cover; display: block; }
    .narration-audio { width: 100%; height: 32px; margin-bottom: 0.8em; }
    .scene-narration { font-size: 0.9rem; margin: 0 0 0.8em; }
    dl { margin: 0; font-size: 0.78rem; }
    dt { color: var(--muted); margin-top: 0.5em; }
    dd { margin: 0.1em 0 0; }
  `]
})
export class SceneEditorComponent implements OnInit {
  episodeId = '';
  scenes: SceneDto[] = [];

  constructor(private route: ActivatedRoute, private api: ApiService) {}

  ngOnInit(): void {
    this.episodeId = this.route.snapshot.paramMap.get('id')!;
    this.api.getScenes(this.episodeId).subscribe(s => this.scenes = s);
  }

  imageUrl(s: SceneDto): string {
    return this.api.sceneImageUrl(s.id);
  }

  audioUrl(s: SceneDto): string {
    return this.api.sceneAudioUrl(s.id);
  }

  onImageError(event: Event): void {
    // no image generated yet for this scene (production hasn't reached it, or it failed)
    (event.target as HTMLImageElement).style.visibility = 'hidden';
  }
}
