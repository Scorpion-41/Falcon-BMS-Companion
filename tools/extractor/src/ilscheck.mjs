/**
 * Checks which runway end the app gives each ILS frequency against BMS's own documents.
 *
 *   node src/ilscheck.mjs            (BMS_ROOT points at the install; needs java and cache/pdfbox-app.jar)
 *
 * A station line of `Stations+Ils.dat` gives a field four ILS frequencies but no runway; `airports.mjs` (`ilsEnds`)
 * gives each to a runway end the way the sim does (read out of `Falcon BMS.exe`: slot 2q is the end of runway q's
 * second type 1 header in PHD order, slot 2q + 1 its first's). Four documents the theaters ship print the runway of
 * each frequency, and this compares the shipped airport sets (`data/airports/ap-*.json`) with every pair they print:
 *   KTO      Docs/03 KTO Charts/KTO_AIP.pdf, 1.2.3 ILS                 "Cheongju ICHG 111.7 ILS 24L"
 *   Hellas   Add-On Hellas/Docs/HTO_Navaid_438.pdf (ILS 1-4 columns)   "111.90, Rwy 24 110.30, Rwy 06 …"
 *   Israel   Add-On Israel/Docs/01 ITO Charts/BASIC NAVAIDS_LIST_*.pdf "110.90, Rwy 08 108.70, Rwy 26 …"
 *   Balkans  Add-On Balkans/Docs/Balkans Navaids List.pdf              "108.30 (29)"
 * A printed runway is matched to an end by its designator, else by the nearest number within one (the lists name some
 * runways from their ATC files: Ramat David's "33" is BMS's 32). Every pair whose end holds another frequency fails,
 * except the documents' own slips in KNOWN_SLIPS, each one where the sim's code and the document disagree.
 * On 4.38.1, counted per theater set: 226 pairs agree (KTO 56, Hellas and LHTO 24 each, Israel 24, Balkans and EF2000
 * 49 each), 15 are known slips (11 distinct), the rest name no runway the field has. The 1.4.0 sets (`ILSCHECK_DATA=<a folder
 * holding their index.json and ap-*.json>`) fail 164 and agree on 64: slots in PHD order, and in the Balkans and
 * Falklands the two names of a runway the wrong way round (atcRunwayFor). Daegu, where the old order had been checked,
 * passes either way: 13R and 31L share 108.7.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { BMS_ROOT } from './util.mjs';

/**
 * '<field's first word> <freq> <printed runway>' → why the document is not followed. Each is a pair where the
 * document and the sim's own code disagree; the sim's name for the slot (the other header's heading and landing
 * pattern, as SetIlsData prints it) is given.
 */
const KNOWN_SLIPS = new Map([
  // KTO AIP, North Korea: the three ILS at Sunan are printed against other runways than the slots serve
  ['Sunan 109.90 18', 'the sim serves 16 on that slot'],
  ['Sunan 110.30 34', 'the sim serves 18 on that slot'],
  ['Sunan 109.50 36', 'the sim serves 34 on that slot'],
  // KTO AIP: a frequency BMS's station line does not have (it gives Wonju 110.1 and 111.5)
  ['Wonju 110.20 04', 'not a frequency of the station line'],
  // Israel's list: three single ILS printed against another end than the slot's (BMS names Nevatim's 26L, Ramon's 25L,
  // Ovda's 03R)
  ['Nevatim 108.50 26R', 'the sim serves 26L on that slot'],
  ['Ramon 110.50 07L', 'the sim serves 25L on that slot'],
  ['Ovda 109.70 21R', 'the sim serves 03R on that slot'],
  // the Balkans list prints three single ILS against the other end, and an ILS Nis's station line does not have
  ['Forli 109.70 12', 'the sim serves 30 on that slot'],
  ['Miramare 109.30 31', 'the sim serves 13 on that slot'],
  ['Palese 111.35 25', 'the sim serves 07 on that slot'],
  ['Nis 109.30 29', 'not a frequency of the station line'],
]);

const HERE = path.dirname(fileURLToPath(import.meta.url));
// ILSCHECK_DATA=<folder with index.json and the ap-*.json sets> checks another build's sets (an earlier one, say)
const OUT = process.env.ILSCHECK_DATA || path.join(path.resolve(HERE, '../../..'), 'app/src/main/assets/data');
const apFile = (set) => [path.join(OUT, 'airports', set + '.json'), path.join(OUT, set + '.json')].find((f) => fs.existsSync(f));
const PDFBOX = path.join(HERE, '../cache/pdfbox-app.jar');
const index = JSON.parse(fs.readFileSync(path.join(OUT, 'index.json'), 'utf8'));
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ilscheck-'));

function pdfText(file, sort) {
  const out = path.join(tmp, path.basename(file) + (sort ? '.sorted' : '') + '.txt');
  execFileSync('java', ['-jar', PDFBOX, 'export:text', ...(sort ? ['-sort'] : []), `-i=${file}`, `-o=${out}`], { stdio: 'ignore' });
  return fs.readFileSync(out, 'utf8').split(/\r?\n/);
}
const ilsFreq = (s) => { const v = Number(s); return v >= 108 && v < 112 ? v.toFixed(2) : null; };

/** KTO AIP 1.2.3: "<name>? <ID> <freq> ILS <rwy>", the name carried down to the lines without one */
function ktoAip(file) {
  const out = [];
  let on = false, name = null;
  for (const raw of pdfText(file, true)) {
    const line = raw.trim();
    if (/^1\.2\.3\b/.test(line)) on = true;
    else if (/^1\.2\.[4-9]\b/.test(line)) on = false;
    if (!on) continue;
    const m = line.match(/^(?:([A-Za-z][A-Za-z .'-]*?)\s+)?([A-Z]{2,4})\s+(\d{3}\.\d{1,2})\s+ILS\s+(\d{2}[LRC]?)$/);
    if (!m) continue;
    if (m[1]) name = m[1];
    const f = ilsFreq(m[3]);
    if (f && name) out.push({ field: name, freq: f, rwy: m[4] });
  }
  return out;
}

/** The ILS 1-4 columns of the Hellas and Israel lists, cell by cell in the unsorted text: "[<freq>,] Rwy <rwy>" */
function slotList(file, numbered) {
  const out = [];
  const records = [];
  let cur = null, pendingName = '';
  const DATA = /^([^\d]*?)\s*(\d+ [XY]\b.*)$/;
  for (const raw of pdfText(file, false)) {
    if (numbered) {
      // Israel: "<Nr> <name>[ <data>]" opens a record; lines with no digits before its data go on with the name
      const m = raw.match(/^(\d{1,3}) ([A-Za-z][^\d]*?)\s*(\d+ [XY]\b.*)?$/);
      if (m) { cur = { name: m[2], text: m[3] || '', data: !!m[3] }; records.push(cur); continue; }
      if (!cur) continue;
      if (!cur.data && !/\d|\bRwy\b/.test(raw)) { cur.name += ' ' + raw; continue; }
      if (DATA.test(raw)) cur.data = true;
      cur.text += ' ' + raw;
    } else {
      // Hellas: a name runs over lines with no digits (a word may break across two), then its data line, which can
      // carry the name's last words
      const m = raw.match(DATA);
      if (m) { cur = { name: pendingName + m[1], text: m[2] }; records.push(cur); pendingName = ''; continue; }
      if (!/\d/.test(raw) && raw.trim() && !/^Objective|NAVAID/i.test(raw)) { pendingName += raw; continue; }
      if (cur) cur.text += ' ' + raw;
    }
  }
  for (const r of records) {
    let slot = 0;
    for (const m of r.text.matchAll(/(?:(\d{3}\.\d{1,3}),\s*)?Rwy\s+(\d{2}[LRC]?)\b/g)) {
      slot++;
      const f = m[1] && ilsFreq(m[1]);
      if (f) out.push({ field: r.name.replace(/\s+/g, ' ').trim(), freq: f, rwy: m[2], slot });
    }
  }
  return out;
}

/** Balkans: a field's line is its name and its first frequencies; every "<freq> (<rwy>)" after it is its */
function balkansList(file) {
  const out = [];
  let name = null;
  for (const raw of pdfText(file, false)) {
    const n = raw.match(/^([A-Z][A-Za-z.' -]+?)\s+\d{2,3}\.\d/);
    if (n && !/^[NE] \d/.test(raw)) name = n[1];
    for (const m of raw.matchAll(/\b(\d{3}\.\d{2,3})\s*\((\d{2}[LRC]?)\)/g)) {
      const f = ilsFreq(m[1]);
      if (f && name) out.push({ field: name, freq: f, rwy: m[2] });
    }
  }
  return out;
}

const GENERIC = new Set(['airport', 'airbase', 'ab', 'intl', 'int', 'international', 'airfield', 'airstrip', 'army',
  'nab', 'afb', 'apt', 'mcas', 'raf', 'air', 'base', 'the', 'old', 'new']);
const words = (s) => s.toLowerCase().normalize('NFD').replace(/[^a-z0-9 ]+/g, ' ').split(/\s+/).filter((w) => w.length >= 3 && !GENERIC.has(w));
function fieldFor(airports, docName) {
  const want = words(docName);
  if (!want.length) return null;
  // a name whose words are exactly the document's wins ("Ramon" is not "Ramon-Eilat INTL", "Cairo INT" not Cairo West)
  const exact = airports.filter((a) => a.type !== 'Carrier' && words(a.name).join(' ') === want.join(' '));
  if (exact.length === 1) return exact[0];
  let best = null, tie = false;
  for (const a of airports) {
    if (a.type === 'Carrier') continue;
    const have = new Set(words(`${a.name} ${a.fullName || ''}`));
    const score = want.filter((w) => have.has(w) || [...have].some((h) => h.startsWith(w) || w.startsWith(h))).length;
    if (!score) continue;
    if (!best || score > best.score) { best = { a, score }; tie = false; } else if (score === best.score) tie = true;
  }
  return best && !tie ? best.a : null;
}
const num = (d) => parseInt(d, 10);
const side = (d) => d.replace(/^\d+/, '');
function endFor(a, rwy) {
  const ends = a.runways.flatMap((r) => r.ends);
  const exact = ends.find((e) => e.designator === rwy);
  if (exact) return exact;
  const near = ends.filter((e) => side(e.designator) === side(rwy) && Math.min(Math.abs(num(e.designator) - num(rwy)), 36 - Math.abs(num(e.designator) - num(rwy))) <= 1);
  return near.length === 1 ? near[0] : null;
}

const DOCS = [
  { theaters: ['korea-kto'], file: 'Docs/03 KTO Charts/KTO_AIP.pdf', read: ktoAip },
  { theaters: ['hellas', 'lhto'], file: 'Data/Add-On Hellas/Docs/HTO_Navaid_438.pdf', read: (f) => slotList(f, false) },
  { theaters: ['israel'], dir: 'Data/Add-On Israel/Docs/01 ITO Charts', match: /^BASIC NAVAIDS_LIST.*\.pdf$/i, read: (f) => slotList(f, true) },
  { theaters: ['balkans', 'ef2000-bto'], file: 'Data/Add-On Balkans/Docs/Balkans Navaids List.pdf', read: balkansList },
];

if (!fs.existsSync(PDFBOX)) { console.log(`FAIL no ${PDFBOX} (see charts.mjs for where to get it)`); process.exit(1); }
let fails = 0, passes = 0, slips = 0;
for (const doc of DOCS) {
  const file = doc.file ? path.join(BMS_ROOT, doc.file)
    : (() => { const d = path.join(BMS_ROOT, doc.dir); const f = fs.existsSync(d) && fs.readdirSync(d).find((x) => doc.match.test(x)); return f ? path.join(d, f) : null; })();
  if (!file || !fs.existsSync(file)) { console.log(`info ${doc.file || doc.dir}: not in this install`); continue; }
  const pairs = doc.read(file);
  for (const thId of doc.theaters) {
    const entry = index.theaters.find((t) => t.id === thId);
    if (!entry) continue;
    const { airports } = JSON.parse(fs.readFileSync(apFile(entry.airportSet), 'utf8'));
    let ok = 0, bad = 0, known = 0, unmatched = 0;
    const lines = [];
    for (const p of pairs) {
      const a = fieldFor(airports, p.field);
      if (!a) { unmatched++; lines.push(`  info ${p.field}: no field of that name`); continue; }
      const e = endFor(a, p.rwy);
      if (!e) { unmatched++; lines.push(`  info ${a.name}: no end ${p.rwy} (${a.runways.map((r) => r.name).join(', ')}) for ${p.freq}`); continue; }
      const where = a.runways.flatMap((r) => r.ends).filter((x) => x.ils === p.freq).map((x) => x.designator);
      if (e.ils === p.freq) { ok++; continue; }
      const key = `${a.name.split(' ')[0]} ${p.freq} ${p.rwy}`;
      if (KNOWN_SLIPS.has(key)) { known++; lines.push(`  known ${a.name} ${p.freq} printed for ${p.rwy}: ${KNOWN_SLIPS.get(key)}`); continue; }
      bad++;
      lines.push(`  FAIL ${a.name}: the document gives ${p.freq} to ${p.rwy}; the app gives ${e.designator} ${e.ils ?? 'no ILS'}${where.length ? `, and ${p.freq} to ${where.join('/')}` : ''}`);
    }
    fails += bad; passes += ok; slips += known;
    console.log(`${bad ? 'FAIL' : 'ok  '} ${thId.padEnd(12)} ${path.basename(file)}: ${pairs.length} printed pairs, ${ok} agree, ${bad} disagree, ${known} known slips, ${unmatched} not matched`);
    lines.forEach((l) => console.log(l));
  }
}
fs.rmSync(tmp, { recursive: true, force: true });
console.log(fails ? `FAIL ${fails} printed ILS runways disagree with the app (${passes} agree)` : `ALL PASS ${passes} printed ILS runways agree (${slips} known slips)`);
process.exit(fails ? 1 : 0);
