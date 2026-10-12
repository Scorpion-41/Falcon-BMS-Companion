// The ground an airfield stands on, from Falcon BMS's own height map.
//
// An airport's elevation is its ATC file's `#INFO` line (`TerrData/ATC/<field>.dat`, airports.mjs) where it has one.
// Most fields have none — 73 of Korea's 94, every field of the Balkans, EF2000 and Israel — and Hellas's files give 0
// for fields standing up to 1,180 ft up (Kasteli), so every page that uses the figure (the Planner's take-off factor,
// refusal speed and climb, the DataCard's ELV, a steerpoint put on a field) had them at sea level: Samjiyon, 4,450 ft
// up on the Korean plateau, planned as a sea-level take-off.
//
// BMS puts the jet on its terrain, whose heights are NewTerrain/HeightMaps/HeightMap.raw: a square of int16 feet,
// row 0 the theater's north edge, read here bilinear between the four samples round the point in BMS's own terrain
// frame (heightmap.mjs `sampleOf`: metres of 3.28084 ft from the centre of the south-west sample, where the campaign
// grid counts 3.27998 — the frame the PC's map tiles and TerrainHeights.kt use; up to 1.4.0 this read WDP's
// `ReadNewTerrainElvLoc` cell, up to nine samples off at the north-east). The
// ground under a field is the median of the cells under both ends and the middle of every runway rectangle BMS
// draws (the point header of type 8), or under the objective itself where the field has no rectangle. Where the
// ATC file has a figure, the two agree: Korea's 21 fields 18 to the foot (Osan 42, Daegu 120, Gunsan 22); Hellas's
// four non-zero ones within 10 ft (Ohrid 2296, Skopje 782/781); and the fields Hellas's files put at 0 come out at
// their published elevations (Kasteli 1180, Tanagra 486, Eleftherios Venizelos 308, Larissa 240, Andravida 55).
//
// BMS then levels the terrain under most airbases (tld.mjs: `TerrainRelatedData/TLD_<CampId>.TPB`), and inside a
// zone's 100 % polygon the ground is the zone's `terrain_level`. So a field whose runways lie in a levelled zone
// takes that level first (`levelledUnder`), ahead of the ATC figure and the height map: they agree where the ATC
// file has a figure (Osan 42, Daegu 120, Gunsan 22; Pohang's says 16 where the level and the map say 75 and 74), and
// elsewhere the height map's cells under a runway can be off: tens of feet on a slope, and some of Hellas's fields
// are carved into it hundreds of feet too low (Graf Ignatievo 190 inside the field, 650-680 round it, level 679).
//
// Read only: the file is opened for reading, two bytes a cell, never whole (it is 2 GB).
import fs from 'node:fs';
import { heightmapFile, sampleOf } from './heightmap.mjs';
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
  // the campaign grid's side (3.27998 ft a metre); the terrain frame itself is heightmap.mjs's
  const km = readTheaterTxt(th.terrainDir)?.sizeKm;
  const side = Number.isFinite(km) && km > 0 ? km * 1000 * FT_PER_M : terrainInfo(th)?.sizeFt;
  if (!Number.isFinite(side) || side <= 0) return null;
  const len = fs.statSync(file).size;
  const cells = Math.round(Math.sqrt(len / 2));
  const fd = fs.openSync(file, 'r');
  const buf = Buffer.alloc(2);
  const sample = (r, c) => {
    const row = Math.min(cells - 1, Math.max(0, r)), col = Math.min(cells - 1, Math.max(0, c));
    return fs.readSync(fd, buf, 0, 2, (row * cells + col) * 2) === 2 ? buf.readInt16LE(0) : null;
  };
  return {
    // bilinear between the four samples round the point, in BMS's terrain frame (heightmap.mjs `sampleOf`) — the
    // height BMS stands its ground units on, as the PC's TerrainHeights.kt reads it
    at(north, east) {
      if (!Number.isFinite(north) || !Number.isFinite(east) || north < 0 || east < 0 || north > side || east > side) return null;
      const s = sampleOf(east / side, north / side, cells);
      const r0 = Math.floor(s.row), c0 = Math.floor(s.col), fy = s.row - r0, fx = s.col - c0;
      const nw = sample(r0, c0), ne = sample(r0, c0 + 1), sw = sample(r0 + 1, c0), se = sample(r0 + 1, c0 + 1);
      if (nw == null || ne == null || sw == null || se == null) return null;
      return (nw * (1 - fx) + ne * fx) * (1 - fy) + (sw * (1 - fx) + se * fx) * fy;
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
  const heights = fieldPoints(north, east, runways)
    .map((p) => sampler.at(p.n, p.e))
    .filter((h) => h != null)
    .sort((a, b) => a - b);
  return heights.length ? Math.round(heights[Math.floor(heights.length / 2)]) : null;
}

/** The points a field's ground is read at: both ends and the middle of every runway, else the objective itself. */
function fieldPoints(north, east, runways) {
  const mid = (a, b) => ({ e: (a.e + b.e) / 2, n: (a.n + b.n) / 2 });
  const points = [];
  for (const c of runways || []) {
    if (c.length < 4) continue;
    const a = mid(c[0], c[1]), b = mid(c[2], c[3]);
    points.push(a, b, mid(a, b));
  }
  return (points.length ? points : [{ e: 0, n: 0 }]).map((p) => ({ n: north + p.n, e: east + p.e }));
}

/**
 * The ground BMS levels a field to (tld.mjs): the median, over the same points as [groundUnder], of the levelled
 * ground, when at least one of them lies inside a zone's 100 % polygon — which, for a field whose runways all lie in
 * one zone, is that zone's `terrain_level`. Null when no point is levelled. `{ ft, zones: the CampIds of the zones
 * holding the points }`.
 */
export function levelledUnder(lev, sampler, north, east, runways) {
  if (!lev) return null;
  const pts = fieldPoints(north, east, runways);
  const zones = new Set();
  for (const p of pts) { const z = lev.core(p.n, p.e); if (z) zones.add(z.id); }
  if (!zones.size) return null;
  const heights = pts.map((p) => lev.at(p.n, p.e, sampler?.at(p.n, p.e) ?? null))
    .filter((h) => h != null).sort((a, b) => a - b);
  return heights.length ? { ft: Math.round(heights[Math.floor(heights.length / 2)]), zones: [...zones] } : null;
}
