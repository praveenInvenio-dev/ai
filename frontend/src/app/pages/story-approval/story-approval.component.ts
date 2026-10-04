import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription, timer, forkJoin } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { Character, CharacterReference } from '../../models/models';
import { Episode, SceneDto, VoiceSegment } from '../../models/models';

@Component({
  selector: 'app-story-approval',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div *ngIf="episode">
      <header class="page-head">
        <div>
          <span class="tag tag-amber">{{ stageLabel }}</span>
          <span class="tag" *ngIf="episode.ollamaModel">{{ episode.ollamaModel }}</span>
          <h1>{{ episode.title || 'Untitled story' }}</h1>
          <p class="sub">{{ logline }}</p>
        </div>
        <div class="quality" *ngIf="episode.qualityScore != null">
          <span class="score">{{ episode.qualityScore }}</span><span class="muted">quality score</span>
        </div>
      </header>

      <section class="card narration" *ngIf="fullNarration"><h3>Story</h3><p class="narration-text">{{ fullNarration }}</p></section>

      <section class="card" *ngIf="imageJob">
        <h3>Generating scene images</h3>
        <p class="muted">The video is not generated yet. Images are produced first so you can choose the voices and delivery before rendering.</p>
        <div class="progress"><div [style.width.%]="imageProgress"></div></div>
        <strong>{{ imageProgress }}%</strong> · {{ imageStatus }}
      </section>

      <section class="card character-builder" *ngIf="characters.length || characterLoading || characterError">
        <div class="section-head compact">
          <div><span class="eyebrow">CHARACTER BUILDING</span><h2>Master character references</h2>
            <p class="muted">Each story character gets their own master prompt and optional reference image. Generate and review them here before scene generation; locking a reference makes it the preferred identity reference for future scenes.</p>
          </div>
          <span class="library-badge" *ngIf="characters.length">{{ lockedCharacterCount }} / {{ characters.length }} locked</span>
        </div>
        <p class="hint" *ngIf="characterLoading">Loading story characters…</p>
        <p class="error inline-error" *ngIf="characterError">{{ characterError }}</p>
        <div class="character-grid">
          <article class="character-builder-card" *ngFor="let c of characters">
            <div class="character-head">
              <div><strong>{{ c.name }}</strong><span class="muted">{{ c.species || 'Character' }}</span></div>
              <span class="tag" *ngIf="characterRef(c.id)?.locked">🔒 Reference locked</span>
            </div>
            <textarea rows="8" [(ngModel)]="characterPrompts[c.id]" placeholder="Master character reference prompt"></textarea>
            <div class="character-reference" *ngIf="characterRef(c.id) as ref">
              <img [src]="characterReferenceUrl(ref)" [alt]="c.name + ' master reference'" loading="lazy">
              <div class="reference-meta">{{ ref.locked ? 'Locked master reference' : 'Generated reference — optional until locked' }}</div>
            </div>
            <p class="hint" *ngIf="characterGenerating[c.id]">Generating master reference with ComfyUI…</p>
            <p class="error inline-error" *ngIf="characterErrors[c.id]">{{ characterErrors[c.id] }}</p>
            <div class="character-actions">
              <button class="btn btn-ghost" type="button" (click)="refreshCharacterPrompt(c)" [disabled]="characterGenerating[c.id]">↻ Auto prompt</button>
              <button class="btn btn-primary" type="button" (click)="generateCharacterMaster(c)" [disabled]="characterGenerating[c.id] || !characterPrompts[c.id].trim()">{{ characterGenerating[c.id] ? 'Generating…' : (characterRef(c.id) ? 'Regenerate reference' : 'Generate reference') }}</button>
              <label class="btn btn-ghost upload-btn">📷 Upload identity image
                <input type="file" accept="image/png,image/jpeg,image/webp" hidden (change)="uploadCharacterReference(c, $event)" [disabled]="characterGenerating[c.id]">
              </label>
              <button class="btn btn-ghost" type="button" *ngIf="characterRef(c.id)" (click)="toggleCharacterReferenceLock(c)" [disabled]="characterGenerating[c.id]">{{ characterRef(c.id)?.locked ? '🔓 Unlock reference' : '🔒 Lock reference' }}</button>
            </div>
            <p class="hint">Upload a real character photo/reference when exact identity matters. H3 uses it as a dedicated identity reference while the storyboard image controls scene composition. Lock it to make it the preferred reference.</p>
          </article>
        </div>
      </section>

      <section>
        <div class="section-head"><div><span class="eyebrow">STORYBOARD</span><h2>Scene breakdown</h2><p class="muted">{{ scenes.length }} scenes · ~{{ totalMinutes }} min · review visual, voice and motion decisions before rendering.</p></div><div class="section-stat"><strong>{{ imagesReady ? "Ready" : "Draft" }}</strong><span>{{ imagesReady ? "Visuals generated" : "Waiting for approval" }}</span></div></div>
        <div class="storyboard">
          <article class="card scene-card" *ngFor="let s of scenes">
            <div class="scene-num">Scene {{ s.sceneNumber }}</div>
            <img *ngIf="imagesReady" [src]="sceneImageUrl(s)" class="scene-image" loading="lazy">
            <div class="scene-image-actions" *ngIf="imagesReady">
              <button class="btn-icon" (click)="toggleLock(s)" [disabled]="sceneBusy.has(s.id)" [title]="s.locked ? 'Unlock this image' : 'Lock this image (regeneration will never touch it)'">{{ s.locked ? '🔒' : '🔓' }}</button>
              <button class="btn-icon" (click)="regenerateImage(s)" [disabled]="sceneBusy.has(s.id) || s.locked" title="Regenerate this image">{{ sceneBusy.has(s.id) ? '⏳' : '↻' }}</button>
            </div>
            <p class="scene-narration">{{ s.narration }}</p>
            <div class="voice-actions" *ngIf="s.narrationSeconds"><span class="voice-label">Narration</span>
              <button class="action-chip" [class.active]="s.narrationLocked" (click)="toggleNarrationLock(s)" [disabled]="sceneBusy.has('n-'+s.id)" [title]="s.narrationLocked ? 'Unlock this narration' : 'Lock this narration'">{{ s.narrationLocked ? '🔒 Narration locked' : '🔓 Lock narration' }}</button>
              <button class="action-chip" (click)="regenerateNarration(s)" [disabled]="sceneBusy.has('n-'+s.id) || s.narrationLocked" title="Regenerate this scene's narration">{{ sceneBusy.has('n-'+s.id) ? '⏳ Generating' : '↻ Regenerate narration' }}</button>
            </div>
            <label class="anim-mode" *ngIf="imagesReady" title="How this scene is animated">Motion:
              <select [ngModel]="s.animationMode || 'AUTO'" (ngModelChange)="setAnimationMode(s, $event)">
                <option value="AUTO">Auto</option>
                <option value="STATIC">Static (no motion)</option>
                <option value="TWO_POINT_FIVE_D">Full camera motion</option>
                <option value="CHARACTER_MOTION">Character motion (breathing + sway)</option>
                <option value="TALKING_CHARACTER">Talking character (lip-sync)</option>
              </select>
            </label>
            <div class="scene-meta"><span class="tag tag-hero" *ngIf="s.importance==='HERO'">⭐ Hero shot</span><span class="tag" *ngIf="s.locked">🔒 Image locked</span><span class="tag" *ngIf="s.narrationLocked">🔒 Voice locked</span><span class="tag" *ngIf="s.location">{{ s.location }}</span><span class="tag tag-teal" *ngIf="s.emotion">{{ s.emotion }}</span></div>
            <div class="lines" *ngIf="s.voiceSegments?.length">
              <div class="line" *ngFor="let seg of s.voiceSegments">
                <b>{{ seg.character || 'Narrator' }}</b><span>{{ seg.text }}</span>
              </div>
            </div>
          </article>
        </div>
      </section>

      <section class="card voices" *ngIf="imagesReady">
        <div class="section-head compact"><div><span class="eyebrow">VOICE DIRECTOR</span><h2>Cast your story</h2><p class="muted">Choose a voice for each speaker. Saved voices from Voice Lab appear here automatically.</p></div><span class="library-badge">{{ customVoiceCount }} saved voice{{ customVoiceCount === 1 ? '' : 's' }}</span></div>
        <div class="voice-mapping" *ngFor="let speaker of speakers">
          <div><strong>{{ speaker }}</strong><span class="muted"> {{ speaker === 'Narrator' ? 'story narration' : 'character dialogue' }}</span></div>
          <select [(ngModel)]="voiceMap[speaker]" (change)="applyVoice(speaker)"><option value="" disabled>Choose a voice…</option>
            <option *ngFor="let v of voices" [value]="v.id">{{ v.label }} — {{ v.accent }} · {{ v.engine || 'tts' }}{{ v.id.startsWith('profile:') ? ' · saved voice' : '' }}</option>
          </select>
        </div>
        <p class="hint" *ngIf="speakers.length === 1">Only one speaker was detected, so the default voice is selected automatically.</p>
      </section>

      <section class="card music" *ngIf="imagesReady">
        <h2>🎵 Background music (optional)</h2>
        <p class="muted">A quiet mood bed plays under the narration and automatically ducks while anyone is speaking. Uploading your own track always takes priority over the preset below.</p>
        <div class="music-row">
          <label>Mood preset
            <select [(ngModel)]="musicPreset" (change)="applyMusicPreset()" [disabled]="!!episode!.musicLocked">
              <option [ngValue]="null">None</option>
              <option value="calm">Calm</option>
              <option value="adventurous">Adventurous</option>
              <option value="emotional">Emotional</option>
            </select>
          </label>
          <label class="btn upload-btn" [class.disabled]="!!episode!.musicLocked">Upload your own track
            <input type="file" accept="audio/*" (change)="onMusicFileSelected($event)" [disabled]="!!episode!.musicLocked" hidden>
          </label>
          <button class="btn-icon" (click)="toggleMusicLock()" [title]="episode!.musicLocked ? 'Unlock music' : 'Lock music (nothing will replace it)'">{{ episode!.musicLocked ? '🔒' : '🔓' }}</button>
          <span class="muted small" *ngIf="musicUploadName">✓ {{ musicUploadName }} uploaded — this will be used instead of the preset.</span>
        </div>
      </section>

      <footer class="actions">
        <button class="btn btn-ghost" (click)="regenerate()" [disabled]="busy || imageJob">Regenerate story</button>
        <button class="btn btn-primary" *ngIf="!imagesReady && !imageJob" (click)="generateImages()" [disabled]="busy">✨ Approve story & generate images</button>
        <button class="btn btn-primary" *ngIf="imagesReady" (click)="produceVideo()" [disabled]="busy || !voicesReady">{{ busy ? 'Generating video…' : '🎬 Generate final video' }}</button>
      </footer>
      <p class="error" *ngIf="error">{{ error }}</p>
    </div>
  `,
  styles: [`
    .page-head{display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:1.6rem;gap:1rem}.section-head{display:flex;justify-content:space-between;align-items:flex-end;gap:1rem;margin-bottom:1rem}.section-head.compact{align-items:center}.eyebrow{display:block;font-size:.67rem;letter-spacing:.16em;color:var(--accent);font-weight:800;margin-bottom:.4rem}.section-head h2{margin:0}.section-stat,.library-badge{padding:.65rem .9rem;border:1px solid var(--border);border-radius:12px;background:rgba(255,255,255,.025);text-align:right}.section-stat strong{display:block;color:var(--teal);font-size:.8rem}.section-stat span{font-size:.68rem;color:var(--muted)}.library-badge{color:var(--teal);font-size:.75rem}.voice-actions{display:flex;align-items:center;gap:.45rem;flex-wrap:wrap;padding:.55rem 1.2rem;margin:.35rem 0 .55rem;border:1px solid var(--border);border-radius:12px;background:linear-gradient(180deg,rgba(255,255,255,.025),rgba(255,255,255,.01))}.voice-label{font-size:.68rem;font-weight:800;letter-spacing:.08em;text-transform:uppercase;color:var(--muted);margin-right:.15rem}.action-chip{background:var(--surface-raised);border:1px solid var(--border);border-radius:9px;padding:.4rem .65rem;color:var(--text);font-size:.75rem;cursor:pointer}.action-chip:hover{border-color:var(--accent)}.action-chip.active{color:var(--teal);border-color:rgba(73,201,189,.5)}.sub{color:var(--muted);max-width:70ch}.tag-hero{background:linear-gradient(90deg,#ffb703,#fb8500);color:#1a1200;font-weight:600}.quality{text-align:center}.quality .score{display:block;font-family:var(--font-display);font-size:2.2rem;color:var(--accent)}.quality .muted{font-size:.75rem}.narration{margin-bottom:1.6rem}.narration-text{white-space:pre-wrap}.row-head{margin-bottom:.8rem}.character-builder{margin:0 0 1.6rem}.character-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:1rem}.character-builder-card{border:1px solid var(--border);border-radius:14px;padding:1rem;background:rgba(255,255,255,.02)}.character-head{display:flex;justify-content:space-between;gap:.8rem;align-items:flex-start;margin-bottom:.7rem}.character-head strong{display:block;font-size:1rem}.character-head .muted{display:block;font-size:.75rem;margin-top:.15rem}.character-builder-card textarea{width:100%;resize:vertical;min-height:150px;font-size:.8rem;line-height:1.45}.character-reference{margin-top:.7rem;border-radius:10px;overflow:hidden;background:var(--surface-raised);border:1px solid var(--border)}.character-reference img{display:block;width:100%;aspect-ratio:1/1;object-fit:cover}.reference-meta{padding:.45rem .6rem;font-size:.72rem;color:var(--muted)}.character-actions{display:flex;flex-wrap:wrap;gap:.5rem;margin-top:.7rem}.inline-error{text-align:left;margin:.5rem 0}.storyboard{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:1rem;margin-bottom:1.5rem}.scene-card{padding:1rem 1.2rem 1.4rem}.scene-num{font-size:.75rem;color:var(--muted);margin-bottom:.5em}.scene-image{width:100%;aspect-ratio:1/1;object-fit:cover;border-radius:10px;margin-bottom:.4rem}.scene-image-actions{display:flex;gap:.4rem;margin-bottom:.6rem}.anim-mode{display:flex;align-items:center;gap:.4rem;font-size:.78rem;color:var(--muted);margin-bottom:.6rem}.anim-mode select{font-size:.78rem;padding:.15rem .3rem}.btn-icon{background:var(--surface-raised);border:1px solid var(--border);border-radius:7px;padding:.3rem .6rem;cursor:pointer;font-size:.95rem;line-height:1}.btn-icon:disabled{opacity:.5;cursor:default}.scene-narration{font-size:.9rem;min-height:3.2em}.scene-meta{display:flex;gap:.4rem;flex-wrap:wrap}.lines{margin-top:.8rem;border-top:1px solid var(--border);padding-top:.6rem}.line{display:grid;grid-template-columns:90px 1fr;gap:.5rem;padding:.3rem 0;font-size:.82rem}.voices{margin:1rem 0 2rem}.voice-mapping{display:grid;grid-template-columns:220px minmax(220px,1fr);gap:1rem;align-items:center;padding:.8rem 0;border-bottom:1px solid var(--border)}.voice-mapping select{width:100%}.music{margin:0 0 2rem}.music-row{display:flex;align-items:center;gap:1rem;flex-wrap:wrap}.music-row label{display:flex;flex-direction:column;gap:.3rem;font-size:.85rem}.music-row select{padding:.4rem .6rem}.upload-btn{background:var(--surface-raised);border:1px solid var(--border);border-radius:7px;padding:.5rem .9rem;cursor:pointer;justify-content:center}.upload-btn.disabled{opacity:.5;cursor:default;pointer-events:none}.progress{height:8px;background:var(--surface-raised);border-radius:99px;overflow:hidden;margin:1rem 0 .4rem}.progress>div{height:100%;background:var(--accent);transition:width .3s}.hint{font-size:.8rem;color:var(--muted);margin-top:1rem}.actions{display:flex;justify-content:flex-end;gap:1rem;padding:1.5rem 0 3rem;border-top:1px solid var(--border)}.error{color:var(--danger);text-align:right}
  `]
})
export class StoryApprovalComponent implements OnInit, OnDestroy {
  episode: Episode | null = null; scenes: SceneDto[] = []; fullNarration=''; logline=''; busy=false; error='';
  voices:any[]=[]; voiceMap: Record<string,string> = {}; customVoiceCount=0; imagesReady=false; imageJob=''; imageProgress=0; imageStatus=''; autoProducing=false;
  musicPreset: string | null = null; musicUploadName = '';
  characters: Character[] = [];
  characterPrompts: Record<string,string> = {};
  characterReferences: Record<string,CharacterReference> = {};
  characterGenerating: Record<string,boolean> = {};
  characterErrors: Record<string,string> = {};
  characterLoading = true;
  characterError = '';
  sceneBusy = new Set<string>();
  imgVersion: Record<string, number> = {};
  private sub?: Subscription;
  constructor(public api: ApiService, private route: ActivatedRoute, private router: Router) {}
  ngOnInit(){ const id=this.route.snapshot.paramMap.get('id')!; this.api.listVoices().subscribe(v=>{this.voices=v.voices||[];this.customVoiceCount=this.voices.filter((x:any)=>String(x.id).startsWith('profile:')).length;this.initializeVoices();this.maybeAutoProduce();}); this.load(id); }
  ngOnDestroy(){ this.sub?.unsubscribe(); }
  get stageLabel(){ return this.imagesReady ? 'Images ready — voice selection' : 'Draft — nothing rendered yet'; }
  get totalMinutes(){ return (this.scenes.reduce((sum,s)=>sum+(s.imageDurationSeconds||0),0)/60).toFixed(1); }
  get speakers(){ const set=new Set<string>(); this.scenes.forEach(s=>(s.voiceSegments||[]).forEach(x=>set.add(x.character||'Narrator'))); return Array.from(set); }
  get voicesReady(){ return this.speakers.length>0 && this.speakers.every(s=>!!this.voiceMap[s]); }
  private load(id:string){
    this.api.getEpisode(id).subscribe(ep=>{this.episode=ep;this.logline=ep.userPrompt;this.musicPreset=ep.musicPreset??null;});
    this.api.getScenes(id).subscribe(s=>{this.scenes=s;this.initializeVoices();});
    this.api.getEpisodeCharacters(id).subscribe({next:chars=>{
      this.characters=chars;
      this.characterLoading=false;
      this.characterError = chars.length ? '' : 'No story characters were found in this draft. Regenerate the draft once to build the Character Builder data.';
      chars.forEach(c=>{
        this.api.generateCharacterMasterPrompt(c.id).subscribe({ next:r=>this.characterPrompts[c.id]=r.prompt, error:()=>this.characterPrompts[c.id]=this.fallbackCharacterPrompt(c) });
        this.api.listCharacterReferences(c.id).subscribe({ next:refs=>{ if(refs.length) this.characterReferences[c.id]=refs.find(r=>r.locked)||refs.find(r=>r.primary)||refs[refs.length-1]; } });
      });
    }, error:e=>{
      this.characterLoading=false;
      this.characterError=e?.error?.message||'Could not load story characters.';
    }});
  }
  private initializeVoices(){ if(!this.voices.length)return; const def=(this.voices as any[]).find(v=>v.isDefault)?.id || this.voices[0]?.id || ''; this.speakers.forEach(sp=>{ if(!this.voiceMap[sp])this.voiceMap[sp]=def; }); }
  get lockedCharacterCount(){ return this.characters.filter(c=>!!this.characterReferences[c.id]?.locked).length; }
  characterRef(id:string): CharacterReference | null { return this.characterReferences[id] || null; }
  characterReferenceUrl(ref: CharacterReference): string { return this.api.characterReferenceImageUrl(ref.id); }
  fallbackCharacterPrompt(c: Character): string {
    return `Create the permanent MASTER CHARACTER REFERENCE for ${c.name}. Exact character design: ${c.canonicalDescription}. Show full-body front, 3/4 and side/profile views, face close-up and happy, sad, surprised, excited and curious expressions. Clean neutral studio background. Preserve exact colors, facial structure, proportions, clothing, accessories and signature features across every view. Premium 3D children's animation character-development artwork. No other characters.`;
  }
  refreshCharacterPrompt(c: Character){
    this.api.generateCharacterMasterPrompt(c.id).subscribe({next:r=>this.characterPrompts[c.id]=r.prompt,error:()=>this.characterPrompts[c.id]=this.fallbackCharacterPrompt(c)});
  }
  uploadCharacterReference(c: Character, event: Event){
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if(!file)return;
    if(file.size > 10 * 1024 * 1024){ this.characterErrors[c.id] = 'Please use an image smaller than 10 MB.'; input.value=''; return; }
    this.characterGenerating[c.id]=true; delete this.characterErrors[c.id];
    this.api.uploadCharacterReference(c.id, file).subscribe({
      next:ref=>{this.characterGenerating[c.id]=false;this.characterReferences[c.id]=ref; input.value='';},
      error:e=>{this.characterGenerating[c.id]=false;this.characterErrors[c.id]=e?.error?.message||'Could not upload the character reference.'; input.value='';}
    });
  }
  generateCharacterMaster(c: Character){
    const prompt=(this.characterPrompts[c.id]||'').trim(); if(!prompt)return;
    this.characterGenerating[c.id]=true; delete this.characterErrors[c.id];
    this.api.generateCharacterReference(c.id, this.episode?.visualStyle, prompt).subscribe({
      next:ref=>{this.characterGenerating[c.id]=false;this.characterReferences[c.id]=ref;},
      error:e=>{this.characterGenerating[c.id]=false;this.characterErrors[c.id]=e?.error?.message||'Could not generate this character reference.';}
    });
  }
  toggleCharacterReferenceLock(c: Character){
    const ref=this.characterReferences[c.id]; if(!ref)return;
    this.api.lockCharacterReference(c.id, ref.id, !ref.locked).subscribe({
      next:updated=>{this.characterReferences[c.id]=updated;},
      error:e=>this.characterErrors[c.id]=e?.error?.message||'Could not update the reference lock.'
    });
  }
  regenerate(){ if(!this.episode)return; this.busy=true; this.api.regenerateDraft(this.episode.id).subscribe({next:d=>{this.busy=false;this.fullNarration=d.fullNarration;this.logline=d.logline;this.scenes=d.scenes;this.imagesReady=false;this.voiceMap={};this.initializeVoices();if(this.episode){this.episode.title=d.title;this.episode.qualityScore=d.qualityScore;this.api.getEpisodeCharacters(this.episode.id).subscribe(chars=>{this.characters=chars;chars.forEach(c=>this.refreshCharacterPrompt(c));});}},error:e=>{this.busy=false;this.error=e?.error?.message||'Regeneration failed.';}}); }
  generateImages(){ if(!this.episode)return; this.busy=true;this.error='';this.api.generateStoryImages(this.episode.id).subscribe({next:j=>{this.busy=false;this.imageJob=j.id;this.pollImageJob(j.id);},error:e=>{this.busy=false;this.error=e?.error?.message||'Could not start image generation.';}}); }
  toggleLock(s: SceneDto){
    if(!this.episode || this.sceneBusy.has(s.id))return;
    this.sceneBusy.add(s.id);
    this.api.setSceneLock(this.episode.id, s.id, !s.locked).subscribe({
      next: updated => { this.sceneBusy.delete(s.id); s.locked = updated.locked; },
      error: e => { this.sceneBusy.delete(s.id); this.error = e?.error?.message || 'Could not update the lock.'; }
    });
  }
  regenerateImage(s: SceneDto){
    if(!this.episode || this.sceneBusy.has(s.id) || s.locked)return;
    this.sceneBusy.add(s.id);
    this.api.regenerateSceneImage(this.episode.id, s.id).subscribe({
      next: () => { this.sceneBusy.delete(s.id); this.imgVersion[s.id] = Date.now(); },
      error: e => { this.sceneBusy.delete(s.id); this.error = e?.error?.message || 'Could not regenerate that image.'; }
    });
  }
  toggleNarrationLock(s: SceneDto){
    const key = 'n-' + s.id;
    if(!this.episode || this.sceneBusy.has(key))return;
    this.sceneBusy.add(key);
    this.api.setNarrationLock(this.episode.id, s.id, !s.narrationLocked).subscribe({
      next: updated => { this.sceneBusy.delete(key); s.narrationLocked = updated.narrationLocked; },
      error: e => { this.sceneBusy.delete(key); this.error = e?.error?.message || 'Could not update the narration lock.'; }
    });
  }
  regenerateNarration(s: SceneDto){
    const key = 'n-' + s.id;
    if(!this.episode || this.sceneBusy.has(key) || s.narrationLocked)return;
    this.sceneBusy.add(key);
    this.api.regenerateSceneNarration(this.episode.id, s.id).subscribe({
      next: updated => { this.sceneBusy.delete(key); s.narrationSeconds = updated.narrationSeconds; },
      error: e => { this.sceneBusy.delete(key); this.error = e?.error?.message || 'Could not regenerate that narration.'; }
    });
  }
  setAnimationMode(s: SceneDto, mode: string){
    if(!this.episode)return;
    const previous = s.animationMode;
    s.animationMode = mode;
    this.api.setAnimationMode(this.episode.id, s.id, mode).subscribe({
      error: e => { s.animationMode = previous; this.error = e?.error?.message || 'Could not update the animation mode.'; }
    });
  }
  applyMusicPreset(){
    if(!this.episode)return;
    this.api.setMusicPreset(this.episode.id, this.musicPreset).subscribe({
      error: e => { this.error = e?.error?.message || 'Could not update the music preset.'; }
    });
  }
  toggleMusicLock(){
    if(!this.episode)return;
    const next = !this.episode.musicLocked;
    this.api.setMusicLock(this.episode.id, next).subscribe({
      next: updated => { if(this.episode) this.episode.musicLocked = updated.musicLocked; },
      error: e => { this.error = e?.error?.message || 'Could not update the music lock.'; }
    });
  }
  onMusicFileSelected(event: Event){
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if(!file || !this.episode || this.episode.musicLocked)return;
    this.api.attachMusic(this.episode.id, file).subscribe({
      next: () => { this.musicUploadName = file.name; },
      error: e => { this.error = e?.error?.message || 'Could not upload that music file.'; }
    });
  }
  sceneImageUrl(s: SceneDto): string {
    const base = this.api.sceneImageUrl(s.id);
    const v = this.imgVersion[s.id];
    return v ? `${base}?v=${v}` : base;
  }
  private pollImageJob(jobId:string){ this.sub?.unsubscribe(); this.sub=timer(0,2000).subscribe(()=>this.api.getJobStatus(jobId).subscribe({next:j=>{this.imageProgress=j.progressPercent||0;this.imageStatus=j.status;if(j.status==='COMPLETED'){this.imagesReady=true;this.imageJob='';this.initializeVoices();this.sub?.unsubscribe();this.maybeAutoProduce();}else if(j.status==='FAILED'){this.error=j.errorMessage||'Image generation failed.';this.imageJob='';this.sub?.unsubscribe();}},error:e=>{this.error=e?.error?.message||'Could not read image generation status.';}})); }
  private maybeAutoProduce(){
    if(!this.imagesReady || this.autoProducing || this.busy || !this.episode || !this.voices.length) return;
    this.initializeVoices();
    if(this.speakers.length<=1 || this.voices.length===1){ this.autoProducing=true; this.produceVideo(); }
  }
  applyVoice(speaker:string){ const voice=this.voiceMap[speaker]; this.scenes.filter(s=>(s.voiceSegments||[]).some(x=>(x.character||'Narrator')===speaker)).forEach(s=>{(s.voiceSegments||[]).forEach(x=>{if((x.character||'Narrator')===speaker)x.voice=voice;});this.api.updateSceneVoices(this.episode!.id,s.id,s.voiceSegments||[]).subscribe();}); }
  produceVideo(){
    if(!this.episode || !this.voicesReady)return;
    this.busy=true; this.error='';
    const updates=this.scenes.map(s=>{
      const segments=(s.voiceSegments||[]).map(x=>{ const speaker=x.character||'Narrator'; return {...x,voice:this.voiceMap[speaker]||x.voice}; });
      s.voiceSegments=segments;
      return this.api.updateSceneVoices(this.episode!.id,s.id,segments);
    });
    forkJoin(updates).subscribe({
      next:()=>this.api.approveAndProduce(this.episode!.id).subscribe({
        next:j=>this.router.navigate(['/episodes',this.episode!.id,'production'],{state:{jobId:j.id}}),
        error:e=>{this.busy=false;this.error=e?.error?.message||'Could not start video generation.';}
      }),
      error:e=>{this.busy=false;this.error=e?.error?.message||'Could not save voice selections.';}
    });
  }
}
