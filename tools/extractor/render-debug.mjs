/**
 * Builds one theater's ground charts in memory and draws one field to a PNG, the way the app draws it.
 * Not part of a build; kept because every change to the pavement has to be looked at before it ships.
 *
 *   node render-debug.mjs <theater id> <field name> <out.png>
 */
import sharp from 'sharp';
import { loadTheaters } from './src/theaters.mjs';
import { buildAirports } from './src/airports.mjs';
import { buildAirfields } from './src/airfields.mjs';
import { loadDb } from './src/db.mjs';

const [, , theaterId, want, out] = process.argv;
const th = loadTheaters().find((x) => x.id === theaterId);
const db = loadDb(th);
const { airports, geo } = buildAirports(th);
const t0 = Date.now();
const { fields } = await buildAirfields(th, airports, geo, db);
const field = fields.find((f) => f.name.toLowerCase() === want.toLowerCase())
  || fields.find((f) => f.name.toLowerCase().startsWith(want.toLowerCase()));
if (!field) { console.log('no field called', want); process.exit(1); }

let e0 = Infinity, e1 = -Infinity, n0 = Infinity, n1 = -Infinity;
const see = (e, n) => { e0 = Math.min(e0, e); e1 = Math.max(e1, e); n0 = Math.min(n0, n); n1 = Math.max(n1, n); };
if (field.ship) { for (let i=0;i+1<field.ship.hull.length;i+=2) see(field.ship.hull[i], field.ship.hull[i+1]); for (const r of field.routes) for (const q of r.parking) { const nd=r.nodes[q.k]; if (nd) see(nd.e, nd.n); } } else { for (const r of field.routes) for (const p of r.nodes) see(p.e, p.n); }
if (!field.ship) for (const r of field.runways) for (const c of (r.corners || [])) see(c.e, c.n);
for (const ring of field.paved || []) for (let i = 0; i + 1 < ring.length; i += 2) see(ring[i], ring[i + 1]);

const pad = 300;
e0 -= pad; e1 += pad; n0 -= pad; n1 += pad;
const W = 1600;
const H = Math.max(200, Math.round((W * (n1 - n0)) / (e1 - e0)));
const ppf = W / (e1 - e0);
const X = (e) => (((e - e0) / (e1 - e0)) * W).toFixed(1);
const Y = (n) => ((1 - (n - n0) / (n1 - n0)) * H).toFixed(1);

const parts = [`<rect width="${W}" height="${H}" fill="#12160f"/>`];

// the taxi network as pavement, wherever BMS modelled none
{
  const WAY = new Set([2, 3, 15, 21]);
  let d = '';
  for (const r of field.routes) {
    for (let i = 0; i + 1 < (r.ed || []).length; i += 2) {
      const a = r.nodes[r.ed[i]], b = r.nodes[r.ed[i + 1]];
      if (!a || !b || !WAY.has(a.t) || !WAY.has(b.t)) continue;
      d += ` M ${X(a.e)} ${Y(a.n)} L ${X(b.e)} ${Y(b.n)}`;
    }
  }
  if (d) parts.push(`<path d="${d}" fill="none" stroke="#4a4f45" stroke-width="${(86 * ppf).toFixed(2)}" stroke-linecap="round" stroke-linejoin="round"/>`);
}

// a ship: the hull, then its island on top
if (field.ship) {
  const ring = (r) => 'M ' + X(r[0]) + ' ' + Y(r[1]) + r.slice(2).reduce((d, v, i) => i % 2 ? d + ' ' + Y(v) : d + ' L ' + X(v), '') + ' Z';
  parts.push('<path d="' + ring(field.ship.hull) + '" fill="#38424a" stroke="#aeb9c0" stroke-width="1.6"/>');
  for (const isl of field.ship.islands || []) parts.push('<path d="' + ring(isl) + '" fill="#8a95a0" stroke="#c8d2d8" stroke-width="1"/>');
}

// the asphalt, as one even-odd path so a ring inside another reads as a hole
{
  let d = '';
  for (const ring of field.paved || []) {
    if (ring.length < 6) continue;
    d += ` M ${X(ring[0])} ${Y(ring[1])}`;
    for (let i = 2; i + 1 < ring.length; i += 2) d += ` L ${X(ring[i])} ${Y(ring[i + 1])}`;
    d += ' Z';
  }
  if (d) parts.push(`<path d="${d}" fill="#4a4f45" fill-rule="evenodd" stroke="#5a6054" stroke-width="0.7"/>`);
}

// what stands beside the pavement
for (const f of field.features || []) {
  const col = f.k === 'pavement' ? '#3f443b' : f.k === 'shelter' ? '#6b7a5e' : '#7a6f5e';
  parts.push(`<rect x="${X(f.e - f.w / 2)}" y="${Y(f.n + f.l / 2)}" width="${(f.w * ppf).toFixed(1)}" height="${(f.l * ppf).toFixed(1)}" fill="${col}" transform="rotate(${f.h} ${X(f.e)} ${Y(f.n)})"/>`);
}

// runways
for (const r of field.runways) {
  if (!r.corners || r.corners.length < 4) continue;
  const d = 'M ' + r.corners.map((c) => `${X(c.e)} ${Y(c.n)}`).join(' L ') + ' Z';
  parts.push(`<path d="${d}" fill="#2b2f28" stroke="#9aa08c" stroke-width="1.5"/>`);
}

// the ramp spots: a domed bay where there is a roof, a thin stand box where there is not
{
  const route = field.routes[0];
  if (route) {
    const bearing = (ae, an, be, bn) => (Math.atan2(be - ae, bn - an) * 180) / Math.PI;
    for (const q of route.parking) {
      const node = route.nodes[q.k];
      const lane = route.nodes[q.l] || node;
      if (!node) continue;
      const half = Math.max(2, ((q.w > 0 ? Math.max(q.w, 45) : 48) / 2 + 8) * ppf);
      const cx = +X(node.e), cy = +Y(node.n);
      const face = bearing(lane.e, lane.n, node.e, node.n);
      const left = cx - half, right = cx + half, top = cy - half * 1.15, bottom = cy + half * 1.15;
      const turn = `rotate(${face.toFixed(1)} ${cx.toFixed(1)} ${cy.toFixed(1)})`;
      if (q.c) {
        const dome = Math.min(half, (bottom - top) * 0.5);
        const d = `M ${left} ${bottom} L ${left} ${top + dome} Q ${left} ${top} ${cx} ${top}`
          + ` Q ${right} ${top} ${right} ${top + dome} L ${right} ${bottom} Z`;
        parts.push(`<path d="${d}" transform="${turn}" fill="#7d8f6c" stroke="none" stroke-opacity="0" stroke-width="${Math.max(0.45, 3 * ppf).toFixed(2)}"/>`);
      } else {
        parts.push(`<rect x="${left}" y="${top}" width="${(half * 2).toFixed(1)}" height="${(bottom - top).toFixed(1)}" transform="${turn}"`
          + ` fill="none" stroke="#b6c2c8" stroke-opacity="0.9" stroke-width="${Math.max(0.5, 4 * ppf).toFixed(2)}"/>`);
      }
    }
  }
}

// the taxi network: the line a pilot follows
for (const r of field.routes) {
  for (let i = 0; i + 1 < (r.ed || []).length; i += 2) {
    const a = r.nodes[r.ed[i]], b = r.nodes[r.ed[i + 1]];
    if (!a || !b) continue;
    parts.push(`<line x1="${X(a.e)}" y1="${Y(a.n)}" x2="${X(b.e)}" y2="${Y(b.n)}" stroke="#d8c24a" stroke-opacity="0.85"`
      + ` stroke-width="${Math.max(0.8, 14 * ppf).toFixed(2)}" stroke-dasharray="${(170 * ppf).toFixed(1)} ${(51 * ppf).toFixed(1)}"/>`);
  }
}

const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}">${parts.join('')}</svg>`;
await sharp(Buffer.from(svg)).png().toFile(out);
const pts = (field.paved || []).reduce((a, r) => a + r.length / 2, 0);
console.log(`${field.name}: ${(field.paved || []).length} rings, ${pts} points, `
  + `${(JSON.stringify(field.paved || []).length / 1024).toFixed(1)} KB, built in ${Date.now() - t0}ms -> ${out} (${W}x${H})`);
