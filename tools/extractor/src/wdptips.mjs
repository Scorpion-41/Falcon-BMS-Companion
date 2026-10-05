// Seeds the Planner's tooltips with Falcas's own: every `SetToolTip(control, "text")` in Weapon Delivery Planner's
// code whose text is written out (a literal, or literals joined with +), per form.
//
// The app shows a control's tip when the mouse rests on it (a long press by finger), from
// `app/src/main/assets/data/wdp/tips/<form>.json`: `{ "<control name>": "text" }`, the control named as in the form's
// layout (`<form>.json`, written by wdplayout.mjs). One file per form, so several people can write tips at once; a tip
// a wiring works out at run time (WDP's weapon ids, a file's path) is its `"<name>.tip"` value instead
// (`WdpWiring.kt`). Falcas's tips are only the start: most controls had none (the DTC page's 435 are nearly all of
// them), and a control the Planner uses differently needs its tip rewritten by hand.
//
// **Re-running never loses a hand-written tip**: a key already in a file is kept as it is, and only keys the file
// does not have are added (`--replace` writes Falcas's text over them instead). Every form with a layout gets a file,
// empty (`{}`) where WDP had no tip, so a writer knows where each form's tips go. Tips are written in the order the
// controls sit in the layout, parents before children.
//
// Usage: node src/wdptips.mjs <decompiled WeaponDeliveryPlanner folder> <the app's data/wdp folder> [--replace]
import fs from 'node:fs';
import path from 'node:path';

const BACKSLASH = String.fromCharCode(92);
const args = process.argv.slice(2);
const replace = args.includes('--replace');
const [srcDir, wdpDir] = args.filter((a) => !a.startsWith('--'));
if (!srcDir || !wdpDir) {
  console.error('usage: node src/wdptips.mjs <decompiled folder> <app assets data/wdp folder> [--replace]');
  process.exit(2);
}

/** The layout files: every `<form>.json` whose content is a form (index.json and racks.json are not). */
function layouts() {
  const out = [];
  for (const f of fs.readdirSync(wdpDir)) {
    if (!f.endsWith('.json')) continue;
    let j;
    try { j = JSON.parse(fs.readFileSync(path.join(wdpDir, f), 'utf8')); } catch { continue; }
    if (j && typeof j === 'object' && !Array.isArray(j) && typeof j.form === 'string' && Array.isArray(j.roots)) out.push(j);
  }
  return out;
}

/** Control names in layout order, parents before children. */
function namesOf(form) {
  const out = [];
  const walk = (cs) => { for (const c of cs || []) { out.push(c.name); walk(c.children); } };
  walk(form.roots);
  return out;
}

/** Reads one C# string literal at s[i] (i at the quote); returns [text, index after it] or null. */
function literal(s, i) {
  let verbatim = false;
  if (s[i] === '@' && s[i + 1] === '"') { verbatim = true; i++; }
  if (s[i] !== '"') return null;
  let out = '';
  for (let k = i + 1; k < s.length; k++) {
    const ch = s[k];
    if (verbatim) {
      if (ch === '"') { if (s[k + 1] === '"') { out += '"'; k++; continue; } return [out, k + 1]; }
      out += ch; continue;
    }
    if (ch === '"') return [out, k + 1];
    if (ch === BACKSLASH) {
      const e = s[++k];
      out += e === 'n' ? '\n' : e === 'r' ? '\r' : e === 't' ? '\t' : e === '0' ? '' : e;
      continue;
    }
    if (ch === '\n') return null;
    out += ch;
  }
  return null;
}

/** The tips a form's code sets with written-out text: control name -> text (the first one set wins). */
function tipsIn(code) {
  const tips = new Map();
  let dynamic = 0;
  const call = /\.SetToolTip\(\s*(?:this\.)?(\w+)\s*,\s*/g;
  let m;
  while ((m = call.exec(code))) {
    let i = call.lastIndex;
    let text = '';
    let ok = false;
    for (;;) {
      const lit = literal(code, i);
      if (!lit) { ok = false; break; }
      text += lit[0];
      i = lit[1];
      while (code[i] === ' ' || code[i] === '\t') i++;
      if (code[i] === ')') { ok = true; break; }
      if (code[i] === '+') { i++; while (/\s/.test(code[i])) i++; continue; }
      ok = false; break;
    }
    if (!ok) { dynamic++; continue; }
    const clean = text.replace(/\r\n|\r/g, '\n').split('\n').map((l) => l.trim()).join('\n').trim();
    if (clean && !tips.has(m[1])) tips.set(m[1], clean);
  }
  return { tips, dynamic };
}

const outDir = path.join(wdpDir, 'tips');
fs.mkdirSync(outDir, { recursive: true });
let total = 0;
for (const form of layouts()) {
  const names = namesOf(form);
  const known = new Set(names);
  const csFile = path.join(srcDir, form.form + '.cs');
  const { tips, dynamic } = fs.existsSync(csFile) ? tipsIn(fs.readFileSync(csFile, 'utf8')) : { tips: new Map(), dynamic: 0 };
  const file = path.join(outDir, form.form + '.json');
  let existing = {};
  if (fs.existsSync(file)) {
    try { existing = JSON.parse(fs.readFileSync(file, 'utf8')); } catch (e) { console.error(`${file}: not JSON (${e.message}); left alone`); continue; }
  }
  const merged = {};
  let added = 0;
  const unknown = [];
  for (const [k, v] of tips) if (!known.has(k)) unknown.push(k);
  // layout order first, then whatever else the file holds (a writer's key for a control the layout lost)
  for (const n of names) {
    const had = Object.prototype.hasOwnProperty.call(existing, n);
    if (had && !(replace && tips.has(n))) merged[n] = existing[n];
    else if (tips.has(n)) { merged[n] = tips.get(n); added++; }
  }
  for (const k of Object.keys(existing)) if (!(k in merged)) merged[k] = existing[k];
  fs.writeFileSync(file, JSON.stringify(merged, null, 1) + '\n');
  total += added;
  console.log(`${form.form}: ${tips.size} of Falcas's tips (${dynamic} worked out at run time), ${added} added, ${Object.keys(merged).length} in the file` +
    (unknown.length ? `; not in the layout: ${unknown.join(', ')}` : ''));
}
console.log(`${total} tips added`);
