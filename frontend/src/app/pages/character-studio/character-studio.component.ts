import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { Project, Universe, Character, CharacterReference, VoiceProfile } from '../../models/models';

@Component({
  selector: 'app-character-studio',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <h1>Character studio</h1>
    <p class="sub">Canonical descriptions live here — the story engine can decide what a character does or feels, never what they look like.</p>

    <div class="card selector">
      <div>
        <label>Project</label>
        <select [(ngModel)]="selectedProjectId" (ngModelChange)="onProjectChange()">
          <option value="">Choose a project</option>
          <option *ngFor="let p of projects" [value]="p.id">{{ p.name }}</option>
        </select>
      </div>
      <div>
        <label>Universe</label>
        <select [(ngModel)]="selectedUniverseId" (ngModelChange)="onUniverseChange()" [disabled]="!selectedProjectId">
          <option value="">Choose a universe</option>
          <option value="__new__">+ New universe</option>
          <option *ngFor="let u of universes" [value]="u.id">{{ u.name }}</option>
        </select>
      </div>
    </div>

    <div class="card selector" *ngIf="selectedUniverseId === '__new__'">
      <div>
        <label>New universe name</label>
        <input [(ngModel)]="newUniverseName" placeholder="Magical Forest" />
      </div>
      <div class="new-universe-actions">
        <button class="btn btn-primary" (click)="createUniverse()" [disabled]="!newUniverseName.trim()">Create universe</button>
      </div>
    </div>

    <div class="card ref-sheet" *ngIf="selectedUniverseId && selectedUniverseId !== '__new__' && characters.length > 0">
      <div class="ref-sheet-head">
        <h3>Master character reference sheet prompt</h3>
        <button class="btn btn-ghost" (click)="generateReferenceSheetPrompt()" [disabled]="generatingSheet">
          {{ generatingSheet ? 'Building...' : (referenceSheetPrompt ? 'Regenerate' : 'Generate prompt') }}
        </button>
      </div>
      <p class="hint">
        One multi-pose, multi-expression prompt showing every character in this universe together -
        generate it, use it with your image tool of choice, then lock the result per character below.
      </p>
      <div *ngIf="referenceSheetPrompt">
        <textarea class="sheet-textarea" rows="10" readonly [value]="referenceSheetPrompt"></textarea>
        <button class="btn" (click)="copyReferenceSheetPrompt()">{{ copied ? 'Copied!' : 'Copy prompt' }}</button>
      </div>
      <p class="ref-status error" *ngIf="sheetError">{{ sheetError }}</p>
    </div>

    <div class="grid" *ngIf="selectedUniverseId && selectedUniverseId !== '__new__'">
      <article class="card char-card" *ngFor="let c of characters">
        <div class="char-head">
          <h3>{{ c.name }}</h3>
          <span class="tag" [class.tag-amber]="c.locked">{{ c.locked ? 'Locked' : 'v' + c.version }}</span>
        </div>
        <p class="desc">{{ c.canonicalDescription }}</p>

        <div class="voice-assign">
          <label>Voice</label>
          <select [ngModel]="c.voiceProfileId || ''" (ngModelChange)="assignVoice(c, $event)">
            <option value="">Not assigned (default TTS)</option>
            <option *ngFor="let vp of voiceProfiles" [value]="vp.id">{{ vp.name }} ({{ vp.provider }})</option>
          </select>
        </div>

        <div class="ref-picker" *ngIf="referenceOptions[c.id]?.length">
          <label>Saved character reference</label>
          <select [ngModel]="selectedReferenceId[c.id]" (ngModelChange)="selectReference(c.id, $event)">
            <option *ngFor="let ref of referenceOptions[c.id]" [value]="ref.id">{{ referenceLabel(ref) }}</option>
          </select>
        </div>
        <div class="ref-image" *ngIf="referenceFor(c.id) as ref">
          <img [src]="imageUrl(ref)" [alt]="c.name" />
        </div>
        <p class="ref-status" *ngIf="generating[c.id]">
          Generating with ComfyUI… this can take several minutes on CPU.
        </p>
        <p class="ref-status error" *ngIf="genError[c.id]">{{ genError[c.id] }}</p>

        <div class="char-actions">
          <button class="btn btn-ghost" (click)="toggleLock(c)">{{ c.locked ? 'Unlock' : 'Lock canon' }}</button>
          <button class="btn btn-ghost" (click)="generateReference(c)" [disabled]="generating[c.id]">
            {{ generating[c.id] ? 'Generating…' : (referenceFor(c.id) ? 'Regenerate image' : 'Generate reference image') }}
          </button>
        </div>
        <p class="hint" *ngIf="!referenceFor(c.id) && !generating[c.id]">
          One simple prompt, one image — a quick way to check your ComfyUI setup without running a full story.
        </p>
      </article>

      <article class="card char-card new-char">
        <h3>New character</h3>
        <input [(ngModel)]="newName" placeholder="Name" />
        <textarea [(ngModel)]="newDescription" rows="3" placeholder="Canonical visual description, e.g. small white rabbit, blue overalls, red backpack..."></textarea>
        <button class="btn btn-primary" (click)="createCharacter()" [disabled]="!newName || !newDescription">Add character</button>
      </article>
    </div>
  `,
  styles: [`
    .sub { color: var(--muted); max-width: 60ch; margin-bottom: 1.6rem; }
    .selector { display: flex; gap: 1.5rem; margin-bottom: 1.6rem; }
    .selector > div { flex: 1; }
    .new-universe-actions { display: flex; align-items: flex-end; }
    .ref-sheet { margin-bottom: 1.6rem; }
    .ref-sheet-head { display: flex; justify-content: space-between; align-items: center; gap: 1rem; }
    .ref-sheet-head h3 { margin: 0; }
    .sheet-textarea {
      width: 100%; font-family: var(--font-body); font-size: 0.82rem; margin: 0.8em 0 0.5em;
      background: var(--surface-raised); color: var(--text); border: 1px solid var(--border); border-radius: 8px;
      padding: 0.7em; resize: vertical;
    }
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 1rem; }
    .char-head { display: flex; justify-content: space-between; align-items: baseline; }
    .desc { font-size: 0.88rem; color: var(--text); min-height: 4em; }
    .voice-assign { display: flex; align-items: center; gap: 0.5rem; margin-top: 0.4em; }
    .voice-assign label { font-size: 0.78rem; color: var(--muted); margin: 0; }
    .voice-assign select { flex: 1; font-size: 0.82rem; padding: 0.25rem 0.4rem; border-radius: 6px; background: var(--surface); color: inherit; border: 1px solid var(--border); }
    .ref-picker{display:flex;flex-direction:column;gap:.3rem;margin-top:.6em}.ref-picker label{font-size:.75rem;color:var(--muted)}.ref-picker select{width:100%;font-size:.82rem;padding:.3rem .4rem;background:var(--surface);color:inherit;border:1px solid var(--border);border-radius:6px}.ref-image {
      width: 100%; aspect-ratio: 1/1; border-radius: 8px; overflow: hidden;
      background: var(--surface-raised); margin: 0.6em 0;
    }
    .ref-image img { width: 100%; height: 100%; object-fit: cover; display: block; }
    .ref-status { font-size: 0.78rem; color: var(--muted); margin: 0.4em 0; }
    .ref-status.error { color: var(--danger); }
    .char-actions { display: flex; gap: 0.5rem; flex-wrap: wrap; margin-top: 0.6em; }
    .hint { font-size: 0.75rem; color: var(--muted); margin-top: 0.6em; }
    .new-char { display: flex; flex-direction: column; gap: 0.6rem; }
    .new-char input, .new-char textarea { width: 100%; }
  `]
})
export class CharacterStudioComponent implements OnInit {
  projects: Project[] = [];
  universes: Universe[] = [];
  characters: Character[] = [];
  selectedProjectId = '';
  selectedUniverseId = '';
  newUniverseName = '';
  newName = '';
  newDescription = '';

  references: Record<string, CharacterReference> = {};
  referenceOptions: Record<string, CharacterReference[]> = {};
  selectedReferenceId: Record<string, string> = {};
  generating: Record<string, boolean> = {};
  genError: Record<string, string> = {};

  referenceSheetPrompt = '';
  generatingSheet = false;
  sheetError = '';
  copied = false;

  voiceProfiles: VoiceProfile[] = [];

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    this.api.listProjects().subscribe(p => this.projects = p);
    this.api.listVoiceProfiles().subscribe({
      next: profiles => { this.voiceProfiles = profiles; },
      error: () => { /* voice picker just shows "not assigned" only - not fatal */ }
    });
  }

  onProjectChange(): void {
    this.universes = [];
    this.characters = [];
    this.selectedUniverseId = '';
    if (this.selectedProjectId) {
      this.api.listUniverses(this.selectedProjectId).subscribe(u => this.universes = u);
    }
  }

  onUniverseChange(): void {
    this.referenceSheetPrompt = '';
    this.sheetError = '';
    if (this.selectedUniverseId && this.selectedUniverseId !== '__new__') {
      this.api.listCharacters(this.selectedUniverseId).subscribe(chars => {
        this.characters = chars;
        chars.forEach(c => this.loadLatestReference(c.id));
      });
    } else {
      this.characters = [];
    }
  }

  createUniverse(): void {
    if (!this.selectedProjectId || !this.newUniverseName.trim()) return;
    this.api.createUniverse({ projectId: this.selectedProjectId, name: this.newUniverseName.trim() }).subscribe(u => {
      this.universes = [...this.universes, u];
      this.selectedUniverseId = u.id;
      this.newUniverseName = '';
      this.onUniverseChange();
    });
  }

  createCharacter(): void {
    this.api.createCharacter({
      universeId: this.selectedUniverseId,
      name: this.newName,
      canonicalDescription: this.newDescription
    }).subscribe(c => {
      this.characters = [...this.characters, c];
      this.newName = '';
      this.newDescription = '';
    });
  }

  toggleLock(c: Character): void {
    this.api.lockCharacter(c.id, !c.locked).subscribe(updated => {
      this.characters = this.characters.map(x => x.id === updated.id ? updated : x);
    });
  }

  assignVoice(c: Character, voiceProfileId: string): void {
    this.api.assignCharacterVoice(c.id, voiceProfileId || null).subscribe({
      next: updated => {
        this.characters = this.characters.map(x => x.id === updated.id ? updated : x);
      },
      error: () => { /* leave the dropdown as-is - character keeps its previous assignment */ }
    });
  }

  referenceFor(characterId: string): CharacterReference | null { return this.references[characterId] || null; }
  referenceLabel(ref: CharacterReference): string { return `${ref.locked ? '🔒 locked' : (ref.primary ? '★ primary' : 'saved')} · ${new Date(ref.createdAt).toLocaleString()}`; }
  selectReference(characterId: string, referenceId: string): void { const ref=(this.referenceOptions[characterId]||[]).find(r=>r.id===referenceId); if(ref){ this.selectedReferenceId[characterId]=ref.id; this.references[characterId]=ref; } }

  imageUrl(ref: CharacterReference): string {
    return this.api.characterReferenceImageUrl(ref.id);
  }

  private loadLatestReference(characterId: string): void {
    this.api.listCharacterReferences(characterId).subscribe({
      next: refs => {
        this.referenceOptions[characterId] = refs || [];
        if (refs.length) {
          const preferred = refs.find(r => r.locked) || refs.find(r => r.primary) || refs[refs.length - 1];
          this.selectedReferenceId[characterId] = preferred.id;
          this.references[characterId] = preferred;
        }
      },
      error: () => {} // no references yet - fine
    });
  }

  generateReference(c: Character): void {
    this.generating[c.id] = true;
    delete this.genError[c.id];
    this.api.generateCharacterReference(c.id).subscribe({
      next: ref => {
        this.generating[c.id] = false;
        this.api.listCharacterReferences(c.id).subscribe({ next: refs => {
          this.referenceOptions[c.id] = refs || [ref];
          this.selectedReferenceId[c.id] = ref.id;
          this.references[c.id] = ref;
        }, error: () => { this.referenceOptions[c.id] = [ref]; this.selectedReferenceId[c.id] = ref.id; this.references[c.id] = ref; } });
      },
      error: err => {
        this.generating[c.id] = false;
        this.genError[c.id] = err?.error?.message || 'Generation failed — check the backend logs for the real reason.';
      }
    });
  }

  generateReferenceSheetPrompt(): void {
    if (!this.selectedUniverseId || this.selectedUniverseId === '__new__') { return; }
    this.generatingSheet = true;
    this.sheetError = '';
    this.copied = false;
    this.api.characterReferenceSheetPrompt(this.selectedUniverseId).subscribe({
      next: res => {
        this.referenceSheetPrompt = res.prompt;
        this.generatingSheet = false;
      },
      error: err => {
        this.sheetError = err?.error?.message || 'Could not build the reference sheet prompt.';
        this.generatingSheet = false;
      }
    });
  }

  copyReferenceSheetPrompt(): void {
    if (!this.referenceSheetPrompt) { return; }
    navigator.clipboard.writeText(this.referenceSheetPrompt).then(() => {
      this.copied = true;
      setTimeout(() => { this.copied = false; }, 2000);
    });
  }
}
