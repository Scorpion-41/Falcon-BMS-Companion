// Map landmark layers per theater map: country borders, province/governorate borders, country and region labels.
// Source: Natural Earth 1:10m (public domain, https://www.naturalearthdata.com), projected with each theater's
// NewTerrain/Theater.txt projection so the lines sit exactly on the campaign grid (and every map style).
// Output: app/src/main/assets/data/geo/<mapId>.json
//   { borders:[{d:0|1, lo:[x,y,…], hi:[x,y,…]}], provinces:[{lo, hi}], countries:[{n, x, y, r}], regions:[{n, x, y, r}] }
//   coordinates are theater feet (x = north, y = east), rounded; lo = simplified for zoomed-out views; d = disputed/line of control;
//   r = size of the label's area in nautical miles (the app sizes and hides labels with it).
// Usage: BMS_ROOT="G:/Falcon BMS 4.38" node src/geo.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readTheaterTxt, makeProjector } from './projection.mjs';
import { loadGeoJson, geometryLines } from './geodata.mjs';
import { MAP_SOURCES } from './maps.mjs';
import { readHeightmap } from './heightmap.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.resolve(HERE, '../../../app/src/main/assets/data/geo');
const BMS = process.env.BMS_ROOT || 'G:/Falcon BMS 4.38';
const NM = 6076.12;

/** Douglas–Peucker on a flat [x, y, x, y, …] array. */
function simplify(pts, tol) {
  const n = pts.length / 2;
  if (n <= 2) return pts.map(Math.round);
  const keep = new Uint8Array(n); keep[0] = keep[n - 1] = 1;
  const stack = [[0, n - 1]], t2 = tol * tol;
  while (stack.length) {
    const [a, b] = stack.pop();
    const ax = pts[2 * a], ay = pts[2 * a + 1], bx = pts[2 * b], by = pts[2 * b + 1];
    const dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
    let best = -1, bestD = t2;
    for (let i = a + 1; i < b; i++) {
      const px = pts[2 * i] - ax, py = pts[2 * i + 1] - ay;
      let d;
      if (len2 === 0) d = px * px + py * py;
      else { const t = Math.max(0, Math.min(1, (px * dx + py * dy) / len2)); const ex = px - t * dx, ey = py - t * dy; d = ex * ex + ey * ey; }
      if (d > bestD) { bestD = d; best = i; }
    }
    if (best >= 0) { keep[best] = 1; stack.push([a, best], [best, b]); }
  }
  const out = [];
  for (let i = 0; i < n; i++) if (keep[i]) out.push(Math.round(pts[2 * i]), Math.round(pts[2 * i + 1]));
  return out;
}

/** Projects a line and splits it into the parts inside the theater square (plus a margin; the app clips at the view). */
function clipLine(coords, proj, size) {
  const m = size * 0.02, parts = [];
  let cur = [], prevOut = null;
  for (const [lon, lat] of coords) {
    const p = proj(lat, lon);
    const inside = p.x >= -m && p.x <= size + m && p.y >= -m && p.y <= size + m;
    if (inside) {
      if (!cur.length && prevOut) cur.push(prevOut.x, prevOut.y);
      cur.push(p.x, p.y);
    } else {
      if (cur.length) { cur.push(p.x, p.y); if (cur.length >= 4) parts.push(cur); cur = []; }
      prevOut = p;
    }
  }
  if (cur.length >= 4) parts.push(cur);
  return parts;
}

/** A coarse land mask of the theater (water as BMS marks it, see heightmap.mjs), grown by one cell. */
function landMask(terrainDir, n = 2048) {
  const { water } = readHeightmap(terrainDir, n);
  const raw = water.map((w) => 1 - w);
  const mask = new Uint8Array(n * n);
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) {
    if (!raw[r * n + c]) continue;
    for (let dr = -1; dr <= 1; dr++) for (let dc = -1; dc <= 1; dc++) { const rr = r + dr, cc = c + dc; if (rr >= 0 && cc >= 0 && rr < n && cc < n) mask[rr * n + cc] = 1; }
  }
  return { n, mask };
}

/** Splits a projected line into its parts over land (Natural Earth province lines also run across the sea between islands). */
function landParts(pts, land, size) {
  const { n, mask } = land, cell = size / n;
  const onLand = (x, y) => {
    const r = Math.floor((size - x) / cell), c = Math.floor(y / cell);
    return r < 0 || c < 0 || r >= n || c >= n ? true : mask[r * n + c] === 1; // outside the terrain: keep
  };
  const parts = [];
  let cur = [];
  for (let i = 0; i + 3 < pts.length; i += 2) {
    const ax = pts[i], ay = pts[i + 1], bx = pts[i + 2], by = pts[i + 3];
    const steps = Math.max(1, Math.ceil(Math.hypot(bx - ax, by - ay) / (cell / 2)));
    let land = 0;
    for (let s = 0; s <= steps; s++) if (onLand(ax + (bx - ax) * s / steps, ay + (by - ay) * s / steps)) land++;
    if (land / (steps + 1) >= 0.5) {
      if (!cur.length) cur.push(ax, ay);
      cur.push(bx, by);
    } else if (cur.length) { parts.push(cur); cur = []; }
  }
  if (cur.length >= 4) parts.push(cur);
  return parts;
}

/**
 * A city is often several campaign objectives with the same name (Seoul, Pyongyang…), which would put the same label on
 * the map two or three times. Objectives of one name closer than 25 nm become one place: the most important type, at the
 * middle of the group. Same names further apart are left alone; they are different towns (Greece has several Pyrgos).
 */
function mergeSameNames(places, nm = 25 * NM) {
  const rank = { city: 0, town: 1 };
  const level = (p) => rank[p.t] ?? 2;
  const out = [];
  for (const p of places) {
    const near = out.find((q) => q.n === p.n && Math.hypot(q.x - p.x, q.y - p.y) < nm);
    if (!near) { out.push({ ...p, _n: 1 }); continue; }
    near.x = Math.round((near.x * near._n + p.x) / (near._n + 1));
    near.y = Math.round((near.y * near._n + p.y) / (near._n + 1));
    near._n++;
    if (level(p) < level(near)) near.t = p.t;
  }
  return out.map(({ _n, ...p }) => p);
}

/** Label position for a polygon inside the theater: the point farthest from its edges (on a grid), and the area's size. */
function labelPoint(rings, size, n = 256) {
  const cell = size / n;
  let minR = n, maxR = -1, minC = n, maxC = -1;
  const proj = rings.map((r) => r.filter((p) => Number.isFinite(p.x)));
  for (const r of proj) for (const p of r) {
    const row = Math.floor((size - p.x) / cell), col = Math.floor(p.y / cell);
    minR = Math.min(minR, row); maxR = Math.max(maxR, row); minC = Math.min(minC, col); maxC = Math.max(maxC, col);
  }
  minR = Math.max(0, minR); minC = Math.max(0, minC); maxR = Math.min(n - 1, maxR); maxC = Math.min(n - 1, maxC);
  if (maxR < minR || maxC < minC) return null;
  const H = maxR - minR + 1, W = maxC - minC + 1;
  const inside = new Uint8Array(H * W);
  for (let r = 0; r < H; r++) {
    const x = size - (minR + r + 0.5) * cell;
    const xs = [];
    for (const ring of proj) for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      const a = ring[i], b = ring[j];
      if ((a.x > x) !== (b.x > x)) xs.push(a.y + (x - a.x) / (b.x - a.x) * (b.y - a.y));
    }
    xs.sort((p, q) => p - q);
    for (let k = 0; k + 1 < xs.length; k += 2) {
      const c0 = Math.max(minC, Math.ceil(xs[k] / cell - 0.5)), c1 = Math.min(maxC, Math.floor(xs[k + 1] / cell - 0.5));
      for (let c = c0; c <= c1; c++) inside[r * W + (c - minC)] ^= 1;
    }
  }
  // chamfer distance to the nearest outside cell (the theater edge counts as outside too, so labels stay on screen)
  const D = new Float32Array(H * W);
  const edge = (r, c) => { const R = minR + r, C = minC + c; return Math.min(R + 1, C + 1, n - R, n - C); };
  let count = 0;
  for (let i = 0; i < H * W; i++) { if (inside[i]) { D[i] = 1e9; count++; } }
  if (count < 3) return null;
  const at = (r, c) => (r < 0 || c < 0 || r >= H || c >= W) ? 0 : D[r * W + c];
  for (let r = 0; r < H; r++) for (let c = 0; c < W; c++) { const i = r * W + c; if (inside[i]) D[i] = Math.min(D[i], at(r - 1, c) + 1, at(r, c - 1) + 1, at(r - 1, c - 1) + 1.414, at(r - 1, c + 1) + 1.414, edge(r, c)); }
  let best = 0, bi = 0;
  for (let r = H - 1; r >= 0; r--) for (let c = W - 1; c >= 0; c--) {
    const i = r * W + c; if (!inside[i]) continue;
    D[i] = Math.min(D[i], at(r + 1, c) + 1, at(r, c + 1) + 1, at(r + 1, c + 1) + 1.414, at(r + 1, c - 1) + 1.414);
    if (D[i] > best) { best = D[i]; bi = i; }
  }
  const r = Math.floor(bi / W), c = bi % W;
  return { x: Math.round(size - (minR + r + 0.5) * cell), y: Math.round((minC + c + 0.5) * cell), r: Math.round(Math.sqrt(count) * cell / NM) };
}

function build(src) {
  const t = readTheaterTxt(path.join(BMS, src.dir));
  const size = t.sizeKm * 1000 * 3.27998;
  const proj = makeProjector(t);
  const near = (coords) => coords.some(([lon, lat]) => { const p = proj(lat, lon); return p.x > -size * 0.2 && p.x < size * 1.2 && p.y > -size * 0.2 && p.y < size * 1.2; });
  const scale = size / 3358700; // Falklands is twice the size: coarser simplification

  const borders = [];
  for (const f of loadGeoJson('ne_10m_admin_0_boundary_lines_land').features) {
    const disputed = /disputed|indefinite|line of control|claim/i.test(f.properties.FEATURECLA || '');
    for (const line of geometryLines(f.geometry)) {
      if (!near(line)) continue;
      for (const part of clipLine(line, proj, size)) borders.push({ d: disputed ? 1 : 0, lo: simplify(part, 2400 * scale), hi: simplify(part, 250 * scale) });
    }
  }
  const land = landMask(path.join(BMS, src.dir));
  const provinces = [];
  for (const f of loadGeoJson('ne_10m_admin_1_states_provinces_lines').features) {
    for (const line of geometryLines(f.geometry)) {
      if (!near(line)) continue;
      for (const part of clipLine(line, proj, size).flatMap((p) => landParts(p, land, size))) provinces.push({ lo: simplify(part, 3000 * scale), hi: simplify(part, 350 * scale) });
    }
  }
  const polyLabels = (file, nameOf, minNm, grid) => {
    const out = [];
    for (const f of loadGeoJson(file).features) {
      const lines = geometryLines(f.geometry);
      if (!lines.some(near)) continue;
      const rings = lines.map((ring) => ring.map(([lon, lat]) => proj(lat, lon)));
      const lp = labelPoint(rings, size, grid);
      const name = nameOf(f.properties);
      if (lp && name && lp.r >= minNm) out.push({ n: name, ...lp });
    }
    return out.sort((a, b) => b.r - a.r);
  };
  const countries = polyLabels('ne_10m_admin_0_countries', (p) => p.NAME || p.NAME_EN, 25 * scale, 256);
  const regions = polyLabels('ne_10m_admin_1_states_provinces', (p) => p.name_en || p.name, 12 * scale, 512);
  // cities, towns and villages of the main theater on this map (campaign objectives)
  const idx = JSON.parse(fs.readFileSync(path.resolve(HERE, '../../../app/src/main/assets/data/index.json'), 'utf8'));
  const main = idx.theaters.find((th) => th.primary && th.mapId === src.id);
  const places = mergeSameNames(main ? JSON.parse(fs.readFileSync(path.resolve(HERE, '../../../app/src/main/assets/data/airports', main.airportSet + '.json'), 'utf8')).places : []);
  return { v: 1, source: 'Natural Earth 1:10m (public domain)', borders, provinces, countries, regions, places };
}

fs.mkdirSync(OUT, { recursive: true });
for (const src of MAP_SOURCES) {
  const t0 = Date.now();
  const geo = build(src);
  const file = path.join(OUT, src.id + '.json');
  fs.writeFileSync(file, JSON.stringify(geo));
  const pts = (k, lod) => geo[k].reduce((a, l) => a + l[lod].length / 2, 0);
  console.log(`${src.id}: ${geo.borders.length} border lines (${pts('borders', 'hi')} pts), ${geo.provinces.length} province lines (${pts('provinces', 'hi')} pts), ${geo.countries.length} countries, ${geo.regions.length} regions, ${(fs.statSync(file).size / 1024).toFixed(0)} KB (${Date.now() - t0} ms)`);
}
