import express from 'express';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { chromium } from 'playwright';
import * as esbuild from 'esbuild';
import { fileURLToPath as urlToPath } from 'node:url';

const app = express(); app.use(express.json({limit:'40mb'}));
const ROOT = path.dirname(fileURLToPath(import.meta.url));
const safe = v => String(v ?? '').replace(/[<>]/g,'').slice(0,1200);
const run = (cmd,args,opts={}) => new Promise((resolve,reject)=>{ const p=spawn(cmd,args,{stdio:['ignore','pipe','pipe'],...opts});let out='',err='';p.stdout.on('data',d=>out+=d);p.stderr.on('data',d=>err+=d);p.on('error',reject);p.on('close',c=>c===0?resolve({out,err}):reject(new Error(`${cmd} failed (${c}): ${err.slice(-1800)}`)));});
app.get('/health',(_,res)=>res.json({status:'ok',engines:{MOTION_CANVAS:false,THREE_JS:true,RIVE:true},capabilities:{MOTION_CANVAS:{available:false,reason:'Server-side headless export is not configured'},THREE_JS:{available:true,reason:'Playwright Chromium and Three.js renderer are installed'},RIVE:{available:true,reason:'Requires a valid .riv asset per scene'}},version:2}));

async function threeFrames(req, outDir, count) {
  const bundle = await esbuild.build({stdin:{contents:`import * as THREE from 'three'; window.THREE=THREE;`,resolveDir:ROOT,sourcefile:'three-entry.js',loader:'js'},bundle:true,format:'iife',write:false});
  const browser=await chromium.launch({headless:true,args:['--no-sandbox','--use-gl=angle','--use-angle=swiftshader']});
  try { const page=await browser.newPage({viewport:{width:req.width||1920,height:req.height||1080},deviceScaleFactor:1});
    await page.setContent(`<html><body style="margin:0;background:#07111f"><canvas id="c" style="width:100vw;height:100vh;display:block"></canvas><script>${bundle.outputFiles[0].text}</script></body></html>`);
    await page.evaluate(({title,labels})=>{const T=window.THREE,c=document.querySelector('#c');const r=new T.WebGLRenderer({canvas:c,antialias:true,alpha:false});r.setSize(innerWidth,innerHeight);r.setPixelRatio(1);r.setClearColor('#07111f');const s=new T.Scene();s.background=new T.Color('#07111f');s.add(new T.AmbientLight(0xffffff,1.7));const l=new T.PointLight(0x8bdcff,90);l.position.set(1,5,7);s.add(l);const cam=new T.PerspectiveCamera(42,innerWidth/innerHeight,.1,100);cam.position.set(0,1,9);const mats=[0x60a5fa,0xfbbf24,0x34d399];const nodes=[];for(let i=0;i<3;i++){const m=new T.Mesh(new T.BoxGeometry(1.3,1.25,.7),new T.MeshStandardMaterial({color:mats[i],metalness:.25,roughness:.3,emissive:mats[i],emissiveIntensity:.1}));m.position.set((i-1)*2.6,0,0);s.add(m);nodes.push(m);const cv=document.createElement('canvas');cv.width=512;cv.height=128;const ctx=cv.getContext('2d');ctx.fillStyle='#fff';ctx.font='bold 44px sans-serif';ctx.textAlign='center';ctx.fillText((labels[i]||['Task A','Task B','Task C'][i]).slice(0,20),256,76);const tex=new T.CanvasTexture(cv);const sp=new T.Sprite(new T.SpriteMaterial({map:tex,transparent:true}));sp.position.set((i-1)*2.6,1.15,0);sp.scale.set(2,0.5,1);s.add(sp);}const pts=[new T.Vector3(-1.9,-.8,0),new T.Vector3(0,-.8,.3),new T.Vector3(1.9,-.8,0)];s.add(new T.Mesh(new T.TubeGeometry(new T.CatmullRomCurve3(pts),40,.025,6,false),new T.MeshBasicMaterial({color:0x67e8f9})));const titleEl=document.createElement('div');titleEl.style='position:absolute;top:4%;left:4%;right:4%;color:#eff6ff;font:700 34px sans-serif;text-align:center;text-shadow:0 2px 8px #000';titleEl.textContent=title;s.add(cam);document.body.appendChild(titleEl);window.renderAt=t=>{nodes.forEach((n,i)=>{n.rotation.y=Math.sin(t*.8+i)*.16;n.position.y=Math.sin(t*1.3+i)*.12});cam.position.x=Math.sin(t*.18)*.55;cam.lookAt(0,0,0);r.render(s,cam)};window.renderAt(0);}, {title:safe(req.title),labels:req.labels||[]});
    const frames=[];for(let i=0;i<count;i++){await page.evaluate(t=>window.renderAt(t),i*.42);frames.push((await page.locator('body').screenshot({type:'png'})).toString('base64'));}return frames;
  } finally {await browser.close();}
}

async function riveFrames(req,count){
  if(!req.riveAssetBase64) throw new Error('Rive scene requires a valid .riv asset; upload or assign one before routing to Rive.');
  const bundle=await esbuild.build({stdin:{contents:`import {Rive} from '@rive-app/canvas'; window.Rive=Rive;`,resolveDir:ROOT,sourcefile:'rive-entry.js',loader:'js'},bundle:true,format:'iife',write:false});
  const width=Math.max(320,Math.min(1920,Number(req.width)||1920));const height=Math.max(240,Math.min(1920,Number(req.height)||1080));
  const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
  try {
    const page=await browser.newPage({viewport:{width,height},deviceScaleFactor:1});
    await page.setContent(`<body style="margin:0;background:#0b1323"><canvas id="c" width="${width}" height="${height}" style="width:100vw;height:100vh;display:block"></canvas><script>${bundle.outputFiles[0].text}</script>`);
    await page.evaluate(async ({b64,width,height})=>new Promise((resolve,reject)=>{
      const raw=atob(b64),a=new Uint8Array(raw.length);for(let i=0;i<raw.length;i++)a[i]=raw.charCodeAt(i);
      let settled=false;const timer=setTimeout(()=>{if(!settled){settled=true;reject(new Error('Timed out waiting for Rive asset to load'));}},10000);
      const canvas=document.querySelector('#c');
      window.riveInstance=new window.Rive({buffer:a.buffer,canvas,autoplay:true,onLoad:()=>{if(settled)return;settled=true;clearTimeout(timer);try{window.riveInstance.resizeDrawingSurfaceToCanvas();resolve(true);}catch(e){reject(e);}},onLoadError:e=>{if(settled)return;settled=true;clearTimeout(timer);reject(new Error('Rive asset failed to load: '+String(e?.message||e)));}});
    }),{b64:req.riveAssetBase64,width,height});
    await page.waitForTimeout(300);
    const frames=[];for(let i=0;i<count;i++){await page.waitForTimeout(350);frames.push((await page.locator('body').screenshot({type:'png'})).toString('base64'));}
    return frames;
  } finally {await browser.close();}
}

async function motionCanvasFrames(req,count){
  const dir=await fs.mkdtemp(path.join(ROOT,'mc-scene-'));const tpl=path.join(ROOT,'motion-template');
  await fs.cp(tpl,dir,{recursive:true});
  await fs.copyFile(path.join(ROOT,'package.json'),path.join(dir,'package.json'));
  await fs.symlink(path.join(ROOT,'node_modules'),path.join(dir,'node_modules'),'dir');
  const title=JSON.stringify(safe(req.title));const labels=(req.labels||[]).slice(0,6).map(safe);while(labels.length<3)labels.push(['Problem','Process','Result'][labels.length]);
  const lab=JSON.stringify(labels);const duration=Math.max(3,count*.65);
  const scene=`import {makeScene2D} from '@motion-canvas/2d';\nimport {all,createRef,waitFor} from '@motion-canvas/core';\nimport {Circle,Line,Rect,Txt} from '@motion-canvas/2d/lib/components';\nexport default makeScene2D(function*(view){\nconst title=createRef<Txt>();const nodes=[createRef<Rect>(),createRef<Rect>(),createRef<Rect>()];const links=[createRef<Line>(),createRef<Line>()];const packet=createRef<Circle>();const labels=${lab};view.add(<><Txt ref={title} text=${title} y={-360} fontSize={42} fontWeight={800} fill="#eaf4ff"/><Line ref={links[0]} points={[[-360,0],[-120,0]]} stroke="#67e8f9" lineWidth={7} endArrow end={0}/><Line ref={links[1]} points={[[120,0],[360,0]]} stroke="#67e8f9" lineWidth={7} endArrow end={0}/>{nodes.map((r,i)=><Rect ref={r} x={(i-1)*240} y={0} width={210} height={140} radius={24} fill={['#173657','#342c16','#153a31'][i]} stroke={['#60a5fa','#fbbf24','#34d399'][i]} lineWidth={4}><Txt text={labels[i]} fontSize={25} fill="#f8fafc"/></Rect>)}<Circle ref={packet} x={-360} y={0} size={26} fill="#ffffff" shadowColor="#67e8f9" shadowBlur={20}/><Txt text="Progressive animation synchronized to the lesson" y={260} fontSize={24} fill="#a9bdd8"/></>);yield* all(...links.map(x=>x().end(1,.6)),...nodes.map(x=>x().scale(1,.3)));yield* packet().position([0,0],1);yield* waitFor(.2);yield* packet().position([360,0],1);yield* waitFor(${Math.max(.5,duration-3).toFixed(2)});});`;
  await fs.mkdir(path.join(dir,'src/scenes'),{recursive:true});
  await fs.writeFile(path.join(dir,'src/scenes/data-flow.tsx'),scene);await fs.writeFile(path.join(dir,'src/project.ts'),`import {makeProject} from '@motion-canvas/core';import scene from './scenes/data-flow?scene';export default makeProject({scenes:[scene]});`);
  // Do not invoke the nonexistent @motion-canvas/cli package. The official
  // Motion Canvas v3 export flow is configured through Vite plugins/exporters;
  // this service currently has no supported headless player/export harness.
  // Fail explicitly so the backend records a renderer failure and can apply
  // its configured fallback instead of falsely claiming Motion Canvas output.
  try {
    throw new Error('Motion Canvas headless export is not configured: @motion-canvas/cli is not a published package. Configure a supported Motion Canvas player/exporter before enabling this production route.');
  } finally {
    await fs.rm(dir,{recursive:true,force:true});
  }
}

app.post('/render-frames',async(req,res)=>{const p=req.body||{};const engine=String(p.engine||'').toUpperCase();const count=Math.max(2,Math.min(8,Number(p.steps)||4));try{let frames;if(engine==='MOTION_CANVAS')frames=await motionCanvasFrames(p,count);else if(engine==='THREE_JS')frames=await threeFrames(p,path.join(os.tmpdir(),'unused'),count);else if(engine==='RIVE')frames=await riveFrames(p,count);else return res.status(400).json({error:'Unsupported renderer '+engine});res.json({engine,rendererStatus:'RENDERED',frames});}catch(e){res.status(503).json({error:String(e?.message||e),engine,rendererStatus:'FAILED'});}});
const port=Number(process.env.PORT||5020);app.listen(port,'0.0.0.0',()=>console.log(`visual-renderer listening on ${port}`));
