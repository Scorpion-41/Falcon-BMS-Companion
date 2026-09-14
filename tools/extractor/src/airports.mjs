// Airports / navaids per theater: CampObjData + Stations+Ils.dat + ATC/*.dat + runway geometry (PHD/PDX/FED).
import path from 'node:path';
import fs from 'node:fs';
import { DATA, parseRecords, findFileCI, readText } from './util.mjs';
import { loadDb } from './db.mjs';

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
    out.set(id, {
      campId: id,
      label: lastComment,
      tacan: +f[1] > 0 && !(+f[1] === 1 && +f[4] === 0) ? { channel: +f[1], band: f[2], callsignIdx: +f[3], rangeNm: +f[4], type: +f[5] } : null,
      towerUhf: fmtUhf(f[6]), towerVhf: fmtUhf(f[7]),
      ils: [fmtIls(f[8]), fmtIls(f[9]), fmtIls(f[10]), fmtIls(f[11])],
      opsUhf: fmtUhf(f[12]), groundUhf: fmtUhf(f[13]), approachUhf: fmtUhf(f[14]), lsoUhf: fmtUhf(f[15]), atisVhf: fmtUhf(f[16]),
    });
    lastComment = null;
  }
  return out;
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
  return { headers, feats };
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

  const ocdType = (ocd) => {
    const ocdDir = findFileCI(path.join(th.data3dDir, 'ObjectiveRelatedData'), `OCD_${String(ocd).padStart(5, '0')}`)
      || findFileCI(path.join(th.objectDir, 'ObjectiveRelatedData'), `OCD_${String(ocd).padStart(5, '0')}`)
      || findFileCI(path.join(DATA, 'TerrData/Objects/ObjectiveRelatedData'), `OCD_${String(ocd).padStart(5, '0')}`);
    if (!ocdDir) return null;
    const rec = parseRecords(findFileCI(ocdDir, `OCD_${String(ocd).padStart(5, '0')}.XML`), 'OCD')?.[ocd];
    if (!rec) return null;
    const c = db.ct[+rec.CtIdx];
    return c && +c.Domain === 3 && +c.Class === 4 ? { type: +c.Type, name: rec.Name } : null;
  };

  const airports = [];
  const navaids = [];
  const placeRaw = [];
  const typeCache = new Map();
  for (const o of objs.values()) {
    if (!typeCache.has(o.ocd)) typeCache.set(o.ocd, ocdType(o.ocd));
    const t = typeCache.get(o.ocd);
    const placeKind = t && PLACE_TYPES[t.type];
    if (placeKind && o.name) placeRaw.push({ n: o.name.replace(/&amp;/g, '&').trim(), t: placeKind, x: o.x, y: o.y });
    const st = stations.get(o.campId);
    const isAirfield = t && OBJ_TYPES[t.type];
    if (!isAirfield) {
      if (st?.tacan && !st.towerUhf) navaids.push({ campId: o.campId, name: st.label || o.name, objective: o.name, x: Math.round(o.x), y: Math.round(o.y), tacan: st.tacan });
      continue;
    }
    const icao = (o.name.match(/\(([A-Z0-9]{4})\)/) || st?.label?.match(/\(([A-Z0-9]{4})\)/) || [])[1] || null;
    const atc = atcByCamp.get(o.campId) || null;
    const geo = runwayGeometry(th, db, o.ocd);
    if (!(geo && geo.headers.length) && !st?.towerUhf && !st?.towerVhf) continue;
    const runways = [];
    if (geo && geo.headers.length) {
      // ILS slots follow runway ends ordered by database runway number (verified: Daegu ILS 31L 108.7).
      const ilsOrder = [...geo.headers].sort((a, b) => a.rwyNo - b.rwyNo || a.order - b.order);
      const ends = geo.headers.map((h) => {
        const atcRwy = atc?.runways?.[h.order] || null;
        let des = designatorFor(h, geo) || atcRwy?.qfu || String(Math.round(h.hdg / 10) || 36).padStart(2, '0');
        return {
          h, des,
          hdgTrue: Math.round(h.hdg * 10) / 10,
          ils: st ? st.ils[ilsOrder.indexOf(h)] || null : null,
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
          runways.push({
            dbRunway: rwyNo,
            name: p.map((e) => e.des).join('/'),
            lengthFt: runwayLength(p[0].h, geo),
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
    airports.push({
      id: o.campId,
      name: o.name.replace(/\s*\([A-Z0-9]{4}\)\s*$/, '').trim(),
      fullName: st?.label || o.name,
      icao,
      type: isCarrier ? 'Carrier' : OBJ_TYPES[t.type],
      x: Math.round(o.x), y: Math.round(o.y),
      elevationFt: atc?.elevationFt ?? null,
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
  return { airports, navaids, radio, places: placeList };
}
