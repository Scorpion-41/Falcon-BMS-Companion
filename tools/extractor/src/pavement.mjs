/**
 * The asphalt, out of BMS's own 3D models.
 *
 * A field's pavement is not in its point data and is not terrain imagery: it is a model, placed once in the
 * field's feature list. Osan's is one object called "RKSO Taxiways" and nearly every field names its own the same
 * way. It carries the aprons, the dispersals and the turnarounds joining the runway ends — none of which any taxi
 * route describes.
 *
 * `bml.mjs` reads those models: the mesh table, then the vertex array, then each mesh as a plain triangle list.
 * What comes back is the real surface, triangle for triangle, so this file has nothing to guess. It places each
 * model by its feature, keeps the triangles lying flat on the ground, and turns the union of them into outlines
 * the app fills — which is why the edges come out straight and the corners square: they are BMS's own.
 *
 * `tools/extractor/bml-format.md` has the format and how each part of it was checked.
 */
import path from 'node:path';
import { findFileCI, DATA, num } from './util.mjs';
import { groundTriangles } from './bml.mjs';
import { outlineRings, simplifyRing, ringArea } from './outline.mjs';

/** How fine the grid the triangles are laid on is. Three feet is under a pixel on any chart of a whole airfield. */
const GRID_FT = 3;
/** How far a simplified outline may move from the traced edge. A stripe's width; it takes the staircase off. */
const SIMPLIFY_FT = 9;
/** Smaller than this and it is a fleck — a kerb, a sliver between two triangles — rather than a piece of ground. */
const MIN_RING_SQFT = 2500;
/** How close a piece of pavement must come to the airfield to be counted as part of it. */
const ANCHOR_FT = 500;

/**
 * How far the triangle areas may exceed the ground they actually cover.
 *
 * A real surface tiles: it covers each patch of ground once, so the sum of its triangle areas is within a few per
 * cent of the area of their union. Osan's taxiway model comes out at 1.00. A model read the wrong way makes
 * triangles out of vertices that are not neighbours, and those overlap wildly — Gunsan's reads at 1.68, and drawn
 * it is a fan of wedges across the field rather than an airfield. Not every model in BMS reads as a plain triangle
 * list, and this is what says so, so a field whose pavement cannot be trusted falls back to its taxi network
 * instead of being given a wrong picture.
 */
const MAX_PILE_UP = 1.25;
/**
 * And a loose cap on how many triangles may span the field.
 *
 * Long thin slivers are real — a runway edge is one quad two thousand feet long — so this is only a backstop:
 * Anshan is 0.8% and correct, Osan 0.1%, while a mis-read that somehow tiled would be far past this.
 */
const MAX_HUGE_SHARE = 0.05;
const HUGE_EDGE_FT = 1500;

/** What the models opened in this run turned out to be. The drivers print it, so a bad build is visible. */
export const stats = { models: 0, read: 0, unreadable: 0, withGround: 0, doesNotTile: 0 };

/**
 * Whether a model's ground triangles tile the ground rather than piling up on it.
 *
 * Measured on a coarse grid, which is enough to tell 1.0 from 1.7.
 */
function tiles(tris) {
  let sum = 0, huge = 0, n = 0;
  let lo = Infinity, hi = -Infinity, lz = Infinity, hz = -Infinity;
  for (let i = 0; i + 5 < tris.length; i += 6) {
    const ax = tris[i], az = tris[i + 1], bx = tris[i + 2], bz = tris[i + 3], cx = tris[i + 4], cz = tris[i + 5];
    n++;
    sum += Math.abs((bx - ax) * (cz - az) - (cx - ax) * (bz - az)) / 2;
    const L = Math.max(Math.hypot(bx - ax, bz - az), Math.hypot(cx - bx, cz - bz), Math.hypot(ax - cx, az - cz));
    if (L > HUGE_EDGE_FT) huge++;
    if (ax < lo) lo = ax; if (ax > hi) hi = ax;
    if (az < lz) lz = az; if (az > hz) hz = az;
  }
  if (!n) return false;
  if (huge / n > MAX_HUGE_SHARE) return false;

  const G = 8;
  const w = Math.min(3000, Math.ceil((hi - lo) / G) + 2);
  const h = Math.min(3000, Math.ceil((hz - lz) / G) + 2);
  if (w < 2 || h < 2) return true;
  const mask = new Uint8Array(w * h);
  for (let i = 0; i + 5 < tris.length; i += 6) {
    const px = [(tris[i] - lo) / G, (tris[i + 2] - lo) / G, (tris[i + 4] - lo) / G];
    const py = [(tris[i + 1] - lz) / G, (tris[i + 3] - lz) / G, (tris[i + 5] - lz) / G];
    const y0 = Math.max(0, Math.floor(Math.min(py[0], py[1], py[2])));
    const y1 = Math.min(h - 1, Math.ceil(Math.max(py[0], py[1], py[2])));
    for (let y = y0; y <= y1; y++) {
      const xs = [];
      for (let e = 0; e < 3; e++) {
        const j = (e + 1) % 3;
        if ((py[e] <= y && py[j] > y) || (py[j] <= y && py[e] > y)) xs.push(px[e] + ((y - py[e]) / (py[j] - py[e])) * (px[j] - px[e]));
      }
      if (xs.length < 2) continue;
      xs.sort((u, v) => u - v);
      for (let x = Math.max(0, Math.round(xs[0])); x <= Math.min(w - 1, Math.round(xs[xs.length - 1])); x++) mask[y * w + x] = 1;
    }
  }
  let cells = 0;
  for (let i = 0; i < mask.length; i++) cells += mask[i];
  const union = cells * G * G;
  return union > 0 && sum / union <= MAX_PILE_UP;
}

const known = new Map();      // graphics index -> ground triangles, or null

/** The ground triangles of one model, in its own feet: six numbers per triangle, east and north. */
export function modelGround(th, graphicsIdx) {
  if (known.has(graphicsIdx)) return known.get(graphicsIdx);
  let out = null;
  const bases = [path.join(th.data3dDir, 'Models'), path.join(th.objectDir, 'Models'), path.join(DATA, 'TerrData/Objects/Models')];
  for (const base of bases) {
    const dir = findFileCI(base, String(graphicsIdx));
    if (!dir) continue;
    const file = findFileCI(dir, 'Model_0.bml') || findFileCI(dir, 'Model_1.bml') || findFileCI(dir, 'Model_2.bml');
    if (!file) continue;
    stats.models++;
    const got = groundTriangles(file);
    if (!got) stats.unreadable++;
    else {
      stats.read++;
      if (!got.xz.length) out = null;
      else if (!tiles(got.xz)) { stats.doesNotTile++; out = null; }
      else { stats.withGround++; out = got.xz; }
    }
    break;
  }
  // A field's own pavement model is used once and never again, so only the small shared ones are worth keeping.
  if (!out || out.length < 20000) known.set(graphicsIdx, out);
  return out;
}

const rad = (d) => (d * Math.PI) / 180;

/**
 * Every paved triangle placed at a field, in the field's own feet.
 *
 * A model is placed by rotating its (x, z) by minus the feature's heading and adding the feature's offset; the
 * field's own heading is then taken out the same way its points are.
 */
export function fieldTriangles(th, db, fed, heading) {
  const oc = Math.cos(rad(heading));
  const os = Math.sin(rad(heading));
  const out = [];
  for (const f of fed) {
    const ct = db.ct[+f.FeatureCtIdx];
    if (!ct) continue;
    const tris = modelGround(th, +ct.GraphicsNormal);
    if (!tris) continue;
    const fe = num(f.OffsetX) ?? 0;
    const fn = num(f.OffsetY) ?? 0;
    const t = rad(-(num(f.Heading) ?? 0));
    const c = Math.cos(t);
    const s = Math.sin(t);
    for (let i = 0; i + 1 < tris.length; i += 2) {
      let e = fe + (tris[i] * c - tris[i + 1] * s);
      let n = fn + (tris[i] * s + tris[i + 1] * c);
      if (heading) {
        const e2 = e * oc + n * os;
        n = -e * os + n * oc;
        e = e2;
      }
      out.push(e, n);
    }
  }
  return out;
}

/**
 * The paved surface of one field as outlines, in feet, clipped to [bounds].
 *
 * [anchors] is a flat list of east,north pairs that are certainly the airfield — its taxi network and the corners
 * of its runways. A piece of pavement that reaches none of them is a road passing by or a neighbour's yard, and is
 * left off the chart.
 *
 * Returns `{ rings }`, each a flat list of east,north pairs, to be filled even-odd so holes read as holes.
 */
export function pavementShapes(tris, bounds, anchors = []) {
  const [e0, e1, n0, n1] = bounds;
  const spanE = e1 - e0, spanN = n1 - n0;
  if (tris.length < 6 || spanE <= 0 || spanN <= 0) return null;
  const w = Math.ceil(spanE / GRID_FT), h = Math.ceil(spanN / GRID_FT);
  if (w * h > 40e6) return null;

  const mask = new Uint8Array(w * h);
  const toX = (e) => (e - e0) / GRID_FT;
  const toY = (n) => (n1 - n) / GRID_FT;
  let painted = 0;
  for (let i = 0; i + 5 < tris.length; i += 6) {
    const ax = toX(tris[i]), ay = toY(tris[i + 1]);
    const bx = toX(tris[i + 2]), by = toY(tris[i + 3]);
    const cx = toX(tris[i + 4]), cy = toY(tris[i + 5]);
    const y0 = Math.max(0, Math.floor(Math.min(ay, by, cy)));
    const y1 = Math.min(h - 1, Math.ceil(Math.max(ay, by, cy)));
    if (y1 < y0) continue;
    for (let y = y0; y <= y1; y++) {
      const xs = [];
      const edge = (px, py, qx, qy) => {
        if ((py <= y && qy > y) || (qy <= y && py > y)) xs.push(px + ((y - py) / (qy - py)) * (qx - px));
      };
      edge(ax, ay, bx, by);
      edge(bx, by, cx, cy);
      edge(cx, cy, ax, ay);
      if (xs.length < 2) continue;
      xs.sort((u, v) => u - v);
      const xa = Math.max(0, Math.round(xs[0]));
      const xb = Math.min(w - 1, Math.round(xs[xs.length - 1]));
      for (let x = xa; x <= xb; x++) mask[y * w + x] = 1;
      painted++;
    }
  }
  if (!painted) return null;

  const rings = [];
  for (const ring of outlineRings(mask, w, h)) {
    if (Math.abs(ringArea(ring)) * GRID_FT * GRID_FT < MIN_RING_SQFT) continue;
    const simple = simplifyRing(ring, SIMPLIFY_FT / GRID_FT);
    if (simple.length < 8) continue;
    const out = new Array(simple.length);
    for (let i = 0; i < simple.length; i += 2) {
      out[i] = Math.round(e0 + simple[i] * GRID_FT);
      out[i + 1] = Math.round(n1 - simple[i + 1] * GRID_FT);
    }
    rings.push(out);
  }
  if (!rings.length) return null;
  return { rings: nearTheAirfield(rings, anchors) };
}

/**
 * Throws away the pieces of pavement that do not reach the airfield.
 *
 * An outline counts if any of its corners comes within [ANCHOR_FT] of a taxi point or a runway corner, or if it
 * lies inside one that does — a hole in an apron belongs to the apron.
 */
function nearTheAirfield(rings, anchors) {
  if (!anchors.length) return rings;
  const lim = ANCHOR_FT * ANCHOR_FT;
  const keep = new Set();
  const boxes = [];
  for (const r of rings) {
    let near = false;
    for (let i = 0; i + 1 < r.length && !near; i += 2) {
      for (let a = 0; a + 1 < anchors.length; a += 2) {
        const de = r[i] - anchors[a], dn = r[i + 1] - anchors[a + 1];
        if (de * de + dn * dn <= lim) { near = true; break; }
      }
    }
    if (near) { keep.add(r); boxes.push(bbox(r)); }
  }
  for (const r of rings) {
    if (keep.has(r)) continue;
    const b = bbox(r);
    if (boxes.some((k) => b[0] >= k[0] && b[1] <= k[1] && b[2] >= k[2] && b[3] <= k[3])) keep.add(r);
  }
  return rings.filter((r) => keep.has(r));
}

function bbox(r) {
  let e0 = Infinity, e1 = -Infinity, n0 = Infinity, n1 = -Infinity;
  for (let i = 0; i + 1 < r.length; i += 2) {
    if (r[i] < e0) e0 = r[i];
    if (r[i] > e1) e1 = r[i];
    if (r[i + 1] < n0) n0 = r[i + 1];
    if (r[i + 1] > n1) n1 = r[i + 1];
  }
  return [e0, e1, n0, n1];
}
