// What Weapon Delivery Planner reads out of a theater's terrain to print latitude and longitude (Falcas's program;
// the app's port of it is app/.../data/wdp/WdpCoords.kt, and docs/WDP-PORT.md says how it is checked).
//
// WDP sets its projection up in fclsMain.InitNewTerrain + InitTransverseMercator from two files in the folder its
// theater definition names:
//   - NewTerrain/Heightmaps/HeightMap.raw, of which it reads only the LENGTH (samples a side = sqrt(bytes / 2)):
//     so its length is recorded here and the 2 GB file itself is never shipped;
//   - NewTerrain/Theater.txt, of which it reads three keys, "theater size in km", "center latitude" and "center
//     longitude" (the projection string's offsets are read too, but then replaced by the forward projection of the
//     centre, so they never matter).
// The arithmetic that turns these into the projection is WDP's, and lives in the port, not here: this only
// records what the files say, exactly as WDP's own parser would take it.
//
//   node src/wdpterrain.mjs      (adds the figures to app/src/main/assets/data/index.json in place)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadTheaters } from './theaters.mjs';
import { DATA, findFileCI, resolveCI, writeJson } from './util.mjs';

/**
 * The folder WDP reads a theater's terrain from: the definition's `terraindir`, or — when it names none — WDP's
 * own default, the old terrain's folder `Terrdata\korea\terrain\`, which has no NewTerrain in it (Korea TvT). BMS
 * itself falls back to `Terrdata\korea` there; WDP does not, and a port keeps WDP's answer.
 */
export function wdpTerrainDir(th) {
  return th.tdfTerrainDir ? th.terrainDir : resolveCI(DATA, 'Terrdata/korea/terrain');
}

// VB's IsNumeric, for the plain decimal numbers Theater.txt holds. Anything fancier ("1,024", "&H400") is left out
// with a warning rather than guessed at: WDP would read some of those, and a wrong guess is worse than a gap.
const NUMERIC = /^\s*[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?\s*$/;

/**
 * The figures, or null when WDP finds neither file. `heightmapBytes` is null when there is no heightmap (WDP then
 * stops before reading Theater.txt at all), `theaterTxt` false when there is no Theater.txt (WDP says so and
 * leaves the projection's centre at zero); a key Theater.txt does not have is null (WDP leaves it at zero).
 */
export function wdpTerrain(th) {
  const dir = wdpTerrainDir(th);
  const nt = findFileCI(dir, 'NewTerrain');
  const hmDir = nt && findFileCI(nt, 'Heightmaps');
  const hm = hmDir && findFileCI(hmDir, 'HeightMap.raw');
  const txt = nt && findFileCI(nt, 'Theater.txt');
  if (!hm && !txt) return null;
  const out = { heightmapBytes: hm ? fs.statSync(hm).size : null, theaterTxt: !!txt, sizeKm: null, centerLat: null, centerLon: null };
  if (!txt) return out;
  // InitTransverseMercator: each line split at its FIRST "=", the key compared lower-cased and untrimmed (so
  // "Center latitude = 38" would not match), the value everything after the "=", the last matching line winning
  for (const line of fs.readFileSync(txt, 'latin1').split(/\r\n|\r|\n/)) {
    const i = line.indexOf('=');
    if (i <= 0) continue;
    const key = line.slice(0, i).toLowerCase();
    const value = line.slice(i + 1);
    const field = { 'theater size in km': 'sizeKm', 'center latitude': 'centerLat', 'center longitude': 'centerLon' }[key];
    if (!field) continue;
    if (!NUMERIC.test(value)) { console.warn(`wdpterrain: ${txt}: "${line}" is not a plain number; left out`); continue; }
    out[field] = Number(value.trim());
  }
  return out;
}

// run on its own: add the figures to the index the app already has, touching nothing else in it
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const index = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../app/src/main/assets/data/index.json');
  const data = JSON.parse(fs.readFileSync(index, 'utf8'));
  const byId = new Map(loadTheaters().map((t) => [t.id, t]));
  for (const t of data.theaters) {
    const th = byId.get(t.id);
    if (!th) { console.warn('wdpterrain: no theater definition for', t.id); continue; }
    const w = wdpTerrain(th);
    if (w) t.wdpTerrain = w; else delete t.wdpTerrain;
    console.log(t.id.padEnd(22), w ? JSON.stringify(w) : '(no NewTerrain where WDP looks: ' + wdpTerrainDir(th) + ')');
  }
  writeJson(index, data);
}
