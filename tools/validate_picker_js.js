'use strict';
// Build-time JS syntax validation for the externally-loaded picker scripts.
// Invoked from the `core` module `validate` phase via exec-maven-plugin (node).
// Every *.js under the given resource directory is parsed with the V8 engine; a syntax error fails
// the build before any browser ever sees the script. Placeholder-bearing templates (e.g. gate-init.js
// with __START_SCRIPT__ / __FORCE__ / __NLS_JSON__) are valid JS identifiers, so they pass here too.
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const dir = process.argv[2];
if (!dir) {
  console.error('usage: node validate_picker_js.js <resource-dir>');
  process.exit(2);
}
if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) {
  console.error('resource dir not found: ' + dir);
  process.exit(2);
}

const files = fs.readdirSync(dir).filter(f => f.endsWith('.js')).sort();
// Fragments are composed at runtime and are NOT standalone-valid (each is one open/close segment of a
// single IIFE, or a head/tail part paired by RolePickerScripts.concat). They are skipped from the
// per-file check below, but their RUN-TIME COMPOSITIONS are validated explicitly by validateComposed()
// right after this loop — so the scripts that are actually injected into the browser are fully checked.
// (A-03 / doc 22：此前的 skip 以「另有 RolePickerScriptsJsValidationTest 校验组合体」为由，但该类
//   并不存在 → 运行期真正注入的 START_SCRIPT / PANEL_SCRIPT 零语法校验。现改为就地组合校验，缺口闭环，
//   无需 phantom Java 测试。来源：RolePickerScripts.START_SCRIPT = A + B1 + B2；PANEL_SCRIPT = A + B。)
const isFragment = f =>
  /-head\.js$/.test(f) || /-tail\.js$/.test(f)
  || /-core-[ab]\.js$/.test(f) || /-core-b[12]\.js$/.test(f);
let failed = 0;
let skipped = 0;
let checked = 0;
for (const f of files) {
  if (isFragment(f)) {
    skipped++;
    continue;
  }
  const p = path.join(dir, f);
  const code = fs.readFileSync(p, 'utf8');
  try {
    // new vm.Script parses the code as a full program; a SyntaxError is thrown on invalid JS.
    new vm.Script(code, { filename: f });
  } catch (e1) {
    // Expression-body scripts (e.g. is-pick-stopped-js.js containing a top-level `return`) are injected
    // by Playwright's page.evaluate, which wraps the source as `(() => { ... })` — so a top-level return is
    // legal in the runtime context but illegal as a standalone program. Retry wrapped as an arrow body to
    // validate genuine syntax while accepting the evaluate() function-body form.
    try {
      new vm.Script('(() => {\n' + code + '\n})', { filename: f });
    } catch (e2) {
      console.error('JS SYNTAX ERROR in ' + f + ': ' + e1.message);
      failed = 1;
    }
  }
  checked++;
}

// A-03 / doc 22：就地组合并校验运行期真正注入浏览器的组合脚本（替代原「由不存在的测试校验」的 skip 理由）。
function readPart(name) {
  const p = path.join(dir, name);
  if (!fs.existsSync(p)) {
    console.error('composed-script part missing: ' + name);
    process.exit(1);
  }
  return fs.readFileSync(p, 'utf8');
}
function validateComposed(label, parts) {
  const code = parts.map(readPart).join('\n;\n');
  try {
    // new vm.Script 按完整程序解析；组合体本就是完整 IIFE，语法错误会在此抛出（与运行期注入等价）。
    new vm.Script(code, { filename: label });
    console.log('composed ' + label + ' OK (' + parts.length + ' parts: ' + parts.join('+') + ')');
  } catch (e) {
    console.error('COMPOSED JS SYNTAX ERROR in ' + label + ' (' + parts.join('+') + '): ' + e.message);
    failed = 1;
  }
}
validateComposed('START_SCRIPT', ['picker-core-a.js', 'picker-core-b1.js', 'picker-core-b2.js']);
validateComposed('PANEL_SCRIPT', ['panel-core-a.js', 'panel-core-b.js']);

if (failed) {
  console.error('Picker JS syntax validation FAILED (' + checked + ' files checked, ' + skipped + ' fragment(s) skipped)');
  process.exit(1);
}
console.log('Picker JS syntax validation OK: ' + checked + ' files checked, ' + skipped + ' fragment(s) skipped');
