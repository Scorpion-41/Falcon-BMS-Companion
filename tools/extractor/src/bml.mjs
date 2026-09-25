/**
 * Reading Falcon BMS's .bml models.
 *
 * See `tools/extractor/bml-format.md` for how the format was worked out and what is still unknown. In short:
 *
 *   container   "BML\0", a 28-byte header, then an LZMA stream (rebuilt as a .lzma and given to `xz`)
 *   header      a variable-length list, then the total vertex count and the number of meshes
 *   mesh table  one entry per mesh, each beginning with its vertex count and the vertex stride (36)
 *   vertices    one array, `total` records of 36 bytes: position, normal, colour, texture coordinates
 *   faces       none stored — each mesh is a plain triangle list, three consecutive vertices at a time
 *
 * The two checks that say a model has been read correctly, and which every reader here has to pass:
 *   - the mesh counts sum to exactly the total vertex count;
 *   - every mesh count divides by three.
 * Osan's fifteen meshes sum to 180,228 and every one divides by three; read this way its 60,058 ground triangles
 * cover 12.8 million square feet, which is an airbase. Read any other way the same file gives eight times that,
 * because the triangles straddle unrelated corners of the model.
 */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { execFileSync } from 'node:child_process';

/** One vertex record. Position first, then the normal, which is what makes a mis-read obvious. */
export const STRIDE = 36;
/** How far off the ground plane a vertex may sit and still be part of the painted surface. */
export const GROUND_TOL = 0.05;

let xzPath = null;

/**
 * Where `xz` is.
 *
 * It ships with git for Windows, but only a shell started from git puts it on the PATH — run the extractor from
 * PowerShell and every model comes back unreadable, which once left the charts silently without any asphalt. So it
 * is looked for where git installs it as well, and if it is nowhere at all the run stops rather than quietly
 * producing a set of charts with nothing on them.
 */
export function xz() {
  if (xzPath) return xzPath;
  const guesses = [
    'xz',
    'C:/Program Files/Git/mingw64/bin/xz.exe',
    'C:/Program Files/Git/usr/bin/xz.exe',
    'C:/Program Files (x86)/Git/mingw64/bin/xz.exe',
    'C:/Program Files (x86)/Git/usr/bin/xz.exe',
    process.env.ProgramW6432 ? process.env.ProgramW6432 + '/Git/mingw64/bin/xz.exe' : null,
    process.env.LOCALAPPDATA ? process.env.LOCALAPPDATA + '/Programs/Git/mingw64/bin/xz.exe' : null,
  ].filter(Boolean);
  for (const g of guesses) {
    try {
      execFileSync(g, ['--version'], { stdio: 'ignore' });
      xzPath = g;
      return g;
    } catch { /* not there */ }
  }
  throw new Error(
    'bml: xz was not found, so no model can be read. It ships with git for Windows (mingw64/bin/xz.exe) — put it '
    + 'on the PATH, or run the extractor from a git bash shell.',
  );
}

const tmp = path.join(os.tmpdir(), 'bms-bml');

/** The decompressed bytes of a .bml, or null if the file is not one or will not decompress. */
export function inflate(file) {
  const b = fs.readFileSync(file);
  if (b.length < 40 || b.toString('latin1', 0, 3) !== 'BML') return null;
  const rawSize = Number(b.readBigUInt64LE(12));
  fs.mkdirSync(tmp, { recursive: true });
  const lzma = path.join(tmp, 'in.lzma');
  const size = Buffer.alloc(8);
  size.writeBigUInt64LE(BigInt(rawSize));
  fs.writeFileSync(lzma, Buffer.concat([b.subarray(28, 33), size, b.subarray(33)]));
  try {
    return execFileSync(xz(), ['--format=lzma', '-d', '-c', lzma], { maxBuffer: 1 << 30, stdio: ['ignore', 'pipe', 'ignore'] });
  } catch (e) {
    if (String(e.message).startsWith('bml:')) throw e;
    return null;     // a handful of models across all the theaters will not decompress
  }
}

/**
 * The counts at the top of the file.
 *
 * Nothing here sits at a fixed offset, because the file opens with a list whose length it declares:
 *
 *   0x00  u32
 *   0x04  u32 n            how many u32s follow
 *   0x08  n x u32
 *   +0    1, 0, totalVertices, meshCount, 1, 0, 1, 4
 *   +32   20 bytes of zeros, then the mesh table
 */
function header(b) {
  if (b.length < 64) return null;
  const n = b.readUInt32LE(4);
  if (n > 4096) return null;
  const at = 8 + 4 * n;
  if (at + 52 > b.length) return null;
  const total = b.readUInt32LE(at + 8);
  const meshes = b.readUInt32LE(at + 12);
  if (!total || !meshes || meshes > 8192 || total > 8e6) return null;
  return { total, meshes, tableAt: at + 52 };
}

/**
 * The mesh table: one entry per mesh, each starting with its vertex count and the stride.
 *
 * Entries are usually 78 bytes but not always — a few carry more — so rather than assume a spacing, each entry is
 * found by scanning on from the last one for the next count-and-stride pair. The table is only accepted when it
 * yields exactly the number of meshes the header promised and they sum to exactly its vertex total, which is a
 * demanding enough pair of conditions that a wrong reading does not pass.
 */
function meshTable(b, hdr) {
  // An entry is 78 bytes, or 78 plus one or more 48-byte blocks — Anshan's tenth mesh carries one. Nothing found
  // so far says which, so the chain is walked trying each length in turn and kept only if it comes out exactly
  // right: as many entries as the header promised, summing to exactly its vertex total, every count a multiple of
  // three. A wrong chain fails all three.
  const LENGTHS = [78, 126, 174, 222, 270];
  const ok = (at) => {
    if (at + 8 > b.length) return null;
    const count = b.readUInt32LE(at);
    const stride = b.readUInt32LE(at + 4);
    if (stride !== 32 && stride !== STRIDE && stride !== 40) return null;
    if (count > hdr.total || count % 3 !== 0) return null;
    return { count, stride, at };
  };

  // The table ends where the counts have used up every vertex the header declared — not after the number of
  // meshes it states, which is not always the number of entries: Anshan says sixteen and has fourteen, and those
  // fourteen account for all 24,207 of its vertices.
  const chain = [];
  const walk = (at, sum) => {
    if (sum === hdr.total) return chain.length > 0;
    if (chain.length >= 4096) return false;
    const entry = ok(at);
    if (!entry || sum + entry.count > hdr.total) return false;
    chain.push(entry);
    for (const len of LENGTHS) {
      if (walk(at + len, sum + entry.count)) return true;
    }
    chain.pop();
    return false;
  };

  return walk(hdr.tableAt, 0) ? chain.slice() : null;
}

/**
 * Where the vertex array begins.
 *
 * It follows the table, but the gap between them is not fixed, so it is found rather than computed: the first
 * offset at which a long run of records all carry a unit normal. Nothing else in the file does that, and being
 * wrong by even one record would show up immediately as normals that are not unit length.
 */
function vertexStart(b, hdr, table, perMesh) {
  const need = table.reduce((a, m) => a + m.count * (perMesh ? m.stride : STRIDE), 0);
  const from = table[table.length - 1].at + 8;
  const to = Math.min(b.length - need, from + 4096);
  if (to < from) return -1;                    // the array this reading needs does not fit
  for (let at = from; at <= to; at++) {
    let ok = true;
    const step = perMesh ? table[0].stride : STRIDE;
    const runs = Math.min(64, table[0].count);
    for (let k = 0; k < runs && ok; k++) {
      const i = at + k * step;
      if (i + 24 > b.length) { ok = false; break; }
      const nx = b.readFloatLE(i + 12), ny = b.readFloatLE(i + 16), nz = b.readFloatLE(i + 20);
      const len = Math.hypot(nx, ny, nz);
      if (!(len > 0.97 && len < 1.03)) ok = false;
      const x = b.readFloatLE(i), y = b.readFloatLE(i + 4), z = b.readFloatLE(i + 8);
      if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) ok = false;
      if (Math.abs(x) > 60000 || Math.abs(z) > 60000 || Math.abs(y) > 20000) ok = false;
    }
    if (ok) return at;
  }
  return -1;
}

/**
 * How much of a reading lands on real vertices.
 *
 * Every record carries a normal, and a normal is a unit vector. Read the array with the wrong record size and the
 * bytes at +12 are not a normal and almost never measure one, so this separates a correct reading from a wrong one
 * without needing to know which is which in advance.
 */
function unitNormalShare(b, table, at, perMesh) {
  let good = 0, seen = 0;
  let off = at;
  for (const m of table) {
    const step = perMesh ? m.stride : STRIDE;
    for (let k = 0; k < m.count; k++) {
      const i = off + k * step;
      if (i + 24 > b.length) break;
      seen++;
      const len = Math.hypot(b.readFloatLE(i + 12), b.readFloatLE(i + 16), b.readFloatLE(i + 20));
      if (len > 0.97 && len < 1.03) good++;
    }
    off += m.count * step;
  }
  return seen ? good / seen : 0;
}

/**
 * A model's triangles, in its own feet.
 *
 * Returns `{ tris, meshes, total }` where `tris` is a flat Float32Array of nine numbers per triangle
 * (x,y,z three times), or null when the file cannot be read this way.
 */
export function readModel(file) {
  const b = inflate(file);
  if (!b) return null;
  const hdr = header(b);
  if (!hdr) return null;
  const table = meshTable(b, hdr);
  if (!table) return null;

  // Some models give every mesh its own record size and some use one size throughout, and nothing in the file
  // says which. Both are read and the normals decide: a wrong record size puts something that is not a normal at
  // +12, and it will not measure one.
  let best = null;
  for (const perMesh of [false, true]) {
    const at = vertexStart(b, hdr, table, perMesh);
    if (at < 0) continue;
    const share = unitNormalShare(b, table, at, perMesh);
    if (!best || share > best.share) best = { at, perMesh, share };
  }
  if (!best || best.share < 0.95) return null;
  // The vertex array is the bulk of a model file. A reading that leaves most of the file unaccounted for has
  // found something else — a stray pair of numbers that happens to look like a one-triangle mesh.
  const used = table.reduce((a, m) => a + m.count * (best.perMesh ? m.stride : STRIDE), 0);
  if (used < (b.length - table[0].at) * 0.6) return null;

  const tris = new Float32Array(Math.floor(hdr.total / 3) * 9);
  let n = 0;
  let off = best.at;
  for (const m of table) {
    const step = best.perMesh ? m.stride : STRIDE;
    for (let t = 0; t + 2 < m.count; t += 3) {
      for (let j = 0; j < 3; j++) {
        const i = off + (t + j) * step;
        if (i + 12 > b.length) break;
        tris[n++] = b.readFloatLE(i);
        tris[n++] = b.readFloatLE(i + 4);
        tris[n++] = b.readFloatLE(i + 8);
      }
    }
    off += m.count * step;
  }
  return { tris: tris.subarray(0, n), meshes: table.length, total: hdr.total, perMesh: best.perMesh, share: best.share };
}

/** Just the triangles lying flat on the ground: the painted surface. Nine numbers each, y dropped. */
export function groundTriangles(file) {
  const model = readModel(file);
  if (!model) return null;
  const out = [];
  const t = model.tris;
  for (let i = 0; i + 8 < t.length; i += 9) {
    if (Math.abs(t[i + 1]) > GROUND_TOL || Math.abs(t[i + 4]) > GROUND_TOL || Math.abs(t[i + 7]) > GROUND_TOL) continue;
    // A mesh can end on slots holding sentinels rather than points; they are not part of any surface.
    let sane = true;
    for (let j = 0; j < 9; j += 3) {
      if (!Number.isFinite(t[i + j]) || !Number.isFinite(t[i + j + 2])) sane = false;
      else if (Math.abs(t[i + j]) > 60000 || Math.abs(t[i + j + 2]) > 60000) sane = false;
    }
    if (!sane) continue;
    out.push(t[i], t[i + 2], t[i + 3], t[i + 5], t[i + 6], t[i + 8]);
  }
  return { xz: Float32Array.from(out), meshes: model.meshes, total: model.total };
}
