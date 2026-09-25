/**
 * Ground charts only, without rebuilding the maps, charts and images a full run does.
 *
 *   node src/airfieldrun.mjs          (BMS_ROOT points at the install)
 *
 * Writes `data/airfields/` and fills in each theater's `airfieldSet` in `data/index.json`. A full `main.mjs` run
 * produces exactly the same files.
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { loadTheaters } from './theaters.mjs';
import { buildAirports } from './airports.mjs';
import { buildAirfields } from './airfields.mjs';
import { stats as pavementStats } from './pavement.mjs';
import { loadDb } from './db.mjs';
import { writeJson } from './util.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(path.resolve(HERE, '../../..'), 'app/src/main/assets/data');
const hash = (o) => crypto.createHash('sha1').update(JSON.stringify(o)).digest('hex').slice(0, 10);

const files = new Map();
const indexes = new Map();
const bySet = new Map();
const warnings = [];
let charts = 0;

// cleared before anything runs: the loop paints pavement straight into this folder
fs.rmSync(path.join(OUT, 'airfields'), { recursive: true, force: true });
fs.mkdirSync(path.join(OUT, 'airfields'), { recursive: true });

for (const th of loadTheaters()) {
  const t0 = Date.now();
  const db = loadDb(th);
  const { airports, geo } = buildAirports(th);
  const { fields, warnings: w } = await buildAirfields(th, airports, geo, db);
  warnings.push(...w);
  const fieldIndex = {};
  for (const f of fields) {
    const fh = 'af-' + hash(f);
    if (!files.has(fh)) files.set(fh, f);
    fieldIndex[f.id] = fh;
  }
  const fih = 'ai-' + hash(fieldIndex);
  if (!indexes.has(fih)) indexes.set(fih, fieldIndex);
  bySet.set(th.id, fields.length ? fih : null);
  charts += fields.length;
  const spots = fields.reduce((a, f) => a + f.routes.reduce((b, r) => b + r.parking.length, 0), 0);
  console.log(`${th.id}: ${fields.length} of ${airports.length} airports charted, ${spots} ramp spots (${Date.now() - t0}ms)`);
}

for (const [k, v] of files) writeJson(path.join(OUT, 'airfields', k + '.json'), v);
for (const [k, v] of indexes) writeJson(path.join(OUT, 'airfields', k + '.json'), v);

const indexFile = path.join(OUT, 'index.json');
const index = JSON.parse(fs.readFileSync(indexFile, 'utf8'));
for (const t of index.theaters) t.airfieldSet = bySet.has(t.id) ? bySet.get(t.id) : null;
writeJson(indexFile, index);

const size = [...files.values()].reduce((a, f) => a + JSON.stringify(f).length, 0);
console.log(`\n${charts} charts across ${index.theaters.length} theaters -> ${files.size} distinct files, ${indexes.size} indexes, ${(size / 1024 / 1024).toFixed(1)} MB`);
const ps = pavementStats;
console.log(`pavement: ${ps.withGround} usable surfaces of ${ps.models} models opened (${ps.doesNotTile} did not tile, ${ps.unreadable} would not read)`);

if (warnings.length) {
  console.log(`\n${warnings.length} field(s) disagree with their airport record:`);
  for (const w of warnings) console.log('  ! ' + w);
} else {
  console.log('every chart agrees with the airport record the rest of the app uses');
}
