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
          <span class="brand-mark">✦</span>
          <span><span class="brand-name">AI Story Studio</span><small>Creative production workspace</small></span>
        </div>
        <nav>
          <div class="nav-label">WORKSPACE</div>
          <a routerLink="/dashboard" routerLinkActive="active"><span>▦</span>Projects</a>
          <a routerLink="/create" routerLinkActive="active"><span>＋</span>Create story</a>
          <div class="nav-label">BUILD</div>
          <a routerLink="/characters" routerLinkActive="active"><span>◉</span>Character studio</a>
          <a routerLink="/build-story" routerLinkActive="active"><span>✎</span>Build a story</a>
          <a routerLink="/story-images" routerLinkActive="active"><span>▧</span>Add story images</a>
          <a routerLink="/voice-lab" routerLinkActive="active"><span>♫</span>Voice lab</a>
          <div class="nav-label">PRODUCTION</div>
          <a routerLink="/video-editor" routerLinkActive="active"><span>◫</span>AI video editor</a>
          <a routerLink="/funny-skits" routerLinkActive="active"><span>😂</span>Funny skits</a>
          <a routerLink="/video-generation" routerLinkActive="active"><span>▶</span>Video generation</a>
          <a routerLink="/video-sequence" routerLinkActive="active"><span>▤</span>Story video production</a>
          <a routerLink="/wan-workflow" routerLinkActive="active"><span>◈</span>Wan 2.2 workflow</a>
          <a routerLink="/minimax-h3-workflow" routerLinkActive="active"><span>◇</span>MiniMax H3 workflow</a>
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
    .brand { display:flex;align-items:center;gap:.7rem;margin-bottom:2rem }.brand-mark{width:36px;height:36px;display:grid;place-items:center;border-radius:12px;background:linear-gradient(135deg,var(--accent),#f3c46b);color:#211606;font-weight:900;box-shadow:0 8px 24px rgba(238,162,59,.18)}.brand-name{display:block;font-weight:800;letter-spacing:-.02em}.brand small{display:block;color:var(--muted);font-size:.62rem;margin-top:.15rem}nav{display:flex;flex-direction:column;gap:.25rem;flex:1}.nav-label{font-size:.62rem;letter-spacing:.14em;color:#6f687e;font-weight:800;margin:1rem .7rem .3rem}nav a{text-decoration:none;color:var(--muted);padding:.72em .8em;border-radius:11px;font-size:.88rem;display:flex;align-items:center;gap:.7rem;transition:.16s ease}nav a span{width:18px;text-align:center;color:#777083}nav a:hover{color:var(--text);background:rgba(255,255,255,.045)}nav a.active{color:var(--text);background:linear-gradient(90deg,rgba(238,162,59,.14),rgba(238,162,59,.035));box-shadow:inset 2px 0 var(--accent)}nav a.active span{color:var(--accent)}
    .rail-footer { padding-top: 1rem; }
    .stage { flex:1; padding:2.5rem clamp(1.2rem,4vw,3.4rem); max-width:none; min-width:0 }
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
