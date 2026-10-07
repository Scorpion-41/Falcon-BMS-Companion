/**
 * The Reference section's data only — encyclopedia, aircraft, weapons, their pictures and the threat guide's
 * pictures — without rebuilding the airports, ground charts and maps a full run does.
 *
 *   node src/referencerun.mjs [--no-images]          (BMS_ROOT points at the install)
 *
 * Writes `data/encyclopedia.json`, `data/aircraft.json`, `data/weapons.json`, `data/curated/threats_*.json` (with
 * each threat's `tacref`), the pictures not yet in `img/tacref/`, and the counts and aircraft numbers in
 * `data/index.json`, with the photographs `photos.mjs` fetched put in place (`applyPhotos`; run it first when
 * `tools/curated/photos.json` changed). A full `main.mjs` run produces exactly the same files.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadTheaters } from './theaters.mjs';
import { buildReference, writePictures, withThreatPictures } from './reference.mjs';
import { applyPhotos } from './photos.mjs';
import { writeJson } from './util.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../../..');
const ASSETS = path.join(ROOT, 'app/src/main/assets');
const OUT = path.join(ASSETS, 'data');
const CURATED = path.join(ROOT, 'tools/curated');

const theaters = loadTheaters();
const { acList, wpList, encList, aircraftCount, imageJobs, sameNation } = buildReference(theaters);
console.log(`same type, same nation: ${sameNation.length} aircraft: ${sameNation.join(', ')}`);
const mapping = JSON.parse(fs.readFileSync(path.join(CURATED, 'threat_pictures.json'), 'utf8'));
const threats = {};
for (const f of fs.readdirSync(CURATED).filter((f) => /^threats_.*\.json$/.test(f))) {
  threats[f] = withThreatPictures(f, JSON.parse(fs.readFileSync(path.join(CURATED, f), 'utf8')), mapping, encList);
}
// the photographs from Wikimedia Commons (photos.mjs): in place of BMS's pictures, and for entries with none
const { replaced, used } = applyPhotos({ acList, wpList, encList, threats });
console.log(`photos: ${used.size} in use, standing in for ${replaced.size} BMS picture(s)`);
writeJson(path.join(OUT, 'aircraft.json'), acList);
writeJson(path.join(OUT, 'weapons.json'), wpList);
writeJson(path.join(OUT, 'encyclopedia.json'), encList);
for (const [f, obj] of Object.entries(threats)) writeJson(path.join(OUT, 'curated', f), obj);

const indexFile = path.join(OUT, 'index.json');
const index = JSON.parse(fs.readFileSync(indexFile, 'utf8'));
for (const t of index.theaters) if (aircraftCount.has(t.id)) t.aircraftCount = aircraftCount.get(t.id);
Object.assign(index.counts, { aircraft: acList.length, weapons: wpList.length, encyclopedia: encList.length });
writeJson(indexFile, index);

if (!process.argv.includes('--no-images')) await writePictures(imageJobs, path.join(ASSETS, 'img/tacref'), replaced);
const pictured = (l) => l.filter((x) => x.pic).length;
console.log(`aircraft ${pictured(acList)} of ${acList.length} pictured, weapons ${pictured(wpList)} of ${wpList.length}, encyclopedia ${pictured(encList)} of ${encList.length}`);
