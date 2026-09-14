// Aircraft + weapons catalog for one theater (flyable aircraft, stations, allowed weapons).
import { loadDb, readAcdata, wclassName } from './db.mjs';
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

/** Is a WCD record a real store (not a rack dummy / placeholder)? */
function isRealWeapon(w, ct) {
  if (!w || !ct) return false;
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
  const tac = db.tacref.get(+w.CtIdx);
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
    const tac = db.tacref.get(+c.Num);
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
