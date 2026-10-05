/**
 * Checks the generated ground charts against the parking charts the theaters ship (see charts/README.txt).
 *
 *   node src/apcverify.mjs
 *
 * A chart prints a latitude and longitude per spot; ours are in the field's own feet about an origin the campaign
 * places. The two are compared after centring both sets, and each printed spot is matched to ours by position.
 *
 * Our numbers are BMS's own — what Ground says: a count of the network's parking points in storage order, read from
 * the sim's code (see numberParking in airfields.mjs). Souda, Tirana and Skopje print exactly that. Araxos's chart is
 * a drawing numbered another way (breadth first by ParkingPointGroup), and six of its spots differ from the count;
 * those six are listed in KNOWN_CHART_NUMBER (chart number -> BMS's), and any other difference fails.
 *
 * The charts also print each spot's **size letter** — S for a small stand (an encircled number on the chart), L for
 * one with no size limit (a boxed number) and Q for the alert cell (printed red) — so the same tables check what the
 * app draws. Size is the point's own type, 11 or 12; the alert cell is ParkingPointGroup -1. Araxos 4 and 5 are the
 * only disagreement in the four charts: the data types them small and the chart boxes them.
 */
const KNOWN_SIZE_MISMATCH = new Set(['Araxos Airbase 36 4', 'Araxos Airbase 36 5']);
/** Araxos 36's chart against BMS's count, '<field> <runway> <chart number>' -> the number Ground says */
const KNOWN_CHART_NUMBER = new Map([
  ['Araxos Airbase 36 12', 14], ['Araxos Airbase 36 13', 15], ['Araxos Airbase 36 14', 16],
  ['Araxos Airbase 36 15', 17], ['Araxos Airbase 36 16', 12], ['Araxos Airbase 36 17', 13],
]);
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadTheaters } from './theaters.mjs';
import { readTheaterTxt, makeProjector } from './projection.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(path.resolve(HERE, '../../..'), 'app/src/main/assets/data');
const DIR = path.join(HERE, '../charts');
const index = JSON.parse(fs.readFileSync(path.join(OUT, 'index.json'), 'utf8'));

let failed = 0;
for (const file of fs.readdirSync(DIR).filter((f) => f.endsWith('.txt'))) {
  const [thId, name, runway] = path.basename(file, '.txt').split('_');
  const th = loadTheaters().find((t) => t.id === thId);
  const entry = index.theaters.find((t) => t.id === thId);
  const proj = makeProjector(readTheaterTxt(th.terrainDir));
  const ai = JSON.parse(fs.readFileSync(path.join(OUT, 'airfields', entry.airfieldSet + '.json'), 'utf8'));
  const ap = JSON.parse(fs.readFileSync(path.join(OUT, 'airports', entry.airportSet + '.json'), 'utf8'));
  const airport = ap.airports.find((a) => new RegExp(name, 'i').test(a.name));
  const field = JSON.parse(fs.readFileSync(path.join(OUT, 'airfields', ai[airport.id] + '.json'), 'utf8'));
  const route = field.routes.find((r) => r.designator === runway || r.designator === runway.padStart(2, '0'));

  const want = [];
  for (const line of fs.readFileSync(path.join(DIR, file), 'utf8').split(/\r?\n/)) {
    const m = line.trim().match(/^(\d+)\s+(\S+)\s+N(\d+),([\d.]+)\s+E(\d+),([\d.]+)/);
    if (!m) continue;
    const p = proj(Number(m[3]) + Number(m[4]) / 60, Number(m[5]) + Number(m[6]) / 60);
    want.push({ n: Number(m[1]), letter: m[2], north: p.x, east: p.y });
  }
  const mine = route.parking.map((s) => ({ n: s.n, north: field.n + route.nodes[s.k].n, east: field.e + route.nodes[s.k].e }));
  const mean = (a) => a.reduce((x, y) => x + y, 0) / a.length;
  const dn = mean(mine.map((m) => m.north)) - mean(want.map((w) => w.north));
  const de = mean(mine.map((m) => m.east)) - mean(want.map((w) => w.east));

  // Each printed spot is matched to ours by position; then its number, its size and the alert cell are compared.
  const wrong = [];
  const known = [];
  const missing = [];
  let worst = 0;
  const matched = new Map();
  for (const w of want) {
    let m = null;
    let d = Infinity;
    for (const x of mine) {
      const dd = Math.hypot(x.north - dn - w.north, x.east - de - w.east);
      if (dd < d) { d = dd; m = x; }
    }
    if (!(d < 120)) { missing.push(w.n); continue; }
    worst = Math.max(worst, d);
    matched.set(w.n, m.n);
    if (m.n === w.n) continue;
    const key = field.name + ' ' + runway + ' ' + w.n;
    if (KNOWN_CHART_NUMBER.get(key) === m.n) known.push(w.n + '=' + m.n);
    else wrong.push(w.n + ' (BMS ' + m.n + ')');
  }
  // the size letter and the alert cell, against the same tables
  const sizeWrong = [];
  for (const w of want) {
    const spot = route.parking.find((x) => x.n === matched.get(w.n));
    if (!spot) continue;
    const ours = spot.q ? 'Q' : spot.s ? 'S' : 'L';
    if (ours !== w.letter && !KNOWN_SIZE_MISMATCH.has(field.name + ' ' + runway + ' ' + w.n)) {
      sizeWrong.push(w.n + ':' + w.letter + '/' + ours);
    }
  }
  const ok = wrong.length === 0 && missing.length === 0 && mine.length === want.length && sizeWrong.length === 0;
  if (!ok) failed++;
  console.log(`${ok ? 'OK  ' : 'BAD '} ${field.name} runway ${runway}: ${want.length} spots on the chart, ${mine.length} of ours, worst ${worst.toFixed(0)} ft` +
    (known.length ? `, chart's own numbering (known, chart=BMS): ${known.join(',')}` : '') +
    (wrong.length ? `, wrong numbers: ${wrong.join(',')}` : '') +
    (missing.length ? `, no spot of ours within 120 ft: ${missing.join(',')}` : '') +
    (sizeWrong.length ? `, chart/ours size: ${sizeWrong.join(',')}` : ''));
}
console.log(failed ? `\n${failed} chart(s) disagree` : '\nevery chart agrees, spot for spot');
process.exit(failed ? 1 : 0);
