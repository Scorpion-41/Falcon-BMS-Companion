/**
 * Airport sets only (`data/airports/ap-*.json`: airports, navaids, places), without rebuilding the maps, charts and
 * pictures a full run does.
 *
 *   node src/airportrun.mjs          (BMS_ROOT points at the install)
 *
 * Writes the sets, points each theater's `airportSet` in `data/index.json` at its new set, moves `data/charts.json`'s
 * entries (keyed by the set) to the new keys and removes the sets nothing names any more. A full `main.mjs` run
 * produces the same files. Run `node src/airfieldrun.mjs` after it: the ground charts carry each field's elevation.
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { loadTheaters } from './theaters.mjs';
import { buildAirports } from './airports.mjs';
import { writeJson } from './util.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(path.resolve(HERE, '../../..'), 'app/src/main/assets/data');
const hash = (o) => crypto.createHash('sha1').update(JSON.stringify(o)).digest('hex').slice(0, 10);

const indexFile = path.join(OUT, 'index.json');
const index = JSON.parse(fs.readFileSync(indexFile, 'utf8'));
const sets = new Map();
const renamed = new Map(); // old set -> new set
for (const th of loadTheaters()) {
  const { airports, navaids, places } = buildAirports(th);
  const aset = { airports, navaids, places };
  const ah = 'ap-' + hash(aset);
  if (!sets.has(ah)) sets.set(ah, aset);
  const t = index.theaters.find((x) => x.id === th.id);
  if (!t) { console.warn(`${th.id}: not in index.json; run main.mjs`); continue; }
  if (t.airportSet && t.airportSet !== ah) {
    if (renamed.has(t.airportSet) && renamed.get(t.airportSet) !== ah) console.warn(`${t.airportSet} splits into two sets; charts.json keeps the first`);
    else renamed.set(t.airportSet, ah);
  }
  console.log(`${th.id}: ${airports.length} airports -> ${ah}${t.airportSet === ah ? '' : ` (was ${t.airportSet})`}`);
  t.airportSet = ah;
  t.airportCount = airports.length;
}

const dir = path.join(OUT, 'airports');
fs.mkdirSync(dir, { recursive: true });
for (const [k, v] of sets) writeJson(path.join(dir, k + '.json'), v);
for (const f of fs.readdirSync(dir)) if (/^ap-.*\.json$/.test(f) && !sets.has(f.slice(0, -5))) fs.rmSync(path.join(dir, f));
index.counts.airportSets = sets.size;
writeJson(indexFile, index);

const chartsFile = path.join(OUT, 'charts.json');
if (fs.existsSync(chartsFile) && renamed.size) {
  const charts = JSON.parse(fs.readFileSync(chartsFile, 'utf8'));
  const out = {};
  for (const [k, v] of Object.entries(charts)) out[renamed.get(k) ?? k] = v;
  writeJson(chartsFile, out);
}
console.log(`${sets.size} airport sets; ${renamed.size} renamed`);
