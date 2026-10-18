import { AfterViewInit, Component, ElementRef, OnDestroy, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import * as THREE from 'three';
import { Rive } from '@rive-app/canvas';

/** Optional interactive preview of the open-source visual engines. Production video timing remains backend-owned. */
@Component({
  selector: 'app-visual-engine-preview', standalone: true, imports: [CommonModule],
  template: `
    <section class="engine-card">
      <div class="engine-heading"><div><span class="eyebrow">OPEN-SOURCE VISUAL ENGINES</span><h2>Preview richer visuals</h2><p>Try a live 3D system scene or preview a Rive animation asset. Motion Canvas and Manim are available as render-worker options.</p></div></div>
      <div class="engine-tabs">
        <button type="button" [class.active]="mode==='three'" (click)="select('three')">Three.js · 3D</button>
        <button type="button" [class.active]="mode==='rive'" (click)="select('rive')">Rive · character/UI</button>
        <button type="button" [class.active]="mode==='motion'" (click)="select('motion')">Motion Canvas · 2D</button>
      </div>
      <div class="engine-stage" [hidden]="mode!=='three'"><canvas #threeCanvas></canvas><div class="stage-caption">Request → API → worker → database · animated packets show the flow</div></div>
      <div class="engine-stage rive-stage" [hidden]="mode!=='rive'"><canvas #riveCanvas></canvas><div class="rive-actions"><label class="upload-rive">Load .riv animation<input type="file" accept=".riv,application/octet-stream" (change)="loadRive($event)"></label><span>{{riveStatus}}</span></div></div>
      <div class="engine-stage motion-stage" [hidden]="mode!=='motion'"><div class="motion-demo"><div class="motion-node source">User request</div><div class="motion-arrow">→</div><div class="motion-node active-node">Queue</div><div class="motion-arrow">→</div><div class="motion-node target">Worker</div><div class="motion-packet"></div></div><p>Motion Canvas is the planned production 2D renderer for synchronized diagrams, timelines and cause/effect scenes. The demo above is a lightweight preview, not a rendered lesson clip.</p></div>
    </section>
  `,
  styles: [`
    :host{display:block;margin:18px 0}.engine-card{border:1px solid var(--border,#283344);border-radius:18px;padding:20px;background:var(--surface,#101722);color:var(--text,#edf3ff)}
    .engine-heading h2{margin:6px 0;font-size:20px}.engine-heading p{margin:0 0 14px;color:var(--muted,#9caac0);font-size:13px}.eyebrow{font-size:10px;letter-spacing:1.5px;color:#7dd3fc;font-weight:800}
    .engine-tabs{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px}.engine-tabs button,.upload-rive{border:1px solid #35465d;background:#172233;color:#dbeafe;border-radius:10px;padding:9px 12px;cursor:pointer;font:inherit;font-size:12px}.engine-tabs button.active{background:#164e63;border-color:#22d3ee;color:#ecfeff}
    .engine-stage{position:relative;height:280px;overflow:hidden;border-radius:13px;background:radial-gradient(ellipse at 50% 30%,#182d4c,#080e19 72%)}.engine-stage[hidden]{display:none}.engine-stage canvas{width:100%;height:100%;display:block}.stage-caption{position:absolute;bottom:10px;left:12px;right:12px;text-align:center;color:#bfdbfe;font-size:12px;pointer-events:none}
    .rive-stage{display:flex;align-items:center;flex-direction:column}.rive-stage canvas{height:220px}.rive-actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap;font-size:12px;color:#b8c5d9}.upload-rive input{display:none}.motion-stage{padding:28px 18px;box-sizing:border-box}.motion-demo{display:flex;align-items:center;justify-content:center;gap:clamp(6px,2vw,24px);position:relative;height:150px}.motion-node{border:1px solid #47607f;border-radius:12px;background:#17263b;padding:15px 12px;min-width:70px;text-align:center;font-size:12px;box-shadow:0 8px 28px #0005}.source{border-color:#60a5fa}.target{border-color:#34d399}.active-node{animation:queuePulse 1.8s ease-in-out infinite;border-color:#fbbf24}.motion-arrow{font-size:25px;color:#67e8f9;animation:arrowPulse 1.2s ease-in-out infinite}.motion-packet{position:absolute;top:62px;left:18%;width:9px;height:9px;border-radius:50%;background:#67e8f9;box-shadow:0 0 15px #67e8f9;animation:packet 3s linear infinite}.motion-stage p{font-size:12px;line-height:1.5;color:#aab9cd;margin:0;text-align:center}
    @keyframes queuePulse{50%{transform:translateY(-5px);box-shadow:0 0 22px #fbbf2438}}@keyframes arrowPulse{50%{opacity:.45;transform:translateX(3px)}}@keyframes packet{0%{left:18%;opacity:0}10%{opacity:1}85%{opacity:1}100%{left:82%;opacity:0}}
  `]
})
export class VisualEnginePreviewComponent implements AfterViewInit, OnDestroy {
  @ViewChild('threeCanvas') private threeCanvas?: ElementRef<HTMLCanvasElement>;
  @ViewChild('riveCanvas') private riveCanvas?: ElementRef<HTMLCanvasElement>;
  mode: 'three'|'rive'|'motion' = 'three';
  riveStatus = 'Choose a .riv file to preview it locally.';
  private renderer?: THREE.WebGLRenderer; private scene?: THREE.Scene; private camera?: THREE.PerspectiveCamera;
  private frame = 0; private resizeObserver?: ResizeObserver; private rive?: Rive; private disposed = false;
  ngAfterViewInit(): void { this.initThree(); }
  select(mode: 'three'|'rive'|'motion'): void { this.mode = mode; if (mode === 'three' && !this.renderer) setTimeout(() => this.initThree()); }
  private initThree(): void {
    const canvas = this.threeCanvas?.nativeElement; if (!canvas || this.renderer || this.disposed) return;
    try {
      this.scene = new THREE.Scene(); this.scene.background = new THREE.Color('#080e19');
      this.camera = new THREE.PerspectiveCamera(42, 1, 0.1, 100); this.camera.position.set(0, 0.5, 8);
      this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: false }); this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
      this.scene.add(new THREE.AmbientLight(0xffffff, 1.7)); const light = new THREE.PointLight(0x7dd3fc, 45); light.position.set(2, 4, 5); this.scene.add(light);
      const points = [{x:-2.4,y:.4,z:0,c:0x60a5fa},{x:0,y:.4,z:0,c:0xfbbf24},{x:2.4,y:.4,z:0,c:0x34d399}];
      points.forEach((p,i)=>{ const mesh = new THREE.Mesh(new THREE.BoxGeometry(1.1,1.1,.7), new THREE.MeshStandardMaterial({color:p.c,metalness:.22,roughness:.35,emissive:p.c,emissiveIntensity:.08})); mesh.position.set(p.x,p.y,p.z); mesh.userData.baseY=p.y; mesh.userData.index=i; this.scene!.add(mesh); const edges=new THREE.LineSegments(new THREE.EdgesGeometry(mesh.geometry),new THREE.LineBasicMaterial({color:0xdbeafe,transparent:true,opacity:.75})); edges.position.copy(mesh.position); this.scene!.add(edges); });
      const makeLink=(x1:number,x2:number)=>{const curve=new THREE.CatmullRomCurve3([new THREE.Vector3(x1,-.55,0),new THREE.Vector3((x1+x2)/2,-.9,.1),new THREE.Vector3(x2,-.55,0)]);this.scene!.add(new THREE.Mesh(new THREE.TubeGeometry(curve,24,.025,6,false),new THREE.MeshBasicMaterial({color:0x67e8f9,transparent:true,opacity:.9})));}; makeLink(-1.85, -0.55); makeLink(.55,1.85);
      const packet=new THREE.Mesh(new THREE.SphereGeometry(.11,16,12),new THREE.MeshBasicMaterial({color:0xffffff})); packet.userData.packet=true; this.scene.add(packet);
      const fit=()=>{if(!this.renderer||!this.camera||!canvas.isConnected)return;const w=Math.max(1,canvas.clientWidth),h=Math.max(1,canvas.clientHeight);this.renderer.setSize(w,h,false);this.camera.aspect=w/h;this.camera.updateProjectionMatrix();}; this.resizeObserver=new ResizeObserver(fit); this.resizeObserver.observe(canvas); fit();
      const animate=(time:number)=>{if(this.disposed||!this.renderer||!this.scene||!this.camera)return;const t=time/1000;this.scene.children.forEach((o:any)=>{if(o.isMesh&&o.userData.index!==undefined){o.rotation.y=Math.sin(t*.7+o.userData.index)*.12;o.position.y=o.userData.baseY+Math.sin(t*1.4+o.userData.index)*.08;}if(o.userData.packet){const u=(t%3)/3;o.position.set(-1.85+u*3.7,-.65+Math.sin(u*Math.PI)*-.2,.2);}});this.renderer.render(this.scene,this.camera);this.frame=requestAnimationFrame(animate);};this.frame=requestAnimationFrame(animate);
    } catch { this.riveStatus='WebGL is unavailable in this browser; use the 2D preview.'; }
  }
  async loadRive(event: Event): Promise<void> {
    const file=(event.target as HTMLInputElement).files?.[0]; if(!file)return;
    if(!file.name.toLowerCase().endsWith('.riv')){this.riveStatus='Please choose a .riv file.';return;}
    try { this.rive?.cleanup(); const buffer=await file.arrayBuffer(); const canvas=this.riveCanvas?.nativeElement; if(!canvas) return; this.rive=new Rive({buffer,canvas,autoplay:true, onLoad:()=>{this.riveStatus=`Playing ${file.name} locally.`;this.rive?.resizeDrawingSurfaceToCanvas();}, onLoadError:()=>{this.riveStatus='This .riv asset could not be loaded by the installed runtime.';}}); this.mode='rive'; }
    catch {this.riveStatus='Could not load this Rive file.';}
  }
  ngOnDestroy(): void { this.disposed=true; cancelAnimationFrame(this.frame); this.resizeObserver?.disconnect(); this.rive?.cleanup(); this.renderer?.dispose(); this.scene?.clear(); }
}
