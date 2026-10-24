import { geoContains } from 'd3-geo';
import { feature } from 'topojson-client';
import { createRequire } from 'node:module';
import fs from 'node:fs';
const require = createRequire(import.meta.url);
const world = require('world-atlas/countries-110m.json');
const land = feature(world, world.objects.land).features[0];
const N = 60000, ga = Math.PI * (3 - Math.sqrt(5)), out = [];
for (let i = 0; i < N; i++) {
  const y = 1 - (2 * i + 1) / N, lat = Math.asin(y) * 180 / Math.PI, lng = (((i * ga) * 180 / Math.PI) % 360) - 180;
  if (geoContains(land, [lng, lat])) out.push([+lat.toFixed(2), +lng.toFixed(2)]);
}
fs.writeFileSync('journey/land-dots.json', JSON.stringify(out));
console.log('land dots', out.length);
