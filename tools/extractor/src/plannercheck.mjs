/**
 * Does every theater carry what the Planner (Mission → Planner, the Weapon Delivery Planner port) reads?
 *
 *   node src/plannercheck.mjs [data dir]   (reads app/src/main/assets/data, or the folder given; BMS_ROOT, when
 *                                           it is there, is read too)
 *
 * The Planner takes everything theater-specific out of index.json and the files it names, and nothing out of code:
 * airports, runways, ILS, TACAN and tower frequencies (`airportSet`), radios by callsign (`radioSet`), ground
 * charts (`airfieldSet`), latitude and longitude (`projection`), and the PPT type table (`pptSet`). So a theater
 * BMS adds later needs only a re-run of the extractor, and this is the check that says the re-run was enough.
 * It fails (exit code 1) when:
 *   - a theater's `planner.missing` (the extractor's own list of what it could not build) is not empty;
 *   - a file any theater names is not in the assets, or is empty (the 1.3.7 extractor dropped every add-on
 *     theater's airport set, so eight of nineteen theaters named files that were never shipped);
 *   - a projection is not the WGS84 transverse Mercator the app knows how to invert, or it does not put the
 *     theater's own centre (Theater.txt's "Center latitude/longitude") in the middle of the theater square, or a
 *     1,024 km theater's lacks the heightmap length BMS's own latitude and longitude are built from;
 *   - an airport of the set lies outside its theater square (a set paired with the wrong theater);
 *   - with the install at hand, theater.lst lists a theater index.json does not have.
 * Nothing is written anywhere.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeProjector } from './projection.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ASSETS = path.resolve(HERE, '../../../app/src/main/assets');
const DATA = process.argv[2] ? path.resolve(process.argv[2]) : path.join(ASSETS, 'data');

const problems = [];
const bad = (id, what) => problems.push(`${id}: ${what}`);
// undefined = no such file, null = there but not JSON
const readJson = (rel) => {
  const f = path.join(DATA, rel);
  if (!fs.existsSync(f)) return undefined;
  try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return null; }
};

const index = readJson('index.json');
if (!index) { console.error(`FAIL: ${path.join(DATA, 'index.json')} is missing or does not parse`); process.exit(1); }
if (!index.bmsBuild) bad('index', 'no bmsBuild (the version of Falcon BMS.exe the data was read from)');

const rows = [];
for (const t of index.theaters || []) {
  const id = t.id;
  if (!t.planner) bad(id, 'no planner block: index.json was written by an older extractor');
  else if (t.planner.missing?.length) bad(id, `the extractor could not build: ${t.planner.missing.join(', ')}`);

  // airports: the file is there, holds airports, and they lie inside the theater
  const ap = t.airportSet ? readJson(`airports/${t.airportSet}.json`) : undefined;
  if (!t.airportSet) bad(id, 'no airportSet');
  else if (ap === undefined) bad(id, `airports/${t.airportSet}.json is not in the assets`);
  else if (!ap?.airports?.length) bad(id, `airports/${t.airportSet}.json holds no airports`);
  else {
    const size = t.sizeFt || 3358700;
    const out = ap.airports.filter((a) => !(a.x >= 0 && a.x <= size && a.y >= 0 && a.y <= size));
    if (out.length) bad(id, `${out.length} airport(s) outside the theater square, e.g. ${out[0].name}`);
    if (t.airportCount && ap.airports.length !== t.airportCount) bad(id, `airportCount ${t.airportCount} but the set holds ${ap.airports.length}`);
  }

  // radios by callsign
  const rm = t.radioSet ? readJson(`radio/${t.radioSet}.json`) : undefined;
  if (!t.radioSet) bad(id, 'no radioSet');
  else if (rm === undefined) bad(id, `radio/${t.radioSet}.json is not in the assets`);
  else if (!rm?.length) bad(id, `radio/${t.radioSet}.json is empty`);

  // ground charts: the index, and every chart it names
  const ai = t.airfieldSet ? readJson(`airfields/${t.airfieldSet}.json`) : undefined;
  if (!t.airfieldSet) bad(id, 'no airfieldSet');
  else if (ai === undefined) bad(id, `airfields/${t.airfieldSet}.json is not in the assets`);
  else {
    const absent = Object.values(ai || {}).filter((f) => !fs.existsSync(path.join(DATA, 'airfields', f + '.json')));
    if (absent.length) bad(id, `${absent.length} ground chart(s) named by ${t.airfieldSet} are not in the assets`);
  }

  // the map and its landmarks
  if (!t.mapId) bad(id, 'no mapId');
  else if (!fs.existsSync(path.join(DATA, 'geo', t.mapId + '.json'))) bad(id, `data/geo/${t.mapId}.json is not in the assets`);

  // the projection: one the app can invert, and one that agrees with the theater's own centre
  const p = t.projection;
  let centreErrFt = null;
  if (!p) bad(id, 'no projection');
  else if (p.type !== 'tmerc' || p.ellps !== 'WGS84') bad(id, `projection is ${p.type}/${p.ellps}, not a WGS84 transverse Mercator`);
  else if (![p.lon0, p.k0, p.x0, p.y0, p.ftPerM, p.sizeKm].every(Number.isFinite)) bad(id, 'projection has a parameter that is not a number');
  else if (p.lat0 !== 0) bad(id, `projection has lat_0=${p.lat0}; the app's projector assumes 0`);
  else if (p.centerLat == null || p.centerLon == null) bad(id, 'Theater.txt gives no centre to check the projection against');
  else {
    const c = makeProjector(p)(p.centerLat, p.centerLon);
    const half = (t.sizeFt || p.sizeKm * 1000 * p.ftPerM) / 2;
    centreErrFt = Math.hypot(c.x - half, c.y - half);
    // Theater.txt rounds its false northing to six figures (-3.74929e+06, i.e. to 10 m), so tens of feet are the
    // file's rounding; half a per cent of the theater (~3 nm) is a projection that belongs to another theater
    if (centreErrFt > 0.005 * 2 * half) bad(id, `the theater's centre projects ${Math.round(centreErrFt)} ft from the middle of the square`);
    // BMS's own latitude and longitude (its ACMI, its AIPs) are a grid over the size, the centre and the heightmap's
    // length (WdpCoords.bmsGrid, D26); without the length the Planner prints the projection string's, 140-220 m out
    if (p.sizeKm === 1024 && !(p.heightmapBytes > 0)) bad(id, 'no heightmap length in the projection: the Planner cannot print BMS\'s own latitude and longitude');
  }

  // the PPT type table
  const pp = t.pptSet ? readJson(`ppt/${t.pptSet}.json`) : undefined;
  if (!t.pptSet) bad(id, 'no pptSet');
  else if (pp === undefined) bad(id, `ppt/${t.pptSet}.json is not in the assets`);
  else if (!pp?.length) bad(id, `ppt/${t.pptSet}.json is empty`);

  rows.push([id, ap?.airports?.length ?? '-', rm?.length ?? '-', ai ? Object.keys(ai).length : '-', pp?.length ?? '-',
    centreErrFt == null ? '-' : Math.round(centreErrFt), t.planner?.ok ? 'ok' : 'NO']);
}

// with the install at hand: every theater BMS lists is in the index
const bmsRoot = process.env.BMS_ROOT || 'G:/Falcon BMS 4.38';
if (fs.existsSync(path.join(bmsRoot, 'Data/TerrData/TheaterDefinition/theater.lst'))) {
  const { loadTheaters } = await import('./theaters.mjs');
  const ids = new Set((index.theaters || []).map((t) => t.id));
  for (const th of loadTheaters()) if (!ids.has(th.id)) bad(th.id, `in ${bmsRoot}'s theater.lst but not in index.json: re-run the extractor`);
} else {
  console.log(`(no install at ${bmsRoot}: theater.lst not compared)`);
}

const head = ['theater', 'airports', 'radio', 'charts', 'ppt', 'centre ft', 'planner'];
const w = head.map((h, i) => Math.max(h.length, ...rows.map((r) => String(r[i]).length)));
const line = (r) => r.map((c, i) => String(c)[i ? 'padStart' : 'padEnd'](w[i])).join('  ');
console.log(`index.json: BMS ${index.bmsBuild ?? index.bmsVersion}, ${rows.length} theaters`);
console.log(line(head));
for (const r of rows) console.log(line(r));
if (problems.length) {
  console.log(`\nFAIL: ${problems.length} problem(s)`);
  for (const p of problems) console.log('  ! ' + p);
  process.exit(1);
}
console.log('\nPASS: every theater carries what the Planner reads');
