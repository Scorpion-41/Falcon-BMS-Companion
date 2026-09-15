// Builds all app assets from the (read-only) Falcon BMS install.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { loadTheaters } from './theaters.mjs';
import { buildCatalog } from './catalog.mjs';
import { buildAirports } from './airports.mjs';
import { loadDb } from './db.mjs';
import { terrainInfo } from './terrain.mjs';
import { findTacRefImage, tgaToWebp } from './images.mjs';
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
const TACREF_CATS = {
  8100: 'Aircraft', 8200: 'Ground Units', 8300: 'Ships', 8400: 'Missiles', 8500: 'Bombs', 8600: 'Stores & Pods', 8700: 'Other',
};
const TACREF_SUBCATS = {
  8110: 'Fighters', 8120: 'Multirole', 8130: 'Attack', 8140: 'Bombers', 8150: 'Helicopters', 8160: 'Support & EW', 8170: 'Transports',
  8210: 'Tanks', 8220: 'IFV / APC', 8230: 'Artillery', 8240: 'SAM Systems', 8250: 'AAA', 8260: 'Radars', 8270: 'Support Vehicles',
  8310: 'Carriers', 8320: 'Cruisers', 8330: 'Frigates', 8340: 'Destroyers', 8350: 'Submarines', 8360: 'Amphibious & Patrol', 8370: 'Civilian',
  8410: 'IR Air-to-Air', 8420: 'Radar Air-to-Air', 8430: 'Anti-Ship', 8440: 'Anti-Radiation', 8450: 'Air-to-Ground', 8460: 'Surface-to-Air', 8470: 'Anti-Tank',
  8510: 'General Purpose', 8520: 'Laser Guided', 8530: 'Guided (GPS/TV/Glide)', 8540: 'Cluster', 8550: 'Incendiary / FAE', 8560: 'Special Purpose', 8570: 'Nuclear',
  8610: 'Fuel Tanks', 8620: 'Recon Pods', 8630: 'ECM Pods', 8640: 'Countermeasure Pods', 8650: 'Targeting Pods', 8660: 'Rocket Pods', 8670: 'Nav / Datalink / Training Pods',
  8710: 'Other',
};

function familyTitle(names) {
  const rx = /^(F\/A-18|Mirage 2000|Mirage F1|Mirage III|Tornado|Jaguar|Harrier|Typhoon|Eurofighter|Rafale|[A-Za-z]{1,4}-\d+|[A-Z][a-z]+ ?\d*)/;
  const counts = new Map();
  for (const n of names) { const m = n.match(rx); const k = m ? m[1] : n; counts.set(k, (counts.get(k) || 0) + 1); }
  return [...counts.entries()].sort((a, b) => b[1] - a[1])[0][0];
}

async function main() {
  for (const d of ['airports', 'radio', 'curated']) fs.rmSync(path.join(OUT, d), { recursive: true, force: true });
  fs.mkdirSync(OUT, { recursive: true });
  fs.mkdirSync(IMG, { recursive: true });
  fs.mkdirSync(MAPS, { recursive: true });
  const theaters = loadTheaters();
  const aircraft = new Map(); // key -> { base, variants: Map(hash -> variant) }
  const weapons = new Map();
  const encyclopedia = new Map(); // key -> {entry, hash}
  const airportSets = new Map();
  const radioSets = new Map();
  const maps = new Map();
  const imageJobs = new Map(); // pic -> source tga
  const theaterIndex = [];

  for (const th of theaters) {
    const t0 = Date.now();
    const db = loadDb(th);
    const { aircraft: acs, weapons: wps } = buildCatalog(th);
    const { airports, navaids, radio, places } = buildAirports(th);

    // --- encyclopedia (TacRef) ---
    const tacKeyByNum = new Map();
    for (const e of db.tacref.values()) {
      const content = { name: e.name, cat: e.category, sub: e.subCategory, pic: e.pic, sections: e.sections.map((s) => ({ title: s.title, lines: s.lines.filter((l) => l !== '') })), description: e.description, rwr: e.rwr?.name && e.rwr.name !== 'No Radar' ? e.rwr.name : null };
      const h = hash(content);
      let key = slug(e.name) + '-' + e.category;
      const existing = encyclopedia.get(key);
      if (existing && existing.hash !== h) {
        const alt = key + '-' + th.id;
        key = alt;
      }
      if (!encyclopedia.has(key)) encyclopedia.set(key, { hash: h, entry: { key, ...content, catName: TACREF_CATS[e.category] || 'Other', subName: TACREF_SUBCATS[e.subCategory] || null, theaters: [] } });
      encyclopedia.get(key).entry.theaters.push(th.id);
      tacKeyByNum.set(e.num, key);
      if (e.pic && !imageJobs.has(e.pic.toLowerCase())) {
        const src = findTacRefImage(th, e.pic);
        if (src) imageJobs.set(e.pic.toLowerCase(), src);
      }
    }

    // --- weapons ---
    for (const w of wps) {
      const rec = { ...w, tacref: w.tacref != null ? tacKeyByNum.get(w.tacref) ?? null : null };
      delete rec.wid;
      if (!weapons.has(w.key)) weapons.set(w.key, { ...rec, theaters: [], carriedBy: new Set() });
      weapons.get(w.key).theaters.push(th.id);
    }

    // --- aircraft ---
    for (const a of acs) {
      const spec = {
        datFile: a.datFile, crew: a.crew, inService: a.inService, maxSpeedKts: a.maxSpeedKts, ceilingFt: a.ceilingFt, cruiseAltFt: a.cruiseAltFt,
        maxWeightLbs: a.maxWeightLbs, emptyWeightLbs: a.emptyWeightLbs, internalFuelLbs: a.internalFuelLbs, rcs: a.rcs, radar: a.radar, fm: a.fm,
      };
      if (/placeholder|^aircraft$/i.test(a.name)) continue;
      const variant = { spec, gun: a.gun, stations: a.stations.map((s) => ({ n: s.n, label: s.label, list: s.list || null, fixed: !!s.fixed, weapons: s.weapons })) };
      const vh = hash(variant);
      if (!aircraft.has(a.key)) aircraft.set(a.key, { key: a.key, name: a.name, family: a.family, role: a.role, pic: a.pic, tacref: a.tacref != null ? tacKeyByNum.get(a.tacref) ?? null : null, variants: new Map() });
      const ac = aircraft.get(a.key);
      if (!ac.pic && a.pic) ac.pic = a.pic;
      if (!ac.variants.has(vh)) ac.variants.set(vh, { ...variant, theaters: [] });
      ac.variants.get(vh).theaters.push(th.id);
      for (const s of a.stations) for (const w of s.weapons) weapons.get(w.key)?.carriedBy.add(a.key);
      if (a.gun) weapons.get(a.gun.weapon)?.carriedBy.add(a.key);
    }

    // --- airports / radio (deduped sets) ---
    const aset = { airports, navaids, places };
    const ah = 'ap-' + hash(aset);
    if (!airportSets.has(ah)) airportSets.set(ah, aset);
    const rh = 'rm-' + hash(radio);
    if (!radioSets.has(rh)) radioSets.set(rh, radio);

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
    theaterIndex.push({
      id: th.id, name: th.name, desc: th.desc, addon: th.addon, sizeFt: ti?.sizeFt ?? 3358700, map: mapFile, mapId: mapFile?.split('/')[1] ?? null,
      airportSet: ah, radioSet: rh, airportCount: airports.length, aircraftCount: acs.length,
      primary, mapGroup: ti ? ti.bil : th.id,
    });
    console.log(`${th.id}: ${acs.length} aircraft, ${wps.length} weapons, ${airports.length} airports, ${db.tacref.size} tacref (${Date.now() - t0}ms)`);
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
  const usedAp = new Set(theaterIndex.filter((t) => t.primary).map((t) => t.airportSet));
  const usedRm = new Set(theaterIndex.filter((t) => t.primary).map((t) => t.radioSet));
  for (const k of [...airportSets.keys()]) if (!usedAp.has(k)) airportSets.delete(k);
  for (const k of [...radioSets.keys()]) if (!usedRm.has(k)) radioSets.delete(k);

  // --- finalize aircraft ---
  const acList = [...aircraft.values()].map((a) => ({ ...a, variants: [...a.variants.values()] }));
  const byFamily = new Map();
  for (const a of acList) { const f = a.family || a.name; (byFamily.get(f) || byFamily.set(f, []).get(f)).push(a.name); }
  for (const a of acList) a.familyTitle = familyTitle(byFamily.get(a.family || a.name));
  const famPic = new Map();
  for (const a of acList) if (a.pic && !famPic.has(a.familyTitle)) famPic.set(a.familyTitle, a.pic);
  for (const a of acList) if (!a.pic) a.pic = famPic.get(a.familyTitle) ?? null;
  acList.sort((a, b) => a.familyTitle.localeCompare(b.familyTitle) || a.name.localeCompare(b.name, 'en', { numeric: true }));
  writeJson(path.join(OUT, 'aircraft.json'), acList);

  const wpList = [...weapons.values()].map((w) => ({ ...w, carriedBy: [...w.carriedBy].sort() }));
  wpList.sort((a, b) => a.name.localeCompare(b.name, 'en', { numeric: true }));
  writeJson(path.join(OUT, 'weapons.json'), wpList);

  const encList = [...encyclopedia.values()].map((e) => e.entry);
  encList.sort((a, b) => a.name.localeCompare(b.name, 'en', { numeric: true }));
  writeJson(path.join(OUT, 'encyclopedia.json'), encList);

  fs.mkdirSync(path.join(OUT, 'airports'), { recursive: true });
  for (const [k, v] of airportSets) writeJson(path.join(OUT, 'airports', k + '.json'), v);
  fs.mkdirSync(path.join(OUT, 'radio'), { recursive: true });
  for (const [k, v] of radioSets) writeJson(path.join(OUT, 'radio', k + '.json'), v);

  // --- curated docs (threat guide, hotas, checklists, comms, harm, rwr) ---
  fs.mkdirSync(path.join(OUT, 'curated'), { recursive: true });
  const curated = [];
  for (const f of fs.existsSync(CURATED) ? fs.readdirSync(CURATED) : []) {
    if (!f.endsWith('.json')) continue;
    const obj = JSON.parse(fs.readFileSync(path.join(CURATED, f), 'utf8'));
    writeJson(path.join(OUT, 'curated', f), obj);
    curated.push(f);
  }

  writeJson(path.join(OUT, 'index.json'), {
    bmsVersion: '4.38', generated: new Date().toISOString(), theaters: theaterIndex, curated,
    tacrefCategories: TACREF_CATS, tacrefSubcategories: TACREF_SUBCATS,
    counts: { aircraft: acList.length, weapons: wpList.length, encyclopedia: encList.length, airportSets: airportSets.size, theaters: theaterIndex.length },
  });

  // --- images ---
  // theater maps (all styles and tile levels) and their landmark layers: node src/maps.mjs, then node src/geo.mjs
  for (const { file } of maps.values()) if (!fs.existsSync(path.join(ASSETS, file))) console.warn('map missing, run node src/maps.mjs:', file);
  if (!skipImages) {
    let n = 0;
    for (const [pic, src] of imageJobs) {
      const dst = path.join(IMG, pic + '.webp');
      if (fs.existsSync(dst)) continue;
      try { await tgaToWebp(src, dst); n++; } catch (e) { console.warn('img fail', pic, e.message); }
    }
    console.log('converted images:', n, 'of', imageJobs.size);
  }
  console.log('done', { aircraft: acList.length, weapons: wpList.length, encyclopedia: encList.length, airportSets: airportSets.size, radioSets: radioSets.size, maps: maps.size });
}

main().catch((e) => { console.error(e); process.exit(1); });
