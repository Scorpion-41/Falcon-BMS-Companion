// Alignment check of a decoded color map (PNG) against the NewTerrain heightmap water (heightmap.mjs), best pixel offset.
// Usage: node src/cmcheck.mjs <theater NewTerrain parent dir> <colormap.png>
import fs from 'node:fs';
import sharp from 'sharp';
import { readHeightmap } from './heightmap.mjs';

const [dir, png] = process.argv.slice(2);
const n = 512;
const { data } = await sharp(png).resize(n, n, { kernel: 'nearest' }).removeAlpha().raw().toBuffer({ resolveWithObject: true });
const sea = readHeightmap(dir, n).water;
const water = (r, c) => { const i = (r * n + c) * 3; const R = data[i], G = data[i + 1], B = data[i + 2]; return R + G + B < 60 || (B > R + 8 && R + G + B < 180); };
let best = { a: 0 };
for (let dr = -6; dr <= 6; dr++) for (let dc = -6; dc <= 6; dc++) {
  let ok = 0, tot = 0;
  for (let r = 8; r < n - 8; r += 2) for (let c = 8; c < n - 8; c += 2) { tot++; if (water(r + dr, c + dc) === !!sea[r * n + c]) ok++; }
  if (ok / tot > best.a) best = { a: ok / tot, dr, dc };
}
console.log(`agreement ${(best.a * 100).toFixed(1)}% at dRow ${best.dr} dCol ${best.dc}`);
