// Pulls the pictures out of Weapon Delivery Planner's form resources.
//
// The designer stores a control's BackgroundImage or Image in the form's .resx as a base64 payload — usually the
// raw PNG or BMP bytes, sometimes wrapped in a serialised System.Drawing.Bitmap whose bytes still carry the file
// inside. Either way the image is found by its magic number and written out as it is; nothing is re-encoded, so
// the DataCard's grid and the TOSS page's selector pictures are the artwork Falcas drew, byte for byte.
//
// Usage: node src/wdpimages.mjs <decompiled folder> <out dir>
//   writes <out dir>/<form>.<control>.<property>.png|bmp|jpg and an index.json naming them
import fs from 'node:fs';
import path from 'node:path';
import { DROPPED_FORMS, REMOVED_ART } from './wdpdropped.mjs';

const [, , srcDir, outDir] = process.argv;
if (!srcDir || !outDir) { console.error('usage: node src/wdpimages.mjs <decompiled folder> <out dir>'); process.exit(2); }
fs.mkdirSync(outDir, { recursive: true });

const MAGIC = [
  { ext: 'png', bytes: [0x89, 0x50, 0x4e, 0x47] },
  { ext: 'jpg', bytes: [0xff, 0xd8, 0xff] },
  { ext: 'bmp', bytes: [0x42, 0x4d] },
  { ext: 'gif', bytes: [0x47, 0x49, 0x46, 0x38] },
];

function findImage(buf) {
  for (const m of MAGIC) {
    const at = buf.indexOf(Buffer.from(m.bytes));
    if (at >= 0 && at < 4096) return { ext: m.ext, at };
  }
  return null;
}

const index = [];
for (const file of fs.readdirSync(srcDir).filter(f => f.endsWith('.resx'))) {
  const form = file.replace(/^WeaponDeliveryPlanner\./, '').replace(/\.resx$/, '');
  // the forms the Planner leaves out are not extracted (wdpdropped.mjs), so neither are their pictures
  if (DROPPED_FORMS.has(form)) continue;
  const xml = fs.readFileSync(path.join(srcDir, file), 'utf8');
  // <data name="pnlRefUp.BackgroundImage" type="System.Drawing.Bitmap, ..." mimetype="..."><value>base64</value></data>
  const re = /<data\s+name="([^"]+)"([^>]*)>\s*<value>([\s\S]*?)<\/value>/g;
  let m;
  while ((m = re.exec(xml))) {
    const [, name, attrs, value] = m;
    if (!/type="System\.Drawing\.(Bitmap|Image|Icon)|mimetype="application\/x-microsoft\.net\.object/.test(attrs)) continue;
    const b64 = value.replace(/\s+/g, '');
    let buf;
    try { buf = Buffer.from(b64, 'base64'); } catch { continue; }
    const img = findImage(buf);
    if (!img) continue;
    const [control, property] = name.split('.');
    // nor those of controls the Planner takes off a page it keeps
    if (REMOVED_ART.test(form + '.' + control)) continue;
    const out = `${form}.${control}.${property || 'Image'}.${img.ext}`;
    fs.writeFileSync(path.join(outDir, out), buf.subarray(img.at));
    index.push({ form, control, property: property || 'Image', file: out, bytes: buf.length - img.at });
  }
}
fs.writeFileSync(path.join(outDir, 'index.json'), JSON.stringify(index, null, 1));
const total = index.reduce((n, i) => n + i.bytes, 0);
console.log(`${index.length} images, ${(total / 1024).toFixed(0)} KB`);
for (const i of index.slice(0, 40)) console.log('  ' + i.file.padEnd(52) + String(i.bytes).padStart(8));
