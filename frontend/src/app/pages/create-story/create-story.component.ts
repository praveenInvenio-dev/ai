import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { Project, Universe, Character } from '../../models/models';

@Component({
  selector: 'app-create-story',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="hero">
      <div>
        <div class="eyebrow">AI STORY STUDIO</div>
        <h1>What would you like to create?</h1>
        <p class="sub">Describe the idea in your own words. The studio creates a scene-by-scene draft with narrator and character dialogue, emotional delivery and pauses. Images are generated first; you choose voices before the final video is rendered.</p>
      </div>
      <div class="hero-badge">🎬 Story → Scenes → Images → Video</div>
    </div>

    <div class="card">
      <textarea
        [(ngModel)]="prompt"
        rows="4"
        placeholder="e.g. Create a funny 3-minute story about Bobo and Mimi discovering a magical moon."
      ></textarea>

      <div class="learning-panel">
        <div class="trend-head"><div><strong>🎬 Entertainment Learning</strong><span>Teach any topic through a funny, cinematic story — not a lecture.</span></div><span class="live-chip">ANY SUBJECT</span></div>
        <div class="learning-grid">
          <div><label>Learning category</label><select [(ngModel)]="learningCategory" (ngModelChange)="applyLearningCategory()"><option *ngFor="let c of learningCategories" [value]="c.value">{{ c.label }}</option></select></div>
          <div><label>Learning style</label><select [(ngModel)]="learningStyle"><option *ngFor="let s of learningStyles" [value]="s.value">{{ s.label }}</option></select></div>
          <div><label>Difficulty</label><select [(ngModel)]="learningDifficulty"><option>Beginner</option><option>Intermediate</option><option>Advanced</option><option>Interview / Exam</option></select></div>
          <div><label>Learning duration</label><select [(ngModel)]="learningDuration" (ngModelChange)="syncLearningDuration()"><option [ngValue]="30">30 sec</option><option [ngValue]="60">60 sec</option><option [ngValue]="90">90 sec</option><option [ngValue]="180">3 min</option></select></div>
          <div><label>Language style</label><select [(ngModel)]="learningLanguageStyle"><option *ngFor="let m of learningLanguageStyles" [value]="m.value">{{ m.label }}</option></select></div>
        </div>
        <div class="field-help">The engine first identifies the correct concept, then hides the explanation inside a story. Medical, legal and exam content gets stricter accuracy/currentness instructions.</div>
      </div>

      <div class="trend-panel">
        <div class="trend-head"><div><strong>🔥 Trend & Satire Studio</strong><span>Turn a hot topic into obvious, balanced fictional satire — roast the contradiction, not just one side.</span></div><span class="live-chip">CURRENT-AFFAIRS READY</span></div>
        <div class="preset-grid">
          <button type="button" class="preset-card" *ngFor="let p of satirePresets" (click)="applySatirePreset(p)">
            <strong>{{ p.name }}</strong><span>{{ p.genre }}</span>
          </button>
        </div>
        <div class="field-help">For live political topics, the engine keeps verified context separate from invented scenes and dialogue; it will not present fictional claims as real news.</div>
      </div>

      <div class="section-heading">
        <div><h2>Story settings</h2><span>Control the story, audience and generation model.</span></div>
      </div>

      <div class="grid">
        <div>
          <label>Project</label>
          <select [(ngModel)]="selectedProjectId" (ngModelChange)="onProjectChange()">
            <option value="__new__">+ New project</option>
            <option *ngFor="let p of projects" [value]="p.id">{{ p.name }}</option>
          </select>
        </div>
        <div *ngIf="selectedProjectId === '__new__'">
          <label>New project name</label>
          <input [(ngModel)]="newProjectName" placeholder="Magical Forest Stories" />
        </div>
        <div>
          <label>Universe (optional)</label>
          <select [(ngModel)]="selectedUniverseId" (ngModelChange)="onUniverseChange()" [disabled]="!universes.length">
            <option value="">Standalone story (no universe)</option>
            <option *ngFor="let u of universes" [value]="u.id">{{ u.name }}</option>
          </select>
        </div>
        <div>
          <label>Duration</label>
          <select [(ngModel)]="durationSeconds">
            <option [ngValue]="60">1 min</option>
            <option [ngValue]="180">3 min</option>
            <option [ngValue]="300">5 min</option>
            <option [ngValue]="600">10 min</option>
          </select>
        </div>
        <div>
          <label>Generation speed</label>
          <select [(ngModel)]="qualityProfile">
            <option value="FAST">Fast (quick test, lower detail)</option>
            <option value="BALANCED">Balanced (lower GPU load)</option>
            <option value="QUALITY">Quality (16GB cinematic, slower)</option>
          </select>
        </div>
        <div>
          <label>Age</label>
          <select [(ngModel)]="targetAge">
            <option>3-5</option>
            <option>5-8</option>
            <option>8-12</option>
            <option>Teen</option>
            <option>General</option>
          </select>
        </div>
        <div>
          <label>Genre</label>
          <select [(ngModel)]="genre">
            <option *ngFor="let g of genres">{{ g }}</option>
          </select>
        </div>
        <div>
          <label>Tone</label>
          <select [(ngModel)]="tone">
            <option *ngFor="let t of tones">{{ t }}</option>
          </select>
        </div>
        <div class="style-field full-width">
          <div class="style-header">
            <div>
              <label>Image generation style</label>
              <span class="style-subtitle">Choose the visual language for the entire story. The selected style is carried into every scene and character reference.</span>
            </div>
            <span class="selected-style">{{ visualStyle }}</span>
          </div>
          <div class="style-grid" role="radiogroup" aria-label="Image generation style">
            <button type="button" class="style-card" *ngFor="let s of styles"
                    [class.selected]="visualStyle === s"
                    [attr.aria-checked]="visualStyle === s"
                    role="radio" (click)="visualStyle = s">
              <span class="style-icon">{{ styleIcon(s) }}</span>
              <span class="style-card-title">{{ styleName(s) }}</span>
              <span class="style-card-desc">{{ styleShortDescription(s) }}</span>
              <span class="style-check" *ngIf="visualStyle === s">✓</span>
            </button>
          </div>
          <div class="style-preview">
            <span class="preview-icon">{{ styleIcon(visualStyle) }}</span>
            <div><strong>{{ visualStyle }}</strong><p>{{ styleDescription(visualStyle) }}</p></div>
          </div>
        </div>
        <div>
          <label>Story language</label>
          <select [(ngModel)]="language">
            <option *ngFor="let l of languages" [value]="l.value">{{ l.label }}</option>
          </select>
          <span class="field-help">Narration, dialogue and story text are generated in this language. Image prompts stay in production-friendly English.</span>
        </div>
        <div>
          <label>Model</label>
          <select [(ngModel)]="selectedModel" *ngIf="ollamaModels.length; else noModels">
            <option *ngFor="let m of ollamaModels" [value]="m">{{ m }}</option>
          </select>
          <ng-template #noModels>
            <select disabled>
              <option>{{ modelsHealthy ? 'No models installed' : 'Ollama unreachable' }}</option>
            </select>
          </ng-template>
        </div>
      </div>

      <div class="characters" *ngIf="characters.length">
        <label>Include existing characters</label>
        <div class="char-chips">
          <button type="button" class="chip" *ngFor="let c of characters"
                  [class.selected]="selectedCharacterIds.has(c.id)"
                  (click)="toggleCharacter(c.id)">{{ c.name }}</button>
        </div>
      </div>

      <div class="actions">
        <span class="error" *ngIf="error">{{ error }}</span>
        <button class="btn btn-primary" (click)="submit()" [disabled]="loading || !prompt.trim()">
          {{ loading ? 'Writing draft…' : 'Create story draft' }}
        </button>
      </div>
    </div>
  `,
  styles: [`
    .learning-panel { margin: 0 0 1.2rem; padding: 1rem; border: 1px solid var(--border); border-radius: 16px; background: var(--surface-raised); } .learning-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:.75rem; }
    .learning-panel select { width:100%; }
    @media (max-width: 900px) { .learning-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } }
    @media (max-width: 560px) { .learning-grid { grid-template-columns:1fr; } }
    .trend-panel { margin: 0 0 1.2rem; padding: 1rem; border: 1px solid var(--border); border-radius: 16px; background: var(--surface-raised); }
    .trend-head { display:flex; justify-content:space-between; gap:1rem; align-items:flex-start; margin-bottom:.8rem; }
    .trend-head strong { display:block; font-size:.9rem; } .trend-head span { display:block; color:var(--muted); font-size:.72rem; margin-top:.25rem; max-width:75ch; }
    .live-chip { color:var(--accent)!important; border:1px solid var(--accent); border-radius:999px; padding:.3rem .55rem; white-space:nowrap; font-weight:700; font-size:.65rem!important; margin-top:0!important; }
    .preset-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:.65rem; }
    .preset-card { text-align:left; min-height:72px; padding:.7rem; border:1px solid var(--border); border-radius:12px; background:var(--surface); color:inherit; cursor:pointer; }
    .preset-card:hover { border-color:var(--accent); transform:translateY(-1px); }
    .preset-card strong,.preset-card span { display:block; } .preset-card span { color:var(--muted); font-size:.68rem; margin-top:.25rem; }
    .hero { display: flex; justify-content: space-between; align-items: flex-start; gap: 1.5rem; margin-bottom: 1.6rem; }
    .eyebrow { font-size: .72rem; font-weight: 800; letter-spacing: .16em; color: var(--accent); margin-bottom: .35rem; }
    .sub { color: var(--muted); margin-bottom: 0; max-width: 70ch; }
    .hero-badge { white-space: nowrap; border: 1px solid var(--border); background: var(--surface-raised); border-radius: 999px; padding: .55rem .85rem; color: var(--muted); font-size: .78rem; }
    textarea { width: 100%; resize: vertical; margin-bottom: 1.2rem; }
    .section-heading { margin: .25rem 0 1rem; border-top: 1px solid var(--border); padding-top: 1.2rem; }
    .section-heading h2 { margin: 0; font-size: 1rem; }
    .section-heading span { color: var(--muted); font-size: .78rem; }
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 1rem; margin-bottom: 1.2rem; }
    .grid select, .grid input { width: 100%; }
    .field-help { display: block; color: var(--muted); font-size: .68rem; margin-top: .3rem; line-height: 1.35; }
    .full-width { grid-column: 1 / -1; }
    .style-field { margin-top: .2rem; padding: 1rem; border: 1px solid var(--border); border-radius: 16px; background: var(--surface-raised); }
    .style-header { display: flex; justify-content: space-between; gap: 1rem; align-items: flex-start; margin-bottom: .85rem; }
    .style-header label { display: block; margin-bottom: .2rem; }
    .style-subtitle { display: block; color: var(--muted); font-size: .78rem; max-width: 70ch; }
    .selected-style { color: var(--accent); border: 1px solid var(--accent); border-radius: 999px; padding: .3rem .65rem; font-size: .72rem; font-weight: 700; white-space: nowrap; }
    .style-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: .65rem; }
    .style-card { position: relative; text-align: left; min-height: 105px; padding: .8rem; border: 1px solid var(--border); border-radius: 13px; background: var(--surface); color: inherit; cursor: pointer; transition: transform .12s ease, border-color .12s ease, box-shadow .12s ease; }
    .style-card:hover { transform: translateY(-1px); border-color: var(--accent); }
    .style-card.selected { border: 2px solid var(--accent); box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent) 12%, transparent); background: var(--surface-raised); }
    .style-icon { display: block; font-size: 1.45rem; margin-bottom: .35rem; }
    .style-card-title { display: block; font-weight: 750; font-size: .82rem; }
    .style-card-desc { display: block; color: var(--muted); font-size: .68rem; margin-top: .2rem; }
    .style-check { position: absolute; top: .45rem; right: .5rem; color: var(--accent); font-weight: 900; }
    .style-preview { display: flex; align-items: center; gap: .7rem; margin-top: .85rem; padding: .65rem .8rem; border-radius: 10px; background: var(--surface); border: 1px dashed var(--border); }
    .preview-icon { font-size: 1.35rem; }
    .style-preview p { margin: .15rem 0 0; color: var(--muted); font-size: .75rem; }
    .characters { margin-bottom: 1.4rem; }
    .char-chips { display: flex; flex-wrap: wrap; gap: 0.5rem; margin-top: 0.4rem; }
    .chip {
      border: 1px solid var(--border); background: var(--surface-raised); color: var(--muted);
      border-radius: 999px; padding: 0.4em 0.9em; font-size: 0.85rem;
    }
    .chip.selected { border-color: var(--accent); color: var(--accent); }
    .actions { display: flex; justify-content: flex-end; align-items: center; gap: 1rem; }
    .error { color: var(--danger); font-size: 0.85rem; }
    @media (max-width: 900px) { .style-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
    @media (max-width: 650px) { .hero { flex-direction: column; } .hero-badge { white-space: normal; } .style-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .style-header { flex-direction: column; } }
  `]
})
export class CreateStoryComponent implements OnInit {
  prompt = '';
  selectedProjectId = '__new__';
  newProjectName = '';
  selectedUniverseId = '';
  durationSeconds = 180;
  learningCategory = 'general';
  learningStyle = 'COMEDY';
  learningDifficulty = 'Beginner';
  learningDuration = 60;
  learningLanguageStyle = 'NATIVE';
  learningLanguageStyles = [
    { value: 'NATIVE', label: 'Native language' },
    { value: 'MIXED_TECH', label: 'Native + English technical terms' },
    { value: 'MIXED_MODERN', label: 'Natural bilingual / modern speech' }
  ];
  targetAge = '5-8';
  genre = 'Adventure';
  tone = 'Funny';
  visualStyle = '3D Realistic';
  qualityProfile: 'FAST' | 'BALANCED' | 'QUALITY' = 'QUALITY';
  language = 'English';

  languages = [
    { value: 'English', label: 'English (International)' },
    { value: 'Indian English', label: 'Indian English (English - India)' },
    { value: 'Kannada', label: 'ಕನ್ನಡ (Kannada)' },
    { value: 'Hindi', label: 'हिन्दी (Hindi)' },
    { value: 'Hinglish', label: 'Hinglish (Hindi + English)' },
    { value: 'Telugu', label: 'తెలుగు (Telugu)' },
    { value: 'Tamil', label: 'தமிழ் (Tamil)' },
    { value: 'Malayalam', label: 'മലയാളം (Malayalam)' },
    { value: 'Marathi', label: 'मराठी (Marathi)' },
    { value: 'Bengali', label: 'বাংলা (Bengali)' },
    { value: 'Gujarati', label: 'ગુજરાતી (Gujarati)' },
    { value: 'Odia', label: 'ଓଡ଼ିଆ (Odia)' },
    { value: 'Punjabi', label: 'ਪੰਜਾਬੀ (Punjabi)' },
    { value: 'Urdu', label: 'اردو (Urdu)' },
    { value: 'Auto-detect', label: 'Auto-detect from my idea' }
  ];

  learningCategories = [
    { value: 'general', label: '🌎 General Knowledge' },
    { value: 'programming', label: '👨‍💻 Programming Languages' },
    { value: 'technology', label: '🤖 Technology / AI' },
    { value: 'medical', label: '🩺 Medical / Biology' },
    { value: 'construction', label: '🏗️ Construction / Civil' },
    { value: 'law', label: '⚖️ Law / Constitution' },
    { value: 'upsc', label: '🇮🇳 UPSC / Civil Services' },
    { value: 'neet', label: '🧬 NEET' },
    { value: 'jee', label: '🧪 JEE' },
    { value: 'science', label: '🔬 Science' },
    { value: 'finance', label: '💰 Finance / Economics' },
    { value: 'custom', label: '✨ Any Custom Topic' }
  ];
  learningStyles = [
    { value: 'COMEDY', label: '😂 Comedy story' },
    { value: 'ABSURD', label: '🤯 Absurd situation' },
    { value: 'MYSTERY', label: '🕵️ Mystery' },
    { value: 'OFFICE', label: '🏢 Office / everyday life' },
    { value: 'ACTION', label: '💥 Action / cinematic' },
    { value: 'DRAMA', label: '🎭 Emotional drama' },
    { value: 'DETECTIVE', label: '🔎 Detective case' }
  ];

  genres = ['Adventure', 'Comedy', 'Fantasy', 'Educational', 'Mystery', 'Bedtime', 'Friendship', 'Moral', 'Science', 'Animals', 'Political Satire', 'Current Affairs Satire', 'News Parody', 'Social Commentary', 'Gen-Z / Meme Satire', 'Corporate Satire', 'Tech / AI Satire', 'Dark Comedy'];
  tones = ['Funny', 'Cute', 'Emotional', 'Exciting', 'Calm', 'Magical', 'Mysterious', 'Sharp Satire', 'Deadpan', 'Absurd', 'Darkly Funny', 'Witty'];

  satirePresets = [
    { name: 'Roast Everyone', genre: 'Political Satire', tone: 'Sharp Satire', prompt: 'Create a mature, balanced political satire that mocks the government, opposition/protesters, bureaucracy, media and ordinary online behaviour rather than taking one side. Use fictional characters and invented dialogue, with a strong absurd final punchline.' },
    { name: 'CJP Current Topic', genre: 'Current Affairs Satire', tone: 'Deadpan', prompt: 'Create a mature fictional satire inspired by the current Cockroach Janta Party (CJP) protests and the controversy around voter-roll revisions. Roast both the protest movement and the government/institutions with comparable comedic intensity. Keep real-world claims clearly framed as context/allegations, invent the characters and dialogue, and make it unmistakably satire rather than fake news.' },
    { name: 'Newsroom Roast', genre: 'News Parody', tone: 'Absurd', prompt: 'Create a fictional mock-news satire where a newsroom tries to cover a political controversy while every side contradicts itself. Roast politicians, activists, anchors, bureaucracy, influencers and viewers equally.' },
    { name: 'AI Politics', genre: 'Tech / AI Satire', tone: 'Witty', prompt: 'Create a modern Indian political satire where an AI assistant is asked to solve a political controversy but discovers that every side has trained it on a different version of reality. Roast everyone through absurd technology and bureaucracy.' }
  ];

  syncLearningDuration(): void {
    this.durationSeconds = this.learningDuration;
  }

  applyLearningCategory(): void {
    if (this.learningCategory === 'custom') return;
    const labels: Record<string,string> = { programming:'Programming / Software', technology:'Technology / AI', medical:'Medical / Biology', construction:'Construction / Civil', law:'Law / Constitution', upsc:'UPSC / Civil Services', neet:'NEET', jee:'JEE', science:'Science', finance:'Finance / Economics', general:'General Knowledge' };
    this.genre = 'Educational';
    this.durationSeconds = this.learningDuration;
    this.targetAge = 'General';
  }

  applySatirePreset(preset: { name: string; genre: string; tone: string; prompt: string }): void {
    this.genre = preset.genre;
    this.tone = preset.tone;
    this.prompt = preset.prompt;
    this.targetAge = 'General';
  }
  styles = [
    '3D Realistic', '3D Animated Feature', 'Cinematic Realistic', 'Anime',
    'Cartoon', 'Storybook', 'Watercolor', '2D Animation', 'Claymation',
    'Comic Book', 'Fantasy Illustration', 'Realistic'
  ];

  projects: Project[] = [];
  universes: Universe[] = [];
  characters: Character[] = [];
  selectedCharacterIds = new Set<string>();

  ollamaModels: string[] = [];
  selectedModel = '';
  modelsHealthy = true;

  loading = false;
  error = '';

  constructor(private api: ApiService, private router: Router) {}

  styleIcon(style: string): string {
    const icons: Record<string, string> = {
      '3D Realistic': '🧊', '3D Animated Feature': '🎬', 'Cinematic Realistic': '🎞️',
      'Anime': '🌸', 'Cartoon': '🎨', 'Storybook': '📖', 'Watercolor': '🖌️',
      '2D Animation': '✏️', 'Claymation': '🧱', 'Comic Book': '💥',
      'Fantasy Illustration': '🧙', 'Realistic': '📷'
    };
    return icons[style] || '✨';
  }

  styleName(style: string): string {
    return style.replace('3D Animated Feature', '3D Animated').replace('Cinematic Realistic', 'Cinematic');
  }

  styleShortDescription(style: string): string {
    const descriptions: Record<string, string> = {
      '3D Realistic': 'Lifelike 3D', '3D Animated Feature': 'Feature-film 3D', 'Cinematic Realistic': 'Film realism',
      'Anime': 'Expressive anime', 'Cartoon': 'Playful cartoon', 'Storybook': 'Illustrated pages',
      'Watercolor': 'Soft painted', '2D Animation': 'Hand-drawn', 'Claymation': 'Stop-motion',
      'Comic Book': 'Graphic comic', 'Fantasy Illustration': 'Painted fantasy', 'Realistic': 'Natural photo look'
    };
    return descriptions[style] || '';
  }

  styleLabel(style: string): string {
    const labels: Record<string, string> = {
      '3D Realistic': '3D Realistic — cinematic, lifelike 3D',
      '3D Animated Feature': '3D Animated Feature — polished family animation',
      'Cinematic Realistic': 'Cinematic Realistic — film-like realism',
      'Anime': 'Anime — expressive 2D anime',
      'Cartoon': 'Cartoon — colorful stylized animation',
      'Storybook': 'Storybook — illustrated storybook',
      'Watercolor': 'Watercolor — soft painted illustration',
      '2D Animation': '2D Animation — clean hand-drawn look',
      'Claymation': 'Claymation — tactile stop-motion look',
      'Comic Book': 'Comic Book — graphic ink and color',
      'Fantasy Illustration': 'Fantasy Illustration — detailed painted fantasy',
      'Realistic': 'Realistic — natural photographic look'
    };
    return labels[style] || style;
  }

  styleDescription(style: string): string {
    const descriptions: Record<string, string> = {
      '3D Realistic': 'Lifelike 3D characters, believable materials, cinematic lighting and detailed environments.',
      '3D Animated Feature': 'Stylized 3D with expressive characters, soft illumination and feature-film composition.',
      'Cinematic Realistic': 'Natural proportions, realistic materials, cinematic lenses and believable lighting.',
      'Anime': 'Consistent anime character design, expressive faces, clean linework and cel-style shading.',
      'Cartoon': 'Bright readable shapes, expressive characters and playful animated backgrounds.',
      'Storybook': 'Warm illustrated pages with painterly textures and gentle compositions.',
      'Watercolor': 'Soft watercolor washes, paper texture and storybook-like color harmony.',
      '2D Animation': 'Clean hand-drawn animation look with controlled shapes and layered backgrounds.',
      'Claymation': 'Physical clay/stop-motion appearance with tactile surfaces and studio lighting.',
      'Comic Book': 'Bold ink outlines, graphic composition, controlled color blocks and action framing.',
      'Fantasy Illustration': 'Detailed painted environments, magical atmosphere and cinematic fantasy lighting.',
      'Realistic': 'Natural-looking people, animals and environments with photographic lighting.'
    };
    return descriptions[style] || '';
  }

  ngOnInit(): void {
    this.api.listProjects().subscribe(p => this.projects = p);
    this.api.listOllamaModels().subscribe({
      next: res => {
        this.modelsHealthy = res.healthy;
        this.ollamaModels = res.models;
        this.selectedModel = res.models.includes(res.defaultModel) ? res.defaultModel : (res.models[0] || '');
      },
      error: () => { this.modelsHealthy = false; }
    });
  }

  onProjectChange(): void {
    this.universes = [];
    this.characters = [];
    if (this.selectedProjectId && this.selectedProjectId !== '__new__') {
      this.api.listUniverses(this.selectedProjectId).subscribe(u => this.universes = u);
    }
  }

  onUniverseChange(): void {
    this.characters = [];
    if (this.selectedUniverseId) {
      this.api.listCharacters(this.selectedUniverseId).subscribe(c => this.characters = c);
    }
  }

  toggleCharacter(id: string): void {
    this.selectedCharacterIds.has(id) ? this.selectedCharacterIds.delete(id) : this.selectedCharacterIds.add(id);
  }

  submit(): void {
    this.error = '';
    this.loading = true;

    const proceed = (projectId: string) => {
      this.api.createDraft({
        projectId,
        universeId: this.selectedUniverseId || undefined,
        prompt: this.learningCategory !== 'custom' ? `${this.prompt}

[ENTERTAINMENT_LEARNING] category=${this.learningCategory}; style=${this.learningStyle}; difficulty=${this.learningDifficulty}; requestedDuration=${this.learningDuration}s; languageStyle=${this.learningLanguageStyle}` : this.prompt,
        durationSeconds: this.durationSeconds,
        targetAge: this.targetAge,
        genre: this.genre,
        tone: this.tone,
        visualStyle: this.visualStyle,
        language: this.language,
        characterIds: Array.from(this.selectedCharacterIds),
        ollamaModel: this.selectedModel || undefined,
        qualityProfile: this.qualityProfile
      }).subscribe({
        next: draft => this.router.navigate(['/episodes', draft.episodeId, 'approve']),
        error: err => {
          this.loading = false;
          this.error = err?.error?.message || 'Could not create the draft. Is Ollama running?';
        }
      });
    };

    if (this.selectedProjectId === '__new__') {
      const name = this.newProjectName.trim() || this.prompt.slice(0, 40);
      this.api.createProject(name).subscribe({
        next: project => proceed(project.id),
        error: () => { this.loading = false; this.error = 'Could not create project.'; }
      });
    } else {
      proceed(this.selectedProjectId);
    }
  }
}
