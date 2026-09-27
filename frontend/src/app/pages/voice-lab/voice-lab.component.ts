import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService, Voice } from '../../services/api.service';
import { VoiceProfile } from '../../models/models';

/** One engine's worth of voices, as rendered by a section of the page. */
interface VoiceGroup {
  key: string;
  title: string;
  badge: string;
  chipClass: string;
  note: string;
  emptyHint: string;
  voices: Voice[];
}

/**
 * Voice lab: type text, pick a voice, hear it.
 *
 * Voices are downloaded on demand rather than baked into the TTS image - only
 * the default ships with it, and the rest install into the tts-data volume the
 * first time they are used. A 110 MB download on first play is why the button
 * reports "Installing voice..." separately from "Speaking...": without that
 * distinction the first click just looks like a hang.
 */
@Component({
  selector: 'app-voice-lab',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <header class="page-head">
      <h1>Voice lab</h1>
      <p class="muted">
        Try a narration voice before committing an episode to it. Voices download on first use
        and are then cached, so the first play of a new voice takes longer than later ones.
      </p>
    </header>

    <section class="panel">
      <label for="sample">Sample text</label>
      <textarea id="sample" rows="4" [(ngModel)]="text"
                placeholder="Type the line you want to hear..."></textarea>

      <div class="controls">
        <div class="control">
          <label for="speed">Speed &mdash; {{ speed.toFixed(2) }}x</label>
          <input id="speed" type="range" min="0.6" max="1.4" step="0.05" [(ngModel)]="speed">
        </div>
        <div class="control">
          <label for="pitch">Pitch &mdash; {{ pitch.toFixed(2) }}x</label>
          <input id="pitch" type="range" min="0.8" max="1.6" step="0.05" [(ngModel)]="pitch">
        </div>
        <button class="btn" (click)="speak()" [disabled]="busy || !text.trim() || !selectedVoice">
          {{ busyLabel || 'Play sample' }}
        </button>
      </div>

      <div class="presets">
        <span class="muted">Character presets:</span>
        <button type="button" class="pill" *ngFor="let p of presets"
                [class.selected]="speed === p.speed && pitch === p.pitch"
                (click)="applyPreset(p)">{{ p.label }}</button>
      </div>

      <audio #player controls *ngIf="audioUrl" [src]="audioUrl" autoplay></audio>
      <p class="error" *ngIf="error">{{ error }}</p>
    </section>

    <p class="muted" *ngIf="loading">Loading voices...</p>

    <section class="panel" *ngFor="let group of groups">
      <div class="group-head">
        <h2>{{ group.title }}</h2>
        <span class="chip" [ngClass]="group.chipClass">{{ group.badge }}</span>
      </div>
      <p class="muted group-note">{{ group.note }}</p>

      <div class="voice-grid" *ngIf="group.voices.length">
        <button type="button" class="voice"
                *ngFor="let v of group.voices"
                [class.selected]="v.id === selectedVoice"
                (click)="selectedVoice = v.id">
          <div class="voice-head">
            <strong>{{ v.label }}</strong>
            <span class="chip" *ngIf="v.quality === 'high'">high quality</span>
            <span class="chip chip-muted" *ngIf="!v.installed">not downloaded</span>
          </div>
          <div class="voice-meta">{{ v.gender }}<span *ngIf="v.accent"> &middot; {{ v.accent }}</span></div>
          <div class="voice-notes">{{ v.notes }}</div>
        </button>
      </div>

      <p class="empty" *ngIf="!group.voices.length && !loading">{{ group.emptyHint }}</p>
    </section>

    <section class="panel">
      <div class="group-head">
        <h2>Voice library</h2>
        <span class="chip chip-teal">reusable across stories</span>
      </div>
      <p class="muted group-note">
        Voices you record or upload here - each one is usable for any character in any story,
        not just where it was created.
      </p>

      <div class="voice-grid" *ngIf="voiceProfiles.length">
        <div class="voice-profile-card" *ngFor="let vp of voiceProfiles">
          <div class="voice-head">
            <ng-container *ngIf="renamingId !== vp.id">
              <strong>{{ vp.name }}</strong>
              <span class="chip chip-amber">{{ vp.provider }}</span>
            </ng-container>
            <ng-container *ngIf="renamingId === vp.id">
              <input class="rename-input" [(ngModel)]="renameValue" (keyup.enter)="confirmRename(vp)">
            </ng-container>
          </div>
          <div class="voice-meta" *ngIf="vp.durationSeconds">
            {{ vp.durationSeconds.toFixed(1) }}s reference clip
            <span *ngIf="vp.language"> &middot; {{ vp.language }}</span>
          </div>
          <div class="voice-notes" *ngIf="vp.personality">{{ vp.personality }}</div>

          <audio *ngIf="testAudioUrl[vp.id]" controls [src]="testAudioUrl[vp.id]"></audio>

          <div class="vp-actions">
            <button type="button" class="btn-ghost" (click)="testVoice(vp)" [disabled]="testing[vp.id]">
              {{ testing[vp.id] ? 'Generating...' : 'Test voice' }}
            </button>
            <button type="button" class="btn-ghost" *ngIf="renamingId !== vp.id" (click)="startRename(vp)">Rename</button>
            <button type="button" class="btn-ghost" *ngIf="renamingId === vp.id" (click)="confirmRename(vp)">Save name</button>
            <button type="button" class="btn-ghost" (click)="deleteVoice(vp)">Delete</button>
          </div>
        </div>
      </div>
      <p class="empty" *ngIf="!voiceProfiles.length && !loadingProfiles">
        No voices in the library yet - record or upload one below.
      </p>
    </section>

    <section class="panel">
      <div class="group-head">
        <h2>Add a voice</h2>
      </div>
      <div class="presets">
        <button type="button" class="pill" [class.selected]="addMode === 'record'" (click)="setAddMode('record')">Record your voice</button>
        <button type="button" class="pill" [class.selected]="addMode === 'upload'" (click)="setAddMode('upload')">Upload voice sample</button>
      </div>

      <div class="field">
        <label for="vp-name">Name</label>
        <input id="vp-name" [(ngModel)]="newVoiceName" placeholder="e.g. Bunny Cute Voice">
      </div>
      <div class="field">
        <label for="vp-lang">Language (optional)</label>
        <input id="vp-lang" [(ngModel)]="newVoiceLanguage" placeholder="e.g. en-US">
      </div>
      <div class="field">
        <label for="vp-personality">Personality/style (optional)</label>
        <input id="vp-personality" [(ngModel)]="newVoicePersonality" placeholder="e.g. warm, curious, gentle">
      </div>

      <div class="field" *ngIf="addMode === 'record'">
        <button type="button" class="btn" (click)="recording ? stopRecording() : startRecording()">
          {{ recording ? 'Stop recording (' + recordSeconds + 's)' : 'Start recording' }}
        </button>
        <p class="muted">5-15 seconds, quiet room, natural speaking voice.</p>
      </div>
      <div class="field" *ngIf="addMode === 'upload'">
        <input type="file" accept="audio/wav,audio/mpeg,audio/mp3" (change)="onFileSelected($event)">
      </div>

      <div *ngIf="pendingAudioUrl" class="field">
        <label>Preview</label>
        <audio controls [src]="pendingAudioUrl"></audio>
      </div>

      <p class="muted" *ngIf="validating">Checking audio quality...</p>
      <ul class="warnings" *ngIf="validationWarnings.length">
        <li *ngFor="let w of validationWarnings">{{ w }}</li>
      </ul>
      <p class="error" *ngIf="addError">{{ addError }}</p>

      <button type="button" class="btn btn-primary" (click)="saveVoice()"
              [disabled]="!pendingAudioBlob || !newVoiceName.trim() || saving || validating">
        {{ saving ? 'Saving...' : 'Save voice' }}
      </button>
    </section>
  `,
  styles: [`
    .page-head { margin-bottom: 1.25rem; }
    .muted { color: var(--muted); font-size: 0.9rem; }
    .panel {
      background: var(--surface-raised); border-radius: var(--radius);
      padding: 1.1rem; margin-bottom: 1.1rem;
    }
    label { display: block; font-size: 0.85rem; margin-bottom: 0.35rem; }
    textarea {
      width: 100%; background: var(--surface); color: inherit;
      border: 1px solid var(--border); border-radius: 8px;
      padding: 0.6rem; font: inherit; resize: vertical;
    }
    .controls { display: flex; align-items: flex-end; gap: 1rem; margin-top: 0.8rem; flex-wrap: wrap; }
    .control { flex: 1; min-width: 200px; }
    .control input { width: 100%; }
    .btn {
      background: var(--accent); color: var(--accent-ink); border: 0;
      border-radius: 8px; padding: 0.6rem 1.1rem; font-weight: 600; cursor: pointer;
    }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
    audio { width: 100%; margin-top: 0.9rem; }
    .error { color: #ff8080; margin-top: 0.6rem; font-size: 0.9rem; }
    h2 { font-size: 1rem; margin: 0 0 0.75rem; }
    .voice-grid {
      display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 0.7rem;
    }
    .voice {
      text-align: left; cursor: pointer; padding: 0.75rem;
      background: var(--surface); color: inherit;
      border: 1px solid var(--border); border-radius: var(--radius);
    }
    .voice:hover { border-color: var(--accent); }
    .voice.selected { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent) inset; }
    .voice-head { display: flex; align-items: center; gap: 0.4rem; flex-wrap: wrap; }
    .voice-meta { font-size: 0.78rem; color: var(--muted); margin-top: 0.2rem; }
    .voice-notes { font-size: 0.8rem; margin-top: 0.4rem; }
    .chip {
      font-size: 0.65rem; text-transform: uppercase; letter-spacing: 0.04em;
      padding: 0.1rem 0.4rem; border-radius: 999px;
      background: rgba(238,162,59,0.18); color: var(--accent);
    }
    .chip-muted { background: rgba(255,255,255,0.07); color: var(--muted); }
    .presets { display: flex; align-items: center; gap: 0.4rem; margin-top: 0.8rem; flex-wrap: wrap; }
    .pill {
      cursor: pointer; font-size: 0.8rem; padding: 0.28rem 0.7rem; border-radius: 999px;
      background: var(--surface); color: inherit;
      border: 1px solid var(--border);
    }
    .pill:hover { border-color: var(--accent); }
    .pill.selected { border-color: var(--accent); color: var(--accent); }
    .group-head { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.2rem; }
    .group-head h2 { margin: 0; }
    .group-note { margin: 0 0 0.85rem; max-width: 62ch; }
    .chip-teal { background: rgba(73,201,189,0.16); color: var(--teal); }
    .chip-amber { background: rgba(251,191,36,0.16); color: #fbbf24; }
    .empty {
      font-size: 0.85rem; color: var(--muted); margin: 0;
      padding: 0.7rem 0.85rem; border-radius: 8px;
      background: var(--surface);
      border: 1px dashed var(--border);
    }
    .voice-profile-card {
      padding: 0.75rem; background: var(--surface); border: 1px solid var(--border);
      border-radius: var(--radius); display: flex; flex-direction: column; gap: 0.4rem;
    }
    .vp-actions { display: flex; gap: 0.4rem; flex-wrap: wrap; margin-top: 0.3rem; }
    .btn-ghost {
      font-size: 0.78rem; padding: 0.3rem 0.6rem; border-radius: 8px;
      background: transparent; color: var(--accent); border: 1px solid var(--border); cursor: pointer;
    }
    .btn-ghost:hover { border-color: var(--accent); }
    .btn-ghost:disabled { opacity: 0.5; cursor: not-allowed; }
    .rename-input { font: inherit; padding: 0.2rem 0.4rem; border-radius: 6px; border: 1px solid var(--border); background: var(--surface-raised); color: inherit; }
    .field { margin-top: 0.8rem; }
    .field input[type="text"], .field input:not([type]) {
      width: 100%; background: var(--surface); color: inherit;
      border: 1px solid var(--border); border-radius: 8px; padding: 0.5rem;
    }
    .warnings { margin: 0.6rem 0 0; padding-left: 1.2rem; font-size: 0.82rem; color: #fbbf24; }
    .warnings li { margin-bottom: 0.2rem; }
  `]
})
export class VoiceLabComponent implements OnInit, OnDestroy {
  voices: Voice[] = [];
  selectedVoice = '';
  text = 'Bobo and Mimi stood before the tiny cave, where golden footprints led into the glowing dark.';
  speed = 1.0;
  pitch = 1.0;

  /**
   * Piper has no child voice in any language, so a kid voice is made by raising
   * pitch while holding duration (ffmpeg does this server-side). Duration has to
   * stay put because scene lengths are derived from the narration audio - a
   * faster-and-higher voice would silently desync the video timing.
   */
  presets = [
    { label: 'Narrator', speed: 1.0, pitch: 1.0 },
    { label: 'Young girl', speed: 1.05, pitch: 1.35 },
    { label: 'Young boy', speed: 1.05, pitch: 1.25 },
    { label: 'Small creature', speed: 1.1, pitch: 1.5 },
    { label: 'Bedtime', speed: 0.85, pitch: 0.95 }
  ];
  loading = true;
  groups: VoiceGroup[] = [];
  busy = false;
  busyLabel = '';
  error = '';
  audioUrl?: string;

  // --- Voice Library ---
  voiceProfiles: VoiceProfile[] = [];
  loadingProfiles = true;
  testing: Record<string, boolean> = {};
  testAudioUrl: Record<string, string> = {};
  renamingId = '';
  renameValue = '';

  addMode: 'record' | 'upload' = 'record';
  newVoiceName = '';
  newVoiceLanguage = '';
  newVoicePersonality = '';
  pendingAudioBlob?: Blob;
  pendingAudioUrl?: string;
  validating = false;
  validationWarnings: string[] = [];
  saving = false;
  addError = '';

  recording = false;
  recordSeconds = 0;
  private mediaRecorder?: MediaRecorder;
  private recordedChunks: Blob[] = [];
  private recordTimer?: ReturnType<typeof setInterval>;

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    this.api.listVoices().subscribe({
      next: res => {
        this.voices = res.voices;
        this.selectedVoice = res.defaultVoice || (res.voices[0]?.id ?? '');
        this.buildGroups();
        this.loading = false;
      },
      error: () => {
        this.error = 'Could not reach the TTS service. Is the tts container running?';
        this.loading = false;
      }
    });
    this.loadVoiceProfiles();
  }

  private loadVoiceProfiles(): void {
    this.api.listVoiceProfiles().subscribe({
      next: profiles => { this.voiceProfiles = profiles; this.loadingProfiles = false; },
      error: () => { this.loadingProfiles = false; } // library just stays empty - not fatal
    });
  }

  speak(): void {
    const voice = this.voices.find(v => v.id === this.selectedVoice);
    this.busy = true;
    this.error = '';
    // Downloading a voice takes far longer than synthesising a line, so say which
    // one is happening. Otherwise the first use of any voice looks like a freeze.
    this.busyLabel = voice && !voice.installed ? 'Installing voice...' : 'Speaking...';

    this.api.previewVoice(this.text, this.selectedVoice, this.speed, this.pitch).subscribe({
      next: blob => {
        this.revokeAudio();
        this.audioUrl = URL.createObjectURL(blob);
        if (voice) { voice.installed = true; }
        this.busy = false;
        this.busyLabel = '';
      },
      error: () => {
        this.error = 'Synthesis failed. Check `docker compose logs tts` for the reason.';
        this.busy = false;
        this.busyLabel = '';
      }
    });
  }

  /**
   * Voices are grouped by engine rather than shown as one flat list, because the
   * engines differ in ways that matter before you pick one: whether the machine
   * needs to be online, whether the audio is free to publish, and how long
   * synthesis takes. Two engines also both offer Hindi voices, so a flat list
   * gives no way to tell Pratham (offline Piper) from Swara (cloud Edge).
   *
   * The engine field is inferred from the id prefix when the server omits it,
   * so an older TTS container that predates the field still groups correctly
   * rather than dumping everything into "other".
   */
  private buildGroups(): void {
    const engineOf = (v: Voice): string => {
      if (v.engine) { return v.engine; }
      if (v.id.startsWith('edge:')) { return 'edge'; }
      if (v.id.startsWith('indic:')) { return 'indicf5'; }
      return 'piper';
    };

    this.groups = [
      {
        key: 'piper',
        title: 'Piper',
        badge: 'offline',
        chipClass: 'chip-teal',
        note: 'Runs entirely on your machine. Free to use and publish. '
            + 'No Kannada and no Indian-accented English.',
        emptyHint: 'No Piper voices found - is the tts container running?',
        voices: []
      },
      {
        key: 'edge',
        title: 'Microsoft Edge',
        badge: 'cloud',
        chipClass: 'chip-amber',
        note: 'Free neural voices, no key needed, but the container must reach the '
            + 'internet. This is an unofficial use of a Microsoft browser endpoint, '
            + 'so it can stop working without warning - check the terms before '
            + 'publishing anything narrated with it.',
        emptyHint: 'No Edge voices available.',
        voices: []
      },
      {
        key: 'indicf5',
        title: 'IndicF5',
        badge: 'offline · MIT',
        chipClass: 'chip-teal',
        note: '11 Indian languages including Kannada, at near-human quality, and '
            + 'MIT-licensed so the audio is safe to publish. No English. Each voice '
            + 'is a reference clip you supply, and synthesis is slow on CPU.',
        emptyHint: 'Not running. Start it with "docker compose --profile indic up -d '
                 + 'tts-indic", set HF_TOKEN in .env, and add a reference clip '
                 + '(name.wav plus name.txt with its exact transcript) to '
                 + 'tts-indic/prompts. See the README in that folder.',
        voices: []
      },
      {
        key: 'other',
        title: 'Other',
        badge: 'custom',
        chipClass: 'chip-muted',
        note: 'Voices installed by hand into the tts-data volume.',
        emptyHint: '',
        voices: []
      }
    ];

    for (const voice of this.voices) {
      const engine = engineOf(voice);
      const group = this.groups.find(g => g.key === engine) ?? this.groups[3];
      group.voices.push(voice);
    }
    // Hide "Other" when empty; keep the three real engines visible even with no
    // voices, so IndicF5's setup instructions are discoverable before it is up.
    this.groups = this.groups.filter(g => g.key !== 'other' || g.voices.length > 0);
  }

  applyPreset(preset: { speed: number; pitch: number }): void {
    this.speed = preset.speed;
    this.pitch = preset.pitch;
  }

  setAddMode(mode: 'record' | 'upload'): void {
    this.addMode = mode;
    this.clearPendingAudio();
  }

  startRecording(): void {
    this.addError = '';
    this.clearPendingAudio();
    navigator.mediaDevices.getUserMedia({ audio: true }).then(stream => {
      this.recordedChunks = [];
      this.mediaRecorder = new MediaRecorder(stream);
      this.mediaRecorder.ondataavailable = e => { if (e.data.size > 0) { this.recordedChunks.push(e.data); } };
      this.mediaRecorder.onstop = () => {
        stream.getTracks().forEach(t => t.stop());
        const blob = new Blob(this.recordedChunks, { type: 'audio/webm' });
        this.setPendingAudio(blob, 'recording.webm');
      };
      this.mediaRecorder.start();
      this.recording = true;
      this.recordSeconds = 0;
      this.recordTimer = setInterval(() => { this.recordSeconds++; }, 1000);
    }).catch(() => {
      this.addError = 'Could not access the microphone - check your browser permissions.';
    });
  }

  stopRecording(): void {
    this.mediaRecorder?.stop();
    this.recording = false;
    if (this.recordTimer) { clearInterval(this.recordTimer); this.recordTimer = undefined; }
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) { return; }
    this.addError = '';
    this.setPendingAudio(file, file.name);
  }

  /** Shared by both record and upload paths - validates immediately so
   *  warnings (clipping, background noise, borderline duration) show up at
   *  Preview, before the user commits to Save. */
  private setPendingAudio(blob: Blob, filename: string): void {
    this.clearPendingAudio();
    this.pendingAudioBlob = blob;
    this.pendingAudioUrl = URL.createObjectURL(blob);
    this.validating = true;
    this.validationWarnings = [];
    this.api.validateVoiceAudio(blob, filename).subscribe({
      next: result => {
        this.validating = false;
        if (!result.ok) {
          this.addError = result.error || 'That clip is not usable.';
          this.clearPendingAudio();
        } else {
          this.validationWarnings = result.warnings;
        }
      },
      error: err => {
        this.validating = false;
        this.addError = err?.error?.message || 'Could not validate that audio.';
      }
    });
  }

  private clearPendingAudio(): void {
    if (this.pendingAudioUrl) { URL.revokeObjectURL(this.pendingAudioUrl); }
    this.pendingAudioBlob = undefined;
    this.pendingAudioUrl = undefined;
    this.validationWarnings = [];
    this.addError = '';
  }

  saveVoice(): void {
    if (!this.pendingAudioBlob || !this.newVoiceName.trim()) { return; }
    this.saving = true;
    this.addError = '';
    const filename = this.addMode === 'record' ? 'recording.webm' : 'upload.audio';
    this.api.createClonedVoiceProfile(
      this.newVoiceName.trim(), this.newVoiceLanguage.trim(), 'chatterbox',
      this.newVoicePersonality.trim(), this.pendingAudioBlob, filename
    ).subscribe({
      next: profile => {
        this.voiceProfiles = [...this.voiceProfiles, profile];
        this.saving = false;
        this.newVoiceName = '';
        this.newVoiceLanguage = '';
        this.newVoicePersonality = '';
        this.clearPendingAudio();
      },
      error: err => {
        this.saving = false;
        this.addError = err?.error?.message || 'Could not save that voice.';
      }
    });
  }

  testVoice(vp: VoiceProfile): void {
    this.testing[vp.id] = true;
    this.api.testVoiceProfile(vp.id, 'Hello! This is what I sound like.').subscribe({
      next: blob => {
        if (this.testAudioUrl[vp.id]) { URL.revokeObjectURL(this.testAudioUrl[vp.id]); }
        this.testAudioUrl[vp.id] = URL.createObjectURL(blob);
        this.testing[vp.id] = false;
      },
      error: () => {
        this.testing[vp.id] = false;
        this.error = `Could not generate a test sample for "${vp.name}".`;
      }
    });
  }

  startRename(vp: VoiceProfile): void {
    this.renamingId = vp.id;
    this.renameValue = vp.name;
  }

  confirmRename(vp: VoiceProfile): void {
    if (!this.renameValue.trim()) { return; }
    this.api.renameVoiceProfile(vp.id, this.renameValue.trim()).subscribe({
      next: updated => {
        this.voiceProfiles = this.voiceProfiles.map(v => v.id === updated.id ? updated : v);
        this.renamingId = '';
      },
      error: () => { this.error = 'Could not rename that voice.'; }
    });
  }

  deleteVoice(vp: VoiceProfile): void {
    this.api.deleteVoiceProfile(vp.id).subscribe({
      next: () => { this.voiceProfiles = this.voiceProfiles.filter(v => v.id !== vp.id); },
      error: () => { this.error = `Could not delete "${vp.name}".`; }
    });
  }

  ngOnDestroy(): void {
    this.revokeAudio();
    this.clearPendingAudio();
    Object.values(this.testAudioUrl).forEach(url => URL.revokeObjectURL(url));
    if (this.recordTimer) { clearInterval(this.recordTimer); }
  }

  /** Object URLs are not garbage collected on their own - leaking one per play
   *  would hold every generated WAV in memory for the life of the tab. */
  private revokeAudio(): void {
    if (this.audioUrl) {
      URL.revokeObjectURL(this.audioUrl);
      this.audioUrl = undefined;
    }
  }
}
