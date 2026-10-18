// Browser-side Three.js scene builder. Bundled by esbuild together with three and run in headless Chromium.
// Everything is a fixed template driven by plain data (title, labels, kind): no model-written code is ever executed.
export function createStudio(THREE, opts) {
  const W = opts.width, H = opts.height;
  const canvas = document.querySelector('#c');
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: false, preserveDrawingBuffer: true });
  renderer.setPixelRatio(1);
  renderer.setSize(W, H, false);
  const BG = '#07111f';
  renderer.setClearColor(BG);
  const scene = new THREE.Scene();
  scene.background = new THREE.Color(BG);
  scene.add(new THREE.AmbientLight(0xffffff, 1.5));
  const key = new THREE.DirectionalLight(0xbfe6ff, 2.2); key.position.set(4, 6, 8); scene.add(key);
  const portrait = H > W;
  const camera = new THREE.PerspectiveCamera(portrait ? 52 : 42, W / H, 0.1, 200);

  const COLORS = [0x60a5fa, 0xfbbf24, 0x34d399, 0xf472b6, 0xa78bfa, 0xfb923c];
  const items = [];      // { objects:[], appearAt, fade }
  const labels = (opts.labels || []).filter(Boolean).map(String);
  const kindText = (opts.title + ' ' + (opts.narration || '')).toLowerCase();

  function label(text, scale = 1) {
    const cv = document.createElement('canvas'); cv.width = 640; cv.height = 160;
    const ctx = cv.getContext('2d');
    ctx.font = 'bold 64px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.lineWidth = 10; ctx.strokeStyle = 'rgba(7,17,31,0.95)'; ctx.strokeText(text.slice(0, 22), 320, 80);
    ctx.fillStyle = '#f8fafc'; ctx.fillText(text.slice(0, 22), 320, 80);
    const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: new THREE.CanvasTexture(cv), transparent: true, depthTest: false }));
    sp.scale.set(2.4 * scale, 0.6 * scale, 1); sp.renderOrder = 10;
    return sp;
  }
  function mat(color, extra = {}) { return new THREE.MeshStandardMaterial(Object.assign({ color, metalness: 0.15, roughness: 0.35 }, extra)); }
  function add(objs, appearAt) { objs.forEach(o => { o.visible = false; scene.add(o); }); items.push({ objs, appearAt, born: null }); }
  function pickLabels(defaults, n) { const out = []; for (let i = 0; i < n; i++) out.push(labels[i] || defaults[i] || ('Part ' + (i + 1))); return out; }

  // ------------------------------------------------------------------ templates
  const spin = [];     // functions (t) => void, called every frame
  function orbit() {
    const n = Math.max(2, Math.min(5, labels.length > 1 ? labels.length - 1 : 4));
    const names = pickLabels(['Sun', 'Mercury', 'Earth', 'Mars', 'Jupiter', 'Saturn'], n + 1);
    // the centre body looks like what it is called: a glowing star only for Sun / star, otherwise a lit planet
    const cname = names[0].toLowerCase();
    const isStar = /sun|star/.test(cname);
    const centreColor = /earth/.test(cname) ? 0x3b82f6 : /moon/.test(cname) ? 0x94a3b8 : /mars/.test(cname) ? 0xef4444 : /jupiter|saturn/.test(cname) ? 0xd6a56b : 0x60a5fa;
    const sun = new THREE.Mesh(new THREE.SphereGeometry(isStar ? 1.1 : 0.95, 48, 32), isStar
      ? new THREE.MeshStandardMaterial({ color: 0xfbbf24, emissive: 0xf59e0b, emissiveIntensity: 1.1 })
      : mat(centreColor));
    const sunLabel = label(names[0]); sunLabel.position.set(0, 1.9, 0);
    const glow = new THREE.PointLight(isStar ? 0xffe7a3 : 0xdbeafe, isStar ? 90 : 25, 60);
    add([sun, sunLabel, glow], 0);
    for (let i = 0; i < n; i++) {
      const r = 2.6 + i * 1.5, size = 0.28 + (i % 3) * 0.1, speed = 0.9 / (1 + i * 0.55), phase = i * 1.7;
      const ring = new THREE.Mesh(new THREE.TorusGeometry(r, 0.012, 8, 160), new THREE.MeshBasicMaterial({ color: 0x475a78 }));
      ring.rotation.x = Math.PI / 2;
      const planet = new THREE.Mesh(new THREE.SphereGeometry(size, 32, 24), mat(COLORS[(i + 2) % COLORS.length]));
      const tag = label(names[i + 1], 0.8);
      add([ring, planet, tag], i + 1);
      spin.push(t => { const a = t * speed + phase; planet.position.set(Math.cos(a) * r, 0, Math.sin(a) * r); tag.position.set(planet.position.x, size + 0.55, planet.position.z); });
    }
    return { cam: (t) => { camera.position.set(Math.sin(t * 0.12) * 2, 6.4, 10.2); camera.lookAt(0, -0.4, 0); } };
  }

  function molecule() {
    const lattice = /lattice|crystal|salt/.test(kindText);
    if (lattice) return crystal();
    const n = Math.max(2, Math.min(4, labels.length > 1 ? labels.length - 1 : 2));
    const names = pickLabels(['O', 'H', 'H', 'H'], n + 1);
    const group = new THREE.Group(); scene.add(group);
    const centre = new THREE.Mesh(new THREE.SphereGeometry(0.95, 40, 28), mat(0xef4444));
    const cl = label(names[0], 0.8); cl.position.set(0, 1.5, 0);
    add([centre, cl], 0);
    const toGroup = [centre];
    for (let i = 0; i < n; i++) {
      const ang = (Math.PI * 2 / n) * i + Math.PI / 2;
      // two bonded atoms: the bent shape of water (104 degrees), the oxygen on top; more atoms spread around the centre
      const pos = n === 2 ? new THREE.Vector3(i === 0 ? -1.9 : 1.9, -1.35, 0)
                          : new THREE.Vector3(Math.cos(ang) * 2.2, Math.sin(ang) * 2.0 - 0.3, (i % 2 ? 0.7 : -0.5));
      const atom = new THREE.Mesh(new THREE.SphereGeometry(0.6, 32, 24), mat(0xe2e8f0));
      atom.position.copy(pos);
      const bond = new THREE.Mesh(new THREE.CylinderGeometry(0.1, 0.1, pos.length(), 16), mat(0x94a3b8));
      bond.position.copy(pos.clone().multiplyScalar(0.5));
      bond.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), pos.clone().normalize());
      const tag = label(names[i + 1], 0.8); tag.position.copy(pos.clone().add(new THREE.Vector3(0, 1.05, 0)));
      add([bond, atom, tag], i + 1);
      toGroup.push(atom, bond);
    }
    spin.push(t => { group.rotation.y = t * 0.45; });
    // atoms and bonds live in the scene; rotate them by wrapping each in the group lazily
    items.forEach(it => it.objs.forEach(o => { if (o.isMesh) { scene.remove(o); group.add(o); } }));
    items.forEach(it => it.objs.forEach(o => { if (o.isSprite) { scene.remove(o); group.add(o); } }));
    return { cam: (t) => { camera.position.set(0, 0.8, 8.8); camera.lookAt(0, -0.6, 0); } };
  }

  function crystal() {
    const size = 3, gap = 1.25, group = new THREE.Group(); scene.add(group);
    const names = pickLabels(['Na', 'Cl'], 2);
    for (let z = 0; z < size; z++) {
      const layer = [];
      for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
        const odd = (x + y + z) % 2 === 1;
        const m = new THREE.Mesh(new THREE.SphereGeometry(odd ? 0.34 : 0.5, 20, 14), mat(odd ? 0x60a5fa : 0x34d399));
        m.position.set((x - 1) * gap, (y - 1) * gap, (z - 1) * gap); layer.push(m); group.add(m);
      }
      const lab = label(names[z % 2], 0.7); lab.position.set(2.6, (z - 1) * gap, 0);
      layer.push(lab); group.add(lab);
      add(layer, z);
    }
    items.forEach(it => it.objs.forEach(o => scene.remove(o)));
    items.forEach(it => it.objs.forEach(o => group.add(o)));
    spin.push(t => { group.rotation.y = t * 0.5; group.rotation.x = 0.4; });
    return { cam: () => { camera.position.set(0, 0.5, 8.2); camera.lookAt(0, 0, 0); } };
  }

  function layers() {
    const earth = /earth|crust|mantle|core/.test(kindText);
    const n = Math.max(3, Math.min(5, labels.length || 4));
    const names = pickLabels(earth ? ['Crust', 'Mantle', 'Outer core', 'Inner core', 'Centre'] : ['Layer 1', 'Layer 2', 'Layer 3', 'Layer 4', 'Layer 5'], n);
    for (let i = 0; i < n; i++) {
      const r = 3.2 - i * (2.2 / n), h = 0.6, y = (n - 1 - i) * 0.85 - (n - 1) * 0.42;
      const slab = new THREE.Mesh(new THREE.CylinderGeometry(r, r, h, 64), mat(COLORS[i % COLORS.length]));
      slab.position.y = y;
      const tag = label(names[i], 1.05); tag.position.set(4.7, y, 0.5);
      const pointer = new THREE.Mesh(new THREE.BoxGeometry(Math.max(0.2, 4.7 - 1.3 - r), 0.025, 0.025), new THREE.MeshBasicMaterial({ color: 0x94a3b8 }));
      pointer.position.set(r + (4.7 - 1.3 - r) / 2 + 0.05, y, 0.5);
      add([slab, tag, pointer], i);
    }
    return { cam: (t) => { camera.position.set(Math.sin(t * 0.25) * 1.2, 3.4, 8.8); camera.lookAt(1.1, 0, 0); } };
  }

  function axes() {
    const group = new THREE.Group(); group.position.y = -1.0; scene.add(group);
    const dirs = [[1, 0, 0, 0xef4444, 'x'], [0, 1, 0, 0x22c55e, 'y'], [0, 0, 1, 0x3b82f6, 'z']];
    const axisObjs = [];
    dirs.forEach(([x, y, z, c, n]) => {
      const v = new THREE.Vector3(x, y, z);
      axisObjs.push(new THREE.ArrowHelper(v, new THREE.Vector3(0, 0, 0), 3.6, c, 0.4, 0.22));
      const l = label(n, 0.55); l.position.copy(v.clone().multiplyScalar(4.1)); axisObjs.push(l);
    });
    add(axisObjs, 0);
    const shapeName = /sphere/.test(kindText) ? 'sphere' : /cylinder/.test(kindText) ? 'cylinder' : /pyramid|cone/.test(kindText) ? 'pyramid' : 'cube';
    const geo = shapeName === 'sphere' ? new THREE.SphereGeometry(1.5, 40, 28) : shapeName === 'cylinder' ? new THREE.CylinderGeometry(1.2, 1.2, 2.6, 40)
      : shapeName === 'pyramid' ? new THREE.ConeGeometry(1.8, 2.6, 4) : new THREE.BoxGeometry(2.2, 2.2, 2.2);
    const body = new THREE.Mesh(geo, mat(0x60a5fa, { transparent: true, opacity: 0.88 }));
    const wire = new THREE.LineSegments(new THREE.EdgesGeometry(geo), new THREE.LineBasicMaterial({ color: 0xe0f2fe }));
    const tag = label(labels[0] || shapeName, 0.9); tag.position.set(0, 2.6, 0);
    add([body, wire, tag], 1);
    const vec = new THREE.ArrowHelper(new THREE.Vector3(1, 1, 1).normalize(), new THREE.Vector3(0, 0, 0), 3.2, 0xfbbf24, 0.45, 0.26);
    const vl = label(labels[1] || 'vector', 0.8); vl.position.set(2.1, 2.7, 2.1);
    add([vec, vl], 2);
    const pl = new THREE.GridHelper(9, 9, 0x2a3f5f, 0x1a2c47); pl.position.y = -2.2;
    add([pl], 3);
    items.forEach(it => it.objs.forEach(o => { scene.remove(o); group.add(o); }));
    spin.push(t => { group.rotation.y = t * 0.55; group.rotation.x = 0.28; });
    return { cam: () => { camera.position.set(0, 1.4, 8.6); camera.lookAt(0, 0, 0); } };
  }

  function nodes() {
    const n = Math.max(3, Math.min(5, labels.length || 3));
    const names = pickLabels(['Input', 'Process', 'Output', 'Step 4', 'Step 5'], n);
    const span = Math.min(2.6, 9 / n);
    for (let i = 0; i < n; i++) {
      const x = (i - (n - 1) / 2) * span;
      const m = new THREE.Mesh(new THREE.BoxGeometry(1.3, 1.2, 0.8), mat(COLORS[i % COLORS.length]));
      m.position.set(x, 0, 0);
      const tag = label(names[i], 0.9); tag.position.set(x, 1.25, 0);
      const objs = [m, tag];
      if (i > 0) { const link = new THREE.Mesh(new THREE.CylinderGeometry(0.04, 0.04, span - 1.3, 8), new THREE.MeshBasicMaterial({ color: 0x67e8f9 })); link.rotation.z = Math.PI / 2; link.position.set(x - span / 2, 0, 0); objs.push(link); }
      add(objs, i);
      spin.push(t => { m.rotation.y = Math.sin(t * 0.8 + i) * 0.2; m.position.y = Math.sin(t * 1.2 + i) * 0.1; });
    }
    return { cam: (t) => { camera.position.set(Math.sin(t * 0.2) * 0.8, 0.8, 7.2); camera.lookAt(0, 0.2, 0); } };
  }

  const builders = { orbit, molecule, layers, axes };
  let kind = builders[opts.kind] ? opts.kind : null;
  if (!kind) {
    if (/orbit|planet|solar|galaxy|satellite/.test(kindText)) kind = 'orbit';
    else if (/molecule|atom|bond|helix|dna|crystal|lattice/.test(kindText)) kind = 'molecule';
    else if (/layer|crust|mantle|cross.section|atmosphere/.test(kindText)) kind = 'layers';
    else if (/3d|cube|sphere|axes|three-dimensional|coordinate|pyramid|cylinder/.test(kindText)) kind = 'axes';
  }
  const rig = (kind ? builders[kind] : nodes)();

  // title, drawn as page text so it is crisp
  const titleEl = document.createElement('div');
  titleEl.style.cssText = `position:absolute;top:${portrait ? 6 : 4}%;left:5%;right:5%;color:#eff6ff;font:700 ${portrait ? 54 : 40}px sans-serif;text-align:center;text-shadow:0 2px 10px #000`;
  titleEl.textContent = String(opts.title || '').slice(0, 90);
  document.body.appendChild(titleEl);

  // step k reveals everything that belongs to steps 0..k; the newest item eases in over the first half second
  // frames may be fewer than items (one frame per narration sentence): spread the reveal so the LAST frame shows everything
  const maxAppear = () => items.reduce((m, it) => Math.max(m, it.appearAt), 0);
  function stepFor(k) { const n = Math.max(1, (opts.count || 1) - 1); return opts.count > 1 ? Math.round(k * maxAppear() / n) : maxAppear(); }
  function renderStep(frame, t) {
    const step = stepFor(frame);
    items.forEach(it => {
      const show = it.appearAt <= step;
      if (show && it.born === null) it.born = t;
      it.objs.forEach(o => {
        o.visible = show;
        if (show) {
          const k = Math.min(1, Math.max(0, (t - it.born) / 0.5));
          const e = 1 - Math.pow(1 - k, 3);
          if (it.appearAt === step && !o.isLight && !o.isSprite && !o.isArrowHelper) { o.userData.s0 = o.userData.s0 || o.scale.clone(); o.scale.copy(o.userData.s0).multiplyScalar(0.55 + 0.45 * e); }
        }
      });
    });
    spin.forEach(fn => fn(t));
    rig.cam(t);
    renderer.render(scene, camera);
  }
  return { renderStep, kind: kind || 'nodes' };
}
