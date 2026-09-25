/**
 * Why one model will not read. Walks the same steps as `src/bml.mjs` and says where it stops.
 * Not part of a build.
 *
 *   node bml-why.mjs <theater id> <gfx index>
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
  file = findFileCI(d, 'Model_0.bml') || findFileCI(d, 'Model_1.bml') || findFileCI(d, 'Model_2.bml');
  if (file) break;
}
if (!file) { console.log('no model file'); process.exit(1); }

const b = inflate(file);
if (!b) { console.log('will not decompress'); process.exit(1); }
console.log(`${b.length} bytes`);

const n = b.readUInt32LE(4);
const at = 8 + 4 * n;
const total = b.readUInt32LE(at + 8);
const meshes = b.readUInt32LE(at + 12);
const tableAt = at + 52;
console.log(`list of ${n} -> counts at ${at}: total ${total}, meshes ${meshes}, table at ${tableAt}`);

// walk greedily and report every entry, whatever it looks like
let p = tableAt;
let sum = 0;
for (let i = 0; i < meshes + 20 && p + 8 <= b.length; i++) {
  const count = b.readUInt32LE(p);
  const stride = b.readUInt32LE(p + 4);
  const fx = b.readFloatLE(p + 8), fy = b.readFloatLE(p + 12), fz = b.readFloatLE(p + 16);
  const okCount = count % 3 === 0 && count <= total;
  const okStride = stride === 36 || stride === 40;
  sum += okCount && okStride ? count : 0;
  console.log(`   ${String(i).padStart(3)} at ${String(p).padStart(6)}  count ${String(count).padStart(9)}${okCount ? ' ' : '!'}`
    + `  stride ${String(stride).padStart(4)}${okStride ? ' ' : '!'}  (${fx.toFixed(1)}, ${fy.toFixed(3)}, ${fz.toFixed(1)})  running sum ${sum}`);
  if (sum === total) { console.log(`   -> sum reaches the total after ${i + 1} entries`); break; }
  // try the usual lengths and take the first that looks like an entry
  let moved = false;
  for (const len of [78, 126, 174, 222, 270]) {
    if (p + len + 8 > b.length) continue;
    const c2 = b.readUInt32LE(p + len), s2 = b.readUInt32LE(p + len + 4);
    if ((s2 === 36 || s2 === 40) && c2 % 3 === 0 && c2 <= total) { p += len; moved = true; break; }
  }
  if (!moved) { console.log(`   -> no entry follows at any of the usual lengths; stopped at ${p}`); break; }
}
