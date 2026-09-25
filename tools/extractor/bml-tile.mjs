/**
 * Do a model's ground triangles tile, or do they pile up?
 *
 * A real surface covers each patch of ground once, so the sum of its triangle areas is close to the area of their
 * union. A mis-read mesh makes triangles out of unrelated vertices, which overlap wildly. Not part of a build.
 *
 *   node bml-tile.mjs <theater id> <field name>
 */
import path from 'node:path';
import { loadTheaters } from './src/theaters.mjs';
import { buildAirports } from './src/airports.mjs';
import { loadDb } from './src/db.mjs';
import { parseRecords, findFileCI, DATA } from './src/util.mjs';
import { modelGround } from './src/pavement.mjs';

const th = loadTheaters().find((t) => t.id === process.argv[2]);
const want = process.argv[3];
const db = loadDb(th);
const { airports, geo } = buildAirports(th);

const ocdDir = (o) => findFileCI(path.join(th.data3dDir, 'ObjectiveRelatedData'), `OCD_${o}`)
  || findFileCI(path.join(th.objectDir, 'ObjectiveRelatedData'), `OCD_${o}`)
  || findFileCI(path.join(DATA, 'TerrData/Objects/ObjectiveRelatedData'), `OCD_${o}`);

const a = airports.find((x) => x.name.toLowerCase() === want.toLowerCase())
  || airports.find((x) => x.name.toLowerCase().startsWith(want.toLowerCase()));
const g = geo.get(a.id);
const id = String(g.ocd).padStart(5, '0');
const fed = (parseRecords(findFileCI(ocdDir(id), `FED_${id}.XML`), 'FED') || []).filter(Boolean);

const seen = new Set();
console.log(`${a.name}:`);
for (const f of fed) {
  const ct = db.ct[+f.FeatureCtIdx];
  if (!ct) continue;
  const gi = +ct.GraphicsNormal;
  if (seen.has(gi)) continue;
  seen.add(gi);
  const tris = modelGround(th, gi);
  if (!tris || tris.length < 6) continue;

  let sum = 0, lo = Infinity, hi = -Infinity, lz = Infinity, hz = -Infinity, huge = 0;
  for (let i = 0; i + 5 < tris.length; i += 6) {
    const ax = tris[i], az = tris[i + 1], bx = tris[i + 2], bz = tris[i + 3], cx = tris[i + 4], cz = tris[i + 5];
    sum += Math.abs((bx - ax) * (cz - az) - (cx - ax) * (bz - az)) / 2;
    const L = Math.max(Math.hypot(bx - ax, bz - az), Math.hypot(cx - bx, cz - bz), Math.hypot(ax - cx, az - cz));
    if (L > 1500) huge++;
    for (const q of [[ax, az], [bx, bz], [cx, cz]]) {
      lo = Math.min(lo, q[0]); hi = Math.max(hi, q[0]); lz = Math.min(lz, q[1]); hz = Math.max(hz, q[1]);
    }
  }

  // the union, on a 5 ft grid
  const G = 5;
  const w = Math.min(4000, Math.ceil((hi - lo) / G) + 2), h = Math.min(4000, Math.ceil((hz - lz) / G) + 2);
  const mask = new Uint8Array(w * h);
  for (let i = 0; i + 5 < tris.length; i += 6) {
    const px = [0, 2, 4].map((k) => (tris[i + k] - lo) / G);
    const py = [1, 3, 5].map((k) => (tris[i + k] - lz) / G);
    const y0 = Math.max(0, Math.floor(Math.min(...py)));
    const y1 = Math.min(h - 1, Math.ceil(Math.max(...py)));
    for (let y = y0; y <= y1; y++) {
      const xs = [];
      for (let e = 0; e < 3; e++) {
        const j = (e + 1) % 3;
        if ((py[e] <= y && py[j] > y) || (py[j] <= y && py[e] > y)) xs.push(px[e] + ((y - py[e]) / (py[j] - py[e])) * (px[j] - px[e]));
      }
      if (xs.length < 2) continue;
      xs.sort((u, v) => u - v);
      for (let x = Math.max(0, Math.round(xs[0])); x <= Math.min(w - 1, Math.round(xs[xs.length - 1])); x++) mask[y * w + x] = 1;
    }
  }
  let cells = 0;
  for (let i = 0; i < mask.length; i++) cells += mask[i];
  const union = cells * G * G;
  const name = db.fcd[+ct.EntityIdx]?.Name || '?';
  console.log(`   gfx ${String(gi).padStart(5)}  ${String(tris.length / 6).padStart(6)} tris`
    + `  sum ${String(Math.round(sum)).padStart(10)}  union ${String(Math.round(union)).padStart(10)}`
    + `  ratio ${(sum / (union || 1)).toFixed(2).padStart(7)}  ${String(huge).padStart(4)} over 1500 ft   ${name}`);
}
