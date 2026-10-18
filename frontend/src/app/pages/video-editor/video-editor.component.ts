import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService, VideoClip, VideoEditorCapabilities, VideoEditorProject, VideoEditorVersion, VideoTimelineClip, VideoMoment, VideoSpeechHit } from '../../services/api.service';

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
  id: string;
  sortOrder: number;
  reason: string | null;
  outputDurationSec: number;
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
      <div class="options">
        <label>Look (filter) <select [(ngModel)]="form.look">
          <option value="auto">Auto — matches the video type</option><option value="none">None (original colours)</option>
          <option *ngFor="let l of looks" [value]="l.id">{{l.label}}</option></select></label>
        <label style="flex:1;min-width:240px">Title text <span class="muted">(shown over the first 3 s of the final video)</span>
          <input [(ngModel)]="form.titleText" maxlength="120" placeholder="e.g. 5 tips for a perfect birthday video"></label>
      </div>
      <div class="options"><span class="muted small">Effects:</span>
        <label *ngFor="let e of effectList"><input type="checkbox" [checked]="hasEffect(e.id)" (change)="toggleEffect(e.id)"> {{e.label}}</label>
      </div>
      <div class="options" *ngIf="form.autoCaptions"><label>Caption look <select [(ngModel)]="captionStyle"><option value="BOLD_REEL">Bold reel (big, word pops)</option><option value="CLEAN">Clean</option><option value="KIDS">Kids (handwritten)</option><option value="CINEMATIC">Cinematic</option><option value="MINIMAL">Minimal</option><option value="STACKED">Stacked words (one per line)</option><option value="LINE_REVEAL">Line reveal (slides up on a box)</option></select></label></div>
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
        <div class="section-head"><h2>Timeline <span class="count">{{timeline.length}} shots · {{totalDuration()}}</span></h2><div class="timeline-actions"><span class="muted small" *ngIf="timelineDirty" role="status">Unsaved changes</span><span class="muted small" *ngIf="!timelineDirty && timeline.length" role="status">Saved</span><button class="btn smallbtn" [disabled]="!timeline.length" (click)="undo()">Undo</button><button class="btn smallbtn" [disabled]="!redoStack.length" (click)="redo()">Redo</button><button class="btn smallbtn" [disabled]="!timeline.length" (click)="saveTimeline()">Save edit</button></div></div>
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
            <input type="range" [min]="0" [max]="selectedClipMax" step="0.05" [(ngModel)]="timeline[selectedRowIndex].sourceStartSec" (focus)="beginRowEdit()" (input)="normalizeRow(timeline[selectedRowIndex])" (change)="finishRowEdit()">
            <input type="range" [min]="0" [max]="selectedClipMax" step="0.05" [(ngModel)]="timeline[selectedRowIndex].sourceEndSec" (focus)="beginRowEdit()" (input)="normalizeRow(timeline[selectedRowIndex])" (change)="finishRowEdit()">
          </div>
          <div class="trim-values"><span>IN {{timeline[selectedRowIndex].sourceStartSec|number:'1.1-1'}}s</span><span>OUT {{timeline[selectedRowIndex].sourceEndSec|number:'1.1-1'}}s</span></div>
        </div>

        <div class="editor-grid" *ngIf="selectedRowIndex>=0">
          <div class="editor-title"><strong>Shot {{selectedRowIndex+1}}</strong><span>{{clipName(timeline[selectedRowIndex].clipId)}}</span></div>
          <label>Start (s)<input type="number" min="0" step="0.1" [(ngModel)]="timeline[selectedRowIndex].sourceStartSec" (focus)="beginRowEdit()" (change)="normalizeRow(timeline[selectedRowIndex]);finishRowEdit()"></label>
          <label>End (s)<input type="number" min="0.1" step="0.1" [(ngModel)]="timeline[selectedRowIndex].sourceEndSec" (focus)="beginRowEdit()" (change)="normalizeRow(timeline[selectedRowIndex]);finishRowEdit()"></label>
          <label>Speed<select [(ngModel)]="timeline[selectedRowIndex].speed" (focus)="beginRowEdit()" (change)="normalizeRow(timeline[selectedRowIndex]);finishRowEdit()"><option [ngValue]="0.5">0.5×</option><option [ngValue]="0.75">0.75×</option><option [ngValue]="1">1×</option><option [ngValue]="1.25">1.25×</option><option [ngValue]="1.5">1.5×</option><option [ngValue]="2">2×</option></select></label>
          <label>Volume<input type="range" min="0" max="2" step="0.05" [(ngModel)]="timeline[selectedRowIndex].volume" (focus)="beginRowEdit()" (ngModelChange)="timelineDirty=true" (change)="finishRowEdit()"><span>{{timeline[selectedRowIndex].volume|number:'1.0-2'}}</span></label>
          <label class="check"><input type="checkbox" [(ngModel)]="timeline[selectedRowIndex].muted" (focus)="beginRowEdit()" (ngModelChange)="timelineDirty=true" (change)="finishRowEdit()"> Mute shot</label>
          <label class="check"><input type="checkbox" [(ngModel)]="timeline[selectedRowIndex].locked" (focus)="beginRowEdit()" (ngModelChange)="timelineDirty=true" (change)="finishRowEdit()"> 🔒 Lock shot <span class="muted">(kept as-is on regenerate)</span></label>
          <label>Transition in<select [(ngModel)]="timeline[selectedRowIndex].techniqueIn" (focus)="beginRowEdit()" (ngModelChange)="timelineDirty=true" (change)="finishRowEdit()"><option [ngValue]="null">None</option><option *ngFor="let t of transitionOptions" [ngValue]="t">{{pretty(t)}}</option></select></label>
          <label>Transition out<select [(ngModel)]="timeline[selectedRowIndex].techniqueOut" (focus)="beginRowEdit()" (ngModelChange)="timelineDirty=true" (change)="finishRowEdit()"><option [ngValue]="null">Hard cut</option><option *ngFor="let t of transitionOptions" [ngValue]="t">{{pretty(t)}}</option></select></label>
          <label *ngIf="timeline[selectedRowIndex].techniqueOut">Transition seconds<input type="number" min="0.05" max="2" step="0.05" [(ngModel)]="timeline[selectedRowIndex].transitionSec" (focus)="beginRowEdit()" (ngModelChange)="timelineDirty=true" (change)="finishRowEdit()"></label>
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

    <section class="panel actions-panel" *ngIf="clips.length">
      <div class="action-intro"><div><span class="eyebrow">EDIT WORKFLOW</span><h2>Make your video</h2><p class="muted">Start with AI, refine the timeline, then preview and export when you're happy.</p></div><span class="job-pill" *ngIf="busy">{{job?.stage || 'Working'}} · {{job?.percent || 0}}%</span></div>
      <div class="action-groups">
        <div class="action-group ai-group"><span class="group-label">AI EDIT</span><button type="button" class="btn primary action-main" [disabled]="busy||!clips.length" (click)="analyze()"><span class="action-icon">✦</span><span><strong>{{busy?'Working…':'Create my edit'}}</strong><small>Analyse clips, build a timeline and render</small></span></button><div class="action-secondary"><button type="button" class="btn" [disabled]="busy||!analysisDone()" (click)="aiEdit()">✧ AI edit timeline</button><button type="button" class="btn" [disabled]="busy||!analysisDone()" (click)="regenerateWithInstruction()">↻ Regenerate</button></div></div>
        <div class="action-group preview-group"><span class="group-label">REVIEW</span><button type="button" class="btn action-main" [disabled]="busy||!timeline.length" (click)="preview()"><span class="action-icon">▶</span><span><strong>Preview video</strong><small>Check pacing, cuts and audio</small></span></button><div class="action-secondary"><button type="button" class="btn" [disabled]="busy||!timeline.length" (click)="saveTimeline()">Save timeline</button></div></div>
        <div class="action-group export-group"><span class="group-label">EXPORT</span><button type="button" class="btn primary action-main" [disabled]="busy||!timeline.length" (click)="render()"><span class="action-icon">⬇</span><span><strong>Render final video</strong><small>Export your finished MP4</small></span></button><div class="action-secondary"><a class="btn" *ngIf="project&&timeline.length" [href]="exportUrl('edl')" title="CMX3600 EDL for Premiere, DaVinci Resolve, Avid">EDL</a><a class="btn" *ngIf="project&&timeline.length" [href]="exportUrl('fcpxml')" title="FCPXML for Final Cut Pro, DaVinci Resolve and Premiere">FCPXML</a></div></div>
      </div>
      <div class="job-status" *ngIf="busy||job"><div class="job-status-head"><strong>{{job?.stage || 'Ready'}}</strong><span>{{job?.percent || 0}}%</span></div><div class="bar"><div class="fill" [style.width.%]="job?.percent || 0"></div></div><p class="muted small" *ngIf="job?.detail">{{job?.detail}}</p><p class="error small" *ngIf="job?.error">{{job?.error}}</p></div>
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
      <div class="section-head"><h2>Finish</h2><span class="muted small">Look strength, transitions, text, zoom moments, sound effects, AI voiceover and export. Everything here is also available from the assistant below.</span></div>

      <div class="settings">
        <label>Look strength: {{(finish.lookIntensity*100)|number:'1.0-0'}}%
          <input type="range" min="0" max="100" step="5" [ngModel]="finish.lookIntensity*100" (ngModelChange)="finish.lookIntensity=$event/100;finishDirty=true"></label>
        <label>Transition for every cut
          <select [(ngModel)]="trSel"><option value="">None (hard cut)</option>
            <optgroup *ngFor="let g of trGroups()" [label]="g.name"><option *ngFor="let t of g.items" [value]="t.id" [title]="t.purpose">{{t.name}}</option></optgroup></select></label>
        <label>Transition length (s)<input type="number" min="0.2" max="1.5" step="0.1" [(ngModel)]="trSeconds" placeholder="default"></label>
        <label>&nbsp;<button class="btn" [disabled]="busy||timeline.length<2" (click)="applyTransitionFromPanel()">Apply to all cuts</button></label>
      </div>

      <h3 class="small-head">Match a reference video</h3>
      <div class="smart-row">
        <label class="btn" style="cursor:pointer">Choose a video to copy the feel of<input type="file" accept="video/*" (change)="onReference($event)" hidden></label>
        <span class="muted small" *ngIf="refBusy">Measuring cuts, loudness and colour… this takes about half a minute.</span>
        <span class="small" style="color:#f87171" *ngIf="refErr">{{refErr}}</span>
      </div>
      <div *ngIf="ref" class="ref-card">
        <div class="chips"><span *ngFor="let c of ref.profile.palette" class="chip" [style.background]="c.hex" [title]="c.hex + ' · ' + c.percent + '%'"></span></div>
        <ul class="ref-notes"><li *ngFor="let n of ref.suggestions.notes">{{n}}</li></ul>
        <p class="muted small">Applying sets: pace <b>{{ref.suggestions.intensity}}</b>, look <b>{{ref.suggestions.look || 'unchanged'}}</b>, loudness <b>{{ref.suggestions.loudness || 'unchanged'}}</b>, export <b>{{ref.suggestions.exportShortSide}}p · {{ref.suggestions.exportFps}} fps</b>. {{refAspectNote()}}</p>
        <button class="btn" [disabled]="!project" (click)="applyReference()">Apply to this project</button>
      </div>

      <h3 class="small-head">Overlays on the timeline</h3>
      <div class="settings">
        <label>Type<select [(ngModel)]="newOv.type"><option value="text">Text / lower third</option><option value="text_behind_person">Behind person (AI mask)</option><option value="zoom">Zoom moment</option><option value="sfx">Sound effect</option></select></label>
        <label>Start (s)<input type="number" min="0" step="0.5" [(ngModel)]="newOv.start"></label>
        <label *ngIf="newOv.type!=='sfx'">End (s)<input type="number" min="0" step="0.5" [(ngModel)]="newOv.end"></label>
        <label *ngIf="(newOv.type==='text'||newOv.type==='text_behind_person')" class="grow">Text<input [(ngModel)]="newOv.text" maxlength="140" placeholder="e.g. Priya - Product designer"></label>
        <label *ngIf="(newOv.type==='text'||newOv.type==='text_behind_person')">Style<select [(ngModel)]="newOv.style"><option value="title">Title</option><option value="lower_third">Lower third</option><option value="box">Boxed</option><option value="small">Small</option></select></label>
        <label *ngIf="(newOv.type==='text'||newOv.type==='text_behind_person')&&newOv.style!=='lower_third'">Position<select [(ngModel)]="newOv.position"><option value="top">Top</option><option value="center">Centre</option><option value="bottom">Bottom</option><option value="custom">Custom position</option></select></label>
        <label *ngIf="(newOv.type==='text'||newOv.type==='text_behind_person')">Motion<select [(ngModel)]="newOv.animation"><option value="fade">Fade in/out</option><option value="none">Static</option><option value="slide_up">Slide up</option><option value="slide_left">Slide from left</option><option value="float">Floating drift</option><option value="pop">Pop in</option><option value="pulse">Gentle pulse</option></select></label>
        <label *ngIf="(newOv.type==='text'||newOv.type==='text_behind_person')&&newOv.position==='custom'">Horizontal position (%)<input type="number" min="0" max="100" step="1" [(ngModel)]="newOv.xPercent"></label>
        <label *ngIf="(newOv.type==='text'||newOv.type==='text_behind_person')&&newOv.position==='custom'">Vertical position (%)<input type="number" min="0" max="100" step="1" [(ngModel)]="newOv.yPercent"></label>
        <label *ngIf="(newOv.type==='text'||newOv.type==='text_behind_person')">Colour<select [(ngModel)]="newOv.color"><option *ngFor="let c of (caps?.overlayColors||['white','yellow'])" [value]="c">{{c}}</option></select></label>
        <label *ngIf="newOv.type==='zoom'">Zoom ×<input type="number" min="1.05" max="2" step="0.05" [(ngModel)]="newOv.amount"></label>
        <label *ngIf="newOv.type==='sfx'">Sound<select [(ngModel)]="newOv.sfx"><option *ngFor="let f of (caps?.soundEffects||['whoosh'])" [value]="f">{{f}}</option></select></label>
        <label>&nbsp;<button class="btn" (click)="addOverlayFromForm()">Add</button></label>
      </div>
      <p class="muted small">Motion and custom placement are baked into the final render. Behind person uses AI foreground segmentation during final rendering. It can be slower than standard text and may need the segmentation model to download on first use.</p>
      <div class="version-list" *ngIf="finish.overlays.length">
        <div class="version-row" *ngFor="let o of finish.overlays;let i=index"><span>{{overlayLabel(o)}}</span><button class="btn smallbtn" (click)="removeOverlay(i)">Remove</button></div>
      </div>

      <h3 class="small-head">AI voiceover</h3>
      <textarea rows="3" [(ngModel)]="voScript" placeholder="Paste the voiceover script. It is spoken sentence by sentence and mixed in with the rest of the sound ducked underneath."></textarea>
      <div class="smart-row">
        <label>Voice<select [(ngModel)]="voVoice"><option *ngFor="let v of voVoices" [value]="v.id">{{v.label}}</option></select></label>
        <label>Language<select [(ngModel)]="voLang"><option>English</option><option>Hindi</option><option>Kannada</option><option>Tamil</option><option>Telugu</option><option>Malayalam</option><option>Marathi</option><option>Bengali</option><option>Gujarati</option></select></label>
        <button class="btn" [disabled]="voBusy||!project" (click)="generateVoiceover()">{{voBusy?'Speaking…':'Generate voiceover'}}</button>
        <span class="muted small" *ngIf="voMsg">{{voMsg}}</span>
      </div>

      <h3 class="small-head">Dub into another language</h3>
      <div class="smart-row">
        <label>Language<select [(ngModel)]="dubLang"><option *ngFor="let l of (caps?.dubLanguages||['Hindi'])" [value]="l">{{l}}</option></select></label>
        <button class="btn" [disabled]="dubBusy||!project" (click)="startDub()">{{dubBusy?'Dubbing…':'Dub the latest render'}}</button>
        <label class="check"><input type="checkbox" [(ngModel)]="finish.voiceoverReplace" (ngModelChange)="finishDirty=true"> Replace the original voice with the voiceover</label>
      </div>
      <p class="muted small" *ngIf="dubMsg">{{dubMsg}}</p>
      <ul class="ref-notes" *ngIf="dubPreview.length"><li *ngFor="let l of dubPreview">{{l}}</li></ul>
      <p class="muted small">Render a Preview first: the dub is matched to that cut (it uses the voice chosen above).</p>

      <h3 class="small-head">Export</h3>
      <div class="settings">
        <label>Format<select [(ngModel)]="finish.exportFormat" (ngModelChange)="finishDirty=true"><option value="">MP4</option><option *ngFor="let f of (caps?.exportFormats||[])" [value]="f" [hidden]="f==='mp4'">{{f.toUpperCase()}}</option></select></label>
        <label>Resolution (short side)<select [(ngModel)]="finish.exportHeight" (ngModelChange)="finishDirty=true"><option [ngValue]="0">Default</option><option *ngFor="let h of (caps?.exportHeights||[])" [ngValue]="h">{{h===2160?'4K (2160p)':h+'p'}}</option></select></label>
        <label>Platform preset<select [(ngModel)]="platformSel" (ngModelChange)="applyPlatform($event)"><option value="">Choose…</option><option *ngFor="let p of (caps?.platforms||[])" [value]="p.id" [title]="p.note">{{p.label}}</option></select></label>
        <label>Loudness<select [(ngModel)]="finish.loudness" (ngModelChange)="finishDirty=true"><option value="">Leave as is</option><option *ngFor="let l of (caps?.loudnessTargets||[])" [value]="l.id">{{l.label}}</option></select></label>
        <label>Frame rate<select [(ngModel)]="finish.exportFps" (ngModelChange)="finishDirty=true"><option [ngValue]="0">Keep</option><option *ngFor="let f of (caps?.exportFps||[])" [ngValue]="f">{{f}} fps</option></select></label>
      </div>
      <p class="muted small" *ngIf="platformHint">{{platformHint}}</p>
      <div class="actions" style="margin-top:.8rem"><button class="btn primary" [disabled]="!finishDirty||!project" (click)="saveSettings()">Save finishing settings</button><span class="muted small" *ngIf="finishDirty">Unsaved changes - press Save, then Preview or Render.</span></div>
    </section>

    <section class="panel" *ngIf="clips.length">
      <div class="section-head"><h2>Edit assistant</h2><span class="muted small">Try: "add captions", "warm look", "make it vertical", "speed up shot 2", "trim shot 3 to 4 seconds", "make the intro faster", "add title: My trip", "clean up speech", "find best moments", "undo" - or any free-form direction</span></div>
      <div class="chat-log" *ngIf="chatLog.length">
        <p *ngFor="let m of chatLog" [class]="'chat-'+m.role">{{m.text}}</p>
      </div>
      <div class="chat-input-row">
        <input type="text" [(ngModel)]="chatInput" placeholder="Tell the editor what to change…" (keydown.enter)="sendChatCommand()">
        <button class="btn primary" [disabled]="!chatInput.trim()" (click)="sendChatCommand()">Send</button>
      </div>
      <div class="smart-row">
        <button class="btn" [disabled]="busy||!analysisDone()" (click)="cleanupSpeech()" title="Cut dead air, 'um/uh' and retakes from talking footage">✂ Clean up speech</button>
        <button class="btn" [disabled]="busy||!analysisDone()" (click)="findMoments()" title="Pick the best 15-60 s moments of a long recording">⭐ Find best moments</button>
        <span class="muted small" *ngIf="!analysisDone()">Run Analyse first.</span>
      </div>
      <div class="smart-row">
        <input type="text" class="grow" [(ngModel)]="searchQuery" placeholder="Search what was said, e.g. “new dashboard”" (keydown.enter)="searchSpeech()">
        <button class="btn" [disabled]="busy||!searchQuery.trim()" (click)="searchSpeech()">🔎 Search speech</button>
        <button class="btn smallbtn" *ngIf="transcriptStatus&&transcriptStatus.indexedClips<transcriptStatus.totalClips" [disabled]="busy||!analysisDone()" (click)="indexSpeech()" title="Transcribe the clips once so their speech can be searched">Index speech ({{transcriptStatus.indexedClips}}/{{transcriptStatus.totalClips}})</button>
      </div>
      <p class="muted small" *ngIf="searchNote">{{searchNote}}</p>
      <div class="moments" *ngIf="hits.length">
        <div class="moment" *ngFor="let h of hits">
          <div><span class="muted small">{{h.clipName}} · {{formatDuration(h.start)}}–{{formatDuration(h.end)}}</span><br>“{{h.text}}”</div>
          <div class="hit-actions"><button class="btn smallbtn" (click)="previewHit(h)">▶ Preview</button><button class="btn smallbtn" [disabled]="busy" (click)="insertHit(h)">＋ Insert{{selectedRowIndex>=0?' after shot '+(selectedRowIndex+1):' at end'}}</button></div>
        </div>
      </div>
      <div class="moments" *ngIf="moments.length">
        <div class="moment" *ngFor="let m of moments">
          <div><strong>{{m.title}}</strong> <span class="muted small">{{m.clipName}} · {{formatDuration(m.startSec)}}–{{formatDuration(m.endSec)}} · {{m.durationSec|number:'1.0-0'}}s · score {{m.score}}/10</span><br><span class="muted small">{{m.reason}}</span></div>
          <button class="btn smallbtn" [disabled]="busy" (click)="useMoment(m.index)">Use as vertical short</button>
        </div>
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
    .ref-card{margin:.6rem 0 1rem;padding:.8rem;border:1px solid rgba(255,255,255,.1);border-radius:12px}
    .ref-card .chip{display:inline-block;width:30px;height:30px;border-radius:50%;margin-right:6px;border:1px solid rgba(255,255,255,.3)}
    .ref-notes{margin:.5rem 0;padding-left:1.1rem;display:flex;flex-direction:column;gap:.2rem}
    .page-head,.section-head{display:flex;align-items:center;justify-content:space-between;gap:1rem}.page-head{margin-bottom:1rem}h1{font-family:var(--font-display);margin:0}.muted{color:var(--muted);font-size:.88rem}.small{font-size:.78rem}.state,.count{font-size:.72rem;border:1px solid var(--border);border-radius:999px;padding:.15rem .55rem;color:var(--muted)}
    .panel{background:var(--surface-raised);border:1px solid var(--border);border-radius:var(--radius);padding:1rem;margin-bottom:1rem}.notice,.error{color:var(--danger);font-size:.88rem}.dropzone{border:2px dashed var(--border);border-radius:var(--radius);padding:2rem;text-align:center}.dropzone.dragging{border-color:var(--accent)}.drop-title{font-family:var(--font-display);font-size:1.15rem}.btn{display:inline-block;cursor:pointer;border:1px solid var(--border);background:var(--surface);color:var(--text);border-radius:8px;padding:.55rem .9rem;font:inherit;text-decoration:none}.btn:hover{border-color:var(--accent)}.btn:disabled{opacity:.45;cursor:not-allowed}.primary{background:var(--accent);color:var(--accent-ink);border-color:var(--accent);font-weight:600}.smallbtn{padding:.4rem .65rem;font-size:.8rem}
    .source-list{display:grid;gap:.45rem}.source-row{display:flex;align-items:center;gap:.65rem;padding:.45rem;border:1px solid var(--border);border-radius:8px;background:var(--surface);cursor:pointer}.source-row.selected{border-color:var(--accent)}.source-thumb{width:70px;height:42px;background:#111;border-radius:5px;overflow:hidden;position:relative;display:grid;place-items:center}.source-thumb img{width:100%;height:100%;object-fit:cover}.source-thumb span{position:absolute;left:4px;bottom:3px;background:#000a;padding:1px 4px;border-radius:3px}.source-main{flex:1;min-width:0}.clip-name{width:100%;background:transparent;color:inherit;border:0;border-bottom:1px solid transparent;font:inherit}.clip-name:focus{outline:0;border-bottom-color:var(--accent)}.clip-meta{display:flex;gap:.65rem;flex-wrap:wrap;color:var(--muted);font-size:.74rem}.clip-actions{display:flex;gap:.2rem}.icon{cursor:pointer;background:transparent;border:1px solid var(--border);color:var(--muted);border-radius:6px;width:1.8rem;height:1.8rem}.icon:disabled{opacity:.3}.danger:hover{color:var(--danger);border-color:var(--danger)}
    .smart-row .grow{flex:1;min-width:220px}.hit-actions{display:flex;gap:.4rem;flex-wrap:wrap}.smart-row{display:flex;gap:.5rem;flex-wrap:wrap;align-items:center;margin-top:.7rem}.moments{margin-top:.6rem}.moment{display:flex;justify-content:space-between;gap:1rem;align-items:center;padding:.55rem 0;border-top:1px solid rgba(127,127,127,.25)}.card-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(135px,1fr));gap:.5rem;margin-bottom:1rem}.card{display:flex;flex-direction:column;gap:.15rem;text-align:left;background:var(--surface);border:1px solid var(--border);border-radius:8px;padding:.65rem;color:inherit;cursor:pointer}.card.selected{border-color:var(--accent);box-shadow:inset 0 0 0 1px var(--accent)}.ratio{font-size:.7rem;color:var(--teal)}.blurb{font-size:.73rem;color:var(--muted)}.settings{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:.7rem}.settings label,.editor-grid label,.transport label{font-size:.78rem;color:var(--muted)}select,textarea,input[type=number]{width:100%;margin-top:.25rem;background:var(--surface);color:var(--text);border:1px solid var(--border);border-radius:7px;padding:.4rem;font:inherit}.custom{display:block;margin-top:.8rem;color:var(--muted);font-size:.8rem}.style-hint{margin-top:.5rem;color:var(--muted);font-size:.74rem}.rationale{white-space:pre-wrap;font:inherit;color:var(--text);background:var(--surface);border:1px solid var(--border);border-radius:8px;padding:.7rem;margin:0}.version-list{border-top:1px solid var(--border);padding-top:.5rem;margin-top:.5rem}.version-row{display:flex;justify-content:space-between;align-items:center;gap:.6rem}.version-main{display:flex;flex-direction:column;gap:.1rem}.version-rationale{margin:.25rem 0 .5rem;white-space:pre-wrap}.chat-log{max-height:220px;overflow-y:auto;display:flex;flex-direction:column;gap:.4rem;margin-bottom:.7rem}.chat-log p{margin:0;padding:.5rem .7rem;border-radius:8px;font-size:.85rem}.chat-user{align-self:flex-end;background:var(--accent);color:var(--accent-ink);max-width:80%}.chat-system{align-self:flex-start;background:var(--surface);border:1px solid var(--border);max-width:80%}.chat-input-row{display:flex;gap:.5rem}.chat-input-row input{flex:1;background:var(--surface);color:var(--text);border:1px solid var(--border);border-radius:7px;padding:.5rem .7rem;font:inherit}.options{display:flex;gap:.8rem;flex-wrap:wrap;margin-top:.8rem}.options label{font-size:.8rem}.disabled{opacity:.5}.pending{font-size:.68rem}.workspace{display:grid;grid-template-columns:minmax(280px,.8fr) minmax(420px,1.5fr);gap:1rem}.workspace .panel{margin:0}.player{width:100%;max-height:55vh;background:#000;border-radius:8px}.monitor-empty,.empty-timeline{padding:3rem 1rem;text-align:center;color:var(--muted);border:1px dashed var(--border);border-radius:8px}.transport{display:flex;gap:.5rem;align-items:end;margin-top:.7rem}.transport label{flex:1}.timeline-track{display:flex;gap:3px;min-height:68px;background:#111;border-radius:8px;padding:5px;overflow:auto}.tl-block{min-width:72px;border:1px solid var(--border);border-radius:6px;background:var(--surface);color:inherit;padding:.45rem;text-align:left;cursor:pointer;display:flex;flex-direction:column;justify-content:space-between}.tl-block.selected{border-color:var(--accent);box-shadow:inset 0 0 0 1px var(--accent)}.tl-block.locked{border-color:#c9a227}.tl-block small{color:var(--muted)}.editor-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:.65rem;margin-top:.8rem;padding-top:.8rem;border-top:1px solid var(--border)}.editor-title{grid-column:1/-1;display:flex;gap:.6rem}.editor-title span{color:var(--muted)}.check{display:flex;align-items:center;gap:.35rem}.row-actions{grid-column:1/-1;display:flex;gap:.4rem;flex-wrap:wrap}.danger-btn{color:var(--danger)}.reason{grid-column:1/-1;color:var(--muted);font-size:.78rem}.reorder{display:flex;align-items:center;gap:.35rem;margin-top:.7rem}.timeline-actions{display:flex;gap:.3rem}.music-name{margin-left:.6rem;color:var(--muted);font-size:.8rem}.actions{display:flex;gap:.5rem;flex-wrap:wrap}.prog-head{display:flex;justify-content:space-between}.bar{height:6px;background:var(--surface);border-radius:9px;overflow:hidden;margin-top:.4rem}.fill{height:100%;background:var(--accent);transition:width .2s}.result{margin-top:1rem}
    .source-row[draggable="true"]{cursor:grab}.source-row[draggable="true"]:active{cursor:grabbing}.timeline-track{align-items:stretch;min-height:92px;padding:7px;gap:2px}.tl-block{position:relative;min-width:110px;min-height:78px}.tl-block[draggable="true"]{cursor:grab}.tl-block[draggable="true"]:active{cursor:grabbing}.tl-index{font-size:.7rem;color:var(--accent)}.drop-marker{width:4px;border-radius:4px;transition:.1s;background:transparent}.drop-marker.active{background:var(--accent);width:7px}.drop-tail{width:22px;min-width:22px;border:1px dashed var(--border);border-radius:5px;display:grid;place-items:center;color:var(--muted)}.drop-tail.active{border-color:var(--accent);color:var(--accent)}.timeline-hint{font-size:.72rem;color:var(--muted);margin-top:.45rem}.timeline-drop-empty{margin-top:.5rem;min-height:92px;border:2px dashed var(--border);border-radius:8px;display:grid;place-items:center;color:var(--muted)}.timeline-drop-empty:hover{border-color:var(--accent);color:var(--text)}.trim-panel{margin-top:.8rem;padding:.75rem;border:1px solid var(--border);border-radius:8px;background:var(--surface)}.trim-head,.trim-values{display:flex;justify-content:space-between;gap:1rem;font-size:.76rem;color:var(--muted)}.trim-head strong{color:var(--text)}.trim-range{position:relative;height:34px;margin:.55rem 0}.trim-track{position:absolute;left:0;right:0;top:15px;height:5px;border-radius:5px;background:var(--border)}.trim-range input[type=range]{position:absolute;left:0;right:0;top:4px;width:100%;margin:0;padding:0;background:transparent;pointer-events:none;border:0}.trim-range input[type=range]::-webkit-slider-thumb{pointer-events:auto}.trim-range input[type=range]::-moz-range-thumb{pointer-events:auto}.trim-range input[type=range]::-webkit-slider-runnable-track{background:transparent}.trim-range input[type=range]::-moz-range-track{background:transparent}.trim-values{font-size:.7rem}.editor-grid{grid-template-columns:repeat(4,1fr)}
    /* Aligned workflow cards replace the old wrap-anywhere action row. */
    .actions-panel{padding:clamp(1rem,2vw,1.5rem);border-color:color-mix(in srgb,var(--accent) 24%,var(--border))}
    .action-intro{display:flex;align-items:center;justify-content:space-between;gap:1rem;margin-bottom:1rem}.action-intro h2{margin:.2rem 0;font-size:1.25rem}.action-intro p{margin:.25rem 0 0}.eyebrow,.group-label{font-size:.68rem;letter-spacing:.12em;font-weight:750;color:var(--muted)}
    .action-groups{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:.85rem;align-items:stretch}
    .action-group{min-width:0;display:flex;flex-direction:column;gap:.65rem;padding:.9rem;border:1px solid var(--border);border-radius:12px;background:var(--surface)}
    .action-group.ai-group{border-top:3px solid var(--accent)}.action-group.preview-group{border-top:3px solid var(--teal)}.action-group.export-group{border-top:3px solid var(--accent)}
    .action-main{display:flex;align-items:center;justify-content:flex-start;gap:.7rem;width:100%;min-height:68px;text-align:left;white-space:normal;padding:.75rem;line-height:1.25}
    .action-main>span:last-child{display:flex;flex-direction:column;gap:.25rem;min-width:0}.action-main strong{font-size:.92rem}.action-main small{font-size:.72rem;opacity:.82;font-weight:400}.action-icon{display:grid;place-items:center;flex:0 0 36px;width:36px;height:36px;border-radius:9px;background:color-mix(in srgb,var(--accent) 14%,transparent);font-size:1.15rem}
    .action-secondary{display:grid;grid-template-columns:repeat(auto-fit,minmax(100px,1fr));gap:.45rem;margin-top:auto}.action-secondary .btn{display:flex;align-items:center;justify-content:center;min-height:38px;text-align:center;font-size:.78rem;padding:.45rem .55rem;white-space:normal;line-height:1.2}
    .job-pill{border:1px solid var(--border);border-radius:999px;padding:.4rem .7rem;font-size:.75rem;color:var(--accent);white-space:nowrap}.job-status{margin-top:1rem;padding:.75rem;border:1px solid var(--border);border-radius:10px;background:var(--surface)}.job-status-head{display:flex;justify-content:space-between;gap:1rem;font-size:.8rem}.job-status .bar{height:8px}
    .section-head{flex-wrap:wrap}.timeline-actions{display:flex;align-items:center;justify-content:flex-end;gap:.4rem;flex-wrap:wrap}.transport{flex-wrap:wrap}.transport .btn{min-height:40px}.row-actions .btn{min-height:38px}.btn:focus-visible,.icon:focus-visible,.card:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
    @media(max-width:1000px){.action-groups{grid-template-columns:1fr 1fr}.action-group.export-group{grid-column:1/-1}}
    @media(max-width:600px){.action-groups{grid-template-columns:1fr}.action-group.export-group{grid-column:auto}.action-intro{align-items:flex-start;flex-direction:column}.job-pill{white-space:normal}.timeline-actions{justify-content:flex-start}.panel{padding:.8rem}.section-head{align-items:flex-start}.section-head .btn{flex-shrink:0}}
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
  // ---- finishing (Cardboard parity): look strength, overlays, transitions, voiceover, export. Kept apart from `form` and only
  // sent when changed, so saving other settings can never wipe overlays that were saved earlier.
  finish:any={lookIntensity:1,overlays:[] as any[],exportFormat:'',exportHeight:0,exportFps:0,loudness:'',voiceoverReplace:false}; finishDirty=false; platformSel=''; platformHint='';
  newOv:any={type:'text',start:0,end:3,text:'',position:'bottom',style:'title',color:'white',amount:1.3,sfx:'whoosh',animation:'fade',xPercent:50,yPercent:50};
  trSel=''; trSeconds:number|null=null;
  // dub the latest render into another language
  dubLang='Hindi'; dubBusy=false; dubMsg=''; dubPreview:string[]=[];
  startDub(lang?:string){
    if(!this.project){this.dubMsg='Open a project first.';return;}
    if(lang)this.dubLang=lang;
    this.dubBusy=true;this.dubMsg=`Listening to the latest render, translating to ${this.dubLang} and speaking it… this can take a few minutes.`;this.dubPreview=[];
    this.api.dubVideo(this.project.id,{language:this.dubLang,voice:this.voVoice}).subscribe({
      next:r=>{this.dubBusy=false;this.finish.voiceoverReplace=true;this.dubPreview=r.preview||[];
        this.dubMsg=`Dubbed ${r.placed} of ${r.lines} lines into ${r.language}`+(r.sped?` (${r.sped} sped up to fit)`:'')+(r.notes?.length?`. ${r.notes.join(' ')}`:'.')+' Press Preview to hear it - the original voice is lowered. Edited the cut? Dub again.';
        this.chatLog.push({role:'system',text:this.dubMsg});},
      error:e=>{this.dubBusy=false;this.dubMsg=e?.error?.error??'Could not dub the video.';}});}
  // match a reference video
  ref:any=null; refBusy=false; refErr='';
  onReference(ev:Event){const input=ev.target as HTMLInputElement;const f=input.files?.[0];if(!f)return;this.refBusy=true;this.refErr='';this.ref=null;
    this.api.analyseReference(f).subscribe({next:r=>{this.ref=r;this.refBusy=false;},error:e=>{this.refBusy=false;this.refErr=e?.error?.error||'Could not analyse that video.';}});input.value='';}
  refAspectNote():string{const a=this.ref?.suggestions?.aspectRatio,mine=(this.project as any)?.aspectRatio||this.form.aspectRatio;return a&&mine&&a!==mine?`The reference is ${String(a).replace(/^[A-Z]+_/,'').replace('_',':')}, this project is ${String(mine).replace(/^[A-Z]+_/,'').replace('_',':')} - the shape is not changed here.`:'';}
  applyReference(){const s=this.ref?.suggestions;if(!s||!this.project)return;
    if(s.intensity)this.form.intensity=s.intensity;if(s.look)this.form.look=s.look;
    if(s.loudness)this.finish.loudness=s.loudness;if(s.exportShortSide)this.finish.exportHeight=s.exportShortSide;if(s.exportFps)this.finish.exportFps=s.exportFps;
    this.finishDirty=true;this.saveSettings();
    this.chatLog.push({role:'system',text:`Matched the reference: pace ${s.intensity}${s.look?', look '+s.look:''}${s.loudness?', loudness '+s.loudness:''}. Press Preview to see it.`});}
  voScript=''; voVoice='narrator-in-male'; voLang='English'; voBusy=false; voMsg=''; voVoices:{id:string;label:string}[]=[];
  moments:VideoMoment[]=[];
  searchQuery=''; hits:VideoSpeechHit[]=[]; searchNote=''; transcriptStatus:{indexedClips:number;totalClips:number}|null=null;
  private stream?:EventSource; private history:TimelineDraft[][]=[]; redoStack:TimelineDraft[][]=[];
  private editSnapshotPending=false;
  readonly transitionOptions=['cross_dissolve','whip_pan','dip_to_black','slide_left','hard_cut'];
  readonly optionList=[
    {key:'smartCuts',label:'Smart cuts',stage:'planning'},{key:'beatSync',label:'Beat sync (cuts land on the music beat — add music first)',stage:'planning'},
    {key:'smartTransitions',label:'Smart transitions',stage:'planning'},{key:'autoCaptions',label:'Auto captions (word-by-word highlight, burned in)',stage:'captions'},
    {key:'audioEnhancement',label:'Audio enhancement',stage:'render'},{key:'stabilize',label:'Stabilize shaky footage',stage:'render'},{key:'smartReframing',label:'Smart reframing',stage:'render'}];
  form:any={name:'Untitled edit',category:'REEL',editingStyle:'TRENDING_REEL',intensity:'DYNAMIC',aspectRatio:'VERTICAL_9_16',targetDurationSec:null,customInstructions:'',smartCuts:true,beatSync:true,smartTransitions:true,autoCaptions:false,audioEnhancement:true,smartReframing:true,stabilize:false,look:'auto',titleText:'',effects:''};
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
  ngOnInit(){this.api.videoEditorCapabilities().subscribe({next:c=>this.caps=c,error:()=>this.loadError='Could not reach the video editor API.'});
    // voices for "AI voiceover": the Indian-English / Chatterbox / Voice Lab voices first, then everything the app can speak
    this.api.listVoices().subscribe({next:r=>{const v=(r?.voices||[]) as any[];const rank=(x:any)=>String(x.id).startsWith('narrator-in-')?0:String(x.id).startsWith('profile:')?1:String(x.id).startsWith('narrator-')?2:3;
      this.voVoices=[{id:'narrator-in-male',label:'Arjun - Indian English, male'},{id:'narrator-in-female',label:'Meera - Indian English, female'},...v.filter(x=>x.installed!==false&&!String(x.id).startsWith('narrator-in-')).sort((a,b)=>rank(a)-rank(b)).slice(0,80).map(x=>({id:x.id,label:x.label+(x.gender?' - '+x.gender:'')}))];},error:()=>{this.voVoices=[{id:'narrator-in-male',label:'Arjun - Indian English, male'},{id:'narrator-in-female',label:'Meera - Indian English, female'}];}});}
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
  isOptionAvailable(key:string){const stage=this.optionList.find(o=>o.key===key)?.stage;return !!(stage&&(this.caps?.implemented as any)?.[stage]);}
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

  remove(c:VideoClip){if(!this.project)return;if(this.timelineDirty){this.uploadError='Save or undo unsaved timeline changes before removing a source clip.';return}this.uploadError='';this.api.removeVideoClip(this.project.id,c.id).subscribe({next:()=>{this.clips=this.clips.filter(x=>x.id!==c.id);if(this.selectedClipId===c.id){this.selectedClipId='';this.sourceUrl='';this.selectedRowIndex=-1}this.reload(true)},error:err=>this.uploadError=err?.error?.error??'Could not remove clip.'});}
  move(i:number,d:number){const t=i+d;if(!this.project||t<0||t>=this.clips.length)return;const n=[...this.clips];[n[i],n[t]]=[n[t],n[i]];this.clips=n;this.api.reorderVideoClips(this.project.id,n.map(x=>x.id)).subscribe({next:u=>this.clips=u,error:()=>this.reload()});}
  rename(c:VideoClip,name:string){if(!this.project||!name.trim()||name===c.displayName)return;this.api.renameVideoClip(this.project.id,c.id,name.trim()).subscribe({next:u=>c.displayName=u.displayName,error:()=>this.reload()});}
  saveSettings(){if(!this.project)return;this.api.updateVideoEditorProject(this.project.id,this.settingsPayload()).subscribe({next:p=>{this.project=p;this.finishDirty=false;this.finishFromProject();},error:e=>this.uploadError=e?.error?.error??'Could not save settings.'});}
  captionStyle='BOLD_REEL';
  readonly effectList=[{id:'soft_blur',label:'Soft blur'},{id:'glow',label:'Soft glow'},{id:'halation',label:'Halation'},{id:'vignette',label:'Vignette'},{id:'film_grain',label:'Film grain'},{id:'scanlines',label:'Scanlines'},{id:'letterbox',label:'Cinematic bars'}];
  hasEffect(id:string){return (this.form.effects||'').split(',').includes(id);}
  toggleEffect(id:string){const cur=(this.form.effects||'').split(',').filter((x:string)=>x);this.form.effects=(cur.includes(id)?cur.filter((x:string)=>x!==id):[...cur,id]).join(',');}
  readonly looks=[{id:'vivid',label:'Vivid'},{id:'warm',label:'Warm'},{id:'cool',label:'Cool'},{id:'golden_hour',label:'Golden hour'},{id:'moody',label:'Moody'},{id:'vintage',label:'Vintage film'},{id:'bw',label:'Black & white'},{id:'soft_pastel',label:'Soft pastel'},{id:'fresh',label:'Fresh & bright'},{id:'drama',label:'Dramatic'}];
  private settingsPayload():any{return this.finishDirty?{...this.form,lookIntensity:this.finish.lookIntensity,overlays:JSON.stringify(this.finish.overlays),exportFormat:this.finish.exportFormat||'mp4',exportHeight:+this.finish.exportHeight||0,exportFps:+this.finish.exportFps||0,loudness:this.finish.loudness||'',voiceoverReplace:!!this.finish.voiceoverReplace}:this.form;}
  private finishFromProject(){const p:any=this.project;if(!p||this.finishDirty)return;let ov:any[]=[];try{ov=p.overlays?JSON.parse(p.overlays):[]}catch{ov=[]}this.finish={lookIntensity:p.lookIntensity??1,overlays:ov,exportFormat:p.exportFormat||'',exportHeight:p.exportHeight||0,exportFps:p.exportFps||0,loudness:p.loudness||'',voiceoverReplace:!!p.voiceoverReplace};}
  private saveSettingsThen(next:()=>void){if(!this.project){return}this.api.updateVideoEditorProject(this.project.id,this.settingsPayload()).subscribe({next:p=>{this.project=p;this.finishDirty=false;this.finishFromProject();if(this.form.autoCaptions){this.api.updateVideoCaptions(p.id,this.captionStyle,true).subscribe({next:()=>next(),error:()=>next()});return;}next()},error:e=>{this.busy=false;this.uploadError=e?.error?.error??'Could not save settings.'}})}
  private reload(resetTimelineState=false){if(!this.project)return;const projectId=this.project.id;this.api.getVideoEditorProject(projectId).subscribe({next:d=>{if(this.project?.id!==projectId)return;this.project=d.project;this.finishFromProject();this.clips=d.clips;this.timeline=d.timeline;if(resetTimelineState){this.history=[];this.redoStack=[];this.editSnapshotPending=false;this.timelineDirty=false;this.selectedRowIndex=this.timeline.length?0:-1;if(this.selectedRowIndex>=0)this.selectRow(0);else{this.selectedClipId='';this.sourceUrl='';}}this.refreshTranscriptStatus()}})}
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
  preview(){if(!this.project)return;if(!this.timeline.length){this.uploadError='There are no shots to preview. Run AI Edit or add a source clip to the timeline.';return}this.saveSettingsThen(()=>this.saveTimelineThen(()=>this.run(()=>this.api.previewVideoProject(this.project!.id),id=>this.showResult(id,'PREVIEW'))))}
  render(){if(!this.project)return;if(!this.timeline.length){this.uploadError='There are no shots to render. Run AI Edit or add a source clip to the timeline.';return}this.saveSettingsThen(()=>this.saveTimelineThen(()=>this.run(()=>this.api.renderVideoProject(this.project!.id),id=>this.showResult(id,'FINAL'))))}
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
  private timelinePayloadKey(payload=this.timelinePayload()):string{return JSON.stringify(payload)}
  private saveTimelineThen(done:()=>void){
    if(!this.project){done();return}
    if(!this.timeline.length){this.busy=false;this.uploadError='The timeline has no shots. Add a shot or run AI Edit before previewing/rendering.';return}
    const projectId=this.project.id;
    const payload=this.timelinePayload();
    const submittedKey=this.timelinePayloadKey(payload);
    this.api.replaceVideoTimeline(projectId,payload).subscribe({
      next:r=>{
        // Do not overwrite edits made while the save request was in flight.
        if(this.project?.id!==projectId)return;
        if(this.timelinePayloadKey()===submittedKey){this.timeline=r;this.timelineDirty=false;this.editSnapshotPending=false;this.project.state='PLAN_READY';}
        else{this.timelineDirty=true;this.uploadError='The timeline changed while saving. Your newer edits are still in the editor; save again before rendering.';this.busy=false;return;}
        done();
      },
      error:e=>{this.busy=false;this.uploadError=e?.error?.error??'Could not save the timeline before rendering.'}
    });
  }
  saveTimeline(){
    if(!this.project)return;
    if(!this.timeline.length){this.uploadError='The timeline has no shots. Add a shot or run AI Edit before saving.';return}
    const projectId=this.project.id;
    const payload=this.timelinePayload();
    const submittedKey=this.timelinePayloadKey(payload);
    this.uploadError='';
    this.api.replaceVideoTimeline(projectId,payload).subscribe({
      next:r=>{
        if(this.project?.id!==projectId)return;
        if(this.timelinePayloadKey()===submittedKey){this.timeline=r;this.timelineDirty=false;this.editSnapshotPending=false;this.project.state='PLAN_READY';if(this.showVersions)this.loadVersions();}
        else{this.timelineDirty=true;this.uploadError='The timeline changed while saving. Your newer edits are still in the editor; save again to persist them.';}
      },
      error:e=>this.uploadError=e?.error?.error??'Could not save the timeline.'
    });
  }
  private snapshot():TimelineDraft[]{return this.timeline.map(r=>({id:r.id,sortOrder:r.sortOrder,reason:r.reason,outputDurationSec:r.outputDurationSec,clipId:r.clipId,sourceStartSec:r.sourceStartSec,sourceEndSec:r.sourceEndSec,techniqueIn:r.techniqueIn,techniqueOut:r.techniqueOut,transitionSec:r.transitionSec,speed:r.speed,volume:r.volume,muted:r.muted,locked:r.locked}))}
  private pushHistory(snapshot=this.snapshot()){this.history.push(snapshot);if(this.history.length>50)this.history.shift();}
  beginRowEdit(){if(this.editSnapshotPending)return;this.pushHistory();this.redoStack=[];this.editSnapshotPending=true;}
  finishRowEdit(){this.editSnapshotPending=false;this.timelineDirty=true;}
  private restoreDraft(draft:TimelineDraft[]){this.timeline=draft.map((r,i)=>({...r,sortOrder:i,outputDurationSec:(r.sourceEndSec-r.sourceStartSec)/Math.max(.01,r.speed)}));this.selectedRowIndex=Math.min(this.selectedRowIndex,this.timeline.length-1);this.timelineDirty=true;this.editSnapshotPending=false;this.recalcSelection()}
  private commit(mutator:()=>void){this.pushHistory();this.redoStack=[];mutator();this.timelineDirty=true;this.editSnapshotPending=false;this.recalcSelection()}
  undo(){const prev=this.history.pop();if(!prev)return;this.redoStack.push(this.snapshot());this.restoreDraft(prev)}
  redo(){const next=this.redoStack.pop();if(!next)return;this.pushHistory();this.restoreDraft(next)}
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
    const smart=this.interpretSmartCommand(raw,text);
    if(smart!==null)return smart;

    if((m=text.match(/\b(\d+)\s*(?:s|sec|secs|seconds?)\b/)) && /\b(make|set|duration|long|length)\b/.test(text)){
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
  // ---- Cardboard-style commands (all deterministic, no model call) -----------
  private static readonly LOOK_WORDS:[RegExp,string,string][]=[
    [/black (and|&) white|b&w|monochrome|greyscale|grayscale/,'bw','Black & white'],[/golden hour|sunset|sunny glow/,'golden_hour','Golden hour'],
    [/vintage|retro|old film|film look/,'vintage','Vintage film'],[/pastel|soft/,'soft_pastel','Soft pastel'],[/moody|dark and/,'moody','Moody'],
    [/dramatic|drama|high contrast/,'drama','Dramatic'],[/vivid|punchy|saturated|colou?rful/,'vivid','Vivid'],[/\bwarm(er)?\b/,'warm','Warm'],
    [/\bcool(er)?\b|\bcold\b|bluish/,'cool','Cool'],[/fresh|bright/,'fresh','Fresh & bright']];
  // ---- finishing helpers (also used by the chat commands below) ------------------------------------------------
  private static readonly TRANSITION_WORDS:[RegExp,string,string][]=[
    [/audio fade/,'cb_audio_fade','Audio fade'],[/light leak/,'cb_light_leak','Light leak'],[/film burn/,'cb_film_burn','Film burn'],
    [/cross ?zoom/,'cb_cross_zoom','Cross zoom'],[/radial|clock wipe/,'cb_radial_wipe','Radial wipe'],[/\bink\b/,'cb_ink_bleed','Ink bleed'],
    [/glitch/,'cb_glitch','Glitch'],[/chromatic|rgb split/,'cb_chromatic','Chromatic aberration'],[/dissolve|crossfade|cross fade/,'cb_dissolve','Dissolve'],
    [/\bfly\b/,'cb_fly_','Fly'],[/\bpush\b/,'cb_push_','Push'],[/\bslide\b/,'cb_slide_','Slide'],[/\bzoom\b/,'cb_zoom','Zoom'],[/\bfade\b|dip to black/,'cb_fade','Fade']];
  private toSec(t:string):number{const x=t.trim().toLowerCase();let m=x.match(/^(\d+):(\d{1,2})$/);if(m)return +m[1]*60+ +m[2];m=x.match(/^(\d+(?:\.\d+)?)/);return m?+m[1]:0;}
  private clock(sec:number):string{const s=Math.round(sec);return `${Math.floor(s/60)}:${String(s%60).padStart(2,'0')}`;}
  addOverlay(o:any){this.finish.overlays=[...this.finish.overlays,{...o}];this.finishDirty=true;}
  removeOverlay(i:number){this.finish.overlays=this.finish.overlays.filter((_:any,k:number)=>k!==i);this.finishDirty=true;}
  addOverlayFromForm(){const o={...this.newOv};if((o.type==='text'||o.type==='text_behind_person')&&!String(o.text).trim())return;if(o.type==='sfx')o.end=o.start;else if(+o.end<=+o.start)o.end=+o.start+(o.type==='zoom'?2:3);this.addOverlay(o);this.newOv={...this.newOv,text:''};}
  overlayLabel(o:any):string{const t=this.clock(+o.start);return (o.type==='text'||o.type==='text_behind_person')?`${t}  “${o.text}” · ${this.overlayMotionLabel(o.animation)}${o.type==='text_behind_person'?' · behind person':''}`:o.type==='zoom'?`${t}  zoom ×${o.amount}`:`${t}  sound: ${o.sfx}`;}
  overlayMotionLabel(animation:string):string{return ({fade:'fade',none:'static',slide_up:'slide up',slide_left:'slide from left',float:'floating drift',pop:'pop in',pulse:'pulse'} as Record<string,string>)[animation||'fade']||'fade';}
  /** Sets the transition after shot `afterShot` (1-based), or after every shot but the last when afterShot is 0. id '' clears. */
  applyTransition(id:string,afterShot:number,seconds?:number|null):number{
    let n=0;this.timeline.forEach((r:any,i:number)=>{if(i>=this.timeline.length-1)return;if(afterShot>0&&i!==afterShot-1)return;r.techniqueOut=id||null;r.transitionSec=id&&seconds?seconds:null;n++;});
    if(n)this.saveTimeline();return n;}
  trGroups():{name:string;items:any[]}[]{const t=this.caps?.transitions||[];const g:Record<string,any[]>={};t.forEach((x:any)=>(g[x.group]=g[x.group]||[]).push(x));return Object.keys(g).map(k=>({name:k,items:g[k]}));}
  applyTransitionFromPanel(){const n=this.applyTransition(this.trSel,0,this.trSeconds);this.chatLog.push({role:'system',text:n?(this.trSel?`Applied the transition to all ${n} cuts. Press Preview to see it.`:`Cleared the transitions on ${n} cuts.`):'You need at least two shots in the timeline first.'});}
  generateVoiceover(){
    if(!this.project){this.voMsg='Open a project first.';return;}
    if(!this.voScript.trim()){this.voMsg='Write or paste the voiceover script first.';return;}
    this.voBusy=true;this.voMsg='Speaking the script… this can take a minute for a long script.';
    this.api.generateVideoVoiceover(this.project.id,{script:this.voScript,voice:this.voVoice,language:this.voLang}).subscribe({
      next:r=>{this.voBusy=false;this.voMsg=`Voiceover ready: ${r.sentences} sentences, ${Math.round(r.seconds)} s.`+(r.note?` ${r.note}`:'')+' It is mixed in on the next Preview / Render, with the other sound ducked under it.';this.chatLog.push({role:'system',text:this.voMsg});},
      error:e=>{this.voBusy=false;this.voMsg=e?.error?.error??'Could not generate the voiceover.';}});}
  // platform presets: the server list (caps.platforms) is used when loaded; this copy only keeps the chat commands working offline
  private static readonly PLATFORM_WORDS:[RegExp,string][]=[[/youtube shorts|\bshorts\b/,'youtube_shorts'],[/instagram reels|\breels\b/,'instagram_reels'],[/tiktok/,'tiktok'],[/instagram feed/,'instagram_feed'],
    [/instagram/,'instagram_reels'],[/linkedin/,'linkedin'],[/classroom|\blesson\b/,'classroom'],[/broadcast|\btv\b/,'broadcast'],[/youtube/,'youtube']];
  private static readonly PLATFORM_FALLBACK:Record<string,any>={youtube:{label:'YouTube',aspect:'LANDSCAPE_16_9',shortSide:1080,fps:30,loudness:'social'},youtube_shorts:{label:'YouTube Shorts',aspect:'VERTICAL_9_16',shortSide:1080,fps:30,loudness:'social'},
    instagram_reels:{label:'Instagram Reels',aspect:'VERTICAL_9_16',shortSide:1080,fps:30,loudness:'social'},tiktok:{label:'TikTok',aspect:'VERTICAL_9_16',shortSide:1080,fps:30,loudness:'social'},
    instagram_feed:{label:'Instagram feed',aspect:'PORTRAIT_4_5',shortSide:1080,fps:30,loudness:'social'},linkedin:{label:'LinkedIn',aspect:'SQUARE_1_1',shortSide:1080,fps:30,loudness:'social'},
    classroom:{label:'Classroom / lesson',aspect:'LANDSCAPE_16_9',shortSide:720,fps:30,loudness:'podcast'},broadcast:{label:'Broadcast',aspect:'LANDSCAPE_16_9',shortSide:1080,fps:25,loudness:'broadcast'}};
  /** Sets resolution, frame rate and loudness for a platform. The picture shape is the project's: a hint says when it differs. */
  applyPlatform(id:string):string{
    const p=this.caps?.platforms?.find((x:any)=>x.id===id)||VideoEditorComponent.PLATFORM_FALLBACK[id];if(!p)return '';
    this.finish.exportFormat='';this.finish.exportHeight=p.shortSide;this.finish.exportFps=p.fps;this.finish.loudness=p.loudness;this.finishDirty=true;
    const mine=(this.project as any)?.aspectRatio||this.form.aspectRatio;
    this.platformHint=mine&&p.aspect&&mine!==p.aspect?`${p.label} is usually ${String(p.aspect).replace('VERTICAL_','').replace('LANDSCAPE_','').replace('PORTRAIT_','').replace('SQUARE_','').replace('_',':')} but this project is ${String(mine).replace('VERTICAL_','').replace('LANDSCAPE_','').replace('PORTRAIT_','').replace('SQUARE_','').replace('_',':')} - change the project format if you want the platform's usual shape.`:'';
    return `${p.label}: ${p.shortSide}p, ${p.fps} fps, ${p.loudness==='social'?'-14':p.loudness==='podcast'?'-16':'-23'} LUFS. ${this.platformHint}`.trim();}
  /** Cardboard-style finishing commands; returns a reply, or null when the phrase is not one of them. */
  private interpretFinishCommand(raw:string,text:string):string|null {
    let m:RegExpMatchArray|null;
    const saved=()=>this.saveSettingsThen(()=>{});
    const TIME='(\\d+:\\d{2}|\\d+(?:\\.\\d+)?\\s*(?:s|sec|secs|seconds?)?)';
    // dub into another language / keep or replace the original voice
    if((m=text.match(/\bdub\b.*\b(?:into|to|in)\s+(hindi|kannada|tamil|telugu|malayalam|marathi|bengali|gujarati|english)\b/))){
      const lang=m[1][0].toUpperCase()+m[1].slice(1);this.startDub(lang);return `Dubbing the latest render into ${lang}…`;}
    if(/\b(replace|mute|silence|lower)\b.*\boriginal\b.*\b(voice|audio|sound|speech)\b/.test(text)){this.finish.voiceoverReplace=true;this.finishDirty=true;saved();return 'The voiceover now replaces the original voice (the original is lowered to about 16%).';}
    if(/\b(keep|restore|bring back)\b.*\boriginal\b.*\b(voice|audio|sound|speech)\b/.test(text)){this.finish.voiceoverReplace=false;this.finishDirty=true;saved();return 'The original voice is kept; it is only ducked under the voiceover.';}
    // voiceover from a script
    if((m=raw.match(/^\s*(?:ai\s+)?(?:voice ?over|narrat(?:e|ion))\s*[:\-]\s*([\s\S]{3,})$/i))){this.voScript=m[1].trim();this.generateVoiceover();return 'Generating the voiceover from your script…';}
    // text overlay / lower third / animated floating text at a time
    if((m=raw.match(/\b(?:add|put|show)\s+(?:a\s+)?(floating text|animated text|lower third|text|label|callout)\s*[:\-]?\s*["“']?(.+?)["”']?\s+(?:at|from)\s+(\d+:\d{2}|\d+(?:\.\d+)?\s*(?:s|sec|secs|seconds?)?)(?:\s+(?:for|to)\s+(\d+(?:\.\d+)?)\s*(?:s|sec|secs|seconds?)?)?(?:\s+(?:with\s+)?(fade|slide up|slide left|floating|pop|pulse)(?:\s+animation)?)?(?:\s+(behind (?:the )?person))?\s*$/i))){
      const start=this.toSec(m[3]),len=m[4]?+m[4]:3;
      const motion=String(m[5]||(/floating/i.test(m[1])?'floating':/animated/i.test(m[1])?'pop':'fade')).toLowerCase();
      const animation=motion==='floating'?'float':motion==='slide up'?'slide_up':motion==='slide left'?'slide_left':motion;
      this.addOverlay({type:m[6]?'text_behind_person':'text',start,end:start+len,text:m[2].trim(),position:'bottom',style:/lower/i.test(m[1])?'lower_third':'title',color:'white',animation,xPercent:50,yPercent:50});saved();
      return `Added “${m[2].trim()}” at ${this.clock(start)} for ${len} s with ${animation.replace('_',' ')} motion${m[6]?' behind the person':''}.`;}
    // sound effect at a time
    if((m=text.match(new RegExp('\\b(?:add|play|put|drop)\\s+(?:a\\s+|an\\s+|the\\s+)?(whoosh|sparkle|door|footstep|pop|click|tick|ding|success|error|swipe|riser|impact|typing|shutter|bubble|coin|notification|boing|tada)s?(?:\\s+sound(?:\\s+effect)?)?\\b.*?\\b(?:at|on)\\s+'+TIME)))){
      const start=this.toSec(m[2]);this.addOverlay({type:'sfx',start,end:start,sfx:m[1],gain:1});saved();return `Added a ${m[1]} sound at ${this.clock(start)}.`;}
    // zoom moment (not a transition)
    if(!/\btransitions?\b/.test(text)&&(m=text.match(new RegExp('\\bzoom(?:\\s+in)?\\b.*?\\b(?:at|on|around)\\s+'+TIME)))){
      const start=this.toSec(m[1]),big=/\b(a lot|more|big|strong)\b/.test(text),amt=big?1.6:1.3;this.addOverlay({type:'zoom',start,end:start+2,amount:amt});saved();
      return `Added a zoom moment at ${this.clock(start)} (×${amt}, 2 s).`;}
    if(/\b(remove|clear|delete|reset)\b.*\b(overlays?|text overlays?|zoom moments?|sound effects?)\b/.test(text)){
      const only=/sound effects?/.test(text)?'sfx':/zoom/.test(text)?'zoom':/text/.test(text)?'text':'';
      this.finish.overlays=only?this.finish.overlays.filter((o:any)=>only==='text'?(o.type!=='text'&&o.type!=='text_behind_person'):o.type!==only):[];this.finishDirty=true;saved();return only?`Removed the ${only==='sfx'?'sound effects':only==='zoom'?'zoom moments':'text overlays'}.`:'Removed all overlays.';}
    // look strength
    if(/\b(look|filter|grade|colou?r)\b/.test(text)&&(m=text.match(/(\d{1,3})\s*%/)||(/\bhalf\b/.test(text)?['','50']:null))){
      const v=Math.max(0,Math.min(100,+m[1]));this.finish.lookIntensity=v/100;this.finishDirty=true;saved();return `Look strength set to ${v}%.`;}
    // platform preset: "export for youtube shorts", "optimise for instagram reels"
    if((m=text.match(/\b(?:export|optimi[sz]e|publish|render|prepare)\b.*\b(?:for|to)\b\s+(?:the\s+)?(.+)$/))){
      const hit=VideoEditorComponent.PLATFORM_WORDS.find(([re])=>re.test(m![1]));
      if(hit){const msg=this.applyPlatform(hit[1]);saved();return msg;}}
    // loudness: "normalise the audio", "set loudness to -16 lufs", "turn off loudness normalisation"
    if(/\bloudness\b|\blufs\b|\b(normali[sz]e|even out)\b.*\b(audio|volume|sound)\b|\b(audio|volume|sound)\b.*\bnormali[sz]/.test(text)){
      if(/\b(no|off|disable|remove|without|stop)\b/.test(text)){this.finish.loudness='';this.finishDirty=true;saved();return 'Loudness normalisation is off.';}
      const t=/(-?\s?23)\s*lufs|\bbroadcast\b|\bebu\b/.test(text)?'broadcast':/(-?\s?16)\s*lufs|\bpodcast\b|\blesson\b|\bclassroom\b/.test(text)?'podcast':'social';
      this.finish.loudness=t;this.finishDirty=true;saved();
      return `Loudness set to ${t==='social'?'-14':t==='podcast'?'-16':'-23'} LUFS (${t}) - applied on the next final Render, picture untouched.`;}
    // export settings
    if(/\bexport\b|\b(save|render) (it )?as\b|\b(1080|720|480|1440|2160)p\b|\b4k\b|\b\d{2}\s?fps\b|\bwebm\b|\bmkv\b/.test(text)&&!/\b(make|set)\b.*\bseconds?\b/.test(text)){
      const fmt=text.match(/\b(webm|mov|mkv|mp4)\b/),res=text.match(/\b(480|720|1080|1440|2160)p\b/),fps=text.match(/\b(24|25|30|50|60)\s?fps\b/);
      if(!fmt&&!res&&!fps&&!/\b4k\b/.test(text))return null;
      if(fmt)this.finish.exportFormat=fmt[1]==='mp4'?'':fmt[1];
      if(res)this.finish.exportHeight=+res[1];else if(/\b4k\b/.test(text))this.finish.exportHeight=2160;
      if(fps)this.finish.exportFps=+fps[1];
      this.finishDirty=true;saved();
      return `Export set to ${(this.finish.exportFormat||'mp4').toUpperCase()}${this.finish.exportHeight?', '+this.finish.exportHeight+'p':''}${this.finish.exportFps?', '+this.finish.exportFps+' fps':''} - applied on the next final Render.`;}
    // transitions between shots
    if(/\btransitions?\b|\bbetween (every|all|the) (clips?|shots?|cuts?)\b/.test(text)){
      if(/\b(no|remove|clear|without|delete)\b.*\btransitions?\b/.test(text)){const n=this.applyTransition('',0);return n?`Cleared the transitions on ${n} cuts.`:'There are no cuts to clear.';}
      const dir=(text.match(/\b(left|right|up|down)\b/)||[,'left'])[1];
      const hit=VideoEditorComponent.TRANSITION_WORDS.find(([re])=>re.test(text));
      if(!hit)return null;
      const id=hit[1].endsWith('_')?hit[1]+dir:hit[1],after=text.match(/\bafter (?:shot|clip)\s*#?(\d+)/),secs=text.match(/(\d+(?:\.\d+)?)\s*(?:s|secs?|seconds?)\b/);
      const n=this.applyTransition(id,after?+after[1]:0,secs?+secs[1]:null);
      return n?`Set “${hit[2]}${hit[1].endsWith('_')?' ('+dir+')':''}” ${after?'after shot '+after[1]:'on all '+n+' cuts'}. Press Preview to see it.`:'You need at least two shots in the timeline first.';}
    return null;
  }
  private interpretSmartCommand(raw:string,text:string):string|null {
    let m:RegExpMatchArray|null;
    const fin=this.interpretFinishCommand(raw,text);if(fin!==null)return fin;
    const off=/\b(no|not|without|remove|turn off|disable|hide|stop|don'?t)\b/.test(text);
    const saved=()=>this.saveSettingsThen(()=>{});

    // speech cleanup / highlights (run as jobs)
    if(/\b(clean ?up|remove|cut|get rid of|delete|trim)\b.*\b(dead air|silences?|fillers?|filler words?|ums?|uhs?|retakes?|pauses|mistakes|bloopers)\b|clean ?up (the )?(speech|talk|talking)/.test(text)){
      this.cleanupSpeech();return 'Cleaning up the speech: cutting dead air, filler words and retakes. Watch the progress bar above.';
    }
    if(/\b(best|top|viral|shareable|highlight|highlights)\b.*\b(moments?|parts?|clips?|shorts?|bits)\b|\b(find|pull|get)\b.*\b(moments|highlights)\b|\b(make|create|cut)\b.*\bshorts?\b.*\b(from|out of)\b/.test(text)){
      this.findMoments();return 'Looking for the best moments in your recording…';
    }
    const sq=raw.match(/(?:where (?:do|did) i (?:say|said|mention|talk about)|find (?:the (?:part|moment|bit) (?:where|about|when)|where i (?:say|said|mention)|when i (?:say|said|mention))|search (?:for|my footage for|the speech for))\s+["“]?(.+?)["”]?\s*\??$/i);
    if(sq){this.searchQuery=sq[1].trim();this.searchSpeech();return `Searching your footage for “${this.searchQuery}”…`;}
    if(/^\s*undo\b/.test(text)){
      if(!this.project)return 'Open a project first.';
      this.api.videoEditorVersions(this.project.id).subscribe({next:v=>{
        if(v.length<2){this.chatLog.push({role:'system',text:'There is no earlier version to go back to.'});return;}
        this.restoreVersion(v[1]);this.chatLog.push({role:'system',text:'Went back to the previous version of the edit.'});
      },error:()=>this.chatLog.push({role:'system',text:'Could not read the version history.'})});
      return 'Looking for the previous version…';
    }

    // project settings
    if(/\b(captions?|subtitles?)\b/.test(text)&&!/\b(bigger|smaller|larger|font|size|position|higher|lower|colou?r|faster|slower|wrong|typo|fix|edit|change the)\b/.test(text)){
      this.form.autoCaptions=!off;
      const st=text.match(/\b(bold|clean|kids|cinematic|minimal|stacked|line reveal|line by line)\b/);
      if(st&&!off)this.captionStyle=st[1]==='bold'?'BOLD_REEL':st[1].startsWith('line')?'LINE_REVEAL':st[1].toUpperCase();
      saved();
      return off?'Captions turned off.':`Captions on (${this.captionStyle.toLowerCase().replace('_',' ')} style) - they are added when you render the final video.`;
    }
    if((m=raw.match(/\b(?:add|set|put|use|change)\s+(?:a\s+|the\s+)?title\b\s*(?:to|as|:|-)?\s*["“]?(.+?)["”]?\s*$/i))&&!off){
      this.form.titleText=m[1].trim();saved();return `Title set to "${this.form.titleText}" - shown over the first 3 seconds of the final video.`;
    }
    if(/\b(remove|no|delete|clear)\s+(the\s+)?title\b/.test(text)){this.form.titleText='';saved();return 'Title removed.';}
    // film effects: "add film grain", "vignette", "remove effects"
    const EFFECT_WORDS:[RegExp,string,string][]=[[/film grain|grain|noisy film/,'film_grain','Film grain'],[/vignette/,'vignette','Vignette'],[/scan ?lines|crt/,'scanlines','Scanlines'],
      [/halation/,'halation','Halation'],[/\bglow\b|dreamy/,'glow','Soft glow'],[/soft (blur|focus)|\bblur\b/,'soft_blur','Soft blur'],[/letterbox|cinematic bars|black bars|widescreen bars/,'letterbox','Cinematic bars']];
    if(/\b(remove|no|clear|turn off)\s+(all\s+)?(the\s+)?effects\b/.test(text)){this.form.effects='';saved();return 'All effects removed.';}
    for(const [re,id,label] of EFFECT_WORDS){
      if(re.test(text)&&/\b(add|apply|use|give|put|turn on|remove|no|without|disable|make it)\b|\beffect\b|^(film grain|vignette|halation|scanlines)/.test(text)){
        const cur=(this.form.effects||'').split(',').filter((x:string)=>x);
        this.form.effects=(off?cur.filter((x:string)=>x!==id):cur.includes(id)?cur:[...cur,id]).join(',');
        saved();return off?`${label} removed.`:`${label} added - press Preview to see it.`;
      }
    }
    if(/\b(no|original|remove)\s+(the\s+)?(filter|look|colou?rs?|grade)\b/.test(text)){this.form.look='none';saved();return 'Look set to original colours.';}
    if(/\b(look|filter|grade|colou?rs?|tone|mood)\b|\bmake it\s+(warm|cool|vivid|moody|vintage|dramatic|pastel|bright|black)/.test(text)){
      for(const [re,id,label] of VideoEditorComponent.LOOK_WORDS){
        if(re.test(text)){this.form.look=id;saved();return `Look set to ${label}. Press Preview to see it.`;}
      }
    }
    if(/\bbeat ?sync\b|\bsync\w*\b.*\b(beat|music)\b|\bcuts? (?:to|on) the beat\b/.test(text)){
      this.form.beatSync=!off;
      if(this.analysisDone()){this.regenerateWithInstruction();return off?'Beat sync off - re-editing.':'Beat sync on - re-editing so cuts land on the beat (add a music track first).';}
      saved();return off?'Beat sync off.':'Beat sync on - it is applied when the AI edit runs (add a music track first).';
    }
    if(/\bstabili[sz]|shaky|steady cam/.test(text)){this.form.stabilize=!off;saved();return off?'Stabilize off.':'Stabilize on - applied when you preview or render.';}
    if(/\b(enhance|improve|clean|denoise)\b.*\b(audio|sound|voice)\b|\bnoise\b/.test(text)){this.form.audioEnhancement=!off;saved();return off?'Audio enhancement off.':'Audio enhancement on.';}
    if(/\b(vertical|reels?|tiktok|9:16|portrait|stories|for shorts|youtube shorts)\b|\b(horizontal|landscape|16:9|widescreen|youtube)\b|\b(square|1:1|4:5)\b/.test(text)&&/\b(make|convert|change|switch|set|format|for|to|into|as)\b/.test(text)){
      const ratio=/\b(horizontal|landscape|16:9|widescreen|youtube)\b/.test(text)&&!/shorts/.test(text)?'LANDSCAPE_16_9':/4:5/.test(text)?'PORTRAIT_4_5':/square|1:1/.test(text)?'SQUARE_1_1':'VERTICAL_9_16';
      this.form.aspectRatio=ratio;
      if(this.analysisDone()){this.regenerateWithInstruction();return `Canvas set to ${ratio.replace('_',' ')} - re-editing to fit it.`;}
      saved();return `Canvas set to ${ratio.replace('_',' ')}.`;
    }

    // per-shot edits
    if((m=text.match(/\b(speed up|faster|fast forward|slow down|slower|slow ?mo(?:tion)?)\b.*?\b(?:shot|clip)\s*#?(\d+)(?:.*?(\d+(?:\.\d+)?)\s*x)?|\b(?:shot|clip)\s*#?(\d+)\b.*?\b(speed up|faster|slow down|slower|slow ?mo(?:tion)?)(?:.*?(\d+(?:\.\d+)?)\s*x)?/))){
      const idx=+(m[2]??m[4]);const word=(m[1]??m[5]??'');const factorTxt=m[3]??m[6];
      const slow=/slow/.test(word);const factor=factorTxt?+factorTxt:(slow?0.5:1.5);
      return this.commandOnRow(idx,slow?'slowed down':'sped up',(i)=>{const r=this.timeline[i];r.speed=Math.max(0.25,Math.min(4,slow?(factorTxt?1/factor:factor):factor));this.saveTimeline();});
    }
    if((m=text.match(/\btrim\s+(?:shot|clip)\s*#?(\d+)\s+(?:to|down to)\s+(\d+(?:\.\d+)?)\s*(?:s|sec|secs|seconds?)\b/))){
      const secs=+m[2];
      return this.commandOnRow(+m[1],`trimmed to ${secs}s`,(i)=>{const r=this.timeline[i];const end=r.sourceStartSec+secs*(r.speed||1);if(end<r.sourceEndSec){r.sourceEndSec=end;this.saveTimeline();}});
    }
    // pacing: "make the intro faster", "tighter pacing"
    if(/\b(faster|tighter|snappier|punchier|tighten|shorter)\b.*\b(pacing|pace|edit|cuts|video|intro|opening|start|it|shots)\b|\bmake (the )?(intro|opening|start) (faster|shorter|tighter)\b/.test(text)){
      if(!this.timeline.length)return 'There is no timeline yet - run the AI edit first.';
      const introOnly=/\b(intro|opening|start)\b/.test(text);
      let n=0;
      this.timeline.forEach((r,i)=>{
        if(r.locked||(introOnly&&i>1))return;
        const dur=(r.sourceEndSec-r.sourceStartSec)/(r.speed||1);const next=Math.max(0.9,dur*0.8);
        if(next<dur-0.05){r.sourceEndSec=r.sourceStartSec+next*(r.speed||1);n++;}
      });
      if(!n)return 'The shots are already as short as they can safely be.';
      this.saveTimeline();return `Tightened ${n} shot(s) by about 20%.`;
    }
    if(/\b(slower|let it breathe|longer shots|relax)\b.*\b(pacing|pace|edit|cuts|shots|it)\b/.test(text)){
      let n=0;
      this.timeline.forEach(r=>{
        if(r.locked)return;
        const len=this.clips.find(c=>c.id===r.clipId)?.durationSec??0;const room=len-r.sourceEndSec;
        const add=Math.min(room-0.05,(r.sourceEndSec-r.sourceStartSec)*0.25);
        if(add>0.2){r.sourceEndSec+=add;n++;}
      });
      if(!n)return 'None of the shots has spare footage to extend into.';
      this.saveTimeline();return `Gave ${n} shot(s) about 25% more room.`;
    }
    return null;
  }
  exportUrl(fmt:'edl'|'fcpxml'){return this.api.videoExportUrl(this.project!.id,fmt);}
  private refreshTranscriptStatus(){
    if(!this.project)return;
    this.api.videoTranscriptStatus(this.project.id).subscribe({next:s=>this.transcriptStatus=s,error:()=>{}});
  }
  indexSpeech(){
    if(!this.project)return;
    const id=this.project.id;
    this.run(()=>this.api.transcribeVideoProject(id),()=>{
      this.refreshTranscriptStatus();
      this.searchNote=((this.job as any)?.detail||'Speech indexed.');
    });
  }
  searchSpeech(){
    const q=this.searchQuery.trim();
    if(!q||!this.project)return;
    const id=this.project.id;
    this.api.videoTranscriptStatus(id).subscribe({next:s=>{
      this.transcriptStatus=s;
      if(s.indexedClips===0){this.hits=[];this.searchNote='Index the speech first (button on the right) - it listens to your clips once so they become searchable.';return;}
      this.api.searchVideoTranscript(id,q).subscribe({next:h=>{
        this.hits=h;
        this.searchNote=h.length?`${h.length} moment(s) found${s.indexedClips<s.totalClips?` (only ${s.indexedClips} of ${s.totalClips} clips are indexed)`:''}.`:'Nothing matches that - try fewer or different words.';
      },error:e=>this.searchNote=e?.error?.error??'Search failed.'});
    },error:()=>this.searchNote='Could not check the speech index.'});
  }
  previewHit(h:VideoSpeechHit){
    this.selectedClipId=h.clipId;
    this.sourceUrl=this.sourceVideo(h.clipId)+'#t='+Math.max(0,h.start-0.2).toFixed(2)+','+(h.end+0.3).toFixed(2);
  }
  insertHit(h:VideoSpeechHit){
    if(!this.project)return;
    const id=this.project.id;
    const after=this.selectedRowIndex>=0?this.selectedRowIndex:undefined;
    this.api.insertVideoSegment(id,{clipId:h.clipId,startSec:Math.max(0,h.start-0.2),endSec:h.end+0.3,afterIndex:after}).subscribe({
      next:()=>{this.loadTimelineThen(()=>{});this.chatLog.push({role:'system',text:`Inserted “${h.text.slice(0,60)}${h.text.length>60?'…':''}” ${after!==undefined?'after shot '+(after+1):'at the end'}.`});},
      error:e=>this.chatLog.push({role:'system',text:e?.error?.error??e?.error?.message??'Could not insert that moment.'})});
  }
  cleanupSpeech(){
    if(!this.project)return;
    const projectId=this.project.id;
    this.chatLog.push({role:'system',text:'Listening for dead air, filler words and retakes…'});
    this.run(()=>this.api.cleanupVideoSpeech(projectId,true),()=>{
      const note=(this.job as any)?.detail||'Speech cleaned up.';
      this.loadTimelineThen(()=>{this.chatLog.push({role:'system',text:note+' Press Preview or Render to see it.'});});
    });
  }
  findMoments(){
    if(!this.project)return;
    const projectId=this.project.id;
    this.moments=[];
    this.run(()=>this.api.findVideoHighlights(projectId),()=>{
      const note=(this.job as any)?.detail||'Moments found.';
      this.api.videoHighlights(projectId).subscribe({next:m=>{this.moments=m;this.chatLog.push({role:'system',text:note+' Pick one below to turn it into a vertical short.'});},
        error:()=>this.chatLog.push({role:'system',text:'Could not load the moments.'})});
    });
  }
  useMoment(index:number){
    if(!this.project)return;
    const id=this.project.id;
    this.api.useVideoHighlight(id,index,true,true).subscribe({next:r=>{
      this.form.aspectRatio='VERTICAL_9_16';this.form.autoCaptions=true;   // keep the form in step with the server so a later save does not undo it
      this.chatLog.push({role:'system',text:r.summary});this.loadTimelineThen(()=>{});this.reload();
    },error:e=>this.chatLog.push({role:'system',text:e?.error?.error??e?.error?.message??'Could not use that moment.'})});
  }
  private commandOnRow(oneBasedIndex:number,verb:string,run:(zeroBasedIndex:number)=>void):string {
    const i=oneBasedIndex-1;
    if(i<0||i>=this.timeline.length){
      return `There's no shot ${oneBasedIndex} - the timeline currently has ${this.timeline.length} shot(s).`;
    }
    // Some deterministic commands use commit()-based operations (which already
    // record history); others update a row directly. Capture the pre-command
    // state so both paths remain undoable without double-recording the former.
    const before=this.snapshot();
    const historyLength=this.history.length;
    run(i);
    if(this.history.length===historyLength){this.pushHistory(before);this.redoStack=[];}
    this.timelineDirty=true;
    return `Shot ${oneBasedIndex} ${verb}.`;
  }
  onMusicPicked(e:Event){const i=e.target as HTMLInputElement;const f=i.files?.[0];i.value='';if(!f||!this.project)return;this.api.uploadVideoMusic(this.project.id,f).subscribe({next:()=>this.musicName=f.name,error:er=>this.uploadError=er?.error?.error??'Could not add music.'})}
  clipName(id:string){return this.clips.find(c=>c.id===id)?.displayName??'clip'}
  totalDuration(){return this.formatDuration(this.timeline.reduce((s,r)=>s+r.outputDurationSec,0))}
  formatDuration(sec:number){const t=Math.round(sec),m=Math.floor(t/60),s=t%60;return `${m}:${s.toString().padStart(2,'0')}`}
  onKeyDown(e:KeyboardEvent){if(e.target instanceof HTMLInputElement||e.target instanceof HTMLTextAreaElement||e.target instanceof HTMLSelectElement)return;if(e.key==='Delete'){e.preventDefault();this.deleteSelected()}else if(e.key==='ArrowLeft'&&e.shiftKey){e.preventDefault();this.moveTimeline(-1)}else if(e.key==='ArrowRight'&&e.shiftKey){e.preventDefault();this.moveTimeline(1)}else if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='z'){e.preventDefault();if(e.shiftKey)this.redo();else this.undo()}else if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='y'){e.preventDefault();this.redo()}}
  pretty(v:string){return v.replace(/_/g,' ').toLowerCase().replace(/^./,c=>c.toUpperCase())}
}
