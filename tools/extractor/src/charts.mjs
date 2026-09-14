// Airport charts (BMS-generated PNG diagrams/plates) → WebP, matched to airports per airport set.
// Output goes to the app assets (charts/ + data/charts.json).
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';
import { BMS_ROOT, DATA, writeJson } from './util.mjs';
import { loadTheaters } from './theaters.mjs';
import { buildAirports } from './airports.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../../..');
const FULL = path.join(ROOT, 'app/src/main/assets');
const OUTDIR = path.join(FULL, 'charts');
const index = JSON.parse(fs.readFileSync(path.join(ROOT, 'app/src/main/assets/data/index.json'), 'utf8'));

const norm = (s) => s.toLowerCase().normalize('NFD').replace(/[^a-z0-9]/g, '');

function walkPngDirs(root, out = new Map()) {
  if (!fs.existsSync(root)) return out;
  for (const e of fs.readdirSync(root, { withFileTypes: true })) {
    const p = path.join(root, e.name);
    if (e.isDirectory()) walkPngDirs(p, out);
    else if (/_(AGC|APC_RWY[0-9LRC]+|EOR)\.png$|\s-\s(ADC|AGC)\.png$/i.test(e.name)) {
      const dir = path.dirname(p);
      if (!out.has(dir)) out.set(dir, []);
      out.get(dir).push(p);
    }
  }
  return out;
}

function chartTitle(file) {
  const b = path.basename(file, '.png');
  let m;
  if ((m = b.match(/APC_RWY([0-9LRC]+)$/i))) return { order: 2, title: `Parking · RWY ${m[1]}` };
  if (/AGC$/i.test(b)) return { order: 1, title: 'Airport ground chart' };
  if (/ADC$/i.test(b)) return { order: 0, title: 'Aerodrome chart' };
  if (/EOR$/i.test(b)) return { order: 3, title: 'End of runway / arming' };
  return { order: 9, title: b };
}

function chartRootsFor(th) {
  const addon = th.tdf.match(/^(Add-On [^\\/]+)/i)?.[1];
  const roots = [];
  const addDocs = (name) => { const d = path.join(DATA, name, 'Docs'); if (fs.existsSync(d)) roots.push(d); };
  if (addon) addDocs(addon);
  // theaters that reuse another add-on's objects (e.g. Hellas WCP → Hellas) inherit its charts
  const objAddon = path.relative(DATA, th.objectDir).match(/^(Add-On [^\\/]+)/i)?.[1];
  if (objAddon && objAddon !== addon) addDocs(objAddon);
  const terrAddon = path.relative(DATA, th.terrainDir).match(/^(Add-On [^\\/]+)/i)?.[1];
  if (terrAddon && terrAddon !== addon) addDocs(terrAddon);
  if (/terrdata[\\/]korea$/i.test(th.terrainDir)) roots.push(path.join(BMS_ROOT, 'Docs/03 KTO Charts'));
  return [...new Set(roots)];
}

async function pool(items, n, fn) {
  let i = 0;
  await Promise.all(Array.from({ length: n }, async () => { while (i < items.length) { const k = i++; await fn(items[k], k); } }));
}

async function main() {
  fs.mkdirSync(OUTDIR, { recursive: true });
  const theaters = loadTheaters();
  const setDone = new Set();
  const chartIndex = {};
  const jobs = new Map(); // hash -> src
  const dirCache = new Map();
  for (const th of theaters) {
    const entryT = index.theaters.find((t) => t.id === th.id);
    if (!entryT?.primary) continue; // add-ons sharing a main theater's map reuse its airfields/charts
    const setId = entryT.airportSet;
    if (!setId || setDone.has(setId)) continue;
    setDone.add(setId);
    const roots = chartRootsFor(th);
    const dirs = new Map();
    for (const r of roots) { if (!dirCache.has(r)) dirCache.set(r, walkPngDirs(r)); for (const [k, v] of dirCache.get(r)) dirs.set(k, v); }
    const byIcao = new Map(), byName = [];
    for (const [dir, files] of dirs) {
      const name = path.basename(dir);
      const icao = name.match(/\(([A-Z0-9]{4})\)/)?.[1];
      if (icao) byIcao.set(icao, files);
      byName.push({ n: norm(name.replace(/\(.*?\)/g, '')), files });
    }
    const { airports } = buildAirports(th);
    const entry = {};
    let matched = 0;
    for (const a of airports) {
      let files = a.icao ? byIcao.get(a.icao) : null;
      if (!files) {
        const an = norm(a.name);
        const hit = byName.find((d) => d.n === an) || byName.find((d) => an.length > 4 && (d.n.startsWith(an) || an.startsWith(d.n)) && Math.min(d.n.length, an.length) >= 5);
        files = hit?.files;
      }
      if (!files) continue;
      matched++;
      entry[a.id] = files.map((f) => {
        const st = fs.statSync(f);
        const h = crypto.createHash('sha1').update(path.basename(f) + ':' + st.size).digest('hex').slice(0, 16);
        jobs.set(h, f);
        return { ...chartTitle(f), file: `charts/${h}.webp` };
      }).sort((x, y) => x.order - y.order || x.title.localeCompare(y.title, 'en', { numeric: true })).map(({ order, ...r }) => r);
    }
    chartIndex[setId] = entry;
    console.log(th.id, setId, 'roots', roots.length, 'dirs', dirs.size, 'airports matched', matched, '/', airports.length);
  }
  writeJson(path.join(FULL, 'data/charts.json'), chartIndex);
  const list = [...jobs.entries()].filter(([h]) => !fs.existsSync(path.join(OUTDIR, h + '.webp')));
  console.log('charts to convert', list.length, 'of', jobs.size);
  let done = 0;
  await pool(list, 6, async ([h, src]) => {
    try {
      await sharp(src, { limitInputPixels: false }).resize({ width: 1300, withoutEnlargement: true }).webp({ quality: 35, effort: 5 }).toFile(path.join(OUTDIR, h + '.webp'));
    } catch (e) { console.warn('fail', src, e.message); }
    if (++done % 100 === 0) console.log('converted', done);
  });
  // prune chart files no longer referenced
  const keep = new Set([...jobs.keys()].map((h) => h + '.webp'));
  let pruned = 0;
  for (const f of fs.readdirSync(OUTDIR)) if (!keep.has(f)) { fs.unlinkSync(path.join(OUTDIR, f)); pruned++; }
  console.log('done, pruned', pruned);
}

main().catch((e) => { console.error(e); process.exit(1); });
