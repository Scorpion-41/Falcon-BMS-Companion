/**
 * The catalogue of Falcon BMS config options, built from the version's own stock file.
 *
 *   node src/cfgcatalog.mjs            (BMS_ROOT points at the install)
 *
 * `User/Config/Falcon BMS.cfg` is the file BMS ships with every option set to its default, and most lines carry
 * BMS's own comment saying what the option does. That is the authority for both the list and the defaults, so the
 * catalogue is read out of it rather than written by hand — on a new BMS version this is re-run and the list is
 * right again, including anything added or removed.
 *
 * What is written by hand is only what the file does not say: which group an option belongs in, a description for
 * the few lines that carry no comment, and the named choices for the handful that take one of a set of values.
 * That lives in `tools/curated/cfgnotes.json`.
 *
 * Writes `app/src/main/assets/data/cfg/options.json`.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DATA, writeJson } from './util.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../../..');
const notes = JSON.parse(fs.readFileSync(path.join(ROOT, 'tools/curated/cfgnotes.json'), 'utf8'));

/** Which group a key belongs in, by the first rule that matches its name. */
function groupOf(key) {
  for (const [group, res] of Object.entries(notes.groups)) {
    if (res.some((re) => new RegExp(re, 'i').test(key))) return group;
  }
  return 'Other';
}

/** What kind of value it takes, from BMS's own naming: b boolean, n integer, f decimal, s text. */
function kindOf(key, value) {
  if (notes.choices[key]) return 'choice';
  const c = key.slice(2, 3);
  if (c === 'b') return 'toggle';
  if (c === 'n') return 'int';
  if (c === 'f') return 'float';
  if (c === 's') return value.startsWith('"0x') ? 'colour' : 'text';
  return 'text';
}

export function buildCatalog(bmsRoot = DATA.replace(/[\\/]Data$/, '')) {
  const file = path.join(bmsRoot, 'User/Config/Falcon BMS.cfg');
  const txt = fs.readFileSync(file, 'latin1');
  const options = [];
  for (const line of txt.split(/\r?\n/)) {
    const m = line.match(/^\s*set\s+(\S+)\s+(".*?"|\S+)\s*(?:\/\/\s*(.*?))?\s*$/);
    if (!m) continue;
    const key = m[1];
    const def = m[2];
    const said = (m[3] || '').trim();
    const note = notes.options[key] || {};
    options.push({
      k: key,
      d: def,
      kind: note.kind || kindOf(key, def),
      g: note.group || groupOf(key),
      // BMS's own words where it has them; ours only where it says nothing.
      t: said || note.text || '',
      vr: note.vr ?? /VR|HMD|MixedReality/i.test(key) ? 1 : 0,
      ...(notes.choices[key] ? { c: notes.choices[key] } : {}),
      ...(note.min !== undefined ? { min: note.min } : {}),
      ...(note.max !== undefined ? { max: note.max } : {}),
    });
  }
  // and the options BMS reads but does not write into its stock file
  const known = new Set(options.map((o) => o.k));
  for (const e of notes.extra) {
    if (known.has(e.k)) continue;
    options.push({
      k: e.k, d: e.d, kind: e.kind || kindOf(e.k, e.d), g: e.g || groupOf(e.k), t: e.t,
      vr: e.vr ?? (/VR|HMD|MixedReality/i.test(e.k) ? 1 : 0),
      ...(notes.choices[e.k] ? { c: notes.choices[e.k] } : {}),
    });
  }
  return options;
}

if (process.argv[1]?.endsWith('cfgcatalog.mjs')) {
  const options = buildCatalog();
  const out = path.join(ROOT, 'app/src/main/assets/data/cfg/options.json');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  writeJson(out, { version: notes.bms, options });
  const noText = options.filter((o) => !o.t);
  const groups = {};
  for (const o of options) groups[o.g] = (groups[o.g] || 0) + 1;
  console.log(`${options.length} options -> ${path.relative(ROOT, out)}`);
  console.log('groups:', Object.entries(groups).map(([g, n]) => `${g} ${n}`).join(', '));
  console.log(`${options.filter((o) => o.vr).length} are VR`);
  if (noText.length) console.log(`no description yet: ${noText.map((o) => o.k).join(', ')}`);
}
