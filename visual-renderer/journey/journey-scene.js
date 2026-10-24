// "Journey" kit: story-style 3D explainer scenes (network, globe, packet card, recap) + the HUD that makes them a series
// (series bar, slow-motion clock with a pill, caption / stat / name-card chips). Pure data in, no model-written code runs.
// Libraries: three (+ bloom post-processing), d3-geo (land mask), topojson-client + world-atlas (map data).
import * as THREE from 'three';
import { feature, mesh } from 'topojson-client';
import world from 'world-atlas/countries-110m.json';
import LAND from './land-dots.json';
import { EffectComposer } from 'three/examples/jsm/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/examples/jsm/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/examples/jsm/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/examples/jsm/postprocessing/OutputPass.js';

const clamp = (v, a = 0, b = 1) => Math.min(b, Math.max(a, v));
const ease = k => 1 - Math.pow(1 - clamp(k), 3);
const mulberry = seed => () => { seed |= 0; seed = seed + 0x6D2B79F5 | 0; let t = Math.imul(seed ^ seed >>> 15, 1 | seed); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; };

window.createJourney = function (opts) {
  const W = opts.width, H = opts.height, u = W / 720, hud = opts.hud || {};
  const canvas = document.querySelector('#c'), BG = opts.bg || '#050b1a';
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, preserveDrawingBuffer: true });
  renderer.setPixelRatio(1); renderer.setSize(W, H, false); renderer.setClearColor(BG);
  const isGlobe = opts.kind === 'globe';
  if (!isGlobe) { renderer.toneMapping = THREE.ACESFilmicToneMapping; renderer.toneMappingExposure = 0.8; }   // compress highlights: the pulse glows, the scene does not wash out
  const scene = new THREE.Scene(); scene.background = new THREE.Color(BG);
  const camera = new THREE.PerspectiveCamera(opts.kind === 'globe' ? 36 : 52, W / H, 0.1, 2000);
  scene.add(new THREE.AmbientLight(0x88aaff, isGlobe ? 1.0 : 0.95)); const key = new THREE.DirectionalLight(0xffffff, isGlobe ? 1.6 : 1.5); key.position.set(-3, 4, 5); scene.add(key);
  const composer = new EffectComposer(renderer); composer.addPass(new RenderPass(scene, camera));
  const bloom = new UnrealBloomPass(new THREE.Vector2(W, H), opts.bloom ?? (isGlobe ? 0.9 : opts.kind === 'packet' ? 0.3 : 0.5), 0.6, isGlobe ? 0.18 : 0.8); composer.addPass(bloom); composer.addPass(new OutputPass());

  // ---------------------------------------------------------------- HUD (DOM, captured by the screenshot)
  document.body.insertAdjacentHTML('beforeend', `<style>
    #hud{position:absolute;inset:0;pointer-events:none;font-family:'DejaVu Sans',Arial,sans-serif;color:#fff}
    #hud .bar{position:absolute;top:${26 * u}px;left:${30 * u}px;right:${30 * u}px;display:flex;justify-content:space-between;font:${13 * u}px 'DejaVu Sans Mono',monospace;letter-spacing:${1.4 * u}px;color:#d4e0f5;text-shadow:0 0 ${6 * u}px #000,0 0 ${12 * u}px #000}
    #hud .bar b{color:#fbbf24}
    #hud .clock{position:absolute;top:${62 * u}px;left:0;right:0;text-align:center}
    #hud .digits{display:inline-block;padding:${8 * u}px ${22 * u}px;border-radius:${16 * u}px;background:rgba(6,11,25,.82);border:${1.5 * u}px solid rgba(251,191,36,.28);font:800 ${52 * u}px 'DejaVu Sans Mono',monospace;color:#fbbf24}
    #hud .digits i{font-style:normal;font-size:${34 * u}px;opacity:.85} #hud .digits u{text-decoration:none;font-size:${20 * u}px;color:#e2c26a;margin-left:${6 * u}px}
    #hud .pill{display:block;margin:${8 * u}px auto 0;width:max-content;padding:${3 * u}px ${14 * u}px;border-radius:${99 * u}px;background:#fbbf24;color:#0b1020;font:800 ${15 * u}px 'DejaVu Sans',sans-serif}
    #hud .chip{position:absolute;left:50%;transform:translateX(-50%);max-width:${660 * u}px;text-align:center;padding:${14 * u}px ${28 * u}px;border-radius:${18 * u}px;background:rgba(8,14,30,.8);border:${1.2 * u}px solid rgba(255,255,255,.12);opacity:0}
    #hud .chip .a{font:800 ${37 * u}px 'DejaVu Sans',sans-serif;line-height:1.16} #hud .chip .b{font:${22 * u}px 'DejaVu Sans',sans-serif;color:#9fb0cc;margin-top:${4 * u}px}
    #hud .chip.stat .a{font-size:${62 * u}px;color:#fbbf24} #hud .chip.name{background:rgba(251,191,36,.97);border-color:transparent;color:#0b1020} #hud .chip.name .a{font-size:${44 * u}px} #hud .chip.name .b{color:#3b3320;font-weight:700}
    #hud .tag{position:absolute;transform:translate(-50%,-130%);padding:${5 * u}px ${12 * u}px;border-radius:${10 * u}px;background:rgba(8,14,30,.85);border:${1.2 * u}px solid rgba(255,255,255,.18);font:800 ${23 * u}px 'DejaVu Sans',sans-serif;white-space:nowrap;opacity:0}
    #hud .tag small{display:block;font:${15 * u}px 'DejaVu Sans',sans-serif;color:#9fb0cc;font-weight:400}
    #fx{position:absolute;inset:0;width:${W}px;height:${H}px;pointer-events:none}
    #stack{position:absolute;left:${60 * u}px;right:${60 * u}px;top:${250 * u}px;bottom:${330 * u}px}
    #stack .row{position:absolute;left:0;right:0;display:flex;align-items:center;opacity:0}
    #stack .dot{width:${34 * u}px;height:${34 * u}px;border-radius:50%;background:#fbbf24;color:#0b1020;font:800 ${18 * u}px 'DejaVu Sans',sans-serif;display:flex;align-items:center;justify-content:center;box-shadow:0 0 ${18 * u}px rgba(251,191,36,.8);flex:none;margin-left:${90 * u}px}
    #stack .lab{margin-left:${22 * u}px;padding:${8 * u}px ${18 * u}px;border-radius:${12 * u}px;background:rgba(8,14,30,.85);border:${1.2 * u}px solid rgba(255,255,255,.16)}
    #stack .lab b{display:block;font:800 ${25 * u}px 'DejaVu Sans',sans-serif} #stack .lab small{font:${15 * u}px 'DejaVu Sans',sans-serif;color:#9fb0cc}
    #stack .rail{position:absolute;left:${90 * u + 17 * u - 1.5 * u}px;width:${3 * u}px;top:0;bottom:0;background:linear-gradient(#fbbf24,#fbbf2455)}
  </style>
  <div id="hud"><canvas id="fx" width="${W}" height="${H}"></canvas>
    <div class="bar"><span>${hud.series || ''}</span><span>${hud.handle || ''}</span></div>
    <div class="clock" style="display:${hud.clock ? 'block' : 'none'}"><div class="digits"><span id="ms">0000</span><i id="msd">.0</i><u>ms</u></div><span class="pill">${hud.slow || ''}</span></div>
    <div id="chips"></div><div id="tags"></div><div id="stack"></div></div>`);
  const chipEls = [], tagEls = [];
  const addChip = (c) => { const d = document.createElement('div'); d.className = 'chip ' + (c.kind || 'cap'); d.style.top = (c.y ?? (c.kind === 'stat' ? 0.45 : 0.72)) * H + 'px'; d.innerHTML = `<div class="a"></div>${c.sub ? '<div class="b"></div>' : ''}`; d.querySelector('.a').textContent = c.text; if (c.sub) d.querySelector('.b').textContent = c.sub; document.querySelector('#chips').appendChild(d); chipEls.push([c, d]); };
  (opts.chips || []).forEach(addChip);
  const fxCtx = document.querySelector('#fx').getContext('2d');
  const proj = (v) => { const p = v.clone().project(camera); return [(p.x * 0.5 + 0.5) * W, (-p.y * 0.5 + 0.5) * H, p.z]; };
  const addTag = (name, sub, t0, getPos) => { const d = document.createElement('div'); d.className = 'tag'; d.innerHTML = `${name}${sub ? `<small>${sub}</small>` : ''}`; document.querySelector('#tags').appendChild(d); tagEls.push({ d, t0, getPos }); };

  const dispose = [];
  const R = 100;
  const ll = (lat, lng, alt = 0) => { const phi = (90 - lat) * Math.PI / 180, th = (lng + 180) * Math.PI / 180, r = R * (1 + alt); return new THREE.Vector3(-r * Math.sin(phi) * Math.cos(th), r * Math.cos(phi), r * Math.sin(phi) * Math.sin(th)); };
  let sceneUpdate = () => {};

  // ================================================================ scene: GLOBE (dotted land, borders, lofted arcs with glowing heads, city tags)
  function buildGlobe(p) {
    scene.add(new THREE.Mesh(new THREE.SphereGeometry(R * 0.995, 96, 64), new THREE.MeshPhongMaterial({ color: '#0a1a3a', emissive: '#06122b', shininess: 8 })));
    const pos = []; for (const [lat, lng] of LAND) { const v = ll(lat, lng, 0.003); pos.push(v.x, v.y, v.z); }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    const cv = document.createElement('canvas'); cv.width = cv.height = 32; const cx = cv.getContext('2d'); cx.fillStyle = '#fff'; cx.beginPath(); cx.arc(16, 16, 14, 0, 7); cx.fill();
    scene.add(new THREE.Points(g, new THREE.PointsMaterial({ color: '#7dd3fc', size: 0.72, map: new THREE.CanvasTexture(cv), transparent: true, alphaTest: 0.4, depthWrite: false })));
    const m = mesh(world, world.objects.countries), lp = [];
    for (const line of m.coordinates) for (let i = 1; i < line.length; i++) { const a = ll(line[i - 1][1], line[i - 1][0], 0.004), b = ll(line[i][1], line[i][0], 0.004); lp.push(a.x, a.y, a.z, b.x, b.y, b.z); }
    const lg = new THREE.BufferGeometry(); lg.setAttribute('position', new THREE.Float32BufferAttribute(lp, 3)); scene.add(new THREE.LineSegments(lg, new THREE.LineBasicMaterial({ color: '#38bdf8', transparent: true, opacity: 0.5 })));
    scene.add(new THREE.Mesh(new THREE.SphereGeometry(R * 1.015, 64, 48), new THREE.ShaderMaterial({ transparent: true, side: THREE.BackSide, blending: THREE.AdditiveBlending, depthWrite: false,
      vertexShader: 'varying vec3 n; void main(){ n = normalize(normalMatrix*normal); gl_Position = projectionMatrix*modelViewMatrix*vec4(position,1.0);}',
      fragmentShader: 'varying vec3 n; void main(){ float i = pow(0.62 - dot(n, vec3(0,0,1.0)), 3.0); gl_FragColor = vec4(0.25,0.6,1.0,1.0)*i*1.4; }' })));
    const arcs = (p.arcs || []).map(a => {
      const A = ll(a.from[0], a.from[1]), B = ll(a.to[0], a.to[1]), loft = a.loft ?? 0.35, mid = A.clone().add(B).multiplyScalar(0.5).normalize().multiplyScalar(R * (1 + loft * A.distanceTo(B) / R));
      const curve = new THREE.QuadraticBezierCurve3(A, mid, B), tube = new THREE.Mesh(new THREE.TubeGeometry(curve, 120, a.width ?? 0.5, 8, false), new THREE.MeshBasicMaterial({ color: a.color || '#fbbf24' }));
      const head = new THREE.Mesh(new THREE.SphereGeometry((a.width ?? 0.5) * 2.6, 16, 12), new THREE.MeshBasicMaterial({ color: '#fff7c2' }));
      scene.add(tube, head); tube.geometry.setDrawRange(0, 0); head.visible = false;
      for (const c of [A, B]) { const d = new THREE.Mesh(new THREE.SphereGeometry(1.1, 12, 10), new THREE.MeshBasicMaterial({ color: '#ffffff' })); d.position.copy(c); scene.add(d); }
      return { a, curve, tube, head };
    });
    (p.cities || []).forEach(c => addTag(c.name, c.sub, c.t0 ?? 0, () => ll(c.lat, c.lng, 0.02)));
    const k = p.camera || []; // keyframes: {t, lat, lng, dist, up}
    sceneUpdate = (t, T) => {
      for (const o of arcs) { const pr = ease((t - o.a.t0) / Math.max(0.2, o.a.t1 - o.a.t0)); o.tube.geometry.setDrawRange(0, Math.floor(o.tube.geometry.index.count * Math.max(0, pr))); o.head.visible = pr > 0 && pr < 1.0001; o.head.position.copy(o.curve.getPoint(clamp(pr))); }
      let i = 0; while (i < k.length - 2 && t > k[i + 1].t) i++;
      const a = k[i] || { lat: 20, lng: 78, dist: 300, up: 0 }, b = k[Math.min(i + 1, k.length - 1)] || a, f = ease((t - a.t) / Math.max(0.01, b.t - a.t));
      const lat = a.lat + (b.lat - a.lat) * f, lng = a.lng + (b.lng - a.lng) * f, dist = a.dist + (b.dist - a.dist) * f, up = (a.up ?? 0) + ((b.up ?? 0) - (a.up ?? 0)) * f;
      const dir = ll(lat, lng).normalize(), east = new THREE.Vector3(0, 1, 0).cross(dir).normalize(), north = dir.clone().cross(east).normalize();
      camera.position.copy(dir.clone().multiplyScalar(dist).add(north.clone().multiplyScalar(-dist * 0.18 * up)).add(east.clone().multiplyScalar(dist * 0.02 * Math.sin(t * 0.4))));
      camera.up.copy(north); camera.lookAt(dir.clone().multiplyScalar(R * 0.92 + (dist - 220) * 0.05));
    };
  }

  // ================================================================ scene: NETWORK (glowing pipes between machines; a pulse travels the route, the camera rides along)
  function buildNetwork(p) {
    const rnd = mulberry(p.seed ?? 7), cols = 9, rows = 12, sp = 6, nodes = [];
    for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) nodes.push(new THREE.Vector3((c - (cols - 1) / 2) * sp + (rnd() - 0.5) * 2.4, 0, -r * sp + (rnd() - 0.5) * 2.4));
    const pipeMat = new THREE.MeshBasicMaterial({ color: '#9db8ea' }), hotMat = new THREE.MeshBasicMaterial({ color: '#fbbf24' });
    const pipe = (a, b, m, w = 0.12) => { const d = b.clone().sub(a), len = d.length(), mm = new THREE.Mesh(new THREE.BoxGeometry(len, w, w), m); mm.position.copy(a).add(b).multiplyScalar(0.5); mm.rotation.y = -Math.atan2(d.z, d.x); mm.position.y = 0.08; scene.add(mm); return mm; };
    const id = (r, c) => r * cols + c;
    for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) { if (c < cols - 1) pipe(nodes[id(r, c)], nodes[id(r, c + 1)], pipeMat); if (r < rows - 1) pipe(nodes[id(r, c)], nodes[id(r + 1, c)], pipeMat); if (r < rows - 1 && c < cols - 1 && rnd() < 0.35) pipe(nodes[id(r, c)], nodes[id(r + 1, c + 1)], pipeMat, 0.1); }
    const ground = new THREE.Mesh(new THREE.PlaneGeometry(200, 200), new THREE.MeshBasicMaterial({ color: '#0a1530' }));   // unlit: a lit plane caught a huge specular highlight from the key light ground.rotation.x = -Math.PI / 2; ground.position.y = -0.05; scene.add(ground);
    // small machines at every node, taller ones (with a glowing top) on the route
    const route = (p.route || [[0, 4], [3, 3], [6, 5], [9, 4]]).map(([r, c]) => id(r, c)), rp = route.map(i => nodes[i].clone());
    nodes.forEach((n, i) => { const h = route.includes(i) ? 3.4 : 0.6 + rnd() * 0.6, box = new THREE.Mesh(new THREE.BoxGeometry(1.1, h, 1.1), new THREE.MeshStandardMaterial({ color: '#0e1d3a', emissive: route.includes(i) ? '#1d3a6b' : '#0a1428', roughness: 0.6 })); box.position.set(n.x, h / 2, n.z); scene.add(box);
      if (route.includes(i)) { const cap = new THREE.Mesh(new THREE.SphereGeometry(0.26, 16, 12), new THREE.MeshBasicMaterial({ color: '#f2b533' })); cap.position.set(n.x, h + 0.35, n.z); scene.add(cap); } });
    const pulse = new THREE.Mesh(new THREE.SphereGeometry(0.4, 20, 14), new THREE.MeshBasicMaterial({ color: '#fff3b0' })); scene.add(pulse);
    const lens = []; let tot = 0; for (let i = 1; i < rp.length; i++) { const l = rp[i].distanceTo(rp[i - 1]); lens.push(l); tot += l; }
    const at = (s) => { let d = s * tot, i = 0; while (i < lens.length - 1 && d > lens[i]) { d -= lens[i]; i++; } const f = clamp(d / lens[i]); return [rp[i].clone().lerp(rp[i + 1], f), rp[i + 1].clone().sub(rp[i]).normalize()]; };
    const hot = []; { let acc = 0; for (let i = 1; i < rp.length; i++) { const m = pipe(rp[i - 1], rp[i], hotMat, 0.22); m.visible = false; hot.push([m, acc / tot]); acc += lens[i - 1]; } }
    const nodeS = rp.map((_, i) => { let acc = 0; for (let k = 1; k <= i; k++) acc += lens[k - 1]; return acc / tot; });
    const rings = rp.map(n => { const r = new THREE.Mesh(new THREE.RingGeometry(0.55, 0.7, 48), new THREE.MeshBasicMaterial({ color: '#ffe08a', transparent: true, opacity: 0, side: THREE.DoubleSide })); r.rotation.x = -Math.PI / 2; r.position.set(n.x, 0.14, n.z); scene.add(r); return r; });
    (p.tags || []).forEach(g => addTag(g.name, g.sub, g.t0 ?? 0, () => { const n = rp[Math.min(g.node, rp.length - 1)]; return new THREE.Vector3(n.x, 4.6, n.z); }));
    let camPos = null;
    sceneUpdate = (t, T, dt) => {
      const s = ease(t / Math.max(1, T * 0.92)), [pos, dir] = at(s);
      hot.forEach(([m, st]) => { m.visible = s >= st; });
      rings.forEach((r, i) => { const d = (s - nodeS[i]) * T * 0.92 * 0.5, k = clamp(d / 1.3); r.material.opacity = d > 0 && k < 1 ? 0.85 * (1 - k) : 0; r.scale.setScalar(1 + k * 5); }); pulse.position.set(pos.x, 0.6, pos.z);
      const want = pos.clone().sub(dir.clone().multiplyScalar(11)).add(new THREE.Vector3(3.2, 7.0 + Math.sin(t * 0.5) * 0.4, 0));   // beside the route: never inside a tower
      camPos = camPos ? camPos.lerp(want, 1 - Math.exp(-(dt || 0.05) * 5)) : want;   // smoothing that does not depend on the frame rate
      camera.position.copy(camPos); camera.up.set(0, 1, 0); camera.lookAt(pos.clone().add(dir.clone().multiplyScalar(6)).add(new THREE.Vector3(-1.2, 0.6, 0)));
    };
  }

  // ================================================================ scene: PACKET (a floating message card, a lock that clicks on, a burst of small cards)
  function buildPacket(p) {
    const cv = document.createElement('canvas'); cv.width = 1024; cv.height = 640; const g = cv.getContext('2d');
    g.fillStyle = '#0d1b36'; g.fillRect(0, 0, 1024, 640); g.fillStyle = '#fbbf24'; g.fillRect(0, 0, 1024, 150);
    g.fillStyle = '#0b1020'; g.font = '800 96px DejaVu Sans Mono'; g.textBaseline = 'middle'; g.fillText(p.id || '#0412', 40, 78); g.font = '600 40px DejaVu Sans'; g.textAlign = 'right'; g.fillText(p.of || 'of 1', 984, 80); g.textAlign = 'left';
    g.fillStyle = '#9fb0cc'; g.font = '600 34px DejaVu Sans'; g.fillText('TO', 40, 200); g.fillStyle = '#fff'; g.font = '800 72px DejaVu Sans'; g.fillText(p.to || 'Shop bank', 40, 270);
    g.fillStyle = '#9fb0cc'; g.font = '600 30px DejaVu Sans'; g.fillText(p.sub || 'account', 40, 320);
    const rnd = mulberry(5); for (let y = 0; y < 5; y++) for (let x = 0; x < 20; x++) { if (rnd() < 0.62) { g.fillStyle = rnd() < 0.5 ? '#f5c451' : '#c9972b'; g.fillRect(40 + x * 47, 370 + y * 48, 40, 40); } }
    const tex = new THREE.CanvasTexture(cv); tex.anisotropy = 4;
    const card = new THREE.Mesh(new THREE.BoxGeometry(6.4, 4.0, 0.3), [0, 1, 2, 3, 4, 5].map(i => i === 4 ? new THREE.MeshStandardMaterial({ map: tex, roughness: 0.6 }) : new THREE.MeshStandardMaterial({ color: '#13244a', roughness: 0.5 })));
    scene.add(card);
    const lockCv = document.createElement('canvas'); lockCv.width = lockCv.height = 256; const lg = lockCv.getContext('2d'); lg.fillStyle = '#1a2a52'; lg.fillRect(0, 0, 256, 256); lg.fillStyle = '#fbbf24'; lg.beginPath(); lg.arc(128, 110, 38, 0, 7); lg.fill(); lg.fillRect(110, 110, 36, 90); lg.fillStyle = '#1a2a52'; lg.beginPath(); lg.arc(128, 110, 14, 0, 7); lg.fill();
    const lock = new THREE.Mesh(new THREE.BoxGeometry(1.5, 1.5, 0.3), new THREE.MeshStandardMaterial({ map: new THREE.CanvasTexture(lockCv), emissive: '#6b4a00', emissiveIntensity: 0.35 })); lock.position.set(0, 0.2, 0.4); lock.visible = false; scene.add(lock);
    const N = 420, inst = new THREE.InstancedMesh(new THREE.BoxGeometry(0.5, 0.32, 0.06), new THREE.MeshStandardMaterial({ color: '#17305f', emissive: '#102245' }), N), dm = new THREE.Object3D(), r2 = mulberry(11), seeds = [];
    for (let i = 0; i < N; i++) { const a = r2() * 6.283, e = (r2() - 0.3) * 1.6, sp = 2 + r2() * 5; seeds.push([Math.cos(a) * Math.cos(e) * sp, Math.sin(e) * sp * 0.8, Math.sin(a) * Math.cos(e) * sp - 1.5, r2() * 6.283, r2() * 2]); } scene.add(inst);
    const lockAt = p.lockAt ?? 5;
    sceneUpdate = (t, T) => {
      const push = ease(t / 3.0);
      card.rotation.y = Math.sin(t * 0.5) * 0.35 - 0.1; card.rotation.x = -0.12 + Math.sin(t * 0.37) * 0.05; card.position.y = Math.sin(t * 0.8) * 0.12;
      lock.visible = t > lockAt; const lk = ease((t - lockAt) / 0.5); lock.scale.setScalar(0.3 + 0.7 * lk); lock.rotation.copy(card.rotation); lock.position.set(2.15, 0.55 + card.position.y, 0.5);
      camera.position.set(0, 0.8, 17 - 3.0 * push); camera.up.set(0, 1, 0); camera.lookAt(0, 0, 0);
      const burst = clamp((t - (p.burstAt ?? 1.2)) / 3.0);
      for (let i = 0; i < N; i++) { const s = seeds[i], b = ease(burst); dm.position.set(s[0] * b * 1.6, s[1] * b * 1.4 - b * b * 1.5, s[2] * b - 2); dm.rotation.set(s[3] + t * 0.4, s[3] * 0.7 + t * s[4] * 0.3, 0); dm.scale.setScalar(burst > 0 ? 1 : 0.0001); dm.updateMatrix(); inst.setMatrixAt(i, dm.matrix); } inst.instanceMatrix.needsUpdate = true;
    };
  }

  // ================================================================ scene: RECAP (numbered stack on a dark gradient with fireworks)
  function buildRecap(p) {
    const stack = document.querySelector('#stack'); stack.innerHTML = '<div class="rail"></div>';
    const items = p.items || [], h = stack.clientHeight || H * 0.4, rowH = h / Math.max(1, items.length), rows = [];
    items.forEach((it, i) => { const d = document.createElement('div'); d.className = 'row'; d.style.top = ((items.length - 1 - i) * rowH) + 'px'; d.innerHTML = `<div class="dot">${i + 1}</div><div class="lab"><b></b><small></small></div>`; d.querySelector('b').textContent = it.label; d.querySelector('small').textContent = it.sub || ''; stack.appendChild(d); rows.push([it, d]); });
    const rnd = mulberry(3), bursts = []; for (let i = 0; i < 9; i++) bursts.push({ x: (0.15 + rnd() * 0.7) * W, y: (0.12 + rnd() * 0.3) * H, t0: 0.5 + i * 1.7 + rnd(), hue: [45, 35, 200, 330][i % 4], n: 70 });
    const stars = []; for (let i = 0; i < 70; i++) stars.push([rnd() * W, rnd() * H, rnd()]);
    sceneUpdate = (t) => {
      fxCtx.clearRect(0, 0, W, H);
      const grd = fxCtx.createLinearGradient(0, 0, 0, H); grd.addColorStop(0, '#071230'); grd.addColorStop(1, '#16264f'); fxCtx.fillStyle = grd; fxCtx.fillRect(0, 0, W, H);
      stars.forEach(([x, y, a]) => { fxCtx.fillStyle = `rgba(255,255,255,${0.2 + 0.4 * a})`; fxCtx.fillRect(x, y, 2 * u, 2 * u); });
      for (const b of bursts) { const dt = t - b.t0; if (dt < 0 || dt > 2.4) continue; for (let i = 0; i < b.n; i++) { const a = (i / b.n) * 6.283 + i * 0.17, sp = (90 + (i % 5) * 30) * u, x = b.x + Math.cos(a) * sp * dt, y = b.y + Math.sin(a) * sp * dt + 60 * u * dt * dt, al = clamp(1 - dt / 2.4);
        fxCtx.fillStyle = `hsla(${b.hue + (i % 3) * 8},95%,${62 + (i % 4) * 5}%,${al})`; fxCtx.beginPath(); fxCtx.arc(x, y, (2.6 - dt) * u + 0.6, 0, 7); fxCtx.fill(); } }
      rows.forEach(([it, d], i) => { const k = ease((t - (it.t0 ?? i * 1.2)) / 0.5); d.style.opacity = k; d.style.transform = `translateX(${(1 - k) * 40 * u}px)`; });
      composer.passes[1].strength = 0.4;
    };
    renderer.setClearColor('#071230'); scene.background = null; canvas.style.opacity = 0;
  }

  const builders = { globe: buildGlobe, network: buildNetwork, packet: buildPacket, recap: buildRecap };
  (builders[opts.kind] || buildNetwork)(opts.scene || {});
  return {
    render(t, T, dt) {
      const clock = hud.clock; if (clock) { const ms = clock[0] + (clock[1] - clock[0]) * clamp(t / T); const whole = Math.floor(ms), dec = Math.floor((ms - whole) * 10);
        document.querySelector('#ms').textContent = String(whole).padStart(4, '0'); document.querySelector('#msd').textContent = '.' + dec; }
      sceneUpdate(t, T, dt);
      window.__cam = { cam: camera.position.toArray().map(v => +v.toFixed(2)), up: camera.up.toArray() };
      if (opts.kind !== 'recap') composer.render();
      for (const [c, d] of chipEls) { const a = clamp((t - c.t0) / 0.35) * clamp((c.t1 - t) / 0.3); d.style.opacity = a; d.style.transform = `translateX(-50%) translateY(${(1 - a) * 14 * u}px)`; }
      for (const g of tagEls) { const [x, y, z] = proj(g.getPos()); const vis = z < 1 && g.t0 <= t && x > 0 && x < W && y > 0 && y < H; g.d.style.opacity = vis ? clamp((t - g.t0) / 0.4) : 0; g.d.style.left = x + 'px'; g.d.style.top = y + 'px'; }
    },
  };
};
