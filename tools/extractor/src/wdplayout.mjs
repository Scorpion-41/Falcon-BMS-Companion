// Reads a Weapon Delivery Planner form's layout out of its designer code.
//
// Falcas laid those forms out in the Visual Studio designer, which writes the result as code: every control gets a
// `new System.Windows.Forms.Label()`, then a `.Location`, a `.Size`, a `.Text`, a `.Font`, and a
// `parent.Controls.Add(this.child)` that says where it sits. That is a complete description of the page — so the
// port reads it rather than re-placing 1,900 controls by hand and getting a hundred of them slightly wrong.
//
// Out comes one JSON file per page: a tree of controls with their kind, rectangle, text and font. The app draws it
// with a generic renderer (`ui/screens/wdp/WdpForm.kt`), so the layout is Falcas's and only the painting is ours.
//
// Usage: node src/wdplayout.mjs <decompiled WeaponDeliveryPlanner folder> <out dir> [Form1 Form2 …]
import fs from 'node:fs';
import path from 'node:path';
import { DROPPED_FORMS } from './wdpdropped.mjs';
import { applyAdded } from './wdpadded.mjs';

const NL = String.fromCharCode(10);
const [, , srcDir, outDir, ...only] = process.argv;
if (!srcDir || !outDir) {
  console.error('usage: node src/wdplayout.mjs <decompiled folder> <out dir> [classes…]');
  process.exit(2);
}

/** The Windows Forms classes worth drawing, mapped to what the app calls them. */
const KIND = {
  Label: 'label',
  Button: 'button',
  TextBox: 'text',
  ComboBox: 'combo',
  CheckBox: 'check',
  RadioButton: 'radio',
  GroupBox: 'group',
  Panel: 'panel',
  PictureBox: 'picture',
  NumericUpDown: 'number',
  ListBox: 'list',
  ListView: 'list',
  // the Different Flight window's package tree: drawn as a list of indented rows, which is how a pilot reads it
  TreeView: 'list',
  DataGridView: 'grid',
  TabControl: 'tabs',
  TabPage: 'tab',
  TrackBar: 'slider',
  RichTextBox: 'text',
  MaskedTextBox: 'text',
  ToolStrip: 'strip',
  MenuStrip: 'menu',
};

/**
 * WDP's own controls that stand where a picture goes: `cntImageControl` is the pannable picture box the chart
 * window, the parking window and the map draw into. Declared as `new WeaponDeliveryPlanner.cntImageControl()`.
 */
const OWN_KIND = {
  cntImageControl: 'picture',
};

function parseForm(file) {
  const text = fs.readFileSync(file, 'utf8');
  const start = text.indexOf('private void InitializeComponent()');
  if (start < 0) return null;
  const lines = text.slice(start).split(NL);

  const controls = new Map();   // name -> control
  const order = [];
  const children = new Map();   // parent -> [child]

  const declare = /this\.(\w+)\s*=\s*new System\.Windows\.Forms\.(\w+)\(\)/;
  const declareOwn = /this\.(\w+)\s*=\s*new WeaponDeliveryPlanner\.(\w+)\(\)/;
  // the form's own colours and picture: most of WDP's windows are charcoal with white ink, set on the form itself
  const formProp = /^this\.(BackColor|ForeColor|BackgroundImage)\s*=\s*(.*);\s*$/;
  const formLook = {};
  // a list's designer items: this.cboX.Items.AddRange(new object[2] { "N", "S" });
  const items = /this\.(\w+)\.Items\.AddRange\(new object\[\d*\]\s*\{(.*)\}\);/;
  // a grid's columns: this.dgvX.Columns.AddRange(this.colA, this.colB); the columns are objects, not controls
  const columns = /this\.(\w+)\.Columns\.AddRange\((.*)\);/;
  // the form's own size (a dialog's window), base.ClientSize = new System.Drawing.Size(869, 413);
  const client = /base\.ClientSize\s*=\s*new System\.Drawing\.Size\((\d+),\s*(\d+)\)/;
  const colProps = new Map();   // column object -> { header, width }
  const gridCols = new Map();   // grid -> [column object]
  let clientSize = null;
  const add = /this\.(\w+)\.Controls\.Add\(this\.(\w+)\)/;
  const prop = /this\.(\w+)\.(\w+)\s*=\s*(.*);\s*$/;

  let depth = 0;
  let seen = false;
  for (const raw of lines) {
    const line = raw.trim();
    if (line === '{') { depth++; seen = true; continue; }
    if (line === '}') { depth--; if (seen && depth <= 0) break; continue; }

    let m = line.match(declare);
    if (m) {
      const kind = KIND[m[2]];
      if (kind) { controls.set(m[1], { name: m[1], kind, winKind: m[2] }); order.push(m[1]); }
      continue;
    }
    m = line.match(declareOwn);
    if (m) {
      const kind = OWN_KIND[m[2]];
      if (kind) { controls.set(m[1], { name: m[1], kind, winKind: m[2] }); order.push(m[1]); }
      continue;
    }
    m = line.match(formProp);
    if (m) {
      if (m[1] === 'BackgroundImage') {
        const p = m[2].match(/resources\.GetObject\("([^"]+)"\)/);
        if (p) formLook.image = `${path.basename(file, '.cs')}.${p[1]}`;
      } else {
        const v = colour(m[2]);
        if (v) formLook[m[1] === 'ForeColor' ? 'fg' : 'bg'] = v;
      }
      continue;
    }
    m = line.match(items);
    if (m && controls.has(m[1])) {
      const list = [];
      const re = /"((?:[^"\\]|\\.)*)"/g;
      let q;
      while ((q = re.exec(m[2]))) list.push(q[1].replace(/\\"/g, '"').replace(/\\\\/g, '\\'));
      controls.get(m[1]).items = list;
      continue;
    }
    m = line.match(columns);
    if (m && controls.has(m[1])) {
      gridCols.set(m[1], [...m[2].matchAll(/this\.(\w+)/g)].map(x => x[1]));
      continue;
    }
    m = line.match(client);
    if (m) { clientSize = { w: +m[1], h: +m[2] }; continue; }
    m = line.match(add);
    if (m) {
      if (!children.has(m[1])) children.set(m[1], []);
      children.get(m[1]).push(m[2]);
      continue;
    }
    m = line.match(prop);
    if (!m) continue;
    const c = controls.get(m[1]);
    if (!c) {
      // a grid column is not a control, but its header and width are part of the grid's look
      if (m[2] === 'HeaderText' || m[2] === 'Width') {
        const cp = colProps.get(m[1]) || {};
        if (m[2] === 'HeaderText') { const t = m[3].match(/^"((?:[^"\\]|\\.)*)"$/); if (t) cp.header = t[1]; }
        else if (/^\d+$/.test(m[3])) cp.width = +m[3];
        colProps.set(m[1], cp);
      }
      continue;
    }
    const [, , key, value] = m;
    switch (key) {
      case 'Location': {
        const p = value.match(/Point\((-?\d+),\s*(-?\d+)\)/);
        if (p) { c.x = +p[1]; c.y = +p[2]; }
        break;
      }
      case 'Size': {
        const p = value.match(/Size\((-?\d+),\s*(-?\d+)\)/);
        if (p) { c.w = +p[1]; c.h = +p[2]; }
        break;
      }
      case 'Text': {
        const p = value.match(/^"((?:[^"\\]|\\.)*)"$/);
        if (p) c.text = p[1].replace(/\\"/g, '"').replace(/\\\\/g, '\\');
        break;
      }
      case 'Font': {
        const p = value.match(/Font\("([^"]+)",\s*([\d.]+)f?(?:,\s*System\.Drawing\.FontStyle\.(\w+))?/);
        if (p) { c.font = p[1]; c.fontSize = +p[2]; if (p[3] && p[3] !== 'Regular') c.fontStyle = p[3]; }
        break;
      }
      case 'TextAlign': {
        const p = value.match(/\.(\w+)$/);
        if (p) c.align = p[1];
        break;
      }
      case 'ForeColor':
      case 'BackColor': {
        const v = colour(value);
        if (v) c[key === 'ForeColor' ? 'fg' : 'bg'] = v;
        break;
      }
      case 'ReadOnly':
        if (value === 'true') c.readOnly = true;
        break;
      // a checkbox or radio button's state as the designer left it (the DataCard's load options start ticked)
      case 'Checked':
        if (value === 'true') c.checked = true;
        break;
      case 'CheckState':
        if (/Checked$/.test(value) && !/Unchecked$/.test(value)) c.checked = true;
        break;
      // a picture from the form's resources: recorded by the name wdpimages.mjs writes it under
      case 'BackgroundImage':
      case 'Image': {
        const p = value.match(/resources\.GetObject\("([^"]+)"\)/);
        if (p) c.image = `${path.basename(file, '.cs')}.${p[1]}`;
        break;
      }
      case 'Visible':
        if (value === 'false') c.hidden = true;
        break;
      case 'Multiline':
        if (value === 'true') c.multiline = true;
        break;
      // a label that grows to fit whatever text the program puts in it at runtime: its designer size is only the
      // size of the placeholder ("Aircraft"), and the aircraft's name is longer
      case 'AutoSize':
        if (value === 'true' && c.winKind === 'Label') c.autoSize = true;
        break;
      // right to left: the alignment it comes to is worked out after the loop (mirror)
      case 'RightToLeft':
        if (/\.Yes$/.test(value)) c.rtl = true;
        break;
      default:
        break;
    }
  }

  // RightToLeft = Yes mirrors a label's alignment, and puts a check box's box after its caption: WDP right-aligns
  // 165 value labels that way (the attack pages' DED figures, "BombRange 11013 feet  1.8 nm", 82 on the DTC page)
  // and sets no TextAlign on them, so read as TopLeft they ran into the unit beside them. Written as the alignment
  // it comes to, which the renderer already draws; the property itself is set only on labels and check boxes.
  const mirror = a => a.endsWith('Left') ? a.replace(/Left$/, 'Right') : a.endsWith('Right') ? a.replace(/Right$/, 'Left') : a;
  for (const c of controls.values()) {
    if (!c.rtl) continue;
    delete c.rtl;
    if (c.kind === 'label') c.align = mirror(c.align || 'TopLeft');
    else if (c.kind === 'check' || c.kind === 'radio') c.align = mirror(c.align || 'MiddleLeft');
  }

  for (const [grid, cols] of gridCols) {
    const g = controls.get(grid);
    g.columns = cols.map(k => (colProps.get(k) || {}).header ?? '');
    g.columnWidths = cols.map(k => (colProps.get(k) || {}).width ?? 100);
  }

  // roots are the controls nobody adopted
  const adopted = new Set();
  for (const kids of children.values()) for (const k of kids) adopted.add(k);
  const build = (name) => {
    const c = controls.get(name);
    if (!c) return null;
    const kids = (children.get(name) || []).map(build).filter(Boolean);
    return kids.length ? { ...c, children: kids } : c;
  };
  const roots = order.filter(n => !adopted.has(n)).map(build).filter(Boolean);

  return { controls: controls.size, roots, clientSize, look: formLook };
}

/** A designer colour — `Color.White`, `SystemColors.ControlDarkDark`, `Color.FromArgb(64, 64, 64)` — as the app writes it. */
function colour(value) {
  const named = value.match(/Color\.(\w+)$/);
  const argb = value.match(/FromArgb\(\s*(\d+),\s*(\d+),\s*(\d+)\s*\)/);
  return argb ? '#' + [argb[1], argb[2], argb[3]].map(n => (+n).toString(16).padStart(2, '0')).join('')
    : named ? named[1] : null;
}

fs.mkdirSync(outDir, { recursive: true });
// the forms the Planner leaves out (WDP's Threats and Munition pages, and the windows nothing opens): wdpdropped.mjs
const DROPPED = DROPPED_FORMS;
const wanted = only.length ? only : fs.readdirSync(srcDir)
  .filter(f => /^(cnt|fcls)\w+\.cs$/.test(f))
  .map(f => f.replace('.cs', ''))
  .filter(f => !DROPPED.has(f));

const index = [];
for (const cls of wanted) {
  const file = path.join(srcDir, cls + '.cs');
  if (!fs.existsSync(file)) { console.error('no ' + cls); continue; }
  const form = parseForm(file);
  if (!form || form.controls === 0) continue;
  // what the Planner adds to a page it keeps (the attack pages' IP STPT and IP STPT at the VRP): wdpadded.mjs
  applyAdded(cls, form.roots);
  const count = (function n(cs) { return cs.reduce((t, c) => t + 1 + (c.children ? n(c.children) : 0), 0); })(form.roots);
  const out = { form: cls, roots: form.roots };
  if (form.clientSize) { out.clientW = form.clientSize.w; out.clientH = form.clientSize.h; }
  Object.assign(out, form.look);
  fs.writeFileSync(path.join(outDir, cls + '.json'), JSON.stringify(out));
  index.push({ form: cls, controls: form.controls, placed: count });
}
index.sort((a, b) => b.controls - a.controls);
fs.writeFileSync(path.join(outDir, 'index.json'), JSON.stringify(index, null, 1));
console.log('form'.padEnd(24) + 'controls'.padStart(10) + 'in tree'.padStart(9));
for (const i of index) console.log(i.form.padEnd(24) + String(i.controls).padStart(10) + String(i.placed).padStart(9));
console.log('');
console.log('forms: ' + index.length + ', controls: ' + index.reduce((n, i) => n + i.controls, 0));
