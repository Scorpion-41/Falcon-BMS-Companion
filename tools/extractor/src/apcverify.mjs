/**
 * Checks the generated ground charts against the parking charts the theaters ship (see charts/README.txt).
 *
 *   node src/apcverify.mjs
 *
 * A chart prints a latitude and longitude per spot; ours are in the field's own feet about an origin the campaign
 * places. The two are compared after centring both sets, so only the shape and the numbering are tested — which is
 * the point: the numbering is the thing that is easy to get wrong, and the only place it is written down.
 */
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
    want.push({ n: Number(m[1]), north: p.x, east: p.y });
  }
  const mine = route.parking.map((s) => ({ n: s.n, north: field.n + route.nodes[s.k].n, east: field.e + route.nodes[s.k].e }));
  const mean = (a) => a.reduce((x, y) => x + y, 0) / a.length;
  const dn = mean(mine.map((m) => m.north)) - mean(want.map((w) => w.north));
  const de = mean(mine.map((m) => m.east)) - mean(want.map((w) => w.east));

  const wrong = [];
  let worst = 0;
  for (const w of want) {
    const m = mine.find((x) => x.n === w.n);
    const d = m ? Math.hypot(m.north - dn - w.north, m.east - de - w.east) : Infinity;
    worst = Math.max(worst, Number.isFinite(d) ? d : 0);
    if (!(d < 120)) wrong.push(w.n);
  }
  const ok = wrong.length === 0 && mine.length === want.length;
  if (!ok) failed++;
  console.log(`${ok ? 'OK  ' : 'BAD '} ${field.name} runway ${runway}: ${want.length} spots on the chart, ${mine.length} of ours, worst ${worst.toFixed(0)} ft` +
    (wrong.length ? `, wrong numbers: ${wrong.join(',')}` : ''));
}
console.log(failed ? `\n${failed} chart(s) disagree` : '\nevery chart agrees, spot for spot');
process.exit(failed ? 1 : 0);
