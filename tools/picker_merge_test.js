'use strict';
/*
 * Node-side unit tests for the picker merge / de-duplication logic (CG-P2-N12 suggestion).
 *
 * The merge scripts under core/src/main/resources/scan/js/*.js run in the browser and mutate
 * window.__rolePicks / window.__rolePickSigs / window.__currentStep. They are the single source of
 * truth for de-duplication across navigation / pop-up / localStorage recovery. This harness shims the
 * minimal browser globals (window, localStorage, document, setTimeout) and exercises the real resource
 * files, so a regression in the de-dup key or locator-identity rule fails fast here instead of only in
 * a flaky headless run.
 *
 * The MERGE_* scripts are emitted as [base]-head.js + merge-key-shim.js + [base]-tail.js (the shim is
 * inlined where the constant referenced MERGE_KEY_SHIM mid-body); two of them are self-executing IIFEs
 * (MERGE_LOCALSTORAGE_PICKS_JS, POST_NAV_COMPACT_AND_RENDER_JS) and the rest are (a) => {...} arrows.
 * Run: node tools/picker_merge_test.js
 */
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const vm = require('vm');

const ROOT = 'd:/IdeaProject/Automation_WebUI_Framework_For_DBB';
const RES = path.join(ROOT, 'core/src/main/resources/scan/js');

function readRes(f) {
  return fs.readFileSync(path.join(RES, f), 'utf8');
}

// Build a fresh browser-global sandbox for one scenario.
function newSandbox() {
  const ls = {};
  const w = {
    __rolePicks: [],
    __rolePickSigs: {},
    __sigToPick: {},
    __rolePickSeq: 0,
    __roleMaxNo: 0,
    __currentStep: [],
    __steps: [],
    __rolePageName: 'P',
    __mergeKey: undefined,
    __sigKey: undefined,
    __pickSig: undefined,
    __renderPicks: function () {},
    __roleCloseSeq: 0,
  };
  const sandbox = {
    window: w,
    console,
    JSON,
    document: { getElementById: function () { return null; } },
    localStorage: {
      getItem: function (k) { return Object.prototype.hasOwnProperty.call(ls, k) ? ls[k] : null; },
      setItem: function (k, v) { ls[k] = String(v); },
      removeItem: function (k) { delete ls[k]; },
    },
    setTimeout: function (fn) { fn(); }, // run deferred post-nav compaction immediately
  };
  vm.createContext(sandbox);
  // Install the canonical merge-key shim so __mergeKey resolves to the production algorithm.
  vm.runInContext(readRes('merge-key-shim.js'), sandbox, { filename: 'merge-key-shim.js' });
  sandbox.__ls = ls;
  return { w: w, sandbox: sandbox };
}

// Compose a MERGE_* script (head + merge-key-shim + tail) into its original source text.
function composeMerge(base) {
  return readRes(base + '-head.js') + readRes('merge-key-shim.js') + readRes(base + '-tail.js');
}

// Load a "(a) => {...}" arrow merge resource and return the callable function.
function loadMergeArrow(sandbox, base) {
  return vm.runInContext(composeMerge(base), sandbox, { filename: base + '.js' });
}

// Run a self-executing IIFE merge resource (mutates window globals immediately).
function runMergeIIFE(sandbox, base) {
  vm.runInContext(composeMerge(base), sandbox, { filename: base + '.js' });
}

let passed = 0;
function test(name, fn) {
  fn();
  passed++;
  console.log('  ok - ' + name);
}

// ---------------------------------------------------------------------------
// T1: MERGE_SNAPSHOT_PICKS_JS de-dupes by __mergeKey (authoritative key)
// ---------------------------------------------------------------------------
test('MERGE_SNAPSHOT_PICKS_JS skips a pick whose key is already in __rolePickSigs', function () {
  const ctx = newSandbox();
  ctx.w.__rolePicks = [{ _sigKey: 'A', _pageClass: 'P1', name: 'existing' }];
  ctx.w.__rolePickSigs = { A: true }; // already registered -> must NOT be re-added
  const fn = loadMergeArrow(ctx.sandbox, 'merge-snapshot-picks-js');
  fn({ stateJson: JSON.stringify({ picks: [
    { _sigKey: 'A', _pageClass: 'P1', name: 'dup' }, // duplicate key -> skipped
    { _sigKey: 'B', _pageClass: 'P2', name: 'fresh' }, // new key -> added
  ] }) });
  assert.strictEqual(ctx.w.__rolePicks.length, 2, 'expected existing + 1 fresh, got ' + ctx.w.__rolePicks.length);
  const keys = ctx.w.__rolePicks.map(function (p) { return p._sigKey; }).sort();
  assert.strictEqual(keys.join(','), 'A,B', 'unexpected keys: ' + JSON.stringify(keys));
});

// ---------------------------------------------------------------------------
// T2: locator-identity de-dup (__LOCID) — same _sig with a locator strategy is NOT re-added
// ---------------------------------------------------------------------------
test('MERGE_SNAPSHOT_PICKS_JS locator-identity de-dup keeps one per _sig', function () {
  const ctx = newSandbox();
  ctx.w.__rolePicks = [{ _sig: 'S1', strategy: 'id', _pageClass: 'P1' }];
  const fn = loadMergeArrow(ctx.sandbox, 'merge-snapshot-picks-js');
  fn({ stateJson: JSON.stringify({ picks: [
    { _sig: 'S1', strategy: 'id', _pageClass: 'P1' }, // same locator sig -> deduped
    { _sig: 'S2', strategy: 'id', _pageClass: 'P2' }, // different sig -> added
  ] }) });
  assert.strictEqual(ctx.w.__rolePicks.length, 2, 'locator dedup failed, got ' + ctx.w.__rolePicks.length);
});

// ---------------------------------------------------------------------------
// T3: MERGE_LOCALSTORAGE_PICKS_JS (IIFE) de-dupes __currentStep by __mergeKey too
// ---------------------------------------------------------------------------
test('MERGE_LOCALSTORAGE_PICKS_JS merges picks and de-dupes currentStep', function () {
  const ctx = newSandbox();
  ctx.w.__rolePicks = [{ _sigKey: 'A', _pageClass: 'P1' }];
  ctx.w.__rolePickSigs = { A: true };
  ctx.w.__currentStep = [{ _sigKey: 'C', _pageClass: 'P1' }];
  ctx.sandbox.__ls['__rolePickState'] = JSON.stringify({
    picks: [
      { _sigKey: 'A', _pageClass: 'P1' }, // dup pick -> skipped
      { _sigKey: 'B', _pageClass: 'P2' }, // fresh -> added
    ],
    currentStep: [
      { _sigKey: 'C', _pageClass: 'P1' }, // dup step -> skipped
      { _sigKey: 'D', _pageClass: 'P2' }, // fresh -> added
    ],
  });
  runMergeIIFE(ctx.sandbox, 'merge-localstorage-picks-js');
  assert.strictEqual(ctx.w.__rolePicks.length, 2, 'picks dedup failed: ' + ctx.w.__rolePicks.length);
  assert.strictEqual(ctx.w.__currentStep.length, 2, 'currentStep dedup failed: ' + ctx.w.__currentStep.length);
});

// ---------------------------------------------------------------------------
// T4: POST_NAV_COMPACT_AND_RENDER_JS (IIFE) compacts by stable key (removes accumulated dups)
// ---------------------------------------------------------------------------
test('POST_NAV_COMPACT_AND_RENDER_JS compacts duplicate picks by stable key', function () {
  const ctx = newSandbox();
  ctx.w.__rolePicks = [
    { _sigKey: 'A', _pageClass: 'P1' },
    { _sigKey: 'A', _pageClass: 'P1' }, // duplicate accumulated across navigations
    { _sigKey: 'B', _pageClass: 'P2' },
  ];
  runMergeIIFE(ctx.sandbox, 'post-nav-compact-and-render-js');
  const keys = ctx.w.__rolePicks.map(function (p) { return p._sigKey; }).sort();
  assert.strictEqual(keys.join(','), 'A,B', 'compact left duplicates: ' + JSON.stringify(keys));
});

// ---------------------------------------------------------------------------
// T5: MERGE_CLOSE_OP_STEP_JS inserts a single close marker after the closed page's elements
// ---------------------------------------------------------------------------
test('MERGE_CLOSE_OP_STEP_JS inserts one _closeOp marker after the closed page', function () {
  const ctx = newSandbox();
  ctx.w.__currentStep = [
    { _pageClass: 'loginPage', name: 'a' },
    { _pageClass: 'privacyPage', name: 'b' },
  ];
  const fn = loadMergeArrow(ctx.sandbox, 'merge-close-op-step-js');
  fn({ closedState: JSON.stringify({ currentStep: [] }), closedCls: 'privacyPage' });
  const markers = ctx.w.__currentStep.filter(function (s) { return s._closeOp === true; });
  assert.strictEqual(markers.length, 1, 'expected exactly one close marker, got ' + markers.length);
  assert.strictEqual(markers[0]._pageClass, 'privacyPage', 'close marker on wrong page');
});

console.log('\nAll ' + passed + ' picker merge/de-dup Node tests passed.');
