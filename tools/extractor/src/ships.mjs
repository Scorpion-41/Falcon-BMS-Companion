/**
 * Carriers, drawn as ships.
 *
 * **Each ship is drawn from its own BMS 3D model** (`tools/curated/carriers.json`, `decks`). A carrier's deck
 * points in `ObjectiveRelatedData` are in the frame of the ship's model — east is the model's starboard, north its
 * bow, and the origin is the model's — so a deck laid in that frame with no turning and no offset puts every BMS
 * spot, every launch point and the jet itself exactly where the sim has them. The Vinson's angled-deck corner, its
 * landing centre line, both bow launch points and its ramp spots all land on model 1844 within a few feet; the Queen
 * Elizabeth's runway corners are her model's port deck corners within two. What each deck carries — the outline
 * with every step and sponson, the structures below the deck edge, the landing area and its lines, the wires, the
 * catapults and their deflectors, the lifts, the ski jump, the islands, the hull number — is measured from the model
 * (outline and paint) or placed from a published figure, and `_src` on each deck says which.
 *
 * A class with no deck falls back on the old way: a deck **built** from the published dimensions of the real ship,
 * laid on the axis its parking spots give ([bestFrame]). BMS's spot numbering is never drawn on a ship either way —
 * a carrier steams into wind, and the runway it names and the numbers on its deck turn with it.
 *
 * Everything comes back in the field's own feet, ready to draw.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const table = JSON.parse(fs.readFileSync(path.resolve(HERE, '../../curated/carriers.json'), 'utf8'));

/**
 * The class whose deck to draw, by name — the field's name and its full name together, specific ships first.
 *
 * BMS's Falklands "HMS CAUSEWAY" is the Wasp-class LHD renamed (same model, same deck data), its "HMS ARC ROYAL" is
 * the 1970s R09 with an angled deck and catapults rather than the Invincible-class ship, and "Carrier Wasp" is LHD-2
 * only by its full name ("US LHD 2 - WASP"). Israel's "Carrier CVN-70 Vinson" once carried the full name "Chinese
 * Carrier - CV-16 Liaoning" (its Stations+Ils.dat gives the Vinson's campaign ID twice: airports.mjs stationFor) with the
 * Vinson's own deck data; the Vinson entries still come before the Liaoning's, so a name that mixes the two is the Vinson.
 */
export function carrierClass(name) {
  const up = (name || '').toUpperCase();
  for (const c of table.classes) {
    if (c.match.some((m) => up.includes(m))) return c;
  }
  return table.default;
}

/** The widest point of the deck, which is what the old beam figure was. */
const beamOf = (c) => c.stbdFt + c.sponsonFt;

// ---------------------------------------------------------------- the deck, in ship units

/**
 * The flight deck as a ring of [along, across] in feet from amidships and from the ship's axis.
 *
 * Not a rectangle. Seen from above a carrier is a long deck that narrows towards the bow, with the angled-deck
 * sponson bulging out to port over the after half — which is the single thing that makes the shape read as a
 * carrier rather than as a barge. An amphibious deck has the same bulge for its deck-edge lift instead.
 */
function deckRing(c) {
  const L = c.deckFt / 2;
  const s = c.stbdFt;
  const p = c.portFwdFt;
  const w = c.sponsonFt;
  // clockwise from the bow
  return [
    [L * 0.93, -p * 0.62], [L, -p * 0.4], [L, s * 0.4], [L * 0.93, s * 0.62],   // the bow: blunt, the way a deck ends
    [L * 0.78, s * 0.9], [L * 0.55, s],                                          // out to the full starboard edge
    [-L * 0.9, s], [-L, s * 0.85],                                               // down the starboard side to the stern
    [-L, -p * 0.85], [-L * 0.95, -w * 0.7],                                      // the stern, square, and onto the sponson
    [-L * 0.78, -w], [L * 0.04, -w],                                             // the sponson, its outboard edge
    [L * 0.18, -p], [L * 0.55, -p], [L * 0.78, -p * 0.9],                        // back in, and up the port side
  ];
}

/**
 * The landing area: angled off the axis to port on a CATOBAR ship, straight down the deck on the others.
 *
 * Given as its centre line plus a width, because that is what the chart draws — a strip with a dashed line down
 * the middle of it, the way a runway is drawn.
 */
function landing(c) {
  const len = c.stripLenFt || c.deckFt * 0.8;
  const wid = c.stripWidFt || Math.min(110, beamOf(c) * 0.42);
  if (c.kind !== 'catobar') {
    // a ski-jump or amphibious deck lands straight down the deck, offset a little to port of the island
    const across = -(c.portFwdFt * 0.25);
    return { a0: -c.deckFt / 2 + len * 0.02, c0: across, a1: -c.deckFt / 2 + len, c1: across, wid };
  }
  // The angled deck: it crosses the after deck from the starboard quarter out over the port sponson, so its
  // after end is to starboard of the axis and its forward end well out to port.
  const r = ((c.angleDeg || 9) * Math.PI) / 180;
  const a0 = -c.deckFt / 2 + c.deckFt * 0.02;
  const c0 = c.stbdFt * 0.45;
  return { a0, c0, a1: a0 + len * Math.cos(r), c1: c0 - len * Math.sin(r), wid };
}

/**
 * The catapults.
 *
 * Two down the bow, along the deck, which is how they are laid. On a big deck two more across the waist, parallel
 * to the landing area they launch alongside — drawn at any other angle they read as scratches across the deck.
 */
function catapults(c) {
  // A ship with a ski jump launches over it. The Kuznetsov has an angled landing deck and no catapults at all.
  if (c.kind !== 'catobar' || c.skiDeg) return [];
  const L = c.deckFt / 2;
  const out = [
    [[L * 0.1, c.stbdFt * 0.35], [L * 0.88, c.stbdFt * 0.35]],
    [[L * 0.1, -c.portFwdFt * 0.45], [L * 0.88, -c.portFwdFt * 0.45]],
  ];
  if (c.deckFt > 900) {
    const st = landing(c);
    const r = Math.atan2(st.c1 - st.c0, st.a1 - st.a0);
    const run = c.deckFt * 0.34;
    for (const off of [-c.sponsonFt * 0.45, -c.sponsonFt * 0.2]) {
      const a0 = -L * 0.34;
      out.push([[a0, off], [a0 + run * Math.cos(r), off + run * Math.sin(r)]]);
    }
  }
  return out;
}

/** The ski jump at the bow, as the run it occupies, for the ships that have one. */
function skiJump(c) {
  if (!c.skiDeg) return null;
  const L = c.deckFt / 2;
  return { a0: L * 0.78, a1: L, c0: -c.portFwdFt * 0.8, c1: c.stbdFt * 0.1, deg: c.skiDeg };
}

// ---------------------------------------------------------------- where the ship lies

/** The middle of a cloud of points. */
function centroid(points) {
  let ce = 0, cn = 0;
  for (const p of points) { ce += p.e; cn += p.n; }
  return { ce: ce / points.length, cn: cn / points.length };
}

/**
 * The direction the ship lies in: the one that holds its parking spots on its deck.
 *
 * Not the principal axis of the spots, which is what this used to take. Aircraft on a fleet carrier park **along
 * the angled landing deck**, so the spread of the spots follows that strip rather than the hull — on the Nimitz
 * class it came out 17 degrees off the ship and a quarter of the spots ended up over the side. Nor the course of
 * the longest runway, which is the approach path on some ships and the angled deck on others.
 *
 * So the angle is measured: the deck is laid at every degree of a half turn and the one that leaves fewest spots
 * over the side wins, ties going to the one whose worst spot is least far out.
 */
function bestFrame(cls, points, all) {
  const c0 = centroid(points);
  let best = null;
  for (let deg = 0; deg < 180; deg++) {
    const r = (deg * Math.PI) / 180;
    const frame = frameFor(cls, Math.sin(r), Math.cos(r), points, all, c0);
    const s = held(frame, points, cls);
    if (!best || s.inside > best.s.inside || (s.inside === best.s.inside && s.worst < best.s.worst)) {
      best = { s, frame };
    }
  }
  return best.frame;
}

/** How many spots the deck holds, and how far outside it the worst one falls. */
function held(f, points, cls) {
  let inside = 0;
  let worst = 0;
  const halfL = cls.deckFt / 2;
  for (const q of points) {
    const dl = (q.e - f.ce) * f.ue + (q.n - f.cn) * f.un;
    const dc = (q.e - f.ce) * f.pe + (q.n - f.cn) * f.pn;
    const ol = Math.max(0, Math.abs(dl) - halfL);
    const oc = Math.max(0, dc > 0 ? dc - cls.stbdFt : -dc - cls.sponsonFt);
    if (ol === 0 && oc === 0) inside++;
    worst = Math.max(worst, Math.hypot(ol, oc));
  }
  return { inside, worst };
}

/**
 * The ship's own frame at one angle: where its middle is, which way is forward and which way is starboard.
 *
 * The spots give a direction but not a centre — they are parked down one side and towards one end — so the middle
 * comes from the deck: every point of the field within a ship's length of them, which is the deck and not the mile
 * of approach path the same list carries.
 */
function frameFor(cls, ue, un, points, all, c0) {
  // Aircraft park to starboard, round the island, so the side the spots are on is the side the island goes.
  let pe = -un, pn = ue;
  {
    let sum = 0;
    for (const q of points) sum += (q.e - c0.ce) * pe + (q.n - c0.cn) * pn;
    if (sum < 0) { pe = -pe; pn = -pn; }
  }
  const alongOf = (q) => (q.e - c0.ce) * ue + (q.n - c0.cn) * un;
  const acrossOf = (q) => (q.e - c0.ce) * pe + (q.n - c0.cn) * pn;
  const near = all.filter((q) => Math.abs(alongOf(q)) <= cls.deckFt * 0.6);
  const deck = near.length >= 4 ? near : points;
  const mid = (f) => {
    let lo = Infinity, hi = -Infinity;
    for (const q of deck) { const v = f(q); if (v < lo) lo = v; if (v > hi) hi = v; }
    return (lo + hi) / 2;
  };
  return {
    ce: c0.ce + ue * mid(alongOf) + pe * mid(acrossOf),
    cn: c0.cn + un * mid(alongOf) + pn * mid(acrossOf),
    ue, un, pe, pn,
  };
}

// ---------------------------------------------------------------- a deck from its own model

/** [along, across] in the model's frame to [east, north] in the field's: the bow is north, starboard east. */
const fieldPt = ([a, c]) => [Math.round(c), Math.round(a)];
const flatRing = (pts) => pts.flatMap(fieldPt);

/** Where a line square across the deck at [along] crosses a ring: the ends of a ski jump's contour, say. */
function acrossRing(ring, along) {
  const xs = [];
  for (let i = 0; i < ring.length; i++) {
    const [a0, c0] = ring[i], [a1, c1] = ring[(i + 1) % ring.length];
    if ((a0 <= along) !== (a1 <= along)) xs.push(c0 + ((along - a0) / (a1 - a0)) * (c1 - c0));
  }
  return xs.length >= 2 ? [Math.min(...xs), Math.max(...xs)] : null;
}

/**
 * One deck, as the marks the chart draws, in the order it draws them: the structures below the deck edge first,
 * then everything painted on the deck, then the islands standing on it.
 *
 * Each mark is { k, p } with [p] east,north pairs in field feet; a circle or a number is its middle, with [r] its
 * radius or height in feet and [t] the text. See AfDeckMark in the app for what each kind looks like.
 */
function deckMarks(d, cls) {
  const marks = [];
  const put = (k, pts, extra = {}) => marks.push({ k, p: pts.flatMap(fieldPt), ...extra });
  for (const r of d.structures || []) put('edge', r);
  if (d.lane) put('lane', d.lane);
  for (const l of d.lifts || []) put(l.est ? 'lift?' : 'lift', l.p);
  for (const h of d.hatches || []) put('hatch', h);
  if (d.ski) {
    put('ski', d.ski.p);
    for (const a of d.ski.steps || []) {
      const x = acrossRing(d.ski.p, a);
      if (x) put('step', [[a, x[0] + 2], [a, x[1] - 2]]);
    }
  }
  for (const l of d.lines || []) put(l.k, l.p);
  for (const w of d.wires || []) put('wire', w);
  for (const c of d.cats || []) {
    if (c.jbd) put('jbd', c.jbd);
    if (c.track) put('cat', c.track);
  }
  for (const sp of d.spots || []) put(sp.tee ? 'tee' : 'spot', [sp.p], { r: sp.r || 15, ...(sp.t ? { t: sp.t } : {}) });
  // the ship's own hull number where the class shares a deck, or the one the deck's paint carries (the Liaoning's 16)
  const number = cls.number ?? d.number;
  if (number && d.numberAt) put('text', [d.numberAt.p], { r: d.numberAt.ft, t: number });
  for (const i of d.islands || []) put('island', i);
  for (const q of d.parts || []) put('part', q);
  return marks;
}

/** The ship drawn from its own model: nothing is fitted, because BMS's deck points are already in its frame. */
function modelShip(cls, d) {
  const centre = (d.lines || []).find((l) => l.k === 'ydash' || l.k === 'dash');
  const cats = (d.cats || []).filter((c) => c.track).map((c) => c.track.flatMap(fieldPt));
  return {
    cls: cls.name,
    model: d.model || '',
    tone: d.tone || 0,
    skiDeg: d.skiDeg ?? null,
    hull: flatRing(d.outline),
    strip: d.lane ? flatRing(d.lane) : [],
    stripLine: centre ? centre.p.flatMap(fieldPt) : [],
    cats,
    ski: d.ski ? flatRing(d.ski.p) : [],
    islands: (d.islands || []).map(flatRing),
    marks: deckMarks(d, cls),
  };
}

// ---------------------------------------------------------------- putting it together

/**
 * The deck of one ship, in the field's own feet.
 *
 * [name] is the field's name and full name; a class with a deck of its own is drawn from it and needs nothing else.
 * For one without, [points] are BMS's own ramp spots (with their numbers), [all] every deck point it has, [courseDeg] the heading
 * of its longest strip, which is only used to decide which end is the bow.
 */
export function shipShapes(name, points, all = points, courseDeg = null) {
  const cls = carrierClass(name);
  const deck = cls.deck && table.decks?.[cls.deck];
  if (deck) return modelShip(cls, deck);
  if (!points.length) return null;
  const f = bestFrame(cls, points, all);

  // Which way the bow points. A measured axis has no sign, so it comes from the deck's own course: a carrier
  // launches over the bow, so the strip's heading is forward. Without one, either end will do.
  let { ue, un } = f;
  if (courseDeg != null) {
    const r = (courseDeg * Math.PI) / 180;
    if (ue * Math.sin(r) + un * Math.cos(r) < 0) { ue = -ue; un = -un; }
  }
  // Starboard is worked out again from whichever way the bow ended up, and is always the side the spots are
  // parked on: that is where the island stands, and it is what mirrors the ship the right way round.
  let pe = -un, pn = ue;
  {
    let sum = 0;
    for (const q of points) sum += (q.e - f.ce) * pe + (q.n - f.cn) * pn;
    if (sum < 0) { pe = -pe; pn = -pn; }
  }
  const ce = f.ce;
  const cn = f.cn;

  const at = (along, across) => [
    Math.round(ce + ue * along + pe * across),
    Math.round(cn + un * along + pn * across),
  ];
  const flat = (pairs) => pairs.flatMap(([a, c]) => at(a, c));
  /** a bearing in the field's frame, for a heading given along the ship */
  const heading = (deg) => {
    const brg = (Math.atan2(ue, un) * 180) / Math.PI + deg;
    return Math.round(((brg % 360) + 360) % 360);
  };

  const hull = flat(deckRing(cls));

  const st = landing(cls);
  const strip = flat([
    [st.a0, st.c0 + st.wid / 2], [st.a1, st.c1 + st.wid / 2],
    [st.a1, st.c1 - st.wid / 2], [st.a0, st.c0 - st.wid / 2],
  ]);
  const stripLine = [...at(st.a0, st.c0), ...at(st.a1, st.c1)];

  const cats = catapults(cls).map((seg) => flat(seg));
  const ski = skiJump(cls);
  const skiRing = ski ? flat([[ski.a0, ski.c0], [ski.a1, ski.c0], [ski.a1, ski.c1], [ski.a0, ski.c1]]) : [];

  const islands = (cls.islands || []).map((i) => {
    const hl = i.long / 2, hw = i.wide / 2;
    return flat([
      [i.along - hl, i.across - hw], [i.along + hl, i.across - hw],
      [i.along + hl, i.across + hw], [i.along - hl, i.across + hw],
    ]);
  });

  return { cls: cls.name, hull, strip, stripLine, cats, ski: skiRing, islands };
}
