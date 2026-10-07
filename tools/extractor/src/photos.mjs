/**
 * Photographs for the Reference section, from Wikimedia Commons (1.3.9).
 *
 * BMS's own TacRef art is 320 x 151 px, and a few aircraft and stores have none at all. `tools/curated/photos.json`
 * names, for a picture or an entry, one Commons file that was checked by eye to show exactly that type, variant and
 * (for an air arm's aircraft) nation:
 *
 *   "pic:<BMS picture name>"  replaces that BMS picture wherever it is shown (a photo of the same thing, same nation)
 *   "aircraft:<key>", "weapon:<key>", "enc:<key>", "threat:<id>"   gives that one entry a picture (it has none, or BMS's
 *                              shows something else)
 *
 * each with `file` ("File:…" on Commons), `subject` (the picture's name in the credits list) and optionally `focus`
 * (where the picture band sits, 0 top … 1 bottom, default 0.5), `fx` (0 left … 1 right) and `zoom` (> 1 crops in);
 * or with `same` (another target: the same photo, fetched and shipped once). `why` says what identifies the type.
 *
 *   node src/photos.mjs [--force] [<target> …]
 *
 * fetches each file's licence and author from the Commons API (extmetadata), refuses anything that is not public
 * domain, CC0, CC BY or CC BY-SA, downloads it (a 1280 px rendition, cached in `cache/photos`), cuts the 320:151 band
 * the app shows, scales it to at most 800 px wide and writes `img/tacref/ph-….webp` (WebP q80), and records the
 * credit in `tools/curated/photo_credits.json` and the list the app shows (`data/credits/photos.json`). A
 * Reference run (`referencerun.mjs`, `main.mjs`) then puts the photos in place with `applyPhotos`; a file this script
 * has not fetched (no credit recorded) changes nothing.
 *
 * Wikimedia's API etiquette: a User-Agent naming the project, one request at a time, a second apart.
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';
import { slug, writeJson } from './util.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../../..');
const CURATED = path.join(ROOT, 'tools/curated');
const ASSETS = path.join(ROOT, 'app/src/main/assets');
export const PHOTO_DIR = path.join(ASSETS, 'img/tacref');
const CACHE = path.join(HERE, '../cache/photos');
const PHOTOS_FILE = path.join(CURATED, 'photos.json');
const CREDITS_FILE = path.join(CURATED, 'photo_credits.json');
const APP_CREDITS = path.join(ASSETS, 'data/credits/photos.json');

const BAND = 320 / 151; // the shape BMS's art has and the app's picture box crops to
const WIDTH = 800;
const QUALITY = 80;
const CHANGES = 'cropped to a 320:151 band, resized to at most 800 px wide, WebP';
const UA = 'BMSCompanionPhotoFetcher/1.0 (https://github.com/Scorpion-41/Falcon-BMS-Companion; Reference pictures)';
const API = 'https://commons.wikimedia.org/w/api.php';

/** Public domain (U.S. government works included), CC0, CC BY and CC BY-SA in any version; never NC or ND. */
export function licenceAllowed(lic) {
  const l = String(lic ?? '').trim();
  if (!l || /\bNC\b|\bND\b|non-?commercial|no-?deriv/i.test(l)) return false;
  return /^(public domain|pd\b|pd[- ]|cc0|cc[- ]by(-sa)?([- ]\d(\.\d)?)?( [a-z]+)?$)/i.test(l);
}

const KIND_PREFIX = { pic: '', aircraft: 'ac-', weapon: 'wp-', enc: 'en-', threat: 'th-' };
export function photoId(target) {
  const i = target.indexOf(':');
  const kind = target.slice(0, i), name = target.slice(i + 1);
  if (!(kind in KIND_PREFIX)) throw new Error(`photos.json: unknown target kind in "${target}"`);
  return 'ph-' + KIND_PREFIX[kind] + slug(name);
}

const readJson = (f, dflt) => (fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')) : dflt);
const targets = (photos) => Object.keys(photos).filter((k) => !k.startsWith('_'));

/**
 * Puts the fetched photos in place: every record showing a replaced BMS picture shows the photo instead, and each
 * entry named directly gets its own. Only photos with a recorded credit and a file on disk count. Returns the BMS
 * picture ids no longer shown (lower case) and the photo ids in use.
 */
export function applyPhotos({ acList, wpList, encList, threats = {} }) {
  const photos = readJson(PHOTOS_FILE, {});
  const credits = readJson(CREDITS_FILE, {});
  const own = (t) => credits[t]?.file && credits[t].file === photos[t]?.file && fs.existsSync(path.join(PHOTO_DIR, credits[t].id + '.webp'));
  // `same`: the photo another target fetched (one picture shown by two entries, fetched and shipped once)
  const creditOf = (t) => (photos[t]?.same ? (own(photos[t].same) ? credits[photos[t].same] : null) : own(t) ? credits[t] : null);
  const live = (t) => !!creditOf(t);
  const byPic = new Map();
  const used = new Set();
  for (const t of targets(photos)) if (t.startsWith('pic:') && live(t)) byPic.set(t.slice(4).toLowerCase(), creditOf(t).id);
  const swap = (r) => { const p = r.pic && byPic.get(r.pic.toLowerCase()); if (p) { r.pic = p; used.add(p); } };
  for (const l of [acList, wpList, encList]) l.forEach(swap);
  const lists = { aircraft: acList, weapon: wpList, enc: encList };
  const allThreats = Object.values(threats).flatMap((o) => o.threats || []);
  for (const t of targets(photos)) {
    if (t.startsWith('pic:') || !live(t)) continue;
    const kind = t.slice(0, t.indexOf(':')), key = t.slice(t.indexOf(':') + 1);
    const hits = kind === 'threat' ? allThreats.filter((x) => x.id === key) : lists[kind].filter((x) => x.key === key);
    if (!hits.length) { console.warn(`photos.json: ${t} names no ${kind} this run has`); continue; }
    for (const h of hits) h.pic = creditOf(t).id;
    used.add(creditOf(t).id);
  }
  const unused = [...byPic.keys()].filter((k) => !used.has(byPic.get(k)));
  if (unused.length) console.warn(`photos.json: no entry shows BMS picture(s) ${unused.join(', ')}`);
  return { replaced: new Set(byPic.keys()), used };
}

// ---- fetching ----
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let lastRequest = 0;
async function get(url, binary = false) {
  for (let attempt = 0; attempt < 5; attempt++) {
    const wait = lastRequest + 1000 - Date.now();
    if (wait > 0) await sleep(wait);
    lastRequest = Date.now();
    const r = await fetch(url, { headers: { 'User-Agent': UA } });
    if (r.status === 429 || r.status >= 500) { await sleep(5000 * (attempt + 1)); continue; }
    if (!r.ok) throw new Error(`${r.status} ${url}`);
    return binary ? Buffer.from(await r.arrayBuffer()) : r.json();
  }
  throw new Error(`gave up on ${url}`);
}
const text = (html) => String(html ?? '').replace(/<[^>]*>/g, '').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#0?39;/g, "'").replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();

/** The width to fetch so the band cut out of it is still at least 800 px wide: a standard rendition, or the original. */
function fetchWidth(rec, W) {
  const share = rec.crop ? rec.crop[2] : 1 / (rec.zoom ?? 1);
  const need = WIDTH / share;
  return [1280, 1920, 2560, 3840].find((b) => b >= need && b < W) ?? W;
}

async function fileInfo(title, width = 1280) {
  const q = `${API}?action=query&format=json&titles=${encodeURIComponent(title)}&prop=imageinfo&iiprop=url|size|mime|extmetadata&iiurlwidth=${width}` +
    '&iiextmetadatafilter=LicenseShortName|LicenseUrl|Artist|Credit|Attribution|AttributionRequired';
  const j = await get(q);
  const page = Object.values(j.query?.pages || {})[0];
  return page?.imageinfo?.[0] ? { page, ii: page.imageinfo[0] } : null;
}

/** The author as the file page credits them; a template's boilerplate (no author named) falls through to the next field. */
function author(m, rec) {
  if (rec.author) return rec.author;
  const junk = (s) => !s || /copyright status|tag does not indicate|unknown author/i.test(s);
  const a = [text(m.Attribution?.value), text(m.Artist?.value), text(m.Credit?.value)].find((s) => !junk(s)) ?? 'Unknown author';
  const clean = a.replace(/^User:/, '').replace(/\s*\(talk\)\s*$/, '');
  return clean.length > 100 ? clean.slice(0, 97) + '…' : clean;
}

async function makePicture(src, rec, dst) {
  const meta = await sharp(src).metadata();
  const W = meta.width, H = meta.height;
  let x, y, w, h;
  if (rec.crop) [x, y, w, h] = [rec.crop[0] * W, rec.crop[1] * H, rec.crop[2] * W, rec.crop[3] * H];
  else {
    w = W / (rec.zoom ?? 1); h = w / BAND;
    if (h > H) { h = H; w = h * BAND; }
    x = Math.min(Math.max(0, (rec.fx ?? 0.5) * W - w / 2), W - w);
    y = Math.min(Math.max(0, (rec.focus ?? 0.5) * H - h / 2), H - h);
  }
  const box = { left: Math.round(x), top: Math.round(y), width: Math.round(w), height: Math.round(h) };
  const outW = Math.min(WIDTH, box.width);
  await sharp(src).extract(box).resize({ width: outW }).webp({ quality: QUALITY, effort: 6 }).toFile(dst);
  return { width: outW, sourceWidth: box.width };
}

async function main() {
  const args = process.argv.slice(2);
  const force = args.includes('--force');
  const only = new Set(args.filter((a) => !a.startsWith('--')));
  const photos = readJson(PHOTOS_FILE, {});
  const credits = readJson(CREDITS_FILE, {});
  fs.mkdirSync(CACHE, { recursive: true });
  fs.mkdirSync(PHOTO_DIR, { recursive: true });
  let made = 0, kept = 0, refused = 0, bytes = 0;
  for (const t of targets(photos)) {
    const rec = photos[t];
    if (rec.same) { if (!photos[rec.same]) console.warn(`${t}: same as ${rec.same}, which photos.json does not have`); continue; }
    const id = photoId(t);
    const dst = path.join(PHOTO_DIR, id + '.webp');
    const recipe = crypto.createHash('sha1').update(JSON.stringify([rec.file, rec.crop, rec.focus, rec.fx, rec.zoom, rec.author, WIDTH, QUALITY])).digest('hex').slice(0, 12);
    if ((only.size && !only.has(t)) || (!force && credits[t]?.recipe === recipe && fs.existsSync(dst))) {
      if (fs.existsSync(dst)) bytes += fs.statSync(dst).size;
      kept++;
      continue;
    }
    let info = await fileInfo(rec.file).catch((e) => { console.warn(t, e.message); return null; });
    const width = info && fetchWidth(rec, info.ii.width);
    if (info && width > 1280 && width < info.ii.width) info = await fileInfo(rec.file, width).catch(() => info);
    if (!info) { console.warn(`${t}: ${rec.file} not found on Commons`); refused++; delete credits[t]; continue; }
    const m = info.ii.extmetadata || {};
    const licence = text(m.LicenseShortName?.value);
    if (!licenceAllowed(licence) || !/jpeg|png|webp|tiff/.test(info.ii.mime)) {
      console.warn(`${t}: ${rec.file} refused (licence "${licence}", ${info.ii.mime})`);
      refused++; delete credits[t]; if (fs.existsSync(dst)) fs.rmSync(dst); continue;
    }
    const url = info.ii.thumburl && info.ii.thumbwidth < info.ii.width ? info.ii.thumburl : info.ii.url;
    const cached = path.join(CACHE, crypto.createHash('sha1').update(url).digest('hex').slice(0, 16) + path.extname(new URL(url).pathname).toLowerCase());
    if (!fs.existsSync(cached)) fs.writeFileSync(cached, await get(url, true));
    const out = await makePicture(cached, rec, dst);
    if (out.sourceWidth < 640) console.warn(`${t}: only ${out.sourceWidth} px wide after cropping`);
    credits[t] = {
      id, subject: rec.subject, file: info.page.title, source: info.ii.descriptionurl, author: author(m, rec), licence,
      licenceUrl: text(m.LicenseUrl?.value) || null, changes: CHANGES, recipe,
    };
    bytes += fs.statSync(dst).size;
    made++;
    console.log(`${t} -> ${id}.webp ${(fs.statSync(dst).size / 1024).toFixed(0)} KB (${licence}, ${credits[t].author})`);
  }
  for (const t of Object.keys(credits)) if (!(t in photos) || photos[t].same) { const f = path.join(PHOTO_DIR, credits[t].id + '.webp'); if (fs.existsSync(f)) fs.rmSync(f); delete credits[t]; }
  const sorted = Object.fromEntries(Object.keys(credits).sort().map((k) => [k, credits[k]]));
  fs.writeFileSync(CREDITS_FILE, JSON.stringify(sorted, null, 1) + '\n');
  const list = Object.values(sorted).map(({ id, subject, author: a, licence, licenceUrl, source }) => ({ id, subject, author: a, licence, licenceUrl, source }))
    .sort((a, b) => a.subject.localeCompare(b.subject, 'en', { numeric: true }));
  writeJson(APP_CREDITS, { source: 'Wikimedia Commons', photos: list });
  console.log(`photos: ${made} made, ${kept} kept, ${refused} refused; ${list.length} credited, ${(bytes / 1048576).toFixed(1)} MB`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
