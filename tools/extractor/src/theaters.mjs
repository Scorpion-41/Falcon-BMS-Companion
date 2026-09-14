// Theater discovery: theater.lst + .tdf files → resolved data directories.
import fs from 'node:fs';
import path from 'node:path';
import { DATA, readText, resolveCI, slug, exists } from './util.mjs';

function parseTdf(file) {
  const out = {};
  for (const raw of (readText(file, 'latin1') || '').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const m = line.match(/^(\S+)\s*(.*)$/);
    if (m) out[m[1].toLowerCase()] = m[2].trim();
  }
  return out;
}

/** Returns theaters with absolute, case-resolved directories. */
export function loadTheaters() {
  const lst = readText(path.join(DATA, 'TerrData/TheaterDefinition/theater.lst'), 'latin1');
  const theaters = [];
  for (const raw of lst.split(/\r?\n/)) {
    const rel = raw.trim();
    if (!rel || rel.startsWith('#')) continue;
    const tdfPath = resolveCI(DATA, rel);
    if (!exists(tdfPath)) { console.warn('missing tdf', rel); continue; }
    const t = parseTdf(tdfPath);
    const dir = (key, def) => resolveCI(DATA, t[key] || def);
    const terrainDir = dir('terraindir', 'Terrdata/korea');
    const th = {
      id: slug(t.name || path.basename(rel, '.tdf')),
      name: (t.name || path.basename(rel, '.tdf')).trim(),
      desc: (t.desc || '').replace(/\s*-?\s*WARNING:.*$/i, '').trim(),
      tdf: rel,
      addon: /^add-on/i.test(rel),
      campaignDir: dir('campaigndir', 'Campaign'),
      terrainDir,
      terrDataDir: path.dirname(terrainDir), // holds TacRefDB.xml and ATC/
      objectDir: dir('objectdir', 'Terrdata/objects'),
      data3dDir: dir('3ddatadir', 'Terrdata/objects'),
      simDir: dir('simdatadir', 'Sim'),
      artDir: dir('artdir', 'Art'),
      magDecl: Number(t.magneticdeclination || 0),
      minTacan: Number(t.mintacan || 1),
    };
    // Korea TvT has no terraindir: it falls back to default Korea terrain.
    theaters.push(th);
  }
  return theaters;
}

if (process.argv[1] && process.argv[1].endsWith('theaters.mjs')) {
  for (const t of loadTheaters()) {
    console.log(t.id, '|', t.name);
    for (const k of ['campaignDir', 'terrainDir', 'terrDataDir', 'objectDir', 'data3dDir', 'simDir']) {
      console.log('   ', k, fs.existsSync(t[k]) ? 'OK ' : 'MISSING', t[k]);
    }
  }
}
