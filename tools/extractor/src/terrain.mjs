// Theater size (feet) from the sim's Terrain/Theater.map. The maps themselves are rendered by maps.mjs.
import fs from 'node:fs';
import path from 'node:path';
import { findFileCI } from './util.mjs';

export function terrainInfo(th) {
  const tdir = findFileCI(th.terrainDir, 'Terrain');
  if (!tdir) return null;
  const map = findFileCI(tdir, 'Theater.map');
  const bil = fs.readdirSync(tdir).find((f) => /^TERRAIN.*\.BIL$/i.test(f));
  if (!map || !bil) return null;
  const b = fs.readFileSync(map);
  const fpp = b.readFloatLE(0), w = b.readUInt32LE(4), h = b.readUInt32LE(8);
  const hdr = fs.readFileSync(path.join(tdir, bil.replace(/BIL$/i, 'HDR')), 'latin1');
  const n = +hdr.match(/NCOLS\s+(\d+)/)[1];
  const l2 = fs.readdirSync(tdir).find((f) => /^THEATER.*.L2$/i.test(f));
  const o2 = fs.readdirSync(tdir).find((f) => /^THEATER.*.O2$/i.test(f));
  return { dir: tdir, bil: path.join(tdir, bil), l2: l2 && path.join(tdir, l2), o2: o2 && path.join(tdir, o2), n, sizeFt: Math.round(w * 32 * fpp), sizeFtY: Math.round(h * 32 * fpp) };
}

