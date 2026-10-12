// Renders the theater maps in several styles with tile levels (LOD), all on the campaign grid of each theater:
//   relief    shaded relief with elevation tints (NewTerrain/HeightMaps/HeightMap.raw, 32768² heights in feet)
//   satellite the sim's photoreal color map (NewTerrain/Photoreal/GlobalColorMap.dds, decoded with EZBoards' texconv)
//   dark      muted land/sea with faint relief, for AWACS work and busy overlays
//   chart     light, paper-like land and water, like an aviation chart
// Output (per map id, e.g. "korea"):
//   app/src/main/assets/maps/<id>/<style>.webp               1024 px overview (bundled)
//   app/src/main/assets/maps/<id>/<style>/<z>/<row>_<col>.webp  tiles, z = 2..MAX_Z (all bundled with the apps)
// Tile z has 2^z × 2^z tiles of 512 px (z2 = 2048 px, z3 = 4096, z4 = 8192 px across the theater; the overview stands in for z1).
// The app's MapBase.kt MAX_Z must match MAX_Z here.
// Every pixel is BMS's terrain at that place of the map's square, in BMS's own terrain frame (heightmap.mjs: the
// terrain is laid in metres of 3.28084 ft from the centre of its south-west sample, the square in 3.27998), with the
// levelling under the airbases (tld.mjs) — exactly what the PC's MapTiles.kt draws past MAX_Z, so the levels join.
// The fifth style, topographic ("topo": contours, hillshade, water), is not drawn here: its bundled levels (overview, z2,
// z3) come from the PC program's own renderer, the one that draws every style's levels past MAX_Z on demand
// (desktop MapTiles.kt; `"BMS Companion.exe" --maptiles <BMS folder> app/src/main/assets`), so both agree to the pixel.
// The BMS install is only read; texconv writes its decoded images into a temp folder.
//
// Usage: BMS_ROOT="G:/Falcon BMS 4.38" node src/maps.mjs [--only korea,hellas] [--styles relief,dark]
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';
import { findFileCI } from './util.mjs';
import { readHeightmap, heightmapSamples, textureOf } from './heightmap.mjs';
import { levelling } from './tld.mjs';

sharp.concurrency(4);
const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../../..');
const ASSETS = path.join(ROOT, 'app/src/main/assets');
const BMS = process.env.BMS_ROOT || 'G:/Falcon BMS 4.38';
export const MAX_Z = 4;
const TILE = 512;

const arg = (k) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : null; };
const ALL_STYLES = ['relief', 'satellite', 'dark', 'chart'];

/** Main terrains and their map ids (the ids the app already uses for maps/<id>.webp). */
// `objects`: the folder whose TerrainRelatedData levels the terrain under the airbases (MapTiles.kt's OBJECTS).
export const MAP_SOURCES = [
  { id: 'korea', dir: 'Data/TerrData/Korea', objects: 'Data/TerrData/Objects' },
  { id: 'balkans', dir: 'Data/Add-On Balkans/Terrdata/Balkans', objects: 'Data/Add-On Balkans/Terrdata/objects' },
  { id: 'hellas', dir: 'Data/Add-On Hellas/TerrData/Hellas', objects: 'Data/Add-On Hellas/TerrData/Objects' },
  { id: 'israel', dir: 'Data/Add-On Israel/Terrdata/Israel', objects: 'Data/Add-On Israel/Terrdata/objects' },
  { id: 'falklands', dir: 'Data/Add-On Falklands/TerrData/Falklands', objects: 'Data/Add-On Falklands/TerrData/objects' },
];

// ------------------------------------------------------------------ heights

/**
 * BMS's terrain levelling under the airbases (tld.mjs) over the heights, as MapTiles.kt's `flatten` lays it over the
 * PC's tiles: each pixel's centre in theater feet (the campaign grid the zones are drawn in), the zone's level inside
 * its 100 % polygon (and no water there), the linear mix in its rings. Returns the pixels changed.
 */
function flatten({ H, water }, n, sizeFt, lev) {
  if (!lev?.zones?.length) return 0;
  const px = sizeFt / n;
  let changed = 0;
  const done = new Uint8Array(n * n);
  for (const z of lev.zones) {
    const b = z.rings[z.rings.length - 1].box;
    const r0 = Math.max(0, Math.floor(n - b.n1 / px - 0.5)), r1 = Math.min(n - 1, Math.ceil(n - b.n0 / px - 0.5));
    const c0 = Math.max(0, Math.floor(b.e0 / px - 0.5)), c1 = Math.min(n - 1, Math.ceil(b.e1 / px - 0.5));
    for (let r = r0; r <= r1; r++) for (let c = c0; c <= c1; c++) {
      const i = r * n + c;
      if (done[i]) continue;
      const w = lev.weight(sizeFt - (r + 0.5) * px, (c + 0.5) * px);
      if (!w) continue;
      done[i] = 1;
      changed++;
      if (w.pct >= 99.9) { H[i] = w.zone.level; water[i] = 0; } else { const p = w.pct / 100; H[i] = w.zone.level * p + H[i] * (1 - p); }
    }
  }
  return changed;
}


const lerp = (a, b, t) => a + (b - a) * t;
function ramp(stops, v) {
  if (v <= stops[0][0]) return stops[0][1];
  for (let i = 1; i < stops.length; i++) if (v <= stops[i][0]) {
    const t = (v - stops[i - 1][0]) / (stops[i][0] - stops[i - 1][0]);
    return stops[i - 1][1].map((c, j) => lerp(c, stops[i][1][j], t));
  }
  return stops[stops.length - 1][1];
}

const RELIEF_LAND = [[0, [58, 82, 62]], [800, [78, 102, 72]], [2500, [110, 120, 82]], [5000, [138, 128, 96]], [8000, [160, 146, 126]], [11000, [214, 210, 204]]];
const RELIEF_SEA = [[-3000, [14, 28, 44]], [-200, [20, 40, 60]], [0, [28, 52, 74]]];
const CHART_LAND = [[0, [236, 232, 214]], [1500, [226, 222, 196]], [4000, [212, 200, 170]], [8000, [196, 182, 156]], [11000, [238, 236, 232]]];
const CHART_SEA = [[-3000, [150, 192, 216]], [0, [184, 214, 230]]];

/** Renders a style from heights into an RGB buffer of size × size. */
function shadeHeights(style, { H, water }, n, sizeFt) {
  const px = sizeFt / n;
  const rgb = Buffer.alloc(n * n * 3);
  const at = (x, y) => H[Math.min(n - 1, Math.max(0, y)) * n + Math.min(n - 1, Math.max(0, x))];
  // sun from the north-west, 45° up; relief exaggerated more on coarse levels so it stays readable
  const exaggeration = Math.max(1.5, Math.min(6, 6 * 1024 / n * 0.5 + 1.2));
  const lx = -Math.SQRT1_2 * Math.SQRT1_2, ly = -Math.SQRT1_2 * Math.SQRT1_2, lz = Math.SQRT1_2;
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    const h = H[y * n + x];
    const gx = (at(x + 1, y) - at(x - 1, y)) / (2 * px) * exaggeration;
    const gy = (at(x, y + 1) - at(x, y - 1)) / (2 * px) * exaggeration;
    const len = Math.sqrt(gx * gx + gy * gy + 1);
    // normal (-gx, gy, 1) with y pointing south in image space
    const lambert = (-gx * lx + gy * ly + lz) / len;
    const shade = lambert / lz; // 1 on flat ground
    let c;
    const land = !water[y * n + x]; // land below sea level (e.g. the Jordan valley) stays land
    switch (style) {
      case 'relief':
        c = land ? ramp(RELIEF_LAND, h).map((v) => v * Math.max(0.35, Math.min(1.35, shade))) : ramp(RELIEF_SEA, h);
        break;
      case 'dark':
        c = land ? [42, 52, 54].map((v) => v * Math.max(0.55, Math.min(1.45, 1 + (shade - 1) * 0.8))) : [12, 19, 27];
        break;
      case 'chart':
        c = land ? ramp(CHART_LAND, h).map((v) => v * Math.max(0.78, Math.min(1.06, 1 + (shade - 1) * 0.45))) : ramp(CHART_SEA, h);
        break;
    }
    const o = (y * n + x) * 3;
    rgb[o] = Math.min(255, c[0]); rgb[o + 1] = Math.min(255, c[1]); rgb[o + 2] = Math.min(255, c[2]);
  }
  // coastline: a thin darker line where land meets sea (chart and dark only)
  if (style !== 'relief') for (let y = 1; y < n - 1; y++) for (let x = 1; x < n - 1; x++) {
    const i = y * n + x;
    if (!water[i] && (water[i - 1] || water[i + 1] || water[i - n] || water[i + n])) {
      const o = i * 3, col = style === 'chart' ? [120, 150, 170] : [70, 92, 104];
      rgb[o] = col[0]; rgb[o + 1] = col[1]; rgb[o + 2] = col[2];
    }
  }
  return rgb;
}

// ------------------------------------------------------------------ satellite

/**
 * A size × size RGB picture of the whole terrain (the texture's own frame) resampled onto the map's square: each
 * output pixel's centre through [textureOf], Lanczos-3 in two passes (the scale is within 0.03 % of one, so this
 * is a shift of up to two pixels at the north-east and an eighth at the south-west, not a resize; Lanczos keeps the
 * texture's detail where a cubic softened it by a few per cent).
 */
function toMapFrame(src, size, n) {
  const T = 6; // taps: Lanczos a = 3
  const sinc = (x) => (x === 0 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x));
  const taps = (pos) => { // source pixel coordinates of each output pixel → T indices and weights summing to 1
    const idx = new Int32Array(size * T), wt = new Float32Array(size * T);
    for (let i = 0; i < size; i++) {
      const s = pos(i), i0 = Math.floor(s) - 2;
      let sum = 0;
      for (let q = 0; q < T; q++) { const d = s - (i0 + q); const w = Math.abs(d) < 3 ? sinc(d) * sinc(d / 3) : 0; wt[i * T + q] = w; sum += w; }
      for (let q = 0; q < T; q++) { idx[i * T + q] = Math.min(size - 1, Math.max(0, i0 + q)); wt[i * T + q] /= sum; }
    }
    return { idx, wt };
  };
  const X = taps((i) => textureOf((i + 0.5) / size, 0, n).u * size - 0.5);
  const Y = taps((j) => textureOf(0, 1 - (j + 0.5) / size, n).v * size - 0.5);
  const clamp = (v) => (v < 0 ? 0 : v > 255 ? 255 : Math.round(v));
  const mid = Buffer.alloc(size * size * 3);
  for (let y = 0; y < size; y++) {
    const row = y * size * 3;
    for (let x = 0; x < size; x++) for (let ch = 0; ch < 3; ch++) {
      let v = 0;
      for (let q = 0; q < T; q++) v += src[row + X.idx[x * T + q] * 3 + ch] * X.wt[x * T + q];
      mid[row + x * 3 + ch] = clamp(v);
    }
  }
  const out = Buffer.alloc(size * size * 3);
  for (let y = 0; y < size; y++) {
    const r = [], w = [];
    for (let q = 0; q < T; q++) { r.push(Y.idx[y * T + q] * size * 3); w.push(Y.wt[y * T + q]); }
    const o = y * size * 3;
    for (let x = 0; x < size * 3; x++) {
      let v = 0;
      for (let q = 0; q < T; q++) v += mid[r[q] + x] * w[q];
      out[o + x] = clamp(v);
    }
  }
  return out;
}

function texconvPath() {
  const p = path.join(BMS, 'Tools/EZBoards/bin/texconv.exe');
  if (!fs.existsSync(p)) throw new Error('texconv.exe not found in ' + p);
  return p;
}

/** Decodes GlobalColorMap.dds to an RGB buffer of size × size (sRGB), brightened for a dark UI. */
async function readSatellite(terrainDir, size) {
  const dds = path.join(findFileCI(findFileCI(terrainDir, 'NewTerrain'), 'Photoreal'), 'GlobalColorMap.dds');
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'bmsc-colormap-'));
  const head = Buffer.alloc(148);
  const fd = fs.openSync(dds, 'r'); fs.readSync(fd, head, 0, 148, 0); fs.closeSync(fd);
  const srgb = head.toString('latin1', 84, 88) === 'DX10' && [29, 72, 75, 78, 91, 93, 99].includes(head.readUInt32LE(128));
  execFileSync(texconvPath(), ['-nologo', '-y', '-ft', 'png', '-f', srgb ? 'R8G8B8A8_UNORM_SRGB' : 'R8G8B8A8_UNORM', '-w', String(size), '-h', String(size), '-o', tmp, dds], { stdio: 'ignore' });
  const png = path.join(tmp, fs.readdirSync(tmp).find((f) => /\.png$/i.test(f)));
  const decoded = await sharp(png, { limitInputPixels: false }).removeAlpha().raw().toBuffer();
  fs.rmSync(tmp, { recursive: true, force: true });
  // the texture covers the terrain, not the map's square (heightmap.mjs, textureOf)
  const raw = toMapFrame(decoded, size, heightmapSamples(terrainDir));
  // auto levels on luminance (1st–99.5th percentile), so dark textures (e.g. Hellas) read well on screen
  const hist = new Uint32Array(256);
  for (let i = 0; i < raw.length; i += 3 * 7) hist[Math.round(raw[i] * 0.3 + raw[i + 1] * 0.59 + raw[i + 2] * 0.11)]++;
  const total = hist.reduce((a, b) => a + b, 0);
  let acc = 0, lo = 0, hi = 255;
  for (let v = 0; v < 256; v++) { acc += hist[v]; if (acc < total * 0.01) lo = v; if (acc < total * 0.995) hi = v; }
  const scale = 235 / Math.max(40, hi - lo);
  for (let i = 0; i < raw.length; i++) raw[i] = Math.max(0, Math.min(255, (raw[i] - lo) * scale));
  return raw;
}

// ------------------------------------------------------------------ output

async function writeLevels(rgb, n, mapId, style, maxZ) {
  const img = () => sharp(rgb, { raw: { width: n, height: n, channels: 3 } });
  const quality = style === 'satellite' ? 70 : style === 'relief' ? 74 : 80;
  let bytes = 0;
  const overview = path.join(ASSETS, 'maps', mapId, style + '.webp');
  fs.mkdirSync(path.dirname(overview), { recursive: true });
  await img().resize(1024, 1024).webp({ quality }).toFile(overview);
  bytes += fs.statSync(overview).size;
  for (let z = 2; z <= maxZ; z++) { // z1 would repeat the 1024 px overview
    const size = TILE << z;
    const level = size === n ? rgb : await img().resize(size, size, { kernel: 'lanczos3' }).raw().toBuffer();
    const dir = path.join(ASSETS, 'maps', mapId, style, String(z));
    fs.rmSync(dir, { recursive: true, force: true });
    fs.mkdirSync(dir, { recursive: true });
    const count = 1 << z;
    const jobs = [];
    for (let r = 0; r < count; r++) for (let c = 0; c < count; c++) {
      const file = path.join(dir, `${r}_${c}.webp`);
      jobs.push(sharp(level, { raw: { width: size, height: size, channels: 3 } }).extract({ left: c * TILE, top: r * TILE, width: TILE, height: TILE }).webp({ quality }).toFile(file)
        .then((info) => { bytes += info.size; }));
      if (jobs.length >= 16) await Promise.all(jobs.splice(0));
    }
    await Promise.all(jobs);
  }
  return bytes;
}

async function main() {
  const only = arg('--only')?.split(',');
  const styles = arg('--styles')?.split(',') ?? ALL_STYLES;
  const maxZ = MAX_Z;
  const sizes = {};
  for (const src of MAP_SOURCES) {
    if (only && !only.includes(src.id)) continue;
    const dir = path.join(BMS, src.dir);
    const theaterTxt = fs.readFileSync(path.join(findFileCI(dir, 'NewTerrain'), 'Theater.txt'), 'latin1');
    const sizeFt = parseFloat(theaterTxt.match(/Theater size in KM=([\d.]+)/)[1]) * 1000 * 3.27998;
    const top = TILE << maxZ;
    const t0 = Date.now();
    let H = null;
    for (const style of styles) {
      let rgb;
      if (style === 'satellite') rgb = await readSatellite(dir, top);
      else {
        if (!H) {
          H = readHeightmap(dir, top);
          const lev = src.objects ? levelling({ terrainDir: dir, objectDir: path.join(BMS, src.objects), data3dDir: null }) : null;
          console.log(`${src.id}: heights read, ${flatten(H, top, sizeFt, lev)} pixels levelled (${lev?.zones?.length ?? 0} zones)`);
        }
        rgb = shadeHeights(style, H, top, sizeFt);
      }
      const s = await writeLevels(rgb, top, src.id, style, maxZ);
      sizes[`${src.id}/${style}`] = s;
      console.log(`${src.id} ${style}: ${(s / 1e6).toFixed(1)} MB (${((Date.now() - t0) / 1000).toFixed(0)} s)`);
    }
  }
  console.log(`total: ${(Object.values(sizes).reduce((a, b) => a + b, 0) / 1e6).toFixed(1)} MB`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) main().catch((e) => { console.error(e); process.exit(1); });
