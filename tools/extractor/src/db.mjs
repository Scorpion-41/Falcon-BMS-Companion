// Loads a theater's object database with fallback to the default (KTO) files, like BMS does.
import path from 'node:path';
import fs from 'node:fs';
import { DATA, parseRecords, findFileCI, readText, decodeEntities } from './util.mjs';

const DEFAULT_OBJ = path.join(DATA, 'TerrData/Objects');
const DEFAULT_SIM = path.join(DATA, 'Sim');

function objFile(th, name) {
  return findFileCI(th.objectDir, name) || findFileCI(DEFAULT_OBJ, name);
}

const dbCache = new Map();

export function loadDb(th) {
  const key = th.objectDir + '|' + th.simDir + '|' + th.terrDataDir;
  if (dbCache.has(key)) return dbCache.get(key);
  const rec = (n, tag) => parseRecords(objFile(th, n), tag) || [];
  const db = {
    files: {},
    ct: rec('Falcon4_CT.xml', 'CT'),
    vcd: rec('Falcon4_VCD.xml', 'VCD'),
    wcd: rec('Falcon4_WCD.xml', 'WCD'),
    wld: rec('Falcon4_WLD.xml', 'WLD'),
    acd: rec('Falcon4_ACD.xml', 'ACD'),
    swd: rec('Falcon4_SWD.xml', 'SWD'),
    rcd: rec('Falcon4_RCD.xml', 'RCD'),
    fcd: rec('Falcon4_FCD.xml', 'FCD'),
    ocd: rec('Falcon4_OCD.xml', 'OCD'),
    ssd: rec('Falcon4_SSD.xml', 'SSD'),
    ucd: rec('Falcon4_UCD.xml', 'UCD'),
  };
  db.files.bmsRack = objFile(th, 'BmsRack.dat');
  db.racks = parseBmsRack(readText(db.files.bmsRack, 'latin1') || '');
  const acdataDir = findFileCI(th.simDir, 'Acdata') || path.join(DEFAULT_SIM, 'Acdata');
  db.acdataDir = acdataDir;
  const typesFile = findFileCI(acdataDir, 'AcTypes.lst') || path.join(DEFAULT_SIM, 'Acdata/AcTypes.lst');
  db.acTypes = (readText(typesFile, 'latin1') || '').split(/\r?\n/).map((s) => s.trim()).filter((s, i) => s || i === 0);
  const tacrefFile = findFileCI(th.terrDataDir, 'TacRefDB.xml') || path.join(DATA, 'TerrData/TacRefDB.xml');
  db.files.tacref = tacrefFile;
  db.tacref = parseTacRef(tacrefFile);
  dbCache.set(key, db);
  return db;
}

/** BmsRack.dat: racks (definerack) and hardpoint groups (definegroup → addpylon → addrack). */
export function parseBmsRack(text) {
  const racks = {}, groups = {};
  let rack = null, group = null, pylon = null;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.replace(/#.*$/, '').trim();
    if (!line) continue;
    const [kwRaw, ...rest] = line.split(/[\s,]+/).filter(Boolean);
    const kw = kwRaw.toLowerCase();
    switch (kw) {
      case 'definerack':
        rack = racks[rest[0]] = { name: rest[0], ct: 0, stations: 1, wids: new Set(), swds: new Set(), wclasses: new Set(), any: false, sms: null };
        group = null; pylon = null; break;
      case 'definegroup':
        group = groups[rest[0]] = { name: rest[0], pylons: [] }; rack = null; pylon = null; break;
      case 'rackct': if (rack) rack.ct = +rest[0]; break;
      case 'rackstations': if (rack) rack.stations = +rest[0]; break;
      case 'racksmsname': if (rack) rack.sms = rest.join(' '); break;
      case 'addwid': if (rack) rest.forEach((v) => rack.wids.add(+v)); break;
      case 'addswd': if (rack) rest.forEach((v) => rack.swds.add(+v)); break;
      case 'addwclass': if (rack) rest.forEach((v) => rack.wclasses.add(v.toLowerCase())); break;
      case 'addany': if (rack) rack.any = true; break;
      case 'addpylon': if (group) { pylon = { ct: 0, sms: null, racks: [] }; group.pylons.push(pylon); } break;
      case 'pylonct': if (pylon) pylon.ct = +rest[0]; break;
      case 'pylonsmsname': if (pylon) pylon.sms = rest.join(' '); break;
      case 'addrack': if (pylon) pylon.racks.push(rest[0]); break;
      default: break;
    }
  }
  return { racks, groups };
}

const WCLASS = ['aim', 'rocket', 'bomb', 'gun', 'ecm', 'tank', 'agm', 'harm', 'sam', 'gbu', 'camera'];
export const wclassName = (n) => WCLASS[+n] ?? 'other';

function parseTacRef(file) {
  const x = readText(file, 'latin1');
  const byCt = new Map();
  if (!x) return byCt;
  const re = /<TacRefData Num="(\d+)">([\s\S]*?)<\/TacRefData>/g;
  let m;
  const g = (b, t) => { const k = b.match(new RegExp(`<${t}>([\\s\\S]*?)</${t}>`)); return k ? decodeEntities(k[1]).trim() : null; };
  while ((m = re.exec(x))) {
    const b = m[2];
    const main = g(b, 'MainData') || '';
    const cats = [];
    const cre = /<CategoryData Num="\d+">([\s\S]*?)<\/CategoryData>/g;
    let c;
    while ((c = cre.exec(b))) {
      const title = g(c[1], 'CategoryTitle');
      const desc = g(c[1], 'CategoryDescription') || '';
      const lines = desc.split(/\r?\n/).map((l) => l.trim());
      cats.push({ title, lines });
    }
    const rwrBlock = g(b, 'RwrData') || '';
    const entry = {
      num: +m[1],
      category: +g(main, 'CategoryId'),
      subCategory: +g(main, 'SubCategoryId'),
      ct: +g(main, 'ClassTable'),
      name: (g(main, 'Name') || '').replace(/\s{2,}/g, ' '),
      pic: g(main, 'PicName'),
      sections: cats,
      description: (g(b, 'DescriptionData') || '').replace(/\s+/g, ' ').trim(),
      rwr: { name: g(rwrBlock, 'Name'), image: +g(rwrBlock, 'Image'), index: +g(rwrBlock, 'Index') },
    };
    if (!byCt.has(entry.ct)) byCt.set(entry.ct, entry);
  }
  return byCt;
}

/** Pull a handful of scalar values out of an aircraft .txtpb flight model. */
export function readAcdata(db, name) {
  if (!name) return null;
  const file = findFileCI(db.acdataDir, name + '.txtpb') || findFileCI(path.join(DEFAULT_SIM, 'Acdata'), name + '.txtpb');
  if (!file) return null;
  const t = readText(file, 'latin1');
  const scalar = (k) => { const m = t.match(new RegExp(`^\\s*${k}:\\s*([-\\d.]+)`, 'm')); return m ? +m[1] : null; };
  const hp = [];
  const hpBlock = t.match(/hardpoints\s*\{([\s\S]*?)is_external_hardpoint/);
  if (hpBlock) for (const m of hpBlock[1].matchAll(/hardpoint_grp:\s*"([^"]*)"/g)) hp.push(m[1]);
  const chaff = [...t.matchAll(/chaff_dispencer\s*\{[\s\S]*?decoys:\s*(\d+)/g)].map((m) => +m[1]);
  const flare = [...t.matchAll(/flare_dispencer\s*\{[\s\S]*?decoys:\s*(\d+)/g)].map((m) => +m[1]);
  return {
    file: path.basename(file),
    fm: (t.match(/^fmtype:\s*(\w+)/m) || [])[1] || null,
    emptyWeightLbs: scalar('empty_weight_lbs'),
    internalFuelLbs: scalar('internal_fuel_lbs'),
    wingAreaSqft: scalar('area_sqft'),
    maxG: scalar('max_g'),
    aoaMax: scalar('aoa_max_player_deg'),
    maxVcasKts: scalar('max_vcas_kts'),
    cornerVcasKts: scalar('corner_vcas_kts'),
    minVcasKts: scalar('min_vcas_kts'),
    lengthFt: scalar('length_ft'),
    spanFt: scalar('span_ft'),
    maxRollDeg: scalar('max_roll_deg'),
    chaff: chaff.reduce((a, b) => a + b, 0) || null,
    flares: flare.reduce((a, b) => a + b, 0) || null,
    hardpointGroups: hp,
  };
}

export function listAcdataFiles(db) {
  return fs.existsSync(db.acdataDir) ? fs.readdirSync(db.acdataDir) : [];
}
