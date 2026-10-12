// Falcon BMS's terrain levelling (an airbase's flattened ground).
//
// BMS levels the terrain at run time, on top of NewTerrain/HeightMaps/HeightMap.raw: the sim reads
// NewTerrain/HeightMaps/TerrainLevelingData.bin, and every objective that levels has a readable source of it,
// `<objectdir>/TerrainRelatedData/TLD_<the objective's CampId, five digits>.TPB` — protocol buffers in text form
// (the number is the objective's CampId in CampObjData.XML, not its OCD: Osan AB is CampId 1784 and TLD_01784):
//
//   terrain_level_data {                                one zone; a file may hold several, each with its own level
//     terrain_level: 42                                 the ground, feet, inside the zone's 100 % polygon
//     terrain_level_polygon {
//       terrain_leveling_percentage: 100                100, then rings round it: 66.7/33.3, 75/50/25, 80/60/40/20, 50
//       terrain_level_point { sim_grid_pos_x: <north ft> sim_grid_pos_y: <east ft> } …
//     } …
//   }
//
// Positions are theater feet, x north and y east (the frame of CampObjData's PositionX/Y), absolute. Where the
// ATC file gives a field elevation the 100 % level is that figure (Osan 42, Daegu 120, Gunsan 22). Inside the 100 %
// polygon the ground is the level; in a ring of p % it is taken as p % of the level and the rest the height map
// (BMS's own blend in the rings is not documented; this is the linear mix). Read only.
import fs from 'node:fs';
import path from 'node:path';
import { DATA, findFileCI } from './util.mjs';

const NUM = '(-?[\\d.]+(?:[eE][-+]?\\d+)?)';

/** One TLD file's text → its zones `[{ level, rings: [{ pct, pts: [[north, east]…], box }] }]` (rings innermost first). */
export function parseTld(text) {
  const zones = [];
  if (!text) return zones;
  for (const block of text.split(/terrain_level_data\s*\{/).slice(1)) {
    const level = block.match(new RegExp('terrain_level:\\s*' + NUM));
    if (!level) continue;
    const rings = [];
    for (const poly of block.split(/terrain_level_polygon\s*\{/).slice(1)) {
      const pct = poly.match(new RegExp('terrain_leveling_percentage:\\s*' + NUM));
      const pts = [];
      for (const m of poly.matchAll(new RegExp(`sim_grid_pos_x:\\s*${NUM}\\s*sim_grid_pos_y:\\s*${NUM}`, 'g'))) pts.push([+m[1], +m[2]]);
      if (pts.length >= 3) rings.push({ pct: pct ? +pct[1] : 100, pts, box: boxOf(pts) });
    }
    rings.sort((a, b) => b.pct - a.pct);
    if (rings.length && rings[0].pct >= 99.9) zones.push({ level: +level[1], rings });
  }
  return zones;
}

function boxOf(pts) {
  let n0 = Infinity, n1 = -Infinity, e0 = Infinity, e1 = -Infinity;
  for (const [n, e] of pts) { n0 = Math.min(n0, n); n1 = Math.max(n1, n); e0 = Math.min(e0, e); e1 = Math.max(e1, e); }
  return { n0, n1, e0, e1 };
}

/** Whether ([north], [east]) lies inside the ring (even-odd). */
export function inside(ring, north, east) {
  const b = ring.box;
  if (north < b.n0 || north > b.n1 || east < b.e0 || east > b.e1) return false;
  const pts = ring.pts;
  let c = false;
  for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
    const [xi, yi] = pts[i], [xj, yj] = pts[j];
    if ((yi > east) !== (yj > east) && north < (xj - xi) * (east - yi) / (yj - yi) + xi) c = !c;
  }
  return c;
}

/**
 * The folder of TLD files for theater [th]: its objectdir's, else its 3ddatadir's, else (a theater on the base
 * terrain whose own folder has none, OFM KTO) the base Objects folder's. Null when there is none.
 */
export function tldDir(th) {
  const has = (dir) => {
    const sub = dir && findFileCI(dir, 'TerrainRelatedData');
    return sub && fs.readdirSync(sub).some((f) => /^TLD_.*\.TPB$/i.test(f)) ? sub : null;
  };
  const base = path.join(DATA, 'TerrData/Objects');
  const onBase = path.resolve(th.terrainDir).toLowerCase() === path.resolve(DATA, 'Terrdata/korea').toLowerCase();
  return has(th.objectDir) || has(th.data3dDir) || (onBase ? has(base) : null);
}

const cache = new Map();

/**
 * Every levelling zone of theater [th], each with the CampId its file names (`id`), read once per folder:
 * `{ zones, at(north, east, ground) → leveled ground, core(north, east) → zone whose 100 % polygon holds it }`.
 */
export function levelling(th) {
  const dir = tldDir(th);
  if (!dir) return null;
  if (cache.has(dir)) return cache.get(dir);
  const zones = [];
  for (const f of fs.readdirSync(dir)) {
    const m = f.match(/^TLD_(-?\d+)\.TPB$/i);
    if (!m) continue;
    for (const z of parseTld(fs.readFileSync(path.join(dir, f), 'latin1'))) zones.push({ ...z, id: +m[1], file: f });
  }
  const out = {
    dir, zones,
    core(north, east) { return zones.find((z) => inside(z.rings[0], north, east)) || null; },
    weight(north, east) {
      let best = null;
      for (const z of zones) {
        if (!inside(z.rings[z.rings.length - 1], north, east)) continue;
        const r = z.rings.find((ring) => inside(ring, north, east));
        if (r && (!best || r.pct > best.pct)) best = { zone: z, pct: r.pct };
        if (best?.pct >= 99.9) break;
      }
      return best;
    },
    at(north, east, ground) {
      const w = this.weight(north, east);
      if (!w) return ground;
      if (w.pct >= 99.9 || ground == null) return w.zone.level;
      const p = w.pct / 100;
      return w.zone.level * p + ground * (1 - p);
    },
  };
  cache.set(dir, out);
  return out;
}
