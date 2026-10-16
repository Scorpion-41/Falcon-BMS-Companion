// Airports / navaids per theater: CampObjData + Stations+Ils.dat + ATC/*.dat + runway geometry (PHD/PDX/FED).
import path from 'node:path';
import fs from 'node:fs';
import { DATA, parseRecords, findFileCI, readText } from './util.mjs';
import { loadDb } from './db.mjs';
import { terrainSampler, groundUnder, levelledUnder } from './elevation.mjs';
import { levelling } from './tld.mjs';

const OBJ_TYPES = { 1: 'Airbase', 2: 'Airstrip' };
const PLACE_TYPES = { 8: 'city', 28: 'town', 29: 'village' };

const fmtUhf = (v) => (!v || v === '0' ? null : (Number(v) / 1000).toFixed(Number(v) % 100 ? 3 : 2));
const fmtIls = (v) => (!v || v === '0' ? null : (Number(v) / 100).toFixed(2));

export function parseStations(file) {
  const text = readText(file, 'latin1');
  const out = new Map();
  if (!text) return out;
  let lastComment = null;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith('#')) {
      const c = line.replace(/^#+\s*/, '').trim();
      if (c && !/^[-=]+$/.test(c) && !/^(file format|<|revised|added|navaids)/i.test(c)) lastComment = c;
      continue;
    }
    const f = line.split(/\s+/);
    if (f.length < 12 || !/^\d+$/.test(f[0])) continue;
    const id = +f[0];
    const rec = {
      campId: id,
      label: lastComment,
      tacan: +f[1] > 0 && !(+f[1] === 1 && +f[4] === 0) ? { channel: +f[1], band: f[2], callsignIdx: +f[3], rangeNm: +f[4], type: +f[5] } : null,
      towerUhf: fmtUhf(f[6]), towerVhf: fmtUhf(f[7]),
      ils: [fmtIls(f[8]), fmtIls(f[9]), fmtIls(f[10]), fmtIls(f[11])],
      opsUhf: fmtUhf(f[12]), groundUhf: fmtUhf(f[13]), approachUhf: fmtUhf(f[14]), lsoUhf: fmtUhf(f[15]), atisVhf: fmtUhf(f[16]),
    };
    // A theater's file can give one campaign ID twice (Israel 1545, 2734; Balkans and EF2000 2677): the last is kept
    // as before, with the earlier ones beside it for stationFor to choose between.
    const prev = out.get(id);
    if (prev) rec.alternatives = [...(prev.alternatives || []), { ...prev, alternatives: undefined }];
    out.set(id, rec);
    lastComment = null;
  }
  return out;
}

/**
 * The station record for an objective, where the theater's file lists its campaign ID more than once. Israel's gives
 * 2734 twice: first "USS CARL VINSON CVN70" (TACAN 10X, UHF 270.2), then "Chinese Carrier - CV-16 Liaoning" (14X,
 * 272.2), a copy of the line meant for the Liaoning's own objective (2733 "Carrier Type 001", which has none). Keeping
 * the last, as a plain map does, gave the Vinson the Liaoning's name and radios. So the record whose own comment names
 * the objective wins; when none or several do (Balkans' two Wasps, 2677), the last is kept, as it always was.
 * Which of two records Falcon BMS itself uses for one ID is NOT TESTED (it needs the sim running); the theater's own
 * navaid list prints both under the Vinson's name, with the 10X line first.
 */
export function stationFor(st, objectiveName) {
  if (!st?.alternatives?.length) return st || null;
  const generic = new Set(['CARRIER', 'AIRBASE', 'AIRFIELD', 'AIRPORT', 'AIRSTRIP', 'INTL', 'INTERNATIONAL', 'BASE', 'USS', 'THE']);
  const words = (s) => (s || '').toUpperCase().split(/[^A-Z]+/).filter((w) => w.length >= 3 && !generic.has(w));
  const own = new Set(words(objectiveName));
  const all = [...st.alternatives, { ...st, alternatives: undefined }];
  const naming = all.filter((r) => words(r.label).some((w) => own.has(w)));
  return naming.length === 1 ? naming[0] : st;
}

/** ATC .dat: values are positional but always preceded by a descriptive comment. */
export function parseAtc(file) {
  const text = readText(file, 'latin1');
  if (!text) return null;
  const atc = { runways: [] };
  let label = '', rwy = null, section = 'top', elevation = null;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    const info = line.match(/^#[*]*INFO\s+(-?\d+)/i);
    if (info) { elevation = +info[1]; continue; }
    if (line.startsWith('#')) {
      const c = line.replace(/^#+\s*/, '').replace(/#+$/, '').trim();
      if (/^RUNWAY:/i.test(c)) { rwy = { section: 'main' }; atc.runways.push(rwy); section = 'main'; continue; }
      if (/^BASE$/i.test(c)) { section = 'base'; continue; }
      if (/^LONG$/i.test(c)) { section = 'long'; continue; }
      if (c) label = c.toLowerCase();
      continue;
    }
    const vals = line.split(/\s+/).map(Number);
    const pt = () => (vals.length >= 3 && (vals[0] || vals[1] || vals[2]) ? { a: vals[0], b: vals[1], altFt: Math.round(vals[2]) } : null);
    if (!rwy) {
      if (label.startsWith('campid')) atc.campId = vals[0];
      else if (label.startsWith('number of active runways')) atc.activeRunways = Math.round(vals[0]);
      else if (label.startsWith('force closer vfr')) atc.shortPattern = vals[0] === 1;
      else if (label.startsWith('ifr minimum visibility')) atc.ifrMinVisM = Math.round(vals[0]);
      else if (label.startsWith('ifr minimum cloud')) atc.ifrMinCloudFt = Math.round(vals[0]);
      else if (label.startsWith('vfr minimum visibility')) atc.vfrMinVisM = Math.round(vals[0]);
      else if (label.startsWith('vfr minimum cloud')) atc.vfrMinCloudFt = Math.round(vals[0]);
      continue;
    }
    if (label.startsWith('database runway')) rwy.dbRunway = vals[0];
    else if (label.startsWith('runway axis')) rwy.axisTrue = vals[0];
    else if (label.startsWith('has "base"')) rwy.hasBase = vals[0] === 1;
    else if (label.startsWith('has "long final"')) rwy.hasLongFinal = vals[0] === 1;
    else if (label.startsWith('overhead side')) rwy.overheadSide = vals[0] < 0 ? 'Left' : 'Right';
    else if (label.startsWith('qfu')) rwy.qfu = String(vals[0]).padStart(2, '0');
    else if (label.startsWith('final pt')) rwy.final = pt();
    else if (label.startsWith('say base')) rwy.sayBase = ['', 'Right', 'Left'][vals[0]] || null;
    else if (label.startsWith('base pt')) rwy.base = pt();
    else if (label.startsWith('entry pt')) rwy[section === 'long' ? 'longEntry' : 'entry'] = pt();
    else if (label.startsWith('holding pt')) rwy[section === 'long' ? 'longHolding' : 'holding'] = pt();
    else if (label.startsWith('loiter turn')) rwy[section === 'long' ? 'longLoiter' : 'loiter'] = vals[0] < 0 ? 'Left' : 'Right';
  }
  atc.elevationFt = elevation == null ? null : Math.round(elevation);
  return atc;
}

function loadAtcDir(th) {
  let dir = findFileCI(th.terrDataDir, 'ATC');
  if (!dir) dir = path.join(DATA, 'TerrData/ATC');
  const byCamp = new Map();
  for (const f of fs.readdirSync(dir)) {
    if (!/\.dat$/i.test(f)) continue;
    const atc = parseAtc(path.join(dir, f));
    if (atc?.campId != null) { atc.file = f; byCamp.set(atc.campId, atc); }
  }
  return byCamp;
}

const rad = (d) => (d * Math.PI) / 180;
const norm360 = (d) => ((d % 360) + 360) % 360;

function runwayGeometry(th, db, ocdIdx) {
  const id = String(ocdIdx).padStart(5, '0');
  const dir = findFileCI(path.join(th.data3dDir, 'ObjectiveRelatedData'), `OCD_${id}`)
    || findFileCI(path.join(DATA, 'TerrData/Objects/ObjectiveRelatedData'), `OCD_${id}`);
  if (!dir) return null;
  const phd = parseRecords(findFileCI(dir, `PHD_${id}.XML`), 'PHD') || [];
  const pd = parseRecords(findFileCI(dir, `PDX_${id}.XML`), 'PD') || [];
  const fed = parseRecords(findFileCI(dir, `FED_${id}.XML`), 'FED') || [];
  const feats = fed.filter(Boolean).map((f) => {
    const c = db.ct[+f.FeatureCtIdx];
    const name = c ? db.fcd[+c.EntityIdx]?.Name || '' : '';
    return { name, e: +f.OffsetX, n: +f.OffsetY };
  });
  const headers = phd.filter((h) => h && +h.Type === 1).map((h, order) => {
    const pts = pd.slice(+h.FirstPtIdx, +h.FirstPtIdx + +h.PointCount).filter(Boolean);
    const hd = rad(+h.Data);
    const f = [Math.sin(hd), Math.cos(hd)], r = [Math.cos(hd), -Math.sin(hd)];
    const p2 = pts.find((p) => +p.Type === 2) || pts[0];
    const lateral = p2 ? +p2.OffsetX * r[0] + +p2.OffsetY * r[1] : 0;
    return { order, hdg: norm360(+h.Data), rwyNo: +h.RunwayNumber, lateral, f, r, p2: p2 ? { e: +p2.OffsetX, n: +p2.OffsetY } : null };
  });
  // each runway's rectangle (a point header of type 8, its first four runway-edge points: one end's pair, then the
  // other's), as airfields.mjs draws it: where the field's elevation is read when its ATC file gives none
  const rectHeaders = phd.filter((h) => h && +h.Type === 8).map((h) => ({ rwyNo: +h.RunwayNumber, c: pd.slice(+h.FirstPtIdx, +h.FirstPtIdx + +h.PointCount)
    .filter((p) => p && +p.Type === 8).slice(0, 4).map((p) => ({ e: +p.OffsetX, n: +p.OffsetY })) }))
    .filter((r) => r.c.length === 4);
  const rects = rectHeaders.map((r) => r.c);
  // and what each measures, exactly as airfields.mjs's buildRunways measures it for the ground charts (the middle of
  // one end's pair of corners to the middle of the other's; the width across the first pair) — the figure BMS's own
  // ground charts print in metres ("2745 X 45 M" at Osan is 9,004 × 149 ft)
  const strips = rectHeaders.map(({ rwyNo, c }) => {
    const a = { e: (c[0].e + c[1].e) / 2, n: (c[0].n + c[1].n) / 2 }, b = { e: (c[2].e + c[3].e) / 2, n: (c[2].n + c[3].n) / 2 };
    return {
      rwyNo,
      lengthFt: Math.round(Math.hypot(b.e - a.e, b.n - a.n)),
      widthFt: Math.round(Math.hypot(c[1].e - c[0].e, c[1].n - c[0].n)),
      mid: { e: (a.e + b.e) / 2, n: (a.n + b.n) / 2 },
      axis: norm360((Math.atan2(b.e - a.e, b.n - a.n) * 180) / Math.PI),
    };
  });
  return { headers, feats, rects, strips };
}

/**
 * The runway rectangle a pair of runway ends belongs to: one of the field's type 8 headers, its own RunwayNumber first
 * (parallel strips share a course — Osan's 09L and 09R), lying along the ends' course, the nearest to their centre
 * line (an end BMS gives no reciprocal finds the same rectangle as its other end would). A field with one rectangle has
 * one runway, so an end whose own record is off — a taxi network stating course 0 (Novo Mesto), or one laid for a
 * strip at another angle (Al-Muzabin's "18/36" on its 14/32 rectangle, Wujah Al Hajar, Kastoria in Hellas) — is on
 * that one. Null where the field has none that fits.
 */
function stripFor(h, geo) {
  const free = geo.strips || [];
  const fits = (list) => list
    .filter((s) => { const d = Math.abs(((s.axis - h.hdg) % 180 + 180) % 180); return Math.min(d, 180 - d) < 15; })
    .map((s) => ({ s, off: Math.abs(s.mid.e * h.r[0] + s.mid.n * h.r[1] - h.lateral) }))
    .filter((x) => x.off < 400)
    .sort((x, y) => x.off - y.off)[0]?.s ?? null;
  return fits(free.filter((s) => s.rwyNo === h.rwyNo)) ?? fits(free) ?? (free.length === 1 ? free[0] : null);
}

function designatorFor(h, geo) {
  // Threshold features named "... Runway THR 31L" lying on this header's centerline.
  let best = null;
  for (const t of geo.feats) {
    const m = t.name.match(/THR\s*([0-3]?\d[LRC]?)\b/i);
    if (!m || /lights/i.test(t.name)) continue;
    const lat = t.e * h.r[0] + t.n * h.r[1];
    const des = m[1].toUpperCase();
    const num = parseInt(des, 10);
    const expected = Math.round(h.hdg / 10) || 36;
    const diff = Math.min(Math.abs(num - expected), 36 - Math.abs(num - expected));
    if (diff > 2 || Math.abs(lat - h.lateral) > 120) continue;
    if (!best || Math.abs(lat - h.lateral) < best.d) best = { des: des.length === 1 ? '0' + des : des, d: Math.abs(lat - h.lateral) };
  }
  return best?.des ?? null;
}

/**
 * Which runway end each of a station line's four ILS frequencies serves, as Falcon BMS itself works it out (read out
 * of `Falcon BMS.exe` with its pdb, 4.38.1):
 * - `ATCBrain::ATCBrain` takes the field's number of runways from the objective class (OCD `DataRate`, held to 2) and
 *   walks its type 1 point headers in PHD order (`NextHeaderIdx`, which `LoadClassTable` sets to the next record of the
 *   same file), filing each under its `RunwayNumber` q: the first as the runway's header 0, any later one as header 1.
 * - `TacanList::TuneILS` takes frequency slot s as runway s / 2's header s % 2, and `NavigationSystem::SetIlsData` flies
 *   that header's first point to its next one with the localizer at the OTHER header's first point
 *   (`ATCBrain::GetOppositeRunway`), naming the runway from the other header's heading and landing pattern ("%02dL").
 * - `ATCBrain::FindBestLandingRunway` agrees from the other side: landing on runway q's header j asks
 *   `TacanList::ILSFreqForRwy` for slot 2q + (1 - j).
 * So slot 2q is the end of runway q's second header, slot 2q + 1 its first's: within each RunwayNumber the PHD order is
 * swapped. Up to 1.4.0 the slots were given in PHD order, which put Cheongju's one ILS on 06R (it is 24L, as BMS's KTO
 * AIP prints it); Daegu, the case that had been checked, was right only because 13R and 31L share 108.7. A runway with
 * one header serves no slot (its other end does not exist to the sim), nor does a runway past the count.
 * Returns Map(header → slot).
 */
export function ilsEnds(headers, runways) {
  const out = new Map();
  const count = Math.min(runways > 0 ? runways : 2, 2);
  for (let q = 0; q < count; q++) {
    const list = headers.filter((h) => h.rwyNo === q);
    if (list.length < 2) continue;
    const pair = [list[0], list[list.length - 1]];
    out.set(pair[1], 2 * q);
    out.set(pair[0], 2 * q + 1);
  }
  return out;
}

/**
 * The ATC file's entry for a runway end (its QFU and its pattern): the one on the end's own runway (Database Runway
 * "Number" = the header's RunwayNumber) whose axis lies along the end's heading. Up to 1.4.0 it was the entry at the
 * header's place in PHD order, and the ATC files do not keep that order: Aviano's lists 05 first and its PHD the 230°
 * end first, so the 230° end was named 05 (with 05's pattern) and the 050° end 23 — 121 ends in the Balkans, 17 in the
 * Falklands, Skopje and Tanagra in Hellas, every one of them the two names of a runway the wrong way round, and with
 * them the ILS figure printed against the wrong number. An entry with no axis is taken by place, as before.
 */
function atcRunwayFor(atc, h) {
  const list = atc?.runways || [];
  const along = (r) => r.axisTrue != null && Math.abs(((r.axisTrue - h.hdg) % 360 + 540) % 360 - 180) < 30;
  const byPlace = list[h.order] || null;
  // a header stating course 0 says nothing (Novo Mesto, Korce): it keeps the entry at its place
  if (!h.hdg) return byPlace;
  return list.find((r) => r.dbRunway === h.rwyNo && along(r)) || list.find(along)
    || (byPlace && byPlace.axisTrue == null ? byPlace : null);
}

function runwayLength(h, geo) {
  const on = (rx) => geo.feats.filter((t) => rx.test(t.name)).filter((t) => Math.abs(t.e * h.r[0] + t.n * h.r[1] - h.lateral) < 120);
  for (const rx of [/lights\s*THR/i, /runway\s*THR/i, /runway|rwy/i]) {
    const list = on(rx);
    if (list.length >= 2) {
      const along = list.map((t) => t.e * h.f[0] + t.n * h.f[1]);
      const len = Math.max(...along) - Math.min(...along);
      if (len > 1000) return Math.round(len / 10) * 10;
    }
  }
  return null;
}

export function buildAirports(th) {
  const db = loadDb(th);
  const camp = parseRecords(findFileCI(th.campaignDir, 'CampObjData.XML'), 'CampObj') || [];
  // parseRecords keys on Num; CampObjData uses CampId attribute instead.
  const campText = readText(findFileCI(th.campaignDir, 'CampObjData.XML'));
  const objs = new Map();
  for (const m of campText.matchAll(/<CampObj CampId="(\d+)">([\s\S]*?)<\/CampObj>/g)) {
    const g = (t) => (m[2].match(new RegExp(`<${t}>([^<]*)</${t}>`)) || [])[1];
    objs.set(+m[1], { campId: +m[1], name: (g('CampName') || '').trim(), ocd: +g('OcdIndex'), x: +g('PositionX'), y: +g('PositionY') });
  }
  void camp;
  const stations = parseStations(findFileCI(th.campaignDir, 'Stations+Ils.dat'));
  const atcByCamp = loadAtcDir(th);
  // BMS's own terrain, for the fields whose ATC file gives no elevation (elevation.mjs)
  const terrain = terrainSampler(th);
  // and BMS's levelling of it under the airbases (tld.mjs), which wins over both
  const lev = levelling(th);
  // how each field's elevation was found, for the checks (not written out)
  const elevations = new Map();

  const ocdType = (ocd) => {
    const ocdDir = findFileCI(path.join(th.data3dDir, 'ObjectiveRelatedData'), `OCD_${String(ocd).padStart(5, '0')}`)
      || findFileCI(path.join(th.objectDir, 'ObjectiveRelatedData'), `OCD_${String(ocd).padStart(5, '0')}`)
      || findFileCI(path.join(DATA, 'TerrData/Objects/ObjectiveRelatedData'), `OCD_${String(ocd).padStart(5, '0')}`);
    if (!ocdDir) return null;
    const rec = parseRecords(findFileCI(ocdDir, `OCD_${String(ocd).padStart(5, '0')}.XML`), 'OCD')?.[ocd];
    if (!rec) return null;
    const c = db.ct[+rec.CtIdx];
    // DataRate is the field's number of runways to the sim (ATCBrain's constructor reads it as such: 1 or 2 at the
    // fields, 7 on the carriers, held to 2), which decides how many ILS slots it serves (ilsEnds)
    return c && +c.Domain === 3 && +c.Class === 4 ? { type: +c.Type, name: rec.Name, runways: +rec.DataRate || 0 } : null;
  };

  const airports = [];
  // What the ground chart needs on top of the airport record: which object class the field is, and which point
  // header belongs to which runway end. The designators are worked out here (from the threshold features), so the
  // chart must not work them out again and risk disagreeing with the rest of the app.
  const geoByCamp = new Map();
  const navaids = [];
  const placeRaw = [];
  const typeCache = new Map();
  // Every field's own objective name (1.4.1). A station line's comment is only a label, and it can name another field:
  // Korea's line for CampId 947 (Sangwon Highwaystrip) says "# Songwhan Highway Strip", the name of objective 925, which
  // has no line at all — so the app showed two "Songwhan"s, the northern one with radios. A comment naming another
  // field is not this one's full name (nor where its ICAO code comes from). BMS's genuine other names (Kumi for Gumi,
  // Koksan for Kangdong) name no objective, so they stay.
  const fieldKey = (s) => (s || '').replace(/\([A-Z0-9]{4}\)/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '');
  const fieldByName = new Map();
  for (const o of objs.values()) {
    if (!typeCache.has(o.ocd)) typeCache.set(o.ocd, ocdType(o.ocd));
    const t = typeCache.get(o.ocd);
    if (t && OBJ_TYPES[t.type] && o.name && !fieldByName.has(fieldKey(o.name))) fieldByName.set(fieldKey(o.name), o.campId);
  }
  const namesAnother = (label, campId) => { const c = fieldByName.get(fieldKey(label)); return c != null && c !== campId; };
  for (const o of objs.values()) {
    if (!typeCache.has(o.ocd)) typeCache.set(o.ocd, ocdType(o.ocd));
    const t = typeCache.get(o.ocd);
    const placeKind = t && PLACE_TYPES[t.type];
    if (placeKind && o.name) placeRaw.push({ n: o.name.replace(/&amp;/g, '&').trim(), t: placeKind, x: o.x, y: o.y });
    const st = stationFor(stations.get(o.campId), o.name);
    const isAirfield = t && OBJ_TYPES[t.type];
    if (!isAirfield) {
      if (st?.tacan && !st.towerUhf) navaids.push({ campId: o.campId, name: st.label || o.name, objective: o.name, x: Math.round(o.x), y: Math.round(o.y), tacan: st.tacan });
      continue;
    }
    const ownLabel = st?.label && !namesAnother(st.label, o.campId) ? st.label : null;
    const icao = (o.name.match(/\(([A-Z0-9]{4})\)/) || ownLabel?.match(/\(([A-Z0-9]{4})\)/) || [])[1] || null;
    const atc = atcByCamp.get(o.campId) || null;
    const geo = runwayGeometry(th, db, o.ocd);
    if (!(geo && geo.headers.length) && !st?.towerUhf && !st?.towerVhf) continue;
    const runways = [];
    if (geo && geo.headers.length) {
      // which end each ILS frequency serves: the sim's own rule (ilsEnds), PHD order swapped within each RunwayNumber
      const ilsSlot = ilsEnds(geo.headers, t.runways);
      const ends = geo.headers.map((h) => {
        const atcRwy = atcRunwayFor(atc, h);
        let des = designatorFor(h, geo) || atcRwy?.qfu || String(Math.round(h.hdg / 10) || 36).padStart(2, '0');
        return {
          h, des,
          hdgTrue: Math.round(h.hdg * 10) / 10,
          ils: st && ilsSlot.has(h) ? st.ils[ilsSlot.get(h)] || null : null,
          pattern: atcRwy ? {
            overheadSide: atcRwy.overheadSide ?? null, hasBase: !!atcRwy.hasBase, hasLongFinal: !!atcRwy.hasLongFinal,
            final: atcRwy.final, base: atcRwy.base, entry: atcRwy.entry, holding: atcRwy.holding, loiter: atcRwy.loiter ?? null,
            longEntry: atcRwy.longEntry, longHolding: atcRwy.longHolding,
          } : null,
        };
      });
      // add L/R suffix for parallel runways without named thresholds
      const byNum = new Map();
      for (const e of ends) { const k = e.des.replace(/[LRC]$/, ''); (byNum.get(k) || byNum.set(k, []).get(k)).push(e); }
      for (const list of byNum.values()) {
        if (list.length === 2 && !/[LRC]$/.test(list[0].des) && list[0].h.rwyNo !== list[1].h.rwyNo) {
          list.sort((a, b) => a.h.lateral - b.h.lateral);
          list[0].des += 'L'; list[1].des += 'R';
        }
      }
      geoByCamp.set(o.campId, {
        ocd: o.ocd,
        ends: ends.map((e) => ({ order: e.h.order, designator: e.des, rwyNo: e.h.rwyNo, headingTrue: e.hdgTrue })),
      });
      const groups = new Map();
      for (const e of ends) (groups.get(e.h.rwyNo) || groups.set(e.h.rwyNo, []).get(e.h.rwyNo)).push(e);
      for (const [rwyNo, list] of groups) {
        // split same runway number into pairs of reciprocal headings
        const pairs = [];
        const used = new Set();
        for (const a of list) {
          if (used.has(a)) continue; used.add(a);
          const b = list.find((x) => !used.has(x) && Math.abs(((x.hdgTrue - a.hdgTrue + 360) % 360) - 180) < 10 && Math.abs(x.h.lateral + a.h.lateral) < 200);
          if (b) used.add(b);
          pairs.push(b ? [a, b] : [a]);
        }
        for (const p of pairs) {
          p.sort((a, b) => parseInt(a.des, 10) - parseInt(b.des, 10));
          // the length and width are BMS's own runway rectangle's (the ground chart's, to the foot); only a field with
          // none that fits keeps the old estimate between the threshold lights (often thousands of feet short: Souda's
          // 8,800 for 10,990), marked lenEst. A ship (no features at all) has no strip: its rectangle is the deck's
          // approach path, and the estimate finds nothing on it either.
          const strip = geo.feats.length ? stripFor(p[0].h, geo) : null;
          const est = strip ? null : runwayLength(p[0].h, geo);
          runways.push({
            dbRunway: rwyNo,
            name: p.map((e) => e.des).join('/'),
            lengthFt: strip ? strip.lengthFt : est,
            ...(strip ? { widthFt: strip.widthFt } : est != null ? { lenEst: true } : {}),
            ends: p.map((e) => ({ designator: e.des, headingTrue: e.hdgTrue, ils: e.ils, pattern: e.pattern })),
          });
        }
      }
    }
    const isCarrier = /(^|\s)(CV|CVN|LHD|USS|TAKR|Carrier)\b/i.test(o.name);
    if (isCarrier && runways.length) {
      const ends = runways.flatMap((r) => r.ends).filter((e) => e.ils);
      runways.length = 0;
      runways.push({ dbRunway: 0, name: "Deck", lengthFt: null, ends: (ends.length ? ends : []).map((e) => ({ ...e, designator: "Deck " + e.designator, pattern: null })) });
    }
    const ground = isCarrier || !geo?.feats?.length ? null : {
      tld: levelledUnder(lev, terrain, o.x, o.y, geo.rects),
      map: groundUnder(terrain, o.x, o.y, geo.rects),
    };
    elevations.set(o.campId, { name: o.name, atc: atc?.elevationFt ?? null, tld: ground?.tld?.ft ?? null, zones: ground?.tld?.zones ?? [], map: ground?.map ?? null });
    airports.push({
      id: o.campId,
      name: o.name.replace(/\s*\([A-Z0-9]{4}\)\s*$/, '').trim(),
      fullName: ownLabel || o.name,
      icao,
      type: isCarrier ? 'Carrier' : OBJ_TYPES[t.type],
      x: Math.round(o.x), y: Math.round(o.y),
      // the level BMS flattens the field's runways to (TerrainRelatedData/TLD_<CampId>.TPB, tld.mjs); else the ATC
      // file's figure; where it has none, or 0 (Hellas's placeholder: Kasteli is 1,180 ft up), the ground BMS puts the
      // jet on, its height map under the runways (elevation.mjs). A ship has none: it is told by having no features
      // at all, as the ground charts tell it (the Falklands type their whole fleet "Airbase")
      elevationFt: ground?.tld?.ft ?? ((atc?.elevationFt ?? 0) !== 0 ? null : ground?.map) ?? atc?.elevationFt ?? null,
      tacan: st?.tacan ? { channel: st.tacan.channel, band: st.tacan.band, rangeNm: st.tacan.rangeNm || null } : null,
      freqs: st ? { towerUhf: st.towerUhf, towerVhf: st.towerVhf, groundUhf: st.groundUhf, approachUhf: st.approachUhf, opsUhf: st.opsUhf, lsoUhf: st.lsoUhf, atisVhf: st.atisVhf } : null,
      atc: atc ? { activeRunways: atc.activeRunways ?? null, shortPattern: atc.shortPattern ?? false, ifrMinVisM: atc.ifrMinVisM ?? null, ifrMinCloudFt: atc.ifrMinCloudFt ?? null, vfrMinVisM: atc.vfrMinVisM ?? null, vfrMinCloudFt: atc.vfrMinCloudFt ?? null } : null,
      runways,
    });
  }
  // Radio map (package/flight/agency frequencies)
  const radio = [];
  const rm = readText(findFileCI(th.campaignDir, 'RadioMap.dat'), 'latin1') || '';
  for (const raw of rm.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('//')) continue;
    const f = line.split(',').map((s) => s.trim());
    if (f.length < 4) continue;
    const fr = (v) => (/^\d+$/.test(v) ? fmtUhf(v) : null);
    radio.push({ agency: f[0], uhf1: fr(f[1]), vhf: fr(f[2]), uhf2: fr(f[3]) });
  }
  // Many 4.38 airbases carry their TACAN as a separate navaid objective next to the field.
  for (const a of airports) {
    let best = null;
    for (const n of navaids) {
      const d = Math.hypot(n.x - a.x, n.y - a.y);
      if (d < 6076 * 4 && (!best || d < best.d)) best = { n, d };
    }
    if (best && !a.tacan) a.tacan = { channel: best.n.tacan.channel, band: best.n.tacan.band, rangeNm: best.n.tacan.rangeNm || null, station: best.n.name };
    if (best) best.n.nearAirport = a.id;
  }
  airports.sort((a, b) => a.name.localeCompare(b.name));
  // Cities are made of several objectives with the same name: merge same-name places within 8 nm.
  const places = [];
  const rank = { city: 0, town: 1, village: 2 };
  for (const p of placeRaw) {
    const hit = places.find((q) => q.n === p.n && Math.hypot(q.x - p.x, q.y - p.y) < 8 * 6076);
    if (hit) { hit.k++; hit.x += (p.x - hit.x) / hit.k; hit.y += (p.y - hit.y) / hit.k; if (rank[p.t] < rank[hit.t]) hit.t = p.t; }
    else places.push({ ...p, k: 1 });
  }
  const placeList = places.map((p) => ({ n: p.n, t: p.t, x: Math.round(p.x), y: Math.round(p.y) }))
    .sort((a, b) => rank[a.t] - rank[b.t] || a.n.localeCompare(b.n));
  terrain?.close();
  return { airports, navaids, radio, places: placeList, geo: geoByCamp, elevations };
}
