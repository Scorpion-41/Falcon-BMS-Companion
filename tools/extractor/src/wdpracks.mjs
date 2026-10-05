// The pylons and racks Falcon BMS hangs a store on, with their weight and drag, for the Planner's Performance page
// (`app/.../data/wdp/PerformanceLoadout.kt`).
//
// Weapon Delivery Planner's Loadout window adds, for every loaded hardpoint, the weight and drag index of its pylon and
// of its rack to the stores' own (`fclsLoadout.WeightsDragsFuel`), both read from BMS's weapon table. The app's
// aircraft data names a station's rack but carries no figures for it, so a loaded jet came out lighter and cleaner
// than BMS makes it. This writes those figures, and nothing else, so it can be re-run on its own after a BMS update.
//
// Where they come from, all in the theater's object folder (falling back to the default one, as BMS does):
//   BmsRack.dat   a hardpoint group (`definegroup`, named by the aircraft's flight model per hardpoint) holds pylons
//                 (`addpylon`, `pylonct`) in order, each with the racks it can take (`addrack`) in order — "Sorted by
//                 number of slots", the file says; a rack (`definerack`) has its `rackct`, its slot count and the
//                 stores it accepts (by weapon id, SWD index or weapon class). A ct of 0 is "no pylon" / "no rack".
//   Falcon4_CT    the pylon's or rack's class-table entry → its `EntityIdx` in the weapon table.
//   Falcon4_WCD   that entry's `Weight` (lb) and `Drag` (drag index): "R F-16 Pylon" 320 / 14, "R LAU-129+16S300"
//                 95 / 2, "R BRU-42 ITER" 369 / 24 … BMS's own combined entry "R Pylon + LAU-118" (440 / 31) is the
//                 sum of "R F-16 Pylon" and "R LAU-118" (320 + 120 / 14 + 17), which is how WDP adds them.
//
// For each aircraft, station and store, the file lists every pylon-and-rack that can carry it, in the order BMS's
// file walks them: [slots, pylon lb, pylon drag, rack lb, rack drag]. The page takes the first with room for the
// count on the station — the smallest rack that fits, since the file sorts them so. That choice follows the file's
// own ordering; it has not been compared with a running BMS's SMS page.
//
// Only the F-16s: the Planner plans nothing else (WDP's type list is the F-16's variants), and every jet of every
// theater would be several megabytes. An option whose slots are no more than an earlier one's is never the first
// with room, so it is left out.
//
// Beside them, what the theater's own data says each of those stores weighs and drags, and what the F-16s' conformal
// tanks add. BMS flies a theater with its own weapon table (`<objectdir>/Falcon4_WCD.xml`), and the add-on theaters'
// tables differ from Korea's, which is the one weapons.json keeps (the first theater a store is seen in): Hellas's
// BLU-109/B drags 10 where Korea's drags 14, its AN/AAQ-13 NAVPOD 32 where Korea's 22, EF2000's Litening pod 12 where
// Korea's 22. WDP reads the running theater's own table; so does the page. The conformal tanks are the flight model's
// (`Sim/Acdata/<jet>.txtpb`: `has_cft`, `cft_empty_weight`, `cft_fuel`, `cft_drag`; 1,700 lb, 3,060 lb and 20 on every
// F-16 that has them), and a save's own fuel shows them: an F-16C B52+ HAF's spare slots start with 10,222 lb, an
// F-16I-52+'s with 8,980 (5,920 + 3,060).
//
// Output, shared parts written once:
//   { note, theaters: { <theater id>: <set> }, sets: { <set>: { <aircraft key>: { <station>: <table> } } },
//     tables: [ { <store key>: <options> } ], options: [ [[slots, pylon lb, pylon drag, rack lb, rack drag], …] ],
//     weights: { <theater id>: <weight set> },
//     weightSets: { <weight set>: { stores: { <store key>: [lb, drag, strength] }, cft: { <aircraft key>: [lb, fuel lb, drag] } } } }
// Theaters with the same object data share a set. The aircraft, station and store keys are the ones `catalog.mjs`
// writes into aircraft.json and weapons.json. A store's strength is a tank's fuel (WDP's `GetExtFuel`).
//
// Usage: node src/wdpracks.mjs [out file]    (BMS_ROOT as for the rest of the extractor; reads only)
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { loadTheaters } from './theaters.mjs';
import { loadDb, readAcdata, wclassName } from './db.mjs';
import { buildCatalog } from './catalog.mjs';
import { slug, num } from './util.mjs';

const OUT = process.argv[2] || path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../app/src/main/assets/data/wdp/racks.json');
const hash = (o) => crypto.createHash('sha1').update(JSON.stringify(o)).digest('hex').slice(0, 10);
const PLANNED = /\bK?F-16/i;

/** Parts written once and referred to by number. */
function interner() {
  const list = [], index = new Map();
  return { list, of(v) { const k = JSON.stringify(v); if (!index.has(k)) { index.set(k, list.length); list.push(v); } return index.get(k); } };
}
const tables = interner();
const optionLists = interner();

/** A pylon's or rack's weight and drag from its class-table entry; 0 / 0 for none. */
function figures(db, ct) {
  if (!ct) return [0, 0];
  const c = db.ct[+ct];
  const w = c && +c.EntityIdx >= 0 ? db.wcd[+c.EntityIdx] : null;
  if (!w) return [0, 0];
  return [Math.round(+w.Weight || 0), Math.round(+w.Drag || 0)];
}

function accepts(rack, wid, swdIdx, cls) {
  return !!rack && (rack.any || rack.wids.has(wid) || rack.swds.has(swdIdx) || (cls && rack.wclasses.has(cls)));
}

function theaterSet(th) {
  const db = loadDb(th);
  // the stations and stores the app's aircraft data has, so the keys agree with it
  const known = new Map();
  for (const a of buildCatalog(th).aircraft) for (const s of a.stations) for (const w of s.weapons) known.set(`${a.key}|${s.n}|${w.key}`, true);
  // A name can come twice with different flight models (the catalog keeps both, as variants of one aircraft): both
  // are read, and where they share a station and store the first one's pylons and racks stand.
  const set = {};
  const weights = { stores: {}, cft: {} };
  for (const c of db.ct) {
    if (!c || +c.Domain !== 2 || +c.Class !== 7 || +c.Type !== 1 || +c.EntityType !== 5) continue;
    const v = db.vcd[+c.EntityIdx];
    if (!v || ((+v.Flags >>> 0) & 0x40000000) === 0) continue;
    const acd = db.acd[+c.MoverDefinitionData];
    const datName = acd ? db.acTypes[+acd.AirframeDatIdx + 1] : null;
    const key = slug(v.Name.trim());
    if (!PLANNED.test(v.Name)) continue;
    const fm = readAcdata(db, datName);
    const groups = fm?.hardpointGroups || [];
    if (fm?.hasCft && fm.cftFuelLbs) weights.cft[key] ??= [fm.cftEmptyLbs ?? 0, fm.cftFuelLbs, fm.cftDrag ?? 0];
    for (let i = 0; i < 16; i++) {
      const idx = v[`WpnOrHpIdx_${i}`];
      if (idx == null || +v[`WpnCount_${i}`] !== 255) continue;
      const list = db.wld[+idx];
      const group = db.racks.groups[groups[i]];
      if (!list) continue;
      for (let j = 0; j < 64; j++) {
        const wid = list[`WpnIdx_${j}`];
        if (wid == null) break;
        const w = db.wcd[+wid];
        if (!w) continue;
        const wkey = slug(w.Name);
        if (!known.has(`${key}|${i}|${wkey}`)) continue;
        weights.stores[wkey] ??= [num(w.Weight) ?? 0, num(w.Drag) ?? 0, num(w.Strength) ?? 0];
        if (!group) continue;
        const wct = db.ct[+w.CtIdx];
        const swdIdx = wct ? +wct.MoverDefinitionData : -1;
        const swd = wct ? db.swd[swdIdx] : null;
        const cls = swd ? wclassName(swd.WpnClass) : null;
        const options = [];
        for (const p of group.pylons) {
          const [pw, pd] = figures(db, p.ct);
          for (const rn of p.racks) {
            const r = db.racks.racks[rn];
            if (!accepts(r, +wid, swdIdx, cls)) continue;
            const [rw, rd] = figures(db, r.ct);
            // first with room wins, so one with no more slots than an earlier option is never used
            if (options.length && r.stations <= options[options.length - 1][0]) continue;
            options.push([r.stations, pw, pd, rw, rd]);
          }
        }
        if (!options.length) continue;
        ((set[key] ??= {})[i] ??= {})[wkey] ??= optionLists.of(options);
      }
    }
  }
  // each station's store table written once for every jet and theater that has it
  for (const st of Object.values(set)) for (const n of Object.keys(st)) st[n] = tables.of(st[n]);
  return { set, weights };
}

const theaters = {};
const sets = {};
const weights = {};
const weightSets = {};
for (const th of loadTheaters()) {
  const { set: s, weights: w } = theaterSet(th);
  if (!Object.keys(s).length) continue;
  const h = 'rk-' + hash(s);
  sets[h] ??= s;
  theaters[th.id] = h;
  const wh = 'wt-' + hash(w);
  weightSets[wh] ??= w;
  weights[th.id] = wh;
}
const out = {
  note: 'Falcon BMS pylons and racks per aircraft, station and store: [slots, pylon lb, pylon drag, rack lb, rack drag], in BmsRack.dat order; each theater\'s own store weight, drag and strength, and the F-16s\' conformal tanks ([lb, fuel lb, drag]). Written by tools/extractor/src/wdpracks.mjs.',
  theaters,
  sets,
  tables: tables.list,
  options: optionLists.list,
  weights,
  weightSets,
};
fs.mkdirSync(path.dirname(OUT), { recursive: true });
const text = JSON.stringify(out);
fs.writeFileSync(OUT, text);
console.log(`wrote ${OUT}: ${(text.length / 1024).toFixed(0)} KB, ${Object.keys(theaters).length} theaters, ${Object.keys(sets).length} sets, ${Object.keys(weightSets).length} weight sets`);
