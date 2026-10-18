// Usage: node smoke.mjs http://localhost:5020   (renders every 3D template and checks the failure paths)
const base = process.argv[2] || 'http://localhost:5020';
const post = body => fetch(base + '/render-frames', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) }).then(async r => ({ status: r.status, json: await r.json() }));
let failed = 0;
const check = (name, ok, extra = '') => { console.log((ok ? 'PASS ' : 'FAIL ') + name + ' ' + extra); if (!ok) failed++; };
const h = await (await fetch(base + '/health')).json();
check('health reports Motion Canvas unavailable', h.engines.MOTION_CANVAS === false);
for (const [kind, title, labels] of [['orbit', 'Planets orbit the sun', ['Sun', 'Earth', 'Mars']], ['molecule', 'Water molecule', ['O', 'H', 'H']], ['layers', 'Layers of the Earth', ['Crust', 'Mantle', 'Core']], ['axes', 'A cube in 3D', ['Cube']], ['', 'Generic', ['A', 'B', 'C']]]) {
  const r = await post({ engine: 'THREE_JS', title, labels, sceneKind: kind, steps: 3, width: 640, height: 360 });
  check('THREE_JS ' + (kind || 'nodes'), r.status === 200 && r.json.rendererStatus === 'RENDERED' && r.json.frames.length === 3, r.json.template || '');
}
let r = await post({ engine: 'RIVE' });
check('RIVE without asset is rejected', r.status === 503 && r.json.rendererStatus === 'FAILED');
r = await post({ engine: 'RIVE', riveAssetBase64: Buffer.from('not a rive file').toString('base64') });
check('RIVE with a garbage file is rejected', r.status === 503 && r.json.rendererStatus === 'FAILED', r.json.error);
r = await post({ engine: 'MOTION_CANVAS' });
check('MOTION_CANVAS is rejected', r.status === 503);
process.exit(failed ? 1 : 0);
