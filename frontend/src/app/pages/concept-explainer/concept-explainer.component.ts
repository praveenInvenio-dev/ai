import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription, interval } from 'rxjs';
import { environment } from '../../../environments/environment';

interface ExplainerScene {
  sceneNumber: number;
  template: string;
  title: string;
  narration: string;
  code: string | null;
  durationSeconds: number;
  steps: number;
  imageUrl: string | null;
  audioUrl: string | null;
  canRedraw: boolean;
}

interface ExplainerJob {
  id: string;
  topic: string;
  title: string;
  summary: string;
  language: string;
  duration: string;
  difficulty: string;
  track: string;
  subject: string;
  examFocus: boolean;
  motion: string;
  status: string;
  stage: string;
  errorMessage: string | null;
  totalDurationSeconds: number;
  wordCount: number;
  videoUrl: string | null;
  warnings: string[];
  estimatedMinutes: number;
  narratorVoice: string;
  scriptUrl: string | null;
  scenes: ExplainerScene[];
}

interface LessonSummary { id: string; title: string; duration: string; language: string; status: string; totalDurationSeconds: number; }

@Component({
  selector: 'app-concept-explainer',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="page">
      <div class="hero">
        <div>
          <div class="eyebrow">LEARN WITH VISUALS</div>
          <h1>Concept Explainer</h1>
          <p>Build a topic-led lesson with scene-specific artwork, detailed explanatory diagrams, code or interface walkthroughs, and visuals revealed in sync with the explanation.</p>
        </div>
        <div class="hero-badge"><span>✦</span> Neon slides + synced voice + 1080p</div>
      </div>

      <section class="builder card">
        <div class="section-title"><div><h2>What do you want to learn?</h2><p>AI plans the teaching narrative and creates a dedicated visual for each scene. Diagrams, labels and code are rendered sharply over rich artwork, with progressive explanation beats.</p></div></div>
        <div class="form-grid">
          <div>
            <label>Visual style</label>
            <select [(ngModel)]="style">
              <option *ngFor="let st of styles" [value]="st.id">{{ st.label }}</option>
            </select>
            <small class="sync-note">{{ styleHint() }}</small>
          </div>
          <div class="short-box" *ngIf="format === 'SHORT'">
            <h4>60-second content</h4>
            <ol>
              <li>Title card — topic tag, concept name in green, one-line subtitle (0–4 s)</li>
              <li>The question + one tiny concrete example built on screen (4–15 s)</li>
              <li>How it works, one step per scene — cubes, chips, info cards, plots, values (15–45 s)</li>
              <li>The payoff with a number, then the concept named in one line (45–55 s)</li>
              <li>Outro: “Learn one concept a day.” (55–60 s)</li>
            </ol>
            <small class="sync-note">1080×1920 · near-black reel look · word-by-word captions · quiet music bed · same GPU-safe pipeline.</small>
          </div>
          <div class="short-box" *ngIf="format === 'BREAKDOWN'">
            <h4>Real-world breakdown</h4>
            <ol>
              <li>The challenge, on top of a 3D picture of the real product (“Build a train booking system like IRCTC.”)</li>
              <li>The naive answer — and why it breaks at real scale</li>
              <li>The user's journey step by step (tracker at the top): each step → the technique → “This is called …”, with the real query or rule</li>
              <li>Trade-offs, then “the best part” — one clever lesser-known detail</li>
              <li>Recap card + a question for the comments</li>
            </ol>
            <small class="sync-note">Tip: put the example in the topic (“Seat locking — explained with IRCTC”), or just the concept and the AI picks a relatable product. 3D blueprint look, glowing databases, phone screens, record slabs, locks, request swarms.</small>
          </div>
          <div *ngIf="format !== 'SHORT' && format !== 'BREAKDOWN'">
            <label>Lesson format</label>
            <select [(ngModel)]="format">
              <option value="TUTORIAL">Tutorial — reference-video teaching</option>
              <option value="CLASSIC">Classic slides + AI artwork</option>
            </select>
            <small class="sync-note">{{ format === 'TUTORIAL' ? 'Running example built up as a diagram, one element per sentence, calm instructor voice — in the visual style you pick.' : 'One template slide per scene with generated artwork.' }}</small>
          </div>
          <div>
            <label>Narrator voice</label>
            <select [(ngModel)]="voice">
              <optgroup label="Indian English — expressive (Chatterbox, local GPU)">
                <option value="narrator-in-male">Arjun — Indian English, male (recommended)</option>
                <option value="narrator-in-female">Meera — Indian English, female</option>
              </optgroup>
              <optgroup label="Indian English — Edge (cloud, needs internet)">
                <option value="edge:en-IN-PrabhatNeural">Prabhat — Indian English, male</option>
                <option value="edge:en-IN-NeerjaNeural">Neerja — Indian English, female</option>
              </optgroup>
              <optgroup *ngIf="edgeForLanguage().length" [label]="'Edge — ' + language">
                <option *ngFor="let v of edgeForLanguage()" [value]="v.id">{{ v.label }}</option>
              </optgroup>
              <optgroup label="US English — expressive (Chatterbox)">
                <option value="narrator-male">Ryan — US English, male</option>
                <option value="narrator-female">Amy — US English, female</option>
              </optgroup>
              <optgroup *ngIf="piperForLanguage().length" label="Local offline voices (Piper — no GPU, no internet)">
                <option *ngFor="let v of piperForLanguage()" [value]="v.id">{{ v.label }}</option>
              </optgroup>
              <optgroup *ngIf="clonedVoices.length" label="My voices (Voice Lab clones — expressive + your accent)">
                <option *ngFor="let v of clonedVoices" [value]="v.id">{{ v.label }}</option>
              </optgroup>
              <optgroup *ngIf="speakForLanguage().length" [label]="'Indic-Speak — ' + language + ' (teaching voice, GPU)'">
                <option *ngFor="let v of speakForLanguage()" [value]="v.id">{{ v.label }}</option>
              </optgroup>
              <optgroup *ngIf="indicForLanguage().length" [label]="'IndicF5 — native ' + language">
                <option *ngFor="let v of indicForLanguage()" [value]="v.id">{{ v.label }}</option>
              </optgroup>
            </select>
            <div style="display:flex;gap:.5rem;align-items:center;margin-top:.4rem">
              <button type="button" class="btn" (click)="testVoice()" [disabled]="testingVoice">{{ testingVoice ? 'Generating sample…' : '▶ Test this voice' }}</button>
              <audio *ngIf="voiceSampleUrl" [src]="voiceSampleUrl" controls autoplay style="height:32px"></audio>
            </div>
            <small class="warn" *ngIf="voiceTestError" style="display:block;color:#f87171">{{ voiceTestError }}</small>
            <small class="sync-note">More voices: choose an Indian narration language (below) for native Hindi / Kannada / Tamil ... voices · Indic-Speak voices appear while its service runs (COMPOSE_PROFILES=…,indicspeak) · clone your own voice in Voice Lab.</small>
            <small class="sync-note" *ngIf="voice.startsWith('narrator-') && language !== 'English' && language !== 'Indian English'">Chatterbox voices speak English only — for {{ language }} the native {{ language }} voice is used automatically.</small>
            <small class="sync-note" *ngIf="voice.startsWith('speak:')">Indic-Speak (Bodhan AI / AI4Bharat): built for teaching — maths, units and mixed Indian-language + English sentences in one voice. Needs the tts-indicspeak service; the first line loads the model (about a minute), later lines are quick. Built with Indic-Speak from Bodhan AI / AI4Bharat.</small>
            <small class="sync-note" *ngIf="voice.startsWith('indic:')">IndicF5 near-human {{ language }} voice (needs the tts-indic service). Slower than Edge — about a few seconds per sentence on GPU.</small>
          </div>
          <div>
            <label>Learning track</label>
            <select [(ngModel)]="track" (ngModelChange)="trackChanged()">
              <option value="TECHNOLOGY">Technology</option>
              <option value="JEE">JEE</option>
              <option value="NEET">NEET</option>
              <option value="GENERAL">General Learning</option>
            </select>
          </div>
          <div>
            <label>{{ track === 'TECHNOLOGY' ? 'Technology area' : track === 'GENERAL' ? 'Subject' : 'Subject' }}</label>
            <select [(ngModel)]="subject">
              <ng-container *ngIf="track === 'TECHNOLOGY'">
                <option>Java / Programming</option><option>Spring Boot</option><option>Python</option><option>Web / Angular / React</option><option>Cloud / DevOps</option><option>AI / GenAI</option><option>Other Technology</option>
              </ng-container>
              <ng-container *ngIf="track === 'JEE'">
                <option>Physics</option><option>Chemistry</option><option>Mathematics</option>
              </ng-container>
              <ng-container *ngIf="track === 'NEET'">
                <option>Biology</option><option>Physics</option><option>Chemistry</option>
              </ng-container>
              <ng-container *ngIf="track === 'GENERAL'">
                <option>General</option><option>Science</option><option>Mathematics</option><option>Social Studies</option><option>Business</option><option>Other</option>
              </ng-container>
            </select>
          </div>
          <div class="wide" *ngIf="track === 'JEE' || track === 'NEET'">
            <label class="exam-toggle">
              <input type="checkbox" [(ngModel)]="examFocus" />
              <span><strong>Exam Focus</strong><small>Include an exam-style checkpoint, common traps, and application thinking — without turning the lesson into rote memorization.</small></span>
            </label>
          </div>
          <div class="wide">
            <label>Topic</label>
            <input [(ngModel)]="topic" placeholder="e.g. Explain Java Variables" (keyup.enter)="generate()" />
          </div>
          <div class="wide">
            <label>Optional instructions</label>
            <textarea [(ngModel)]="instructions" rows="3" placeholder="Explain like I'm a complete beginner. Use shopping, banking, food delivery or other everyday examples."></textarea>
          </div>
          <div class="wide mode-block">
            <label>Choose your lesson</label>
            <div class="mode-grid">
              <button type="button" class="mode-card" [class.selected]="duration === '1 minute'" (click)="duration = '1 minute'; leaveShort()">
                <span class="mode-icon">⚡</span>
                <span class="mode-copy"><strong>1-Minute Quick Learn</strong><small>Core idea fast · ~60 sec · 9–11 slides<span class="est">Takes about 5–8 min to make</span></small></span>
                <span class="mode-check">{{ duration === '1 minute' ? '✓' : '' }}</span>
              </button>
              <button type="button" class="mode-card deep" [class.selected]="duration === '3 minutes'" (click)="duration = '3 minutes'; leaveShort()">
                <span class="mode-icon">✦</span>
                <span class="mode-copy"><strong>3-Minute Deep Dive</strong><small>Learn it properly · ~3 min · 18–22 slides · examples + pitfalls + recap<span class="est">Takes about 12–20 min to make</span></small></span>
                <span class="mode-check">{{ duration === '3 minutes' ? '✓' : '' }}</span>
              </button>
              <button type="button" class="mode-card breakdown" [class.selected]="format === 'BREAKDOWN'" (click)="pickBreakdown()">
                <span class="mode-icon">◈</span>
                <span class="mode-copy"><strong>Real-World Breakdown</strong><small>Vertical · ~2 min · any concept explained through one real product (e.g. how IRCTC books a seat) · 3D blueprint visuals<span class="est">Fast confident narration · takes about 15–25 min to make</span></small></span>
                <span class="mode-check">{{ format === 'BREAKDOWN' ? '✓' : '' }}</span>
              </button>
              <button type="button" class="mode-card short" [class.selected]="format === 'SHORT'" (click)="pickShort()">
                <span class="mode-icon">⏱</span>
                <span class="mode-copy"><strong>60-Second Short</strong><small>Vertical 9:16 reel · ~60 s · 7–9 scenes · title card, word-by-word captions, outro<span class="est">Reel look · takes about 5–10 min to make</span></small></span>
                <span class="mode-check">{{ format === 'SHORT' ? '✓' : '' }}</span>
              </button>
              <button type="button" class="mode-card deep" [class.selected]="duration === '10 minutes full'" (click)="duration = '10 minutes full'; format = 'TUTORIAL'">
                <span class="mode-icon">▶</span>
                <span class="mode-copy"><strong>10-Minute Full Lesson</strong><small>Course-style · ~10 min · 32–42 scenes · what / why / how / in practice / use cases / recap<span class="est">Tech tutorial style · takes about 25–45 min to make</span></small></span>
                <span class="mode-check">{{ duration === '10 minutes full' ? '✓' : '' }}</span>
              </button>
            </div>
          </div>
          <div>
            <label>Language</label>
            <select [(ngModel)]="language" (ngModelChange)="onLanguageChange()">
              <option *ngFor="let l of languages">{{ l }}</option>
            </select>
            <small class="sync-note">Only the voice uses this language. Everything on screen and the subtitles stay in English.</small>
          </div>
          <div>
            <label>Difficulty</label>
            <select [(ngModel)]="difficulty">
              <option>Complete Beginner</option><option>Beginner</option><option>Intermediate</option><option>Advanced</option>
            </select>
          </div>
          <div>
            <label>Motion</label>
            <select [(ngModel)]="motion">
              <option value="REVEAL">Synced reveal — each element appears as the voice explains it</option>
              <option value="STATIC">Static — full slide for the whole scene</option>
            </select>
          </div>
          <div>
            <label>Teaching style</label>
            <select [(ngModel)]="teachingStyle">
              <option value="ENGAGING_TECH_TUTOR">Engaging tech tutor (friendly, light humour) — default</option>
              <option value="STORYTELLING_TEACHER">Storytelling teacher</option>
              <option value="PROFESSIONAL_INSTRUCTOR">Professional instructor</option>
              <option value="SIMPLE_BEGINNER">Simple beginner-friendly</option>
            </select>
            <small class="sync-note">Changes tone and humour only; slides, pictures and voice always explain the same point at the same time.</small>
          </div>
          <div>
            <label>Where will it be used?</label>
            <select [(ngModel)]="destination">
              <option value="SOCIAL">YouTube / social video (ends with a subscribe line)</option>
              <option value="CLASSROOM">Classroom / internal training (no subscribe line)</option>
            </select>
          </div>
          <div>
            <label>Lesson model</label>
            <select [(ngModel)]="model">
              <option value="">Default Ollama model</option>
              <option *ngFor="let item of models" [value]="item">{{ item }}</option>
            </select>
          </div>
        </div>
        <div class="form-footer">
          <div class="hint"><strong>{{ duration === '1 minute' ? 'Quick Learn:' : duration === '3 minutes' ? 'Deep Dive:' : 'Full Lesson:' }}</strong> <ng-container *ngIf="format === 'TUTORIAL'; else artHint">every diagram, label, window and line of code is drawn by the app (always crisp and correct) and builds up sentence by sentence with the narration. No AI artwork, no music, no zoom.</ng-container><ng-template #artHint>text, code, tables and diagrams are drawn by the app; the AI image model draws scene artwork in the selected style. No zoom or pan — text stays sharp.</ng-template></div>
          <button class="btn btn-primary generate" [disabled]="loading || !topic.trim()" (click)="generate()">{{ loading ? 'Starting…' : 'Generate Concept Explainer' }}</button>
        </div>
        <p class="err-inline" *ngIf="formError">{{ formError }}</p>

        <div class="lessons" *ngIf="lessons.length">
          <label>My lessons <span class="subtle">(kept for 24 hours)</span></label>
          <ul>
            <li *ngFor="let l of lessons">
              <button type="button" [class.active]="job?.id === l.id" (click)="open(l.id)">
                <span>{{ l.title }}</span>
                <span class="subtle">{{ l.status }}<span *ngIf="l.totalDurationSeconds"> · {{ l.totalDurationSeconds | number:'1.0-0' }}s</span> · {{ l.language }}</span>
              </button>
            </li>
          </ul>
        </div>
      </section>

      <section *ngIf="job" class="workspace">
        <div class="status card">
          <div>
            <div class="eyebrow">{{ job.status }} · {{ job.duration === '2 minute breakdown' ? 'REAL-WORLD BREAKDOWN' : job.duration === '60 seconds short' ? '60-SECOND SHORT' : job.duration === '1 minute' ? 'QUICK LEARN' : job.duration === '3 minutes' ? 'DEEP DIVE' : 'FULL LESSON' }} · {{ job.track }}<span *ngIf="job.examFocus"> · EXAM FOCUS</span></div>
            <h2>{{ job.title || job.topic }}</h2>
            <p>{{ job.stage }}</p>
            <p class="subtle" *ngIf="isWorking()">Usually about {{ job.estimatedMinutes }} min. You can leave this page — the lesson is under “My lessons”.</p>
            <p class="warn" *ngFor="let w of job.warnings">⚠ {{ w }}</p>
          </div>
          <div class="status-right">
            <span class="pill">{{ job.language }}</span>
            <span class="pill">{{ job.subject }}</span>
            <span class="pill" *ngIf="job.examFocus">Exam Focus</span>
            <span class="pill">{{ job.scenes.length }} slides</span>
            <span class="pill" *ngIf="job.wordCount">{{ job.wordCount }} words</span>
            <span class="pill" *ngIf="job.totalDurationSeconds">{{ job.totalDurationSeconds | number:'1.0-1' }}s</span>
          </div>
        </div>

        <div *ngIf="job.errorMessage" class="error card">
          {{ job.errorMessage }}
          <button *ngIf="job.status === 'FAILED'" class="btn btn-primary" (click)="retry()">Retry lesson</button>
        </div>

        <div *ngIf="job.videoUrl" class="result card">
          <div class="result-head"><div><div class="eyebrow">FINAL LESSON</div><h2>{{ job.title }}</h2><p>{{ job.summary }}</p></div><a class="btn btn-primary" [href]="job.videoUrl" target="_blank" rel="noopener" download>Download video</a></div>
          <video class="final-video" [src]="job.videoUrl" controls playsinline></video>
        </div>

        <div *ngIf="job.scenes.length" class="scenes-head">
          <div><div class="eyebrow">SLIDE-BY-SLIDE</div><h2>Visual lesson</h2></div>
          <span class="subtle">Each slide builds up in steps as its sentences are spoken.</span>
        </div>

        <div class="scene-grid">
          <article class="scene card" *ngFor="let scene of job.scenes; trackBy: trackScene">
            <div class="scene-image-wrap" [class.portrait]="job.duration === '60 seconds short' || job.duration === '2 minute breakdown'">
              <img *ngIf="scene.imageUrl; else drawing" [src]="scene.imageUrl" [alt]="scene.title" />
              <ng-template #drawing><div class="placeholder">Drawing slide…</div></ng-template>
              <div class="scene-number">{{ scene.sceneNumber }}</div>
            </div>
            <div class="scene-body">
              <div class="scene-title"><h3>{{ scene.title }}</h3><span *ngIf="scene.durationSeconds">{{ scene.durationSeconds | number:'1.0-1' }}s</span></div>
              <p class="narration">{{ scene.narration }}</p>
              <p class="sync-note" *ngIf="scene.steps > 1">{{ scene.steps }} build steps, timed to the narration</p>
              <div class="scene-actions">
                <audio *ngIf="scene.audioUrl" [src]="scene.audioUrl" controls preload="none"></audio>
                <button *ngIf="scene.canRedraw" class="btn btn-ghost" [disabled]="!canEdit() || busyScene === scene.sceneNumber" (click)="regenerate(scene.sceneNumber, 'image')">{{ busyScene === scene.sceneNumber && busyKind === 'image' ? 'Working…' : 'New illustration' }}</button>
                <button class="btn btn-ghost" [disabled]="!canEdit() || busyScene === scene.sceneNumber" (click)="regenerate(scene.sceneNumber, 'audio')">{{ busyScene === scene.sceneNumber && busyKind === 'audio' ? 'Working…' : 'New narration' }}</button>
              </div>
            </div>
          </article>
        </div>
      </section>

      <section *ngIf="!job" class="empty card">
        <div class="empty-icon">✦</div>
        <h2>From “What is a Java variable?” to a visual lesson</h2>
        <p>One neon slide per idea, everyday analogies, a natural narrator — and each label, code part or row lights up exactly when it is explained.</p>
        <div class="examples"><button class="example" (click)="topic='Explain Java Variables'">Java Variables</button><button class="example" (click)="topic='Explain HashMap like I am a beginner'">Java HashMap</button><button class="example" (click)="topic='Explain Docker containers'">Docker Containers</button></div>
      </section>
    </div>
  `,
  styles: [`
    .page{max-width:1320px;margin:0 auto}.hero{display:flex;justify-content:space-between;align-items:flex-end;gap:2rem;margin-bottom:1.8rem}.eyebrow{font-size:.68rem;letter-spacing:.16em;color:var(--teal);font-weight:800;margin-bottom:.45rem}.hero h1{font-size:clamp(2.2rem,4vw,3.7rem);margin-bottom:.45rem}.hero p,.section-title p,.status p,.result p{color:var(--muted);margin:.2rem 0}.hero-badge{padding:.7rem 1rem;border:1px solid var(--border);border-radius:999px;color:#bcb5c9;background:rgba(255,255,255,.025);white-space:nowrap}.hero-badge span{color:var(--accent);margin-right:.4rem}.builder{margin-bottom:1.4rem}.section-title{display:flex;justify-content:space-between;margin-bottom:1.25rem}.section-title h2,.status h2,.result h2,.scenes-head h2{margin:0}.form-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:1rem}.form-grid .wide{grid-column:1/-1}.form-grid input,.form-grid textarea,.form-grid select{width:100%}.mode-block{margin-top:.15rem}.mode-block>label{display:block;margin-bottom:.55rem}.mode-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.8rem}
    .short-box{margin-top:.8rem;padding:.9rem 1rem;border:1px solid #2a2a2e;border-radius:12px;background:#0b0b0c;color:#d4d4d8}
    .short-box h4{margin:0 0 .4rem;font-size:.95rem;color:#4ade80}
    .short-box ol{margin:.2rem 0 0 1.1rem;padding:0;font-size:.85rem;line-height:1.5}.mode-card{display:flex;align-items:center;gap:.85rem;text-align:left;width:100%;padding:1rem 1.05rem;border:1px solid var(--border);border-radius:14px;background:rgba(255,255,255,.02);color:var(--text);cursor:pointer;transition:border-color .18s,background .18s,transform .18s}.mode-card:hover{border-color:rgba(73,201,189,.55);transform:translateY(-1px)}.mode-card.selected{border-color:var(--teal);background:linear-gradient(135deg,rgba(73,201,189,.10),rgba(183,94,255,.07));box-shadow:0 0 0 1px rgba(73,201,189,.15),0 0 24px rgba(73,201,189,.07)}.mode-icon{width:42px;height:42px;border-radius:12px;display:grid;place-items:center;font-size:1.2rem;color:var(--teal);border:1px solid rgba(73,201,189,.35);background:#05070d;flex:0 0 auto}.mode-card.deep .mode-icon{color:#d39aff;border-color:rgba(211,154,255,.35)}.mode-copy{display:flex;flex-direction:column;gap:.22rem;min-width:0}.mode-copy strong{font-size:.9rem}.mode-copy small{font-size:.72rem;line-height:1.4;color:var(--muted)}.mode-check{margin-left:auto;color:var(--teal);font-weight:900;font-size:1.1rem;min-width:1rem}.form-footer{display:flex;justify-content:space-between;align-items:center;gap:1rem;margin-top:1.2rem}.hint{font-size:.78rem;color:var(--muted);max-width:760px;line-height:1.5}.generate{padding:.8rem 1.25rem}.workspace{display:flex;flex-direction:column;gap:1.3rem}.status{display:flex;justify-content:space-between;gap:1.5rem;align-items:center}.status-right{display:flex;gap:.45rem;flex-wrap:wrap;justify-content:flex-end}.pill{border:1px solid var(--border);border-radius:999px;padding:.3rem .65rem;color:var(--muted);font-size:.72rem}.error{border-color:rgba(226,102,91,.5);color:#ffb1a9;background:rgba(226,102,91,.07)}.script-actions{display:flex;gap:.6rem;align-items:center;flex-wrap:wrap;margin:.8rem 0 1rem;padding:.8rem;border:1px solid var(--border);border-radius:12px;background:rgba(255,255,255,.02)}.result-head{display:flex;justify-content:space-between;align-items:flex-start;gap:1rem;margin-bottom:1rem}.final-video{display:block;width:min(100%,980px);max-height:620px;background:#000;border-radius:12px;margin:0 auto}.scenes-head{display:flex;justify-content:space-between;align-items:flex-end}.subtle{font-size:.78rem;color:var(--muted)}.scene-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:1rem}.scene{padding:0;overflow:hidden}.scene-image-wrap{position:relative;background:#000;aspect-ratio:16/9}.scene-image-wrap img{width:100%;height:100%;object-fit:cover;display:block}.scene-image-wrap.portrait{aspect-ratio:9/16;max-height:420px;margin:0 auto}.scene-image-wrap.portrait img{object-fit:contain}.scene-number{position:absolute;top:10px;left:10px;width:32px;height:32px;border-radius:50%;display:grid;place-items:center;background:rgba(5,5,10,.84);border:1px solid rgba(73,201,189,.6);color:var(--teal);font-weight:800}.scene-body{padding:1rem}.scene-title{display:flex;justify-content:space-between;gap:.6rem;align-items:flex-start}.scene-title h3{font-size:1rem;margin:0}.scene-title span{font-size:.7rem;color:var(--muted);white-space:nowrap}.concept{color:var(--teal);font-weight:700;font-size:.82rem;margin:.45rem 0}.analogy{font-size:.78rem;color:#d8d1df;line-height:1.45}.narration{font-size:.8rem;line-height:1.55;color:var(--muted);min-height:4.2em}.code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;background:#090a0f;border:1px solid #2b3440;color:#8fffb0;padding:.7rem;border-radius:9px;white-space:pre-wrap;font-size:.73rem;overflow:auto}.scene-actions{display:flex;flex-direction:column;gap:.45rem}.scene-actions audio{width:100%;height:34px}.scene-actions .btn{font-size:.72rem;padding:.5rem .7rem}.empty{text-align:center;padding:4rem 2rem;margin-top:1rem}.empty-icon{font-size:2rem;color:var(--accent);margin-bottom:.5rem}.empty p{max-width:700px;margin:0 auto;color:var(--muted);line-height:1.6}.examples{display:flex;justify-content:center;gap:.5rem;flex-wrap:wrap;margin-top:1.2rem}.example{background:transparent;border:1px solid var(--border);color:var(--muted);border-radius:999px;padding:.5rem .8rem}.example:hover{color:var(--text);border-color:var(--accent)}
    @media(max-width:1000px){.scene-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.form-grid{grid-template-columns:repeat(2,1fr)}.mode-grid{grid-template-columns:1fr}}
    @media(max-width:700px){.hero,.status,.result-head,.form-footer,.scenes-head{flex-direction:column;align-items:flex-start}.form-grid,.scene-grid{grid-template-columns:1fr}.form-grid .wide{grid-column:auto}.hero-badge{white-space:normal}.status-right{justify-content:flex-start}}
  
    .lessons{margin-top:1.4rem}.lessons ul{list-style:none;padding:0;margin:.6rem 0 0;display:grid;gap:.5rem}
    .lessons li button{width:100%;display:flex;justify-content:space-between;gap:1rem;text-align:left;padding:.7rem 1rem;border-radius:12px;border:1px solid rgba(127,127,127,.25);background:transparent;color:inherit;cursor:pointer}
    .lessons li button.active{border-color:var(--teal)}
    .placeholder{display:flex;align-items:center;justify-content:center;aspect-ratio:16/9;background:#05060c;color:#7d8aa5;font-size:.85rem;border-radius:12px}
    .warn{color:#f0a030;margin:.3rem 0}.err-inline{color:#ff6b6b;margin:.6rem 0}
    .est{display:block;margin-top:.25rem;opacity:.75}
    .exam-toggle{display:flex;gap:.8rem;align-items:flex-start;padding:.9rem 1rem;border:1px solid rgba(0,255,210,.25);border-radius:12px;background:rgba(0,255,210,.04);cursor:pointer}
    .exam-toggle input{margin-top:.25rem;accent-color:#00ffd5}
    .exam-toggle span{display:flex;flex-direction:column;gap:.25rem}.exam-toggle small{opacity:.72;line-height:1.45}
    .sync-note{font-size:.8rem;opacity:.75;margin-top:.3rem}
`]
})
export class ConceptExplainerComponent implements OnInit, OnDestroy {
  private base = environment.apiBaseUrl;
  private poll?: Subscription;
  readonly languages = ['English', 'Indian English', 'Kannada', 'Hindi', 'Hinglish', 'Telugu', 'Tamil', 'Malayalam', 'Marathi', 'Bengali', 'Gujarati', 'Punjabi', 'Odia'];
  topic = '';
  instructions = '';
  duration = '1 minute';
  language = 'English';
  difficulty = 'Complete Beginner';
  track = 'TECHNOLOGY';
  subject = 'Java / Programming';
  examFocus = false;
  teachingStyle = 'ENGAGING_TECH_TUTOR';
  destination = 'SOCIAL';
  motion = 'REVEAL';
  voice = 'narrator-in-male';
  model = '';
  models: string[] = [];
  lessons: LessonSummary[] = [];
  job: ExplainerJob | null = null;
  loading = false;
  formError = '';
  busyScene: number | null = null;
  busyKind = '';

  /** IndicF5 voices from the TTS service ("indic:kn-in-sapna" ...). */
  indicVoices: { id: string; label: string }[] = [];
  speakVoices: { id: string; label: string }[] = [];
  edgeVoices: { id: string; label: string }[] = [];
  clonedVoices: { id: string; label: string }[] = [];
  piperVoices: { id: string; label: string }[] = [];
  testingVoice = false;
  voiceSampleUrl = '';
  voiceTestError = '';

  /** Speaks a short sample with EXACTLY the selected engine (no fallback), so a broken service shows up here, not in the lesson. */
  testVoice(): void {
    const hindi = this.language === 'Hindi' || this.language === 'Hinglish';
    const samples: Record<string, string> = {
      Hindi: 'नमस्ते! आज हम एक नया कॉन्सेप्ट आसान भाषा में समझेंगे।',
      Hinglish: 'Namaste! Aaj hum ek naya concept bilkul simple tarike se samjhenge.',
      Kannada: 'ನಮಸ್ಕಾರ! ಇಂದು ನಾವು ಹೊಸ ವಿಷಯವನ್ನು ಸುಲಭವಾಗಿ ಕಲಿಯೋಣ.',
      Tamil: 'வணக்கம்! இன்று ஒரு புதிய கருத்தை எளிமையாகப் புரிந்துகொள்வோம்.',
      Telugu: 'నమస్కారం! ఈ రోజు ఒక కొత్త విషయాన్ని సులభంగా నేర్చుకుందాం.',
    };
    const text = samples[this.language] || (hindi ? samples['Hindi'] : 'Hi! Today we will understand one new concept, step by step, with a real example.');
    this.testingVoice = true; this.voiceTestError = '';
    if (this.voiceSampleUrl) { URL.revokeObjectURL(this.voiceSampleUrl); this.voiceSampleUrl = ''; }
    this.http.post(`${this.base}/tts/preview`, { text, voice: this.voice, speed: 1, pitch: 1 }, { responseType: 'blob' }).subscribe({
      next: b => { this.testingVoice = false; this.voiceSampleUrl = URL.createObjectURL(b); },
      error: async e => {
        this.testingVoice = false;
        let msg = 'This voice could not speak.';
        try { const t = await (e?.error as Blob).text(); msg = (JSON.parse(t)?.error) || t || msg; } catch { }
        this.voiceTestError = msg + ' — in a lesson this voice would fall back to another one.';
      }
    });
  }

  /** Piper voices for the narration language (en_* for English, hi_IN-* for Hindi ...). */
  piperForLanguage(): { id: string; label: string }[] {
    const english = ['English', 'Indian English'].includes(this.language);
    const code = english ? 'en' : (ConceptExplainerComponent.LANG_CODE[this.language] || '');
    return code ? this.piperVoices.filter(v => v.id.startsWith(code + '_')) : [];
  }
  style = 'reference';
  format: 'TUTORIAL' | 'CLASSIC' | 'SHORT' | 'BREAKDOWN' = 'TUTORIAL';

  pickBreakdown() { this.format = 'BREAKDOWN'; this.duration = '2 minute breakdown'; }

  pickShort() { this.format = 'SHORT'; this.duration = '60 seconds short'; }
  leaveShort() { if (this.format === 'SHORT' || this.format === 'BREAKDOWN') this.format = 'TUTORIAL'; }
  readonly styles = [
    { id: 'reference', label: 'Tech tutorial (reference video)', hint: 'Course style: dark-teal canvas, one running example built up as a diagram (user, agent, tiles, dashed arrows, chat/terminal/browser/code windows). One element appears per spoken sentence. Calm instructor voice, no music, no AI artwork.' },
    { id: 'neon', label: 'Neon glow', hint: 'Black canvas, glowing neon panels and code — tech look.' },
    { id: 'sketchnote', label: 'Sketchnote (hand-drawn)', hint: 'Cream paper, hand-drawn ink lines, handwritten font, pastel highlights.' },
    { id: 'storyboard', label: 'Clean storyboard (3D icons)', hint: 'Light pastel cards with soft shadows and glossy 3D-style illustrations.' },
    { id: 'chalkboard', label: 'Chalkboard classroom', hint: 'Green board, chalk lines and handwriting — classroom feel.' },
    { id: 'blueprint', label: 'Blueprint (engineering)', hint: 'Blue grid paper with white technical lines.' },
    { id: 'anime', label: 'Anime pop', hint: 'Bright pop colours, bold outlines, comic title font, anime-style illustrations.' }
  ];
  styleHint(): string { return this.styles.find(s => s.id === this.style)?.hint || ''; }
  private static readonly LANG_CODE: Record<string, string> = {
    Kannada: 'kn', Hindi: 'hi', Hinglish: 'hi', Telugu: 'te', Tamil: 'ta', Malayalam: 'ml', Marathi: 'mr',
    Bengali: 'bn', Gujarati: 'gu', Punjabi: 'pa', Odia: 'or'
  };

  constructor(private http: HttpClient, private route: ActivatedRoute, private router: Router) {}

  edgeForLanguage(): { id: string; label: string }[] {
    const english = ['English', 'Indian English'].includes(this.language);
    const code = english ? 'en' : ConceptExplainerComponent.LANG_CODE[this.language];
    return code ? this.edgeVoices.filter(v => v.id.startsWith('edge:' + code + '-')) : [];
  }

  /** Indic-Speak voices for the chosen language; English / Indian English get its Indian-accent English teaching voices. */
  speakForLanguage(): { id: string; label: string }[] {
    const english = ['English', 'Indian English'].includes(this.language);
    const code = english ? 'en' : (ConceptExplainerComponent.LANG_CODE[this.language] || '');
    return code ? this.speakVoices.filter(v => v.id.startsWith('speak:' + code + '-')) : [];
  }

  indicForLanguage(): { id: string; label: string }[] {
    const code = ConceptExplainerComponent.LANG_CODE[this.language];
    return code ? this.indicVoices.filter(v => v.id.startsWith('indic:' + code + '-')) : [];
  }

  onLanguageChange(): void {
    // IndicF5 voices are per language (and never English): reset an invalid choice.
    if ((this.voice.startsWith('speak:') && !this.speakForLanguage().some(v => v.id === this.voice))
        || (this.voice.startsWith('indic:') && !this.indicForLanguage().some(v => v.id === this.voice))
        || (this.voice.startsWith('edge:') && !this.voice.startsWith('edge:en-IN') && !this.edgeForLanguage().some(v => v.id === this.voice))
        || (/^[a-z]{2}_[A-Z]{2}-/.test(this.voice) && !this.piperForLanguage().some(v => v.id === this.voice))) {
      this.voice = 'narrator-in-male';
    }
  }

  trackChanged(): void {
    this.examFocus = this.track === 'JEE' || this.track === 'NEET' ? this.examFocus : false;
    if (this.track === 'TECHNOLOGY') this.subject = 'Java / Programming';
    else if (this.track === 'JEE') this.subject = 'Physics';
    else if (this.track === 'NEET') this.subject = 'Biology';
    else this.subject = 'General';
  }

  ngOnInit(): void {
    this.http.get<any>(`${this.base}/tts/voices`).subscribe({
      next: r => {
        const list: any[] = r?.voices || [];
        // "xx-in-name" IndicF5 references were synthesised from the Edge voice of the same name (evaluation clips): they sound
        // like that Edge voice. The others are real human recordings - those give a genuinely different voice.
        this.indicVoices = list.filter(v => String(v.id).startsWith('indic:') && v.installed !== false)
          .map(v => {
            const id = String(v.id).replace('indic:', '');
            const edgeCopy = /^[a-z]{2}-in-/.test(id);
            const name = edgeCopy ? id.replace(/^[a-z]{2}-in-/, '') : id.replace(/^[a-z]{2}-/, '');
            return { id: v.id, label: edgeCopy ? name.charAt(0).toUpperCase() + name.slice(1) + ' — copy of the Edge voice (sounds the same)'
                                              : name + ' — real human recording', real: !edgeCopy };
          })
          .sort((a: any, b: any) => (b.real ? 1 : 0) - (a.real ? 1 : 0));
        this.speakVoices = list.filter(v => String(v.id).startsWith('speak:'))
          .map(v => ({ id: v.id, label: v.label || String(v.id).replace('speak:', '') }));
        this.piperVoices = list.filter(v => /^[a-z]{2}_[A-Z]{2}-/.test(String(v.id)))
          .map(v => ({ id: v.id, label: (v.label || v.id) + (v.gender ? ' — ' + v.gender : '') + (v.installed ? '' : ' (downloads on first use)') }));
        this.edgeVoices = list.filter(v => String(v.id).startsWith('edge:') && !String(v.id).startsWith('edge:en-IN'))
          .map(v => ({ id: v.id, label: v.label || String(v.id).replace('edge:', '').replace('Neural', '') }));
        this.clonedVoices = list.filter(v => String(v.id).startsWith('profile:'))
          .map(v => ({ id: v.id, label: v.label || v.name || String(v.id).replace('profile:', '') }));
      },
      error: () => {}
    });
    this.http.get<any>(`${this.base}/models/ollama`).subscribe({ next: r => this.models = r?.models || [], error: () => {} });
    this.loadLessons();
    const id = this.route.snapshot.queryParamMap.get('job');
    if (id) { this.open(id); }
  }

  ngOnDestroy(): void { this.poll?.unsubscribe(); }

  loadLessons(): void {
    this.http.get<LessonSummary[]>(`${this.base}/concept-explainer/jobs`).subscribe({ next: l => this.lessons = l, error: () => {} });
  }

  isWorking(): boolean { return !!this.job && !['SUCCEEDED', 'FAILED'].includes(this.job.status); }
  canEdit(): boolean { return this.job?.status === 'SUCCEEDED' && this.busyScene === null; }

  copyScript(): void {
    if (!this.job?.scriptUrl) return;
    this.http.get(this.job.scriptUrl, { responseType: 'text' }).subscribe({ next: text => navigator.clipboard?.writeText(text) });
  }

  generate(): void {
    if (!this.topic.trim() || this.loading) return;
    this.loading = true;
    this.formError = '';
    this.http.post<{ jobId: string }>(`${this.base}/concept-explainer/jobs`, {
      topic: this.topic.trim(), instructions: this.instructions, language: this.language,
      duration: this.duration, difficulty: this.difficulty, motion: this.motion, model: this.model, voice: this.voice, style: this.style,
      track: this.track, subject: this.subject, examFocus: this.examFocus,
      teachingStyle: this.teachingStyle, destination: this.destination, format: this.format
    }).subscribe({
      next: r => { this.loading = false; this.open(r.jobId); this.loadLessons(); },
      error: e => { this.loading = false; this.formError = e?.error?.message || e?.message || 'Could not start the concept explainer.'; }
    });
  }

  /** Opens a lesson and keeps its id in the address bar, so a refresh or coming back works. */
  open(id: string): void {
    this.router.navigate([], { queryParams: { job: id }, replaceUrl: true });
    this.poll?.unsubscribe();
    const fetch = () => this.http.get<ExplainerJob>(`${this.base}/concept-explainer/jobs/${id}`).subscribe({
      next: job => {
        this.job = job;
        if (this.busyScene !== null && job.status !== 'REGENERATING') { this.busyScene = null; this.busyKind = ''; }
        if (['SUCCEEDED', 'FAILED'].includes(job.status) && this.busyScene === null) {
          this.poll?.unsubscribe();
          this.loadLessons();
        }
      },
      error: e => {
        if (e?.status === 404) { this.poll?.unsubscribe(); this.job = null; this.formError = 'That lesson has expired.'; }
      }
    });
    fetch();
    this.poll = interval(2500).subscribe(fetch);
  }

  retry(): void {
    if (!this.job) return;
    this.http.post<{ jobId: string }>(`${this.base}/concept-explainer/jobs/${this.job.id}/retry`, {}).subscribe({
      next: r => { this.open(r.jobId); this.loadLessons(); },
      error: e => this.formError = e?.error?.message || 'Could not retry.'
    });
  }

  regenerate(scene: number, kind: string): void {
    if (!this.job || !this.canEdit()) return;
    this.busyScene = scene;
    this.busyKind = kind;
    const id = this.job.id;
    this.http.post(`${this.base}/concept-explainer/jobs/${id}/scenes/${scene}/regenerate?kind=${kind}`, {}).subscribe({
      next: () => { if (this.job) { this.job.status = 'REGENERATING'; } this.open(id); },
      error: e => { this.busyScene = null; this.busyKind = ''; this.formError = e?.error?.message || 'Could not regenerate this scene.'; }
    });
  }

  trackScene(_: number, scene: ExplainerScene): number { return scene.sceneNumber; }
}
