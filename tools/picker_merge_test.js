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

//  A-01（doc 22）：原先写死作者本机绝对路径 'd:/IdeaProject/...'，在 ubuntu CI 上 path.join 退化成相对路径
//    → fs.readFileSync 相对 cwd 解析 → ENOENT → 构建恒红。改为相对脚本自身定位仓库根（__dirname = tools/），
//    与 cwd / 盘符 / 目录名均无关；支持 PICKER_TEST_ROOT 环境变量覆盖（便于本地调试，非硬编码默认）。
const ROOT = process.env.PICKER_TEST_ROOT || path.resolve(__dirname, '..');
const RES = path.join(ROOT, 'pw-core/src/main/resources/scan/js');

function readRes(f) {
  const p = path.join(RES, f);
  if (!fs.existsSync(p)) {
    throw new Error('[picker_merge_test] resource not found: ' + p
            + ' (repo root resolved as: ' + ROOT + '; override with PICKER_TEST_ROOT if needed)');
  }
  return fs.readFileSync(p, 'utf8');
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

// ---------------------------------------------------------------------------
// T6: 无序号元素的"排序垫底"哨兵必须是 int32 安全值，且与 Java 侧占位语义字面一致。
//     回归两例：① 占位号 0 → 升序排到【最前】；② Number.MAX_SAFE_INTEGER（> 2^31-1）
//     → 作为真实序号回传 Java 时只能靠 Double→int 饱和"碰巧"落成 Integer.MAX_VALUE。
//     封装逻辑内联在 panel-core-b.js（非 merge-* 箭头资源），故此处按源码契约锁定。
// ---------------------------------------------------------------------------
test('__packageStep 的无序号哨兵是 int32 安全值且与 Java 占位号一致', function () {
  const js = readRes('panel-core-b.js');
  const m = js.match(/if \(!_nos\.length\) _nos = \[(\d+)\];/);
  assert.ok(m, 'panel-core-b.js 未找到无序号元素的哨兵赋值');
  const sentinel = Number(m[1]);
  assert.ok(Number.isInteger(sentinel) && sentinel > 0, '哨兵必须是正整数: ' + m[1]);
  assert.ok(sentinel <= 2147483647,
    '哨兵超出 int32 上界（回传 Java 的 intValue()/List<Integer> 路径不安全）: ' + sentinel);
  assert.strictEqual(sentinel, 2147483647,
    '哨兵应与 Java 占位号字面一致（Integer.MAX_VALUE），当前为 ' + sentinel);
  const javaSrc = fs.readFileSync(path.join(ROOT,
    'pw-codegen/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/page/scan/RoleElementStepGenerator.java'), 'utf8');
  assert.ok(/Integer\.MAX_VALUE/.test(javaSrc),
    'Java 侧未找到无序号元素的垫底兜底（Integer.MAX_VALUE）——两端口径可能再次背离');
});

// ---------------------------------------------------------------------------
// T7: 展开顺序必须是【升序】(a.no - b.no)，"哨兵垫底"才成立；降序会让哨兵跑到最前。
// ---------------------------------------------------------------------------
test('__packageStep 展开后按序号升序排序（哨兵因此垫底）', function () {
  const js = readRes('panel-core-b.js');
  assert.ok(/_expanded\.sort\(function\s*\(a,\s*b\)\s*\{\s*return\s*\(a\.no\s*\|\|\s*0\)\s*-\s*\(b\.no\s*\|\|\s*0\);\s*\}\)/.test(js),
    '_expanded 排序不是 (a.no||0)-(b.no||0) 的升序比较，"无序号元素垫底"语义不成立');
});

// ---------------------------------------------------------------------------
// T8: 面板门控必须在"关闭墓碑"与"进行中的拾取会话"之间做取舍——
//     __rolePanelEnabled==='0' 是 close-panel-js.js 写入的【粘性】墓碑；若在 bootstrap 里对它
//     无条件 return，则 __rolePanelForce 永不置位，panel-core-a.js 的 UI 门禁（'1' || force）
//     双假 ⇒ 该 origin 上后续每个新文档都没有面板（"打开新页面，没有重新创建面板"，且
//     此后任何导航都恢复不了）。回归锁：墓碑仅在【会话未开启】时生效。
// ---------------------------------------------------------------------------
test('面板 bootstrap：关闭墓碑不得压过进行中的拾取会话（否则新页面永不重建面板）', function () {
  const boot = readRes('panel-bootstrap-script.js');
  assert.ok(!/getItem\('__rolePanelEnabled'\)\s*===\s*'0'\s*\)\s*return;/.test(boot),
    'panel-bootstrap-script.js 又出现"读到墓碑就无条件 return"——新页面将永远没有面板');
  assert.ok(/if \(__panelFlag === '0' && __sessionOn !== '1'\) return;/.test(boot),
    '未找到"墓碑 + 会话开关"的取舍判断（__rolePanelEnabled=0 且 __rolePickSessionOn!=1 才 return）');
  assert.ok(/__rolePickSessionOn/.test(boot), 'bootstrap 必须读取 __rolePickSessionOn 作为会话开关');
  assert.ok(/setItem\('__rolePanelEnabled','1'\)/.test(boot), '放行路径必须把墓碑改回 1（否则下次导航又被挡）');
  assert.ok(/__rolePanelForce\s*=\s*true/.test(boot), '放行路径必须置位 __rolePanelForce（跨源新页面的唯一兜底）');
});

// ---------------------------------------------------------------------------
// T9: 上述取舍只有配合"开始拾取时强制使能面板"才闭环：Java 侧 CMD_START 分支必须调用
//     ensurePanelVisible（注入 PANEL_FORCE_AND_ENABLE_JS + PANEL_SCRIPT）。
// ---------------------------------------------------------------------------
test('开始拾取时强制使能并重建面板（Java CMD_START 分支）', function () {
  const java = fs.readFileSync(path.join(ROOT,
    'pw-codegen/src/main/java/com/hsbc/cmb/hk/dbb/automation/framework/web/page/scan/RolePickerPanelController.java'), 'utf8');
  assert.ok(/RolePickerConstants\.CMD_START\.equals\(cmd\)[\s\S]{0,400}?ensurePanelVisible\(ev\.page\);/.test(java),
    'CMD_START 分支未调用 ensurePanelVisible —— 关闭过面板后点"开始拾取"将拿不回面板');
  assert.ok(/private static void ensurePanelVisible\(Page page\)[\s\S]{0,600}?PANEL_FORCE_AND_ENABLE_JS[\s\S]{0,200}?PANEL_SCRIPT/.test(java),
    'ensurePanelVisible 必须同时注入 PANEL_FORCE_AND_ENABLE_JS（清墓碑）与 PANEL_SCRIPT（重建面板）');
});

// ---------------------------------------------------------------------------
// T10: 重放不得铸号（序号无限累积的根因）。
//   真实点击会命中 dup 分支并正常累加序号；而"__sigKey/__sigToPick/dup 全失配"的兜底分支处理的
//   其实是【同一次动作】（push 分支已铸过号），此处再铸一个 ⇒ 两个号都回传 Java 并被并集进同一条目，
//   表现为"每轮每元素 +2 个号"（现场：单元素 68~104 个号、全局涨到 354）。
// ---------------------------------------------------------------------------
test('兜底分支不再为同一次动作重复铸号（否则每轮每元素 +2）', function () {
  const js = readRes('picker-core-b1.js');
  assert.ok(!/__appendPickNo\(window\.__rolePicks\[i\]\)/.test(js),
    '兜底分支又出现 __appendPickNo(window.__rolePicks[i]) —— 每次重放会 +2 个号');
  assert.ok(/\[replay-suppressed\]/.test(js) && /__samePageSameSig/.test(js),
    '未找到"同页类 + 同 _sig 即复用既有条目"的重放抑制（replay-suppressed / __samePageSameSig）');
});

// ---------------------------------------------------------------------------
// T11: 重放被抑制时不得切页签（否则用户手动切回上一页后每轮被拉走）；
//      且写回脚本不得把"只增不回退"的单调基线压低（会与 record 的续接语义打架）。
// ---------------------------------------------------------------------------
test('重放抑制时守卫 auto-focus，且写回不压低单调序号基线', function () {
  const js = readRes('picker-core-b1.js');
  assert.ok(/if \(!__samePageSameSig && pick && pick\._pageClass\)/.test(js),
    'auto-focus 未用 !__samePageSameSig 守卫 —— 重放会持续把面板页签拉回拾取页');
  const sync = readRes('sync-panel-to-browser-js.js');
  assert.ok(!/window\.__rolePickSeq=__ns\.length;\s*window\.__roleMaxNo=__ns\.length;/.test(sync),
    'sync-panel-to-browser 又把 __rolePickSeq/__roleMaxNo 直接置为 __ns.length（压低单调基线）');
  assert.ok(/__rolePickSeq=\(window\.__rolePickSeq>__ns\.length\?window\.__rolePickSeq:__ns\.length\)/.test(sync),
    '未找到"取 max(当前, __ns.length)"的单调保留写法');
});

// ---------------------------------------------------------------------------
// T12: 回灌写回不得对 _pickNos 做【无条件并集】。
//   现场证据：同一个号会出现在多个元素的 pickNos 里（5/11/25/43/72/105/161/217/294/371/468 在
//   title_username_page、user_name、title_steps_activate_msk 等条目里都出现），号在各元素间交叉累积、
//   总数涨到 494。根因：写回时无条件取 __old（浏览器上一轮的值）∪ Java 值，而脚本末尾又把号重排成 1..N，
//   于是"旧轮的号"与"现轮的号"被反复并集，跨轮无限膨胀。
//   正确口径：并集只为保住"浏览器刚铸、Java 还没收到的新号"，而这类号必然大于 Java 侧最大号。
// ---------------------------------------------------------------------------
test('回灌写回：序号并集必须限定为“大于 Java 最大号的新号”，不得无条件并集', function () {
  const sync = readRes('sync-panel-to-browser-js.js');
  assert.ok(!/__old\.concat\(o\._pickNos\)/.test(sync),
    'sync-panel-to-browser 又出现 __old.concat(o._pickNos) 的无条件并集 —— 号会跨轮交叉累积');
  assert.ok(/__o2>__jmax/.test(sync) && /__jmax/.test(sync),
    '未找到“只并入大于 Java 最大号的旧号”（__o2>__jmax）的受限并集');
});

console.log('\nAll ' + passed + ' picker merge/de-dup Node tests passed.');
