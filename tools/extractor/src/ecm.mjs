/**
 * The ECM pod programs that jam each air-defence threat's radars, for its Threats page.
 *
 * Two of BMS's own sources, nothing invented:
 *  - **the band each radar works in, per mode**: BMS's radar data, `Data\Sim\Radar\<name>.dat`
 *    (`Bandsearch1..3`, `Bandacuire`, `Bandguide`: one EW band letter A-M each). A radar of `Falcon4_RCD.xml` is
 *    found through its `RadarDatIdx`, the place in `Data\Sim\Radar\RadTypes.lst` (first line the count, then one
 *    file name per index from 0) — never the `.dat`'s own `Indx`, which is wrong in places (Tombstone.dat says 128,
 *    the index of Big_Bird_PMU1, while RadTypes.lst and the RCD give Tombstone 127).
 *  - **which program jams which band**: TO 1F-16CMAM-34-1-1 BMS (4.38), 2.7.4.1.1 USAF ECM pods: "The ECM
 *    (AN/ALQ-131 and AN/ALQ-184 pods) can be operated through 5 programs. Each program can jam specific radar
 *    frequency bands. Program 1: A-B-C, Program 2: D-E, Program 3: F-G, Program 4: H-I, Program 5: J-K" (the same
 *    table as the BMS Threat Guide's RWR coverage page). L and M are beyond the pod; the IDIAS picks its band itself.
 *
 * Which radars a threat has is `tools/curated/threat_radars.json` (reviewed against BMS's unit table; see its `_src`).
 *
 *   node src/ecm.mjs        rewrites only the `ecm` of each threat in the app's data/curated/threats_*.json
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DATA, parseRecords, readText, findFileCI, writeJson } from './util.mjs';

/** Program -> the EW bands it jams (TO 1F-16CMAM-34-1-1 BMS 2.7.4.1.1). */
export const ECM_PROGRAMS = { 1: 'ABC', 2: 'DE', 3: 'FG', 4: 'HI', 5: 'JK' };

export function programOf(band) {
  for (const [p, bands] of Object.entries(ECM_PROGRAMS)) if (bands.includes(band)) return +p;
  return null;
}

/** BMS's radars by name: `{ file, search: [bands], acquisition, guidance }`. */
export function readRadars(data = DATA) {
  const rcd = parseRecords(path.join(data, 'TerrData/Objects/Falcon4_RCD.xml'), 'RCD') || [];
  const dir = path.join(data, 'Sim/Radar');
  const lst = (readText(findFileCI(dir, 'RadTypes.lst') ?? '') ?? '').split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  const names = lst.slice(1); // line 1 is the count
  const out = new Map();
  for (const r of rcd) {
    if (!r || !r.Name) continue;
    const file = names[+r.RadarDatIdx];
    const dat = file && findFileCI(dir, file + '.dat');
    if (!dat) continue;
    const v = {};
    for (const line of (readText(dat) ?? '').split(/\r?\n/)) {
      const m = line.trim().match(/^(Band\w+)\s+([A-M])\b/);
      if (m) v[m[1].toLowerCase()] = m[2];
    }
    const search = [...new Set(['bandsearch1', 'bandsearch2', 'bandsearch3'].map((k) => v[k]).filter(Boolean))];
    if (!search.length && !v.bandacuire && !v.bandguide) continue;
    out.set(r.Name.trim(), { file, search, acquisition: v.bandacuire ?? null, guidance: v.bandguide ?? null });
  }
  return out;
}

/**
 * Gives each threat named in `mapping` its `ecm`: one entry per radar, `{ radar, search, acquisition, guidance,
 * programs }`, programs sorted, a band beyond the pod (L, M) giving none. A radar BMS no longer has is dropped with
 * a warning; a threat the mapping does not list gets nothing (and a warning, so a new threat is not forgotten).
 */
export function withEcmPrograms(file, threats, mapping, radars) {
  for (const t of threats.threats || []) {
    delete t.ecm;
    if (!(t.id in mapping)) { if (/airdefense/.test(file)) console.warn(`${file}: ${t.id} is not in threat_radars.json; no ECM programs`); continue; }
    const ecm = [];
    for (const name of mapping[t.id]) {
      const r = radars.get(name);
      if (!r) { console.warn(`${file}: ${t.id} names radar "${name}", which BMS's radar data does not have`); continue; }
      const bands = [...r.search, r.acquisition, r.guidance].filter(Boolean);
      const programs = [...new Set(bands.map(programOf).filter((p) => p != null))].sort((a, b) => a - b);
      ecm.push({ radar: name, search: r.search, acquisition: r.acquisition, guidance: r.guidance, programs });
    }
    if (ecm.length) t.ecm = ecm;
  }
  return threats;
}

export function loadRadarMapping(curatedDir) {
  const m = JSON.parse(fs.readFileSync(path.join(curatedDir, 'threat_radars.json'), 'utf8'));
  delete m._src;
  return m;
}

// node src/ecm.mjs: the app's threat files gain (or lose) only their `ecm`; everything else in them stays as written
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
  const out = path.join(ROOT, 'app/src/main/assets/data/curated');
  const mapping = loadRadarMapping(path.join(ROOT, 'tools/curated'));
  const radars = readRadars();
  for (const f of fs.readdirSync(out).filter((f) => /^threats_.*\.json$/.test(f))) {
    const obj = withEcmPrograms(f, JSON.parse(fs.readFileSync(path.join(out, f), 'utf8')), mapping, radars);
    writeJson(path.join(out, f), obj);
    const n = (obj.threats || []).filter((t) => t.ecm).length;
    console.log(`${f}: ${n} threat(s) with ECM programs`);
  }
}
