// Airport charts, matched to airports per airport set, written to the app assets (charts/ + data/charts.json):
//   - the BMS ground/parking PNG plates (one image per chart), and
//   - the instrument chart PDFs some theaters ship (KTO, Falklands: ILS/APP/SID/STAR/VISUAL…), rendered page by
//     page with Apache PDFBox. A PDF becomes one chart entry whose "pages" are the page images.
// PDFBox: put pdfbox-app-3.x.jar in tools/extractor/cache/pdfbox-app.jar
// (https://repo1.maven.org/maven2/org/apache/pdfbox/pdfbox-app/3.0.3/pdfbox-app-3.0.3.jar). Without it the PDFs are skipped.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import os from 'node:os';
import { execFileSync } from 'node:child_process';
import sharp from 'sharp';
import { BMS_ROOT, DATA, writeJson } from './util.mjs';
import { loadTheaters } from './theaters.mjs';
import { buildAirports } from './airports.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../../..');
const FULL = path.join(ROOT, 'app/src/main/assets');
const OUTDIR = path.join(FULL, 'charts');
const index = JSON.parse(fs.readFileSync(path.join(ROOT, 'app/src/main/assets/data/index.json'), 'utf8'));

const norm = (s) => s.toLowerCase().normalize('NFD').replace(/[^a-z0-9]/g, '');

const PDFBOX = path.join(HERE, '../cache/pdfbox-app.jar');
const PAGE_DPI = 150;      // AIP plates are vector: 150 dpi stays sharp when zoomed on a tablet
const PAGE_QUALITY = 62;   // line art compresses well
const JAVA = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java';

/** A page with fewer characters than this in its middle is drawn rather than typeset: PDFBox cannot say which way up it is. */
const TEXT_ENOUGH = 60;

/**
 * Is the table on this *drawn* page lying on its side? Many BMS charts are pictures rather than typeset pages, so
 * PDFBox has no glyphs to report the angle of and the image has to answer.
 *
 * What an AIP prints sideways is always a table, and a table is its rules. A coding table laid out in landscape and
 * dropped onto an upright page comes out with its row separators running DOWN the page — several long parallel lines,
 * the full depth of the table, with nothing of the sort running across it, because its column separators are now
 * short. An upright page has the opposite, and a plan chart (a SID picture, a parking diagram) has neither: the one
 * or two long lines on it are the printed frame and the edge of a note panel.
 *
 * So: count the long rules inside the frame, each way. The frame's own sides are left out (they are at the very
 * edge of the region and are on every page); a rule counts when it runs at least 60% of the page's depth, and
 * three-fifths is close enough for a second reading at 45% to tell a real grid from a stray line.
 *
 * Checked against PDFBox on the 646 pages that do carry text: of the 82 it calls sideways this finds 66, and of the
 * 564 it calls upright it turns none. It errs, in other words, on leaving a page exactly as the chart was drawn.
 */
const RULE_LONG = 0.60;   // of the page's depth (down) or width (across)
const RULE_SHORT = 0.45;
const DARK = 170;

/** The longest unbroken dark run for each line across the region, as a fraction of the region. */
function inkRuns(outer, inner, at) {
  const out = new Float64Array(outer);
  for (let i = 0; i < outer; i++) {
    let run = 0, gap = 0, best = 0;
    for (let j = 0; j < inner; j++) {
      if (at(i, j)) { run += gap + 1; gap = 0; if (run > best) best = run; }
      else if (run) { gap++; if (gap > 3) { run = 0; gap = 0; } } // a rule may be dashed, or crossed by text
    }
    out[i] = best / inner;
  }
  return out;
}

/** How many rules that long the region holds, counting a rule once however many pixels thick it is drawn. */
function countRules(runs, min) {
  let n = 0, i = 0;
  while (i < runs.length) {
    if (runs[i] >= min) {
      let j = i;
      while (j < runs.length && runs[j] >= min) j++;
      const at = (i + j - 1) / 2 / runs.length;
      if (at > 0.04 && at < 0.96) n++; // not the frame itself
      i = j;
    } else i++;
  }
  return n;
}

async function pageSideways(file) {
  const { data, info } = await sharp(file).grayscale().resize({ width: 1200, height: 1200, fit: 'inside' }).raw().toBuffer({ resolveWithObject: true });
  const W = info.width, H = info.height;
  const ink = (x, y) => data[y * W + x] < DARK;
  const x0 = Math.round(W * 0.06), x1 = Math.round(W * 0.94);
  const y0 = Math.round(H * 0.08), y1 = Math.round(H * 0.92);
  const across = inkRuns(y1 - y0, x1 - x0, (i, j) => ink(x0 + j, y0 + i));
  const down = inkRuns(x1 - x0, y1 - y0, (i, j) => ink(x0 + i, y0 + j));
  const downLong = countRules(down, RULE_LONG);
  return downLong >= 2 && countRules(down, RULE_SHORT) >= 3 && downLong > countRules(across, RULE_LONG);
}

/**
 * Is this rendered page one of the "INTENTIONALLY LEFT BLANK" fillers AIP chart sets are full of? Those carry almost
 * no ink in the body of the page.
 *
 * Every margin is left out, not just the header: Chile and Argentina rule a line across the foot of every sheet and
 * print the issuing office down both sides, which together were enough to make a page saying "DEJADA EN BLANCO
 * INTENCIONALMENTE" look busy. Measured on the body alone the fillers sit near 3‰ ink and the emptiest real chart at
 * 12‰, so the line between them is not a fine one.
 */
async function pageBlank(file) {
  const { data, info } = await sharp(file).grayscale().resize({ width: 700, height: 700, fit: 'inside' }).raw().toBuffer({ resolveWithObject: true });
  const W = info.width, H = info.height;
  let ink = 0, seen = 0;
  for (let y = Math.round(H * 0.15); y < H * 0.88; y++) {
    for (let x = Math.round(W * 0.12); x < W * 0.88; x++) { seen++; if (data[y * W + x] < 170) ink++; }
  }
  return ink / seen < 0.005;
}

/**
 * The turn every page of these PDFs needs, as {"<pdf>|<page>": {turn, chars}}, from PageDirs.java.
 *
 * AIP charts often draw a landscape table on a portrait page: the page itself is not rotated, the text is. PDFBox
 * knows the angle of each glyph, and PageDirs weighs only the glyphs in the middle of the page (the frame around a
 * chart is always upright and would outvote a small table) and takes off the page's own /Rotate. `chars` says how
 * much text it had to go on: a page drawn rather than typeset has none, and is judged from its image instead.
 */
function pageAngles(files) {
  const out = new Map();
  if (!files.length || !fs.existsSync(PDFBOX)) return out;
  try {
    const text = execFileSync(JAVA, ['-cp', PDFBOX, path.join(HERE, 'PageDirs.java'), ...files], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] });
    for (const line of text.split(/\r?\n/)) {
      const [file, page, turn, chars] = line.split('\t');
      if (page) out.set(file + '|' + page, { turn: parseInt(turn, 10), chars: parseInt(chars, 10) });
    }
  } catch (e) {
    console.warn('page angles failed, leaving every page as printed:', e.message);
  }
  return out;
}

/** Page images of one chart PDF, rendered with PDFBox and encoded as WebP. Returns the written file names. */
async function renderPdf(src, hash, outDir, angles) {
  const src0 = src;
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'bmsc-chart-'));
  try {
    execFileSync(JAVA, ['-jar', PDFBOX, 'render', '-i', src, '-prefix', path.join(tmp, 'p'), '-format', 'png', '-dpi', String(PAGE_DPI)], { stdio: 'ignore' });
    const pages = fs.readdirSync(tmp).filter((f) => f.endsWith('.png'))
      .sort((a, b) => parseInt(a.match(/(\d+)\.png$/)[1], 10) - parseInt(b.match(/(\d+)\.png$/)[1], 10));
    const files = [];
    let blanks = 0, turned = 0;
    for (let i = 0; i < pages.length; i++) {
      const src = path.join(tmp, pages[i]);
      if (await pageBlank(src)) { blanks++; continue; } // "INTENTIONALLY LEFT BLANK"
      const out = `${hash}-${files.length + 1}.webp`;
      let img = sharp(src, { limitInputPixels: false });
      // PDFBox knows the angle of real text; a page that is drawn rather than typeset has none, and is read from
      // the image instead
      const known = angles?.get(src0 + '|' + (i + 1));
      const angle = known && known.chars >= TEXT_ENOUGH ? known.turn : (await pageSideways(src)) ? 90 : 0;
      if (angle) { img = img.rotate(angle); turned++; }
      await img.resize({ width: 1600, height: 1600, fit: 'inside', withoutEnlargement: true })
        .webp({ quality: PAGE_QUALITY, effort: 5 }).toFile(path.join(outDir, out));
      files.push(`charts/${out}`);
    }
    if (blanks || turned) console.log(`  ${path.basename(src0)}: ${blanks} blank pages skipped, ${turned} turned upright`);
    return files;
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

// The title on a chart's card comes from its file name, and BMS ships four naming conventions:
//   Korea      "Osan AB (RKSO) - ils_or_locdme_rwy_09l.pdf"   procedure and runway, after the airport's name
//              "Osan AB (RKSO) - draggin_dep.pdf"             a departure named after its first fix
//   Argentina  "SAVT-APP.pdf", "SAWC-Parking.pdf"             ICAO and the kind of chart
//   Chile      "SCCI IAC1.pdf", "SCVD STAR4.pdf"              ICAO, kind and a number
//              "vac sccy ad teniente vidal - coyhaique.pdf"   kind first, then ICAO and the aerodrome's name
//   Falklands  "2025 Chart SFAL NDB DME R09 v6.pdf"           procedure and runway among the noise
// Only the words that mean something are used, so an aerodrome's name, a year or a version tag is simply skipped.
// The runway goes first where there is one, so one runway's charts sit together on the airfield page.
const PROCEDURE = {
  ils: 'ILS', ilsdme: 'ILS/DME', loc: 'LOC', locdme: 'LOC/DME', vor: 'VOR', vordme: 'VOR/DME', dvor: 'DVOR',
  tacan: 'TACAN', rnav: 'RNAV', gps: 'GPS', ndb: 'NDB', dme: 'DME', gls: 'GLS', rnp: 'RNP', lda: 'LDA',
  sdf: 'SDF', asr: 'ASR', par: 'PAR', or: 'or', and: 'and',
};

/**
 * What a whole document is, when the name says so rather than naming one procedure. Kept short: these are read on a
 * card a finger wide, so "Approach 12" beats "Instrument approach 12".
 */
const KIND = {
  iac: 'Approach', app: 'Approach', approach: 'Approach',
  sid: 'Departures (SID)', dep: 'Departures (SID)', star: 'Arrivals (STAR)', arr: 'Arrivals (STAR)',
  vac: 'Visual approach', visual: 'Visual approach', adc: 'Aerodrome chart', aerodrome: 'Aerodrome chart',
  parking: 'Parking', gnd: 'Ground chart', taxi: 'Taxi chart', combined: 'Combined charts', guide: 'Guide',
};

/** "3r" → "03R", "09l" → "09L". Charts write the same runway both ways. */
function runwayName(token) {
  const m = /^(\d{1,2})([lrcLRC]?)$/.exec(token || '');
  return m ? m[1].padStart(2, '0') + m[2].toUpperCase() : null;
}

/** "hi ils or locdme" → "HI-ILS or LOC/DME", "rnav gps" → "RNAV (GPS)", "ndb dme" → "NDB/DME". */
function procedureName(tokens) {
  let high = false;
  if (tokens[0]?.toLowerCase() === 'hi') { high = true; tokens = tokens.slice(1); }
  const out = [];
  for (let i = 0; i < tokens.length; i++) {
    const word = tokens[i].toLowerCase();
    const next = tokens[i + 1]?.toLowerCase();
    if (word === 'rnav' && next === 'gps') { out.push('RNAV (GPS)'); i++; }
    else if (next === 'dme' && PROCEDURE[word] && word !== 'dme' && word !== 'or' && word !== 'and') { out.push(PROCEDURE[word] + '/DME'); i++; }
    else out.push(PROCEDURE[word] || tokens[i].toUpperCase());
  }
  const name = out.join(' ');
  return high ? `HI-${name}` : name;
}

/** Everything a chart's file name can be read for. */
function titleFromName(base) {
  const plain = base.replace(/\(.*?\)/g, ' ');
  const all = plain.split(/[^A-Za-z0-9]+/).filter(Boolean);
  if (!all.length) return null;
  // Korea puts the airport's own name first, then a separator; the rest is the chart
  const dash = plain.indexOf(' - ');
  const cut = dash >= 0 ? dash + 3 : plain.includes('_') ? plain.indexOf('_') + 1 : -1;
  const rest = cut >= 0 ? plain.slice(cut).split(/[^A-Za-z0-9]+/).filter(Boolean) : all;

  // "draggin_dep", "suwon_1_dep": a departure named after its first fix. Whatever follows ("…_obstacle_rnav") is on
  // the chart itself and would only make the card unreadable.
  const dep = rest.map((t) => t.toLowerCase()).indexOf('dep');
  if (dep > 0) return `${rest.slice(0, dep).join(' ').toUpperCase()} departure`;

  // "<procedure> rwy <runway>", or a runway written as "R09"
  const rwy = rest.findIndex((t) => /^rwy$/i.test(t) || /^rwy\d/i.test(t) || /^r\d{1,2}[lrc]?$/i.test(t));
  if (rwy >= 0) {
    const glued = /^r(?:wy)?(\d.*)$/i.exec(rest[rwy]);
    const runway = runwayName(glued ? glued[1] : rest[rwy + 1]);
    const procedure = procedureName(rest.slice(0, rwy).filter((t) => PROCEDURE[t.toLowerCase()] || /^(hi|[a-z])$/i.test(t)));
    if (runway) return procedure ? `${runway} · ${procedure}` : `RWY ${runway}`;
  }

  // "SAVT-APP", "SCCI IAC1", "vac sccy ad …": the kind of chart, sometimes numbered
  for (let i = 0; i < all.length; i++) {
    const m = /^([A-Za-z]+?)(\d*)$/.exec(all[i]);
    const kind = m && KIND[m[1].toLowerCase()];
    if (!kind) continue;
    if (m[1].toLowerCase() === 'aerodrome' && /^plan$/i.test(all[i + 1] || '')) return 'Aerodrome plan';
    return m[2] ? `${kind} ${Number(m[2])}` : kind;
  }

  // a name that only says which procedure it is ("Gunsan AB (RKJK) - ILS.pdf")
  const procedures = rest.filter((t) => PROCEDURE[t.toLowerCase()]);
  if (procedures.length) return `${procedureName(procedures)} approach`;

  // nothing but a number or a sheet letter ("SAVC-1", "e sccy ad …")
  const number = rest.filter((t) => /^\d+$/.test(t)).pop();
  if (number) return `Chart ${Number(number)}`;
  if (/^[a-z]\d?$/i.test(all[0])) return `Chart ${all[0].toUpperCase()}`;
  return null;
}

/**
 * Some charts are saved with the procedure missing from the file name ("Yecheon AB (RKTY) - .pdf"). They carry no
 * text either — they are drawn, not typeset — but the PDF's own title still holds the name it was exported under
 * ("N:\\taps\\PAA\\Yecheon\\hi_ilsdme_rwy_28.dgn"), which reads the same way as a file name.
 */
function titleFromPdfMetadata(file) {
  let text;
  try { text = fs.readFileSync(file).toString('latin1'); } catch { return null; }
  const info = /\/Title\s*\(([^)]{1,200})\)/.exec(text);
  const xmp = /<dc:title>[\s\S]{0,400}?<rdf:li[^>]*>([^<]{1,200})<\/rdf:li>/.exec(text);
  const raw = info?.[1] || xmp?.[1];
  if (!raw) return null;
  const name = raw.replace(/\\\\/g, '/').split(/[/\\]/).pop().replace(/\.[a-z0-9]+$/i, '').trim();
  return name ? titleFromName(`x - ${name}`) : null;
}

function pdfTitle(file) {
  const named = titleFromName(path.basename(file, path.extname(file)));
  // a departure named only by its number ("3 departure") has lost the procedure's name; the PDF's own title normally
  // still has it ("gangwon_3_dep" → "GANGWON 3 departure")
  if (named && /^\d/.test(named) && named.endsWith('departure')) {
    const meta = titleFromPdfMetadata(file);
    if (meta && meta.length > named.length) return meta;
  }
  return named || titleFromPdfMetadata(file) || 'Chart';
}

/**
 * Two charts of one airport must not arrive with the same title — an airfield's cards are read at a glance. Where a
 * name cannot tell them apart, the first word that differs between the files does.
 */
function withDistinctTitles(refs, files) {
  const groups = new Map();
  refs.forEach((r, i) => {
    if (!groups.has(r.title)) groups.set(r.title, []);
    groups.get(r.title).push(i);
  });
  for (const [, idx] of groups) {
    if (idx.length < 2) continue;
    const words = idx.map((i) => path.basename(files[i], path.extname(files[i])).split(/[^A-Za-z0-9]+/).filter(Boolean));
    const owns = words.map((mine, n) => {
      const others = words.filter((_, k) => k !== n).flat().map((w) => w.toLowerCase());
      return mine.find((w) => !others.includes(w.toLowerCase()));
    });
    // the one whose name says nothing extra keeps the plain title; only number them when there are two such
    const plain = owns.filter((o) => !o).length;
    idx.forEach((i, n) => {
      const own = owns[n]?.toUpperCase();
      if (!own) { if (plain > 1) refs[i].title += ` ${n + 1}`; return; }
      // a departure or arrival told apart by the fix it is named after is that procedure, not a footnote on another
      const named = /^[A-Z]{4,}$/.test(own) && /^(Departures|Arrivals)/.test(refs[i].title);
      refs[i].title = named
        ? `${own} ${refs[i].title.startsWith('Departures') ? 'departure' : 'arrival'}`
        : `${refs[i].title} · ${own}`;
    });
  }
  return refs;
}

function walkPngDirs(root, out = new Map()) {
  if (!fs.existsSync(root)) return out;
  for (const e of fs.readdirSync(root, { withFileTypes: true })) {
    const p = path.join(root, e.name);
    if (e.isDirectory()) walkPngDirs(p, out);
    // the BMS plates, plus the instrument chart PDFs that sit in the same per-airport folder. A huge PDF is a national
    // AIP volume rather than a chart (Falklands ships a 155 MB AIP for Chile), so it is left out.
    else if (/_(AGC|APC_RWY[0-9LRC]+|EOR)\.png$|\s-\s(ADC|AGC)\.png$/i.test(e.name) || (/\.pdf$/i.test(e.name) && fs.statSync(p).size < 60 * 1024 * 1024)) {
      const dir = path.dirname(p);
      if (!out.has(dir)) out.set(dir, []);
      out.get(dir).push(p);
    }
  }
  // a folder counts as an airport only when it holds at least one BMS plate; that keeps country-level PDFs out, which
  // would otherwise attach to any airport whose name starts like the folder ("Chile" → "Chile Chico")
  for (const [dir, files] of [...out]) if (!files.some((f) => /\.png$/i.test(f))) out.delete(dir);
  return out;
}

function chartTitle(file) {
  const b = path.basename(file, '.png');
  let m;
  if ((m = b.match(/APC_RWY([0-9LRC]+)$/i))) return { order: 2, title: `Parking · RWY ${m[1]}` };
  if (/AGC$/i.test(b)) return { order: 1, title: 'Airport ground chart' };
  if (/ADC$/i.test(b)) return { order: 0, title: 'Aerodrome chart' };
  if (/EOR$/i.test(b)) return { order: 3, title: 'End of runway / arming' };
  return { order: 9, title: b };
}

function chartRootsFor(th) {
  const addon = th.tdf.match(/^(Add-On [^\\/]+)/i)?.[1];
  const roots = [];
  const addDocs = (name) => { const d = path.join(DATA, name, 'Docs'); if (fs.existsSync(d)) roots.push(d); };
  if (addon) addDocs(addon);
  // theaters that reuse another add-on's objects (e.g. Hellas WCP → Hellas) inherit its charts
  const objAddon = path.relative(DATA, th.objectDir).match(/^(Add-On [^\\/]+)/i)?.[1];
  if (objAddon && objAddon !== addon) addDocs(objAddon);
  const terrAddon = path.relative(DATA, th.terrainDir).match(/^(Add-On [^\\/]+)/i)?.[1];
  if (terrAddon && terrAddon !== addon) addDocs(terrAddon);
  if (/terrdata[\\/]korea$/i.test(th.terrainDir)) roots.push(path.join(BMS_ROOT, 'Docs/03 KTO Charts'));
  return [...new Set(roots)];
}

async function pool(items, n, fn) {
  let i = 0;
  await Promise.all(Array.from({ length: n }, async () => { while (i < items.length) { const k = i++; await fn(items[k], k); } }));
}

async function main() {
  fs.mkdirSync(OUTDIR, { recursive: true });
  const theaters = loadTheaters();
  const setDone = new Set();
  const chartIndex = {};
  const jobs = new Map(); // hash -> src (single-image plates)
  const pdfJobs = new Map(); // hash -> src (instrument chart PDFs, one entry per page)
  const dirCache = new Map();
  for (const th of theaters) {
    const entryT = index.theaters.find((t) => t.id === th.id);
    if (!entryT?.primary) continue; // add-ons sharing a main theater's map reuse its airfields/charts
    const setId = entryT.airportSet;
    if (!setId || setDone.has(setId)) continue;
    setDone.add(setId);
    const roots = chartRootsFor(th);
    const dirs = new Map();
    for (const r of roots) { if (!dirCache.has(r)) dirCache.set(r, walkPngDirs(r)); for (const [k, v] of dirCache.get(r)) dirs.set(k, v); }
    const byIcao = new Map(), byName = [];
    for (const [dir, files] of dirs) {
      const name = path.basename(dir);
      const icao = name.match(/\(([A-Z0-9]{4})\)/)?.[1];
      if (icao) byIcao.set(icao, files);
      byName.push({ n: norm(name.replace(/\(.*?\)/g, '')), files });
    }
    const { airports } = buildAirports(th);
    const entry = {};
    let matched = 0;
    for (const a of airports) {
      let files = a.icao ? byIcao.get(a.icao) : null;
      if (!files) {
        const an = norm(a.name);
        const hit = byName.find((d) => d.n === an) || byName.find((d) => an.length > 4 && (d.n.startsWith(an) || an.startsWith(d.n)) && Math.min(d.n.length, an.length) >= 5);
        files = hit?.files;
      }
      if (!files) continue;
      matched++;
      const refs = files.map((f) => {
        const st = fs.statSync(f);
        const h = crypto.createHash('sha1').update(path.basename(f) + ':' + st.size).digest('hex').slice(0, 16);
        if (/\.pdf$/i.test(f)) { pdfJobs.set(h, f); return { order: 20, title: pdfTitle(f), file: `charts/${h}-1.webp`, hash: h }; }
        jobs.set(h, f);
        return { ...chartTitle(f), file: `charts/${h}.webp` };
      });
      entry[a.id] = withDistinctTitles(refs, files)
        .sort((x, y) => x.order - y.order || x.title.localeCompare(y.title, 'en', { numeric: true })).map(({ order, ...r }) => r);
    }
    chartIndex[setId] = entry;
    console.log(th.id, setId, 'roots', roots.length, 'dirs', dirs.size, 'airports matched', matched, '/', airports.length);
  }
  // charts.json is written at the end: the instrument chart entries only get their page lists once the PDFs are rendered
  const list = [...jobs.entries()].filter(([h]) => !fs.existsSync(path.join(OUTDIR, h + '.webp')));
  console.log('charts to convert', list.length, 'of', jobs.size);
  let done = 0;
  await pool(list, 6, async ([h, src]) => {
    try {
      await sharp(src, { limitInputPixels: false }).resize({ width: 1300, withoutEnlargement: true }).webp({ quality: 35, effort: 5 }).toFile(path.join(OUTDIR, h + '.webp'));
    } catch (e) { console.warn('fail', src, e.message); }
    if (++done % 100 === 0) console.log('converted', done);
  });
  // instrument charts: render each PDF once, then give every entry that uses it its page list
  const pdfPages = new Map();
  if (pdfJobs.size && fs.existsSync(PDFBOX)) {
    const todo = [...pdfJobs.entries()];
    const angles = pageAngles([...new Set(todo.map(([, src]) => src))]);
    console.log('instrument chart PDFs', todo.length);
    let n = 0, pages = 0;
    for (const [h, src] of todo) {
      const existing = fs.readdirSync(OUTDIR).filter((f) => f.startsWith(h + '-')).sort((a, b) => parseInt(a.split('-')[1]) - parseInt(b.split('-')[1]));
      let files;
      if (existing.length) files = existing.map((f) => `charts/${f}`);
      else {
        try { files = await renderPdf(src, h, OUTDIR, angles); } catch (e) { console.warn('pdf failed', src, e.message); files = []; }
      }
      pdfPages.set(h, files);
      pages += files.length;
      if (++n % 20 === 0) console.log('pdf', n, '/', todo.length, 'pages', pages);
    }
    console.log('instrument charts done:', n, 'pdfs,', pages, 'pages');
  } else if (pdfJobs.size) {
    console.warn('skipping', pdfJobs.size, 'instrument chart PDFs: no', PDFBOX);
  }
  for (const set of Object.values(chartIndex)) {
    for (const [id, list] of Object.entries(set)) {
      set[id] = list.map(({ hash, ...c }) => {
        if (!hash) return c;
        const files = pdfPages.get(hash) ?? [];
        return files.length ? { ...c, file: files[0], pages: files } : null;
      }).filter(Boolean);
    }
  }
  writeJson(path.join(FULL, 'data/charts.json'), chartIndex);
  // prune chart files no longer referenced
  const keep = new Set([...jobs.keys()].map((h) => h + '.webp'));
  for (const files of pdfPages.values()) for (const f of files) keep.add(path.basename(f));
  let pruned = 0;
  for (const f of fs.readdirSync(OUTDIR)) if (!keep.has(f)) { fs.unlinkSync(path.join(OUTDIR, f)); pruned++; }
  console.log('done, pruned', pruned);
}

main().catch((e) => { console.error(e); process.exit(1); });
