/**
 * How many of a theater's airfield models the decoder can read. Not part of a build.
 *
 *   node bml-rate.mjs <theater id> [limit]
 */
import path from 'node:path';
import { loadTheaters } from './src/theaters.mjs';
import { buildAirports } from './src/airports.mjs';
import { loadDb } from './src/db.mjs';
import { parseRecords, findFileCI, DATA } from './src/util.mjs';
import { readModel, groundTriangles } from './src/bml.mjs';

const th = loadTheaters().find((t) => t.id === process.argv[2]);
const limit = Number(process.argv[3] || 400);
const db = loadDb(th);
const { airports, geo } = buildAirports(th);

const ocdDir = (o) => findFileCI(path.join(th.data3dDir, 'ObjectiveRelatedData'), `OCD_${o}`)
  || findFileCI(path.join(th.objectDir, 'ObjectiveRelatedData'), `OCD_${o}`)
  || findFileCI(path.join(DATA, 'TerrData/Objects/ObjectiveRelatedData'), `OCD_${o}`);

const modelFile = (i) => {
  for (const b of [path.join(th.data3dDir, 'Models'), path.join(th.objectDir, 'Models'), path.join(DATA, 'TerrData/Objects/Models')]) {
    const d = findFileCI(b, String(i));
    if (!d) continue;
    const f = findFileCI(d, 'Model_0.bml') || findFileCI(d, 'Model_1.bml') || findFileCI(d, 'Model_2.bml');
    if (f) return f;
  }
  return null;
};

/** What a model's name says it is. Only used to check the reader, never to decide what is pavement. */
const PAVED = /taxiway|taxi way|runway|apron|ramp|tarmac|stopway|hardstand|dispersal|overrun|helipad/i;
const NOT = /sign|light|papi|vasi|marking|arrestor|barrier|fence|wall|tower|shed|hangar|shelter|building|depot|barrack|office|warehouse|fuel|radar|antenna|hut|tank\b/i;

const seen = new Set();
let tried = 0, parsed = 0, failed = 0, withGround = 0, bigGround = 0;
let pavedTried = 0, pavedParsed = 0;
const failures = [];
const pavedFailures = [];

outer:
for (const a of airports) {
  const g = geo.get(a.id);
  if (!g) continue;
  const id = String(g.ocd).padStart(5, '0');
  const d = ocdDir(id);
  if (!d) continue;
  for (const f of (parseRecords(findFileCI(d, `FED_${id}.XML`), 'FED') || []).filter(Boolean)) {
    const ct = db.ct[+f.FeatureCtIdx];
    if (!ct) continue;
    const gi = +ct.GraphicsNormal;
    if (seen.has(gi)) continue;
    seen.add(gi);
    const file = modelFile(gi);
    if (!file) continue;
    tried++;
    const nm = db.fcd[+ct.EntityIdx]?.Name || '?';
    const isPaved = PAVED.test(nm) && !NOT.test(nm);
    if (isPaved) pavedTried++;
    const m = readModel(file);
    if (!m) {
      failed++;
      if (failures.length < 12) failures.push(`${gi} (${nm})`);
      if (isPaved && pavedFailures.length < 20) pavedFailures.push(`${gi} (${nm})`);
    } else {
      parsed++;
      if (isPaved) pavedParsed++;
      const gt = groundTriangles(file);
      if (gt && gt.xz.length) {
        withGround++;
        if (gt.xz.length / 6 > 200) bigGround++;
      }
    }
    if (tried >= limit) break outer;
  }
}

console.log(`${th.id}: ${tried} models tried`);
console.log(`   parsed ${parsed} (${(100 * parsed / tried).toFixed(1)}%), failed ${failed}`);
console.log(`   ${withGround} have ground triangles, ${bigGround} of those have more than 200`);
console.log(`   named like pavement: ${pavedParsed} of ${pavedTried} parsed`);
if (pavedFailures.length) console.log(`   PAVEMENT MODELS THAT FAIL: ${pavedFailures.join(', ')}`);
if (failures.length) console.log(`   other failures: ${failures.join(', ')}`);
