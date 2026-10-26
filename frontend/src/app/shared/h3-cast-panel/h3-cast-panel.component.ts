import { Component, EventEmitter, Input, OnDestroy, OnInit, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CastCharacter, CharacterCastService } from './character-cast.service';

/**
 * Character references for MiniMax H3. Upload a character once, give it a name + master prompt, generate a clean
 * reference sheet from the upload (identity and outfit preserved), and it is locked. From then on every H3 clip
 * whose prompt names that character gets exactly that reference image - two characters per clip at most.
 */
@Component({
  selector: 'app-h3-cast-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
  <section class="cast" aria-label="Character references for H3">
    <header>
      <div>
        <strong>Character references</strong>
        <span class="muted small">Same name = same locked reference image in every H3 clip · max {{ perClip }} characters per clip</span>
      </div>
      <button type="button" class="btn small-btn" (click)="adding = !adding">{{ adding ? 'Close' : '+ Add character' }}</button>
    </header>

    <div class="add" *ngIf="adding">
      <div class="grid2">
        <label>Name <span class="muted small">(the story must use this exact name)</span>
          <input type="text" [(ngModel)]="newName" maxlength="40" placeholder="e.g. Asha" />
        </label>
        <label>Other names <span class="muted small">(optional, comma separated)</span>
          <input type="text" [(ngModel)]="newAliases" placeholder="e.g. Dadi, Grandma" />
        </label>
      </div>
      <label>Master prompt <span class="muted small">(fixed description sent with the reference on every clip)</span>
        <textarea rows="3" maxlength="800" [(ngModel)]="newPrompt" placeholder="e.g. 8-year-old Indian girl, two black braids with red ribbons, round face, yellow kurta with blue border, brown sandals, small silver nose stud"></textarea>
      </label>
      <div class="file-row">
        <input type="file" accept="image/png,image/jpeg,image/webp" (change)="onFile($event)" />
        <img *ngIf="newPreview" [src]="newPreview" alt="Upload preview" />
      </div>
      <label class="check"><input type="checkbox" [(ngModel)]="newGenerate" />
        <span>Generate a clean reference sheet from this image <span class="muted small">(same face and outfit, plain background, full body). Untick if the upload already is a clean character sheet.</span></span>
      </label>
      <button type="button" class="btn btn-primary" [disabled]="busy || !newFile || !newName.trim()" (click)="create()">{{ busy ? 'Saving…' : 'Save character' }}</button>
    </div>

    <p class="error" *ngIf="error">{{ error }}</p>
    <p class="muted small" *ngIf="!cast.length && !adding">No characters yet. Add the main character (and a second one if the story has two) so every scene keeps the same look.</p>

    <ul class="list">
      <li *ngFor="let c of cast; trackBy: byId" [class.off]="!c.enabled">
        <div class="thumbs">
          <figure><img [src]="svc.imageUrl(c, 'upload')" alt="" /><figcaption>Upload</figcaption></figure>
          <figure>
            <img *ngIf="c.referenceStatus === 'READY'" [src]="svc.imageUrl(c, 'reference')" alt="" />
            <div class="ph" *ngIf="c.referenceStatus !== 'READY'">{{ c.referenceStatus === 'GENERATING' ? 'Generating…' : c.referenceStatus === 'FAILED' ? 'Failed' : 'No reference' }}</div>
            <figcaption>Reference{{ c.referenceIsUpload && c.referenceStatus === 'READY' ? ' (= upload)' : '' }}</figcaption>
          </figure>
        </div>
        <div class="body">
          <div class="title">
            <strong>{{ c.name }}</strong>
            <span class="badge" [class.ok]="c.referenceStatus === 'READY' && c.locked">{{ c.referenceStatus === 'READY' ? (c.locked ? '🔒 locked' : 'ready · unlocked') : c.referenceStatus.toLowerCase() }}</span>
            <span class="muted small" *ngIf="c.aliases?.length">also: {{ c.aliases.join(', ') }}</span>
          </div>
          <ng-container *ngIf="editing !== c.id; else editTpl">
            <p class="prompt">{{ c.masterPrompt || 'No master prompt.' }}</p>
          </ng-container>
          <ng-template #editTpl>
            <textarea rows="3" maxlength="800" [(ngModel)]="editPrompt"></textarea>
            <input type="text" [(ngModel)]="editAliases" placeholder="Other names, comma separated" />
          </ng-template>
          <p class="error small" *ngIf="c.referenceStatus === 'FAILED'">{{ c.referenceError }}</p>
          <div class="actions">
            <label class="check inline"><input type="checkbox" [ngModel]="c.enabled" (ngModelChange)="toggle(c, $event)" /> use in H3</label>
            <ng-container *ngIf="!c.locked">
              <button type="button" class="link" (click)="act(svc.generate(c.id))" [disabled]="c.referenceStatus === 'GENERATING'">{{ c.referenceStatus === 'READY' ? 'Regenerate' : 'Generate reference' }}</button>
              <button type="button" class="link" (click)="act(svc.useUpload(c.id))" [disabled]="c.referenceStatus === 'GENERATING'">Use upload as-is</button>
              <button type="button" class="link" *ngIf="c.referenceStatus === 'READY'" (click)="act(svc.lock(c.id, true))">Lock</button>
              <label class="link file-link">Replace image<input type="file" accept="image/png,image/jpeg,image/webp" (change)="replace(c, $event)" /></label>
            </ng-container>
            <button type="button" class="link" *ngIf="c.locked" (click)="act(svc.lock(c.id, false))">Unlock</button>
            <button type="button" class="link" *ngIf="editing !== c.id" (click)="startEdit(c)">Edit</button>
            <button type="button" class="link" *ngIf="editing === c.id" (click)="saveEdit(c)">Save</button>
            <button type="button" class="link danger" (click)="remove(c)">Delete</button>
          </div>
        </div>
      </li>
    </ul>
  </section>`,
  styles: [`
    .cast { border: 1px solid var(--border); border-radius: 12px; padding: .9rem; background: var(--surface); }
    header { display: flex; justify-content: space-between; align-items: center; gap: .8rem; flex-wrap: wrap; }
    header div { display: flex; flex-direction: column; }
    .muted { color: var(--muted); } .small { font-size: .75rem; } .error { color: var(--danger); font-size: .85rem; }
    .add { display: grid; gap: .6rem; margin: .8rem 0; padding: .8rem; border: 1px dashed var(--border); border-radius: 10px; }
    .add label { display: grid; gap: .25rem; font-size: .85rem; }
    .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: .6rem; }
    @media (max-width: 700px) { .grid2 { grid-template-columns: 1fr; } }
    .file-row { display: flex; align-items: center; gap: .8rem; } .file-row img { height: 72px; border-radius: 8px; }
    .check { display: flex !important; grid-template-columns: none; gap: .5rem; align-items: flex-start; }
    .check.inline { display: inline-flex !important; align-items: center; font-size: .8rem; }
    .list { list-style: none; padding: 0; margin: .8rem 0 0; display: grid; gap: .6rem; }
    li { display: flex; gap: .8rem; padding: .6rem; border: 1px solid var(--border); border-radius: 10px; background: var(--bg); }
    li.off { opacity: .55; }
    .thumbs { display: flex; gap: .4rem; flex-shrink: 0; }
    figure { margin: 0; text-align: center; font-size: .65rem; color: var(--muted); }
    figure img, .ph { width: 74px; height: 96px; object-fit: cover; border-radius: 7px; display: block; background: var(--surface-raised); }
    .ph { display: grid; place-items: center; font-size: .68rem; padding: .2rem; }
    .body { flex: 1; min-width: 0; display: grid; gap: .3rem; }
    .title { display: flex; gap: .5rem; align-items: center; flex-wrap: wrap; }
    .badge { font-size: .68rem; padding: .1rem .5rem; border-radius: 999px; border: 1px solid var(--border); color: var(--muted); }
    .badge.ok { color: var(--teal); border-color: var(--teal); }
    .prompt { margin: 0; font-size: .8rem; color: var(--muted); display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
    textarea, input[type=text] { width: 100%; }
    .actions { display: flex; flex-wrap: wrap; gap: .3rem .9rem; align-items: center; }
    .link { background: none; border: 0; padding: 0; color: var(--accent); font-size: .8rem; cursor: pointer; }
    .link:disabled { opacity: .5; cursor: default; } .link.danger { color: var(--danger); }
    .file-link input { display: none; }
    .small-btn { font-size: .8rem; padding: .35em .8em; }
  `],
})
export class H3CastPanelComponent implements OnInit, OnDestroy {
  readonly svc = inject(CharacterCastService);
  readonly perClip = 2;
  /** Emits the characters that will be used (enabled + reference ready). */
  @Output() castChange = new EventEmitter<CastCharacter[]>();
  @Input() startOpen = false;

  cast: CastCharacter[] = [];
  adding = false;
  busy = false;
  error = '';
  newName = ''; newAliases = ''; newPrompt = ''; newFile?: File; newPreview?: string; newGenerate = true;
  editing: string | null = null; editPrompt = ''; editAliases = '';
  private timer?: ReturnType<typeof setInterval>;

  ngOnInit() {
    this.adding = this.startOpen;
    this.load();
  }
  ngOnDestroy() {
    if (this.timer) clearInterval(this.timer);
    if (this.newPreview) URL.revokeObjectURL(this.newPreview);
  }
  byId = (_: number, c: CastCharacter) => c.id;

  load() {
    this.svc.list().subscribe({
      next: (l) => { this.cast = l; this.emit(); this.schedulePoll(); },
      error: () => (this.error = 'Could not load character references.'),
    });
  }
  private emit() { this.castChange.emit(this.cast.filter((c) => c.enabled && c.referenceStatus === 'READY')); }
  private schedulePoll() {
    const generating = this.cast.some((c) => c.referenceStatus === 'GENERATING');
    if (generating && !this.timer) this.timer = setInterval(() => this.load(), 4000);
    if (!generating && this.timer) { clearInterval(this.timer); this.timer = undefined; }
  }

  onFile(e: Event) {
    const f = (e.target as HTMLInputElement).files?.[0];
    if (!f) return;
    if (this.newPreview) URL.revokeObjectURL(this.newPreview);
    this.newFile = f; this.newPreview = URL.createObjectURL(f);
  }
  create() {
    if (!this.newFile) return;
    this.busy = true; this.error = '';
    this.svc.create(this.newName.trim(), this.newPrompt.trim(), this.newAliases, this.newFile, this.newGenerate).subscribe({
      next: () => {
        this.busy = false; this.adding = false;
        this.newName = this.newAliases = this.newPrompt = ''; this.newFile = undefined;
        if (this.newPreview) URL.revokeObjectURL(this.newPreview);
        this.newPreview = undefined;
        this.load();
      },
      error: (e) => { this.busy = false; this.error = e?.error?.error || 'Could not save the character.'; },
    });
  }
  act(obs: import('rxjs').Observable<unknown>) {
    this.error = '';
    obs.subscribe({ next: () => this.load(), error: (e) => (this.error = e?.error?.error || 'Action failed.') });
  }
  toggle(c: CastCharacter, enabled: boolean) { this.act(this.svc.update(c.id, { enabled })); }
  replace(c: CastCharacter, e: Event) {
    const f = (e.target as HTMLInputElement).files?.[0];
    if (f) this.act(this.svc.replaceImage(c.id, f));
  }
  startEdit(c: CastCharacter) { this.editing = c.id; this.editPrompt = c.masterPrompt; this.editAliases = (c.aliases || []).join(', '); }
  saveEdit(c: CastCharacter) {
    this.svc.update(c.id, { masterPrompt: this.editPrompt, aliases: this.editAliases }).subscribe({
      next: () => { this.editing = null; this.load(); },
      error: (e) => (this.error = e?.error?.error || 'Could not save.'),
    });
  }
  remove(c: CastCharacter) {
    if (!confirm(`Delete ${c.name} and its reference images?`)) return;
    this.act(this.svc.remove(c.id));
  }
}
