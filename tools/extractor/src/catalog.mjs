// Aircraft + weapons catalog for one theater (flyable aircraft, stations, allowed weapons).
import { loadDb, readAcdata, wclassName, ctName, tacrefHomeDb } from './db.mjs';
import { slug, num } from './util.mjs';

export const AC_ROLES = { 1: 'Airplane', 2: 'Attack', 3: 'AWACS', 4: 'Bomber', 5: 'Electronic Warfare', 6: 'Fighter', 7: 'Multirole', 8: 'Surveillance', 9: 'Reconnaissance', 10: 'Tanker', 11: 'Transport', 12: 'ELINT' };

const TACREF_WEAPON_CAT = {
  8410: 'AAM_IR', 8420: 'AAM_RADAR', 8430: 'ANTI_SHIP', 8440: 'ARM', 8450: 'AGM', 8460: 'SAM', 8470: 'ATGM',
  8510: 'BOMB_GP', 8520: 'BOMB_LGB', 8530: 'BOMB_GUIDED', 8540: 'BOMB_CLUSTER', 8550: 'BOMB_INCENDIARY', 8560: 'BOMB_SPECIAL', 8570: 'BOMB_NUCLEAR',
  8610: 'FUEL_TANK', 8620: 'RECON_POD', 8630: 'ECM_POD', 8640: 'CM_POD', 8650: 'TARGETING_POD', 8660: 'ROCKETS', 8670: 'AVIONICS_POD',
};

export const GUIDANCE_FLAGS = [[1, 'Anti-radiation'], [2, 'IR / Heat'], [4, 'Radar'], [8, 'Laser'], [16, 'TV / EO'], [32, 'Rear aspect'], [64, 'Command / SARH']];
const guidanceText = (g) => GUIDANCE_FLAGS.filter(([b]) => (g & b) !== 0).map(([, t]) => t);

function weaponCategory(ct, w, swd, tac) {
  if (tac && TACREF_WEAPON_CAT[tac.subCategory]) {
    const c = TACREF_WEAPON_CAT[tac.subCategory];
    // TacRef groups GBU-10/12 etc. correctly; keep it.
    return c;
  }
  const cls = swd ? wclassName(swd.WpnClass) : null;
  const g = +w.Guidance || 0;
  const [d, k, t, s] = [+ct.Domain, +ct.Class, +ct.Type, +ct.SubType];
  if (cls === 'gun' || (d === 1 && k === 8 && t === 3)) return 'GUN';
  if (d === 1 && k === 8 && t === 5) return 'ROCKETS';
  if (d === 2 && k === 7) {
    if (t === 6) {
      if (s === 1) return g & 4 || g & 64 ? 'AAM_RADAR' : 'AAM_IR';
      if (s === 2) return g & 1 ? 'ARM' : 'AGM';
      if (s === 3) return 'ANTI_SHIP';
      if (s === 5) return 'SAM';
      return 'MISSILE_OTHER';
    }
    if (t === 2) {
      if (/^B\s?(28|43|53|57|61|83)\b/.test(w.Name)) return 'BOMB_NUCLEAR';
      return ({ 1: 'BOMB_GP', 2: 'BOMB_LGB', 3: 'BOMB_GP', 4: 'BOMB_GUIDED', 5: 'BOMB_CLUSTER' })[s] || 'BOMB_GP';
    }
    if (t === 3) return ({ 1: 'ECM_POD', 2: 'CM_POD', 5: 'TARGETING_POD', 6: 'TARGETING_POD', 8: 'AVIONICS_POD', 9: 'AVIONICS_POD', 10: 'AVIONICS_POD', 11: 'AVIONICS_POD', 12: 'GUN_POD' })[s] || 'AVIONICS_POD';
    if (t === 4) return 'FUEL_TANK';
    if (t === 7) return 'RECON_POD';
    if (t === 8) return s === 2 ? 'ROCKETS' : 'GUN';
  }
  if (cls === 'tank') return 'FUEL_TANK';
  if (cls === 'ecm') return 'ECM_POD';
  if (cls === 'camera') return 'RECON_POD';
  return 'OTHER';
}

// a name as words, for comparing an object with a TacRef entry: "F-16C B52+ HAF" → f16c block 52+ haf
const nameWords = (s) => (s || '').replace(/\([^)]*\)/g, ' ').replace(/\bBlk\b/gi, 'Block').replace(/\bB(?=\d)/g, 'Block ')
  .split(/[\s/]+/).map((w) => w.toLowerCase().replace(/[^a-z0-9+]/g, '')).filter(Boolean);

/**
 * The TacRef entry BMS links to class-table row `ct` (the entry's own `ClassTable`), and so its picture, or null.
 * The link is BMS's own when the TacRef file sits beside the theater's own object files. A theater that borrows
 * another's TacRef (Hellas, LHTO and Hellas WCP use Data/TerrData's; the Korea 2012 six, LKTO, OFMKTO, TvT and KTO 80s
 * too; EF2000 BTO uses Balkans's) only borrows the numbers: its row `ct` may be another object altogether (Hellas's
 * 2421 is its "M2k-5 HAF", the TacRef's is the Mirage 2000EGM; its 2472 is a placeholder). So a borrowed link is
 * kept only when the row is the same object in both tables (the same name), or the object's name is the entry's
 * name word for word ("F-15J" → "F-15J Peace Eagle", "F-16C B52+ HAF" → "F-16C Block 52+ HAF w/o CFT").
 */
// Borrowed links the rules below would refuse but that are the same store under another name (reviewed, 1.3.9):
// object name → the TacRef entry it is
const SAME_STORE = { 'AIM-120C5': 'AIM-120C AMRAAM', 'AIM-2000': 'IRIS-T', 'KEPD350 TAURUS': 'Taurus KEPD 350' };

export function tacrefFor(db, ct, name) {
  const tac = db.tacref.get(ct);
  if (!tac) return null;
  if (db.tacrefOwn) return tac;
  if (SAME_STORE[name] === tac.name) return tac;
  const home = tacrefHomeDb(db);
  const ours = ctName(db, ct) ?? name;
  const theirs = home ? ctName(home, ct) : null;
  const norm = (s) => (s || '').toLowerCase().replace(/[^a-z0-9]/g, '');
  if (theirs && norm(theirs) === norm(ours)) return tac;
  const a = nameWords(name), b = nameWords(tac.name);
  if (a.length && a.length <= b.length && a.every((w, i) => w === b[i])) return tac;
  // a weapon (EntityType 6) may also carry the entry's designation: "AGM-78 ARM" → "AGM-78 Standard", "YJ-83K" →
  // "CSS-N-8 Saccade (YJ-83K)", "3M80 (Kh-41)" → "SS-N-22 Sunburn (Kh-41)"; never an aircraft, whose national
  // variants share a designation
  if (+db.ct[ct]?.EntityType === 6) {
    const des = (s) => [(s || '').trim().split(/\s+/)[0], ...[...(s || '').matchAll(/\(([^)]*)\)/g)].map((m) => m[1])]
      .map((d) => d.toLowerCase().replace(/[^a-z0-9]/g, '')).filter((d) => /\d/.test(d) && /[a-z]/.test(d));
    const theirsDes = new Set(des(tac.name));
    if (des(name).some((d) => theirsDes.has(d))) return tac;
  }
  return null;
}

/**
 * A class-table or TacRef row BMS keeps as an empty slot rather than a real thing: "*free" (in the F-16 family),
 * "--Free Slot--" (in the Eurofighter's), an empty name, "none", "placeholder". Never listed anywhere, and never a
 * carrier of a store. Whole-name matches only: "F-5A Freedom Fighter" is real.
 */
export function isPlaceholderName(name) {
  const n = (name || '').trim().replace(/^[-*_.\s]+|[-*_.\s]+$/g, '');
  return n === '' || /^(free(\s*slot)?|none|empty|unused|n\/?a|aircraft)$/i.test(n) || /placeholder/i.test(n);
}

/** Is a WCD record a real store (not a rack dummy / placeholder)? */
function isRealWeapon(w, ct) {
  if (!w || !ct) return false;
  if (isPlaceholderName(w.Name)) return false;
  if (/^(- No Weapon|R\s|Empty w\/Pylon)/.test(w.Name)) return false;
  if (+ct.Domain === 1 && +ct.Class === 8 && +ct.Type === 4) return false; // racks
  return true;
}

function rackAccepts(rack, wid, swdIdx, cls) {
  if (!rack) return false;
  return rack.any || rack.wids.has(wid) || rack.swds.has(swdIdx) || (cls && rack.wclasses.has(cls));
}

function prettyGroup(g) {
  if (!g || g === '0') return null;
  return g.replace(/^[a-z0-9+.]+?-(?=[0-9a-z])/i, '').replace(/[-_]/g, ' ');
}

/** Build weapon record (theater-local). */
function buildWeapon(db, wid) {
  const w = db.wcd[wid];
  if (!w) return null;
  const ct = db.ct[+w.CtIdx];
  if (!isRealWeapon(w, ct)) return null;
  const swd = ct ? db.swd[+ct.MoverDefinitionData] : null;
  const tac = tacrefFor(db, +w.CtIdx, w.Name.trim());
  const g = +w.Guidance || 0;
  return {
    key: slug(w.Name),
    name: w.Name.trim(),
    category: weaponCategory(ct, w, swd, tac),
    weightLbs: num(w.Weight),
    drag: num(w.Drag),
    rangeKm: num(w.Range),
    blastRadiusFt: num(w.BlastRadius),
    guidance: guidanceText(g),
    guidanceCode: g,
    simClass: swd ? wclassName(swd.WpnClass) : null,
    simName: swd?.WpnName ?? null,
    hits: { air: +w.Hit_Air, lowAir: +w.Hit_LowAir, ground: Math.max(+w.Hit_NoMove, +w.Hit_Wheeled, +w.Hit_Tracked), naval: +w.Hit_Naval },
    strength: num(w.Strength),
    tacref: tac ? tac.num : null,
    pic: tac?.pic ?? null,
    wid,
  };
}

export function buildCatalog(th) {
  const db = loadDb(th);
  const weapons = new Map(); // key -> record
  const useWeapon = (wid) => {
    const rec = buildWeapon(db, wid);
    if (!rec) return null;
    if (!weapons.has(rec.key)) weapons.set(rec.key, rec);
    return rec;
  };

  const aircraft = [];
  const seen = new Set();
  for (const c of db.ct) {
    if (!c || +c.Domain !== 2 || +c.Class !== 7 || +c.Type !== 1 || +c.EntityType !== 5) continue;
    const v = db.vcd[+c.EntityIdx];
    if (!v) continue;
    const flags = +v.Flags >>> 0;
    const flyable = (flags & 0x40000000) !== 0;
    if (!flyable) continue;
    const acd = db.acd[+c.MoverDefinitionData];
    const datName = acd ? db.acTypes[+acd.AirframeDatIdx + 1] : null;
    const name = v.Name.trim();
    if (isPlaceholderName(name)) continue; // an empty slot, not an aircraft: its stores must not count it a carrier
    const dedupeKey = name + '|' + datName;
    if (seen.has(dedupeKey)) continue;
    seen.add(dedupeKey);

    const fm = readAcdata(db, datName);
    const groups = fm?.hardpointGroups || [];
    const stations = [];
    let gun = null;
    for (let i = 0; i < 16; i++) {
      const idx = v[`WpnOrHpIdx_${i}`];
      const cnt = v[`WpnCount_${i}`];
      if (idx == null || cnt == null) continue;
      const count = +cnt;
      if (count !== 255) {
        // Fixed weapon (internal gun etc.)
        const rec = useWeapon(+idx);
        if (!rec) continue;
        if (rec.category === 'GUN' || rec.simClass === 'gun') gun = { weapon: rec.key, name: rec.name, rounds: count * 10 };
        else stations.push({ n: i, label: prettyGroup(groups[i]) || `Station ${i}`, group: groups[i] || null, fixed: true, weapons: [{ key: rec.key, max: count }] });
        continue;
      }
      const list = db.wld[+idx];
      if (!list) continue;
      const group = db.racks.groups[groups[i]];
      const allowed = [];
      for (let j = 0; j < 64; j++) {
        const wid = list[`WpnIdx_${j}`];
        if (wid == null) break;
        const rec = useWeapon(+wid);
        if (!rec) continue;
        let max = +(list[`WpnCount_${j}`] || 1);
        let viaRack = null;
        if (group) {
          const w = db.wcd[+wid]; const wct = db.ct[+w.CtIdx];
          const swdIdx = wct ? +wct.MoverDefinitionData : -1;
          const cls = rec.simClass;
          let best = 0;
          for (const p of group.pylons) for (const rn of p.racks) {
            const r = db.racks.racks[rn];
            if (rackAccepts(r, +wid, swdIdx, cls)) { if (r.stations > best) { best = r.stations; viaRack = r.sms || rn; } }
          }
          if (best === 0) continue; // no rack on this hardpoint can mount it
          max = best;
        }
        if (!allowed.some((a) => a.key === rec.key)) allowed.push({ key: rec.key, max, rack: viaRack });
      }
      if (allowed.length) stations.push({ n: i, label: prettyGroup(groups[i]) || list.Name || `Station ${i}`, group: groups[i] || null, list: list.Name || null, weapons: allowed });
    }

    const radar = db.rcd[+v.RadarIdx];
    const tac = tacrefFor(db, +c.Num, name);
    aircraft.push({
      key: slug(name),
      name,
      family: v.NCTR || null,
      role: AC_ROLES[+c.SubType] || 'Aircraft',
      ctIdx: +c.Num,
      datFile: datName,
      crew: num(v.NumberOfCrew),
      inService: num(v.InServiceStart),
      maxSpeedKts: num(v.MaxSpeed),
      ceilingFt: v.MaxAlt ? +v.MaxAlt * 100 : null,
      cruiseAltFt: v.CruiseAlt ? +v.CruiseAlt * 100 : null,
      maxWeightLbs: num(v.MaxWeight),
      emptyWeightLbs: fm?.emptyWeightLbs ?? num(v.EmptyWeight),
      internalFuelLbs: fm?.internalFuelLbs ?? num(v.FuelWeight),
      rcs: num(v.RadarCs),
      radar: radar && +v.RadarIdx > 0 ? { name: radar.Name, detectionNm: num(radar.DetectionRange), scanWidthDeg: num(radar.ScanWidth) } : null,
      fm: fm ? { type: fm.fm, maxG: fm.maxG, aoaMax: fm.aoaMax, maxVcasKts: fm.maxVcasKts, cornerKts: fm.cornerVcasKts, lengthFt: fm.lengthFt, spanFt: fm.spanFt, wingAreaSqft: fm.wingAreaSqft, chaff: fm.chaff, flares: fm.flares } : null,
      gun,
      stations,
      tacref: tac ? tac.num : null,
      pic: tac?.pic ?? null,
    });
  }
  return { aircraft, weapons: [...weapons.values()], db };
}
