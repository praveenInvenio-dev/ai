import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription, timer } from 'rxjs';
import { ApiService, CameraPreset, StudioJobView, StudioSource, StudioStatus } from '../../services/api.service';

type Tab = 'motion' | 'upscale' | 'reframe' | 'background' | 'dub' | 'analyze' | 'camera';

/**
 * Motion & Effects Studio - Higgsfield-style tools running locally:
 * motion control (Wan 2.2 Animate), AI/fast upscale (+24 fps smoothing), reframe, camera presets.
 * Results can be chained: motion control -> upscale -> reframe.
 */
@Component({
  selector: 'app-motion-studio',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
  <div class="page">
    <header class="page-head">
      <h1>Motion &amp; effects studio</h1>
      <p class="muted">Local, Higgsfield-style tools. Every job runs on your GPU queue; results can be chained (motion &rarr; upscale &rarr; reframe).</p>
      <p class="warn" *ngIf="status && !status.comfyAvailable">ComfyUI is not available: {{ status.comfyReason }}. Fast upscale and reframe still work.</p>
    </header>

    <nav class="tabs">
      <button [class.active]="tab==='motion'" (click)="tab='motion'">🕺 Motion control</button>
      <button [class.active]="tab==='upscale'" (click)="tab='upscale'">🔍 Upscale 720p&ndash;4K</button>
      <button [class.active]="tab==='reframe'" (click)="tab='reframe'">▭ Reframe</button>
      <button [class.active]="tab==='background'" (click)="tab='background'">🧹 Remove background</button>
      <button [class.active]="tab==='dub'" (click)="tab='dub'">🗣️ Dub / re-voice</button>
      <button [class.active]="tab==='analyze'" (click)="tab='analyze'">📈 Analyze</button>
      <button [class.active]="tab==='camera'" (click)="tab='camera'">🎥 Camera presets</button>
    </nav>

    <section class="panel" *ngIf="tab==='motion'">
      <h2>Motion control</h2>
      <p class="muted">Your character (image) performs the movement of a driving video &mdash; a dance, a fight move, a gesture. Wan 2.2 Animate with DWPose skeleton. Up to {{ status?.motionMaxSeconds || 20 }}s at 16 fps (rendered as chained ~4.8 s windows, each takes several minutes); use <em>Upscale &amp; smooth</em> afterwards for 1080p / 24 fps.</p>
      <p class="muted small">First time: set <code>DOWNLOAD_MOTION_CONTROL_MODELS=true</code> in <code>.env</code> and restart ComfyUI (~18 GB download).</p>
      <div class="grid2">
        <label>Character image (full body works best)
          <input type="file" accept="image/png,image/jpeg,image/webp" (change)="pick($event,'image')">
        </label>
        <label>Driving video (the movement)
          <input type="file" accept="video/*" (change)="pick($event,'driving')">
        </label>
      </div>
      <div class="previews">
        <img *ngIf="imagePreview" [src]="imagePreview" alt="character">
        <video *ngIf="drivingPreview" [src]="drivingPreview" muted controls></video>
      </div>
      <label>Prompt (optional)
        <textarea rows="2" [(ngModel)]="motionPrompt" placeholder="e.g. the girl dances joyfully in the same outfit, natural motion"></textarea>
      </label>
      <div class="row">
        <label>Orientation
          <select [(ngModel)]="motionOrientation"><option value="vertical">9:16 vertical</option><option value="horizontal">16:9 horizontal</option></select>
        </label>
        <label>Seconds
          <input type="number" min="1" [max]="status?.motionMaxSeconds || 20" step="0.1" [(ngModel)]="motionSeconds">
        </label>
      </div>
      <button class="btn btn-primary" [disabled]="busy || !imageFile || !drivingFile" (click)="runMotion()">Animate character</button>
    </section>

    <section class="panel" *ngIf="tab==='upscale'">
      <h2>Upscale resolution</h2>
      <p class="muted">Turn 480p H3 / Wan / motion-control clips into HD, Full HD, 2K or 4K. Orientation is kept (vertical stays vertical).</p>
      <ng-container *ngTemplateOutlet="source"></ng-container>
      <div class="res-grid">
        <button type="button" *ngFor="let r of resolutions" class="res" [class.active]="resolution===r.value" (click)="resolution=r.value">
          <strong>{{ r.label }}</strong>
          <span class="muted small">{{ r.vertical }} &middot; {{ r.horizontal }}</span>
          <span class="muted small">{{ r.hint }}</span>
        </button>
      </div>
      <div class="row">
        <label>Quality
          <select [(ngModel)]="upscaleMode">
            <option value="AI">AI detail (RealESRGAN on GPU, then exact size) &mdash; best</option>
            <option value="FAST">Fast (FFmpeg lanczos + sharpen, CPU) &mdash; any length</option>
          </select>
        </label>
        <label class="check"><input type="checkbox" [(ngModel)]="smooth24"> Smooth to 24 fps (for 16 fps motion-control / Wan clips)</label>
      </div>
      <p class="muted small" *ngIf="upscaleMode==='AI'">AI pass works on clips up to {{ status?.aiUpscaleMaxSeconds || 30 }}s. 4K files are large (~3&ndash;4&times; the 1080p size).</p>
      <button class="btn btn-primary" [disabled]="busy || !hasSource()" (click)="runUpscale()">Upscale to {{ resolutionName() }}</button>
    </section>

    <section class="panel" *ngIf="tab==='reframe'">
      <h2>Reframe</h2>
      <p class="muted">One video, every platform: Reels/Shorts 9:16, YouTube 16:9, feed 1:1 / 4:5.</p>
      <ng-container *ngTemplateOutlet="source"></ng-container>
      <div class="row">
        <label>Aspect <select [(ngModel)]="aspect"><option>9:16</option><option>16:9</option><option>1:1</option><option>4:5</option></select></label>
        <label>Fill <select [(ngModel)]="reframeMode">
          <option value="BLUR_FILL">Blurred background (keeps whole frame)</option>
          <option value="CROP">Centre crop (fills screen)</option>
          <option value="BARS">Black bars</option></select></label>
      </div>
      <button class="btn btn-primary" [disabled]="busy || !hasSource()" (click)="runReframe()">Reframe</button>
    </section>

    <section class="panel" *ngIf="tab==='background'">
      <h2>Remove background</h2>
      <p class="muted">Cut out the character from an image (PNG) or a short video (up to 15 s, CPU, frame by frame). Use for thumbnails, stickers or putting a character on a new scene.</p>
      <div class="row">
        <label>Image <input type="file" accept="image/png,image/jpeg,image/webp" (change)="pick($event,'bgImage')"></label>
        <span class="muted">or</span>
      </div>
      <ng-container *ngTemplateOutlet="source"></ng-container>
      <div class="row">
        <label>New background
          <select [(ngModel)]="bgMode"><option value="transparent">Transparent (PNG / WebM)</option><option value="color">Solid colour</option><option value="blur">Blurred original</option></select>
        </label>
        <label *ngIf="bgMode==='color'">Colour <input type="color" [(ngModel)]="bgColor"></label>
        <label>Quality
          <select [(ngModel)]="bgQuality"><option value="fast">Fast (people)</option><option value="general">General</option><option value="best">Best (BiRefNet, slow)</option></select>
        </label>
      </div>
      <button class="btn btn-primary" [disabled]="busy || (!bgImageFile && !hasSource())" (click)="runBackground()">Remove background</button>
    </section>

    <section class="panel" *ngIf="tab==='dub'">
      <h2>Dub / re-voice in another language</h2>
      <p class="muted"><strong>Re-voice</strong> keeps the picture and lays a new IndicF5 voice over it (original sound kept low). Lips are not changed.
        <strong>Re-animate</strong> makes a new H3 clip from the first frame where the speech &mdash; and the lips for character lines &mdash; match the new language.</p>
      <ng-container *ngTemplateOutlet="source"></ng-container>
      <label>Lines (what is said) &mdash; one per line, <code>Name: line</code> for characters, plain text for narration
        <textarea rows="4" [(ngModel)]="dubScript" placeholder="ಅವನಿಗೆ ಎಲ್ಲವೂ ಆಟದ ಸಾಮಾನುಗಳಂತೆ ಕಾಣುತ್ತಿವೆ.&#10;Advik: ಅಮ್ಮಾ, ನೋಡು!"></textarea>
      </label>
      <div class="row">
        <label>Lines are in <select [(ngModel)]="dubFrom"><option value="">(same as target)</option><option *ngFor="let l of languages" [value]="l">{{ l }}</option></select></label>
        <label>Speak in <select [(ngModel)]="dubTo"><option *ngFor="let l of languages" [value]="l">{{ l }}</option></select></label>
        <label>Mode <select [(ngModel)]="dubMode"><option value="REVOICE">Re-voice (keep picture)</option><option value="REANIMATE">Re-animate with H3 (lips match)</option></select></label>
      </div>
      <div class="row">
        <label *ngIf="dubMode==='REVOICE'">Original sound level {{ dubOriginal | number:'1.2-2' }}
          <input type="range" min="0" max="0.6" step="0.05" [(ngModel)]="dubOriginal"></label>
        <label class="check" *ngIf="dubMode==='REANIMATE'"><input type="checkbox" [(ngModel)]="dubIndic"> Indic TTS voice instead of H3 speech</label>
      </div>
      <button class="btn btn-primary" [disabled]="busy || !hasSource() || !dubScript.trim()" (click)="runDub()">Dub video</button>
    </section>

    <section class="panel" *ngIf="tab==='analyze'">
      <h2>Analyze (hook &amp; virality check)</h2>
      <p class="muted">Measures the first seconds, shot changes, loudness, length and format, then the story LLM gives a score and concrete fixes.</p>
      <ng-container *ngTemplateOutlet="source"></ng-container>
      <label>Platform <select [(ngModel)]="platform"><option>Instagram Reels</option><option>YouTube Shorts</option><option>YouTube (long)</option><option>WhatsApp status</option></select></label>
      <button class="btn btn-primary" [disabled]="busy || !hasSource()" (click)="runAnalyze()">Analyze</button>
    </section>

    <section class="panel" *ngIf="tab==='camera'">
      <h2>Camera presets</h2>
      <p class="muted">Pick a preset in <a routerLink="/video-generation">Video Generation</a> (H3 or Wan) &mdash; it is added to the motion prompt. Kids presets suit story videos; Viral presets suit reels/skits.</p>
      <div class="presets">
        <div class="preset" *ngFor="let p of presets">
          <span class="tag">{{ p.category }}</span><strong>{{ p.name }}</strong>
          <p class="muted small">{{ p.prompt }}</p>
        </div>
      </div>
    </section>

    <ng-template #source>
      <div class="row">
        <label>Video file <input type="file" accept="video/*" (change)="pick($event,'source')"></label>
        <p class="ext" *ngIf="ext">Source: <strong>{{ ext.label }}</strong> <button type="button" class="btn small" (click)="ext=undefined">clear</button></p>
        <label>&hellip;or an earlier studio result
          <select [(ngModel)]="sourceJobId" (ngModelChange)="sourceFile=undefined">
            <option [ngValue]="undefined">&mdash;</option>
            <option *ngFor="let j of doneJobs()" [ngValue]="j.id">{{ label(j) }}</option>
          </select>
        </label>
      </div>
    </ng-template>

    <p class="error" *ngIf="error">{{ error }}</p>

    <section class="panel" *ngIf="jobs.length">
      <h2>Results</h2>
      <div class="job" *ngFor="let j of jobs">
        <div class="job-head">
          <strong>{{ toolName(j.tool) }}</strong>
          <span class="status" [class.ok]="j.status==='SUCCEEDED'" [class.bad]="j.status==='FAILED'">{{ j.status }}</span>
          <span class="muted small">{{ j.stage }}</span>
        </div>
        <p class="error small" *ngIf="j.errorMessage">{{ j.errorMessage }}</p>
        <pre class="report" *ngIf="j.report">{{ j.report }}</pre>
        <ng-container *ngIf="j.status==='SUCCEEDED' && j.resultUrl">
          <img *ngIf="j.resultExtension==='png'" [src]="api.studioVideoUrl(j.id)" class="cut" alt="cut-out">
          <video *ngIf="j.resultExtension!=='png'" [src]="api.studioVideoUrl(j.id)" controls playsinline></video>
          <p class="muted small">{{ j.summary }}<span *ngIf="j.resultSeconds"> &middot; {{ j.resultSeconds | number:'1.1-1' }}s</span></p>
          <div class="row">
            <a class="btn" [href]="api.studioVideoUrl(j.id)" download>Download</a>
            <ng-container *ngIf="j.resultExtension!=='png'">
              <button class="btn" (click)="chain(j,'upscale')">Upscale this</button>
              <button class="btn" (click)="chain(j,'reframe')">Reframe this</button>
              <button class="btn" (click)="chain(j,'dub')">Dub this</button>
              <button class="btn" (click)="chain(j,'analyze')">Analyze this</button>
            </ng-container>
          </div>
        </ng-container>
      </div>
    </section>
  </div>`,
  styles: [`
    .page{max-width:1100px;margin:0 auto;padding:1.5rem}
    .tabs{display:flex;gap:.5rem;flex-wrap:wrap;margin:1rem 0}
    .tabs button{padding:.5rem .9rem;border-radius:8px;border:1px solid var(--border,#3333);background:transparent;cursor:pointer;color:inherit}
    .tabs button.active{background:var(--accent,#6c5ce7);color:#fff;border-color:transparent}
    .panel{margin-bottom:1rem}
    label{display:flex;flex-direction:column;gap:.3rem;margin:.5rem 0}
    label.check{flex-direction:row;align-items:center}
    .grid2{display:grid;grid-template-columns:1fr 1fr;gap:1rem}
    .row{display:flex;gap:1rem;flex-wrap:wrap;align-items:flex-end}
    .previews{display:flex;gap:1rem}
    .previews img,.previews video{max-height:220px;border-radius:8px}
    .res-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:.6rem;margin:.8rem 0}
    .res{display:flex;flex-direction:column;gap:.2rem;text-align:left;padding:.7rem;border-radius:10px;border:1px solid var(--border,#3333);background:transparent;color:inherit;cursor:pointer}
    .res.active{border-color:var(--accent,#6c5ce7);box-shadow:0 0 0 2px var(--accent,#6c5ce7)}
    .presets{display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:.75rem}
    .preset{border:1px solid var(--border,#3333);border-radius:10px;padding:.7rem}
    .tag{font-size:.7rem;text-transform:uppercase;opacity:.7;margin-right:.4rem}
    .job{border-top:1px solid var(--border,#3333);padding:.8rem 0}
    .job-head{display:flex;gap:.7rem;align-items:center}
    .job video{max-width:100%;max-height:420px;border-radius:8px;margin-top:.5rem}
    .status.ok{color:#2ecc71}.status.bad{color:#e74c3c}
    .report{white-space:pre-wrap;background:rgba(127,127,127,.08);padding:.7rem;border-radius:8px;font-family:inherit}
    .cut{max-height:360px;background:repeating-conic-gradient(#8883 0% 25%,transparent 0% 50%) 50%/20px 20px;border-radius:8px}
    .ext{margin:.3rem 0}
    .warn{color:#e67e22}.error{color:#e74c3c}
    @media (max-width:700px){.grid2{grid-template-columns:1fr}}
  `]
})
export class MotionStudioComponent implements OnInit, OnDestroy {
  tab: Tab = 'motion';
  status?: StudioStatus;
  presets: CameraPreset[] = [];
  jobs: StudioJobView[] = [];
  busy = false;
  error = '';

  imageFile?: File; drivingFile?: File; sourceFile?: File; sourceJobId?: string;
  imagePreview = ''; drivingPreview = '';
  motionPrompt = ''; motionOrientation = 'vertical'; motionSeconds = 4.8;
  upscaleMode: 'AI' | 'FAST' = 'AI'; smooth24 = false;
  resolution = 1080;
  readonly resolutions = [
    { value: 720, label: '720p HD', vertical: '720×1280', horizontal: '1280×720', hint: 'Quick share, WhatsApp' },
    { value: 1080, label: '1080p Full HD', vertical: '1080×1920', horizontal: '1920×1080', hint: 'Reels, Shorts, YouTube' },
    { value: 1440, label: '1440p 2K', vertical: '1440×2560', horizontal: '2560×1440', hint: 'YouTube high quality' },
    { value: 2160, label: '2160p 4K', vertical: '2160×3840', horizontal: '3840×2160', hint: 'Archive, big screens' }
  ];
  resolutionName(): string { return this.resolutions.find(r => r.value === this.resolution)?.label || ''; }
  aspect = '9:16'; reframeMode = 'BLUR_FILL';
  bgImageFile?: File; bgMode = 'transparent'; bgColor = '#00ff00'; bgQuality = 'general';
  readonly languages = ['Kannada', 'Hindi', 'Tamil', 'Telugu', 'Malayalam', 'Marathi', 'Bengali', 'Gujarati', 'Punjabi', 'Odia', 'English'];
  dubScript = ''; dubFrom = ''; dubTo = 'Hindi'; dubMode = 'REVOICE'; dubOriginal = 0.15; dubIndic = true;
  platform = 'Instagram Reels';
  /** Video handed over from a story / production (?episodeId= / ?sequenceId=). */
  ext?: { episodeId?: string; sequenceId?: string; label: string };
  private poll?: Subscription;

  constructor(public api: ApiService, private route: ActivatedRoute) {}

  ngOnInit(): void {
    const q = this.route.snapshot.queryParamMap;
    const episodeId = q.get('episodeId') || undefined, sequenceId = q.get('sequenceId') || undefined;
    if (episodeId || sequenceId) { this.ext = { episodeId, sequenceId, label: episodeId ? 'final video of the story' : 'Story Video Production result' }; }
    const t = q.get('tab') as Tab | null;
    if (t) { this.tab = t; } else if (this.ext) { this.tab = 'upscale'; }
    this.api.studioStatus().subscribe({ next: s => { this.status = s; this.motionSeconds = Math.min(4.8, s.motionMaxSeconds || 4.8); }, error: () => {} });
    this.api.cameraPresets().subscribe({ next: p => this.presets = p, error: () => {} });
    this.refresh();
    this.poll = timer(3000, 3000).subscribe(() => { if (this.jobs.some(j => j.status === 'QUEUED' || j.status === 'RUNNING')) { this.refresh(); } });
  }

  ngOnDestroy(): void {
    this.poll?.unsubscribe();
    [this.imagePreview, this.drivingPreview].forEach(u => u && URL.revokeObjectURL(u));
  }

  refresh(): void { this.api.studioJobs().subscribe({ next: j => this.jobs = j, error: () => {} }); }

  pick(ev: Event, kind: 'image' | 'driving' | 'source' | 'bgImage'): void {
    const f = (ev.target as HTMLInputElement).files?.[0];
    if (!f) { return; }
    if (kind === 'image') { this.imageFile = f; if (this.imagePreview) { URL.revokeObjectURL(this.imagePreview); } this.imagePreview = URL.createObjectURL(f); }
    if (kind === 'driving') { this.drivingFile = f; if (this.drivingPreview) { URL.revokeObjectURL(this.drivingPreview); } this.drivingPreview = URL.createObjectURL(f); }
    if (kind === 'source') { this.sourceFile = f; this.sourceJobId = undefined; }
    if (kind === 'bgImage') { this.bgImageFile = f; }
  }

  hasSource(): boolean { return !!this.sourceFile || !!this.sourceJobId || !!this.ext; }
  private src(): StudioSource {
    return { video: this.sourceFile, sourceJobId: this.sourceJobId,
             episodeId: !this.sourceFile && !this.sourceJobId ? this.ext?.episodeId : undefined,
             sequenceId: !this.sourceFile && !this.sourceJobId ? this.ext?.sequenceId : undefined };
  }
  doneJobs(): StudioJobView[] { return this.jobs.filter(j => j.status === 'SUCCEEDED'); }
  toolName(t: StudioJobView['tool']): string { return ({ MOTION_CONTROL: 'Motion control', UPSCALE: 'Upscale', REFRAME: 'Reframe', BACKGROUND: 'Background removal', DUB: 'Dub', ANALYZE: 'Analysis' } as Record<string, string>)[t] || t; }
  label(j: StudioJobView): string { return `${this.toolName(j.tool)} - ${j.summary || j.id.slice(0, 8)}`; }

  runMotion(): void {
    if (!this.imageFile || !this.drivingFile) { return; }
    this.start(this.api.studioMotionControl(this.imageFile, this.drivingFile, this.motionPrompt, this.motionOrientation, this.motionSeconds));
  }
  runUpscale(): void { this.start(this.api.studioUpscale(this.src(), this.upscaleMode, this.smooth24, this.resolution)); }
  runBackground(): void {
    const s: StudioSource = this.bgImageFile ? { image: this.bgImageFile } : this.src();
    this.start(this.api.studioBackground(s, this.bgMode, this.bgColor, this.bgQuality));
  }
  runDub(): void {
    this.start(this.api.studioDub(this.src(), { script: this.dubScript, sourceLanguage: this.dubFrom || undefined, targetLanguage: this.dubTo,
      mode: this.dubMode, speechEngine: this.dubIndic ? 'INDIC_TTS' : 'H3', originalVolume: this.dubOriginal }));
  }
  runAnalyze(): void { this.start(this.api.studioAnalyze(this.src(), this.platform)); }
  runReframe(): void { this.start(this.api.studioReframe(this.src(), this.aspect, this.reframeMode)); }

  chain(j: StudioJobView, tab: Tab): void {
    this.sourceFile = undefined; this.sourceJobId = j.id; this.tab = tab;
    if (tab === 'upscale' && j.tool === 'MOTION_CONTROL') { this.smooth24 = true; }
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  private start(obs: import('rxjs').Observable<StudioJobView>): void {
    this.busy = true; this.error = '';
    obs.subscribe({
      next: j => { this.busy = false; this.jobs = [j, ...this.jobs.filter(x => x.id !== j.id)]; },
      error: e => { this.busy = false; this.error = e?.error?.message || e?.message || 'Could not start the job.'; }
    });
  }
}
