import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-video-workflow',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <header class="page-head">
      <p class="eyebrow">VIDEO WORKFLOW</p>
      <h1>{{ isH3 ? 'MiniMax H3 workflow' : 'Wan 2.2 workflow' }}</h1>
      <p class="muted">{{ isH3 ? 'Independent native ComfyUI workflow configuration for MiniMax H3. This is not the MiniMax API.' : 'Independent ComfyUI workflow configuration for the existing Wan 2.2 pipeline.' }}</p>
    </header>

    <section class="panel">
      <div class="status-row"><span class="tag tag-teal">Local ComfyUI</span><span class="muted">Selected independently from Video Generation</span></div>
      <div class="field"><label>Workflow key</label><input [(ngModel)]="workflowKey" readonly></div>
      <div class="field"><label>Workflow template</label><input [(ngModel)]="templateName" readonly></div>
      <div class="field"><label>Mode</label><select [(ngModel)]="mode"><option value="i2v">Image to video</option><option value="t2v">Text to video</option><option *ngIf="isH3" value="r2v">Reference to video</option></select></div>
      <div class="grid">
        <div class="field"><label>Width</label><input type="number" [(ngModel)]="width"></div>
        <div class="field"><label>Height</label><input type="number" [(ngModel)]="height"></div>
        <div class="field"><label>Steps</label><input type="number" [(ngModel)]="steps"></div>
        <div class="field"><label>Max duration (seconds)</label><input type="number" [(ngModel)]="maxDuration"></div>
      </div>
      <div class="notice" *ngIf="isH3">
        <strong>MiniMax H3 local setup</strong>
        <p>The H3 workflow is already bundled as a native ComfyUI graph. Select Image-to-Video, Text-to-Video, or Reference-to-Video; the application fills and queues the matching local graph.</p>
        <p class="muted">ComfyUI 0.30.0+ is required for native H3 support. The H3 model files are separate from Wan.</p>
      </div>
      <div class="notice" *ngIf="!isH3">
        <strong>Wan 2.2 preserved</strong>
        <p>TI2V-5B (720p at 24fps, text or image to video) and I2V-A14B (best quality, image to video, 480p at 16fps) are picked on the Video Generation page.</p>
      </div>
    </section>
  `,
  styles: [`
    .page-head{margin-bottom:1.6rem;max-width:760px}.eyebrow{font-size:.68rem;letter-spacing:.16em;color:var(--accent);font-weight:800}.panel{max-width:760px;display:flex;flex-direction:column;gap:1rem}.field{display:flex;flex-direction:column;gap:.4rem}.grid{display:grid;grid-template-columns:1fr 1fr;gap:1rem}.status-row{display:flex;align-items:center;gap:.7rem}.notice{border:1px solid var(--border);border-radius:12px;padding:1rem;background:var(--surface)}code{background:var(--surface-raised);padding:.15rem .35rem;border-radius:4px}@media(max-width:600px){.grid{grid-template-columns:1fr}}
  `]
})
export class VideoWorkflowComponent implements OnInit {
  isH3 = false;
  workflowKey = 'WAN_2_2';
  templateName = 'wan-ti2v-5b-image-to-video / wan22-i2v-a14b';
  mode = 'i2v';
  width = 704;
  height = 1280;
  steps = 30;
  maxDuration = 5;
  constructor(private route: ActivatedRoute) {}
  ngOnInit(): void {
    this.isH3 = this.route.snapshot.data['workflow'] === 'MINIMAX_H3';
    if (this.isH3) { this.workflowKey = 'MINIMAX_H3'; this.templateName = 'minimax-h3-image-to-video'; this.width = 480; this.height = 832; this.steps = 20; this.maxDuration = 10; }
  }
}
