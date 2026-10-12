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

// ------------------------------------------------------------------ BMS's terrain frame
//
// The map's square (MapProjection, every object's position, the campaign grid) counts 3.27998 ft a metre: a 1,024 km
// theater is 3,358,700 ft. Falcon BMS lays its terrain — HeightMap.raw and the photoreal textures alike, which agree
// with each other — in metres of 3.28084 ft, sample k's centre k samples from the square's south-west corner (and so
// the textures' edge half a sample south-west of it). Drawn as one square, the terrain sat up to 880 ft off BMS's own
// runways at Korea's north-east corner and 51 ft at the south-west (`--satalign`). Every reader of the height map or
// a texture by map position goes through these two functions; the PC's MapTiles.kt (`Terrain.scale`,
// `Terrain.halfSample`) is the same formula, so the bundled levels and the PC's deep tiles join.

/** The share of the terrain's width the map's square covers (3.27998 / 3.28084, whatever the theater's size). */
export const TERRAIN_SCALE = 3.27998 / 3.28084;

/**
 * A place on the map — [east] and [north] as fractions of the square's side from its west and south edges — as a
 * place among the height map's [n] × [n] samples: `col` from the west, `row` from the north, sample k's centre at k.
 */
export function sampleOf(east, north, n) {
  return { col: TERRAIN_SCALE * east * n, row: n - 1 - TERRAIN_SCALE * north * n };
}

/**
 * The same place on a photoreal texture that covers the whole terrain (GlobalColorMap): `u` from its west edge and `v`
 * from its north edge, as fractions of its side; [n] is the height map's samples a side.
 */
export function textureOf(east, north, n) {
  return { u: TERRAIN_SCALE * east + 0.5 / n, v: 1 - TERRAIN_SCALE * north - 0.5 / n };
}

/** The samples a side of a terrain's height map (its file is n × n int16). */
export function heightmapSamples(terrainDir) {
  return Math.round(Math.sqrt(fs.statSync(heightmapFile(terrainDir)).size / 2));
}

/**
 * Heights averaged down to size × size pixels of the map's square (feet) and a water mask (1 where at least half of
 * the block is water); each pixel is the k × k samples (k = n / size) round its centre in BMS's terrain frame
 * ([sampleOf]), as MapTiles.kt's `grid` takes them. Blocks on a shoreline average land and water heights; use
 * [water] rather than the height to tell them apart.
 */
export function readHeightmap(terrainDir, size) {
  const file = heightmapFile(terrainDir);
  const N = Math.round(Math.sqrt(fs.statSync(file).size / 2));
  const k = Math.round(N / size);
  const H = new Float32Array(size * size);
  const water = new Uint8Array(size * size);
  const rows = k + 2 * D;
  const buf = new Int16Array(rows * N);
  const bytes = new Uint8Array(buf.buffer);
  const fd = fs.openSync(file, 'r');
  // the first of the k samples round a pixel's centre s (MapTiles.kt's `first`)
  const firstOf = (s) => Math.floor(s - (k - 1) / 2 + 0.5);
  const colFirst = new Int32Array(size);
  for (let c = 0; c < size; c++) colFirst[c] = firstOf(sampleOf((c + 0.5) / size, 0, N).col);
  const clampX = (x) => (x < 0 ? 0 : x >= N ? N - 1 : x);
  for (let r = 0; r < size; r++) {
    // rows first - D … first + k - 1 + D, clamped at the edges (missing rows are copies of the nearest one)
    const first = firstOf(sampleOf(0, 1 - (r + 0.5) / size, N).row) - D;
    for (let j = 0; j < rows; j++) {
      const src = Math.min(N - 1, Math.max(0, first + j));
      fs.readSync(fd, bytes, j * N * 2, N * 2, src * N * 2);
    }
    for (let c = 0; c < size; c++) {
      let sum = 0, wet = 0;
      for (let dy = 0; dy < k; dy++) {
        const row = (D + dy) * N;
        for (let dx = 0; dx < k; dx++) {
          const x = clampX(colFirst[c] + dx), v = buf[row + x];
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
