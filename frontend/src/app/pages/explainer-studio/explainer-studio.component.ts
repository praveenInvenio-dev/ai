import { AfterViewChecked, Component, ElementRef, OnDestroy, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription, interval } from 'rxjs';
import { ExplainerStudioService, ExHealth, ExJob, ExLog, ExMeta, ExRequest, ExState } from './explainer-studio.service';

const THEMES: Record<string, { label: string; bg: string; a: string; b: string; fg: string }> = {
  dracula: { label: 'Dracula', bg: '#282a36', a: '#bd93f9', b: '#ff79c6', fg: '#f8f8f2' },
  cyberpunk: { label: 'Cyberpunk', bg: '#1a0536', a: '#05d9e8', b: '#ff2a6d', fg: '#d1f7ff' },
  academic: { label: 'Academic', bg: '#ffffff', a: '#0b3d91', b: '#b00020', fg: '#0a0a0a' },
};
const EXAMPLES = [
  { topic: 'Java multithreading race conditions', domain: 'cs' },
  { topic: 'Why a 45° launch angle gives the maximum range', domain: 'physics_jee' },
  { topic: 'Why methane is tetrahedral', domain: 'chemistry_neet' },
  { topic: 'Volume of a cylinder, visually', domain: 'math_jee' },
  { topic: 'How UPI moves money in two seconds', domain: 'general' },
  { topic: 'UPI payment kaise kaam karta hai', domain: 'general', language: 'hinglish' },
  { topic: 'Photosynthesis — explained in Hindi', domain: 'biology_neet', language: 'hi' },
];
const STAGES = [
  { id: 'script', label: 'Script' }, { id: 'voice', label: 'Voice' }, { id: 'timeline', label: 'Sync' },
  { id: 'render', label: 'Render' }, { id: 'assemble', label: 'Master' },
];
const TAGS: Record<string, string> = { llm: 'ollama', tts: 'voice', render: 'remotion', ffmpeg: 'ffmpeg', warn: 'warn', error: 'error', info: 'job' };

@Component({
  selector: 'app-explainer-studio',
  standalone: true,
  imports: [FormsModule, DatePipe],
  templateUrl: './explainer-studio.component.html',
  styleUrl: './explainer-studio.component.scss',
})
export class ExplainerStudioComponent implements OnInit, OnDestroy, AfterViewChecked {
  private api = inject(ExplainerStudioService);
  @ViewChild('logBox') logBox?: ElementRef<HTMLElement>;

  readonly examples = EXAMPLES;
  readonly stages = STAGES;

  meta = signal<ExMeta | null>(null);
  health = signal<ExHealth | null>(null);
  history = signal<ExJob[]>([]);
  activeId = signal<string | null>(null);
  logs = signal<ExLog[]>([]);
  state = signal<ExState | null>(null);
  error = signal('');
  portrait = signal(true);
  copied = signal(false);

  topic = '';
  domain = 'general';
  platform = 'reel';
  aspect = '9:16';
  fps = 30;
  theme = 'dracula';
  voice = '';
  language = 'en';
  model = '';

  busy = computed(() => ['queued', 'running'].includes(this.state()?.status ?? ''));
  models = computed(() => this.health()?.ollama?.models ?? []);
  ollamaOk = computed(() => !!this.health()?.ollama?.reachable);
  offline = computed(() => this.health() === null && this.error() !== '');

  private sse?: Subscription;
  private poll?: Subscription;
  private lastLogCount = 0;

  ngOnInit() {
    this.api.meta().subscribe({
      next: (m) => {
        this.meta.set(m);
        this.voice = m.defaultVoice || m.voices.find((v) => v.available && v.lang === 'en')?.id || m.voices[0]?.id || '';
        this.model = m.model;
      },
      error: () => this.error.set('Explainer Studio service is not reachable. Check: docker compose ps explainer-studio'),
    });
    this.refresh();
    this.poll = interval(5000).subscribe(() => this.refresh());
  }
  ngOnDestroy() { this.sse?.unsubscribe(); this.poll?.unsubscribe(); }
  ngAfterViewChecked() {
    const el = this.logBox?.nativeElement;
    if (el && this.logs().length !== this.lastLogCount) { this.lastLogCount = this.logs().length; el.scrollTop = el.scrollHeight; }
  }

  tp(id: string) { return THEMES[id] ?? THEMES['dracula']; }
  /** Voices that speak the selected language, available ones first. */
  voicesFor() {
    const vl = this.meta()?.languages.find((l) => l.id === this.language)?.voiceLang || this.language;
    const list = (this.meta()?.voices ?? []).filter((v) => v.lang === vl);
    return [...list.filter((v) => v.available), ...list.filter((v) => !v.available)];
  }
  setLanguage(id: string) {
    this.language = id;
    const def = this.meta()?.languages.find((l) => l.id === id)?.defaultVoice;
    const avail = this.voicesFor().find((v) => v.available);
    this.voice = (def && this.voicesFor().some((v) => v.id === def && v.available) ? def : avail?.id) ?? def ?? '';
  }
  /** Voices of the selected language grouped by engine, most natural first. */
  voiceGroups() {
    const order: [string, string][] = [['indicf5', 'IndicF5 — most natural (tts-indic)'], ['edge', 'Edge neural — needs internet'], ['indicspeak', 'Indic-Speak'],
      ['kokoro', 'Kokoro'], ['chatterbox', 'Chatterbox'], ['profile', 'My voices (Voice Lab)'], ['piper', 'Piper — offline, basic']];
    const list = this.voicesFor();
    const groups = order.map(([k, label]) => ({ label, voices: list.filter((v) => v.engine === k) }));
    const known = new Set(order.map(([k]) => k));
    groups.push({ label: 'Other', voices: list.filter((v) => !known.has(v.engine)) });
    return groups.filter((g) => g.voices.length);
  }
  studioOk(name: string) { return this.health()?.tts?.studio?.[name]; }
  noVoice() { return this.language !== 'en' && !this.voicesFor().some((v) => v.available); }
  langLabel(id?: string) { const l = this.meta()?.languages.find((x) => x.id === (id || 'en')); return l ? (l.label === l.native ? l.label : `${l.label} · ${l.native}`) : 'English'; }
  serviceOk(name: string) { return this.health()?.tts?.services?.[name]; }
  short(l: string) { return l.replace(/\s*\(.*\)/, ''); }
  dur(s: number) { return s < 120 ? `${s}s` : `${Math.round(s / 60)} min`; }
  tag(l: ExLog) { return TAGS[l.level] ?? 'job'; }
  pick(e: { topic: string; domain: string; language?: string }) { this.topic = e.topic; this.domain = e.domain; if (e.language) this.setLanguage(e.language); }
  setPlatform(id: string, aspect: string) { this.platform = id; this.aspect = aspect; }

  stageClass(id: string) {
    const s = this.state();
    const i = STAGES.findIndex((x) => x.id === id), c = STAGES.findIndex((x) => x.id === s?.stage);
    if (s?.status === 'done' || c > i) return 'past';
    return c === i ? 'now' : '';
  }
  statusTitle() {
    const s = this.state();
    if (!s) return 'Pipeline';
    if (s.status === 'queued') return `Queued${s.queuePosition ? ` · position ${s.queuePosition}` : ''}`;
    return ({ running: 'Running', done: 'Finished', failed: 'Failed', cancelled: 'Cancelled' } as Record<string, string>)[s.status] ?? s.status;
  }

  private refresh() {
    this.api.health().subscribe({ next: (h) => { this.health.set(h); if (this.error().startsWith('Explainer Studio service')) this.error.set(''); }, error: () => this.health.set(null) });
    this.api.list().subscribe({ next: (l) => this.history.set(l.slice(0, 8)), error: () => {} });
  }

  generate() {
    if (this.busy() || this.topic.trim().length < 3) return;
    this.error.set('');
    const req: ExRequest = { topic: this.topic.trim(), domain: this.domain, platform: this.platform, aspect: this.aspect, fps: this.fps, theme: this.theme, voice: this.voice, language: this.language, ...(this.model ? { model: this.model } : {}) };
    this.api.create(req).subscribe({
      next: ({ id }) => { this.portrait.set(req.aspect === '9:16'); this.watch(id); },
      error: (e) => this.error.set(e.error?.error || 'Could not start the job.'),
    });
  }
  rerender(patch: Partial<ExRequest>) {
    const id = this.activeId();
    if (!id) return;
    this.api.rerender(id, patch).subscribe({
      next: ({ id: nid }) => { if (patch.aspect) this.portrait.set(patch.aspect === '9:16'); this.watch(nid); },
      error: (e) => this.error.set(e.error?.error || 'Re-render failed to start.'),
    });
  }
  open(j: ExJob) { this.portrait.set(j.request.aspect === '9:16'); this.watch(j.id); }
  remove(j: ExJob) {
    this.api.remove(j.id).subscribe(() => {
      if (this.activeId() === j.id) { this.activeId.set(null); this.logs.set([]); this.state.set(null); this.sse?.unsubscribe(); }
      this.refresh();
    });
  }
  copy(text: string) {
    navigator.clipboard?.writeText(text).then(() => { this.copied.set(true); setTimeout(() => this.copied.set(false), 1400); });
  }

  private watch(id: string) {
    this.sse?.unsubscribe();
    this.activeId.set(id);
    this.logs.set([]);
    this.state.set(null);
    this.sse = this.api.events(id).subscribe({
      next: (e) => {
        if (e.type === 'log') this.logs.update((l) => (l.length > 1500 ? [...l.slice(-1000), e] : [...l, e]));
        else { this.state.set(e); if (['done', 'failed', 'cancelled'].includes(e.status)) this.refresh(); }
      },
    });
    this.refresh();
  }
}
