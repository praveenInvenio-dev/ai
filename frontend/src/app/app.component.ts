import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { ApiService } from './services/api.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="shell">
      <aside class="rail">
        <div class="brand">
          <span class="brand-mark">🎬</span>
          <span class="brand-name">AI Story Studio</span>
        </div>
        <nav>
          <a routerLink="/dashboard" routerLinkActive="active">Projects</a>
          <a routerLink="/create" routerLinkActive="active">Create story</a>
          <a routerLink="/characters" routerLinkActive="active">Character studio</a>
          <a routerLink="/build-story" routerLinkActive="active">Build a story</a>
          <a routerLink="/story-images" routerLinkActive="active">Add story images</a>
          <a routerLink="/voice-lab" routerLinkActive="active">Voice lab</a>
          <a routerLink="/video-editor" routerLinkActive="active">AI video editor</a>
          <a routerLink="/video-generation" routerLinkActive="active">Video generation</a>
        </nav>
        <div class="rail-footer">
          <span class="tag" *ngIf="healthChecked && !demoMode" [class.tag-teal]="true">Local &amp; self-hosted</span>
          <span class="tag tag-amber" *ngIf="healthChecked && demoMode" title="Images and audio are placeholders while demo mode is on. Set DEMO_MODE=false in your .env to generate real media.">
            Demo mode - placeholder media
          </span>
        </div>
      </aside>
      <main class="stage">
        <div class="demo-banner" *ngIf="healthChecked && demoMode">
          <strong>Demo mode is on.</strong>
          Images and audio you see anywhere in the app are placeholders, not real generated
          content. Set <code>DEMO_MODE=false</code> in your <code>.env</code> and restart the
          backend to generate real media.
        </div>
        <router-outlet />
      </main>
    </div>
  `,
  styles: [`
    .shell { display: flex; min-height: 100vh; }
    .rail {
      width: 240px;
      flex-shrink: 0;
      background: var(--surface);
      border-right: 1px solid var(--border);
      padding: 1.5rem 1.2rem;
      display: flex;
      flex-direction: column;
      position: sticky;
      top: 0;
      height: 100vh;
    }
    .brand { display: flex; align-items: center; gap: 0.6em; margin-bottom: 2.2rem; }
    .brand-mark { font-size: 1.4rem; }
    .brand-name { font-family: var(--font-display); font-size: 1.05rem; }
    nav { display: flex; flex-direction: column; gap: 0.3rem; flex: 1; }
    nav a {
      text-decoration: none;
      color: var(--muted);
      padding: 0.6em 0.8em;
      border-radius: 8px;
      font-size: 0.92rem;
    }
    nav a:hover { color: var(--text); background: var(--surface-raised); }
    nav a.active { color: var(--accent); background: var(--surface-raised); }
    .rail-footer { padding-top: 1rem; }
    .stage { flex: 1; padding: 2.4rem 2.6rem; max-width: 1100px; }
    .demo-banner {
      background: rgba(238, 162, 59, 0.12);
      border: 1px solid rgba(238, 162, 59, 0.4);
      border-radius: 10px;
      padding: 0.9rem 1.2rem;
      margin-bottom: 1.6rem;
      font-size: 0.88rem;
      color: var(--text);
      line-height: 1.5;
    }
    .demo-banner code {
      background: var(--surface-raised);
      padding: 0.1em 0.4em;
      border-radius: 4px;
      font-size: 0.85em;
    }
    @media (max-width: 760px) {
      .shell { flex-direction: column; }
      .rail { width: 100%; height: auto; position: static; flex-direction: row; align-items: center; }
      nav { flex-direction: row; }
      .stage { padding: 1.4rem; }
    }
  `]
})
export class AppComponent implements OnInit {
  demoMode = false;
  healthChecked = false;

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    this.api.health().subscribe({
      next: (res: any) => {
        this.demoMode = !!res?.demoMode;
        this.healthChecked = true;
      },
      error: () => { this.healthChecked = false; }
    });
  }
}
