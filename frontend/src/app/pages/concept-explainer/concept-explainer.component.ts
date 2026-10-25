import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription, interval } from 'rxjs';
import { environment } from '../../../environments/environment';
import { VisualEnginePreviewComponent } from '../../shared/visual-engine-preview/visual-engine-preview.component';

interface ScriptIssue { scene: number; severity: string; code: string; message: string; fixable: boolean; }

interface ExplainerScene {
  sectionType?: string | null;
  sentences?: string[];
  pictureDescription?: string | null;
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
  visualEngine?: string;
  visualEngineReason?: string;
  actualRenderer?: string;
  rendererStatus?: string;
  rendererMessage?: string;
}

interface ExplainerJob {
  scriptIssues?: ScriptIssue[];
  scriptWords?: number;
  scriptSeconds?: number;
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
  imports: [CommonModule, FormsModule, VisualEnginePreviewComponent],
  template: `
    <div class="page concept-studio-page">
      <div class="studio-topbar"><div class="studio-brand"><span class="studio-mark">✦</span><div><span class="studio-kicker">AI STORY STUDIO / LEARNING</span><strong>Concept Studio</strong></div></div><div class="studio-actions"><button type="button" class="studio-ghost" (click)="scrollToSection('lesson-builder')">＋ New lesson</button><button type="button" class="studio-primary" (click)="scrollToSection('lesson-library')">Open lesson library ↗</button></div></div>
      <section class="studio-intro"><div class="studio-intro-copy"><div class="studio-overline"><span class="pulse-dot"></span> EXPLAIN COMPLEX IDEAS CLEARLY</div><h1>Turn any topic into a <em>lesson people remember.</em></h1><p>Build concept-first lessons with accurate explanations, purposeful animation, readable diagrams and language-aware narration—from Indian English to regional Indian languages.</p><div class="studio-promise"><span>01 / Understand</span><span>02 / Visualise</span><span>03 / Remember</span></div></div>
      <div class="studio-preview-card"><div class="preview-card-top"><span>LESSON BLUEPRINT</span><span class="live-pill"><i></i> MULTI-ENGINE</span></div><div class="blueprint-visual"><div class="blueprint-orbit orbit-one"></div><div class="blueprint-orbit orbit-two"></div><div class="blueprint-core">IDEA</div><div class="blueprint-node node-a">WHY</div><div class="blueprint-node node-b">HOW</div><div class="blueprint-node node-c">EXAMPLE</div></div><div class="blueprint-footer"><span><b>✓</b> Concept coverage</span><span><b>✓</b> Visual continuity</span><span><b>✓</b> Voice preview</span></div></div></section>
      <section class="studio-workflow" aria-label="Lesson workflow"><div class="workflow-step active"><span>01</span><div><b>Design the lesson</b><small>Topic, audience and learning goal</small></div></div><div class="workflow-line"></div><div class="workflow-step"><span>02</span><div><b>Build understanding</b><small>Examples, reasoning and checkpoints</small></div></div><div class="workflow-line"></div><div class="workflow-step"><span>03</span><div><b>Produce the lesson</b><small>Animation, narration and QA</small></div></div></section>
      <div class="hero legacy-hero">
        <div>
          <div class="eyebrow">LEARN WITH VISUALS</div>
          <h1>Concept Explainer</h1>
          <p>Build a topic-led lesson with scene-specific artwork, detailed explanatory diagrams, code or interface walkthroughs, and visuals revealed in sync with the explanation.</p>
        </div>
        <div class="hero-badge"><span>✦</span> Neon slides + synced voice + 1080p</div>
      </div>

      <app-visual-engine-preview (riveAssetUploaded)="onRiveAssetUploaded($event)"></app-visual-engine-preview>

      <section class="visual-intelligence card" *ngIf="isConceptStudio">
        <div class="vi-heading"><div><div class="eyebrow">VISUAL INTELLIGENCE</div><h2>Let the system plan the visuals</h2><p>Preview how each concept should be explained, which renderer fits each scene, and when the animation should follow the narration.</p></div><span class="vi-status">Explainable routing</span></div>
        <div class="vi-controls"><label for="vi-topic">Topic to analyse</label><input id="vi-topic" [(ngModel)]="topic" placeholder="e.g. Electric current, Java multithreading, projectile motion"/><button type="button" class="studio-primary" [disabled]="visualPlanLoading || !topic.trim()" (click)="previewVisualPlan()">{{ visualPlanLoading ? 'Planning…' : 'Plan visual strategy' }}</button></div>
        <div class="vi-error" *ngIf="visualPlanError">{{ visualPlanError }}</div>
        <div class="vi-result" *ngIf="visualPlan as vp">
          <div class="vi-goal"><strong>Learning goal</strong><p>{{ vp.learningGoal }}</p><small>{{ vp.visualStrategy }}</small></div>
          <div class="vi-concept-graph" *ngIf="vp.conceptGraph?.nodes?.length"><h3>Concept map</h3><div class="vi-concept-nodes"><article *ngFor="let node of vp.conceptGraph.nodes"><strong>{{ node.label }}</strong><small>{{ node.explanation }}</small></article></div><ul *ngIf="vp.conceptGraph.relationships?.length"><li *ngFor="let edge of vp.conceptGraph.relationships"><b>{{ conceptNodeLabel(vp, edge.from) }}</b> <span>→ {{ edge.relationship }} →</span> <b>{{ conceptNodeLabel(vp, edge.to) }}</b></li></ul></div>
          <div class="vi-engine-grid"><article class="vi-engine" *ngFor="let engine of vp.engines"><div><strong>{{ engine.label }}</strong><span [class.vi-unavailable]="!engine.available">{{ engine.available ? 'Available candidate' : 'Planned / not active' }}</span></div><b>{{ engine.score }}/100</b><p>{{ engine.reason }}</p></article></div>
          <h3>Suggested scene strategy</h3><div class="vi-scenes"><article *ngFor="let scene of vp.sceneIdeas"><span>{{ scene.timingGuidance }}</span><strong>{{ scene.visualIntent.replace('_',' ') }}</strong><small>Preferred: {{ scene.suggestedEngine }}</small><p>{{ scene.reason }}</p></article></div>
          <p class="vi-policy">{{ vp.policyNote }}</p>
        </div>
      </section>

      <section id="lesson-builder" class="builder card studio-builder">
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
              <optgroup label="English — Kokoro (open-source, fast, runs on CPU)">
                <option value="kokoro:am_michael">Michael — American English, male</option>
                <option value="kokoro:af_heart">Heart — American English, female</option>
                <option value="kokoro:bm_george">George — British English, male</option>
                <option value="kokoro:bf_emma">Emma — British English, female</option>
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
              <option value="GENERAL">General Learning / Science</option>
              <option value="GK">General Knowledge</option>
              <option value="KIDS_2_5">Kids 2–5 · Little Learners</option>
              <option value="KIDS">Kids 5–10 · Young Explorers</option>
              <option value="KIDS_10_15">Kids 10–15 · Tech Teens</option>
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
                <option>General</option><option>Science</option><option>Physics</option><option>Chemistry</option><option>Biology</option><option>Mathematics</option><option>Health / Medical</option><option>Social Studies</option><option>Business</option><option>Other</option>
              </ng-container>
              <ng-container *ngIf="track === 'GK'">
                <option>General Knowledge</option><option>History</option><option>Geography</option><option>Space</option><option>Inventions</option><option>Current Affairs</option><option>Sports</option>
              </ng-container>
              <ng-container *ngIf="track === 'KIDS_2_5'">
                <option>Colours &amp; Shapes</option><option>Numbers 1–5</option><option>Animals &amp; Sounds</option><option>Letters</option><option>Things at home (phone, light, fan)</option><option>Big &amp; Small</option>
              </ng-container>
              <ng-container *ngIf="track === 'KIDS'">
                <option>How things work</option><option>Computers &amp; Internet</option><option>Coding basics</option><option>AI &amp; Robots</option><option>Science for kids</option><option>Space</option><option>Maths for kids</option><option>Staying safe online</option>
              </ng-container>
              <ng-container *ngIf="track === 'KIDS_10_15'">
                <option>Coding &amp; Apps</option><option>AI &amp; Technology</option><option>How the internet &amp; games work</option><option>Problem solving</option><option>Real projects &amp; innovation</option><option>Science &amp; Space</option><option>Digital safety &amp; privacy</option>
              </ng-container>
            </select>
          </div>
          <div class="wide short-box" *ngIf="track === 'KIDS_2_5'">
            <h4>Ages 2–5 · Little Learners — calm, one thing at a time</h4>
            <ol>
              <li><b>Slow, calm and realistic pacing</b> — fast, fantastical cartoons measurably tire young children's attention; slow ones don't.</li>
              <li><b>A friend who talks with the child</b> — the toddler hero and robot buddy ask, then wait 2.5 s for an answer.</li>
              <li><b>One big thing per scene</b>, its word said three times; colours, shapes, counting to 5, big and small; picture-only choices (no reading).</li>
              <li><b>Warm 3D-illustrated playroom scenes</b> with the same characters every time.</li>
            </ol>
            <small class="sync-note">Best watched together with a grown-up, and kept short (1 minute).</small>
          </div>
          <div class="wide short-box" *ngIf="track === 'KIDS'">
            <h4>Ages 5–10 · Young Explorers — discover, create, be curious</h4>
            <ol>
              <li><b>A small mystery with three clues</b> the child helps solve, with the same hero and robot buddy every scene.</li>
              <li><b>Concrete examples</b> (lunch boxes, toys, posting a letter) — at this age logic works best on things they can picture.</li>
              <li><b>Ask, pause, answer</b>, the key idea repeated three ways, easy quiz and a "Great job!" badge.</li>
              <li><b>Cozy, magical 3D-illustrated study scenes</b> — starry window, light-bulb ideas, rockets.</li>
            </ol>
          </div>
          <div class="wide short-box" *ngIf="track === 'KIDS_10_15'">
            <h4>Ages 10–15 · Tech Teens — build, solve, create the future</h4>
            <ol>
              <li><b>A curiosity hook</b> about something they use (games, phones, apps) — curiosity measurably boosts memory.</li>
              <li><b>Respect, not lectures</b> — "you might try", real trade-offs, room to reason; nothing babyish.</li>
              <li><b>Levels with XP, challenges, myth vs fact</b>, and an Idea → Plan → Build → Test → Improve flow.</li>
              <li><b>Modern maker-studio 3D scenes</b> with holographic screens.</li>
            </ol>
          </div>
          <div class="wide" *ngIf="track === 'JEE' || track === 'NEET'">
            <label class="exam-toggle">
              <input type="checkbox" [(ngModel)]="examFocus" />
              <span><strong>Exam Focus</strong><small>Include an exam-style checkpoint, common traps, and application thinking — without turning the lesson into rote memorization.</small></span>
            </label>
          </div>
          <section class="wide jee-blueprint" *ngIf="track === 'JEE'" aria-label="JEE lesson structure">
            <div class="jee-blueprint-head"><span class="jee-kicker">JEE LEARNING PATH</span><h3>Build a concept-first, exam-ready lesson</h3><p>Teach the reasoning before shortcuts. Every formula must be explained, every numerical must show its steps, and practice questions must be original unless a verified source is supplied.</p></div>
            <div class="jee-settings-grid">
              <div><label>Target exam</label><select [(ngModel)]="jeeTarget"><option value="BOTH">JEE Main + Advanced</option><option value="MAIN">JEE Main</option><option value="ADVANCED">JEE Advanced</option></select></div>
              <div><label>Class / syllabus level</label><select [(ngModel)]="jeeClass"><option value="BOTH">Class 11 + 12 / combined</option><option value="CLASS_11">Class 11</option><option value="CLASS_12">Class 12</option><option value="FOUNDATION">Foundation / prerequisite revision</option></select></div>
              <div><label>Explanation depth</label><select [(ngModel)]="jeeDepth"><option value="BALANCED">Concept + worked example</option><option value="INTUITION">Build intuition from basics</option><option value="DERIVATION">Derivation / proof focused</option><option value="PROBLEM_SOLVING">Problem-solving focused</option><option value="REVISION">Fast revision and formula connections</option></select></div>
              <div><label>Practice level</label><select [(ngModel)]="jeeQuestionMode"><option value="MIXED">Conceptual + numerical / MCQ</option><option value="MAIN_STYLE">JEE Main-style practice</option><option value="ADVANCED_STYLE">JEE Advanced-style multi-step</option><option value="NUMERICAL">Numerical answer type</option><option value="CONCEPTUAL">Conceptual questions only</option></select></div>
            </div>
            <div class="jee-toggles"><label><input type="checkbox" [(ngModel)]="jeeFormulaRecap" /> Include formula / key-result recap with units and conditions</label><label><input type="checkbox" [(ngModel)]="jeeTrapCheck" /> Include common traps and a final checkpoint</label></div>
            <div class="jee-sequence"><b>Required teaching sequence</b><ol><li>Hook + learning objective</li><li>Prerequisite / intuition</li><li>Core concept with a labelled diagram or equation</li><li>Derivation, formula meaning, units and conditions</li><li>One fully worked problem, step by step</li><li>Exam-style practice with explained answer</li><li>Common mistake / trap</li><li>Formula recap + takeaway</li></ol><small>Adapt this sequence to the topic and lesson length. Do not force a derivation or formula sheet when it is not relevant.</small></div>
          </section>
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
            <small class="sync-note">Choose the spoken language. On-screen text and subtitles follow the lesson's configured visual/text pipeline; verify rendered output for your selected language.</small>
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
            <label>Narration track</label>
            <select [(ngModel)]="teachingStyle">
              <optgroup label="New narration tracks">
                <option value="HUMOROUS_EXPLAINER">Humorous explainer — witty, energetic, memorable</option>
                <option value="JOURNEY_EXPLAINER">Journey explainer — follow one thing end to end, in slow motion</option>
                <option value="LOGIC_FIRST_ANALYST">Logic-first analyst — structured reasoning, evidence, trade-offs</option>
                <option value="STORY_DRIVEN_MENTOR">Story-driven mentor — characters, challenge, reveal</option>
              </optgroup>
              <optgroup label="Classic styles">
                <option value="ENGAGING_TECH_TUTOR">Engaging tutor — friendly, light humour</option>
                <option value="STORYTELLING_TEACHER">Storytelling teacher</option>
                <option value="PROFESSIONAL_INSTRUCTOR">Professional instructor</option>
                <option value="SIMPLE_BEGINNER">Simple beginner-friendly</option>
              </optgroup>
            </select>
            <small class="sync-note">The track changes script structure, examples, pacing and per-scene voice direction—not just the voice name. Emotion-aware voices express it more fully; simpler TTS engines use pace and pitch. Accuracy and visual/narration sync stay mandatory.</small>
          </div>
          <div class="wide pre-box">
            <h4>Brief &amp; story <small class="sync-note">optional - the more you say, the better the lesson fits</small></h4>
            <div class="pre-grid">
              <div><label>Who is it for?</label><input [(ngModel)]="audience" maxlength="160" placeholder="e.g. Class 9 students in India, first-time coders"></div>
              <div><label>After watching they should…</label><input [(ngModel)]="outcome" maxlength="200" placeholder="e.g. be able to explain why the padlock matters"></div>
              <div><label>Tone</label>
                <select [(ngModel)]="tone"><option value="">Automatic</option><option>Warm and friendly</option><option>Playful and funny</option><option>Serious and clear</option><option>Curious and exciting</option><option>Calm and reassuring</option></select></div>
              <div><label>Visual variety</label>
                <select [(ngModel)]="dVar"><option value="LOW">Low - one tight visual system</option><option value="MID">Medium</option><option value="HIGH">Rich - varied visual idea each beat</option></select></div>
              <div><label>Motion</label>
                <select [(ngModel)]="dMotion"><option value="LOW">Calm - long soft fades</option><option value="MID">Medium</option><option value="HIGH">Energetic - quick changes</option></select></div>
              <div><label>Information on screen</label>
                <select [(ngModel)]="dDensity"><option value="LOW">Light - one idea per frame</option><option value="MID">Medium</option><option value="HIGH">Rich - layered detail</option></select></div>
            </div>
            <div class="concept-actions">
              <label class="btn" style="cursor:pointer">🎞 Take the style from a reference video<input type="file" accept="video/*" (change)="onReferenceStyle($event)" hidden></label>
              <small class="subtle" *ngIf="refBusy">Measuring… about half a minute.</small>
              <small class="subtle" style="color:#f87171" *ngIf="refErr">{{ refErr }}</small>
            </div>
            <ul class="issues" *ngIf="refNotes.length"><li *ngFor="let n of refNotes">{{ n }}</li></ul>
            <label class="check" *ngIf="manimChecked"><input type="checkbox" [(ngModel)]="useManim" [disabled]="!manimAvailable">
              Animated maths &amp; physics scenes (Manim)
              <small class="sync-note" *ngIf="manimAvailable">graphs, vectors, projectile motion, algebra steps, waves — used when a moving picture teaches the idea better (best for maths / physics)</small>
              <small class="sync-note" *ngIf="!manimAvailable">service not running — start it with: docker compose --profile manim up -d --build manim</small>
            </label>
            <label class="check"><input type="checkbox" [(ngModel)]="reviewScript"> Let me read and edit the script before any pictures or voice are made <small class="sync-note">(lesson, short, breakdown and kids formats)</small></label>
            <div class="concept-actions">
              <button class="btn" type="button" [disabled]="!topic.trim() || conceptsBusy" (click)="suggestConcepts()">{{ conceptsBusy ? 'Thinking…' : '💡 Suggest 3 story concepts' }}</button>
              <small class="subtle" *ngIf="concept">Using the concept “{{ concept.title }}” — <a href="javascript:void(0)" (click)="concept = null">clear</a></small>
            </div>
            <p class="warn" *ngIf="conceptsError">{{ conceptsError }}</p>
            <div class="concept-grid" *ngIf="concepts.length">
              <button type="button" class="concept" *ngFor="let c of concepts" [class.selected]="concept === c" (click)="concept = c">
                <span class="tag">{{ c.hookType }} · {{ c.approach }}</span>
                <strong>{{ c.title }}</strong>
                <em>“{{ c.hook }}”</em>
                <span>{{ c.angle }}</span>
                <small>{{ c.whyItWorks }}</small>
              </button>
            </div>
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

        <div id="lesson-library" class="lessons" *ngIf="lessons.length">
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

        <div *ngIf="job.status === 'PLAN_REVIEW' || (job.status === 'REGENERATING' && reviewBusy)" class="card storyboard">
          <div class="sb-head">
            <div>
              <div class="eyebrow">SCRIPT REVIEW · BEFORE ANY PICTURES OR VOICE</div>
              <h2>{{ job.title || job.topic }}</h2>
              <p class="subtle">{{ job.scriptWords }} words · about {{ job.scriptSeconds }} s spoken. Edit any line, let the app rewrite what it flagged, then continue.</p>
            </div>
            <div class="sb-actions">
              <button class="btn btn-primary" [disabled]="reviewBusy || job.status !== 'PLAN_REVIEW'" (click)="continueAfterReview()">✓ Looks good — draw and record</button>
              <button class="btn" [disabled]="reviewBusy || job.status !== 'PLAN_REVIEW' || !hasFixable()" (click)="fixScript()">{{ reviewBusy ? 'Rewriting…' : '✨ Rewrite the flagged scenes' }}</button>
              <small class="subtle">Nothing has used the GPU yet. After you continue, pictures and the voice are made.</small>
            </div>
          </div>
          <ul class="issues" *ngIf="job.scriptIssues?.length">
            <li *ngFor="let i of job.scriptIssues" [class.err]="i.severity === 'ERROR'"><b>{{ i.scene ? 'Scene ' + i.scene : 'Whole script' }}</b> {{ i.message }}</li>
          </ul>
          <p class="ok" *ngIf="!job.scriptIssues?.length">Nothing flagged: the hook, pacing, length and wording checks all passed.</p>
          <div class="sb-grid">
            <div class="sb-page" *ngFor="let sc of job.scenes">
              <div class="sb-body">
                <strong>{{ sc.sceneNumber }}. {{ sc.title }}</strong>
                <small class="subtle">{{ sc.sectionType }}</small>
                <textarea rows="5" [ngModel]="sbText[sc.sceneNumber] ?? (sc.sentences || []).join('\n')" (ngModelChange)="sbText[sc.sceneNumber] = $event"></textarea>
                <button class="btn" [disabled]="reviewBusy || sbText[sc.sceneNumber] === undefined" (click)="saveNarration(sc.sceneNumber)">Save words</button>
              </div>
            </div>
          </div>
        </div>

        <div *ngIf="job.status === 'STORYBOARD' || (job.status === 'REGENERATING' && sbBusy !== null)" class="card storyboard">
          <div class="sb-head">
            <div>
              <div class="eyebrow">STORYBOARD · STEP 1 OF 2</div>
              <h2>Check every picture</h2>
              <p class="subtle">Change what a scene says, describe a picture differently and redraw it — the characters stay the same. When every page looks right, make the video.</p>
            </div>
            <div class="sb-actions">
              <button class="btn btn-primary" [disabled]="sbBusy !== null || job.status !== 'STORYBOARD'" (click)="makeVideo(true)">🎬 Make animated cartoon</button>
              <button class="btn" [disabled]="sbBusy !== null || job.status !== 'STORYBOARD'" (click)="makeVideo(false)">▶ Quick video (camera moves)</button>
              <small class="subtle">Animated: every picture comes to life with Wan (blinks, smiles, gestures) — about 1–2 min per scene. Quick: slow pans and zooms, a few minutes in total.</small>
            </div>
          </div>
          <div class="sb-grid">
            <div class="sb-page" *ngFor="let sc of job.scenes">
              <div class="sb-img" [class.portrait]="job.duration === '60 seconds short'">
                <img *ngIf="sc.imageUrl" [src]="sc.imageUrl" alt="Scene {{ sc.sceneNumber }}">
                <div class="sb-busy" *ngIf="sbBusy === sc.sceneNumber">Drawing a new picture…</div>
              </div>
              <div class="sb-body">
                <strong>{{ sc.sceneNumber }}. {{ sc.title }}</strong>
                <label class="subtle">What is said (one sentence per line)</label>
                <textarea rows="4" [ngModel]="sbText[sc.sceneNumber] ?? (sc.sentences || []).join('\n')" (ngModelChange)="sbText[sc.sceneNumber] = $event"></textarea>
                <button class="btn" [disabled]="sbBusy !== null || sbText[sc.sceneNumber] === undefined" (click)="saveNarration(sc.sceneNumber)">Save words</button>
                <label class="subtle">Picture</label>
                <textarea rows="3" [ngModel]="sbPic[sc.sceneNumber] ?? sc.pictureDescription ?? ''" (ngModelChange)="sbPic[sc.sceneNumber] = $event"></textarea>
                <button class="btn" [disabled]="sbBusy !== null || job.status !== 'STORYBOARD'" (click)="redoPicture(sc.sceneNumber)">↻ New picture</button>
              </div>
            </div>
          </div>
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
              <div class="renderer-chip" *ngIf="scene.visualEngine"><b>Planned: {{ scene.visualEngine }}</b><span>{{ scene.visualEngineReason }}</span><small class="actual-renderer" [class.fallback-renderer]="scene.rendererStatus === 'FALLBACK'"><strong>Rendered with: {{ scene.actualRenderer || 'Not reported' }}</strong> · {{ scene.rendererStatus || 'UNKNOWN' }}<br>{{ scene.rendererMessage || 'Renderer details unavailable.' }}</small></div>
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
    .storyboard .sb-head{display:flex;gap:1.5rem;justify-content:space-between;align-items:flex-start;flex-wrap:wrap;margin-bottom:1rem}
    .storyboard .sb-actions{display:flex;flex-direction:column;gap:.5rem;max-width:340px}
    .sb-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:1rem}
    .sb-page{border:1px solid rgba(255,255,255,.08);border-radius:14px;overflow:hidden;background:rgba(255,255,255,.02)}
    .sb-img{position:relative;aspect-ratio:16/9;background:#111}
    .sb-img.portrait{aspect-ratio:9/16;max-height:520px;margin:0 auto}
    .sb-img img{width:100%;height:100%;object-fit:cover;display:block}
    .sb-busy{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;background:rgba(0,0,0,.55);color:#fff;font-weight:600}
    .sb-body{display:flex;flex-direction:column;gap:.4rem;padding:.8rem}
    .sb-body textarea{width:100%;resize:vertical}
    .pre-box{padding:.9rem 1rem;border:1px solid rgba(255,255,255,.1);border-radius:12px;background:rgba(255,255,255,.02)}
    .pre-box h4{margin:0 0 .6rem}
    .pre-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:.7rem;margin-bottom:.7rem}
    .concept-actions{display:flex;gap:.8rem;align-items:center;flex-wrap:wrap;margin-top:.7rem}
    .concept-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:.7rem;margin-top:.7rem}
    .concept{display:flex;flex-direction:column;gap:.35rem;text-align:left;padding:.8rem;border-radius:12px;border:1px solid rgba(255,255,255,.12);background:rgba(255,255,255,.03);color:inherit;cursor:pointer}
    .concept.selected{border-color:#4ade80;box-shadow:inset 0 0 0 1px #4ade80}
    .concept .tag{font-size:.7rem;text-transform:uppercase;letter-spacing:.05em;opacity:.7}
    .concept em{opacity:.9}
    .issues{margin:.4rem 0 1rem;padding-left:1.1rem;display:flex;flex-direction:column;gap:.3rem}
    .issues li.err{color:#f87171}
    .ok{color:#4ade80}

    .page{max-width:1320px;margin:0 auto}.hero{display:flex;justify-content:space-between;align-items:flex-end;gap:2rem;margin-bottom:1.8rem}.eyebrow{font-size:.68rem;letter-spacing:.16em;color:var(--teal);font-weight:800;margin-bottom:.45rem}.hero h1{font-size:clamp(2.2rem,4vw,3.7rem);margin-bottom:.45rem}.hero p,.section-title p,.status p,.result p{color:var(--muted);margin:.2rem 0}.hero-badge{padding:.7rem 1rem;border:1px solid var(--border);border-radius:999px;color:#bcb5c9;background:rgba(255,255,255,.025);white-space:nowrap}.hero-badge span{color:var(--accent);margin-right:.4rem}.builder{margin-bottom:1.4rem}.section-title{display:flex;justify-content:space-between;margin-bottom:1.25rem}.section-title h2,.status h2,.result h2,.scenes-head h2{margin:0}.form-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:1rem}.form-grid .wide{grid-column:1/-1}.form-grid input,.form-grid textarea,.form-grid select{width:100%}.mode-block{margin-top:.15rem}.mode-block>label{display:block;margin-bottom:.55rem}.mode-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.8rem}
    .short-box{margin-top:.8rem;padding:.9rem 1rem;border:1px solid #2a2a2e;border-radius:12px;background:#0b0b0c;color:#d4d4d8}
    .short-box h4{margin:0 0 .4rem;font-size:.95rem;color:#4ade80}
    .short-box ol{margin:.2rem 0 0 1.1rem;padding:0;font-size:.85rem;line-height:1.5}.mode-card{display:flex;align-items:center;gap:.85rem;text-align:left;width:100%;padding:1rem 1.05rem;border:1px solid var(--border);border-radius:14px;background:rgba(255,255,255,.02);color:var(--text);cursor:pointer;transition:border-color .18s,background .18s,transform .18s}.mode-card:hover{border-color:rgba(73,201,189,.55);transform:translateY(-1px)}.mode-card.selected{border-color:var(--teal);background:linear-gradient(135deg,rgba(73,201,189,.10),rgba(183,94,255,.07));box-shadow:0 0 0 1px rgba(73,201,189,.15),0 0 24px rgba(73,201,189,.07)}.mode-icon{width:42px;height:42px;border-radius:12px;display:grid;place-items:center;font-size:1.2rem;color:var(--teal);border:1px solid rgba(73,201,189,.35);background:#05070d;flex:0 0 auto}.mode-card.deep .mode-icon{color:#d39aff;border-color:rgba(211,154,255,.35)}.mode-copy{display:flex;flex-direction:column;gap:.22rem;min-width:0}.mode-copy strong{font-size:.9rem}.mode-copy small{font-size:.72rem;line-height:1.4;color:var(--muted)}.mode-check{margin-left:auto;color:var(--teal);font-weight:900;font-size:1.1rem;min-width:1rem}.form-footer{display:flex;justify-content:space-between;align-items:center;gap:1rem;margin-top:1.2rem}.hint{font-size:.78rem;color:var(--muted);max-width:760px;line-height:1.5}.generate{padding:.8rem 1.25rem}.workspace{display:flex;flex-direction:column;gap:1.3rem}.status{display:flex;justify-content:space-between;gap:1.5rem;align-items:center}.status-right{display:flex;gap:.45rem;flex-wrap:wrap;justify-content:flex-end}.pill{border:1px solid var(--border);border-radius:999px;padding:.3rem .65rem;color:var(--muted);font-size:.72rem}.error{border-color:rgba(226,102,91,.5);color:#ffb1a9;background:rgba(226,102,91,.07)}.script-actions{display:flex;gap:.6rem;align-items:center;flex-wrap:wrap;margin:.8rem 0 1rem;padding:.8rem;border:1px solid var(--border);border-radius:12px;background:rgba(255,255,255,.02)}.result-head{display:flex;justify-content:space-between;align-items:flex-start;gap:1rem;margin-bottom:1rem}.final-video{display:block;width:min(100%,980px);max-height:620px;background:#000;border-radius:12px;margin:0 auto}.scenes-head{display:flex;justify-content:space-between;align-items:flex-end}.subtle{font-size:.78rem;color:var(--muted)}.scene-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:1rem}.scene{padding:0;overflow:hidden}.scene-image-wrap{position:relative;background:#000;aspect-ratio:16/9}.scene-image-wrap img{width:100%;height:100%;object-fit:cover;display:block}.scene-image-wrap.portrait{aspect-ratio:9/16;max-height:420px;margin:0 auto}.scene-image-wrap.portrait img{object-fit:contain}.scene-number{position:absolute;top:10px;left:10px;width:32px;height:32px;border-radius:50%;display:grid;place-items:center;background:rgba(5,5,10,.84);border:1px solid rgba(73,201,189,.6);color:var(--teal);font-weight:800}.scene-body{padding:1rem}.scene-title{display:flex;justify-content:space-between;gap:.6rem;align-items:flex-start}.scene-title h3{font-size:1rem;margin:0}.scene-title span{font-size:.7rem;color:var(--muted);white-space:nowrap}.concept{color:var(--teal);font-weight:700;font-size:.82rem;margin:.45rem 0}.analogy{font-size:.78rem;color:#d8d1df;line-height:1.45}.actual-renderer{display:block;margin-top:6px;color:#b7c6dc;font-size:11px;line-height:1.45}.actual-renderer strong{color:#dbeafe}.fallback-renderer{color:#fbbf24!important}.renderer-chip{display:flex;flex-direction:column;gap:3px;border:1px solid var(--border);border-radius:8px;padding:.55rem .65rem;margin:.5rem 0;background:rgba(255,255,255,.025)}.renderer-chip b{font-size:.68rem;letter-spacing:.06em;color:var(--teal)}.renderer-chip span{font-size:.7rem;line-height:1.35;color:var(--muted)}.narration{font-size:.8rem;line-height:1.55;color:var(--muted);min-height:4.2em}.code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;background:#090a0f;border:1px solid #2b3440;color:#8fffb0;padding:.7rem;border-radius:9px;white-space:pre-wrap;font-size:.73rem;overflow:auto}.scene-actions{display:flex;flex-direction:column;gap:.45rem}.scene-actions audio{width:100%;height:34px}.scene-actions .btn{font-size:.72rem;padding:.5rem .7rem}.empty{text-align:center;padding:4rem 2rem;margin-top:1rem}.empty-icon{font-size:2rem;color:var(--accent);margin-bottom:.5rem}.empty p{max-width:700px;margin:0 auto;color:var(--muted);line-height:1.6}.examples{display:flex;justify-content:center;gap:.5rem;flex-wrap:wrap;margin-top:1.2rem}.example{background:transparent;border:1px solid var(--border);color:var(--muted);border-radius:999px;padding:.5rem .8rem}.example:hover{color:var(--text);border-color:var(--accent)}
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

    .visual-intelligence{margin:1rem 0 1.4rem;padding:1.2rem;border:1px solid rgba(115,234,217,.22);background:linear-gradient(135deg,rgba(115,234,217,.045),rgba(179,160,255,.035))}.vi-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:1rem}.vi-heading h2{margin:.2rem 0 .35rem}.vi-heading p,.vi-goal p{color:var(--muted);margin:.25rem 0;line-height:1.5}.vi-status{border:1px solid rgba(115,234,217,.35);border-radius:999px;padding:.35rem .6rem;font-size:.68rem;color:#8ee9db;white-space:nowrap}.vi-controls{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:.5rem;align-items:end;margin:1rem 0}.vi-controls label{grid-column:1/-1;font-size:.75rem;color:var(--muted)}.vi-controls input{min-width:0;width:100%;padding:.8rem;border-radius:9px;border:1px solid var(--border);background:rgba(0,0,0,.2);color:var(--text)}.vi-controls button{padding:.8rem 1rem;border-radius:9px;cursor:pointer}.vi-controls button:disabled{opacity:.5;cursor:not-allowed}.vi-error{color:#ffb1a9;font-size:.8rem;margin:.5rem 0}.vi-goal{padding:.9rem;border:1px solid var(--border);border-radius:10px;margin:.8rem 0}.vi-goal small,.vi-policy{color:var(--muted);font-size:.75rem;line-height:1.5}.vi-concept-graph{margin:.8rem 0}.vi-concept-graph h3{font-size:.95rem;margin:.8rem 0 .6rem}.vi-concept-nodes{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:.5rem}.vi-concept-nodes article{border:1px solid var(--border);border-radius:9px;padding:.7rem;background:rgba(0,0,0,.12)}.vi-concept-nodes strong,.vi-concept-nodes small{display:block}.vi-concept-nodes small{font-size:.72rem;line-height:1.4;color:var(--muted);margin-top:.3rem}.vi-concept-graph ul{padding-left:1.1rem;color:var(--muted);font-size:.75rem;line-height:1.6}.vi-concept-graph li b{color:var(--text)}.vi-engine-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:.6rem}.vi-engine,.vi-scenes article{border:1px solid var(--border);border-radius:10px;padding:.8rem;background:rgba(0,0,0,.12)}.vi-engine>div{display:flex;justify-content:space-between;gap:.5rem;align-items:center}.vi-engine>div span{font-size:.62rem;color:#8ee9db}.vi-engine>div .vi-unavailable{color:#f3c46b}.vi-engine>b{display:block;font-size:1.1rem;margin:.55rem 0}.vi-engine p,.vi-scenes p{font-size:.75rem;color:var(--muted);line-height:1.45;margin:.4rem 0 0}.vi-result h3{font-size:.95rem;margin:1rem 0 .6rem}.vi-scenes{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:.6rem}.vi-scenes article>span,.vi-scenes article>small{display:block;color:var(--muted);font-size:.68rem;margin-bottom:.4rem}.vi-scenes article>strong{display:block;text-transform:capitalize;margin-bottom:.35rem}.vi-policy{margin:.8rem 0 0}
    /* Concept Studio redesign */
    .concept-studio-page{max-width:1480px}
    .studio-topbar{display:flex;align-items:center;justify-content:space-between;gap:1rem;padding:.35rem 0 1.3rem;border-bottom:1px solid rgba(255,255,255,.08);margin-bottom:1.35rem}
    .studio-brand{display:flex;align-items:center;gap:.8rem}.studio-mark{display:grid;place-items:center;width:44px;height:44px;border-radius:15px;color:#071014;background:linear-gradient(135deg,#73ead9,#b3a0ff);font-size:1.35rem;box-shadow:0 8px 28px rgba(93,223,205,.18)}
    .studio-kicker{display:block;font-size:.58rem;letter-spacing:.18em;color:var(--muted);font-weight:800;margin-bottom:.2rem}.studio-brand strong{font-size:1.05rem;letter-spacing:-.03em}
    .studio-actions{display:flex;gap:.55rem;flex-wrap:wrap}.studio-actions button{border-radius:10px;padding:.7rem .95rem;font-weight:700;cursor:pointer}
    .studio-ghost{background:rgba(255,255,255,.035);border:1px solid var(--border);color:var(--text)}.studio-primary{border:1px solid rgba(115,234,217,.38);background:linear-gradient(120deg,rgba(115,234,217,.17),rgba(179,160,255,.14));color:var(--text)}
    .studio-intro{display:grid;grid-template-columns:minmax(0,1.15fr) minmax(300px,.85fr);gap:1.2rem;margin:1.3rem 0 1rem}
    .studio-intro-copy{position:relative;overflow:hidden;border:1px solid rgba(115,234,217,.16);border-radius:22px;padding:clamp(1.4rem,3vw,2.6rem);background:radial-gradient(ellipse at 8% 5%,rgba(115,234,217,.11),transparent 45%),linear-gradient(135deg,#101c25,#11121c 65%,#171429);min-height:310px;display:flex;flex-direction:column;justify-content:center}
    .studio-overline{font-size:.65rem;letter-spacing:.15em;font-weight:800;color:#8ee9db;display:flex;align-items:center;gap:.5rem}.pulse-dot{width:7px;height:7px;border-radius:50%;background:#73ead9;box-shadow:0 0 0 5px rgba(115,234,217,.1)}
    .studio-intro-copy h1{font-size:clamp(2rem,4.2vw,3.65rem);line-height:1.02;letter-spacing:-.055em;max-width:780px;margin:1.05rem 0 .85rem}.studio-intro-copy h1 em{font-style:normal;background:linear-gradient(90deg,#7fe8d8,#c2a9ff);-webkit-background-clip:text;background-clip:text;color:transparent}
    .studio-intro-copy p{max-width:650px;line-height:1.7;color:#b9c5d0;font-size:.96rem;margin:0}.studio-promise{display:flex;gap:.55rem;flex-wrap:wrap;margin-top:1.5rem}.studio-promise span{font-size:.66rem;font-weight:800;letter-spacing:.04em;padding:.42rem .65rem;border:1px solid rgba(255,255,255,.12);border-radius:7px;color:#d6e3e6;background:rgba(255,255,255,.025)}
    .studio-preview-card{border:1px solid rgba(255,255,255,.09);border-radius:22px;background:linear-gradient(160deg,#161825,#0d1119);padding:1.2rem;min-height:310px;display:flex;flex-direction:column;justify-content:space-between;overflow:hidden}
    .preview-card-top{display:flex;justify-content:space-between;gap:.5rem;align-items:center;font-size:.6rem;font-weight:800;letter-spacing:.13em;color:#a5b3c5}.live-pill{border:1px solid rgba(115,234,217,.24);border-radius:20px;padding:.32rem .48rem;color:#91e9da;letter-spacing:.06em}.live-pill i{display:inline-block;width:6px;height:6px;border-radius:50%;background:#73ead9;margin-right:.3rem}
    .blueprint-visual{height:205px;position:relative;display:grid;place-items:center;isolation:isolate}.blueprint-orbit{position:absolute;border:1px solid rgba(115,234,217,.18);border-radius:50%;transform:rotate(-22deg)}.orbit-one{width:220px;height:145px}.orbit-two{width:280px;height:180px;transform:rotate(33deg);border-color:rgba(179,160,255,.2)}.blueprint-core{width:88px;height:88px;display:grid;place-items:center;border-radius:26px;background:linear-gradient(145deg,#263f4b,#25213c);border:1px solid rgba(115,234,217,.55);color:#a8fff0;font-weight:900;letter-spacing:.12em;box-shadow:0 0 45px rgba(115,234,217,.12);z-index:2}.blueprint-node{position:absolute;padding:.5rem .65rem;border-radius:9px;background:#1a202d;border:1px solid rgba(255,255,255,.12);font-size:.62rem;letter-spacing:.08em;font-weight:900;color:#dbe6f2;box-shadow:0 10px 25px rgba(0,0,0,.22)}.node-a{top:20px;left:18%}.node-b{right:12%;top:72px}.node-c{bottom:14px;left:36%;border-color:rgba(179,160,255,.35)}
    .blueprint-footer{display:flex;justify-content:space-between;gap:.45rem;flex-wrap:wrap;border-top:1px solid rgba(255,255,255,.08);padding-top:.85rem;font-size:.63rem;color:#9ba9b9}.blueprint-footer b{color:#73ead9;margin-right:.3rem}
    .studio-workflow{display:flex;align-items:center;gap:.85rem;padding:1rem 1.2rem;margin-bottom:1.3rem;border:1px solid rgba(255,255,255,.08);border-radius:16px;background:rgba(255,255,255,.02)}.workflow-step{display:flex;align-items:center;gap:.7rem;min-width:0}.workflow-step>span{display:grid;place-items:center;flex:0 0 auto;width:34px;height:34px;border-radius:10px;background:rgba(255,255,255,.06);color:#9daaba;font-size:.72rem;font-weight:900}.workflow-step.active>span{background:rgba(115,234,217,.15);border:1px solid rgba(115,234,217,.3);color:#8ff0e1}.workflow-step b{display:block;font-size:.77rem}.workflow-step small{display:block;color:var(--muted);font-size:.64rem;margin-top:.15rem}.workflow-line{height:1px;background:linear-gradient(90deg,rgba(115,234,217,.35),rgba(255,255,255,.07));flex:1;min-width:12px}
    .legacy-hero{display:none}.studio-builder{scroll-margin-top:20px;border-radius:18px;border-color:rgba(255,255,255,.09);background:linear-gradient(150deg,rgba(255,255,255,.035),rgba(255,255,255,.012));padding:clamp(1rem,2vw,1.7rem)}.studio-builder .section-title h2{font-size:1.4rem;letter-spacing:-.035em}.studio-builder .section-title p{max-width:900px;line-height:1.65}
    .studio-builder label{font-weight:700;font-size:.78rem;color:#d6dce6}.studio-builder input:not([type=checkbox]):not([type=file]),.studio-builder textarea,.studio-builder select{border-radius:10px;border:1px solid rgba(255,255,255,.12);background:#11141c;color:var(--text);padding:.75rem .8rem;min-height:42px}.studio-builder textarea{line-height:1.6}.studio-builder input:focus,.studio-builder textarea:focus,.studio-builder select:focus{outline:2px solid rgba(115,234,217,.2);border-color:rgba(115,234,217,.5)}
    .studio-builder .mode-card{min-height:112px}.studio-builder .mode-card.selected{border-color:#73ead9;background:linear-gradient(135deg,rgba(115,234,217,.11),rgba(179,160,255,.06))}.studio-builder .mode-icon{background:rgba(115,234,217,.07)}
    .studio-builder .pre-box,.studio-builder .short-box{border-radius:14px;border-color:rgba(255,255,255,.1);background:rgba(5,8,14,.42)}.studio-builder .form-footer{position:sticky;bottom:1rem;z-index:5;padding:.8rem;border:1px solid rgba(115,234,217,.18);border-radius:14px;background:rgba(13,17,25,.94);backdrop-filter:blur(16px)}
    #lesson-library{scroll-margin-top:24px}
    .jee-blueprint{padding:1.1rem;border:1px solid rgba(115,234,217,.25);border-radius:16px;background:linear-gradient(145deg,rgba(115,234,217,.07),rgba(120,100,255,.045))}.jee-blueprint-head{margin-bottom:1rem}.jee-kicker{font-size:.68rem;letter-spacing:.14em;font-weight:900;color:#73ead9}.jee-blueprint h3{margin:.35rem 0;font-size:1.12rem}.jee-blueprint p,.jee-blueprint small{color:var(--muted);line-height:1.55}.jee-settings-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.8rem}.jee-settings-grid label{display:block;margin-bottom:.3rem}.jee-toggles{display:flex;gap:1rem;flex-wrap:wrap;margin:1rem 0}.jee-toggles label{display:flex;align-items:center;gap:.45rem;font-weight:500}.jee-sequence{padding:.85rem;border-radius:12px;background:rgba(0,0,0,.18);border:1px solid rgba(255,255,255,.08)}.jee-sequence b{font-size:.85rem}.jee-sequence ol{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.35rem .9rem;padding-left:1.25rem;margin:.65rem 0}.jee-sequence li{font-size:.8rem;line-height:1.45}.jee-sequence small{display:block;margin-top:.5rem}
    @media(max-width:1050px){.studio-intro{grid-template-columns:1fr}.studio-preview-card{min-height:270px}.form-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
    @media(max-width:680px){.jee-settings-grid,.jee-sequence ol{grid-template-columns:1fr}.studio-topbar{align-items:flex-start;flex-direction:column}.studio-intro-copy{min-height:0}.studio-workflow{align-items:flex-start;flex-direction:column}.workflow-line{width:1px;height:12px;flex:none;margin-left:16px}.form-grid,.mode-grid{grid-template-columns:1fr}.studio-actions{width:100%}.studio-actions button{flex:1}.studio-promise{gap:.35rem}.blueprint-footer{justify-content:flex-start}}
`]
})
export class ConceptExplainerComponent implements OnInit, OnDestroy {
  private base = environment.apiBaseUrl;
  private poll?: Subscription;
  readonly languages = ['English', 'Indian English', 'Kannada', 'Hindi', 'Hinglish', 'Telugu', 'Tamil', 'Malayalam', 'Marathi', 'Bengali', 'Gujarati', 'Punjabi', 'Odia'];
  isConceptStudio = false;
  visualPlanLoading = false;
  visualPlanError = '';
  visualPlan: any = null;
  topic = '';
  instructions = '';
  duration = '1 minute';
  language = 'English';
  difficulty = 'Complete Beginner';
  track = 'TECHNOLOGY';
  subject = 'Java / Programming';
  examFocus = false;
  jeeTarget = 'BOTH';
  jeeClass = 'BOTH';
  jeeDepth = 'BALANCED';
  jeeQuestionMode = 'MIXED';
  jeeFormulaRecap = true;
  jeeTrapCheck = true;
  teachingStyle = 'ENGAGING_TECH_TUTOR';
  destination = 'SOCIAL';
  motion = 'REVEAL';
  voice = 'narrator-in-male';
  model = '';
  models: string[] = [];
  lessons: LessonSummary[] = [];
  job: ExplainerJob | null = null;
  loading = false;
  selectedRiveAssetPath: string | null = null;
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

  conceptNodeLabel(plan: any, id: string): string {
    return plan?.conceptGraph?.nodes?.find((node: any) => node.id === id)?.label || id;
  }

  previewVisualPlan(): void {
    if (!this.topic.trim()) return;
    this.visualPlanLoading = true; this.visualPlanError = ''; this.visualPlan = null;
    this.http.post<any>(`${this.base}/concept-explainer/visual-intelligence/plan`, {
      topic: this.topic.trim(), audience: this.difficulty, subject: this.subject || this.track, duration: this.duration, complexity: 'balanced'
    }).subscribe({
      next: result => { this.visualPlan = result; this.visualPlanLoading = false; },
      error: error => { this.visualPlanLoading = false; this.visualPlanError = error?.error?.error || 'Could not build the visual plan. Check that the backend is running.'; }
    });
  }

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
    else if (this.track === 'GK') this.subject = 'General Knowledge';
    else if (this.track === 'KIDS') this.subject = 'How things work';
    else if (this.track === 'KIDS_2_5') this.subject = 'Colours & Shapes';
    else if (this.track === 'KIDS_10_15') this.subject = 'Coding & Apps';
    else this.subject = 'General';
  }

  ngOnInit(): void {
    this.isConceptStudio = this.router.url.startsWith('/concept-studio');
    this.http.get<{ available: boolean }>(`${this.base}/concept-explainer/manim/status`).subscribe({
      next: r => { this.manimAvailable = !!r?.available; this.manimChecked = true; },
      error: () => { this.manimAvailable = false; this.manimChecked = true; }
    });
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

  isWorking(): boolean { return !!this.job && !['SUCCEEDED', 'FAILED', 'STORYBOARD', 'PLAN_REVIEW'].includes(this.job.status) && !(this.job.status === 'REGENERATING' && this.reviewBusy); }

  // ---- pre-production: brief, concepts first, script review
  useManim = true; manimAvailable = false; manimChecked = false;
  refBusy = false; refErr = ''; refNotes: string[] = [];

  /** Measures a reference video and sets the lesson look and motion to match it (the voice, pictures and words are not copied). */
  onReferenceStyle(ev: Event): void {
    const input = ev.target as HTMLInputElement;
    const f = input.files?.[0];
    if (!f) return;
    this.refBusy = true; this.refErr = ''; this.refNotes = [];
    const fd = new FormData();
    fd.append('file', f);
    this.http.post<any>(`${this.base}/reference/analyse`, fd).subscribe({
      next: r => {
        this.refBusy = false;
        const s = r?.suggestions;
        if (!s) return;
        if (s.lessonStyle) this.style = s.lessonStyle;
        if (s.lessonMotion) this.dMotion = s.lessonMotion;
        this.refNotes = [`Set the look to “${s.lessonStyle}” and the motion to ${s.lessonMotion}.`, ...(s.notes || [])];
      },
      error: e => { this.refBusy = false; this.refErr = e?.error?.error || 'Could not analyse that video.'; }
    });
    input.value = '';
  }
  audience = ''; outcome = ''; tone = ''; dVar = 'HIGH'; dMotion = 'HIGH'; dDensity = 'MID'; reviewScript = true;
  concepts: any[] = []; concept: any = null; conceptTopic = ''; conceptsBusy = false; conceptsError = '';
  reviewBusy = false;

  suggestConcepts(): void {
    if (!this.topic.trim()) return;
    this.conceptsBusy = true; this.conceptsError = ''; this.concepts = []; this.concept = null;
    this.http.post<{ concepts: any[] }>(`${this.base}/concept-explainer/concepts`, {
      topic: this.topic.trim(), language: this.language, duration: this.duration, difficulty: this.difficulty,
      audience: this.audience, outcome: this.outcome, tone: this.tone, track: this.track, model: this.model
    }).subscribe({
      next: r => { this.conceptsBusy = false; this.concepts = r.concepts || []; this.conceptTopic = this.topic.trim(); },
      error: e => { this.conceptsBusy = false; this.conceptsError = e?.error?.error || 'Could not get concepts.'; }
    });
  }

  hasFixable(): boolean { return !!this.job?.scriptIssues?.some(i => i.fixable); }

  continueAfterReview(): void {
    if (!this.job) return;
    const id = this.job.id;
    this.http.post(`${this.base}/concept-explainer/jobs/${id}/continue`, {}).subscribe({
      next: () => this.open(id),
      error: e => { this.formError = e?.error?.error || 'Could not continue.'; }
    });
  }

  fixScript(): void {
    if (!this.job) return;
    const id = this.job.id;
    this.reviewBusy = true;
    this.http.post(`${this.base}/concept-explainer/jobs/${id}/doctor/fix`, {}).subscribe({
      next: () => {
        const t = setInterval(() => {
          if (!this.job || this.job.id !== id) { clearInterval(t); this.reviewBusy = false; return; }
          if (this.job.status === 'PLAN_REVIEW') { clearInterval(t); this.reviewBusy = false; this.sbText = {}; }
        }, 1000);
      },
      error: e => { this.reviewBusy = false; this.formError = e?.error?.error || 'Could not rewrite the script.'; }
    });
  }

  // ---- kids storyboard (pictures first, then the video)
  sbBusy: number | null = null;
  sbText: Record<number, string> = {};
  sbPic: Record<number, string> = {};

  private sbError(e: any, fallback: string): void { this.formError = e?.error?.message || e?.message || fallback; }

  saveNarration(n: number): void {
    if (!this.job || this.sbText[n] === undefined) return;
    const sentences = this.sbText[n].split('\n').map(x => x.trim()).filter(x => x);
    this.http.put(`${this.base}/concept-explainer/jobs/${this.job.id}/scenes/${n}/narration`, { sentences }).subscribe({
      next: () => { delete this.sbText[n]; },
      error: e => this.sbError(e, 'Could not save the words.')
    });
  }

  redoPicture(n: number): void {
    if (!this.job) return;
    this.sbBusy = n;
    this.http.post(`${this.base}/concept-explainer/jobs/${this.job.id}/scenes/${n}/picture`, { description: this.sbPic[n] ?? null }).subscribe({
      next: () => { delete this.sbPic[n]; this.watchStoryboard(); },
      error: e => { this.sbBusy = null; this.sbError(e, 'Could not redraw the picture.'); }
    });
  }

  /** Clears the "drawing" marker once the job is back at the storyboard step. */
  private watchStoryboard(): void {
    const id = this.job?.id;
    const t = setInterval(() => {
      if (!this.job || this.job.id !== id) { clearInterval(t); this.sbBusy = null; return; }
      if (this.job.status === 'STORYBOARD') { clearInterval(t); this.sbBusy = null; }
    }, 1000);
  }

  makeVideo(animated: boolean): void {
    if (!this.job) return;
    const id = this.job.id;
    this.http.post(`${this.base}/concept-explainer/jobs/${id}/video`, { animated }).subscribe({
      next: () => this.open(id),
      error: e => this.sbError(e, 'Could not start the video.')
    });
  }
  canEdit(): boolean { return this.job?.status === 'SUCCEEDED' && this.busyScene === null; }

  copyScript(): void {
    if (!this.job?.scriptUrl) return;
    this.http.get(this.job.scriptUrl, { responseType: 'text' }).subscribe({ next: text => navigator.clipboard?.writeText(text) });
  }

  onRiveAssetUploaded(assetPath: string): void { this.selectedRiveAssetPath = assetPath; }

  private buildGenerationInstructions(): string {
    const base = (this.instructions || '').trim();
    if (this.track !== 'JEE') return base;
    const subjectGuidance = this.subject === 'Physics'
      ? 'Physics structure: physical intuition -> labelled setup/free-body or circuit/ray/field diagram when relevant -> law and assumptions -> derive or interpret the equation -> units/dimensions and limiting cases -> worked numerical with substitutions -> exam practice.'
      : this.subject === 'Mathematics'
        ? 'Mathematics structure: define the objects and constraints -> explain the idea visually or algebraically -> derive the identity/result where useful -> show a fully worked solution with each transformation justified -> discuss valid shortcuts only after the reasoning -> exam practice.'
        : 'Chemistry structure: identify the relevant particle-level/model intuition -> explain the governing principle, equation, structure or mechanism -> state assumptions/conditions and units where relevant -> worked application/calculation -> exam practice.';
    const examLabel = this.jeeTarget === 'MAIN' ? 'JEE Main' : this.jeeTarget === 'ADVANCED' ? 'JEE Advanced' : 'both JEE Main and JEE Advanced, clearly distinguishing difficulty';
    const classLabel = ({ CLASS_11: 'Class 11', CLASS_12: 'Class 12', FOUNDATION: 'foundation/prerequisite revision', BOTH: 'combined Class 11/Class 12 level' } as Record<string, string>)[this.jeeClass] || 'combined Class 11/Class 12 level';
    const depthLabel = ({ INTUITION: 'build intuition from first principles', DERIVATION: 'prioritise derivation/proof and assumptions', PROBLEM_SOLVING: 'prioritise problem-solving strategy and worked examples', REVISION: 'fast revision, links between formulas, and high-yield misconceptions', BALANCED: 'balance intuition, explanation, and worked examples' } as Record<string, string>)[this.jeeDepth] || 'balance intuition, explanation, and worked examples';
    const practiceLabel = ({ MAIN_STYLE: 'one original JEE Main-style question', ADVANCED_STYLE: 'one original JEE Advanced-style multi-step question', NUMERICAL: 'one original numerical-answer question', CONCEPTUAL: 'one conceptual check question', MIXED: 'one conceptual question and one numerical/MCQ-style question when duration permits' } as Record<string, string>)[this.jeeQuestionMode] || 'a balanced exam-style question';
    return [base, 'JEE LESSON DESIGN (required):',
      `Target: ${examLabel}. Syllabus level: ${classLabel}. Explanation approach: ${depthLabel}.`, subjectGuidance,
      'Narration and scene order: (1) state the exact learning objective and a curiosity hook; (2) briefly check prerequisites; (3) build intuition before formal notation; (4) teach the core concept with a readable labelled diagram/equation and reveal parts in narration order; (5) derive or justify the key result when relevant, explaining symbols, units, assumptions and validity conditions; (6) solve one representative problem step by step, showing why each step is chosen; (7) present '+practiceLabel+' and explain the reasoning after a short thinking pause; (8) call out a common misconception or tempting wrong approach; (9) end with the key result/formula and when it applies.',
      'Accuracy rules: use standard NCERT/JEE terminology; do not invent syllabus coverage, formulas, derivations, answer keys, or previous-year-question claims. Practice questions must be labelled original JEE-style questions unless a verified source/reference was explicitly supplied. State assumptions and sign conventions. Check arithmetic, dimensions/units and the final answer. Do not teach shortcuts that work only for an unstated condition.',
      `Formula/key-result recap: ${this.jeeFormulaRecap ? 'include when relevant, with symbols, units and conditions' : 'omit a separate formula sheet unless essential to understanding'}.`,
      `Common-trap/final-checkpoint: ${this.jeeTrapCheck ? 'include one topic-relevant trap and a solved checkpoint' : 'do not force a separate trap or quiz'}.`,
      'Visual rules: use clean board-style equations, labelled physics diagrams/graphs, mathematical transformations, or chemistry particle/mechanism diagrams as appropriate. Highlight only the currently discussed step. Avoid decorative visuals that do not teach.'
    ].filter(Boolean).join('\n');
  }

  generate(): void {
    if (!this.topic.trim() || this.loading) return;
    this.loading = true;
    this.formError = '';
    this.http.post<{ jobId: string }>(`${this.base}/concept-explainer/jobs`, {
      topic: this.topic.trim(), instructions: this.buildGenerationInstructions(), language: this.language,
      duration: this.duration, difficulty: this.difficulty, motion: this.motion, model: this.model, voice: this.voice, style: this.style,
      track: this.track, subject: this.subject, examFocus: this.examFocus,
      teachingStyle: this.teachingStyle, destination: this.destination, format: this.format,
      audience: this.audience, outcome: this.outcome, tone: this.tone,
      concept: this.concept && this.conceptTopic === this.topic.trim() ? JSON.stringify(this.concept) : undefined,
      reviewScript: this.reviewScript, visualVariance: this.dVar, motionIntensity: this.dMotion, density: this.dDensity,
      useManim: this.useManim, riveAsset: this.selectedRiveAssetPath || undefined
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

  scrollToSection(id: string): void {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  trackScene(_: number, scene: ExplainerScene): number { return scene.sceneNumber; }
}
