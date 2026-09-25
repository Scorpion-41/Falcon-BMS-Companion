/**
 * Reads models with `src/bml.mjs` and reports whether each one parses and what it contains.
 * Not part of a build; this is the check that says the format is understood.
 *
 *   node bml-check.mjs <theater id> <gfx index>...
 */
import path from 'node:path';
import { loadTheaters } from './src/theaters.mjs';
import { findFileCI, DATA } from './src/util.mjs';
import { readModel, groundTriangles } from './src/bml.mjs';

const th = loadTheaters().find((t) => t.id === process.argv[2]);

function modelFile(idx) {
  for (const b0 of [path.join(th.data3dDir, 'Models'), path.join(th.objectDir, 'Models'), path.join(DATA, 'TerrData/Objects/Models')]) {
    const d = findFileCI(b0, String(idx));
    if (!d) continue;
    const f = findFileCI(d, 'Model_0.bml') || findFileCI(d, 'Model_1.bml') || findFileCI(d, 'Model_2.bml');
    if (f) return f;
  }
  return null;
}

for (const gi of process.argv.slice(3).map(Number)) {
  const file = modelFile(gi);
  if (!file) { console.log(`gfx ${gi}: no model file`); continue; }
  const m = readModel(file);
  if (!m) { console.log(`gfx ${gi}: does not parse`); continue; }
  const g = groundTriangles(file);
  const n = g.xz.length / 6;

  let area = 0, huge = 0;
  let lo = Infinity, hi = -Infinity, lz = Infinity, hz = -Infinity;
  const edges = [];
  for (let i = 0; i + 5 < g.xz.length; i += 6) {
    const ax = g.xz[i], az = g.xz[i + 1], bx = g.xz[i + 2], bz = g.xz[i + 3], cx = g.xz[i + 4], cz = g.xz[i + 5];
    area += Math.abs((bx - ax) * (cz - az) - (cx - ax) * (bz - az)) / 2;
    const L = Math.max(Math.hypot(bx - ax, bz - az), Math.hypot(cx - bx, cz - bz), Math.hypot(ax - cx, az - cz));
    edges.push(L);
    if (L > 1500) huge++;
    for (const q of [[ax, az], [bx, bz], [cx, cz]]) {
      lo = Math.min(lo, q[0]); hi = Math.max(hi, q[0]); lz = Math.min(lz, q[1]); hz = Math.max(hz, q[1]);
    }
  }
  edges.sort((a, b) => a - b);
  console.log(`gfx ${gi}: ${m.total} vertices in ${m.meshes} meshes -> ${m.tris.length / 9} triangles, ${n} on the ground`);
  console.log(`   ${Math.round(area).toLocaleString()} sq ft, spanning ${Math.round(hi - lo)} x ${Math.round(hz - lz)} ft,`
    + ` longest edge median ${edges.length ? edges[Math.floor(edges.length / 2)].toFixed(1) : '-'} ft, ${huge} over 1500 ft`);
}
