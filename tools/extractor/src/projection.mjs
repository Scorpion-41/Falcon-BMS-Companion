// Geographic ↔ theater coordinates for BMS "new terrain" theaters.
// Each theater's NewTerrain/Theater.txt holds a PROJ string (+proj=tmerc +lon_0 +k +x_0 +y_0, metres, WGS84).
// Theater feet: x = north, y = east (the campaign grid), 3.27998 ft per metre (1024 km = 3,358,700 ft).
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
  return {
    dir: nt, name: kv['Theater name'], sizeKm: parseFloat(kv['Theater size in KM']), mapPx: parseInt(kv['Map size in pixels'], 10),
    lon0: p('lon_0'), k0: p('k') ?? 0.9996, x0: p('x_0') ?? 0, y0: p('y_0') ?? 0,
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
