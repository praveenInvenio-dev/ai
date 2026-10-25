// Browser-side Three.js scene builder. Bundled by esbuild together with three and run in headless Chromium.
// Everything is a fixed template driven by plain data (title, labels, kind): no model-written code is ever executed.
export function createStudio(THREE, opts) {
  const W = opts.width, H = opts.height;
  const canvas = document.querySelector('#c');
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: false, preserveDrawingBuffer: true });
  renderer.setPixelRatio(1);
  renderer.setSize(W, H, false);
  const BG = opts.bg || '#07111f';
  renderer.setClearColor(BG);
  const scene = new THREE.Scene();
  scene.background = new THREE.Color(BG);
  const LIGHT = !!opts.light;   // bright (kids) look: dark text on a cream background
  const amb = new THREE.AmbientLight(0xffffff, 1.5); scene.add(amb);
  const key = new THREE.DirectionalLight(0xbfe6ff, 2.2); key.position.set(4, 6, 8); scene.add(key);
  const portrait = H > W;
  const camera = new THREE.PerspectiveCamera(portrait ? 52 : 42, W / H, 0.1, 200);

  // Theme-aware palette: keep all 3D scenes visually consistent with the selected lesson theme.
  const COLORS = LIGHT ? [0x6d5bd0, 0xe0a13a, 0x36a68a, 0xd86c91, 0x648bd8, 0xb27bd1]
    : /#0b0b0c|#1a1a1a/.test(BG) ? [0xf5b942, 0x6ed6e6, 0xf07c69, 0xc4a7ff, 0x9be3b1, 0xf4e8d0]
    : /#0a1f3a|#08162b|#0c2433/.test(BG) ? [0x38bdf8, 0xfbbf24, 0x2dd4bf, 0x818cf8, 0xfb7185, 0xe2e8f0]
    : [0x60a5fa, 0xfbbf24, 0x34d399, 0xf472b6, 0xa78bfa, 0xfb923c];
  const items = [];      // { objects:[], appearAt, fade }
  const labels = (opts.labels || []).filter(Boolean).map(String);
  const kindText = (opts.title + ' ' + (opts.narration || '')).toLowerCase();

  function label(text, scale = 1) {
    const cv = document.createElement('canvas'); cv.width = 640; cv.height = 160;
    const ctx = cv.getContext('2d');
    ctx.font = 'bold 64px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.lineWidth = 10; ctx.strokeStyle = LIGHT ? 'rgba(255,247,230,0.95)' : 'rgba(7,17,31,0.95)'; ctx.strokeText(text.slice(0, 22), 320, 80);
    ctx.fillStyle = LIGHT ? '#2a2540' : '#f8fafc'; ctx.fillText(text.slice(0, 22), 320, 80);
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
    const earth = /earth|crust|mantle/.test(kindText);
    const tech = /jvm|java|app|operating system|kernel|hardware|cpu/.test(kindText);
    const n = Math.max(3, Math.min(5, labels.length || 4));
    const names = pickLabels(earth ? ['Crust', 'Mantle', 'Outer core', 'Inner core', 'Centre'] : tech ? ['Your app', 'JVM', 'Threads', 'CPU cores', 'Hardware'] : ['Layer 1', 'Layer 2', 'Layer 3', 'Layer 4', 'Layer 5'], n);
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

  // parallel work vs one-at-a-time: lanes are tracks, tasks are blocks, a bright cursor shows what is running
  function lanes() {
    const parallel = /parallel|simultaneous|multithread|worker|cores|team|concurrent/.test(kindText);
    const n = Math.max(2, Math.min(4, labels.length || 3));
    const names = pickLabels(['Take order', 'Brew tea', 'Take payment', 'Serve'], n);
    const cursorMat = () => new THREE.MeshStandardMaterial({ color: 0xffffff, emissive: 0x67e8f9, emissiveIntensity: 1.4 });
    if (parallel) {
      const speeds = [0.5, 0.26, 0.42, 0.34];
      for (let i = 0; i < n; i++) {
        const z = (i - (n - 1) / 2) * 1.8;
        const slab = new THREE.Mesh(new THREE.BoxGeometry(8.4, 0.14, 1.2), mat(0x1e3250));
        slab.position.set(0, -0.1, z);
        const block = new THREE.Mesh(new THREE.BoxGeometry(2.0 + (i % 2) * 1.4, 0.5, 0.9), mat(COLORS[i % COLORS.length]));
        block.position.set(-1.2 + (i % 2) * 0.7, 0.25, z);
        const tag = label(names[i], 0.95); tag.position.set(-5.6, 0.35, z);
        const cursor = new THREE.Mesh(new THREE.SphereGeometry(0.22, 20, 14), cursorMat()); cursor.position.set(-4, 0.5, z);
        add([slab, block, tag, cursor], i);
        spin.push(t => { cursor.position.x = -3.9 + (((t * speeds[i % 4]) + i * 0.17) % 1) * 7.8; });
      }
      return { fit: 7.6, cam: (t) => { camera.position.set(Math.sin(t * 0.15) * 1.0, 5.6, 9.6); camera.lookAt(-0.4, 0, 0); } };
    }
    // one lane: every task waits for the one before it
    const widths = [1.7, 3.0, 1.7, 1.3];
    let x = -3.9; const marks = [];
    const slab = new THREE.Mesh(new THREE.BoxGeometry(9.6, 0.14, 1.4), mat(0x1e3250)); slab.position.set(0, -0.1, 0);
    add([slab], 0);
    for (let i = 0; i < n; i++) {
      const w = widths[i % 4], cx = x + w / 2;
      const block = new THREE.Mesh(new THREE.BoxGeometry(w - 0.12, 0.6, 1.0), mat(COLORS[i % COLORS.length])); block.position.set(cx, 0.3, 0);
      const tag = label(names[i], 0.85); tag.position.set(cx, 1.25, 0);
      add([block, tag], i);
      marks.push([x, x + w]); x += w;
    }
    const cursor = new THREE.Mesh(new THREE.SphereGeometry(0.24, 20, 14), cursorMat()); cursor.position.set(-4.2, 0.9, 0);
    add([cursor], 0);
    const total = x + 3.9;
    spin.push(t => { cursor.position.x = -4.2 + (((t * 0.16) % 1) * (total + 0.6)); });
    return { fit: 6.4, cam: (t) => { camera.position.set(Math.sin(t * 0.15) * 0.8, 3.6, 9.2); camera.lookAt(0, 0.2, 0); } };
  }


  // Narration-led 3D flow: physical nodes, glowing connections and moving packets.
  // Each node reveals at the corresponding sentence boundary; packets move continuously once visible.
  function dataFlow() {
    const n = Math.max(3, Math.min(5, labels.length || 3));
    const names = pickLabels(['Client', 'API Gateway', 'Service', 'Database', 'Response'], n);
    const group = new THREE.Group(); scene.add(group);
    const xs = names.map((_, i) => (i - (n - 1) / 2) * 2.55);
    const packets = [];
    for (let i = 0; i < n; i++) {
      const nodeGroup = new THREE.Group(); nodeGroup.position.set(xs[i], 0, i % 2 ? -0.25 : 0.25); group.add(nodeGroup);
      const box = new THREE.Mesh(new THREE.BoxGeometry(1.25, 1.05, 0.8), mat(COLORS[i % COLORS.length], { metalness: 0.35, roughness: 0.24, emissive: COLORS[i % COLORS.length], emissiveIntensity: 0.12 }));
      const edge = new THREE.LineSegments(new THREE.EdgesGeometry(box.geometry), new THREE.LineBasicMaterial({ color: 0xdbeafe }));
      const tag = label(names[i], 0.72); tag.position.set(0, 1.05, 0.15);
      const base = new THREE.Mesh(new THREE.CylinderGeometry(0.78, 0.92, 0.12, 32), mat(0x243b53)); base.position.y = -0.62;
      nodeGroup.add(box, edge, tag, base);
      add([nodeGroup], i);
      if (i < n - 1) {
        const start = new THREE.Vector3(xs[i] + 0.7, 0, 0);
        const end = new THREE.Vector3(xs[i + 1] - 0.7, 0, 0);
        const delta = end.clone().sub(start);
        const line = new THREE.Mesh(new THREE.CylinderGeometry(0.035, 0.035, delta.length(), 10), new THREE.MeshBasicMaterial({ color: 0x38bdf8, transparent: true, opacity: 0.8 }));
        line.position.copy(start.clone().add(end).multiplyScalar(0.5)); line.rotation.z = Math.PI / 2;
        const glow = new THREE.Mesh(new THREE.SphereGeometry(0.13, 16, 12), new THREE.MeshBasicMaterial({ color: 0xfbbf24, emissive: 0xfbbf24, emissiveIntensity: 1.3 }));
        glow.position.copy(start); group.add(line, glow); packets.push({ glow, start, end, at: i });
        add([line], i);
      }
    }
    const floor = new THREE.GridHelper(Math.max(12, n * 3), Math.max(12, n * 3), 0x29435f, 0x15283d); floor.position.y = -0.72; group.add(floor); add([floor], 0);
    spin.push(t => {
      group.rotation.y = Math.sin(t * 0.16) * 0.12;
      packets.forEach(p => { const u = (t * 0.65 + p.at * 0.31) % 1; p.glow.position.lerpVectors(p.start, p.end, u); p.glow.position.y = 0.04 + Math.sin(u * Math.PI) * 0.16; });
    });
    return { fit: Math.max(5.6, n * 1.45), cam: (t) => { camera.position.set(Math.sin(t * 0.12) * 0.6, 2.4, Math.max(8.5, n * 2.0)); camera.lookAt(0, 0, 0); } };
  }


  // ---- stack: a real 3D LIFO structure, not a flat tile diagram.
  // Each item is revealed bottom-up; the top item is highlighted and gently lifts to communicate POP.
  function stack() {
    const group = new THREE.Group(); scene.add(group);
    const count = Math.max(3, Math.min(5, labels.length || 4));
    const names = pickLabels(['Item 1', 'Item 2', 'Item 3', 'Item 4', 'Top item'], count);
    const blockH = 0.62, gap = 0.10, baseY = -1.72;
    const blocks = [];
    for (let i = 0; i < count; i++) {
      const color = i === count - 1 ? 0xfbbf24 : COLORS[i % COLORS.length];
      const geometry = new THREE.BoxGeometry(2.75, blockH, 1.08);
      const material = mat(color, i === count - 1
        ? { emissive: 0x8a5a00, emissiveIntensity: 0.55, metalness: 0.28, roughness: 0.28 }
        : { metalness: 0.18, roughness: 0.34 });
      const block = new THREE.Mesh(geometry, material);
      block.position.set(-0.15, baseY + i * (blockH + gap), 0);
      const edge = new THREE.LineSegments(new THREE.EdgesGeometry(geometry),
        new THREE.LineBasicMaterial({ color: i === count - 1 ? 0xfff0ad : 0xdbeafe }));
      edge.position.copy(block.position);
      const tag = label(names[i], 0.88);
      tag.position.set(-0.15, block.position.y, 0.62);
      const contents = [block, edge, tag];
      add(contents, i);
      contents.forEach(o => group.add(o));
      blocks.push({ block, edge, tag, baseY: block.position.y, top: i === count - 1 });
    }
    const floor = new THREE.GridHelper(7, 10, 0x29435f, 0x15283d);
    floor.position.y = -1.92; add([floor], 0); group.add(floor);

    // The arrows are visible as the narration introduces the two operations.
    const pushArrow = new THREE.ArrowHelper(new THREE.Vector3(0, -1, 0), new THREE.Vector3(2.25, 2.0, 0.1), 0.85, 0x38bdf8, 0.25, 0.16);
    const pushLabel = label('PUSH', 0.86); pushLabel.position.set(2.65, 2.55, 0);
    const popArrow = new THREE.ArrowHelper(new THREE.Vector3(0, 1, 0), new THREE.Vector3(-2.35, -0.1, 0.1), 0.85, 0xfbbf24, 0.25, 0.16);
    const popLabel = label('POP', 0.86); popLabel.position.set(-2.65, 0.9, 0);
    add([pushArrow, pushLabel, popArrow, popLabel], Math.max(1, count - 2));
    [pushArrow, pushLabel, popArrow, popLabel].forEach(o => group.add(o));

    spin.push(t => {
      group.rotation.y = Math.sin(t * 0.22) * 0.12;
      const top = blocks[blocks.length - 1];
      if (top) {
        const lift = Math.max(0, Math.sin(t * 1.7)) * 0.12;
        top.block.position.y = top.baseY + lift;
        top.edge.position.y = top.baseY + lift;
        top.tag.position.y = top.baseY + lift;
      }
    });
    return {
      fit: 4.6,
      cam: (t) => { camera.position.set(3.0 + Math.sin(t * 0.08) * 0.12, 1.55, 6.9); camera.lookAt(0, 0.05, 0); }
    };
  }

  // ---- earth: a globe lit from one side (real day and night), a sun, and a "you" marker that rides round from day into night
  function earth() {
    amb.intensity = 0.32; key.visible = false;
    const sunPos = new THREE.Vector3(-8.5, 1.4, 2.5);
    const sunLight = new THREE.DirectionalLight(0xfff2cc, 3.2); sunLight.position.copy(sunPos); scene.add(sunLight);
    const cv = document.createElement('canvas'); cv.width = 1024; cv.height = 512; const g = cv.getContext('2d');
    const og = g.createLinearGradient(0, 0, 0, 512); og.addColorStop(0, '#2b7fe0'); og.addColorStop(0.5, '#1d6fd1'); og.addColorStop(1, '#2b7fe0'); g.fillStyle = og; g.fillRect(0, 0, 1024, 512);
    let sd = 7; const rnd = () => { sd = (sd * 16807) % 2147483647; return sd / 2147483647; };
    for (let i = 0; i < 34; i++) { const x = rnd() * 1024, y = 90 + rnd() * 330, rx = 30 + rnd() * 90, ry = 18 + rnd() * 50; g.fillStyle = rnd() < 0.7 ? '#43b05f' : '#9a8a45'; for (let k = 0; k < 4; k++) { g.beginPath(); g.ellipse(x + (rnd() - 0.5) * rx, y + (rnd() - 0.5) * ry, rx * (0.5 + rnd() * 0.6), ry * (0.5 + rnd() * 0.6), rnd() * 3, 0, 7); g.fill(); } }
    g.fillStyle = '#f4f8ff'; g.fillRect(0, 0, 1024, 34); g.fillRect(0, 478, 1024, 34);
    const globe = new THREE.Mesh(new THREE.SphereGeometry(2.1, 64, 48), new THREE.MeshStandardMaterial({ map: new THREE.CanvasTexture(cv), roughness: 0.85 }));
    globe.rotation.z = 0.41; add([globe], 0);
    const sun = new THREE.Mesh(new THREE.SphereGeometry(0.95, 32, 24), new THREE.MeshBasicMaterial({ color: 0xffd54a })); sun.position.set(-5.1, 1.1, 0.5);
    const halo = new THREE.Mesh(new THREE.SphereGeometry(1.35, 32, 24), new THREE.MeshBasicMaterial({ color: 0xffe28a, transparent: true, opacity: 0.28 })); halo.position.copy(sun.position);
    const tagSun = label('Sun', 0.9); tagSun.position.set(sun.position.x, sun.position.y + 1.7, 0); add([sun, halo, tagSun], 1);
    const you = new THREE.Mesh(new THREE.SphereGeometry(0.13, 16, 12), new THREE.MeshBasicMaterial({ color: 0xff3d71 }));
    const lat = 0.35, lon0 = 0; you.position.set(Math.cos(lat) * Math.sin(lon0) * 2.14, Math.sin(lat) * 2.14, Math.cos(lat) * Math.cos(lon0) * 2.14); globe.add(you);
    const tagYou = label('You', 0.8), tagDay = label('Day', 0.95), tagNight = label('Night', 0.95);
    tagDay.position.set(-2.7, 2.5, 0); tagNight.position.set(2.9, 2.5, 0);
    add([you, tagYou], 2); add([tagDay, tagNight], 2);
    spin.push(t => { globe.rotation.y = -t * 0.55 + 1.3; const w = new THREE.Vector3(); you.getWorldPosition(w); tagYou.position.set(w.x, w.y + 0.55, w.z + 0.2); tagYou.visible = w.z > -0.4 && you.visible; });
    return { fit: 7.0, cam: () => { camera.position.set(0.4, 1.4, 9.6); camera.lookAt(-0.6, 0, 0); } };
  }
  const builders = { orbit, molecule, layers, axes, lanes, earth, dataFlow, stack };
  let kind = builders[opts.kind] ? opts.kind : null;
  if (!kind) {
    if (/orbit|planet|solar|galaxy|satellite/.test(kindText)) kind = 'orbit';
    else if (/stack|lifo|push|pop|plate pile|pile of plates/.test(kindText)) kind = 'stack';
    else if (/molecule|atom|bond|helix|dna|crystal|lattice/.test(kindText)) kind = 'molecule';
    else if (/layer|crust|mantle|cross.section|atmosphere/.test(kindText)) kind = 'layers';
    else if (/3d|cube|sphere|axes|three-dimensional|coordinate|pyramid|cylinder/.test(kindText)) kind = 'axes';
    else if (/api gateway|microservice|data flow|request.{0,20}(route|forward)|router|network|queue|worker|database|client.{0,20}server|server.{0,20}client|pipeline|packet/.test(kindText)) kind = 'dataFlow';
    else if (/parallel|multithread|simultaneous|one at a time|in order|sequential/.test(kindText)) kind = 'lanes';
  }
  const rig = (kind ? builders[kind] : nodes)();

  // title, drawn as page text so it is crisp
  // same heading look as the native renderer: a small grey kicker over a regular-weight title, scaled to the frame height
  const k = H / 720;
  const head = document.createElement('div');
  head.style.cssText = `position:absolute;top:${portrait ? 8.5 : 1.2}%;left:5%;right:5%;text-align:center;text-shadow:${LIGHT ? 'none' : '0 2px 10px #000'};font-family:Lato,Arial,Helvetica,'DejaVu Sans',sans-serif`;
  head.innerHTML = `<div style="color:${LIGHT ? '#7a7396' : '#8a9bb0'};font:${portrait ? 13 * k : 15 * k}px 'DejaVu Sans Mono',monospace;margin-bottom:${4 * k}px"></div><div style="color:${LIGHT ? '#2a2540' : '#eaf4ff'};font-size:${portrait ? 64 * k * 0.56 : 36 * k}px;font-weight:400"></div>`;
  head.children[0].textContent = String(opts.kicker || '').slice(0, 60);
  head.children[1].textContent = String(opts.title || '').slice(0, 90);
  document.body.appendChild(head);

  // step k reveals everything that belongs to steps 0..k; the newest item eases in over the first half second
  // frames may be fewer than items (one frame per narration sentence): spread the reveal so the LAST frame shows everything
  const maxAppear = () => items.reduce((m, it) => Math.max(m, it.appearAt), 0);
  function stepFor(k) { const n = Math.max(1, (opts.count || 1) - 1); return opts.count > 1 ? Math.round(k * maxAppear() / n) : maxAppear(); }
  // camera + tall-frame fit + draw, shared by the still-frame and the timeline renderers
  function finish(t) {
    spin.forEach(fn => fn(t));
    rig.cam(t);
    if (portrait) {   // a tall frame is narrow: pull back until the scene's width (about 5.6 units each side of the centre) fits
      const dist = camera.position.length();
      const halfW = dist * Math.tan(THREE.MathUtils.degToRad(camera.fov / 2)) * camera.aspect;
      const need = rig.fit || 5.6;   // half-width a template needs; lanes carry wide labels
      if (halfW < need) {
        const aim = camera.position.clone().add(camera.getWorldDirection(new THREE.Vector3()).multiplyScalar(dist));   // the point the template was looking at
        camera.position.multiplyScalar(need / halfW);
        camera.lookAt(aim);                                                                                       // keep looking at it (scaling alone slides the view sideways)
      }
    }
    renderer.render(scene, camera);
  }

  // ---- timeline mode: a real animation. Item i pops in when the narration reaches the sentence about it (starts = sentence start times in seconds),
  // and the camera keeps moving (the template's own sway / orbit), so the 3D scene is alive for the whole narration.
  const easeOut = k => 1 - Math.pow(1 - k, 3);
  function revealTimes(starts, steps) {
    const out = [], count = starts.length;
    for (let i = 0; i < steps; i++) {
      let k = 0;
      if (count > 1) { k = count - 1; for (let j = 0; j < count; j++) if (Math.round(j * (steps - 1) / (count - 1)) >= i) { k = j; break; } }
      out.push(count ? Math.max(0, starts[k] - 0.15) : i * 1.2);
    }
    return out;
  }
  function renderTime(t, starts) {
    const rv = revealTimes(starts || [], maxAppear() + 1);
    items.forEach(it => {
      const r = rv[Math.min(it.appearAt, rv.length - 1)], show = t >= r, e = easeOut(Math.min(1, Math.max(0, (t - r) / 0.6)));
      it.objs.forEach(o => {
        o.visible = show;
        if (show && !o.isLight && !o.isArrowHelper) { o.userData.s0 = o.userData.s0 || o.scale.clone(); o.scale.copy(o.userData.s0).multiplyScalar(0.55 + 0.45 * e); }
      });
    });
    finish(t);
  }
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
    finish(t);
  }
  return { renderStep, renderTime, kind: kind || 'nodes' };
}
