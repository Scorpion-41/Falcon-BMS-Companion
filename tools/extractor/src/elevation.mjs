// The ground an airfield stands on, from Falcon BMS's own height map.
//
// An airport's elevation is its ATC file's `#INFO` line (`TerrData/ATC/<field>.dat`, airports.mjs) where it has one.
// Most fields have none — 73 of Korea's 94, every field of the Balkans, EF2000 and Israel — and Hellas's files give 0
// for fields standing up to 1,180 ft up (Kasteli), so every page that uses the figure (the Planner's take-off factor,
// refusal speed and climb, the DataCard's ELV, a steerpoint put on a field) had them at sea level: Samjiyon, 4,450 ft
// up on the Korean plateau, planned as a sea-level take-off.
//
// BMS puts the jet on its terrain, whose heights are NewTerrain/HeightMaps/HeightMap.raw: a square of int16 feet,
// row 0 the theater's north edge, read here at the same cell the PC's TerrainHeights.kt reads (the row the distance
// from the north edge over the theater's side, the column the east, each times the side in cells, rounded). The
// ground under a field is the median of the cells under both ends and the middle of every runway rectangle BMS
// draws (the point header of type 8), or under the objective itself where the field has no rectangle. Where the
// ATC file has a figure, the two agree: Korea's 21 fields 18 to the foot (Osan 42, Daegu 120, Gunsan 22); Hellas's
// four non-zero ones within 10 ft (Ohrid 2296, Skopje 782/781); and the fields Hellas's files put at 0 come out at
// their published elevations (Kasteli 1180, Tanagra 486, Eleftherios Venizelos 308, Larissa 240, Andravida 55).
//
// Read only: the file is opened for reading, two bytes a cell, never whole (it is 2 GB).
import fs from 'node:fs';
import { heightmapFile } from './heightmap.mjs';
import { terrainInfo } from './terrain.mjs';
import { readTheaterTxt, FT_PER_M } from './projection.mjs';

/**
 * A reader of the theater's height map: `at(north ft, east ft)` answers the int16 feet of that cell, or null off the
 * map; `close()` when done. Null when the theater has no height map or its size is not known.
 */
export function terrainSampler(th) {
  let file;
  try { file = heightmapFile(th.terrainDir); } catch { return null; }
  if (!file || !fs.existsSync(file)) return null;
  const side = terrainInfo(th)?.sizeFt ?? ((readTheaterTxt(th.terrainDir)?.sizeKm ?? NaN) * 1000 * FT_PER_M);
  if (!Number.isFinite(side) || side <= 0) return null;
  const len = fs.statSync(file).size;
  const cells = Math.round(Math.sqrt(len / 2));
  const fd = fs.openSync(file, 'r');
  const buf = Buffer.alloc(2);
  return {
    at(north, east) {
      if (!Number.isFinite(north) || !Number.isFinite(east)) return null;
      const row = Math.round((side - north) / side * cells);
      const col = Math.round(east / side * cells);
      if (row < 0 || col < 0 || row >= cells || col >= cells) return null;
      return fs.readSync(fd, buf, 0, 2, (row * cells + col) * 2) === 2 ? buf.readInt16LE(0) : null;
    },
    close() { fs.closeSync(fd); },
  };
}

/**
 * The ground under a field: the median of the cells under each runway's two ends and middle ([runways]: each the
 * rectangle's four corners, `{ e, n }` feet from the objective at [north]/[east], in BMS's order — the first two one
 * end, the last two the other), or the cell under the objective when there is no rectangle. Null when the map has
 * nothing there.
 */
export function groundUnder(sampler, north, east, runways) {
  if (!sampler) return null;
  const mid = (a, b) => ({ e: (a.e + b.e) / 2, n: (a.n + b.n) / 2 });
  const points = [];
  for (const c of runways || []) {
    if (c.length < 4) continue;
    const a = mid(c[0], c[1]), b = mid(c[2], c[3]);
    points.push(a, b, mid(a, b));
  }
  const heights = (points.length ? points : [{ e: 0, n: 0 }])
    .map((p) => sampler.at(north + p.n, east + p.e))
    .filter((h) => h != null)
    .sort((a, b) => a - b);
  return heights.length ? heights[Math.floor(heights.length / 2)] : null;
}
