// Geographic ↔ theater coordinates for BMS "new terrain" theaters.
// Each theater's NewTerrain/Theater.txt holds a PROJ string (+proj=tmerc +lon_0 +k +x_0 +y_0, metres, WGS84).
// Theater feet: x = north, y = east (the campaign grid), 3.27998 ft per metre (1024 km = 3,358,700 ft).
// This is what the maps, towns and ground charts are laid on. The latitude and longitude BMS itself gives (its ACMI
// recordings, its AIPs) are another grid over the same terrain's size and centre and its heightmap's length — see
// theaterProjection and the app's WdpCoords.coordData (docs/WDP-PORT.md, D26).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { findFileCI } from './util.mjs';

export const FT_PER_M = 3.27998;

/** Reads NewTerrain/Theater.txt next to a theater's terrain folder. */
export function readTheaterTxt(terrainDir) {
  const nt = findFileCI(terrainDir, 'NewTerrain');
  const f = nt && findFileCI(nt, 'Theater.txt');
  if (!f) return null;
  const kv = {};
  for (const line of fs.readFileSync(f, 'latin1').split(/\r?\n/)) {
    const i = line.indexOf('=');
    if (i > 0) kv[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  }
  const proj = kv['Projection string'] || '';
  const p = (k) => { const m = proj.match(new RegExp(`\\+${k}=([-+0-9.eE]+)`)); return m ? parseFloat(m[1]) : null; };
  if (!/\+proj=tmerc/.test(proj)) return null;
  const numKey = (k) => { const v = parseFloat(kv[k]); return Number.isFinite(v) ? v : null; };
  return {
    dir: nt, name: kv['Theater name'], sizeKm: parseFloat(kv['Theater size in KM']), mapPx: parseInt(kv['Map size in pixels'], 10),
    lon0: p('lon_0'), k0: p('k') ?? 0.9996, x0: p('x_0') ?? 0, y0: p('y_0') ?? 0,
    lat0: p('lat_0') ?? 0, ellps: proj.match(/\+ellps=(\S+)/)?.[1] ?? (/\+datum=WGS84/.test(proj) ? 'WGS84' : null),
    centerLat: numKey('Center latitude'), centerLon: numKey('Center longitude'), proj,
  };
}

/**
 * What goes into index.json for a theater: the figures of the terrain BMS itself flies it on (the definition's
 * `terraindir`, or `Terrdata/korea` when it names none, as for Korea TvT); null when that terrain has no
 * `NewTerrain/Theater.txt` or it is not a transverse Mercator.
 *
 * Two things read them. **The latitude and longitude BMS prints** (its ACMI, its AIPs' "BMS coord") are a grid
 * built from three of these figures, the size, the centre and the heightmap's sample count (`heightmapBytes`, the
 * length of `Heightmaps/HeightMap.raw`): metres are feet / 3.28084, the false origin is the centre's forward
 * projection less half the theater, and the north is one heightmap sample (31.25 m) further up. The app's
 * `WdpCoords.coordData` builds that grid; docs/WDP-PORT.md D26 has the evidence. **The drawing**: easting =
 * y ft / ftPerM - x0 and northing = x ft / ftPerM - y0 on the projection string (makeProjector's the other way
 * round, 3.27998 ft/m) is what the maps, towns and ground charts were laid on, and what the app falls back on for a
 * terrain the grid is not established for (the Falklands' 2,048 km). The proj string is kept word for word, so a
 * reader that meets a parameter it does not know can say so rather than print a wrong position.
 */
export function theaterProjection(terrainDir) {
  const t = readTheaterTxt(terrainDir);
  if (!t || t.lon0 == null || !Number.isFinite(t.sizeKm)) return null;
  const hmDir = findFileCI(t.dir, 'Heightmaps');
  const hm = hmDir && findFileCI(hmDir, 'HeightMap.raw');
  return {
    type: 'tmerc', ellps: t.ellps, lat0: t.lat0, lon0: t.lon0, k0: t.k0, x0: t.x0, y0: t.y0, ftPerM: FT_PER_M,
    sizeKm: t.sizeKm, centerLat: t.centerLat, centerLon: t.centerLon, proj: t.proj,
    heightmapBytes: hm ? fs.statSync(hm).size : null,
  };
}

// WGS84 Transverse Mercator (Krüger series, 6th order): accurate to well under a metre across a 2000 km theater.
const A = 6378137, F = 1 / 298.257223563;
const N = F / (2 - F);
const A_RECT = A / (1 + N) * (1 + N * N / 4 + N ** 4 / 64 + N ** 6 / 256);
const ALPHA = [
  N / 2 - 2 * N ** 2 / 3 + 5 * N ** 3 / 16 + 41 * N ** 4 / 180 - 127 * N ** 5 / 288 + 7891 * N ** 6 / 37800,
  13 * N ** 2 / 48 - 3 * N ** 3 / 5 + 557 * N ** 4 / 1440 + 281 * N ** 5 / 630 - 1983433 * N ** 6 / 1935360,
  61 * N ** 3 / 240 - 103 * N ** 4 / 140 + 15061 * N ** 5 / 26880 + 167603 * N ** 6 / 181440,
  49561 * N ** 4 / 161280 - 179 * N ** 5 / 168 + 6601661 * N ** 6 / 7257600,
  34729 * N ** 5 / 80640 - 3418889 * N ** 6 / 1995840,
  212378941 * N ** 6 / 319334400,
];
const E2 = F * (2 - F), E = Math.sqrt(E2);

/** Returns (lat°, lon°) → {x: north ft, y: east ft} in the theater grid. */
export function makeProjector(t) {
  const lon0 = t.lon0 * Math.PI / 180;
  return (lat, lon) => {
    const phi = lat * Math.PI / 180, lam = lon * Math.PI / 180 - lon0;
    const tau = Math.tan(phi);
    const sigma = Math.sinh(E * Math.atanh(E * tau / Math.sqrt(1 + tau * tau)));
    const tauP = tau * Math.sqrt(1 + sigma * sigma) - sigma * Math.sqrt(1 + tau * tau);
    const xiP = Math.atan2(tauP, Math.cos(lam)), etaP = Math.asinh(Math.sin(lam) / Math.sqrt(tauP * tauP + Math.cos(lam) ** 2));
    let xi = xiP, eta = etaP;
    for (let j = 1; j <= 6; j++) {
      xi += ALPHA[j - 1] * Math.sin(2 * j * xiP) * Math.cosh(2 * j * etaP);
      eta += ALPHA[j - 1] * Math.cos(2 * j * xiP) * Math.sinh(2 * j * etaP);
    }
    const easting = t.k0 * A_RECT * eta + t.x0;
    const northing = t.k0 * A_RECT * xi + t.y0;
    return { x: northing * FT_PER_M, y: easting * FT_PER_M };
  };
}

export const ROOT_CACHE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../cache');

// run on its own: refresh each theater's `projection` in the index the app already has (the terrain BMS flies it on),
// touching nothing else in it
//   node src/projection.mjs
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { loadTheaters } = await import('./theaters.mjs');
  const { writeJson } = await import('./util.mjs');
  const index = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../app/src/main/assets/data/index.json');
  const data = JSON.parse(fs.readFileSync(index, 'utf8'));
  const byId = new Map(loadTheaters().map((t) => [t.id, t]));
  for (const t of data.theaters) {
    const th = byId.get(t.id);
    if (!th) { console.warn('projection: no theater definition for', t.id); continue; }
    const p = theaterProjection(th.terrainDir);
    if (p) t.projection = p; else delete t.projection;
    console.log(t.id.padEnd(22), p ? `${p.sizeKm} km, centre ${p.centerLat}, ${p.centerLon}, heightmap ${p.heightmapBytes} bytes` : '(no Theater.txt: ' + th.terrainDir + ')');
  }
  writeJson(index, data);
}
