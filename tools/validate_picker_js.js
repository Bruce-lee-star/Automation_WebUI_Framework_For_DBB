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
// Fragments composed at runtime are NOT standalone-valid; their composed output is syntax-checked by
// RolePickerScriptsJsValidationTest (node --check on START_SCRIPT / PANEL_SCRIPT). They are skipped here to
// avoid false failures, while every standalone script is fully validated. Two fragment shapes exist:
//   1) head/tail parts emitted by RolePickerScripts.loadScripts / concat (e.g. *-head.js / *-tail.js);
//   2) the START_SCRIPT = A+B1+B2 and PANEL_SCRIPT = A+B composition parts
//      (picker-core-a/b1/b2.js, panel-core-a/b.js) — each is one open/close segment of a single IIFE.
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
if (failed) {
  console.error('Picker JS syntax validation FAILED (' + checked + ' files checked, ' + skipped + ' fragment(s) skipped)');
  process.exit(1);
}
console.log('Picker JS syntax validation OK: ' + checked + ' files checked, ' + skipped + ' fragment(s) skipped');
