'use strict';
// Byte-identity verification: original inline constants (current RolePickerScripts.java) must equal the
// values reconstructed from the generated resource files + new Java constant mapping.
const fs = require('fs');
const path = require('path');
const ROOT = 'd:/IdeaProject/Automation_WebUI_Framework_For_DBB';
const SRC = path.join(ROOT, 'codegen/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/page/scan/RolePickerScripts.java');
const INJ = path.join(ROOT, 'codegen/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/page/scan/RolePickerScriptInjector.java');
const RES = path.join(ROOT, 'core/src/main/resources/scan/js');
const CONST_TXT = path.join(ROOT, 'tools/gen_picker_constants.txt');

function readRes(f) { return fs.readFileSync(path.join(RES, f), 'utf8'); }
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
function stripComments(s) {
  let out = ''; let i = 0; let inStr = false; let quote = 0;
  while (i < s.length) {
    const c = s[i];
    if (inStr) { if (c === '\\') { out += c; if (i + 1 < s.length) out += s[i + 1]; i += 2; continue; } if (c === quote) { out += c; inStr = false; quote = 0; i++; continue; } out += c; i++; continue; }
    if (c === '"' || c === "'") { out += c; inStr = true; quote = c; i++; continue; }
    if (c === '/' && s[i + 1] === '/') { i += 2; while (i < s.length && s[i] !== '\n') i++; continue; }
    if (c === '/' && s[i + 1] === '*') { i += 2; while (i + 1 < s.length && !(s[i] === '*' && s[i + 1] === '/')) i++; i += 2; continue; }
    out += c; i++;
  }
  return out;
}
function tokenize(expr) {
  const tokens = []; let i = 0;
  while (i < expr.length) {
    const c = expr[i];
    if (c === ' ' || c === '\t' || c === '\r' || c === '\n') { i++; continue; }
    if (c === '"') { i++; let buf = ''; while (i < expr.length) { const d = expr[i]; if (d === '\\') { const [ch, ni] = unescapeAt(expr, i); buf += ch; i = ni; } else if (d === '"') { i++; break; } else { buf += d; i++; } } tokens.push({ t: 'str', v: buf }); continue; }
    if (/[A-Za-z_$]/.test(c)) { let name = ''; while (i < expr.length && /[A-Za-z0-9_$]/.test(expr[i])) { name += expr[i]; i++; } tokens.push({ t: 'ref', v: name }); continue; }
    if (c === '+') { i++; continue; }
    i++;
  }
  return tokens;
}
function evalTokens(tokens) { let out = ''; for (const tk of tokens) { if (tk.t === 'str') out += tk.v; else out += (map[tk.v] !== undefined ? map[tk.v] : ''); } return out; }

const src = fs.readFileSync(SRC, 'utf8');
const reConst = /public static final String (\w+) =([\s\S]*?)"\s*;\s*\n/g;
let m; const origVal = {};
while ((m = reConst.exec(src)) !== null) {
  const name = m[1]; if (PRECOMPUTED.has(name)) continue;
  const val = evalTokens(tokenize(stripComments(m[2])));
  origVal[name] = val;
  map[name] = val;
}

function resolveNew(expr) {
  expr = expr.trim();
  let mm;
  if ((mm = /^loadScript\("([^"]+)"\)$/.exec(expr))) return readRes(mm[1]);
  if ((mm = /^loadScripts\((.*)\)$/.exec(expr))) {
    return mm[1].split(',').map(s => s.trim().replace(/^"|"$/g, '')).map(p => readRes(p)).join('');
  }
  if ((mm = /^concat\((.*)\)$/.exec(expr))) {
    return mm[1].split(',').map(s => s.trim()).map(p => {
      const ls = /^loadScript\("([^"]+)"\)$/.exec(p);
      if (ls) return readRes(ls[1]);
      if (p === 'START_SCRIPT') return map['START_SCRIPT'];
      return '';
    }).join('');
  }
  throw new Error('unknown new expr form: ' + expr);
}
const ctext = fs.readFileSync(CONST_TXT, 'utf8');
const newVal = {};
ctext.split('\n').forEach(line => {
  const mm = /^public static final String (\w+) = (.*);$/.exec(line);
  if (mm) newVal[mm[1]] = resolveNew(mm[2]);
});

let failures = 0;
const names = Object.keys(origVal);
for (const name of names) {
  if (!(name in newVal)) { console.error('MISSING in new: ' + name); failures++; continue; }
  if (origVal[name] !== newVal[name]) { console.error('MISMATCH: ' + name + ' (orig len=' + origVal[name].length + ', new len=' + newVal[name].length + ')'); failures++; }
}
for (const name of Object.keys(newVal)) if (!(name in origVal)) { console.error('EXTRA in new: ' + name); failures++; }
console.log('Compared ' + names.length + ' constants. Failures: ' + failures);

const injSrc = fs.readFileSync(INJ, 'utf8');
// Widened: match the full method body up to its method-level closing `}` (tolerating leading `//`
// comments before `return`). When the Injector delegates to GATE_INIT_TEMPLATE (externalized gate),
// the body is circular for verification, so fall back to the canonical inline gate source.
const GATE_SRC = path.join(ROOT, 'tools/gate-inline-source.txt');
const reGate = /static String gatedPickerInitScript\(String nlsReverseJson, boolean force\) \{([\s\S]*?)\n    \}/;
const gm = reGate.exec(injSrc);
const gateBody = gm[1];
const geSrc = gateBody.includes('GATE_INIT_TEMPLATE') ? fs.readFileSync(GATE_SRC, 'utf8') : gateBody;
const reRet = /return\s+([\s\S]*?);\s*$/;
const retM = reRet.exec(geSrc);
let ge = stripComments(retM ? retM[1] : geSrc);
ge = ge.split('RolePickerScripts.START_SCRIPT').join('__S__');
ge = ge.replace(/\(force\s*\?\s*"true"\s*:\s*"false"\)/g, '__F__');
ge = ge.replace(/\(nlsReverseJson\s*==\s*null\s*\?[\s\S]*?GSON\.toJson\(nlsReverseJson\)\)/g, '__N__');
function evalKeepAll(tokens) { let o = ''; for (const tk of tokens) o += (tk.t === 'str' ? tk.v : tk.v); return o; }
const origGate = evalKeepAll(tokenize(ge));
const gateInit = fs.readFileSync(path.join(RES, 'gate-init.js'), 'utf8')
  .replace('__START_SCRIPT__', '__S__').replace('__FORCE__', '__F__').replace('__NLS_JSON__', '__N__');
if (origGate !== gateInit) { console.error('GATE: template structure MISMATCH (len orig=' + origGate.length + ' new=' + gateInit.length + ')'); failures++; }
else console.log('Gate init template structure: OK');

process.exit(failures === 0 ? 0 : 1);
