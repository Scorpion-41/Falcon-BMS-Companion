// Reads Weapon Delivery Planner's five engine classes out of the decompiled program into a compact program the app
// runs as it is (`app/.../data/wdp/Engines.kt`).
//
// clsGE100, clsGE129, clsPW200, clsPW220 and clsPW229 are the F-16's engines as the flight manual charts them:
// 90,000 lines of C#, each method a table of figures with its interpolation unrolled into thousands of `else if`
// branches. Sampling those tables at their breakpoints and interpolating between them is close, but not the
// program: the ladders carry their own copy-and-paste slips (a cell interpolated from the wrong row, a weight band
// that jumps, a chart that answers 0 past one end and the last row past the other), and every one of them shows in
// the Performance page's figures. So the methods are translated, not re-derived: every statement of every method
// the page reaches, in the order the program runs it, as a small prefix code. Nothing is typed by hand and nothing
// is interpreted — the figures, the branches and the slips are the program's.
//
// The code, one method per line:
//   <class> M <name> <return type> <number of parameters> <number of slots> <statement>
// a statement:   B n s…  (block) | Sk e (slot k = e) | Xk i e (array k[i] = e) | AIk n v… (array k = v…)
//                | NAk n (new array) | IF c s s | R e | N (nothing) | SSk "text" | RS "text" | RSL k (text)
// an expression: #v (number) | T c a b (c ? a : b) | "text" (spaces as ~) | Lk (slot) | Ak i (array element)
//                | + - * / | // (whole-number division) | == != < > <= >= | & (and) | O (or) | ! (not)
//                | NEG e | TRUNC e | ROUND e | ROUNDN e d | POW a b | CMP a b | IP e×8 (Interpolate) | CALL name n e…
// Parameters are the first slots. Decimals are carried as doubles.
//
// Usage: node src/wdpengines.mjs <decompiled WeaponDeliveryPlanner folder> <out dir>
import fs from 'node:fs';
import path from 'node:path';

const [, , srcDir, outDir] = process.argv;
if (!srcDir || !outDir) {
  console.error('usage: node src/wdpengines.mjs <decompiled folder> <out dir>');
  process.exit(2);
}

const NL = String.fromCharCode(10);
const CLASSES = ['clsGE100', 'clsGE129', 'clsPW200', 'clsPW220', 'clsPW229'];
/** What the Performance page calls; the methods these call are pulled in after them. */
const ROOTS = [
  'TakeoffFactor', 'OptCruise', 'GetOptMach', 'CruiseCeiling', 'ClimbScheduleMIL', 'ClimbScheduleAB',
  'MilFuelIndex', 'MilFuelUsed', 'MilClimbIndex', 'MilClimbDistance', 'MilClimbTime',
  'ABFuelIndex', 'ABFuelUsed', 'ABClimbIndex', 'ABClimbDistance', 'ABClimbTime',
  'TakeOffSpeed', 'RotationSpeed', 'RefusalSpeed',
];

// ------------------------------------------------------------------------------------------------ tokens

function tokenize(src) {
  const out = [];
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i];
    if (c === ' ' || c === '\t' || c === '\r' || c === '\n') { i++; continue; }
    if (c === '/' && src[i + 1] === '/') { while (i < n && src[i] !== '\n') i++; continue; }
    if (c === '"') {
      let j = i + 1; let s = '';
      while (src[j] !== '"') { s += src[j]; j++; }
      out.push({ t: 'str', v: s }); i = j + 1; continue;
    }
    if (/[0-9]/.test(c) || (c === '.' && /[0-9]/.test(src[i + 1]))) {
      const m = /^[0-9]*\.?[0-9]+(?:[eE][-+]?[0-9]+)?[mMfFdD]?/.exec(src.slice(i, i + 40));
      let s = m[0];
      let kind = /[.eE]/.test(s) ? 'double' : 'int';
      if (/[mM]$/.test(s)) { kind = 'decimal'; s = s.slice(0, -1); } else if (/[fFdD]$/.test(s)) { kind = 'double'; s = s.slice(0, -1); }
      out.push({ t: 'num', v: s, kind }); i += m[0].length; continue;
    }
    if (/[A-Za-z_@]/.test(c)) {
      let j = i; while (j < n && /[A-Za-z0-9_@]/.test(src[j])) j++;
      out.push({ t: 'id', v: src.slice(i, j) }); i = j; continue;
    }
    const three = src.slice(i, i + 2);
    if (['==', '!=', '<=', '>=', '&&', '||', '+=', '-=', '*=', '/=', '++', '--'].includes(three)) { out.push({ t: 'op', v: three }); i += 2; continue; }
    out.push({ t: 'op', v: c }); i++;
  }
  return out;
}

// ------------------------------------------------------------------------------------------------ parser

class Parser {
  constructor(tokens) { this.k = tokens; this.p = 0; }
  peek(o = 0) { return this.k[this.p + o]; }
  next() { return this.k[this.p++]; }
  is(v, o = 0) { const t = this.peek(o); return t && (t.t === 'op' || t.t === 'id') && t.v === v; }
  eat(v) { const t = this.next(); if (!t || t.v !== v) throw new Error(`expected ${v}, got ${t && t.v} near ${this.ctx()}`); return t; }
  ctx() { return this.k.slice(Math.max(0, this.p - 8), this.p + 8).map(t => t.v).join(' '); }

  /** Every method of the class: name, return type, parameters, body tokens range. */
  methods() {
    const out = [];
    while (this.p < this.k.length) {
      if ((this.is('public') || this.is('private')) && this.peek(2)?.t === 'id' && this.is('(', 3)) {
        this.next();
        const ret = this.next().v;
        const name = this.next().v;
        this.eat('(');
        const params = [];
        while (!this.is(')')) {
          const type = this.next().v; const pname = this.next().v;
          params.push({ type, name: pname });
          if (this.is(',')) this.next();
        }
        this.eat(')');
        const body = this.block();
        out.push({ name, ret, params, body });
      } else this.next();
    }
    return out;
  }

  block() {
    this.eat('{');
    const list = [];
    while (!this.is('}')) list.push(this.statement());
    this.eat('}');
    return { s: 'block', list };
  }

  statement() {
    const t = this.peek();
    if (this.is('{')) return this.block();
    if (this.is('checked') && this.is('{', 1)) { this.next(); return this.block(); }
    if (this.is('unchecked') && this.is('{', 1)) { this.next(); return this.block(); }
    if (this.is('if')) {
      this.next(); this.eat('(');
      const cond = this.expr(); this.eat(')');
      const then = this.statement();
      let els = null;
      if (this.is('else')) { this.next(); els = this.statement(); }
      return { s: 'if', cond, then, els };
    }
    if (this.is('return')) { this.next(); const e = this.is(';') ? null : this.expr(); this.eat(';'); return { s: 'return', e }; }
    if (this.is('break')) { this.next(); this.eat(';'); return { s: 'break' }; }
    if (this.is('switch')) {
      this.next(); this.eat('('); const on = this.expr(); this.eat(')'); this.eat('{');
      const cases = [];
      while (!this.is('}')) {
        const labels = [];
        let isDefault = false;
        while (this.is('case') || this.is('default')) {
          if (this.is('default')) { this.next(); this.eat(':'); isDefault = true; } else { this.next(); labels.push(this.expr()); this.eat(':'); }
        }
        const body = [];
        while (!this.is('case') && !this.is('default') && !this.is('}')) body.push(this.statement());
        cases.push({ labels, isDefault, body });
      }
      this.eat('}');
      return { s: 'switch', on, cases };
    }
    // a declaration: type name [= e] ;   or   type[] name = new type[n];
    if (t.t === 'id' && ['double', 'int', 'decimal', 'bool', 'string', 'float', 'long'].includes(t.v)) {
      this.next();
      let isArray = false;
      if (this.is('[')) { this.eat('['); this.eat(']'); isArray = true; }
      const name = this.next().v;
      let init = null;
      if (this.is('=')) { this.next(); init = this.expr(); }
      this.eat(';');
      return { s: 'decl', type: t.v, isArray, name, init };
    }
    // an assignment or compound assignment
    const target = this.unary();
    const op = this.next().v;
    if (!['=', '+=', '-=', '*=', '/='].includes(op)) throw new Error('unexpected statement near ' + this.ctx());
    const e = this.expr();
    this.eat(';');
    return { s: 'assign', target, op, e };
  }

  // precedence: || , && , | , & , equality , relational , additive , multiplicative , unary
  expr() { const c = this.or(); if (!this.is('?')) return c; this.next(); const a = this.expr(); this.eat(':'); return { e: 'cond', c, a, b: this.expr() }; }
  or() { let l = this.and(); while (this.is('||')) { this.next(); l = { e: 'bin', op: '||', l, r: this.and() }; } return l; }
  and() { let l = this.bor(); while (this.is('&&')) { this.next(); l = { e: 'bin', op: '&&', l, r: this.bor() }; } return l; }
  bor() { let l = this.band(); while (this.is('|')) { this.next(); l = { e: 'bin', op: '|', l, r: this.band() }; } return l; }
  band() { let l = this.eq(); while (this.is('&')) { this.next(); l = { e: 'bin', op: '&', l, r: this.eq() }; } return l; }
  eq() { let l = this.rel(); while (this.is('==') || this.is('!=')) { const op = this.next().v; l = { e: 'bin', op, l, r: this.rel() }; } return l; }
  rel() { let l = this.add(); while (this.is('<') || this.is('>') || this.is('<=') || this.is('>=')) { const op = this.next().v; l = { e: 'bin', op, l, r: this.add() }; } return l; }
  add() { let l = this.mul(); while (this.is('+') || this.is('-')) { const op = this.next().v; l = { e: 'bin', op, l, r: this.mul() }; } return l; }
  mul() { let l = this.unary(); while (this.is('*') || this.is('/') || this.is('%')) { const op = this.next().v; l = { e: 'bin', op, l, r: this.unary() }; } return l; }
  unary() {
    if (this.is('-')) { this.next(); return { e: 'neg', a: this.unary() }; }
    if (this.is('+')) { this.next(); return this.unary(); }
    if (this.is('!')) { this.next(); return { e: 'not', a: this.unary() }; }
    // a cast: (int) x, (double) x, (float) x, (decimal) x
    if (this.is('(') && this.peek(1)?.t === 'id' && ['int', 'double', 'float', 'decimal', 'long'].includes(this.peek(1).v) && this.is(')', 2)) {
      this.next(); const type = this.next().v; this.next();
      return { e: 'cast', type, a: this.unary() };
    }
    return this.postfix();
  }
  postfix() {
    let x = this.primary();
    while (this.is('[')) { this.next(); const i = this.expr(); this.eat(']'); x = { e: 'index', a: x, i }; }
    return x;
  }
  primary() {
    const t = this.next();
    if (t.t === 'num') return { e: 'num', v: Number(t.v), kind: t.kind };
    if (t.t === 'str') return { e: 'str', v: t.v };
    if (t.t === 'op' && t.v === '(') { const x = this.expr(); this.eat(')'); return x; }
    if (t.t === 'id') {
      if (t.v === 'true') return { e: 'num', v: 1, kind: 'bool' };
      if (t.v === 'false') return { e: 'num', v: 0, kind: 'bool' };
      if (t.v === 'new') {
        const type = this.next().v;
        if (this.is('[')) {
          this.next(); const size = this.expr(); this.eat(']');
          let init = null;
          if (this.is('{')) { this.next(); init = []; while (!this.is('}')) { init.push(this.expr()); if (this.is(',')) this.next(); } this.eat('}'); }
          return { e: 'newarray', type, size, init };
        }
        this.eat('('); const a = this.args(); return { e: 'call', name: 'new ' + type, args: a };
      }
      if (t.v === 'default') { this.eat('('); this.next(); this.eat(')'); return { e: 'num', v: 0, kind: 'double' }; }
      // dotted names: Math.Round, decimal.Compare, Convert.ToDouble
      let name = t.v;
      while (this.is('.')) { this.next(); name += '.' + this.next().v; }
      if (this.is('(')) { this.next(); return { e: 'call', name, args: this.args() }; }
      return { e: 'var', name };
    }
    throw new Error('unexpected ' + t.v + ' near ' + this.ctx());
  }
  args() {
    const a = [];
    while (!this.is(')')) { a.push(this.expr()); if (this.is(',')) this.next(); }
    this.eat(')');
    return a;
  }
}

// ------------------------------------------------------------------------------------------------ code

/** Compiles one method: slots for parameters and locals, the static type of every expression. */
function compile(m, known) {
  const slots = new Map();
  const types = new Map();
  const arrays = new Set();
  for (const p of m.params) { slots.set(p.name, slots.size); types.set(p.name, norm(p.type)); }
  const calls = new Set();
  function norm(t) { return t === 'float' || t === 'decimal' ? 'double' : t === 'long' ? 'int' : t; }
  function slot(name) { if (!slots.has(name)) slots.set(name, slots.size); return slots.get(name); }
  const fmt = v => { const s = String(v); return s.includes('e') ? v.toExponential() : s; };

  function type(x) {
    switch (x.e) {
      case 'num': return x.kind === 'int' ? 'int' : x.kind === 'bool' ? 'bool' : 'double';
      case 'str': return 'string';
      case 'var': return types.get(x.name) || 'double';
      case 'index': return types.get(x.a.name) || 'double';
      case 'neg': return type(x.a);
      case 'cond': return type(x.a) === 'int' && type(x.b) === 'int' ? 'int' : 'double';
      case 'not': return 'bool';
      case 'cast': return norm(x.type);
      case 'bin':
        if (['==', '!=', '<', '>', '<=', '>=', '&&', '||'].includes(x.op)) return 'bool';
        if (x.op === '&' || x.op === '|') return 'bool';
        return type(x.l) === 'int' && type(x.r) === 'int' ? 'int' : 'double';
      case 'call':
        if (x.name === 'checked' || x.name === 'unchecked') return type(x.args[0]);
        if (x.name === 'decimal.Compare') return 'int';
        if (x.name === 'Math.Round' || x.name === 'Math.Pow' || x.name === 'Convert.ToDouble') return 'double';
        if (known.has(x.name)) return known.get(x.name);
        return 'double';
      default: return 'double';
    }
  }

  function ex(x) {
    switch (x.e) {
      case 'num': return '#' + fmt(x.v);
      case 'str': return '"' + x.v.replace(/ /g, '~') + '"';
      case 'var': if (!slots.has(x.name)) throw new Error(`${m.name}: unknown ${x.name}`); return 'L' + slots.get(x.name);
      case 'index': return `A${slots.get(x.a.name)} ${ex(x.i)}`;
      case 'neg': return x.a.e === 'num' ? '#' + fmt(-x.a.v) : 'NEG ' + ex(x.a);
      case 'cond': return `T ${ex(x.c)} ${ex(x.a)} ${ex(x.b)}`;
      case 'not': return '! ' + ex(x.a);
      case 'cast':
        // (int) of a double truncates; of an int (or an int-valued expression) it is the value
        if (norm(x.type) === 'int' && type(x.a) !== 'int') return 'TRUNC ' + ex(x.a);
        return ex(x.a);
      case 'bin': {
        const op = { '&&': '&', '||': 'O', '|': 'O' }[x.op] || x.op;
        if (op === '/' && type(x.l) === 'int' && type(x.r) === 'int') return `// ${ex(x.l)} ${ex(x.r)}`;
        if (op === '%') throw new Error('remainder not supported');
        return `${op} ${ex(x.l)} ${ex(x.r)}`;
      }
      case 'call': {
        const a = x.args;
        switch (x.name) {
          case 'checked': case 'unchecked': case 'Convert.ToDouble': case 'new decimal': case 'Convert.ToInt32':
            if (x.name === 'Convert.ToInt32') throw new Error('Convert.ToInt32');
            return ex(a[0]);
          case 'Math.Round': return a.length === 1 ? 'ROUND ' + ex(a[0]) : `ROUNDN ${ex(a[0])} ${ex(a[1])}`;
          case 'Math.Pow': return `POW ${ex(a[0])} ${ex(a[1])}`;
          case 'decimal.Compare': return `CMP ${ex(a[0])} ${ex(a[1])}`;
          case 'decimal.Divide': return `/ ${ex(a[0])} ${ex(a[1])}`;
          case 'decimal.Subtract': return `- ${ex(a[0])} ${ex(a[1])}`;
          case 'decimal.Add': return `+ ${ex(a[0])} ${ex(a[1])}`;
          case 'decimal.Multiply': return `* ${ex(a[0])} ${ex(a[1])}`;
          default:
            if (!known.has(x.name)) throw new Error(`${m.name}: call to ${x.name}`);
            calls.add(x.name);
            // Interpolate is called 7,000 times: it gets a code of its own
            if (x.name === 'Interpolate' && a.length === 8) return `IP ${a.map(ex).join(' ')}`;
            return `CALL ${x.name} ${a.length} ${a.map(ex).join(' ')}`.trim();
        }
      }
      default: throw new Error('expression ' + x.e);
    }
  }

  function st(s) {
    switch (s.s) {
      case 'block': {
        const parts = [];
        // an array filled with literals, element 0 upwards, becomes one AI
        for (let i = 0; i < s.list.length; i++) {
          const a = s.list[i];
          if (a.s === 'assign' && a.op === '=' && a.target.e === 'index' && a.target.i.e === 'num' && a.target.i.v === 0 && isLiteral(a.e)) {
            const name = a.target.a.name; const vals = [];
            let j = i;
            while (j < s.list.length) {
              const b = s.list[j];
              if (b.s === 'assign' && b.op === '=' && b.target.e === 'index' && b.target.a.name === name && b.target.i.e === 'num' && b.target.i.v === vals.length && isLiteral(b.e)) {
                vals.push(literal(b.e)); j++;
              } else break;
            }
            if (vals.length > 1) { parts.push(`AI${slots.get(name)} ${vals.length} ${vals.map(fmt).join(' ')}`); i = j - 1; continue; }
          }
          parts.push(st(a));
        }
        // a block of one statement is that statement
        if (parts.length === 1) return parts[0];
        return `B ${parts.length} ${parts.join(' ')}`.trim();
      }
      case 'decl': {
        const k = slot(s.name);
        types.set(s.name, norm(s.type));
        if (s.isArray) {
          arrays.add(s.name);
          if (!s.init || s.init.e !== 'newarray') throw new Error('array without new');
          if (s.init.init) return `AI${k} ${s.init.init.length} ${s.init.init.map(v => fmt(literal(v))).join(' ')}`;
          return `NA${k} ${ex(s.init.size).replace('#', '')}`;
        }
        if (s.init == null) return 'N';
        if (norm(s.type) === 'string') return `SS${k} ${ex(s.init)}`;
        return `S${k} ${conv(norm(s.type), s.init)}`;
      }
      case 'assign': {
        const t = s.target;
        let e = s.e;
        if (s.op !== '=') e = { e: 'bin', op: s.op[0], l: t, r: s.e };
        if (t.e === 'var' && types.get(t.name) === 'string') return `SS${slots.get(t.name)} ${ex(e)}`;
        if (t.e === 'var') return `S${slots.get(t.name)} ${conv(types.get(t.name), e)}`;
        if (t.e === 'index') return `X${slots.get(t.a.name)} ${ex(t.i)} ${conv(types.get(t.a.name), e)}`;
        throw new Error('assignment target');
      }
      case 'if': return `IF ${ex(s.cond)} ${st(s.then)} ${s.els ? st(s.els) : 'N'}`;
      case 'return':
        // a text answer is a literal or a text slot
        if (m.ret === 'string') return s.e.e === 'str' ? 'RS ' + ex(s.e) : 'RSL ' + slots.get(s.e.name);
        return 'R ' + (s.e ? conv(m.ret, s.e) : '#0');
      case 'break': return 'N';
      case 'switch': {
        // cases that end in break, as the decompiler writes them: an if-chain on the value
        let chain = 'N';
        for (let i = s.cases.length - 1; i >= 0; i--) {
          const c = s.cases[i];
          const body = c.body.filter(b => b.s !== 'break');
          const blk = st({ s: 'block', list: body });
          if (c.isDefault) { chain = blk; continue; }
          const cond = c.labels.map(l => `== ${ex(s.on)} ${ex(l)}`).reduce((acc, x) => acc ? `O ${acc} ${x}` : x, null);
          chain = `IF ${cond} ${blk} ${chain}`;
        }
        return chain;
      }
      default: throw new Error('statement ' + s.s);
    }
  }
  // an int slot takes an int value (the C# source always casts explicitly, so this is only a check)
  function conv(t, e) { return ex(e); }
  function isLiteral(e) { return e.e === 'num' || (e.e === 'neg' && e.a.e === 'num'); }
  function literal(e) { return e.e === 'num' ? e.v : -e.a.v; }

  const body = st(m.body);
  return { code: `M ${m.name} ${m.ret} ${m.params.length} ${slots.size} ${body}`, calls };
}

// ------------------------------------------------------------------------------------------------ main

fs.mkdirSync(outDir, { recursive: true });
// One file for the five classes. The GE-100 and GE-129 share most of their charts, and a method that is the same in
// two classes is written once: "= <class> <method> <class it is the same as>".
const lines = [`# Weapon Delivery Planner's engine classes, by Falcas, translated by tools/extractor/src/wdpengines.mjs`];
const seen = new Map();
for (const cls of CLASSES) {
  const src = fs.readFileSync(path.join(srcDir, cls + '.cs'), 'utf8');
  const methods = new Parser(tokenize(src)).methods();
  const byName = new Map(methods.map(m => [m.name, m]));
  const known = new Map(methods.map(m => [m.name, m.ret === 'float' || m.ret === 'decimal' ? 'double' : m.ret]));
  const want = ROOTS.filter(n => byName.has(n));
  const done = new Set();
  let own = 0;
  while (want.length) {
    const n = want.shift();
    if (done.has(n)) continue;
    done.add(n);
    const c = compile(byName.get(n), known);
    const same = seen.get(c.code);
    if (same) lines.push(`= ${cls} ${n} ${same}`);
    else { seen.set(c.code, cls); lines.push(`${cls} ${c.code}`); own++; }
    for (const k of c.calls) if (!done.has(k)) want.push(k);
  }
  console.log(`${cls}: ${done.size} methods, ${own} of its own`);
}
const text = lines.join(NL) + NL;
fs.writeFileSync(path.join(outDir, 'engines.wdpc'), text);
console.log(`wrote ${path.join(outDir, 'engines.wdpc')}: ${(text.length / 1024).toFixed(0)} KB`);
