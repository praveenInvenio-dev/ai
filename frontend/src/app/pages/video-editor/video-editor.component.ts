import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService, VideoClip, VideoEditorCapabilities, VideoEditorProject, VideoEditorVersion, VideoTimelineClip } from '../../services/api.service';

interface CategoryCard {
  id: string; label: string; ratio: string; blurb: string;
  /** Duration presets offered for this style, in seconds. Always includes "Auto". */
  durations: number[];
  /** Aspect ratios that make sense for this style - restricts the picker
   *  instead of always showing all four regardless of what was chosen. */
  aspectRatios: string[];
  /** Same 4 backend EditIntensity values (SUBTLE/BALANCED/DYNAMIC/AGGRESSIVE),
   *  relabelled per style so "Aggressive" reads as "Viral" for a Reel and
   *  "Action" for Cinematic rather than one generic label set everywhere. */
  intensityLabels: Record<string, string>;
}
interface TimelineDraft {
  clipId: string;
  sourceStartSec: number;
  sourceEndSec: number;
  techniqueIn: string | null;
  techniqueOut: string | null;
  transitionSec: number | null;
  speed: number;
  volume: number;
  muted: boolean;
  locked: boolean;
}

@Component({
  selector: 'app-video-editor',
  host: { '(window:keydown)': 'onKeyDown($event)' },
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <header class="page-head">
      <div>
        <h1>AI Video Editor</h1>
        <p class="muted">A real non-destructive timeline editor: cut, trim, reorder, speed, audio, transitions, preview and export.</p>
      </div>
      <span class="state" *ngIf="project">{{ pretty(project.state) }}</span>
    </header>

    <p class="notice" *ngIf="loadError">{{ loadError }}</p>
    <p class="error" *ngIf="uploadError">{{ uploadError }}</p>

    <section class="panel" *ngIf="!clips.length || uploading">
      <div class="dropzone" [class.dragging]="dragging"
           (dragover)="onDragOver($event)" (dragleave)="dragging=false" (drop)="onDrop($event)">
        <p class="drop-title">Drop source videos here</p>
        <p class="muted">or</p>
        <label class="btn">Choose videos
          <input type="file" multiple accept="video/*" hidden (change)="onFilePicked($event)">
        </label>
        <p class="muted small" *ngIf="caps">Up to {{caps.maxClips}} clips · {{caps.maxUploadMb}} MB each · {{caps.allowedVideoExtensions.join(', ')}}</p>
        <p class="muted" *ngIf="uploading">Uploading {{uploadingCount}} file(s)…</p>
      </div>
    </section>

    <section class="panel" *ngIf="clips.length">
      <div class="section-head"><h2>Source media <span class="count">{{clips.length}}</span></h2><label class="btn smallbtn">+ Add clips<input type="file" multiple accept="video/*" hidden (change)="onFilePicked($event)"></label></div>
      <div class="source-list">
        <div class="source-row" *ngFor="let clip of clips; let i=index" [class.selected]="selectedClipId===clip.id" draggable="true" (dragstart)="onSourceDragStart($event, clip)" (click)="selectSource(clip)">
          <div class="source-thumb"><img *ngIf="project" [src]="thumbnailUrl(clip.id)" (error)="$any($event.target).style.display='none'"><span>{{i+1}}</span></div>
          <div class="source-main">
            <input class="clip-name" [ngModel]="clip.displayName" (click)="$event.stopPropagation()" (blur)="rename(clip,$any($event.target).value)">
            <div class="clip-meta">
              <span *ngIf="clip.durationSec">{{formatDuration(clip.durationSec)}}</span><span *ngIf="clip.width&&clip.height">{{clip.width}}×{{clip.height}}</span>
              <span *ngIf="clip.fps">{{clip.fps|number:'1.0-0'}} fps</span><span *ngIf="clip.hasAudio">audio</span><span *ngIf="clip.analyzed">analysed</span>
            </div>
          </div>
          <div class="clip-actions" (click)="$event.stopPropagation()">
            <button class="icon" (click)="move(i,-1)" [disabled]="i===0">↑</button>
            <button class="icon" (click)="move(i,1)" [disabled]="i===clips.length-1">↓</button>
            <button class="icon danger" (click)="remove(clip)">×</button>
          </div>
        </div>
      </div>
    </section>

    <section class="panel" *ngIf="clips.length">
      <div class="section-head"><h2>Project settings</h2><button class="btn smallbtn" [disabled]="busy" (click)="saveSettings()">Save settings</button></div>
      <div class="card-grid">
        <button type="button" class="card" *ngFor="let c of categories" [class.selected]="form.category===c.id" (click)="selectCategory(c.id)"><strong>{{c.label}}</strong><span class="ratio">{{c.ratio}}</span><span class="blurb">{{c.blurb}}</span></button>
      </div>
      <div class="settings">
        <label>Editing style<select [(ngModel)]="form.editingStyle"><option *ngFor="let s of caps?.styles" [value]="s">{{pretty(s)}}</option></select></label>
        <label>Intensity<select [(ngModel)]="form.intensity"><option *ngFor="let i of caps?.intensities" [value]="i">{{intensityLabel(i)}}</option></select></label>
        <label>Aspect ratio<select [(ngModel)]="form.aspectRatio">
          <option *ngFor="let id of currentCategory()?.aspectRatios ?? []" [value]="id">{{aspectRatioLabel(id)}}</option>
        </select></label>
        <label>Target duration<select [(ngModel)]="form.targetDurationSec">
          <option [ngValue]="null">Auto</option>
          <option *ngFor="let d of currentCategory()?.durations ?? []" [ngValue]="d">{{d}}s</option>
        </select></label>
      </div>
      <p class="style-hint" *ngIf="currentCategory()?.blurb">{{currentCategory()?.blurb}} · durations and ratios shown are the ones that suit this style; pick "Editing style" above to fine-tune the actual technique set.</p>
      <label class="custom">AI edit instruction <span class="muted">(optional — steers shot order and reasoning; works for any style, not just Custom)</span><textarea rows="2" [(ngModel)]="form.customInstructions" placeholder="e.g. &quot;Focus on the birthday cake shots&quot;, &quot;make the opening more exciting&quot;, &quot;less slow motion&quot;"></textarea></label>
      <div class="options"><label *ngFor="let opt of optionList" [class.disabled]="!isOptionAvailable(opt.key)"><input type="checkbox" [(ngModel)]="form[opt.key]" [disabled]="!isOptionAvailable(opt.key)">{{opt.label}} <span class="pending" *ngIf="!isOptionAvailable(opt.key)">not implemented</span></label></div>
    </section>

    <section class="workspace" *ngIf="clips.length">
      <div class="panel preview-panel">
        <div class="section-head"><h2>Monitor</h2><span class="muted" *ngIf="selectedClipId">Source preview</span></div>
        <video *ngIf="sourceUrl && !resultUrl" controls [src]="sourceUrl" class="player"></video>
        <video *ngIf="resultUrl" controls [src]="resultUrl" class="player"></video>
        <div class="monitor-empty" *ngIf="!sourceUrl && !resultUrl">Select a source clip or render a preview.</div>
        <div class="transport" *ngIf="selectedRowIndex>=0"><label>Playhead / split time <input type="number" min="0" [max]="selectedRowDuration" step="0.1" [(ngModel)]="splitTime"></label><button class="btn" (click)="splitSelected()">✂ Split at time</button></div>
      </div>

      <div class="panel timeline-panel">
        <div class="section-head"><h2>Timeline <span class="count">{{timeline.length}} shots · {{totalDuration()}}</span></h2><div class="timeline-actions"><button class="btn smallbtn" [disabled]="!timeline.length" (click)="undo()">Undo</button><button class="btn smallbtn" [disabled]="!redoStack.length" (click)="redo()">Redo</button><button class="btn smallbtn" [disabled]="!timeline.length" (click)="saveTimeline()">Save edit</button></div></div>
        <div class="timeline-track" *ngIf="timeline.length" (dragover)="allowTimelineDrop($event)" (drop)="dropOnTimeline($event, timeline.length)">
          <ng-container *ngFor="let row of timeline; let i=index">
            <div class="drop-marker" [class.active]="dropIndex===i" (dragover)="allowTimelineDrop($event);setDropIndex($event,i)" (drop)="dropOnTimeline($event,i)"></div>
            <button class="tl-block" draggable="true" *ngIf="row" [class.selected]="selectedRowIndex===i" [class.locked]="row.locked" [style.flex]="Math.max(.35,row.outputDurationSec)" (dragstart)="onTimelineDragStart($event,i)" (dragend)="dropIndex=-1" (click)="selectRow(i)">
              <span class="tl-index">{{i+1}}<span *ngIf="row.locked" title="Locked - kept as-is on regenerate">🔒</span></span><strong>{{clipName(row.clipId)}}</strong><small>{{row.sourceStartSec|number:'1.1-1'}}–{{row.sourceEndSec|number:'1.1-1'}}s · {{row.outputDurationSec|number:'1.1-1'}}s</small>
            </button>
          </ng-container>
          <div class="drop-tail" [class.active]="dropIndex===timeline.length" (dragover)="allowTimelineDrop($event);setDropIndex($event,timeline.length)" (drop)="dropOnTimeline($event,timeline.length)">+</div>
        </div>
        <div class="timeline-hint" *ngIf="timeline.length">Drag a source clip onto the timeline to add it. Drag a timeline shot to reorder it. Select a shot to trim or edit.</div>
        <div class="timeline-drop-empty" *ngIf="!timeline.length" (dragover)="allowTimelineDrop($event)" (drop)="dropOnTimeline($event,0)">Drag source clips here to start the edit</div>
        <div class="empty-timeline" *ngIf="!timeline.length">Analyse your clips to automatically build an initial edit, then refine it here. You can also use AI Edit again or add clips manually.</div>

        <div class="trim-panel" *ngIf="selectedRowIndex>=0">
          <div class="trim-head"><strong>Trim shot {{selectedRowIndex+1}}</strong><span>{{trimDuration()|number:'1.1-1'}}s output at {{timeline[selectedRowIndex].speed|number:'1.0-2'}}×</span></div>
          <div class="trim-range">
            <div class="trim-track"></div>
            <input type="range" [min]="0" [max]="selectedClipMax" step="0.05" [(ngModel)]="timeline[selectedRowIndex].sourceStartSec" (input)="normalizeRow(timeline[selectedRowIndex])" (change)="markTimelineDirty()">
            <input type="range" [min]="0" [max]="selectedClipMax" step="0.05" [(ngModel)]="timeline[selectedRowIndex].sourceEndSec" (input)="normalizeRow(timeline[selectedRowIndex])" (change)="markTimelineDirty()">
          </div>
          <div class="trim-values"><span>IN {{timeline[selectedRowIndex].sourceStartSec|number:'1.1-1'}}s</span><span>OUT {{timeline[selectedRowIndex].sourceEndSec|number:'1.1-1'}}s</span></div>
        </div>

        <div class="editor-grid" *ngIf="selectedRowIndex>=0">
          <div class="editor-title"><strong>Shot {{selectedRowIndex+1}}</strong><span>{{clipName(timeline[selectedRowIndex].clipId)}}</span></div>
          <label>Start (s)<input type="number" min="0" step="0.1" [(ngModel)]="timeline[selectedRowIndex].sourceStartSec" (change)="normalizeRow(timeline[selectedRowIndex])"></label>
          <label>End (s)<input type="number" min="0.1" step="0.1" [(ngModel)]="timeline[selectedRowIndex].sourceEndSec" (change)="normalizeRow(timeline[selectedRowIndex])"></label>
          <label>Speed<select [(ngModel)]="timeline[selectedRowIndex].speed" (change)="normalizeRow(timeline[selectedRowIndex])"><option [ngValue]="0.5">0.5×</option><option [ngValue]="0.75">0.75×</option><option [ngValue]="1">1×</option><option [ngValue]="1.25">1.25×</option><option [ngValue]="1.5">1.5×</option><option [ngValue]="2">2×</option></select></label>
          <label>Volume<input type="range" min="0" max="2" step="0.05" [(ngModel)]="timeline[selectedRowIndex].volume"><span>{{timeline[selectedRowIndex].volume|number:'1.0-2'}}</span></label>
          <label class="check"><input type="checkbox" [(ngModel)]="timeline[selectedRowIndex].muted"> Mute shot</label>
          <label class="check"><input type="checkbox" [(ngModel)]="timeline[selectedRowIndex].locked"> 🔒 Lock shot <span class="muted">(kept as-is on regenerate)</span></label>
          <label>Transition in<select [(ngModel)]="timeline[selectedRowIndex].techniqueIn"><option [ngValue]="null">None</option><option *ngFor="let t of transitionOptions" [ngValue]="t">{{pretty(t)}}</option></select></label>
          <label>Transition out<select [(ngModel)]="timeline[selectedRowIndex].techniqueOut"><option [ngValue]="null">Hard cut</option><option *ngFor="let t of transitionOptions" [ngValue]="t">{{pretty(t)}}</option></select></label>
          <label *ngIf="timeline[selectedRowIndex].techniqueOut">Transition seconds<input type="number" min="0.05" max="2" step="0.05" [(ngModel)]="timeline[selectedRowIndex].transitionSec"></label>
          <div class="row-actions"><button class="btn" (click)="duplicateSelected()">Duplicate</button><button class="btn" (click)="addClipToTimeline()">+ Add shot</button><button class="btn" (click)="resetSelectedTrim()">Reset trim</button><button class="btn danger-btn" (click)="deleteSelected()">Delete shot</button></div>
          <p class="reason" *ngIf="timeline[selectedRowIndex].reason">AI rationale: {{timeline[selectedRowIndex].reason}}</p>
        </div>

        <div class="reorder" *ngIf="timeline.length"><button class="icon" (click)="moveTimeline(-1)" [disabled]="selectedRowIndex<=0">↑</button><button class="icon" (click)="moveTimeline(1)" [disabled]="selectedRowIndex<0||selectedRowIndex===timeline.length-1">↓</button><span class="muted">Reorder shots without changing source files.</span></div>
      </div>
    </section>

    <section class="panel" *ngIf="clips.length">
      <div class="section-head"><h2>Audio</h2><span class="muted">Music is mixed under the rendered edit.</span></div>
      <label class="btn">{{musicName?'Replace music':'Add background music'}}<input type="file" accept="audio/*" hidden (change)="onMusicPicked($event)"></label>
      <span class="music-name" *ngIf="musicName">{{musicName}}</span>
    </section>

    <section class="panel actions">
      <button class="btn primary" [disabled]="busy||!clips.length" (click)="analyze()">1. Analyse → AI Edit → Render final</button>
      <button class="btn" [disabled]="busy||!analysisDone()" (click)="aiEdit()">2. ✨ AI edit only</button>
      <button class="btn" [disabled]="busy||!analysisDone()" (click)="regenerateWithInstruction()">↻ Regenerate with instruction</button>
      <button class="btn" [disabled]="busy||!timeline.length" (click)="preview()">Preview</button>
      <button class="btn" [disabled]="busy||!timeline.length" (click)="render()">Render final</button>
    </section>

    <section class="panel" *ngIf="planRationale">
      <div class="section-head"><h2>Why this edit?</h2></div>
      <pre class="rationale">{{planRationale}}</pre>
    </section>

    <section class="panel" *ngIf="clips.length">
      <div class="section-head"><h2>Version history</h2><button class="btn smallbtn" [disabled]="busy" (click)="toggleVersions()">{{showVersions?'Hide':'Show'}}</button></div>
      <div *ngIf="showVersions">
        <p class="muted small" *ngIf="!versions.length">No versions yet - run AI Edit or save the timeline to create the first one.</p>
        <div class="version-list" *ngFor="let v of versions">
          <div class="version-row">
            <div class="version-main">
              <strong>{{versionLabel(v)}}</strong>
              <span class="muted small">{{v.shotCount}} shot(s) · {{v.createdAt | date:'MMM d, HH:mm'}}</span>
            </div>
            <button class="btn smallbtn" [disabled]="busy" (click)="restoreVersion(v)">Restore</button>
          </div>
          <p class="version-rationale muted small" *ngIf="v.rationale">{{v.rationale}}</p>
        </div>
      </div>
    </section>

    <section class="panel" *ngIf="clips.length">
      <div class="section-head"><h2>Edit assistant</h2><span class="muted small">"remove shot 3", "lock shot 1", "mute shot 2", "make it 20 seconds", or any free-form direction</span></div>
      <div class="chat-log" *ngIf="chatLog.length">
        <p *ngFor="let m of chatLog" [class]="'chat-'+m.role">{{m.text}}</p>
      </div>
      <div class="chat-input-row">
        <input type="text" [(ngModel)]="chatInput" placeholder="Tell the editor what to change…" (keydown.enter)="sendChatCommand()">
        <button class="btn primary" [disabled]="!chatInput.trim()" (click)="sendChatCommand()">Send</button>
      </div>
    </section>

    <section class="panel" *ngIf="job">
      <div class="prog-head"><strong>{{job.stage||'Working'}}</strong><span>{{job.percent}}%</span></div><div class="bar"><div class="fill" [style.width.%]="job.percent"></div></div><p class="muted" *ngIf="job.detail">{{job.detail}}</p><p class="error" *ngIf="job.error">{{job.error}}</p>
    </section>

    <section class="panel result" *ngIf="resultUrl">
      <div class="section-head"><h2>{{resultKind==='FINAL'?'Final video':'Preview ready'}}</h2><a class="btn primary" [href]="downloadUrl" download>Download {{resultKind==='FINAL'?'MP4':'preview'}}</a></div>
      <video controls [src]="resultUrl" class="player"></video>
    </section>
  `,
  styles: [`
    .page-head,.section-head{display:flex;align-items:center;justify-content:space-between;gap:1rem}.page-head{margin-bottom:1rem}h1{font-family:var(--font-display);margin:0}.muted{color:var(--muted);font-size:.88rem}.small{font-size:.78rem}.state,.count{font-size:.72rem;border:1px solid var(--border);border-radius:999px;padding:.15rem .55rem;color:var(--muted)}
    .panel{background:var(--surface-raised);border:1px solid var(--border);border-radius:var(--radius);padding:1rem;margin-bottom:1rem}.notice,.error{color:var(--danger);font-size:.88rem}.dropzone{border:2px dashed var(--border);border-radius:var(--radius);padding:2rem;text-align:center}.dropzone.dragging{border-color:var(--accent)}.drop-title{font-family:var(--font-display);font-size:1.15rem}.btn{display:inline-block;cursor:pointer;border:1px solid var(--border);background:var(--surface);color:var(--text);border-radius:8px;padding:.55rem .9rem;font:inherit;text-decoration:none}.btn:hover{border-color:var(--accent)}.btn:disabled{opacity:.45;cursor:not-allowed}.primary{background:var(--accent);color:var(--accent-ink);border-color:var(--accent);font-weight:600}.smallbtn{padding:.4rem .65rem;font-size:.8rem}
    .source-list{display:grid;gap:.45rem}.source-row{display:flex;align-items:center;gap:.65rem;padding:.45rem;border:1px solid var(--border);border-radius:8px;background:var(--surface);cursor:pointer}.source-row.selected{border-color:var(--accent)}.source-thumb{width:70px;height:42px;background:#111;border-radius:5px;overflow:hidden;position:relative;display:grid;place-items:center}.source-thumb img{width:100%;height:100%;object-fit:cover}.source-thumb span{position:absolute;left:4px;bottom:3px;background:#000a;padding:1px 4px;border-radius:3px}.source-main{flex:1;min-width:0}.clip-name{width:100%;background:transparent;color:inherit;border:0;border-bottom:1px solid transparent;font:inherit}.clip-name:focus{outline:0;border-bottom-color:var(--accent)}.clip-meta{display:flex;gap:.65rem;flex-wrap:wrap;color:var(--muted);font-size:.74rem}.clip-actions{display:flex;gap:.2rem}.icon{cursor:pointer;background:transparent;border:1px solid var(--border);color:var(--muted);border-radius:6px;width:1.8rem;height:1.8rem}.icon:disabled{opacity:.3}.danger:hover{color:var(--danger);border-color:var(--danger)}
    .card-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(135px,1fr));gap:.5rem;margin-bottom:1rem}.card{display:flex;flex-direction:column;gap:.15rem;text-align:left;background:var(--surface);border:1px solid var(--border);border-radius:8px;padding:.65rem;color:inherit;cursor:pointer}.card.selected{border-color:var(--accent);box-shadow:inset 0 0 0 1px var(--accent)}.ratio{font-size:.7rem;color:var(--teal)}.blurb{font-size:.73rem;color:var(--muted)}.settings{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:.7rem}.settings label,.editor-grid label,.transport label{font-size:.78rem;color:var(--muted)}select,textarea,input[type=number]{width:100%;margin-top:.25rem;background:var(--surface);color:var(--text);border:1px solid var(--border);border-radius:7px;padding:.4rem;font:inherit}.custom{display:block;margin-top:.8rem;color:var(--muted);font-size:.8rem}.style-hint{margin-top:.5rem;color:var(--muted);font-size:.74rem}.rationale{white-space:pre-wrap;font:inherit;color:var(--text);background:var(--surface);border:1px solid var(--border);border-radius:8px;padding:.7rem;margin:0}.version-list{border-top:1px solid var(--border);padding-top:.5rem;margin-top:.5rem}.version-row{display:flex;justify-content:space-between;align-items:center;gap:.6rem}.version-main{display:flex;flex-direction:column;gap:.1rem}.version-rationale{margin:.25rem 0 .5rem;white-space:pre-wrap}.chat-log{max-height:220px;overflow-y:auto;display:flex;flex-direction:column;gap:.4rem;margin-bottom:.7rem}.chat-log p{margin:0;padding:.5rem .7rem;border-radius:8px;font-size:.85rem}.chat-user{align-self:flex-end;background:var(--accent);color:var(--accent-ink);max-width:80%}.chat-system{align-self:flex-start;background:var(--surface);border:1px solid var(--border);max-width:80%}.chat-input-row{display:flex;gap:.5rem}.chat-input-row input{flex:1;background:var(--surface);color:var(--text);border:1px solid var(--border);border-radius:7px;padding:.5rem .7rem;font:inherit}.options{display:flex;gap:.8rem;flex-wrap:wrap;margin-top:.8rem}.options label{font-size:.8rem}.disabled{opacity:.5}.pending{font-size:.68rem}.workspace{display:grid;grid-template-columns:minmax(280px,.8fr) minmax(420px,1.5fr);gap:1rem}.workspace .panel{margin:0}.player{width:100%;max-height:55vh;background:#000;border-radius:8px}.monitor-empty,.empty-timeline{padding:3rem 1rem;text-align:center;color:var(--muted);border:1px dashed var(--border);border-radius:8px}.transport{display:flex;gap:.5rem;align-items:end;margin-top:.7rem}.transport label{flex:1}.timeline-track{display:flex;gap:3px;min-height:68px;background:#111;border-radius:8px;padding:5px;overflow:auto}.tl-block{min-width:72px;border:1px solid var(--border);border-radius:6px;background:var(--surface);color:inherit;padding:.45rem;text-align:left;cursor:pointer;display:flex;flex-direction:column;justify-content:space-between}.tl-block.selected{border-color:var(--accent);box-shadow:inset 0 0 0 1px var(--accent)}.tl-block.locked{border-color:#c9a227}.tl-block small{color:var(--muted)}.editor-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:.65rem;margin-top:.8rem;padding-top:.8rem;border-top:1px solid var(--border)}.editor-title{grid-column:1/-1;display:flex;gap:.6rem}.editor-title span{color:var(--muted)}.check{display:flex;align-items:center;gap:.35rem}.row-actions{grid-column:1/-1;display:flex;gap:.4rem;flex-wrap:wrap}.danger-btn{color:var(--danger)}.reason{grid-column:1/-1;color:var(--muted);font-size:.78rem}.reorder{display:flex;align-items:center;gap:.35rem;margin-top:.7rem}.timeline-actions{display:flex;gap:.3rem}.music-name{margin-left:.6rem;color:var(--muted);font-size:.8rem}.actions{display:flex;gap:.5rem;flex-wrap:wrap}.prog-head{display:flex;justify-content:space-between}.bar{height:6px;background:var(--surface);border-radius:9px;overflow:hidden;margin-top:.4rem}.fill{height:100%;background:var(--accent);transition:width .2s}.result{margin-top:1rem}
    .source-row[draggable="true"]{cursor:grab}.source-row[draggable="true"]:active{cursor:grabbing}.timeline-track{align-items:stretch;min-height:92px;padding:7px;gap:2px}.tl-block{position:relative;min-width:110px;min-height:78px}.tl-block[draggable="true"]{cursor:grab}.tl-block[draggable="true"]:active{cursor:grabbing}.tl-index{font-size:.7rem;color:var(--accent)}.drop-marker{width:4px;border-radius:4px;transition:.1s;background:transparent}.drop-marker.active{background:var(--accent);width:7px}.drop-tail{width:22px;min-width:22px;border:1px dashed var(--border);border-radius:5px;display:grid;place-items:center;color:var(--muted)}.drop-tail.active{border-color:var(--accent);color:var(--accent)}.timeline-hint{font-size:.72rem;color:var(--muted);margin-top:.45rem}.timeline-drop-empty{margin-top:.5rem;min-height:92px;border:2px dashed var(--border);border-radius:8px;display:grid;place-items:center;color:var(--muted)}.timeline-drop-empty:hover{border-color:var(--accent);color:var(--text)}.trim-panel{margin-top:.8rem;padding:.75rem;border:1px solid var(--border);border-radius:8px;background:var(--surface)}.trim-head,.trim-values{display:flex;justify-content:space-between;gap:1rem;font-size:.76rem;color:var(--muted)}.trim-head strong{color:var(--text)}.trim-range{position:relative;height:34px;margin:.55rem 0}.trim-track{position:absolute;left:0;right:0;top:15px;height:5px;border-radius:5px;background:var(--border)}.trim-range input[type=range]{position:absolute;left:0;right:0;top:4px;width:100%;margin:0;padding:0;background:transparent;pointer-events:none;border:0}.trim-range input[type=range]::-webkit-slider-thumb{pointer-events:auto}.trim-range input[type=range]::-moz-range-thumb{pointer-events:auto}.trim-range input[type=range]::-webkit-slider-runnable-track{background:transparent}.trim-range input[type=range]::-moz-range-track{background:transparent}.trim-values{font-size:.7rem}.editor-grid{grid-template-columns:repeat(4,1fr)}
    @media(max-width:900px){.workspace{grid-template-columns:1fr}.editor-grid{grid-template-columns:1fr 1fr}} @media(max-width:600px){.editor-grid{grid-template-columns:1fr}.page-head{align-items:flex-start;flex-direction:column}}
  `]
})
export class VideoEditorComponent implements OnInit, OnDestroy {
  caps?: VideoEditorCapabilities; project?: VideoEditorProject; clips: VideoClip[]=[]; timeline: VideoTimelineClip[]=[];
  Math=Math; dropIndex=-1; private draggedTimelineIndex=-1; private draggedSourceClipId=''; timelineDirty=false;
  dragging=false; uploading=false; uploadingCount=0; uploadError=''; loadError=''; busy=false; job?:{stage:string;percent:number;detail?:string;error?:string};
  resultUrl=''; downloadUrl=''; resultKind=''; musicName=''; sourceUrl=''; selectedClipId=''; selectedRowIndex=-1; splitTime=0; planRationale='';
  versions:VideoEditorVersion[]=[]; showVersions=false;
  chatLog:{role:'user'|'system';text:string}[]=[]; chatInput='';
  private stream?:EventSource; private history:TimelineDraft[][]=[]; redoStack:TimelineDraft[][]=[];
  readonly transitionOptions=['cross_dissolve','whip_pan','dip_to_black','slide_left','hard_cut'];
  readonly optionList=[
    {key:'smartCuts',label:'Smart cuts',stage:'planning'},{key:'beatSync',label:'Beat sync (coming soon)',stage:'unsupported'},
    {key:'smartTransitions',label:'Smart transitions',stage:'planning'},{key:'autoCaptions',label:'Auto captions (coming soon)',stage:'unsupported'},
    {key:'audioEnhancement',label:'Audio enhancement',stage:'render'},{key:'smartReframing',label:'Smart reframing',stage:'render'}];
  form:any={name:'Untitled edit',category:'REEL',editingStyle:'TRENDING_REEL',intensity:'DYNAMIC',aspectRatio:'VERTICAL_9_16',targetDurationSec:null,customInstructions:'',smartCuts:true,beatSync:true,smartTransitions:true,autoCaptions:false,audioEnhancement:true,smartReframing:true};
  readonly defaultIntensityLabels:Record<string,string> = {SUBTLE:'Subtle',BALANCED:'Balanced',DYNAMIC:'Dynamic',AGGRESSIVE:'Aggressive'};
  readonly categories:CategoryCard[]=[
    {id:'REEL',label:'Reel',ratio:'9:16',blurb:'Fast, engaging, social-first',
      durations:[15,30,45,60,90], aspectRatios:['VERTICAL_9_16','SQUARE_1_1','PORTRAIT_4_5'],
      intensityLabels:{SUBTLE:'Minimal',BALANCED:'Natural',DYNAMIC:'Dynamic',AGGRESSIVE:'Viral'}},
    {id:'SHORT',label:'Short',ratio:'9:16',blurb:'YouTube Shorts',
      durations:[15,30,45,60],aspectRatios:['VERTICAL_9_16','SQUARE_1_1'],
      intensityLabels:{SUBTLE:'Minimal',BALANCED:'Natural',DYNAMIC:'Dynamic',AGGRESSIVE:'Viral'}},
    {id:'YOUTUBE',label:'YouTube',ratio:'16:9',blurb:'Long-form storytelling',
      durations:[60,120,180,300,600],aspectRatios:['LANDSCAPE_16_9'],
      intensityLabels:this.defaultIntensityLabels},
    {id:'KIDS_STORY',label:'Kids story',ratio:'9:16 / 16:9',blurb:'Fun and emotional',
      durations:[30,60,90,120],aspectRatios:['VERTICAL_9_16','SQUARE_1_1','LANDSCAPE_16_9'],
      intensityLabels:{SUBTLE:'Gentle',BALANCED:'Playful',DYNAMIC:'Energetic',AGGRESSIVE:'Energetic'}},
    {id:'CINEMATIC',label:'Cinematic',ratio:'16:9',blurb:'Slow and deliberate',
      durations:[30,60,90,120,180],aspectRatios:['LANDSCAPE_16_9','VERTICAL_9_16'],
      intensityLabels:{SUBTLE:'Slow cinema',BALANCED:'Natural',DYNAMIC:'Dramatic',AGGRESSIVE:'Action'}},
    {id:'BIRTHDAY',label:'Birthday',ratio:'9:16',blurb:'Celebration',
      durations:[15,30,45,60,90],aspectRatios:['VERTICAL_9_16','SQUARE_1_1'],
      intensityLabels:{SUBTLE:'Calm',BALANCED:'Natural',DYNAMIC:'Festive',AGGRESSIVE:'Party'}},
    {id:'TRAVEL',label:'Travel',ratio:'9:16 / 16:9',blurb:'Montage',
      durations:[30,60,90,120],aspectRatios:['VERTICAL_9_16','LANDSCAPE_16_9','SQUARE_1_1'],
      intensityLabels:this.defaultIntensityLabels},
    {id:'VLOG',label:'Vlog',ratio:'9:16 / 16:9',blurb:'Natural dialogue',
      durations:[60,120,180,300],aspectRatios:['LANDSCAPE_16_9','VERTICAL_9_16'],
      intensityLabels:this.defaultIntensityLabels},
    {id:'PROMO',label:'Promo',ratio:'9:16 / 16:9',blurb:'Product-focused',
      durations:[15,30,45,60],aspectRatios:['VERTICAL_9_16','LANDSCAPE_16_9','SQUARE_1_1','PORTRAIT_4_5'],
      intensityLabels:this.defaultIntensityLabels},
    {id:'MUSIC_VIDEO',label:'Music video',ratio:'9:16 / 16:9',blurb:'Music-led',
      durations:[15,30,45,60],aspectRatios:['VERTICAL_9_16','LANDSCAPE_16_9','SQUARE_1_1'],
      intensityLabels:{SUBTLE:'Chill',BALANCED:'Natural',DYNAMIC:'Beat-driven',AGGRESSIVE:'High-energy'}},
    {id:'CUSTOM',label:'Custom',ratio:'Any',blurb:'Describe it yourself',
      durations:[15,30,45,60,90,120],aspectRatios:['VERTICAL_9_16','LANDSCAPE_16_9','SQUARE_1_1','PORTRAIT_4_5'],
      intensityLabels:this.defaultIntensityLabels}];
  constructor(private api:ApiService){}
  ngOnInit(){this.api.videoEditorCapabilities().subscribe({next:c=>this.caps=c,error:()=>this.loadError='Could not reach the video editor API.'});}
  ngOnDestroy(){this.closeStream();}
  selectCategory(id:string){
    this.form.category=id;
    const cat=this.categories.find(c=>c.id===id);
    switch(id){
      case'KIDS_STORY':this.form.editingStyle='KIDS_STORY';this.form.intensity='BALANCED';break;
      case'CINEMATIC':case'YOUTUBE':this.form.editingStyle='CINEMATIC';this.form.aspectRatio='LANDSCAPE_16_9';break;
      case'VLOG':this.form.editingStyle='VLOG';break;
      case'BIRTHDAY':this.form.editingStyle='BIRTHDAY';this.form.intensity='DYNAMIC';break;
      case'MUSIC_VIDEO':this.form.editingStyle='MUSIC_VIDEO';this.form.intensity='DYNAMIC';break;
      case'CUSTOM':this.form.editingStyle='CUSTOM';break;
      default:this.form.editingStyle='TRENDING_REEL';this.form.intensity='DYNAMIC';this.form.aspectRatio='VERTICAL_9_16';
    }
    if(cat){
      // Keep the current aspect ratio/duration only if they still make sense
      // for the newly selected style; otherwise fall back to that style's
      // first (most typical) option rather than silently leaving an
      // aspect ratio or duration on screen that this style doesn't offer.
      if(!cat.aspectRatios.includes(this.form.aspectRatio)){this.form.aspectRatio=cat.aspectRatios[0];}
      if(this.form.targetDurationSec!=null && !cat.durations.includes(this.form.targetDurationSec)){this.form.targetDurationSec=null;}
    }
  }
  intensityLabel(id:string){const cat=this.categories.find(c=>c.id===this.form.category);return cat?.intensityLabels[id] ?? this.pretty(id);}
  aspectRatioLabel(id:string){const r=this.caps?.aspectRatios?.find(x=>x.id===id);return r?`${this.pretty(id)} · ${r.width}×${r.height}`:this.pretty(id);}
  currentCategory(){return this.categories.find(c=>c.id===this.form.category);}
  isOptionAvailable(key:string){if(key==='beatSync'||key==='autoCaptions')return false;const stage=this.optionList.find(o=>o.key===key)?.stage;return !!(stage&&(this.caps?.implemented as any)?.[stage]);}
  onDragOver(e:DragEvent){e.preventDefault();this.dragging=true} onDrop(e:DragEvent){e.preventDefault();this.dragging=false;const f=Array.from(e.dataTransfer?.files??[]);if(f.length)this.upload(f)}
  onFilePicked(e:Event){const i=e.target as HTMLInputElement;const f=Array.from(i.files??[]);if(f.length)this.upload(f);i.value='';}
  private upload(files:File[]){this.uploadError='';const room=(this.caps?.maxClips??20)-this.clips.length;if(room<=0){this.uploadError='Maximum clip count reached.';return}const accepted=files.slice(0,room);this.ensureProject().then(id=>{this.uploading=true;this.uploadingCount=accepted.length;this.api.uploadVideoClips(id,accepted).subscribe({next:a=>{this.clips=[...this.clips,...a];this.api.completeVideoUpload(id).subscribe({next:p=>{this.project=p;this.uploading=false},error:err=>{this.uploading=false;this.uploadError=err?.error?.error??'Could not finalise upload.'}})},error:err=>{this.uploading=false;this.uploadError=err?.error?.error??'Upload failed.'}})}).catch(()=>this.uploadError='Could not create the editor project.');}
  private ensureProject():Promise<string>{if(this.project)return Promise.resolve(this.project.id);return new Promise((resolve,reject)=>this.api.createVideoEditorProject(this.form).subscribe({next:p=>{this.project=p;resolve(p.id)},error:reject}));}
  thumbnailUrl(id:string){return this.project?this.api.videoClipThumbnailUrl(this.project.id,id):'';}
  sourceVideo(id:string){return this.project?this.api.sourceVideoUrl(this.project.id,id):'';}
  selectSource(c:VideoClip){this.selectedClipId=c.id;this.sourceUrl=this.sourceVideo(c.id);const idx=this.timeline.findIndex(r=>r.clipId===c.id);if(idx>=0)this.selectRow(idx);}
  onSourceDragStart(e:DragEvent,c:VideoClip){this.draggedSourceClipId=c.id;this.draggedTimelineIndex=-1;if(e.dataTransfer){e.dataTransfer.effectAllowed='copy';e.dataTransfer.setData('text/plain','source:'+c.id)}}
  onTimelineDragStart(e:DragEvent,i:number){this.draggedTimelineIndex=i;this.draggedSourceClipId='';if(e.dataTransfer){e.dataTransfer.effectAllowed='move';e.dataTransfer.setData('text/plain','timeline:'+i)}}
  allowTimelineDrop(e:DragEvent){e.preventDefault();if(e.dataTransfer)e.dataTransfer.dropEffect=this.draggedTimelineIndex>=0?'move':'copy'}
  setDropIndex(e:DragEvent,i:number){e.preventDefault();this.dropIndex=i}
  dropOnTimeline(e:DragEvent,index:number){e.preventDefault();const data=e.dataTransfer?.getData('text/plain')??'';this.dropIndex=-1;
    if(data.startsWith('source:')){const id=data.substring(7);const c=this.clips.find(x=>x.id===id);if(c?.durationSec!=null){this.commit(()=>{const row:VideoTimelineClip={id:crypto.randomUUID(),clipId:c.id,sortOrder:index,sourceStartSec:0,sourceEndSec:c.durationSec!,techniqueIn:null,techniqueOut:null,transitionSec:null,speed:1,volume:1,muted:false,locked:false,reason:null,outputDurationSec:c.durationSec!};this.timeline.splice(index,0,row);this.timeline.forEach((x,n)=>x.sortOrder=n)});this.selectedRowIndex=index;this.recalcSelection()}}
    else if(data.startsWith('timeline:')){const from=Number(data.substring(9));if(Number.isInteger(from)&&from>=0&&from<this.timeline.length){let to=Math.max(0,Math.min(index,this.timeline.length));if(from<to)to--;if(from!==to){this.commit(()=>{const [row]=this.timeline.splice(from,1);this.timeline.splice(to,0,row);this.timeline.forEach((x,n)=>x.sortOrder=n)});this.selectedRowIndex=to;this.recalcSelection()}}}
    this.draggedTimelineIndex=-1;this.draggedSourceClipId='';
  }
  trimDuration(){const r=this.timeline[this.selectedRowIndex];return r?Math.max(0,r.sourceEndSec-r.sourceStartSec)/Math.max(.01,r.speed):0}
  markTimelineDirty(){this.timelineDirty=true}
  resetSelectedTrim(){const r=this.timeline[this.selectedRowIndex];const c=r&&this.clips.find(x=>x.id===r.clipId);if(!r||c?.durationSec==null)return;this.commit(()=>{r.sourceStartSec=0;r.sourceEndSec=c.durationSec!;r.outputDurationSec=c.durationSec!/Math.max(.01,r.speed)});this.timelineDirty=true}

  remove(c:VideoClip){if(!this.project)return;this.api.removeVideoClip(this.project.id,c.id).subscribe({next:()=>{this.clips=this.clips.filter(x=>x.id!==c.id);this.timeline=this.timeline.filter(x=>x.clipId!==c.id);if(this.selectedClipId===c.id){this.selectedClipId='';this.sourceUrl='';this.selectedRowIndex=-1}},error:err=>this.uploadError=err?.error?.error??'Could not remove clip.'});}
  move(i:number,d:number){const t=i+d;if(!this.project||t<0||t>=this.clips.length)return;const n=[...this.clips];[n[i],n[t]]=[n[t],n[i]];this.clips=n;this.api.reorderVideoClips(this.project.id,n.map(x=>x.id)).subscribe({next:u=>this.clips=u,error:()=>this.reload()});}
  rename(c:VideoClip,name:string){if(!this.project||!name.trim()||name===c.displayName)return;this.api.renameVideoClip(this.project.id,c.id,name.trim()).subscribe({next:u=>c.displayName=u.displayName,error:()=>this.reload()});}
  saveSettings(){if(!this.project)return;this.api.updateVideoEditorProject(this.project.id,this.form).subscribe({next:p=>this.project=p,error:e=>this.uploadError=e?.error?.error??'Could not save settings.'});}
  private saveSettingsThen(next:()=>void){if(!this.project){return}this.api.updateVideoEditorProject(this.project.id,this.form).subscribe({next:p=>{this.project=p;next()},error:e=>{this.busy=false;this.uploadError=e?.error?.error??'Could not save settings.'}})}
  private reload(){if(!this.project)return;this.api.getVideoEditorProject(this.project.id).subscribe({next:d=>{this.project=d.project;this.clips=d.clips;this.timeline=d.timeline}})}
  analysisDone(){return this.clips.length>0&&this.clips.every(c=>c.analyzed)}
  /** One-click production flow: Analyse → AI Director → build timeline → final MP4.
   *  The user should not have to manually chain four buttons just to get the
   *  edited video. Each stage waits for the previous job to finish before the
   *  next stage starts, and the final render is shown automatically. */
  analyze(){
    if(!this.project)return;
    const projectId=this.project.id;
    this.resultUrl='';
    this.downloadUrl='';
    this.resultKind='';
    this.run(()=>this.api.analyzeVideoProject(projectId),()=>{
      this.reloadThen(()=>this.startAiEditAndRender(projectId));
    });
  }
  private startAiEditAndRender(projectId:string){
    if(!this.project || this.project.id!==projectId)return;
    this.saveSettingsThen(()=>this.run(()=>this.api.aiEditVideoProject(projectId,true),()=>{
      this.fetchRationale();
      this.loadTimelineThen(()=>{
        if(!this.timeline.length){
          this.busy=false;
          this.uploadError='AI editing finished but produced an empty timeline. Check the analysis results.';
          return;
        }
        this.render();
      });
    }));
  }
  aiEdit(){
    if(!this.project)return;
    this.saveSettingsThen(()=>this.run(()=>this.api.aiEditVideoProject(this.project!.id,true),()=>{
      this.fetchRationale();
      this.loadTimelineThen(()=>{});
    }));
  }
  /** Re-runs AI edit + render using whatever is currently in the "AI edit
   *  instruction" box, without repeating the (already-done) analysis step.
   *  The instruction is a plain field on the project that the LLM prompt
   *  already includes ("Extra direction: ..."), so this is a real re-plan
   *  driven by the instruction, not a cosmetic relabel of the AI edit button. */
  regenerateWithInstruction(){
    if(!this.project || !this.analysisDone())return;
    this.resultUrl='';this.downloadUrl='';this.resultKind='';
    this.startAiEditAndRender(this.project.id);
  }
  private fetchRationale(){
    if(!this.project)return;
    this.api.getVideoEditorProject(this.project.id).subscribe({next:d=>this.planRationale=d.rationale??''});
    if(this.showVersions)this.loadVersions();
  }
  toggleVersions(){
    this.showVersions=!this.showVersions;
    if(this.showVersions)this.loadVersions();
  }
  private loadVersions(){
    if(!this.project)return;
    this.api.videoEditorVersions(this.project.id).subscribe({next:v=>this.versions=v,error:()=>{this.uploadError='Could not load version history.'}});
  }
  versionLabel(v:VideoEditorVersion){
    if(v.planner==='MANUAL')return 'Manual edit';
    if(v.planner==='RULE_ENGINE_LLM')return 'AI edit (with story director)';
    return 'AI edit (rule engine)';
  }
  /** Restoring loads that version's shots back onto the live timeline - it
   *  does not delete anything, so a restore-then-regret is always safe:
   *  restoring a DIFFERENT version afterwards just adds another entry. */
  restoreVersion(v:VideoEditorVersion){
    if(!this.project)return;
    this.busy=true;this.uploadError='';
    this.api.restoreVideoEditorVersion(this.project.id,v.id).subscribe({
      next:r=>{this.busy=false;this.timeline=r;this.history=[];this.redoStack=[];this.selectedRowIndex=this.timeline.length?0:-1;if(this.selectedRowIndex>=0)this.selectRow(0);this.loadVersions();},
      error:e=>{this.busy=false;this.uploadError=e?.error?.error??'Could not restore that version.'}
    });
  }
  preview(){if(!this.project||!this.timeline.length)return;this.saveSettingsThen(()=>this.saveTimelineThen(()=>this.run(()=>this.api.previewVideoProject(this.project!.id),id=>this.showResult(id,'PREVIEW'))))}
  render(){if(!this.project||!this.timeline.length)return;this.saveSettingsThen(()=>this.saveTimelineThen(()=>this.run(()=>this.api.renderVideoProject(this.project!.id),id=>this.showResult(id,'FINAL'))))}
  private run(start:()=>any,done:(id:string)=>void){if(!this.project)return;this.busy=true;this.uploadError='';this.job={stage:'Starting',percent:0};start().subscribe({next:(r:any)=>this.follow(r.jobId,done),error:(e:any)=>{this.busy=false;this.job=undefined;this.uploadError=e?.error?.error??'Could not start that step.'}})}
  private follow(jobId:string,done:(id:string)=>void){
    this.closeStream();
    const s=this.api.videoProgressStream(jobId);
    this.stream=s;
    let finished=false;
    const complete=(id:string)=>{if(finished)return;finished=true;this.busy=false;this.closeStream();done(id);};
    const fail=(message:string)=>{if(finished)return;finished=true;this.busy=false;this.closeStream();this.job={stage:'Failed',percent:0,error:message};};
    const handle=(event:any)=>{
      try{
        const d=JSON.parse(event.data);
        this.job={stage:d.stage??'Working',percent:d.percent??0,detail:d.detail,error:d.error};
        if(d.error){fail(d.error);return;}
        if((d.stage??'').toLowerCase()==='complete' || d.percent>=100){
          complete(jobId);
        }
      }catch{}
    };
    s.addEventListener('progress',handle);
    s.onmessage=handle;
    s.onerror=()=>{
      this.closeStream();
      this.pollJobUntilFinished(jobId,complete,fail);
    };
  }
  private pollJobUntilFinished(jobId:string,done:(id:string)=>void,fail:(message:string)=>void,attempt=0){
    this.api.videoJob(jobId).subscribe({
      next:j=>{
        if(j.status==='FAILED'||j.status==='CANCELLED'){fail(j.errorMessage??'The video editing job failed.');return;}
        if(j.status==='COMPLETED'){this.job={stage:'Complete',percent:100};done(jobId);return;}
        this.job={stage:j.stage??'Working',percent:j.progressPercent??0};
        if(attempt>=720){fail('The editing job did not finish within the expected time. Check the backend logs.');return;}
        window.setTimeout(()=>this.pollJobUntilFinished(jobId,done,fail,attempt+1),2000);
      },
      error:()=>{
        if(attempt>=720){fail('Could not read the editing job status.');return;}
        window.setTimeout(()=>this.pollJobUntilFinished(jobId,done,fail,attempt+1),2000);
      }
    });
  }
  private closeStream(){this.stream?.close();this.stream=undefined;}
  private reloadThen(done:()=>void){
    if(!this.project){done();return;}
    this.api.getVideoEditorProject(this.project.id).subscribe({
      next:d=>{this.project=d.project;this.clips=d.clips;this.timeline=d.timeline;this.planRationale=d.rationale??this.planRationale;done();},
      error:()=>{this.reload();done();}
    });
  }
  private loadTimelineThen(done:()=>void){
    if(!this.project){done();return;}
    this.api.videoTimeline(this.project.id).subscribe({
      next:r=>{this.timeline=r;this.history=[];this.redoStack=[];this.selectedRowIndex=this.timeline.length?0:-1;if(this.selectedRowIndex>=0)this.selectRow(0);done();},
      error:()=>{this.uploadError='AI edit completed, but the generated timeline could not be loaded.';done();}
    });
  }
  private loadTimeline(){this.loadTimelineThen(()=>{});}
  private showResult(id:string,k:string){if(!this.project)return;this.resultKind=k;this.resultUrl=this.api.renderedVideoUrl(this.project.id,id);this.downloadUrl=this.api.renderedVideoDownloadUrl(this.project.id,id);}
  private timelinePayload(){return this.timeline.map(r=>({clipId:r.clipId,sourceStartSec:r.sourceStartSec,sourceEndSec:r.sourceEndSec,techniqueIn:r.techniqueIn,techniqueOut:r.techniqueOut,transitionSec:r.transitionSec,speed:r.speed,volume:r.volume,muted:r.muted,locked:r.locked}));}
  private saveTimelineThen(done:()=>void){if(!this.project||!this.timeline.length){done();return}this.api.replaceVideoTimeline(this.project.id,this.timelinePayload()).subscribe({next:r=>{this.timeline=r;done()},error:e=>{this.busy=false;this.uploadError=e?.error?.error??'Could not save the timeline before rendering.'}})}
  saveTimeline(){if(!this.project||!this.timeline.length)return;this.api.replaceVideoTimeline(this.project.id,this.timelinePayload()).subscribe({next:r=>{this.timeline=r;this.history=[];this.redoStack=[];this.timelineDirty=false;this.project!.state='PLAN_READY';if(this.showVersions)this.loadVersions();},error:e=>this.uploadError=e?.error?.error??'Could not save the timeline.'});}
  private snapshot():TimelineDraft[]{return this.timeline.map(r=>({clipId:r.clipId,sourceStartSec:r.sourceStartSec,sourceEndSec:r.sourceEndSec,techniqueIn:r.techniqueIn,techniqueOut:r.techniqueOut,transitionSec:r.transitionSec,speed:r.speed,volume:r.volume,muted:r.muted,locked:r.locked}))}
  private commit(mutator:()=>void){this.history.push(this.snapshot());if(this.history.length>50)this.history.shift();this.redoStack=[];mutator();this.recalcSelection()}
  undo(){const prev=this.history.pop();if(!prev)return;this.redoStack.push(this.snapshot());this.timeline=prev.map((r,i)=>({...r,id:this.timeline[i]?.id??crypto.randomUUID(),sortOrder:i,reason:this.timeline[i]?.reason??null,outputDurationSec:(r.sourceEndSec-r.sourceStartSec)/Math.max(.01,r.speed)}));this.recalcSelection()}
  redo(){const next=this.redoStack.pop();if(!next)return;this.history.push(this.snapshot());this.timeline=next.map((r,i)=>({...r,id:this.timeline[i]?.id??crypto.randomUUID(),sortOrder:i,reason:this.timeline[i]?.reason??null,outputDurationSec:(r.sourceEndSec-r.sourceStartSec)/Math.max(.01,r.speed)}));this.recalcSelection()}
  selectRow(i:number){this.selectedRowIndex=i;const r=this.timeline[i];if(r){this.selectedClipId=r.clipId;this.sourceUrl=this.sourceVideo(r.clipId);this.splitTime=r.sourceStartSec+(r.sourceEndSec-r.sourceStartSec)/2;}}
  get selectedRowDuration(){const r=this.timeline[this.selectedRowIndex];return r?Math.max(.1,r.sourceEndSec-r.sourceStartSec):0}
  get selectedClipMax(){const r=this.timeline[this.selectedRowIndex];const c=r?this.clips.find(x=>x.id===r.clipId):undefined;return Math.max(.05,c?.durationSec??r?.sourceEndSec??0.05)}
  normalizeRow(r:VideoTimelineClip){const c=this.clips.find(x=>x.id===r.clipId);const max=c?.durationSec??r.sourceEndSec;r.sourceStartSec=Math.max(0,Math.min(r.sourceStartSec,max-.05));r.sourceEndSec=Math.max(r.sourceStartSec+.05,Math.min(r.sourceEndSec,max));r.speed=Math.max(.25,Math.min(4,r.speed||1));r.volume=Math.max(0,Math.min(2,r.volume??1));if(r.transitionSec!=null)r.transitionSec=Math.max(.05,Math.min(2,r.transitionSec));r.outputDurationSec=(r.sourceEndSec-r.sourceStartSec)/r.speed;this.timelineDirty=true;}
  addClipToTimeline(){const c=this.clips.find(x=>x.id===this.selectedClipId)||this.clips[0];if(!c||c.durationSec==null)return;this.commit(()=>{this.timeline.push({id:crypto.randomUUID(),clipId:c.id,sortOrder:this.timeline.length,sourceStartSec:0,sourceEndSec:c.durationSec!,techniqueIn:null,techniqueOut:null,transitionSec:null,speed:1,volume:1,muted:false,locked:false,reason:null,outputDurationSec:c.durationSec!})});this.selectRow(this.timeline.length-1)}
  duplicateSelected(){const r=this.timeline[this.selectedRowIndex];if(!r)return;this.commit(()=>{const copy={...r,id:crypto.randomUUID(),sortOrder:this.selectedRowIndex+1};this.timeline.splice(this.selectedRowIndex+1,0,copy);this.timeline.forEach((x,i)=>x.sortOrder=i)});this.selectedRowIndex++;}
  deleteSelected(){if(this.selectedRowIndex<0)return;this.commit(()=>{this.timeline.splice(this.selectedRowIndex,1);this.timeline.forEach((x,i)=>x.sortOrder=i)});this.selectedRowIndex=Math.min(this.selectedRowIndex,this.timeline.length-1);this.recalcSelection()}
  splitSelected(){const i=this.selectedRowIndex,r=this.timeline[i];if(!r)return;const t=Math.max(r.sourceStartSec+.05,Math.min(this.splitTime,r.sourceEndSec-.05));if(t<=r.sourceStartSec||t>=r.sourceEndSec)return;this.commit(()=>{const a={...r,id:crypto.randomUUID(),sourceEndSec:t,outputDurationSec:(t-r.sourceStartSec)/r.speed};const b={...r,id:crypto.randomUUID(),sourceStartSec:t,techniqueIn:null,outputDurationSec:(r.sourceEndSec-t)/r.speed};this.timeline.splice(i,1,a,b);this.timeline.forEach((x,n)=>x.sortOrder=n)});this.selectRow(i+1)}
  moveTimeline(d:number){const i=this.selectedRowIndex,t=i+d;if(i<0||t<0||t>=this.timeline.length)return;this.commit(()=>{[this.timeline[i],this.timeline[t]]=[this.timeline[t],this.timeline[i]];this.timeline.forEach((x,n)=>x.sortOrder=n)});this.selectedRowIndex=t}
  recalcSelection(){if(this.selectedRowIndex>=this.timeline.length)this.selectedRowIndex=this.timeline.length-1;if(this.selectedRowIndex>=0)this.selectRow(this.selectedRowIndex)}

  // ---- natural-language command box ---------------------------------------
  //
  // A small, deterministic parser for a fixed set of edit commands - NOT an
  // LLM chat. Recognised patterns (remove/lock/mute/move a numbered shot,
  // set a target duration) execute directly against the same timeline
  // methods the buttons use, so they're instant and 100% reliable - no model
  // call, nothing to hallucinate. Anything that doesn't match a known
  // pattern is treated as free-form direction and handed to the real
  // LLM-backed regenerate-with-instruction flow (already wired above),
  // never fabricated or silently ignored.
  sendChatCommand(){
    const text=this.chatInput.trim();
    if(!text||!this.project)return;
    this.chatLog.push({role:'user',text});
    this.chatInput='';
    const summary=this.interpretCommand(text);
    this.chatLog.push({role:'system',text:summary});
  }
  private interpretCommand(raw:string):string {
    const text=raw.toLowerCase();
    let m:RegExpMatchArray|null;

    if((m=text.match(/\b(?:remove|delete)\s+(?:shot|clip)\s*#?(\d+)/))){
      return this.commandOnRow(+m[1],'removed',(i)=>{this.selectRow(i);this.deleteSelected();this.saveTimeline();});
    }
    if((m=text.match(/\bduplicate\s+(?:shot|clip)\s*#?(\d+)/))){
      return this.commandOnRow(+m[1],'duplicated',(i)=>{this.selectRow(i);this.duplicateSelected();this.saveTimeline();});
    }
    if((m=text.match(/\bunlock\s+(?:shot|clip)\s*#?(\d+)/))){
      return this.commandOnRow(+m[1],'unlocked',(i)=>{this.timeline[i].locked=false;this.saveTimeline();});
    }
    if((m=text.match(/\block\s+(?:shot|clip)\s*#?(\d+)/))){
      return this.commandOnRow(+m[1],'locked',(i)=>{this.timeline[i].locked=true;this.saveTimeline();});
    }
    if((m=text.match(/\bunmute\s+(?:shot|clip)\s*#?(\d+)/))){
      return this.commandOnRow(+m[1],'unmuted',(i)=>{this.timeline[i].muted=false;this.saveTimeline();});
    }
    if((m=text.match(/\bmute\s+(?:shot|clip)\s*#?(\d+)/))){
      return this.commandOnRow(+m[1],'muted',(i)=>{this.timeline[i].muted=true;this.saveTimeline();});
    }
    if((m=text.match(/\bmove\s+(?:shot|clip)\s*#?(\d+)\s+(up|down)/))){
      const dir=m[2]==='up'?-1:1;
      return this.commandOnRow(+m[1],`moved ${m[2]}`,(i)=>{this.selectRow(i);this.moveTimeline(dir);this.saveTimeline();});
    }
    if((m=text.match(/\b(\d+)\s*(?:s|sec|secs|seconds)\b/)) && /\b(make|set|duration|long|length)\b/.test(text)){
      const secs=+m[1];
      if(secs>0){
        this.form.targetDurationSec=secs;
        this.regenerateWithInstruction();
        return `Set target duration to ${secs}s and started a re-edit. Watch the progress bar above.`;
      }
    }

    // Fall through: free-form direction for the real AI editor, same path
    // as the "AI edit instruction" box - a genuine re-plan, not a canned reply.
    if(!this.analysisDone()){
      return "That doesn't match a specific shot command, and the clips haven't been analysed yet, so there's nothing for the AI editor to act on. Run Analyse first.";
    }
    this.form.customInstructions=raw;
    this.regenerateWithInstruction();
    return 'Sent as an instruction to the AI editor and started a re-edit. Watch the progress bar above.';
  }
  private commandOnRow(oneBasedIndex:number,verb:string,run:(zeroBasedIndex:number)=>void):string {
    const i=oneBasedIndex-1;
    if(i<0||i>=this.timeline.length){
      return `There's no shot ${oneBasedIndex} - the timeline currently has ${this.timeline.length} shot(s).`;
    }
    run(i);
    return `Shot ${oneBasedIndex} ${verb}.`;
  }
  onMusicPicked(e:Event){const i=e.target as HTMLInputElement;const f=i.files?.[0];i.value='';if(!f||!this.project)return;this.api.uploadVideoMusic(this.project.id,f).subscribe({next:()=>this.musicName=f.name,error:er=>this.uploadError=er?.error?.error??'Could not add music.'})}
  clipName(id:string){return this.clips.find(c=>c.id===id)?.displayName??'clip'}
  totalDuration(){return this.formatDuration(this.timeline.reduce((s,r)=>s+r.outputDurationSec,0))}
  formatDuration(sec:number){const t=Math.round(sec),m=Math.floor(t/60),s=t%60;return `${m}:${s.toString().padStart(2,'0')}`}
  onKeyDown(e:KeyboardEvent){if(e.target instanceof HTMLInputElement||e.target instanceof HTMLTextAreaElement||e.target instanceof HTMLSelectElement)return;if(e.key==='Delete'){e.preventDefault();this.deleteSelected()}else if(e.key==='ArrowLeft'&&e.shiftKey){e.preventDefault();this.moveTimeline(-1)}else if(e.key==='ArrowRight'&&e.shiftKey){e.preventDefault();this.moveTimeline(1)}else if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='z'){e.preventDefault();this.undo()}else if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='y'){e.preventDefault();this.redo()}}
  pretty(v:string){return v.replace(/_/g,' ').toLowerCase().replace(/^./,c=>c.toUpperCase())}
}
