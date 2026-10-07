// Builds all app assets from the (read-only) Falcon BMS install.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { loadTheaters, readPptTable } from './theaters.mjs';
import { theaterProjection } from './projection.mjs';
import { buildReference, writePictures, withThreatPictures, TACREF_CATS, TACREF_SUBCATS } from './reference.mjs';
import { buildCatalog as buildCfgCatalog } from './cfgcatalog.mjs';
import { buildAirports } from './airports.mjs';
import { buildAirfields } from './airfields.mjs';
import { loadDb } from './db.mjs';
import { terrainInfo } from './terrain.mjs';
import { wdpTerrain } from './wdpterrain.mjs';
import { applyPhotos } from './photos.mjs';
import { slug, writeJson } from './util.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../../..');
const ASSETS = path.join(ROOT, 'app/src/main/assets');
const OUT = path.join(ASSETS, 'data');
const IMG = path.join(ASSETS, 'img/tacref');
const MAPS = path.join(ASSETS, 'maps');
const CURATED = path.join(ROOT, 'tools/curated');

const hash = (o) => crypto.createHash('sha1').update(JSON.stringify(o)).digest('hex').slice(0, 10);
const skipImages = process.argv.includes('--no-images');
const DATA_DIR = path.join(process.env.BMS_ROOT || 'G:/Falcon BMS 4.38', 'Data');

/**
 * The version BMS's own executable carries (`Bin/x64/Falcon BMS.exe`, the VS_FIXEDFILEINFO block of its version
 * resource: 4.38.1.3315 for the December 2025 4.38.1), so index.json says which build the data was read from and
 * not only "4.38". Null when the file is not there or carries no version.
 */
function readBmsBuild() {
  const exe = path.join(path.dirname(DATA_DIR), 'Bin', 'x64', 'Falcon BMS.exe');
  if (!fs.existsSync(exe)) return null;
  const b = fs.readFileSync(exe);
  const sig = Buffer.from([0xbd, 0x04, 0xef, 0xfe]);
  for (let i = b.indexOf(sig); i >= 0; i = b.indexOf(sig, i + 4)) {
    if (i + 16 > b.length || b.readUInt32LE(i + 4) !== 0x00010000) continue; // dwStrucVersion is always 1.0
    const ms = b.readUInt32LE(i + 8), ls = b.readUInt32LE(i + 12);
    return `${ms >>> 16}.${ms & 0xffff}.${ls >>> 16}.${ls & 0xffff}`;
  }
  return null;
}

async function main() {
  for (const d of ['airports', 'airfields', 'radio', 'ppt', 'curated']) fs.rmSync(path.join(OUT, d), { recursive: true, force: true });
  fs.mkdirSync(OUT, { recursive: true });
  fs.mkdirSync(IMG, { recursive: true });
  fs.mkdirSync(MAPS, { recursive: true });
  fs.mkdirSync(path.join(OUT, 'airfields'), { recursive: true });
  const theaters = loadTheaters();
  // encyclopedia, aircraft, weapons and their pictures (reference.mjs; referencerun.mjs builds them alone)
  const { acList, wpList, encList, aircraftCount, imageJobs } = buildReference(theaters);
  const airportSets = new Map();
  const airfieldFiles = new Map();   // af-hash -> one field's ground chart
  const airfieldIndexes = new Map(); // ai-hash -> { campId: af-hash }
  const airfieldWarnings = [];
  const radioSets = new Map();
  const pptSets = new Map(); // pp-hash -> a theater's PPT type table (Campaign/Ppt.ini)
  const maps = new Map();
  const theaterIndex = [];

  for (const th of theaters) {
    const t0 = Date.now();
    const db = loadDb(th);
    const { airports, navaids, radio, places, geo } = buildAirports(th);

    // --- airports / radio (deduped sets) ---
    const aset = { airports, navaids, places };
    const ah = 'ap-' + hash(aset);
    if (!airportSets.has(ah)) airportSets.set(ah, aset);
    const rh = 'rm-' + hash(radio);
    if (!radioSets.has(rh)) radioSets.set(rh, radio);

    // --- PPT types (Campaign/Ppt.ini: code, radius, name), shared between theaters with the same file ---
    const ppt = readPptTable(th);
    const ph = ppt?.rows.length ? 'pp-' + hash(ppt.rows) : null;
    if (ph && !pptSets.has(ph)) pptSets.set(ph, ppt.rows);

    // --- ground charts (one file per field, shared between theaters that fly the same terrain) ---
    const { fields, warnings } = await buildAirfields(th, airports, geo, db);
    airfieldWarnings.push(...warnings);
    const fieldIndex = {};
    for (const f of fields) {
      const fh = 'af-' + hash(f);
      if (!airfieldFiles.has(fh)) airfieldFiles.set(fh, f);
      fieldIndex[f.id] = fh;
    }
    const fih = 'ai-' + hash(fieldIndex);
    if (!airfieldIndexes.has(fih)) airfieldIndexes.set(fih, fieldIndex);

    // --- terrain map ---
    const ti = terrainInfo(th);
    let mapFile = null;
    if (ti) {
      if (!maps.has(ti.bil)) maps.set(ti.bil, { id: slug(path.basename(path.dirname(ti.dir))), file: `maps/${slug(path.basename(path.dirname(ti.dir)))}/relief.webp`, info: ti });
      mapFile = maps.get(ti.bil).file;
    }
    // A "main" theater ships its own terrain (base KTO, or an add-on whose terraindir is inside its own folder).
    // Add-ons that reuse another theater's terrain (LHTO, Hellas WCP, EF2000 BTO, Korea 2012 pack, LKTO…) are
    // grouped under that main theater for airfields/maps, but keep their own aircraft & weapon data.
    const ownAddon = th.tdf.match(/^(Add-On [^\\/]+)/i)?.[1]?.toLowerCase();
    const terrRel = path.relative(DATA_DIR, th.terrainDir).split(path.sep).join('/').toLowerCase();
    const primary = !th.addon ? true : !!ownAddon && terrRel.startsWith(ownAddon + '/');
    const wt = wdpTerrain(th);
    theaterIndex.push({
      id: th.id, name: th.name, desc: th.desc, addon: th.addon, sizeFt: ti?.sizeFt ?? 3358700, map: mapFile, mapId: mapFile?.split('/')[1] ?? null,
      airportSet: ah, radioSet: rh, airfieldSet: fields.length ? fih : null, airportCount: airports.length, aircraftCount: aircraftCount.get(th.id) ?? 0,
      primary, mapGroup: ti ? ti.bil : th.id,
      // BMS's own projection for this terrain (NewTerrain/Theater.txt), which is what turns theater feet into lat/lon
      projection: theaterProjection(th.terrainDir),
      pptSet: ph, radioCount: radio.length,
      // what Weapon Delivery Planner reads to print lat/lon (the port sets its projection up from these)
      ...(wt ? { wdpTerrain: wt } : {}),
    });
    console.log(`${th.id}: ${aircraftCount.get(th.id) ?? 0} aircraft, ${airports.length} airports, ${fields.length} ground charts, ${db.tacref.size} tacref (${Date.now() - t0}ms)`);
  }

  // group add-on theaters under their main theater
  for (const t of theaterIndex) {
    const main = theaterIndex.find((m) => m.primary && m.mapGroup === t.mapGroup) || (t.mapGroup && theaterIndex.find((m) => m.primary && m.id === 'korea-kto'));
    t.mainTheater = t.primary ? t.id : main?.id ?? t.id;
  }
  for (const t of theaterIndex) {
    if (t.primary) t.includes = theaterIndex.filter((o) => !o.primary && o.mainTheater === t.id).map((o) => o.name);
    delete t.mapGroup;
  }
  // Every theater's own airport and radio set is written, add-ons included. Keeping only the primary theaters' sets
  // (as this did up to 1.3.7) left Korea TvT, the six Korea 2012 theaters and EF2000 BTO naming files that were
  // never shipped, so the Planner flew them with another theater's airports or none.
  //
  // What the Planner reads from a theater, and whether this run could build it. `missing` names each part that is
  // not there; `node src/plannercheck.mjs` fails on any theater whose list is not empty, so a new theater that
  // needs more than a re-run of this extractor is caught here rather than in the cockpit.
  for (const t of theaterIndex) {
    const missing = [];
    if (!t.airportCount) missing.push('airports');
    if (!t.radioCount) missing.push('radio');
    if (!t.airfieldSet) missing.push('airfields');
    if (!t.mapId) missing.push('map');
    if (!t.projection) missing.push('projection');
    if (!t.pptSet) missing.push('ppt');
    t.planner = { ok: missing.length === 0, missing };
    delete t.radioCount;
  }

  // threat_pictures.json reaches the app as each threat's `tacref` (withThreatPictures), and the photographs from
  // Wikimedia Commons (photos.mjs) stand in for BMS's pictures and give entries without one theirs (applyPhotos)
  const threatPictures = JSON.parse(fs.readFileSync(path.join(CURATED, 'threat_pictures.json'), 'utf8'));
  const threats = {};
  for (const f of fs.readdirSync(CURATED).filter((f) => /^threats_.*\.json$/.test(f))) {
    threats[f] = withThreatPictures(f, JSON.parse(fs.readFileSync(path.join(CURATED, f), 'utf8')), threatPictures, encList);
  }
  const { replaced: photoReplaced } = applyPhotos({ acList, wpList, encList, threats });
  writeJson(path.join(OUT, 'aircraft.json'), acList);
  writeJson(path.join(OUT, 'weapons.json'), wpList);
  writeJson(path.join(OUT, 'encyclopedia.json'), encList);

  fs.mkdirSync(path.join(OUT, 'airports'), { recursive: true });
  for (const [k, v] of airportSets) writeJson(path.join(OUT, 'airports', k + '.json'), v);
  fs.mkdirSync(path.join(OUT, 'airfields'), { recursive: true });
  for (const [k, v] of airfieldFiles) writeJson(path.join(OUT, 'airfields', k + '.json'), v);
  for (const [k, v] of airfieldIndexes) writeJson(path.join(OUT, 'airfields', k + '.json'), v);
  if (airfieldWarnings.length) {
    console.log(`
ground charts: ${airfieldWarnings.length} field(s) disagree with the airport record and need looking at:`);
    for (const w of airfieldWarnings) console.log('  ! ' + w);
  } else {
    console.log(`
ground charts: ${airfieldFiles.size} fields, all agreeing with their airport records`);
  }
  fs.mkdirSync(path.join(OUT, 'radio'), { recursive: true });
  for (const [k, v] of radioSets) writeJson(path.join(OUT, 'radio', k + '.json'), v);
  fs.mkdirSync(path.join(OUT, 'ppt'), { recursive: true });
  for (const [k, v] of pptSets) writeJson(path.join(OUT, 'ppt', k + '.json'), v);

  // --- curated docs (threat guide, hotas, checklists, comms, harm, rwr) ---
  fs.mkdirSync(path.join(OUT, 'curated'), { recursive: true });
  const curated = [];
  // carriers.json (ships.mjs) and cfgnotes.json (cfgcatalog.mjs) are this extractor's own inputs: what they say
  // reaches the app inside the ground charts and the config catalogue, so the files themselves are not shipped
  // threat_pictures.json reaches the app as each threat's `tacref`, photos.json as pictures and photo_credits.json as
  // data/credits/photos.json (photos.mjs); the threats were read above
  const EXTRACTOR_ONLY = new Set(['carriers.json', 'cfgnotes.json', 'threat_pictures.json', 'photos.json', 'photo_credits.json']);
  for (const f of fs.existsSync(CURATED) ? fs.readdirSync(CURATED) : []) {
    if (!f.endsWith('.json') || EXTRACTOR_ONLY.has(f)) continue;
    let obj = JSON.parse(fs.readFileSync(path.join(CURATED, f), 'utf8'));
    if (f.startsWith('threats_')) obj = threats[f];
    writeJson(path.join(OUT, 'curated', f), obj);
    curated.push(f);
  }

  // --- the config catalogue: every option this version has, out of BMS's own stock Falcon BMS.cfg ---
  const cfgOptions = buildCfgCatalog();
  fs.mkdirSync(path.join(OUT, 'cfg'), { recursive: true });
  writeJson(path.join(OUT, 'cfg', 'options.json'), { version: '4.38', options: cfgOptions });
  console.log(`config options: ${cfgOptions.length}`);

  const bmsBuild = readBmsBuild();
  writeJson(path.join(OUT, 'index.json'), {
    bmsVersion: bmsBuild?.split('.').slice(0, 2).join('.') ?? '4.38', bmsBuild, generated: new Date().toISOString(), theaters: theaterIndex, curated,
    tacrefCategories: TACREF_CATS, tacrefSubcategories: TACREF_SUBCATS,
    counts: { aircraft: acList.length, weapons: wpList.length, encyclopedia: encList.length, airportSets: airportSets.size, radioSets: radioSets.size, pptSets: pptSets.size, theaters: theaterIndex.length },
  });

  // --- images ---
  // theater maps (all styles and tile levels) and their landmark layers: node src/maps.mjs, then node src/geo.mjs
  for (const { file } of maps.values()) if (!fs.existsSync(path.join(ASSETS, file))) console.warn('map missing, run node src/maps.mjs:', file);
  if (!skipImages) await writePictures(imageJobs, IMG, photoReplaced);
  console.log('done', { bmsBuild, aircraft: acList.length, weapons: wpList.length, encyclopedia: encList.length, airportSets: airportSets.size, radioSets: radioSets.size, pptSets: pptSets.size, maps: maps.size });
  const notReady = theaterIndex.filter((t) => !t.planner.ok);
  if (notReady.length) console.log('Planner data missing:', notReady.map((t) => `${t.id} (${t.planner.missing.join(', ')})`).join('; '));
}

main().catch((e) => { console.error(e); process.exit(1); });
