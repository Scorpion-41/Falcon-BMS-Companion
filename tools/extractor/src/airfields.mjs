/**
 * Ground charts, out of Falcon BMS's own authored airfield data.
 *
 * BMS keeps every field it flies in `ObjectiveRelatedData/OCD_nnnnn/`: the objective class (OCD), its features (FED),
 * its point headers (PHD) and its points (PDX). The sim's own ground AI taxis on exactly these points, so a chart
 * drawn from them is the airfield as flown rather than an artist's impression — and it is the only place the ramp
 * spots exist at all.
 *
 * What the files hold, measured against Gunsan AB (the runway rectangle reproduces its published 9000 x 150 ft and
 * the authored course its 169.6 deg):
 *   header type 8   a runway: four corner points (type 8), then pairs across it (type 9, exits and cables)
 *   header type 1   a taxi route, one per runway end, in the same order the airport record's ends are built in
 *   point  type 3   a taxi point; carries the taxiway letter, plus BranchIdx/RootIdx (see below)
 *   point  type 15  a point on a runway crossing, type 21 a hold short, type 2 where the route meets its runway,
 *                   type 1 the far end of that runway
 *   point  type 11  a parking spot with the size of aircraft it takes, type 12 a spot without
 *
 * **The point list is a walk of a tree, not one chain.** A side taxiway is stored as its own run; the run's first
 * point carries RootIdx back to the point it leaves, and that point carries BranchIdx to the run. Joining the list
 * in order therefore invents edges that cross the whole field — ten of them at Gunsan, up to 6000 ft long. Each run
 * is joined at its root instead, which leaves the longest taxi leg there at 1373 ft.
 *
 * Offsets are in feet about the objective's own origin, X east and Y north. Every airfield objective in the stock
 * theaters carries heading 0, but the heading is applied anyway and [checkField] fails the build if a rotated
 * runway then disagrees with the heading the app's own airport record states for that end.
 */
import path from 'node:path';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { parseRecords, findFileCI, DATA, num } from './util.mjs';
import { fieldTriangles, pavementShapes } from './pavement.mjs';
import { shipShapes } from './ships.mjs';

const TYPE = {
  RUNWAY_END: 1, TAXI_START: 2, TAXI: 3, RUNWAY_EDGE: 8, RUNWAY_CROSS: 9,
  PARK_SIZED: 11, PARK: 12, ON_RUNWAY: 15, HOLD_SHORT: 21,
};
/** What an aircraft can roll along. The far runway end (type 1) belongs to the runway, not to the taxi network. */
const WAY_TYPES = new Set([TYPE.TAXI_START, TYPE.TAXI, TYPE.ON_RUNWAY, TYPE.HOLD_SHORT]);
const PARKING_TYPES = new Set([TYPE.PARK_SIZED, TYPE.PARK]);
const ROUTE_HEADER = 1;
const RUNWAY_HEADER = 8;

/**
 * What every object placed at a field is, and how large to draw it in plan.
 *
 * A field's feature list is the same list the sim builds the place out of — 255 objects at Osan — so drawing all of
 * it is what makes a flat chart look like the field the pilot taxis through. Each object's position and heading are
 * exact. Its **footprint is not**: BMS keeps that inside the 3D model and publishes no dimensions anywhere, so the
 * sizes below (feet, across x along) are representative of the real thing.
 *
 * The asphalt itself cannot be drawn from data: a field's pavement is one custom model — Osan's is a single object
 * called "RKSO Taxiways" — so the taxiways on the chart are BMS's own ground network drawn at real width.
 */
const FEATURE_KINDS = [
  // pavement first: patches of apron and taxiway, drawn under everything else
  [/taxiway (extension|curve)|pk area|parking area|apron/i, 'pavement', 220, 150],
  [/runway (section|stopway)/i, 'pavement', 300, 170],
  [/control tower/i, 'tower', 60, 60],
  [/\bHAS\b|shelter/i, 'shelter', 84, 66],
  [/hangar|advance maintenance/i, 'hangar', 200, 140],
  [/fuel|\bpol\b|tank/i, 'fuel', 70, 70],
  [/radar|radio tower|water tower|beacon|antenna|windsock|mast/i, 'mast', 34, 34],
  [/warehouse|depot|ammo/i, 'building', 130, 74],
  [/apartment|dormitory|barrack|house|terminal|office|admin|factory|technical|wing\/squad|shed|barn|building|works|maintenance/i, 'building', 96, 62],
  // walls and fences are lines rather than boxes, and say where a ramp ends
  [/wall|fence|blast barrier|revetment|barrier/i, 'wall', 130, 8],
  [/papi|lights?\b|floodlight/i, 'light', 14, 14],
];

/** The letters BMS signs its own taxiways with, and where it puts each sign. */
const TAXI_SIGN = /^Taxi Sign Pos - ([A-Z])$/;

const rad = (d) => (d * Math.PI) / 180;


/**
 * Whether an object stands over a point — a hardened shelter or a hangar over a ramp spot.
 *
 * The point data does not say whether a spot has a roof: its type 11 only means BMS records what size of aircraft
 * fits there, which is true of most spots at most fields and says nothing about shelter. What does say is the
 * field's own object list, which stands a shelter or a hangar on the spots that have one — the red-roofed
 * revetments a pilot sees taxiing in. So a spot is covered when one of those objects sits on top of it.
 *
 * [f] carries a compass heading, with [w] across it and [l] along it, so the point is turned into the object's own
 * frame before being tested against the box.
 */
function coveredBy(f, e, n) {
  const t = rad(f.h);
  const de = e - f.e;
  const dn = n - f.n;
  const along = de * Math.sin(t) + dn * Math.cos(t);
  const across = de * Math.cos(t) - dn * Math.sin(t);
  const slack = 6;
  return Math.abs(across) <= f.w / 2 + slack && Math.abs(along) <= f.l / 2 + slack;
}
const norm360 = (d) => ((d % 360) + 360) % 360;
const bearing = (a, b) => norm360((Math.atan2(b.e - a.e, b.n - a.n) * 180) / Math.PI);
const gap = (a, b) => Math.abs(norm360(a - b + 180) - 180);
const span = (a, b) => Math.hypot(a.e - b.e, a.n - b.n);
const mid = (a, b) => ({ e: (a.e + b.e) / 2, n: (a.n + b.n) / 2 });
const round = (p) => ({ e: Math.round(p.e), n: Math.round(p.n) });
const letterOf = (v) => (v >= 1 && v <= 26 ? String.fromCharCode(64 + v) : null);

function ocdDirFor(th, ocd) {
  const id = String(ocd).padStart(5, '0');
  return findFileCI(path.join(th.data3dDir, 'ObjectiveRelatedData'), `OCD_${id}`)
    || findFileCI(path.join(th.objectDir, 'ObjectiveRelatedData'), `OCD_${id}`)
    || findFileCI(path.join(DATA, 'TerrData/Objects/ObjectiveRelatedData'), `OCD_${id}`);
}

/** The authored points, turned upright: X east, Y north, rotated by the objective's own heading. */
function readPoints(dir, id, heading) {
  const raw = parseRecords(findFileCI(dir, `PDX_${id}.XML`), 'PD') || [];
  const c = Math.cos(rad(heading)), s = Math.sin(rad(heading));
  return raw.filter(Boolean).map((p, i) => {
    const e = num(p.OffsetX) ?? 0, n = num(p.OffsetY) ?? 0;
    return {
      i,
      type: num(p.Type),
      e: heading ? e * c + n * s : e,
      n: heading ? -e * s + n * c : n,
      letter: num(p.TaxiwayLetter),
      branch: num(p.BranchIdx),
      root: num(p.RootIdx),
      maxWidth: num(p.MaxWidth),
      group: num(p.ParkingPointGroup),
    };
  });
}

function readHeaders(dir, id) {
  return (parseRecords(findFileCI(dir, `PHD_${id}.XML`), 'PHD') || []).filter(Boolean).map((h, i) => ({
    i,
    type: num(h.Type),
    count: num(h.PointCount) ?? 0,
    first: num(h.FirstPtIdx) ?? 0,
    runway: num(h.RunwayNumber) ?? 0,
    course: num(h.Data),
  }));
}

function readFeatures(dir, id, db, heading) {
  const raw = parseRecords(findFileCI(dir, `FED_${id}.XML`), 'FED') || [];
  const c = Math.cos(rad(heading)), s = Math.sin(rad(heading));
  const out = [];
  const signs = [];
  for (const f of raw.filter(Boolean)) {
    const ct = db.ct[+f.FeatureCtIdx];
    const name = ct ? db.fcd[+ct.EntityIdx]?.Name || '' : '';
    const e0 = num(f.OffsetX) ?? 0, n0 = num(f.OffsetY) ?? 0;
    const e = Math.round(heading ? e0 * c + n0 * s : e0);
    const n = Math.round(heading ? -e0 * s + n0 * c : n0);
    const h = Math.round(norm360((num(f.Heading) ?? 0) - heading));

    const sign = name.match(TAXI_SIGN);
    if (sign) { signs.push({ l: sign[1], e, n }); continue; }

    const hit = FEATURE_KINDS.find(([re]) => re.test(name));
    if (!hit) continue;
    const [, kind, w, l] = hit;
    out.push({ k: kind, e, n, h, w, l });
  }
  return { features: out, signs };
}

/**
 * One taxi network, as authored: the points an aircraft rolls over, how they join, and the ramp spots that hang off
 * them. See the note at the top of the file for why the list order is not the topology.
 */
function buildRoute(header, points) {
  const slice = points.slice(header.first, header.first + header.count);
  const nodes = slice.map((p, k) => ({ ...p, k }));
  const isWay = (p) => WAY_TYPES.has(p.type);

  const rootOf = new Map();
  for (const p of nodes) {
    if (p.branch != null && p.branch >= 0 && p.branch < nodes.length) rootOf.set(p.branch, p.k);
    if (p.root != null && p.root >= 0 && p.root < nodes.length) rootOf.set(p.k, p.root);
  }

  const edges = [];
  let prevWay = null;
  for (const p of nodes) {
    if (!isWay(p)) continue;
    const root = rootOf.get(p.k);
    if (root !== undefined && isWay(nodes[root])) edges.push([root, p.k]);
    else if (prevWay !== null) edges.push([prevWay, p.k]);
    prevWay = p.k;
  }

  // Some fields step out to a far point and straight back (Athens' 22R route goes 3900 ft west and returns).
  // The point is kept, so the chart still shows the spur, but the two lane points either side are also joined:
  // without that the router drives 7000 ft to cross 200 ft of apron.
  const spurs = new Set();
  const wayList = nodes.filter(isWay);
  for (let i = 1; i + 1 < wayList.length; i++) {
    const [a, b, c] = [wayList[i - 1], wayList[i], wayList[i + 1]];
    if (span(a, b) > 2500 && span(b, c) > 2500 && span(a, c) < 1000) { edges.push([a.k, c.k]); spurs.add(b.k); }
  }

  // A spot is stored between the two taxi points either side of it and belongs to whichever is nearer; one at the
  // end of a run has both list neighbours far away, so the nearest lane point anywhere is taken instead.
  const lanes = nodes.filter(isWay);
  const parking = [];
  for (const p of nodes) {
    if (!PARKING_TYPES.has(p.type)) continue;
    let before = null, after = null;
    for (let k = p.k - 1; k >= 0; k--) if (isWay(nodes[k])) { before = nodes[k]; break; }
    for (let k = p.k + 1; k < nodes.length; k++) if (isWay(nodes[k])) { after = nodes[k]; break; }
    const to = (q) => (q ? span(p, q) : Infinity);
    let lane = to(before) <= to(after) ? before : after;
    if (to(lane) > 600) for (const q of lanes) if (to(q) < to(lane)) lane = q;
    if (!lane) continue;
    edges.push([lane.k, p.k]);
    parking.push({
      n: 0,                       // filled in below, in BMS's own order
      k: p.k,
      l: lane.k,
      w: p.maxWidth ? Math.round(p.maxWidth) : 0,
      s: p.type === TYPE.PARK_SIZED ? 1 : 0,
      group: p.group,
    });
  }

  numberParking(nodes, parking);

  // Where the route meets its runway. A dirt strip has no line-up point at all (Al-Muzabin is all on-runway
  // points), so the first point that is on the runway stands in for it.
  const start = nodes.find((p) => p.type === TYPE.TAXI_START)
    || nodes.find((p) => p.type === TYPE.ON_RUNWAY)
    || nodes.find(isWay);
  return {
    course: Math.round((header.course ?? 0) * 10) / 10,
    rwy: header.runway,
    start: start ? start.k : null,
    nodes: nodes.map((p) => {
      const o = { e: Math.round(p.e), n: Math.round(p.n), t: p.type };
      const l = letterOf(p.letter);
      if (l) o.l = l;
      return o;
    }),
    ed: edges.flat(),
    spurs: [...spurs],
    parking,
  };
}

/**
 * Numbers the ramp spots the way BMS does — which is **not** the order they are stored in.
 *
 * The route is a tree of runs (see the note at the top). Numbering walks it breadth first: the run that carries the
 * line-up point first, then the runs that branch off it, then the runs that branch off those. Among the runs that
 * leave the same parent, the order is by `ParkingPointGroup`, with -1 — BMS's "no group" — sorted last.
 *
 * Checked against the parking charts the theaters ship, which print a latitude and longitude per spot: Araxos
 * runway 36 (24 spots), Souda 11 (24), Tirana 17 (27) and Skopje 16 (28) all come out exactly right. Plain storage
 * order gets Araxos wrong; a breadth-first walk that ignores the groups gets Tirana and Skopje wrong. Where one of
 * those charts disagrees with the field's own data — Yenihesir's is drawn for a layout this install no longer has —
 * the data wins, because that is what the sim flies.
 */
function numberParking(nodes, parking) {
  if (!parking.length) return;
  const starts = new Set([0]);
  for (const n of nodes) if (n.branch != null && n.branch > 0 && n.branch < nodes.length) starts.add(n.branch);
  const bounds = [...starts].sort((a, b) => a - b);
  const runs = bounds.map((from, i) => ({ from, to: (bounds[i + 1] ?? nodes.length) - 1 }));
  const runOf = (k) => runs.findIndex((r) => k >= r.from && k <= r.to);
  for (const r of runs) {
    r.children = nodes.slice(r.from, r.to + 1)
      .filter((n) => n.branch != null && n.branch > 0 && n.branch < nodes.length)
      .map((n) => runOf(n.branch))
      .filter((i) => i >= 0);
    r.spots = parking.filter((s) => s.k >= r.from && s.k <= r.to);
    const groups = r.spots.map((s) => (s.group == null ? 0 : s.group));
    r.key = groups.length ? Math.max(...groups.map((g) => (g < 0 ? Number.MAX_SAFE_INTEGER : g))) : 0;
  }
  const order = [];
  const seen = new Set();
  const queue = [0];
  while (queue.length) {
    const i = queue.shift();
    if (i == null || seen.has(i)) continue;
    seen.add(i);
    order.push(...runs[i].spots);
    queue.push(...runs[i].children.filter((c) => !seen.has(c)).sort((a, b) => (runs[a].key - runs[b].key) || (a - b)));
  }
  for (let i = 0; i < runs.length; i++) if (!seen.has(i)) order.push(...runs[i].spots);
  order.forEach((s, i) => { s.n = i; });
  parking.sort((a, b) => a.n - b.n);
  for (const s of parking) delete s.group;   // it has done its job; it is not chart data
}

/**
 * A runway end is named for the heading you fly from it, so "16" has to sit on the end measuring about 160.
 *
 * A few fields in the shipped data have the two names on the wrong ends — Skopje's record puts 16 on the end that
 * measures 347.6 — which would send a pilot to the far end of the field. The names themselves are kept (they are
 * BMS's own, and carry the L/R of parallel runways); only which end each belongs to is checked, by seeing whether
 * swapping them agrees better with the strip as drawn.
 */
function fixEndNames(runway) {
  const [a, b] = runway.ends;
  if (!a || !b) return runway;
  const implied = (d) => {
    const n = parseInt(d, 10);
    return Number.isFinite(n) ? ((n % 36) * 10) : null;
  };
  const err = (d, course) => {
    const h = implied(d);
    return h === null ? 0 : gap(h, course);
  };
  const asIs = err(a.designator, a.course) + err(b.designator, b.course);
  const swapped = err(b.designator, a.course) + err(a.designator, b.course);
  if (swapped + 20 < asIs) {
    const tmp = a.designator;
    a.designator = b.designator;
    b.designator = tmp;
  }
  return runway;
}

function buildRunways(headers, points, ends) {
  const named = (course, rwyNo) => {
    let best = null;
    for (const e of endsFor(rwyNo)) {
      const d = gap(e.headingTrue, course);
      if (!best || d < best.d) best = { d, designator: e.designator };
    }
    return best && best.d < 20 ? best.designator : String(Math.round(norm360(course) / 10) || 36).padStart(2, '0');
  };
  const endsFor = (rwyNo) => {
    const own = ends.filter((e) => e.rwyNo === rwyNo);
    return own.length ? own : ends;
  };
  return headers.filter((h) => h.type === RUNWAY_HEADER).map((h) => {
    const slice = points.slice(h.first, h.first + h.count);
    const corners = slice.filter((p) => p.type === TYPE.RUNWAY_EDGE).slice(0, 4);
    if (corners.length < 4) return null;
    const a = mid(corners[0], corners[1]);
    const b = mid(corners[2], corners[3]);
    return {
      rwy: h.runway,
      lengthFt: Math.round(span(a, b)),
      widthFt: Math.round(span(corners[0], corners[1])),
      corners: corners.map(round),
      // A runway end is named for the course flown *from* it, so each end is named by the bearing to the other.
      ends: [
        { designator: named(bearing(a, b), h.runway), course: Math.round(bearing(a, b) * 10) / 10, at: round(a) },
        { designator: named(bearing(b, a), h.runway), course: Math.round(bearing(b, a) * 10) / 10, at: round(b) },
      ],
      crossings: slice.filter((p) => p.type === TYPE.RUNWAY_CROSS).map(round),
    };
  }).filter(Boolean).map(fixEndNames);
}

/**
 * Everything that could silently produce a wrong chart, checked here so it fails the build instead of the flight.
 * Returns the complaints; an empty list means the field agrees with the airport record the rest of the app uses.
 */
function checkField(field, airport, geoEnds) {
  const bad = [];
  const where = `${airport.name} (${airport.icao || airport.id})`;

  for (const r of field.runways) {
    for (const end of r.ends) {
      // The designator is BMS's own threshold sign, so it is kept whatever the rectangle measures; a record with
      // no heading at all (0) is simply missing one. What is worth knowing is a field where the two sources
      // disagree wildly, because there the chart and the Airports page will read differently.
      const stated = geoEnds.find((e) => e.designator === end.designator);
      if (stated && stated.headingTrue !== 0 && gap(stated.headingTrue, end.course) > 25) {
        bad.push(`${where}: runway ${end.designator} measures ${end.course.toFixed(1)} but the airport record states ${stated.headingTrue}`);
      }
    }
  }

  for (const r of field.routes) {
    if (r.start == null) { bad.push(`${where}: taxi route for ${r.designator} never meets its runway`); continue; }

    const adj = new Map();
    for (let i = 0; i < r.ed.length; i += 2) {
      const [x, y] = [r.ed[i], r.ed[i + 1]];
      (adj.get(x) || adj.set(x, []).get(x)).push(y);
      (adj.get(y) || adj.set(y, []).get(y)).push(x);
    }
    const seen = new Set([r.start]);
    const stack = [r.start];
    while (stack.length) {
      const at = stack.pop();
      for (const v of adj.get(at) || []) if (!seen.has(v)) { seen.add(v); stack.push(v); }
    }
    const stranded = r.parking.filter((p) => !seen.has(p.k));
    if (stranded.length) bad.push(`${where}: ${stranded.length} of ${r.parking.length} spots on ${r.designator} cannot be taxied to`);

    // A wrongly joined tree shows up as a long edge that also turns away from the lane at both ends. A long edge
    // that carries straight on is a real taxiway sampled sparsely — Iwon's runs 6178 ft across the field on one
    // bearing — and rolling from one on-runway point to another is taxiing down the runway, not a jump.
    for (let i = 0; i < r.ed.length; i += 2) {
      const [x, y] = [r.ed[i], r.ed[i + 1]];
      const a = r.nodes[x], b = r.nodes[y];
      if (span(a, b) < 2500) continue;
      if (r.spurs.includes(x) || r.spurs.includes(y)) continue;   // an outlier the router already goes around
      if (a.t === TYPE.ON_RUNWAY && b.t === TYPE.ON_RUNWAY) continue;
      const leg = bearing(a, b);
      let straight = false;
      for (let j = 0; j < r.ed.length && !straight; j += 2) {
        const [u, v] = [r.ed[j], r.ed[j + 1]];
        if (u === x && v === y) continue;
        if (v === x) straight = gap(bearing(r.nodes[u], a), leg) < 15;
        else if (u === y) straight = gap(leg, bearing(b, r.nodes[v])) < 15;
      }
      if (!straight) bad.push(`${where}: taxi route for ${r.designator} jumps ${Math.round(span(a, b))} ft between points ${x} and ${y}`);
    }
  }
  return bad;
}

/**
 * Ground charts for every field in one theater that has authored points.
 *
 * `geo` is the map [buildAirports] hands back: the object class and the runway-end order it worked the designators
 * out from, so the chart names its ends exactly as the rest of the app does.
 */
/**
 * How far from the airfield a thing may stand and still belong on its chart.
 *
 * BMS places objects over a wide area around a field, and the ones out in the fields read on a 2D chart as little
 * boxes floating in the dark with nothing to explain them. What a pilot needs is what is beside the pavement he is
 * taxiing along, so anything further than this from a taxiway, a runway or a piece of apron is left off.
 */
const NEAR_FIELD_FT = 900;

/** Whether a point is close enough to any of [near] (a flat list of east,north pairs) to be part of the field. */
function nearTheField(e, n, near) {
  const lim = NEAR_FIELD_FT * NEAR_FIELD_FT;
  for (let i = 0; i + 1 < near.length; i += 2) {
    const de = e - near[i], dn = n - near[i + 1];
    if (de * de + dn * dn <= lim) return true;
  }
  return false;
}

export async function buildAirfields(th, airports, geo, db) {
  const fields = [];
  const warnings = [];
  for (const airport of airports) {
    // Set BMSC_ONLY_FIELD while working on the pavement: one field builds in a second, the theater in a minute.
    if (process.env.BMSC_ONLY_FIELD && !airport.name.toLowerCase().startsWith(process.env.BMSC_ONLY_FIELD.toLowerCase())) continue;
    const g = geo.get(airport.id);
    if (!g) continue;
    const dir = ocdDirFor(th, g.ocd);
    if (!dir) continue;
    const id = String(g.ocd).padStart(5, '0');
    const headers = readHeaders(dir, id);
    if (!headers.some((h) => h.type === RUNWAY_HEADER)) continue;

    const heading = 0;   // every airfield objective in the stock theaters; checkField catches a theater that differs
    const points = readPoints(dir, id, heading);
    const runways = buildRunways(headers, points, g.ends);
    if (!runways.length) continue;
    // Ships come through as airbases in some theaters (the Falklands fleet is typed "Airbase"), and a deck is a few
    // hundred feet of angled landing area with no ramp to taxi on. The shortest real strip in any theater is
    // thousands of feet long, so the length tells them apart without matching ship names.
    // A ship is told from a field by having nothing standing on it. BMS types some carriers as "Airbase" (the
    // whole Falklands fleet) and their decks measure longer than a runway, because the same point list carries the
    // approach path — but not one of the fourteen has a single feature, and no airfield anywhere has none.
    const shipLike = airport.type === 'Carrier'
      || (parseRecords(findFileCI(dir, `FED_${id}.XML`), 'FED') || []).filter(Boolean).length === 0;

    // A route's name is the runway end it leads to, chosen by the course it states rather than by the order the
    // ends happen to be stored in — the same crossing that fixEndNames repairs shows up here otherwise.
    const routes = headers.filter((h) => h.type === ROUTE_HEADER).map((h) => {
      const r = buildRoute(h, points);
      // Its own runway first — at Osan the two parallel strips share a course, and without the number both of
      // 09L's and 09R's routes ended up called 09L.
      const own = runways.filter((x) => x.rwy === h.runway).flatMap((x) => x.ends);
      let best = null;
      for (const e of (own.length ? own : runways.flatMap((x) => x.ends))) {
        const d = gap(e.course, r.course);
        if (!best || d < best.d) best = { d, e };
      }
      r.designator = best && best.d < 25
        ? best.e.designator
        : String(Math.round(norm360(r.course) / 10) || 36).padStart(2, '0');
      return r;
    }).filter((r) => r.nodes.length > 1);

    const furniture = readFeatures(dir, id, db, heading);

    // The asphalt: the real triangles out of the field's own 3D models — the taxiways, the aprons, the
    // dispersals and the turnarounds joining the runway ends, none of which the ground network describes. The
    // union of them comes back as outlines, which the app fills.
    let paved = null;
    {
      let pe0 = Infinity, pe1 = -Infinity, pn0 = Infinity, pn1 = -Infinity;
      const see = (e, n) => {
        if (e < pe0) pe0 = e;
        if (e > pe1) pe1 = e;
        if (n < pn0) pn0 = n;
        if (n > pn1) pn1 = n;
      };
      for (const r of routes) for (const p2 of r.nodes) if (p2.t !== TYPE.RUNWAY_END) see(p2.e, p2.n);
      for (const r of runways) for (const c of r.corners) see(c.e, c.n);
      if (Number.isFinite(pe0)) {
        // the field, with room for pavement that runs past it — but not so much that a stray piece of a model
        // stretches the picture until the airfield is a speck in the middle of it
        const ROOM = 3000;
        const le = pe0 - ROOM, re = pe1 + ROOM, ln = pn0 - ROOM, rn = pn1 + ROOM;
        const tris = fieldTriangles(th, db, parseRecords(findFileCI(dir, `FED_${id}.XML`), 'FED')?.filter(Boolean) ?? [], heading);
        let qe0 = Infinity, qe1 = -Infinity, qn0 = Infinity, qn1 = -Infinity;
        for (let i = 0; i + 1 < tris.length; i += 2) {
          const e = tris[i], n = tris[i + 1];
          if (!Number.isFinite(e) || !Number.isFinite(n)) continue;
          if (e < le || e > re || n < ln || n > rn) continue;
          if (e < qe0) qe0 = e;
          if (e > qe1) qe1 = e;
          if (n < qn0) qn0 = n;
          if (n > qn1) qn1 = n;
        }
        if (Number.isFinite(qe0)) {
          const b = [Math.min(pe0, qe0) - 400, Math.max(pe1, qe1) + 400, Math.min(pn0, qn0) - 400, Math.max(pn1, qn1) + 400];
          // the field's own points, so pavement that reaches none of them can be left off
          const anchors = [];
          for (const r of routes) for (const q of r.nodes) anchors.push(q.e, q.n);
          for (const r of runways) for (const c of r.corners) anchors.push(c.e, c.n);
          const shapes = pavementShapes(tris, b, anchors);
          if (shapes && shapes.rings.length) paved = shapes.rings;
        }
      }
    }

    // A ship is drawn as a ship: its real flight deck, the landing area at its published angle, the catapults and
    // the island. No ramp — a carrier turns, and its spot numbers turn with it. See ships.mjs.
    let ship = null;
    if (shipLike) {
      // the parking spots sit on the deck itself; the taxi points run out along the approach
      // the parking spots sit on the deck itself, which is what the ship is laid on; the taxi points run out
      // along the approach
      const onDeck = [];
      for (const r of routes) for (const q of r.parking) if (r.nodes[q.k]) onDeck.push(r.nodes[q.k]);
      const spread = onDeck.length >= 4 ? onDeck : routes.flatMap((r) => r.nodes);
      const everything = routes.flatMap((r) => r.nodes).concat(runways.flatMap((r) => r.corners));
      const deckCourse = runways.slice().sort((a2, b2) => b2.lengthFt - a2.lengthFt)[0]?.ends?.[0]?.course ?? null;
      const shapes = spread.length ? shipShapes(airport.name, spread, everything, deckCourse) : null;
      if (shapes) {
        ship = {
          cls: shapes.cls, hull: shapes.hull, islands: shapes.islands,
          strip: shapes.strip, stripLine: shapes.stripLine, cats: shapes.cats, ski: shapes.ski,
        };
      }
    }

    // Only what stands beside the pavement. The rest is scenery BMS spreads over the countryside, and on a
    // chart it is a box in the dark with nothing to say for it.
    const near = [];
    for (const r of routes) for (const q of r.nodes) near.push(q.e, q.n);
    for (const r of runways) for (const c of r.corners) near.push(c.e, c.n);
    for (const r of paved || []) for (let i = 0; i + 1 < r.length; i += 2) near.push(r[i], r[i + 1]);
    const kept = furniture.features.filter((f) => nearTheField(f.e, f.n, near));
    const keptSigns = furniture.signs.filter((f) => nearTheField(f.e, f.n, near));

    // Which spots have a roof over them, so the chart can show it and the clearance can say it.
    const roofs = kept.filter((f) => f.k === 'shelter' || f.k === 'hangar');
    for (const r of routes) {
      for (const q of r.parking) {
        const node = r.nodes[q.k];
        q.c = node && roofs.some((f) => coveredBy(f, node.e, node.n)) ? 1 : 0;
      }
    }

    const field = {
      id: airport.id,
      name: airport.name,
      icao: airport.icao,
      // where the campaign puts the field, in theater feet: x north, y east, the frame the app's map and the jet's
      // own position are already in
      n: airport.x,
      e: airport.y,
      elevationFt: airport.elevationFt ?? null,
      runways,
      routes,
      features: kept,
      signs: keptSigns,
      paved,
      ship,
    };
    warnings.push(...checkField(field, airport, g.ends));
    fields.push(field);
  }
  return { fields, warnings };
}
