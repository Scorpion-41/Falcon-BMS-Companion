/**
 * Every plausible way to read one model, scored. Not part of a build.
 *
 *   node bml-probe.mjs <theater id> <gfx index>
 */
import path from 'node:path';
import { loadTheaters } from './src/theaters.mjs';
import { findFileCI, DATA } from './src/util.mjs';
import { inflate } from './src/bml.mjs';

const th = loadTheaters().find((t) => t.id === process.argv[2]);
const gi = Number(process.argv[3]);

let file = null;
for (const b0 of [path.join(th.data3dDir, 'Models'), path.join(th.objectDir, 'Models'), path.join(DATA, 'TerrData/Objects/Models')]) {
  const d = findFileCI(b0, String(gi));
  if (!d) continue;
  file = findFileCI(d, 'Model_0.bml') || findFileCI(d, 'Model_1.bml');
  if (file) break;
}
const b = inflate(file);
const n = b.readUInt32LE(4);
const at = 8 + 4 * n;
const total = b.readUInt32LE(at + 8);
const tableAt = at + 52;
console.log(`gfx ${gi}: ${b.length} bytes, ${total} vertices, table at ${tableAt}`);

// all chains that use up every vertex
const LENGTHS = [78, 126, 174, 222, 270];
const chains = [];
const chain = [];
const walk = (p, sum) => {
  if (chains.length >= 6) return;
  if (sum === total) { chains.push(chain.slice()); return; }
  if (p + 8 > b.length || chain.length > 4096) return;
  const count = b.readUInt32LE(p), stride = b.readUInt32LE(p + 4);
  if (!(stride === 32 || stride === 36 || stride === 40) || count % 3 || sum + count > total) return;
  chain.push({ count, stride, at: p });
  for (const L of LENGTHS) walk(p + L, sum + count);
  chain.pop();
};
walk(tableAt, 0);
console.log(`   ${chains.length} chain(s) account for every vertex`);

for (const [ci, table] of chains.entries()) {
  for (const perMesh of [false, true]) {
    const need = table.reduce((a, m) => a + m.count * (perMesh ? m.stride : 36), 0);
    const from = table[table.length - 1].at + 8;
    const maxAt = b.length - need;
    if (maxAt < from) continue;
    for (let start = from; start <= Math.min(maxAt, from + 600); start++) {
      // quick reject: the first record must have a unit normal
      const L0 = Math.hypot(b.readFloatLE(start + 12), b.readFloatLE(start + 16), b.readFloatLE(start + 20));
      if (!(L0 > 0.97 && L0 < 1.03)) continue;

      let good = 0, seen = 0, huge = 0, tris = 0, area = 0;
      let off = start;
      for (const m of table) {
        const st = perMesh ? m.stride : 36;
        for (let k = 0; k < m.count; k++) {
          const i = off + k * st;
          if (i + 24 > b.length) break;
          seen++;
          const len = Math.hypot(b.readFloatLE(i + 12), b.readFloatLE(i + 16), b.readFloatLE(i + 20));
          if (len > 0.97 && len < 1.03) good++;
        }
        for (let t = 0; t + 2 < m.count; t += 3) {
          const q = [0, 1, 2].map((j) => {
            const i = off + (t + j) * st;
            return [b.readFloatLE(i), b.readFloatLE(i + 4), b.readFloatLE(i + 8)];
          });
          if (!q.every((v) => Math.abs(v[1]) <= 0.05 && Number.isFinite(v[0]) && Math.abs(v[0]) < 60000)) continue;
          tris++;
          const Lm = Math.max(
            Math.hypot(q[1][0] - q[0][0], q[1][2] - q[0][2]),
            Math.hypot(q[2][0] - q[1][0], q[2][2] - q[1][2]),
            Math.hypot(q[0][0] - q[2][0], q[0][2] - q[2][2]),
          );
          if (Lm > 1500) huge++;
          area += Math.abs((q[1][0] - q[0][0]) * (q[2][2] - q[0][2]) - (q[2][0] - q[0][0]) * (q[1][2] - q[0][2])) / 2;
        }
        off += m.count * st;
      }
      if (!seen || good / seen < 0.98) continue;
      console.log(`   chain ${ci}  ${perMesh ? 'own stride' : 'all 36   '}  start ${String(start).padStart(7)}`
        + `  normals ${(100 * good / seen).toFixed(1)}%  ground tris ${String(tris).padStart(6)}`
        + `  over 1500 ft ${String(huge).padStart(5)}  area ${Math.round(area).toLocaleString()}`);
    }
  }
}
