/**
 * What a building looks like from above, out of its own BMS 3D model.
 *
 * Used for the control towers on a ground chart — a pilot finds his way round a field by them, so they are drawn as
 * the shape they are rather than as a symbol. Three sources, in order:
 *
 *   model   the model's own triangles (`modelTriangles` in bml.mjs), every one that stands off the ground projected
 *           straight down, and the union of them traced into outlines: the shape a pilot sees from above, exact
 *   box     where the model will not read, the bounding box the model's own `Parent.dat` states
 *           ("Dimensions = radius xmin xmax ymin ymax zmin zmax", x forward, y right, z down): the right size and
 *           heading, a rectangle
 *   kind    neither: the feature kind's representative size (FEATURE_KINDS in airfields.mjs)
 *
 * The model's axes are x right, y up, z forward; Parent.dat's x is the model's z and its y the model's x. A model's
 * reading is only believed when its extent agrees with what Parent.dat states, so a model whose vertices are stored
 * some other way (one lists every vertex inside a unit cube) falls back to the box instead of drawing a speck.
 */
import fs from 'node:fs';
import path from 'node:path';
import { findFileCI, DATA } from './util.mjs';
import { modelTriangles } from './bml.mjs';
import { outlineRings, simplifyRing, ringArea } from './outline.mjs';

/** One foot: a control tower is 30 to 300 ft across, so this is a pixel or less at any chart scale. */
const GRID_FT = 1;
/** A triangle lower than this all round is lying on the ground — an apron decal, a shadow plane — not the building. */
const RAISED_FT = 0.5;
/** Smaller than this and it is a lamp post or an aerial seen end on. */
const MIN_RING_SQFT = 24;

const known = new Map();      // folder -> footprint

function modelDir(th, gfx) {
  for (const base of [path.join(th.data3dDir, 'Models'), path.join(th.objectDir, 'Models'), path.join(DATA, 'TerrData/Objects/Models')]) {
    const dir = findFileCI(base, String(gfx));
    if (dir) return dir;
  }
  return null;
}

/** Parent.dat's bounding box, in the model's own axes: [x0, x1, z0, z1, height], or null. */
function parentBox(dir) {
  const file = findFileCI(dir, 'Parent.dat');
  if (!file) return null;
  const m = fs.readFileSync(file, 'latin1').match(/Dimensions\s*=\s*([-\d.eE+\s]+)/);
  if (!m) return null;
  const v = m[1].trim().split(/\s+/).map(Number);
  if (v.length < 7 || v.some((x) => !Number.isFinite(x))) return null;
  const [, fx0, fx1, ry0, ry1, dz0, dz1] = v;
  // forward is the model's z, right its x, down its -y
  return { x0: ry0, x1: ry1, z0: fx0, z1: fx1, height: Math.round(Math.abs(dz1 - dz0)) };
}

/** The tallest part of a model, which on a control tower is the shaft and the cab: what reaches this share of its height. */
const TOP_SHARE = 0.6;

/** How tall the model stands, in its own feet. */
function topOf(tris) {
  let top = 0;
  for (let i = 1; i < tris.length; i += 3) if (Number.isFinite(tris[i]) && tris[i] < 5000 && tris[i] > top) top = tris[i];
  return top;
}

/**
 * The outlines of the raised triangles, seen from above: rings of x,z pairs in model feet. With [above], only the
 * triangles that reach that height.
 */
function silhouette(tris, above = 0) {
  let x0 = Infinity, x1 = -Infinity, z0 = Infinity, z1 = -Infinity;
  const keep = [];
  for (let i = 0; i + 8 < tris.length; i += 9) {
    const ys = [tris[i + 1], tris[i + 4], tris[i + 7]];
    if (ys.every((y) => Math.abs(y) < RAISED_FT)) continue;
    if (above > 0 && Math.max(...ys) < above) continue;
    let sane = true;
    for (let j = 0; j < 9; j++) if (!Number.isFinite(tris[i + j]) || Math.abs(tris[i + j]) > 5000) sane = false;
    if (!sane) continue;
    keep.push(i);
    for (const j of [0, 3, 6]) {
      const x = tris[i + j], z = tris[i + j + 2];
      if (x < x0) x0 = x; if (x > x1) x1 = x;
      if (z < z0) z0 = z; if (z > z1) z1 = z;
    }
  }
  if (!keep.length) return null;
  const w = Math.ceil((x1 - x0) / GRID_FT) + 3;
  const h = Math.ceil((z1 - z0) / GRID_FT) + 3;
  if (w * h > 4e6) return null;
  const mask = new Uint8Array(w * h);
  // grid row 0 at the far end (largest z), so the traced rings come back the right way round
  const gx = (x) => (x - x0) / GRID_FT + 1;
  const gy = (z) => (z1 - z) / GRID_FT + 1;
  for (const i of keep) {
    const px = [gx(tris[i]), gx(tris[i + 3]), gx(tris[i + 6])];
    const py = [gy(tris[i + 2]), gy(tris[i + 5]), gy(tris[i + 8])];
    const ya = Math.max(0, Math.floor(Math.min(...py)));
    const yb = Math.min(h - 1, Math.ceil(Math.max(...py)));
    for (let y = ya; y <= yb; y++) {
      const yc = y + 0.5;
      const xs = [];
      for (let e = 0; e < 3; e++) {
        const k = (e + 1) % 3;
        if ((py[e] <= yc && py[k] > yc) || (py[k] <= yc && py[e] > yc)) xs.push(px[e] + ((yc - py[e]) / (py[k] - py[e])) * (px[k] - px[e]));
      }
      // a wall seen from above is a line: give an edge-on triangle the cell it crosses
      if (xs.length < 2) continue;
      xs.sort((a, b) => a - b);
      for (let x = Math.max(0, Math.floor(xs[0])); x <= Math.min(w - 1, Math.floor(xs[xs.length - 1])); x++) mask[y * w + x] = 1;
    }
  }
  const rings = [];
  for (const ring of outlineRings(mask, w, h)) {
    if (Math.abs(ringArea(ring)) * GRID_FT * GRID_FT < MIN_RING_SQFT) continue;
    const simple = simplifyRing(ring, 0.8 / GRID_FT);
    if (simple.length < 6) continue;
    const out = [];
    for (let i = 0; i + 1 < simple.length; i += 2) out.push((simple[i] - 1) * GRID_FT + x0, z1 - (simple[i + 1] - 1) * GRID_FT);
    rings.push(out);
  }
  return rings.length ? { rings, x0, x1, z0, z1 } : null;
}

/**
 * The footprint of the model in graphics folder [gfx]: `{ src, rings, height }`, rings as flat x,z lists in model
 * feet; null when the model has no folder at all.
 */
export function modelFootprint(th, gfx) {
  const dir = modelDir(th, gfx);
  if (!dir) return null;
  if (known.has(dir)) return known.get(dir);
  const box = parentBox(dir);
  let out = null;
  const file = findFileCI(dir, 'Model_0.bml') || findFileCI(dir, 'Model_1.bml');
  const model = file ? modelTriangles(file) : null;
  const sil = model ? silhouette(model.tris) : null;
  if (sil) {
    // believed only when it is about the size its Parent.dat says the model is
    const agrees = !box || (
      (sil.x1 - sil.x0) > 0.5 * (box.x1 - box.x0) && (sil.x1 - sil.x0) < 1.6 * (box.x1 - box.x0) + 10
      && (sil.z1 - sil.z0) > 0.5 * (box.z1 - box.z0) && (sil.z1 - sil.z0) < 1.6 * (box.z1 - box.z0) + 10
    );
    if (agrees) {
      const top = topOf(model.tris);
      // the shaft and the cab: on a tower that stands over a building, the part a pilot looks for
      const high = top > 10 ? silhouette(model.tris, top * TOP_SHARE) : null;
      out = { src: 'model', rings: sil.rings, top: high?.rings ?? [], height: box?.height ?? Math.round(top) };
    }
  }
  if (!out && box && box.x1 > box.x0 && box.z1 > box.z0) {
    out = { src: 'box', rings: [[box.x0, box.z0, box.x1, box.z0, box.x1, box.z1, box.x0, box.z1]], top: [], height: box.height };
  }
  known.set(dir, out);
  return out;
}

const rad = (d) => (d * Math.PI) / 180;

/**
 * A footprint placed on the field: rotated by the feature's heading and moved to its offset, the way a field's
 * pavement models are placed (fieldTriangles in pavement.mjs). Rings come back as flat east,north lists, whole feet.
 */
export function placeFootprint(rings, e, n, heading) {
  const t = rad(-heading);
  const c = Math.cos(t), s = Math.sin(t);
  return rings.map((r) => {
    const out = [];
    for (let i = 0; i + 1 < r.length; i += 2) out.push(Math.round(e + r[i] * c - r[i + 1] * s), Math.round(n + r[i] * s + r[i + 1] * c));
    return out;
  });
}
