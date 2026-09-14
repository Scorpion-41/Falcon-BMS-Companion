// Shared helpers for reading Falcon BMS data files (read-only access to the game folder).
import fs from 'node:fs';
import path from 'node:path';

export const BMS_ROOT = process.env.BMS_ROOT || 'G:/Falcon BMS 4.38';
export const DATA = path.join(BMS_ROOT, 'Data');

const textCache = new Map();

/** Read a text file, stripping BOM. Returns null when missing. */
export function readText(file, encoding = 'utf8') {
  const key = file + '|' + encoding;
  if (textCache.has(key)) return textCache.get(key);
  if (!fs.existsSync(file)) return null;
  let t = fs.readFileSync(file, encoding === 'latin1' ? 'latin1' : 'utf8');
  if (t.charCodeAt(0) === 0xfeff) t = t.slice(1);
  textCache.set(key, t);
  return t;
}

/** Case-insensitive path resolution (BMS mixes case freely; Windows is fine but keep portable). */
export function resolveCI(base, rel) {
  const parts = rel.replace(/\\/g, '/').split('/').filter(Boolean);
  let cur = base;
  for (const p of parts) {
    const direct = path.join(cur, p);
    if (fs.existsSync(direct)) { cur = direct; continue; }
    if (!fs.existsSync(cur) || !fs.statSync(cur).isDirectory()) return path.join(cur, ...parts.slice(parts.indexOf(p)));
    const hit = fs.readdirSync(cur).find((e) => e.toLowerCase() === p.toLowerCase());
    cur = path.join(cur, hit ?? p);
  }
  return cur;
}

export function exists(p) { return p && fs.existsSync(p); }

/**
 * Parse flat Falcon record XML (<XRecords><X Num="n"><Field>v</Field>...</X>...).
 * Returns array indexed by Num (sparse-safe).
 */
export function parseRecords(file, tag) {
  const x = readText(file);
  if (x == null) return null;
  const out = [];
  const re = new RegExp(`<${tag}\\s+Num="(\\d+)"\\s*>([\\s\\S]*?)</${tag}>`, 'gi');
  const fieldRe = /<(\w+)>([^<]*)<\/\1>/g;
  let m;
  while ((m = re.exec(x))) {
    const o = { Num: +m[1] };
    let k;
    fieldRe.lastIndex = 0;
    while ((k = fieldRe.exec(m[2]))) o[k[1]] = decodeEntities(k[2]);
    out[o.Num] = o;
  }
  return out;
}

export function decodeEntities(s) {
  return s
    .replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&#(\d+);/g, (_, d) => String.fromCharCode(+d))
    .replace(/&amp;/g, '&');
}

/** Find a file in a directory ignoring case. */
export function findFileCI(dir, name) {
  if (!dir || !fs.existsSync(dir)) return null;
  const hit = fs.readdirSync(dir).find((e) => e.toLowerCase() === name.toLowerCase());
  return hit ? path.join(dir, hit) : null;
}

export function slug(s) {
  return String(s).toLowerCase().replace(/\+/g, 'plus').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}

export function writeJson(file, obj) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(obj));
}

export const num = (v) => (v == null || v === '' ? null : Number(v));
