// Shared helpers for the map layers: Natural Earth data (public domain) projected into a theater grid.
import fs from 'node:fs';
import path from 'node:path';
import { ROOT_CACHE } from './projection.mjs';

const cache = new Map();
export function loadGeoJson(name) {
  if (!cache.has(name)) {
    const f = path.join(ROOT_CACHE, 'ne', name + '.geojson');
    if (!fs.existsSync(f)) throw new Error(`Missing ${f}: download it from https://github.com/nvkelso/natural-earth-vector/tree/master/geojson`);
    cache.set(name, JSON.parse(fs.readFileSync(f, 'utf8')));
  }
  return cache.get(name);
}

/** Lines (arrays of [lon, lat]) of a LineString/MultiLineString/Polygon/MultiPolygon geometry. */
export function geometryLines(g) {
  if (!g) return [];
  switch (g.type) {
    case 'LineString': return [g.coordinates];
    case 'MultiLineString': return g.coordinates;
    case 'Polygon': return g.coordinates;
    case 'MultiPolygon': return g.coordinates.flat();
    default: return [];
  }
}

/** Bounding box in lon/lat of a theater, from its projector (sampled along the edges, with a margin). */
export function lonLatBounds(unproject, sizeFt) {
  let minLon = 999, maxLon = -999, minLat = 999, maxLat = -999;
  for (let i = 0; i <= 20; i++) for (const [x, y] of [[i / 20, 0], [i / 20, 1], [0, i / 20], [1, i / 20]]) {
    const { lat, lon } = unproject(x * sizeFt, y * sizeFt);
    minLon = Math.min(minLon, lon); maxLon = Math.max(maxLon, lon); minLat = Math.min(minLat, lat); maxLat = Math.max(maxLat, lat);
  }
  return { minLon: minLon - 1, maxLon: maxLon + 1, minLat: minLat - 1, maxLat: maxLat + 1 };
}

/** Rasterizes polygons (theater feet rings) into an n×n mask, row 0 = north, col 0 = west. */
export function rasterizeRings(rings, sizeFt, n) {
  const mask = new Uint8Array(n * n);
  const cell = sizeFt / n;
  for (let row = 0; row < n; row++) {
    const x = sizeFt - (row + 0.5) * cell; // north coordinate of this row
    const xs = [];
    for (const r of rings) {
      for (let i = 0, j = r.length - 1; i < r.length; j = i++) {
        const a = r[i], b = r[j];
        if ((a.x > x) !== (b.x > x)) xs.push(a.y + (x - a.x) / (b.x - a.x) * (b.y - a.y));
      }
    }
    xs.sort((p, q) => p - q);
    for (let k = 0; k + 1 < xs.length; k += 2) {
      const c0 = Math.max(0, Math.ceil(xs[k] / cell - 0.5)), c1 = Math.min(n - 1, Math.floor(xs[k + 1] / cell - 0.5));
      for (let c = c0; c <= c1; c++) mask[row * n + c] ^= 1;
    }
  }
  return mask;
}
