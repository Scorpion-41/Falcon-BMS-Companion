// TGA decoding (types 2/3/10/11, 8/16/24/32 bpp) → WebP via sharp.
import fs from 'node:fs';
import path from 'node:path';
import sharp from 'sharp';
import { DATA, findFileCI } from './util.mjs';

export function decodeTga(buf) {
  const idLen = buf[0], cmapType = buf[1], type = buf[2];
  const cmapLen = buf.readUInt16LE(5), cmapDepth = buf[7];
  const w = buf.readUInt16LE(12), h = buf.readUInt16LE(14), bpp = buf[16], flags = buf[17];
  let off = 18 + idLen + (cmapType ? cmapLen * Math.ceil(cmapDepth / 8) : 0);
  const rle = type === 10 || type === 11;
  const gray = type === 3 || type === 11;
  const bytes = bpp / 8;
  const out = Buffer.alloc(w * h * 4);
  const readPixel = (o, dst) => {
    if (gray) { out[dst] = out[dst + 1] = out[dst + 2] = buf[o]; out[dst + 3] = 255; return; }
    if (bpp === 16 || bpp === 15) {
      const v = buf.readUInt16LE(o);
      out[dst] = ((v >> 10) & 31) * 255 / 31; out[dst + 1] = ((v >> 5) & 31) * 255 / 31; out[dst + 2] = (v & 31) * 255 / 31; out[dst + 3] = 255;
      return;
    }
    out[dst] = buf[o + 2]; out[dst + 1] = buf[o + 1]; out[dst + 2] = buf[o]; out[dst + 3] = bpp === 32 ? buf[o + 3] : 255;
  };
  const px = w * h;
  let i = 0;
  if (!rle) {
    for (; i < px; i++, off += bytes) readPixel(off, i * 4);
  } else {
    while (i < px && off < buf.length) {
      const c = buf[off++];
      const n = (c & 127) + 1;
      if (c & 128) { for (let k = 0; k < n && i < px; k++, i++) readPixel(off, i * 4); off += bytes; }
      else { for (let k = 0; k < n && i < px; k++, i++, off += bytes) readPixel(off, i * 4); }
    }
  }
  // origin: bit 5 set = top-left; otherwise bottom-left → flip rows
  if (!(flags & 0x20)) {
    const row = w * 4, tmp = Buffer.alloc(row);
    for (let y = 0; y < h >> 1; y++) {
      const a = y * row, b = (h - 1 - y) * row;
      out.copy(tmp, 0, a, a + row); out.copy(out, a, b, b + row); tmp.copy(out, b);
    }
  }
  // 32bpp TacRef images often have an all-zero alpha channel; ignore alpha in that case
  if (bpp === 32) { let any = false; for (let k = 3; k < out.length; k += 4) if (out[k]) { any = true; break; } if (!any) for (let k = 3; k < out.length; k += 4) out[k] = 255; }
  return { w, h, data: out };
}

export function findTacRefImage(th, pic) {
  if (!pic) return null;
  const dirs = [path.join(th.artDir, 'TacRData'), path.join(DATA, 'Art/TacRData')];
  for (const d of dirs) {
    const f = findFileCI(d, pic + '.tga');
    if (f) return f;
  }
  return null;
}

export async function tgaToWebp(src, dst, quality = 78) {
  const { w, h, data } = decodeTga(fs.readFileSync(src));
  await sharp(data, { raw: { width: w, height: h, channels: 4 } }).flatten({ background: '#10161d' }).webp({ quality }).toFile(dst);
}
