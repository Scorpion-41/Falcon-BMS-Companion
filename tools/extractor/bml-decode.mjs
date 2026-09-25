/**
 * Working out the .bml mesh format, on one model at a time. Not part of a build.
 *
 *   node bml-decode.mjs <theater id> <gfx index> [command]
 *
 * commands: head (default), chunks, region <offset> <length>
 */
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { loadTheaters } from './src/theaters.mjs';
import { findFileCI, DATA } from './src/util.mjs';

const th = loadTheaters().find((t) => t.id === process.argv[2]);
const gi = Number(process.argv[3]);
const cmd = process.argv[4] || 'head';
const XZ = 'C:/Program Files/Git/mingw64/bin/xz.exe';
const tmp = 'C:/temp/bmldec';
fs.mkdirSync(tmp, { recursive: true });

function raw(idx) {
  for (const b0 of [path.join(th.data3dDir, 'Models'), path.join(th.objectDir, 'Models'), path.join(DATA, 'TerrData/Objects/Models')]) {
    const d = findFileCI(b0, String(idx));
    if (!d) continue;
    const f = findFileCI(d, 'Model_0.bml');
    if (!f) continue;
    const bb = fs.readFileSync(f);
    const rs = Number(bb.readBigUInt64LE(12));
    const sz = Buffer.alloc(8);
    sz.writeBigUInt64LE(BigInt(rs));
    const lz = path.join(tmp, 'm.lzma');
    fs.writeFileSync(lz, Buffer.concat([bb.subarray(28, 33), sz, bb.subarray(33)]));
    return execFileSync(XZ, ['--format=lzma', '-d', '-c', lz], { maxBuffer: 1 << 30, stdio: ['ignore', 'pipe', 'ignore'] });
  }
  return null;
}

const b = raw(gi);
if (!b) { console.log('no model'); process.exit(1); }
console.log(`gfx ${gi}: ${b.length} bytes decompressed\n`);

const f32 = (i) => b.readFloatLE(i);
const u32 = (i) => b.readUInt32LE(i);
const looksFloat = (v) => Number.isFinite(v) && (v === 0 || (Math.abs(v) > 1e-6 && Math.abs(v) < 1e6));

function dump(off, len) {
  for (let i = off; i < Math.min(off + len, b.length); i += 16) {
    const s = b.subarray(i, i + 16);
    const hex = [...s].map((v) => v.toString(16).padStart(2, '0')).join(' ');
    const asc = [...s].map((v) => (v >= 32 && v < 127 ? String.fromCharCode(v) : '.')).join('');
    const ints = [0, 4, 8, 12].filter((k) => i + k + 4 <= b.length).map((k) => String(u32(i + k)).padStart(10)).join(' ');
    const fls = [0, 4, 8, 12].filter((k) => i + k + 4 <= b.length).map((k) => {
      const v = f32(i + k);
      return (looksFloat(v) ? v.toFixed(3) : '-').padStart(11);
    }).join(' ');
    console.log(`${String(i).padStart(8)}  ${hex}  ${asc}`);
    console.log(`          u32 ${ints}`);
    console.log(`          f32 ${fls}`);
  }
}

if (cmd === 'head') {
  dump(0, Number(process.argv[5] || 320));
} else if (cmd === 'region') {
  dump(Number(process.argv[5]), Number(process.argv[6] || 128));
} else if (cmd === 'chunks') {
  // Every place a plausible (count, stride) pair sits, and what follows it.
  console.log('offset      count  stride  bytes       ends at     note');
  for (let i = 0; i + 8 < b.length; i += 2) {
    const count = u32(i), stride = u32(i + 4);
    if (stride < 8 || stride > 128 || stride % 4 !== 0) continue;
    if (count < 8 || count > 4e6) continue;
    const bytes = count * stride;
    if (i + 8 + bytes > b.length) continue;
    // does what follows look like vertices? first record's three floats
    const x = f32(i + 8), y = f32(i + 12), z = f32(i + 16);
    if (!looksFloat(x) || !looksFloat(z)) continue;
    if (Math.abs(x) > 40000 || Math.abs(z) > 40000) continue;
    if (Math.abs(y) > 2000) continue;
    const end = i + 8 + bytes;
    console.log(`${String(i).padStart(8)}  ${String(count).padStart(8)}  ${String(stride).padStart(6)}  ${String(bytes).padStart(10)}  ${String(end).padStart(10)}  ${end === b.length ? 'ENDS FILE' : ''} first (${x.toFixed(1)}, ${y.toFixed(4)}, ${z.toFixed(1)})`);
  }
}
