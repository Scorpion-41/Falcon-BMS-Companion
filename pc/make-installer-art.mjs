// Draws the two bitmaps the MSI installer shows, so it looks like BMS Companion and not a blank WiX installer:
//   desktop/installer/banner.bmp  493x58   top banner of the interior pages
//   desktop/installer/dialog.bmp  493x312  background of the welcome and finish pages
// WiX draws its own text over both in black, so the areas it writes in are kept light:
//   banner: title at x 20-406, so the artwork sits on the right
//   dialog: title and text at x 180-473, so the artwork sits in the left panel
// The output is checked in; run this again only when the artwork changes.
//   node pc/make-installer-art.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const HERE = path.dirname(fileURLToPath(import.meta.url));
// the repo has one node_modules, the extractor's
const sharp = createRequire(path.join(HERE, '../tools/extractor/'))('sharp');
const OUT = path.join(HERE, '../desktop/installer');

const BG = '#0A0F14'; // Hud.Bg
const GREEN = '#5BE38A';
const AMBER = '#FFB547';

/** The app's mark: dark disc, HUD reticle, F-16 delta — the same drawing as the app icon. */
const logo = (cx, cy, r) => `
  <g transform="translate(${cx} ${cy}) scale(${r / 54})">
    <circle cx="0" cy="0" r="54" fill="#0B1218"/>
    <circle cx="0" cy="0" r="30" fill="none" stroke="${GREEN}" stroke-width="4.4"/>
    <circle cx="0" cy="0" r="10" fill="none" stroke="${GREEN}" stroke-width="3.2"/>
    <path d="M0,-36 L0,-28 M0,28 L0,36 M-36,0 L-28,0 M28,0 L36,0" stroke="${GREEN}" stroke-width="4.4"/>
    <path d="M0,-24 L3,-10 L3,-2 L18,8 L18,12 L3,8 L2.5,16 L8,21 L8,24 L0,22 L-8,24 L-8,21 L-2.5,16 L-3,8 L-18,12 L-18,8 L-3,-2 L-3,-10 Z" fill="${AMBER}"/>
  </g>`;

/** Faint HUD grid, so the dark panel is not a flat rectangle. */
const grid = (w, h, step = 22) => {
  let d = '';
  for (let x = step; x < w; x += step) d += `M${x},0 L${x},${h} `;
  for (let y = step; y < h; y += step) d += `M0,${y} L${w},${y} `;
  return `<path d="${d}" stroke="#FFFFFF" stroke-opacity="0.05" stroke-width="1"/>`;
};

const dialog = `<svg xmlns="http://www.w3.org/2000/svg" width="493" height="312">
  <defs>
    <linearGradient id="light" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#E7EDF2"/>
    </linearGradient>
    <linearGradient id="accent" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="${GREEN}"/><stop offset="1" stop-color="${AMBER}"/>
    </linearGradient>
    <radialGradient id="glow" cx="0.5" cy="0.35" r="0.7">
      <stop offset="0" stop-color="${GREEN}" stop-opacity="0.16"/><stop offset="1" stop-color="${GREEN}" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect width="493" height="312" fill="url(#light)"/>
  <rect width="176" height="312" fill="${BG}"/>
  ${grid(176, 312)}
  <rect width="176" height="312" fill="url(#glow)"/>
  ${logo(88, 104, 52)}
  <text x="88" y="196" text-anchor="middle" font-family="Segoe UI, Arial, sans-serif" font-size="25" font-weight="700" fill="#E6EDF3" letter-spacing="1.5">BMS</text>
  <text x="88" y="220" text-anchor="middle" font-family="Segoe UI, Arial, sans-serif" font-size="19" font-weight="600" fill="#E6EDF3" letter-spacing="1.2">COMPANION</text>
  <text x="88" y="248" text-anchor="middle" font-family="Segoe UI, Arial, sans-serif" font-size="10.5" font-weight="600" fill="${GREEN}" letter-spacing="1.6">FOR FALCON BMS</text>
  <rect x="176" y="0" width="3" height="312" fill="url(#accent)"/>
</svg>`;

const banner = `<svg xmlns="http://www.w3.org/2000/svg" width="493" height="58">
  <defs>
    <linearGradient id="light" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#EDF2F6"/>
    </linearGradient>
    <linearGradient id="accent" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="${GREEN}"/><stop offset="1" stop-color="${AMBER}"/>
    </linearGradient>
  </defs>
  <rect width="493" height="58" fill="url(#light)"/>
  <rect x="410" y="0" width="83" height="56" fill="${BG}"/>
  ${grid(83, 56, 14).replace('<path d="', '<path transform="translate(410 0)" d="')}
  ${logo(451, 27, 22)}
  <rect x="0" y="56" width="493" height="2" fill="url(#accent)"/>
</svg>`;

/** 24-bit BMP: the only format the WiX dialogs read. Rows are bottom-up and padded to four bytes. */
function toBmp(rgb, w, h) {
  const rowSize = Math.ceil((w * 3) / 4) * 4;
  const pixels = Buffer.alloc(rowSize * h);
  for (let y = 0; y < h; y++) {
    const src = (h - 1 - y) * w * 3; // BMP stores the bottom row first
    for (let x = 0; x < w; x++) {
      const s = src + x * 3;
      const d = y * rowSize + x * 3;
      pixels[d] = rgb[s + 2]; // BMP is BGR
      pixels[d + 1] = rgb[s + 1];
      pixels[d + 2] = rgb[s];
    }
  }
  const header = Buffer.alloc(54);
  header.write('BM', 0, 'latin1');
  header.writeUInt32LE(54 + pixels.length, 2);
  header.writeUInt32LE(54, 10);
  header.writeUInt32LE(40, 14);
  header.writeInt32LE(w, 18);
  header.writeInt32LE(h, 22);
  header.writeUInt16LE(1, 26); // planes
  header.writeUInt16LE(24, 28); // bits per pixel
  header.writeUInt32LE(pixels.length, 34);
  header.writeInt32LE(3780, 38); // ~96 dpi
  header.writeInt32LE(3780, 42);
  return Buffer.concat([header, pixels]);
}

async function write(name, svg, w, h) {
  // librsvg renders SVG at its own dpi, so the size is pinned here: the BMP header has to match the pixels exactly
  const { data, info } = await sharp(Buffer.from(svg), { density: 96 })
    .resize(w, h, { fit: 'fill' })
    .flatten({ background: '#ffffff' }).removeAlpha().raw().toBuffer({ resolveWithObject: true });
  if (info.width !== w || info.height !== h) throw new Error(`${name}: rendered ${info.width}x${info.height}, wanted ${w}x${h}`);
  fs.mkdirSync(OUT, { recursive: true });
  const file = path.join(OUT, name);
  fs.writeFileSync(file, toBmp(data, w, h));
  console.log('wrote', file, fs.statSync(file).size, 'bytes');
  // a PNG next to it, for looking at the artwork without a BMP viewer
  await sharp(Buffer.from(svg), { density: 96 }).resize(w, h, { fit: 'fill' }).png().toFile(file.replace(/\.bmp$/, '.png'));
}

await write('dialog.bmp', dialog, 493, 312);
await write('banner.bmp', banner, 493, 58);
