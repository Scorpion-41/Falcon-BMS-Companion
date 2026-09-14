// Renders a shaded-relief theater map from TERRAIN*.BIL (16-bit heights, row 0 = north).
import fs from 'node:fs';
import path from 'node:path';
import sharp from 'sharp';
import { findFileCI } from './util.mjs';

export function terrainInfo(th) {
  const tdir = findFileCI(th.terrainDir, 'Terrain');
  if (!tdir) return null;
  const map = findFileCI(tdir, 'Theater.map');
  const bil = fs.readdirSync(tdir).find((f) => /^TERRAIN.*\.BIL$/i.test(f));
  if (!map || !bil) return null;
  const b = fs.readFileSync(map);
  const fpp = b.readFloatLE(0), w = b.readUInt32LE(4), h = b.readUInt32LE(8);
  const hdr = fs.readFileSync(path.join(tdir, bil.replace(/BIL$/i, 'HDR')), 'latin1');
  const n = +hdr.match(/NCOLS\s+(\d+)/)[1];
  const l2 = fs.readdirSync(tdir).find((f) => /^THEATER.*.L2$/i.test(f));
  const o2 = fs.readdirSync(tdir).find((f) => /^THEATER.*.O2$/i.test(f));
  return { dir: tdir, bil: path.join(tdir, bil), l2: l2 && path.join(tdir, l2), o2: o2 && path.join(tdir, o2), n, sizeFt: Math.round(w * 32 * fpp), sizeFtY: Math.round(h * 32 * fpp) };
}

const lerp = (a, b, t) => a + (b - a) * t;
function tint(hn) {
  // hypsometric palette tuned for a dark UI (low → high)
  const stops = [
    [0.0, [52, 74, 60]], [0.08, [72, 96, 70]], [0.22, [104, 116, 80]], [0.42, [132, 124, 92]], [0.65, [150, 136, 118]], [1.0, [210, 206, 200]],
  ];
  for (let i = 1; i < stops.length; i++) {
    if (hn <= stops[i][0]) {
      const t = (hn - stops[i - 1][0]) / (stops[i][0] - stops[i - 1][0]);
      return stops[i - 1][1].map((c, k) => lerp(c, stops[i][1][k], t));
    }
  }
  return stops[stops.length - 1][1];
}

/**
 * Heights in theater orientation (row 0 = north, col 0 = west).
 * Uses the sim's LOD-2 terrain (THEATER*.L2/.O2), which is exactly aligned with campaign coordinates.
 * (Some add-ons ship a TERRAIN*.BIL heightmap that covers a different extent, e.g. Israel.)
 */
export function loadHeights(info) {
  if (info.l2 && info.o2) {
    const l2 = fs.readFileSync(info.l2), o2 = fs.readFileSync(info.o2);
    const nb = Math.round(Math.sqrt(o2.length / 4));
    const offs = []; for (let b = 0; b < nb * nb; b++) offs.push(o2.readUInt32LE(b * 4));
    const sorted = [...new Set(offs)].sort((a, b) => a - b);
    const blockBytes = sorted.length > 1 ? sorted[1] - sorted[0] : l2.length;
    const pp = Math.round(Math.sqrt(blockBytes / 9));
    const n = nb * pp, H = new Float32Array(n * n);
    for (let b = 0; b < nb * nb; b++) {
      const off = offs[b], bi = Math.floor(b / nb), bj = b % nb;
      for (let p = 0; p < pp * pp; p++) {
        const pi = Math.floor(p / pp), pj = p % pp;
        const v = l2.readInt16LE(off + p * 9 + 4);
        const row = n - 1 - (bi * pp + pi), col = bj * pp + pj;
        H[row * n + col] = Math.max(0, v);
      }
    }
    return { n, H };
  }
  const raw = fs.readFileSync(info.bil); const n = info.n; const H = new Float32Array(n * n);
  for (let i = 0; i < n * n; i++) H[i] = Math.max(0, raw.readInt16LE(i * 2));
  return { n, H };
}

/**
 * @param opts.affine  {sx, sy, ox, oy}: theater pixel (u,v) -> heightmap pixel (u*sx+ox, v*sy+oy), in 1024-grid units.
 *                     Used when a theater's heightmap is not aligned with its campaign grid.
 * @param opts.fill    image path used (heavily blurred, as a land/sea tint) where the heightmap has no data.
 */
export async function renderTerrain(info, outFile, size = 1024, opts = {}) {
  const src = loadHeights(info);
  let n = src.n, H = src.H;
  let missing = null;
  if (opts.affine) {
    const { sx, sy, ox, oy } = opts.affine, k = src.n / 1024;
    const W = new Float32Array(n * n); missing = new Uint8Array(n * n);
    const sample = (x, y) => {
      const x0 = Math.floor(x), y0 = Math.floor(y), fx = x - x0, fy = y - y0;
      const g = (c, r) => src.H[Math.min(src.n - 1, Math.max(0, r)) * src.n + Math.min(src.n - 1, Math.max(0, c))];
      return (g(x0, y0) * (1 - fx) + g(x0 + 1, y0) * fx) * (1 - fy) + (g(x0, y0 + 1) * (1 - fx) + g(x0 + 1, y0 + 1) * fx) * fy;
    };
    for (let v = 0; v < n; v++) for (let u = 0; u < n; u++) {
      const x = (u * sx + ox * k), y = (v * sy + oy * k);
      if (x < 0 || y < 0 || x > src.n - 1 || y > src.n - 1) { missing[v * n + u] = 1; continue; }
      W[v * n + u] = sample(x, y);
    }
    H = W;
  }
  let fill = null;
  if (missing && opts.fill && fs.existsSync(opts.fill)) {
    // downscale to 512 then blur: baked-in text becomes unreadable smudge, terrain texture survives
    fill = await sharp(await sharp(opts.fill, { limitInputPixels: false }).resize(512, 512, { fit: 'fill' }).blur(1.6).png().toBuffer())
      .resize(n, n, { kernel: 'cubic' }).blur(1.2).removeAlpha().raw().toBuffer();
  }
  let max = 1;
  // robust max (99.8th percentile) so a few spikes don't flatten the map
  const sorted = Float32Array.from(H).sort();
  max = Math.max(1, sorted[Math.floor(sorted.length * 0.998)]);
  const fillColor = (i) => {
    if (!fill) return [22, 38, 56];
    const r = fill[i * 3], g = fill[i * 3 + 1], b = fill[i * 3 + 2];
    if (b > r + 18 && b > g) return [22, 38, 56];
    // grade satellite colour toward the relief palette (desaturate + darken)
    const l = (r * 0.3 + g * 0.59 + b * 0.11) / 255;
    const base = tint(Math.min(0.55, 0.05 + l * 0.5));
    // pseudo hillshade from local luminance gradient (light from NW), so the strip isn't flat
    const lum = (j) => (j < 0 || j >= n * n) ? l * 255 : fill[j * 3] * 0.3 + fill[j * 3 + 1] * 0.59 + fill[j * 3 + 2] * 0.11;
    const gx = (lum(i + 2) - lum(i - 2)) / 255, gy = (lum(i + 2 * n) - lum(i - 2 * n)) / 255;
    const shade = Math.max(0.7, Math.min(1.2, 1 + (gx + gy) * 1.6));
    return base.map((v, k) => Math.min(255, (v * 0.75 + [r, g, b][k] * 0.18) * shade));
  };
  // distance (in px) to the nearest missing pixel, for feathering the seam
  let feather = null;
  if (missing && fill) {
    const F = 10; feather = new Float32Array(n * n).fill(F);
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (missing[y * n + x])
      for (let dy = -F; dy <= F; dy++) for (let dx = -F; dx <= F; dx++) {
        const xx = x + dx, yy = y + dy; if (xx < 0 || yy < 0 || xx >= n || yy >= n) continue;
        const d = Math.hypot(dx, dy); const j = yy * n + xx; if (d < feather[j]) feather[j] = d;
      }
  }
  const rgb = Buffer.alloc(n * n * 3);
  const at = (x, y) => H[Math.min(n - 1, Math.max(0, y)) * n + Math.min(n - 1, Math.max(0, x))];
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      const i = y * n + x;
      let c;
      if (missing && missing[i]) {
        c = fillColor(i);
      } else {
        const h = H[i];
        if (h <= 0) {
          c = [22, 38, 56]; // sea
        } else {
          const dx = (at(x + 1, y) - at(x - 1, y)) / max * 60;
          const dy = (at(x, y + 1) - at(x, y - 1)) / max * 60;
          // light from north-west
          const shade = Math.max(0.45, Math.min(1.25, 1 + (-dx - dy) * 0.35));
          c = tint(Math.min(1, h / max)).map((v) => Math.min(255, v * shade));
        }
        if (feather && feather[i] < 10) { const t = feather[i] / 10; const f = fillColor(i); c = c.map((v, k) => v * t + f[k] * (1 - t)); }
      }
      const o = i * 3;
      rgb[o] = c[0]; rgb[o + 1] = c[1]; rgb[o + 2] = c[2];
    }
  }
  await sharp(rgb, { raw: { width: n, height: n, channels: 3 } }).resize(size, size).webp({ quality: 80 }).toFile(outFile);
}
