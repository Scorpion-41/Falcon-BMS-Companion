// What the Planner adds to a Weapon Delivery Planner page it keeps, and the few controls it moves to make room —
// applied by wdplayout.mjs after it reads Falcas's designer code, so a re-run keeps them. Everything else on a page
// stays exactly where WDP's designer put it.
//
// The attack pages' Coordinates box (Pop-up, HADB, TOSS; the same box on all three): WDP's Campaign and TE buttons
// chose which cartridge table the target and IP were read from (the pilot's Callsign.ini or a TE's own mission .ini);
// the Planner reads one table, slot by slot (D87 in docs/WDP-PORT.md), so the two buttons are taken off the page
// (AttackSelection.REMOVED in the app). TGT STPT moves 50 px left in its own row and the IP STPT box stands beside
// it: the IP is the steerpoint before the target unless the pilot picks another. The 1.3.8 test builds' Send to
// DataCard stood in the two buttons' row; the page's Save to DTC fills the DataCard now (D87), so it is dropped
// ([DROP]) and, on Pop-up and TOSS, IP STPT at the VRP has that row.
//
// Usage (patches the app's layouts in place, idempotent): node src/wdpadded.mjs <layout dir>
import fs from 'node:fs';
import path from 'node:path';

//
// Pop-up and TOSS also get IP STPT at the VRP (1.3.8, D94): in VRP mode it puts a steerpoint on the VRP and plans the
// attack as VIP from it. It stands in the row WDP's Campaign and TE buttons left, clear of the IP Coordinate lines (VIP
// mode), so it can also show in VIP mode to move the IP STPT it created. HADB plans from a VRP only and has none.
const ATTACK = (tgtLabel, ipAtVrp) => ({
  parent: 'grpCoordinates',
  move: { [tgtLabel]: { x: 36 }, numWaypoint: { x: 94 } },
  add: [
    { name: 'lblIPpoint', kind: 'label', winKind: 'Label', autoSize: true, x: 144, y: 77, w: 47, h: 14, text: 'IP STPT:' },
    { name: 'numIPpoint', kind: 'number', winKind: 'NumericUpDown', x: 192, y: 74, w: 34, h: 20 },
    ...(ipAtVrp ? [{
      name: 'btnIpAtVrp', kind: 'button', winKind: 'Button', font: 'Arial', fontSize: 9.75, fontStyle: 'Bold', fg: 'Black',
      x: 42, y: 27, w: 180, h: 33, text: 'IP STPT at the VRP',
    }] : []),
  ],
});

/** Controls an earlier run added and the Planner no longer has: taken out of a layout patched in place. */
export const DROP = ['btnSendToCard'];

export const ADDED = {
  cntPopUp: ATTACK('lblWaypoint', true),
  cntHADB: ATTACK('lblWaypoint', false),
  cntTOSS: ATTACK('lblTGTwp', true),
};

/** Applies [ADDED] for [form] to its control tree [roots] (in place); a control already there is replaced, not doubled. */
export function applyAdded(form, roots) {
  const a = ADDED[form];
  if (!a) return roots;
  const find = (cs) => {
    for (const c of cs) {
      if (c.name === a.parent) return c;
      const r = c.children && find(c.children);
      if (r) return r;
    }
    return null;
  };
  const parent = find(roots);
  if (!parent) throw new Error(form + ': no ' + a.parent);
  parent.children = parent.children || [];
  for (const c of parent.children) if (a.move[c.name]) Object.assign(c, a.move[c.name]);
  const names = new Set(a.add.map(c => c.name).concat(DROP));
  parent.children = parent.children.filter(c => !names.has(c.name)).concat(a.add.map(c => ({ ...c })));
  return roots;
}

if (process.argv[1] && path.basename(process.argv[1]) === 'wdpadded.mjs') {
  const dir = process.argv[2];
  if (!dir) { console.error('usage: node src/wdpadded.mjs <layout dir>'); process.exit(2); }
  for (const form of Object.keys(ADDED)) {
    const file = path.join(dir, form + '.json');
    const j = JSON.parse(fs.readFileSync(file, 'utf8'));
    applyAdded(form, j.roots);
    fs.writeFileSync(file, JSON.stringify(j));
    console.log(form + ': ' + ADDED[form].add.map(c => c.name).join(', '));
  }
}
