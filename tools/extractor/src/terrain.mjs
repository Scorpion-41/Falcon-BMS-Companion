// Theater size (feet) from the sim's Terrain/Theater.map. The maps themselves are rendered by maps.mjs.
import fs from 'node:fs';
import path from 'node:path';
import { findFileCI } from './util.mjs';

export function terrainInfo(th) {
  const tdir = findFileCI(th.terrainDir, 'Terrain');
  if (!tdir) return null;
  const map = findFileCI(tdir, 'Theater.map');
  // The legacy TERRAIN*.BIL height grid is optional: Korea's Terrain folder no longer carries one (the 4.38.1 and
  // 4.38.2 installs hold only Theater.map/.mea/.l2/.o2), and the size comes from Theater.map. Without it, the
  // Theater.map file keys the terrain (main.mjs groups theaters flying the same terrain by `bil`).
  const bil = fs.readdirSync(tdir).find((f) => /^TERRAIN.*\.BIL$/i.test(f));
  if (!map) return null;
  const b = fs.readFileSync(map);
  const fpp = b.readFloatLE(0), w = b.readUInt32LE(4), h = b.readUInt32LE(8);
  const hdrFile = bil ? path.join(tdir, bil.replace(/BIL$/i, 'HDR')) : null;
  const n = hdrFile && fs.existsSync(hdrFile) ? +(fs.readFileSync(hdrFile, 'latin1').match(/NCOLS\s+(\d+)/)?.[1] ?? 0) || null : null;
  const l2 = fs.readdirSync(tdir).find((f) => /^THEATER.*.L2$/i.test(f));
  const o2 = fs.readdirSync(tdir).find((f) => /^THEATER.*.O2$/i.test(f));
  return { dir: tdir, bil: bil ? path.join(tdir, bil) : map, legacyBil: !!bil, l2: l2 && path.join(tdir, l2), o2: o2 && path.join(tdir, o2), n, sizeFt: Math.round(w * 32 * fpp), sizeFtY: Math.round(h * 32 * fpp) };
}

