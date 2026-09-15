// NewTerrain/HeightMaps/HeightMap.raw: N × N int16 heights in feet, row 0 = north.
// Water is not "height <= 0": land below sea level (the Jordan valley, the Arava, around the Dead Sea) has real,
// varying heights. BMS stores every water body as a perfectly flat surface at one level (the sea at -70 ft in Israel,
// the Dead Sea at -1457, the Sea of Galilee at -771), so a sample is water when it is <= 0 and exactly equal to its
// neighbours two samples away in all eight directions.
import fs from 'node:fs';
import path from 'node:path';
import { findFileCI } from './util.mjs';

export function heightmapFile(terrainDir) {
  const dir = findFileCI(findFileCI(terrainDir, 'NewTerrain'), 'HeightMaps');
  return path.join(dir, fs.readdirSync(dir).find((f) => /^heightmap\.raw$/i.test(f)));
}

const D = 2; // neighbour distance in samples (~60 m); random equality of nine varying land samples is practically impossible

/**
 * Heights averaged down to size × size (feet) and a water mask (1 where at least half of the block is water).
 * Blocks on a shoreline average land and water heights; use [water] rather than the height to tell them apart.
 */
export function readHeightmap(terrainDir, size) {
  const file = heightmapFile(terrainDir);
  const N = Math.round(Math.sqrt(fs.statSync(file).size / 2));
  const k = N / size;
  const H = new Float32Array(size * size);
  const water = new Uint8Array(size * size);
  const rows = k + 2 * D;
  const buf = new Int16Array(rows * N);
  const bytes = new Uint8Array(buf.buffer);
  const fd = fs.openSync(file, 'r');
  for (let r = 0; r < size; r++) {
    // rows r*k - D … r*k + k - 1 + D, clamped at the edges (missing rows are copies of the nearest one)
    const first = r * k - D;
    for (let j = 0; j < rows; j++) {
      const src = Math.min(N - 1, Math.max(0, first + j));
      fs.readSync(fd, bytes, j * N * 2, N * 2, src * N * 2);
    }
    for (let c = 0; c < size; c++) {
      let sum = 0, wet = 0;
      for (let dy = 0; dy < k; dy++) {
        const row = (D + dy) * N;
        for (let dx = 0; dx < k; dx++) {
          const x = c * k + dx, v = buf[row + x];
          sum += v;
          if (v > 0) continue;
          const xl = Math.max(0, x - D), xr = Math.min(N - 1, x + D);
          const up = row - D * N, dn = row + D * N;
          if (buf[row + xl] === v && buf[row + xr] === v && buf[up + x] === v && buf[dn + x] === v
            && buf[up + xl] === v && buf[up + xr] === v && buf[dn + xl] === v && buf[dn + xr] === v) wet++;
        }
      }
      H[r * size + c] = sum / (k * k);
      if (wet * 2 >= k * k) water[r * size + c] = 1;
    }
  }
  fs.closeSync(fd);
  return { H, water };
}
