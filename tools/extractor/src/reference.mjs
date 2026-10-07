// The Reference section's data: the TacRef encyclopedia, the aircraft and weapons catalogue, their pictures, and the
// pictures of the threat guide. Used by main.mjs (a full run) and referencerun.mjs (this part alone).
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { buildCatalog, isPlaceholderName } from './catalog.mjs';
import { loadDb } from './db.mjs';
import { findTacRefImage, isPlaceholderTga, tgaToWebp } from './images.mjs';
import { DATA, slug } from './util.mjs';

const hash = (o) => crypto.createHash('sha1').update(JSON.stringify(o)).digest('hex').slice(0, 10);

export const TACREF_CATS = {
  8100: 'Aircraft', 8200: 'Ground Units', 8300: 'Ships', 8400: 'Missiles', 8500: 'Bombs', 8600: 'Stores & Pods', 8700: 'Other',
};
export const TACREF_SUBCATS = {
  8110: 'Fighters', 8120: 'Multirole', 8130: 'Attack', 8140: 'Bombers', 8150: 'Helicopters', 8160: 'Support & EW', 8170: 'Transports',
  8210: 'Tanks', 8220: 'IFV / APC', 8230: 'Artillery', 8240: 'SAM Systems', 8250: 'AAA', 8260: 'Radars', 8270: 'Support Vehicles',
  8310: 'Carriers', 8320: 'Cruisers', 8330: 'Frigates', 8340: 'Destroyers', 8350: 'Submarines', 8360: 'Amphibious & Patrol', 8370: 'Civilian',
  8410: 'IR Air-to-Air', 8420: 'Radar Air-to-Air', 8430: 'Anti-Ship', 8440: 'Anti-Radiation', 8450: 'Air-to-Ground', 8460: 'Surface-to-Air', 8470: 'Anti-Tank',
  8510: 'General Purpose', 8520: 'Laser Guided', 8530: 'Guided (GPS/TV/Glide)', 8540: 'Cluster', 8550: 'Incendiary / FAE', 8560: 'Special Purpose', 8570: 'Nuclear',
  8610: 'Fuel Tanks', 8620: 'Recon Pods', 8630: 'ECM Pods', 8640: 'Countermeasure Pods', 8650: 'Targeting Pods', 8660: 'Rocket Pods', 8670: 'Nav / Datalink / Training Pods',
  8710: 'Other',
};

/*
 * Where BMS's own data puts another thing's picture on an entry, reviewed by eye (1.3.9) and keyed by name and the
 * picture, so an exception lapses by itself once BMS changes either.
 *
 * A TacRef entry whose PicName is another type's photo: the entry shows no picture.
 */
const ENTRY_PICTURE_SLIPS = { 'MR.2 Nimrod': 'f35c', 'MLRS Family of Munition': 'm72' };
// An aircraft or store whose class-table row BMS links to another type's entry: neither the picture nor the link.
const LINKED_TO_ANOTHER_TYPE = {
  'J-15': 'SU37', 'MiG-17PF': 'MIG19PM', 'Su-30MKK': 'J16', 'GR.9': 'AV8B', 'AV-8B+': 'gr9', 'Su-xx RPod': 'FRECON',
};
// Linked to its type's entry, whose photo shows another air arm's jet (the THK F-16 on BMS's "F-16C Block 40", the
// ROKAF F-4E, the U.S. Navy aggressor F-5E …): the link stays, the picture does not.
const PICTURED_IN_ANOTHER_NATION = {
  'F-16CM-40': 'F16C40', 'F-15A IAF': 'F15A', 'F-4E IAF': 'F4E', 'F-4E USAF': 'F4E', 'F-4E EAF': 'F4D', 'RF-4C': 'RF4C',
  'F-5E FAC': 'F5E', 'F-5E RJAF': 'F5E', 'F-5E ROKAF': 'F5E', 'Mirage F1EJ': 'MF1CT',
};
const slipped = (table, name, pic) => !!pic && table[name]?.toLowerCase() === pic.toLowerCase();

/*
 * Same type, same nation. Pictures whose markings were identified by eye (1.3.9; the add-on F-16s also by the name
 * BMS gives their entry): one may be shown for an aircraft with no picture of its own when the aircraft is of the
 * same type family (`type`) and its name names that nation's air arm (`NATION_WORDS`) — never across nations, and
 * never for a name that names two. `prefer` picks among a nation's pictures of the type (a Block 52 for a Block 52 …),
 * else the first listed. A picture showing no nation is not listed here: it follows the own-link rule only.
 */
const F16 = /\b(?:K?F-?16|F16)/i;
const NATION_PICTURES = [
  { pic: 'F16C40', nation: 'Turkey', type: F16 },
  { pic: 'F16C52+', nation: 'Greece', type: F16 },
  { pic: 'F16C52+CFT', nation: 'Greece', type: F16, prefer: /CFT|PXIV/ },
  { pic: 'KF16C52', nation: 'Korea', type: F16, prefer: /52/ },
  { pic: 'KF16C32', nation: 'Korea', type: F16, prefer: /3[02]/ },
  { pic: 'F-16C40EAF', nation: 'Egypt', type: F16, prefer: /40/ },
  { pic: 'F-16C32EAF', nation: 'Egypt', type: F16, prefer: /3[02]/ },
  { pic: 'F-16C52EAF', nation: 'Egypt', type: F16, prefer: /52/ },
  { pic: 'F-16ABMRJAF', nation: 'Jordan', type: F16 },
  { pic: 'F-16CCGIAF', nation: 'Israel', type: F16, prefer: /F-16C/ },
  { pic: 'F-16DDGIAF', nation: 'Israel', type: F16, prefer: /F-16D/ },
  { pic: 'F-16ABIAF', nation: 'Israel', type: F16, prefer: /F-16[AB]/ },
  { pic: 'F16AMRDAF', nation: 'Denmark', type: F16 },
  { pic: 'F16AMRNLAF', nation: 'Netherlands', type: F16 },
  { pic: 'F16AMRNoAF', nation: 'Norway', type: F16 },
  { pic: 'F16AMBE', nation: 'Belgium', type: F16 },
  { pic: 'F15CIAF', nation: 'Israel', type: /\bF-15/ },
  { pic: 'F15A', nation: 'USA', type: /\bF-15/, prefer: /F-15A/ },
  { pic: 'F15C', nation: 'USA', type: /\bF-15/, prefer: /F-15C/ },
  { pic: 'F4E', nation: 'Korea', type: /\bF-4/, prefer: /F-4E/ },
  { pic: 'F4D', nation: 'Korea', type: /\bF-4/, prefer: /F-4D/ },
  { pic: 'RF4C', nation: 'Japan', type: /\bRF-4/ },
  { pic: 'F5E', nation: 'USA', type: /\bF-5/ },
  { pic: 'MF1CT', nation: 'France', type: /\bMirage F1/i },
];
// an air arm in an aircraft's name → its nation (aggressor, "RED" and test units name none)
const NATION_WORDS = {
  HAF: 'Greece', PXII: 'Greece', PXIII: 'Greece', PXIV: 'Greece', THK: 'Turkey', TUAF: 'Turkey', IAF: 'Israel', IDF: 'Israel',
  ROKAF: 'Korea', ROK: 'Korea', JASDF: 'Japan', EAF: 'Egypt', RJAF: 'Jordan', USAF: 'USA', USN: 'USA', USMC: 'USA',
  RAF: 'UK', RN: 'UK', RDAF: 'Denmark', RNLAF: 'Netherlands', RNOAF: 'Norway', BAC: 'Belgium', BAF: 'Belgium', SYAF: 'Syria',
};
export function nationOf(name) {
  const found = new Set((name.match(/[A-Za-z]+/g) || []).map((w) => NATION_WORDS[w.toUpperCase()]).filter(Boolean));
  return found.size === 1 ? [...found][0] : null;
}
function samenationPicture(name) {
  const nation = nationOf(name);
  if (!nation) return null;
  const same = NATION_PICTURES.filter((p) => p.nation === nation && p.type.test(name));
  return (same.find((p) => p.prefer?.test(name)) ?? same[0])?.pic ?? null;
}

function familyTitle(names) {
  const rx = /^(F\/A-18|Mirage 2000|Mirage F1|Mirage III|Tornado|Jaguar|Harrier|Typhoon|Eurofighter|Rafale|[A-Za-z]{1,4}-\d+|[A-Z][a-z]+ ?\d*)/;
  const counts = new Map();
  for (const n of names) { const m = n.match(rx); const k = m ? m[1] : n; counts.set(k, (counts.get(k) || 0) + 1); }
  return [...counts.entries()].sort((a, b) => b[1] - a[1])[0][0];
}

/**
 * The picture files: one per TacRef picture name, from the first theater (the base theater's art) that has it. A
 * theater whose own art holds a different picture under the same name (Israel's MiG-21s, the Falklands' Eurofighter)
 * keeps its own as `<name>__<its add-on folder>`. A name no art folder has, or a picture of one flat colour (a
 * placeholder), gives no picture at all.
 */
function pictureFiles() {
  const jobs = new Map(); // image id (lower case) -> source tga
  const sums = new Map(); // source -> content hash
  const flat = new Map(); // source -> one flat colour (a placeholder: no picture)
  const sum = (f) => sums.get(f) ?? sums.set(f, crypto.createHash('sha1').update(fs.readFileSync(f)).digest('hex')).get(f);
  const idFor = (th, pic) => {
    if (!pic) return null;
    const src = findTacRefImage(th, pic);
    if (!src) return null;
    if (!flat.has(src)) flat.set(src, isPlaceholderTga(src));
    if (flat.get(src)) return null;
    const key = pic.toLowerCase();
    const have = jobs.get(key);
    if (!have) { jobs.set(key, src); return pic; }
    if (have === src || sum(have) === sum(src)) return pic;
    const own = key + '__' + slug(path.relative(DATA, src).split(path.sep)[0]);
    if (!jobs.has(own)) jobs.set(own, src);
    return own;
  };
  return { jobs, idFor };
}

/**
 * Builds encyclopedia, aircraft and weapons for every theater. An aircraft's or a weapon's picture is the one of the
 * TacRef entry BMS links to its own class-table row (`tacrefFor`), and nothing else: there is no picture of a sibling
 * variant — up to 1.3.8 every aircraft without its own took its family's first (every F-16 without one showed BMS's
 * "F-16C Block 40", a Turkish jet).
 */
export function buildReference(theaters) {
  const aircraft = new Map(); // key -> { base, variants: Map(hash -> variant) }
  const weapons = new Map();
  const encyclopedia = new Map(); // key -> {entry, hash}
  const aircraftCount = new Map(); // theater id -> its flyable aircraft
  const pics = pictureFiles();

  for (const th of theaters) {
    const db = loadDb(th);
    const { aircraft: acs, weapons: wps } = buildCatalog(th);
    aircraftCount.set(th.id, acs.length);

    // --- encyclopedia (TacRef) ---
    const tacKeyByNum = new Map();
    for (const e of db.tacref.values()) {
      if (isPlaceholderName(e.name)) continue; // an empty TacRef slot
      const pic = slipped(ENTRY_PICTURE_SLIPS, e.name, e.pic) ? null : pics.idFor(th, e.pic);
      const content = { name: e.name, cat: e.category, sub: e.subCategory, pic, sections: e.sections.map((s) => ({ title: s.title, lines: s.lines.filter((l) => l !== '') })), description: e.description, rwr: e.rwr?.name && e.rwr.name !== 'No Radar' ? e.rwr.name : null };
      const h = hash(content);
      let key = slug(e.name) + '-' + e.category;
      const existing = encyclopedia.get(key);
      if (existing && existing.hash !== h) key = key + '-' + th.id;
      if (!encyclopedia.has(key)) encyclopedia.set(key, { hash: h, entry: { key, ...content, catName: TACREF_CATS[e.category] || 'Other', subName: TACREF_SUBCATS[e.subCategory] || null, theaters: [] } });
      encyclopedia.get(key).entry.theaters.push(th.id);
      tacKeyByNum.set(e.num, key);
    }

    // --- weapons ---
    for (const w of wps) {
      const other = slipped(LINKED_TO_ANOTHER_TYPE, w.name, w.pic);
      const rec = { ...w, pic: other ? null : pics.idFor(th, w.pic), tacref: !other && w.tacref != null ? tacKeyByNum.get(w.tacref) ?? null : null };
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
      if (isPlaceholderName(a.name)) continue; // catalog.mjs leaves them out already; a second guard
      const variant = { spec, gun: a.gun, stations: a.stations.map((s) => ({ n: s.n, label: s.label, list: s.list || null, fixed: !!s.fixed, weapons: s.weapons })) };
      const vh = hash(variant);
      const other = slipped(LINKED_TO_ANOTHER_TYPE, a.name, a.pic);
      // an aircraft named like an entry whose picture is another type's (the MR.2 Nimrod's F-35C) shows none either
      const wrong = slipped(PICTURED_IN_ANOTHER_NATION, a.name, a.pic) || slipped(ENTRY_PICTURE_SLIPS, a.name, a.pic);
      const pic = other || wrong ? null : pics.idFor(th, a.pic);
      const tacref = !other && a.tacref != null ? tacKeyByNum.get(a.tacref) ?? null : null;
      if (!aircraft.has(a.key)) aircraft.set(a.key, { key: a.key, name: a.name, family: a.family, role: a.role, pic, tacref, variants: new Map() });
      const ac = aircraft.get(a.key);
      // the same aircraft (same name) in a theater that links it where the first did not
      if (!ac.pic && pic) { ac.pic = pic; ac.tacref = ac.tacref ?? tacref; }
      if (!ac.variants.has(vh)) ac.variants.set(vh, { ...variant, theaters: [] });
      ac.variants.get(vh).theaters.push(th.id);
      for (const s of a.stations) for (const w of s.weapons) weapons.get(w.key)?.carriedBy.add(a.key);
      if (a.gun) weapons.get(a.gun.weapon)?.carriedBy.add(a.key);
    }
  }

  const acList = [...aircraft.values()].map((a) => ({ ...a, variants: [...a.variants.values()] }));
  const byFamily = new Map();
  for (const a of acList) { const f = a.family || a.name; (byFamily.get(f) || byFamily.set(f, []).get(f)).push(a.name); }
  for (const a of acList) a.familyTitle = familyTitle(byFamily.get(a.family || a.name));
  // no picture of its own: one of the same type showing the same nation's air arm, if one is known
  const sameNation = [];
  for (const a of acList) {
    if (a.pic) continue;
    const pic = samenationPicture(a.name);
    if (pic && pics.jobs.has(pic.toLowerCase())) { a.pic = pic; sameNation.push(a.name); }
  }
  acList.sort((a, b) => a.familyTitle.localeCompare(b.familyTitle) || a.name.localeCompare(b.name, 'en', { numeric: true }));

  const wpList = [...weapons.values()].map((w) => ({ ...w, carriedBy: [...w.carriedBy].sort() }));
  wpList.sort((a, b) => a.name.localeCompare(b.name, 'en', { numeric: true }));

  const encList = [...encyclopedia.values()].map((e) => e.entry);
  encList.sort((a, b) => a.name.localeCompare(b.name, 'en', { numeric: true }));

  return { acList, wpList, encList, aircraftCount, imageJobs: pics.jobs, sameNation };
}

/**
 * Converts every picture not yet in `imgDir` (one webp per image id). A BMS picture a photograph now stands in for
 * everywhere (`replaced`, from photos.mjs's `applyPhotos`) is not shipped, and its old file is removed.
 */
export async function writePictures(imageJobs, imgDir, replaced = new Set()) {
  fs.mkdirSync(imgDir, { recursive: true });
  let n = 0;
  for (const [id, src] of imageJobs) {
    const dst = path.join(imgDir, id + '.webp');
    if (replaced.has(id)) { if (fs.existsSync(dst)) fs.rmSync(dst); continue; }
    if (fs.existsSync(dst)) continue;
    try { await tgaToWebp(src, dst); n++; } catch (e) { console.warn('img fail', id, e.message); }
  }
  console.log('converted images:', n, 'of', imageJobs.size);
  const used = new Set([...imageJobs.keys()].filter((k) => !replaced.has(k)).map((k) => k + '.webp'));
  const stale = fs.readdirSync(imgDir).filter((f) => !used.has(f) && !f.startsWith('ph-'));
  if (stale.length) console.log(`img/tacref: ${stale.length} picture(s) no entry names any more (left in place): ${stale.slice(0, 20).join(' ')}`);
}

/**
 * The threat guide's pictures: `tools/curated/threat_pictures.json` names, for each threat, the TacRef entry that is
 * the same thing (reviewed by hand: same type and variant, and no other nation's markings), or null. The copy of
 * each threats_*.json the app reads gets that name as `tacref`; a name the encyclopedia no longer has is dropped with
 * a warning. Up to 1.3.8 the app matched names by prefix, which gave the SA-2 the Fan Song radar, the Avenger the
 * M109 howitzer and the F-15C an Israeli Baz.
 */
export function withThreatPictures(file, threats, mapping, encList) {
  const names = new Set(encList.map((e) => e.name));
  for (const t of threats.threats || []) {
    const name = mapping[t.id] ?? null;
    if (name && !names.has(name)) console.warn(`${file}: ${t.id} names TacRef entry "${name}", which no theater has; no picture`);
    if (!(t.id in mapping)) console.warn(`${file}: ${t.id} is not in threat_pictures.json; no picture`);
    t.tacref = name && names.has(name) ? name : null;
  }
  return threats;
}
