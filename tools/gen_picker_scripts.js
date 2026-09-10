'use strict';
// One-off generator: externalize inline picker JS string constants from RolePickerScripts.java
// into core/src/main/resources/scan/js/*.js resource files, byte-for-byte identical to the
// current runtime strings. Emits the new Java constant declarations to gen_picker_constants.txt.
const fs = require('fs');
const path = require('path');

const ROOT = 'd:/IdeaProject/Automation_WebUI_Framework_For_DBB';
const SRC = path.join(ROOT, 'codegen/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/page/scan/RolePickerScripts.java');
const INJ = path.join(ROOT, 'codegen/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/page/scan/RolePickerScriptInjector.java');
const RES = path.join(ROOT, 'core/src/main/resources/scan/js');
const src = fs.readFileSync(SRC, 'utf8');

function readRes(f) { return fs.readFileSync(path.join(RES, f), 'utf8'); }

// Precomputed (already externalized) constants read from existing resource files.
const map = {};
map['START_SCRIPT_A'] = readRes('picker-core-a.js');
map['START_SCRIPT_B1'] = readRes('picker-core-b1.js');
map['START_SCRIPT_B2'] = readRes('picker-core-b2.js');
map['START_SCRIPT'] = map['START_SCRIPT_A'] + map['START_SCRIPT_B1'] + map['START_SCRIPT_B2'];
map['STOP_SCRIPT'] = readRes('picker-stop.js');
map['SHOW_PANEL_SCRIPT'] = readRes('panel-show.js');
map['PANEL_SCRIPT_A'] = readRes('panel-core-a.js');
map['PANEL_SCRIPT_B'] = readRes('panel-core-b.js');
map['PANEL_SCRIPT'] = map['PANEL_SCRIPT_A'] + map['PANEL_SCRIPT_B'];
const PRECOMPUTED = new Set(Object.keys(map));

function kebab(name) { return name.toLowerCase().replace(/_/g, '-'); }

// Remove // and /* */ comments that occur OUTSIDE string literals. Comments between concatenated
// string literals are whitespace and do not affect the resulting string; leaving them in would let a
// comment that happens to mention START_SCRIPT / MERGE_KEY_SHIM be mistaken for a real constant reference.
function stripComments(s) {
  let out = '';
  let i = 0;
  let inStr = false;
  let quote = 0;
  while (i < s.length) {
    const c = s[i];
    if (inStr) {
      if (c === '\\') { out += c; if (i + 1 < s.length) out += s[i + 1]; i += 2; continue; }
      if (c === quote) { out += c; inStr = false; quote = 0; i++; continue; }
      out += c; i++; continue;
    }
    if (c === '"' || c === "'") { out += c; inStr = true; quote = c; i++; continue; }
    if (c === '/' && s[i + 1] === '/') { i += 2; while (i < s.length && s[i] !== '\n') i++; continue; }
    if (c === '/' && s[i + 1] === '*') { i += 2; while (i + 1 < s.length && !(s[i] === '*' && s[i + 1] === '/')) i++; i += 2; continue; }
    out += c; i++;
  }
  return out;
}

function unescapeAt(expr, i) {
  const n = expr[i + 1];
  switch (n) {
    case '\\': return ['\\', i + 2];
    case '"': return ['"', i + 2];
    case "'": return ["'", i + 2];
    case 'n': return ['\n', i + 2];
    case 't': return ['\t', i + 2];
    case 'r': return ['\r', i + 2];
    case 'b': return ['\b', i + 2];
    case 'f': return ['\f', i + 2];
    case 'u': return [String.fromCharCode(parseInt(expr.substr(i + 2, 4), 16)), i + 6];
    case '/': return ['/', i + 2];
    default: return [n, i + 2];
  }
}

function tokenize(expr) {
  const tokens = [];
  let i = 0;
  while (i < expr.length) {
    const c = expr[i];
    if (c === ' ' || c === '\t' || c === '\r' || c === '\n') { i++; continue; }
    if (c === '"') {
      i++;
      let buf = '';
      while (i < expr.length) {
        const d = expr[i];
        if (d === '\\') { const [ch, ni] = unescapeAt(expr, i); buf += ch; i = ni; }
        else if (d === '"') { i++; break; }
        else { buf += d; i++; }
      }
      tokens.push({ t: 'str', v: buf });
      continue;
    }
    if (/[A-Za-z_$]/.test(c)) {
      let name = '';
      while (i < expr.length && /[A-Za-z0-9_$]/.test(expr[i])) { name += expr[i]; i++; }
      tokens.push({ t: 'ref', v: name });
      continue;
    }
    if (c === '+') { i++; continue; }
    i++;
  }
  return tokens;
}

function evalTokens(tokens) {
  let out = '';
  for (const tk of tokens) {
    if (tk.t === 'str') out += tk.v;
    else out += (map[tk.v] !== undefined ? map[tk.v] : '');
  }
  return out;
}

const out = [];
const written = new Set();
function writeRes(name, content) {
  if (written.has(name)) return;
  fs.writeFileSync(path.join(RES, name), content, 'utf8');
  written.add(name);
}

// 1) Extract inline `public static final String NAME = ... ;` constants (in declaration order).
// The value is either a string literal (`"...";`) or a call expression with no literal
// (`concat(...)`, `loadScript(...)`). The alternation matches the declaration terminator for
// either shape: a trailing `"\s*;` for string literals (which may themselves contain `;`),
// or `;\s*\n` for call-only expressions. The old `"\s*;\s*\n` form mis-fired on call-only
// values (no `"`), greedily swallowing the following constant into the match.
const reConst = /public static final String (\w+) =([\s\S]*?)(?:"\s*;|;\s*\n)/g;
let m;
const consts = [];
while ((m = reConst.exec(src)) !== null) consts.push({ name: m[1], expr: m[2] });

for (const cst of consts) {
  if (PRECOMPUTED.has(cst.name)) continue; // already externalized; keep as-is in Java
  const tokens = tokenize(stripComments(cst.expr));
  const idxMerge = tokens.findIndex(t => t.t === 'ref' && t.v === 'MERGE_KEY_SHIM');
  const idxStart = tokens.findIndex(t => t.t === 'ref' && t.v === 'START_SCRIPT');
  let javaExpr;
  if (idxMerge >= 0) {
    const head = evalTokens(tokens.slice(0, idxMerge));
    const tail = evalTokens(tokens.slice(idxMerge + 1));
    const base = kebab(cst.name);
    writeRes(base + '-head.js', head);
    writeRes(base + '-tail.js', tail);
    javaExpr = 'loadScripts("' + base + '-head.js", "merge-key-shim.js", "' + base + '-tail.js")';
    map[cst.name] = head + map['MERGE_KEY_SHIM'] + tail;
  } else if (idxStart >= 0) {
    const head = evalTokens(tokens.slice(0, idxStart));
    const tail = evalTokens(tokens.slice(idxStart + 1));
    const base = kebab(cst.name);
    writeRes(base + '-head.js', head);
    writeRes(base + '-tail.js', tail);
    javaExpr = 'concat(loadScript("' + base + '-head.js"), START_SCRIPT, loadScript("' + base + '-tail.js"))';
    map[cst.name] = head + map['START_SCRIPT'] + tail;
  } else {
    const full = evalTokens(tokens);
    const file = kebab(cst.name) + '.js';
    writeRes(file, full);
    javaExpr = 'loadScript("' + file + '")';
    map[cst.name] = full;
  }
  out.push('public static final String ' + cst.name + ' = ' + javaExpr + ';');
}

// 2) Gate init script (RolePickerScriptInjector.gatedPickerInitScript) -> template with placeholders.
// Match the full method body (from `{` to the method-level closing `}` at 4-space indent),
// so leading `//` comments before `return` no longer break the capture.
// The gate body may be (a) inline — returning the `(function(){...})` literal directly, or
// (b) externalized — delegating to `GATE_INIT_TEMPLATE` (the gate-init.js resource) via `.replace(...)`.
// Case (b) is circular for generation (the template IS the resource we emit), so when the Injector is
// externalized we fall back to the canonical inline gate source captured at externalization time
// (tools/gate-inline-source.txt), which is byte-equivalent to the old inline body.
const GATE_SRC = path.join(ROOT, 'tools/gate-inline-source.txt');
const reGate = /static String gatedPickerInitScript\(String nlsReverseJson, boolean force\) \{([\s\S]*?)\n    \}/;
const gm = reGate.exec(fs.readFileSync(INJ, 'utf8'));
if (!gm) { console.error('gate method not found'); process.exit(2); }
let gateExpr = gm[1].includes('GATE_INIT_TEMPLATE') ? fs.readFileSync(GATE_SRC, 'utf8') : gm[1];
gateExpr = stripComments(gateExpr);
gateExpr = gateExpr.split('RolePickerScripts.START_SCRIPT').join('__START_SCRIPT__');
gateExpr = gateExpr.replace(/\(force\s*\?\s*"true"\s*:\s*"false"\)/g, '__FORCE__');
gateExpr = gateExpr.replace(/\(nlsReverseJson\s*==\s*null\s*\?[\s\S]*?GSON\.toJson\(nlsReverseJson\)\)/g, '__NLS_JSON__');
const gateTokens = tokenize(gateExpr);
let gateTpl = '';
for (const tk of gateTokens) {
  if (tk.t === 'str') gateTpl += tk.v;
  else if (tk.v === '__FORCE__' || tk.v === '__NLS_JSON__' || tk.v === '__START_SCRIPT__') gateTpl += tk.v;
  else gateTpl += '';
}
writeRes('gate-init.js', gateTpl);
out.push('// GATE_INIT_TEMPLATE = loadScript("gate-init.js")  (see RolePickerScriptInjector)');

fs.writeFileSync(path.join(ROOT, 'tools/gen_picker_constants.txt'), out.join('\n') + '\n', 'utf8');
console.log('Generated ' + written.size + ' resource files.');
console.log('Constant declarations written to tools/gen_picker_constants.txt');
