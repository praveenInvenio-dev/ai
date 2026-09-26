import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { Project, Universe, Character, CharacterReference } from '../../models/models';

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

    <div class="grid" *ngIf="selectedUniverseId && selectedUniverseId !== '__new__'">
      <article class="card char-card" *ngFor="let c of characters">
        <div class="char-head">
          <h3>{{ c.name }}</h3>
          <span class="tag" [class.tag-amber]="c.locked">{{ c.locked ? 'Locked' : 'v' + c.version }}</span>
        </div>
        <p class="desc">{{ c.canonicalDescription }}</p>

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
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 1rem; }
    .char-head { display: flex; justify-content: space-between; align-items: baseline; }
    .desc { font-size: 0.88rem; color: var(--text); min-height: 4em; }
    .ref-image {
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
  generating: Record<string, boolean> = {};
  genError: Record<string, string> = {};

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    this.api.listProjects().subscribe(p => this.projects = p);
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

  referenceFor(characterId: string): CharacterReference | null {
    return this.references[characterId] || null;
  }

  imageUrl(ref: CharacterReference): string {
    return this.api.characterReferenceImageUrl(ref.id);
  }

  private loadLatestReference(characterId: string): void {
    this.api.listCharacterReferences(characterId).subscribe({
      next: refs => {
        if (refs.length) {
          this.references[characterId] = refs[refs.length - 1];
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
        this.references[c.id] = ref;
      },
      error: err => {
        this.generating[c.id] = false;
        this.genError[c.id] = err?.error?.message || 'Generation failed — check the backend logs for the real reason.';
      }
    });
  }
}
