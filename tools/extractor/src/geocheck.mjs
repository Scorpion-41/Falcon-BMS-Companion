// Registration check: projects real airport coordinates (OurAirports) into each theater and compares them with BMS positions.
// Usage: BMS_ROOT="G:/Falcon BMS 4.38" node src/geocheck.mjs
import fs from 'node:fs';
import path from 'node:path';
import { loadTheaters } from './theaters.mjs';
import { buildAirports } from './airports.mjs';
import { readTheaterTxt, makeProjector, ROOT_CACHE } from './projection.mjs';

const csv = fs.readFileSync(path.join(ROOT_CACHE, 'airports.csv'), 'utf8').split('\n');
const head = csv[0].split(',').map((s) => s.replace(/"/g, ''));
const col = (n) => head.indexOf(n);
const real = new Map();
for (const line of csv.slice(1)) {
  const f = line.match(/("([^"]|"")*"|[^,]*)(,|$)/g)?.map((s) => s.replace(/,$/, '').replace(/^"|"$/g, ''));
  if (!f) continue;
  for (const k of [f[col('icao_code')], f[col('gps_code')], f[col('ident')]]) if (k && k.length === 4 && !real.has(k)) real.set(k, [parseFloat(f[col('latitude_deg')]), parseFloat(f[col('longitude_deg')])]);
}
const done = new Set();
for (const th of loadTheaters()) {
  const t = readTheaterTxt(th.terrainDir);
  if (!t || done.has(t.dir)) continue;
  done.add(t.dir);
  const proj = makeProjector(t);
  const { airports } = buildAirports(th);
  const errs = [];
  for (const a of airports) {
    const ll = a.icao && real.get(a.icao);
    if (!ll) continue;
    const p = proj(ll[0], ll[1]);
    errs.push({ icao: a.icao, dnm: Math.hypot(p.x - a.x, p.y - a.y) / 6076.12, dx: (p.x - a.x) / 6076.12, dy: (p.y - a.y) / 6076.12 });
  }
  errs.sort((a, b) => a.dnm - b.dnm);
  const med = errs.length ? errs[Math.floor(errs.length / 2)] : null;
  const mdx = errs.map((e) => e.dx).sort((a, b) => a - b)[Math.floor(errs.length / 2)];
  const mdy = errs.map((e) => e.dy).sort((a, b) => a - b)[Math.floor(errs.length / 2)];
  console.log(`${th.id} (${t.name}): ${errs.length} matched, median error ${med?.dnm.toFixed(2)} nm, median dN ${mdx?.toFixed(2)} dE ${mdy?.toFixed(2)} nm, 80% within ${errs[Math.floor(errs.length * 0.8)]?.dnm.toFixed(2)} nm`);
}
