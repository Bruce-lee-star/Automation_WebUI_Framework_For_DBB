              // 由 Java 侧负责拼接并转义选择器字符串，避免在 Java 文本块里处理引号转义。


              // ============================================================================


              // 与 Java 侧 normalize() 保持一致：把 name 归一化（回车换行→换行、nbsp→空格、折叠空白、trim），


              // 用于和预加载的 nls 反向查表（已用同样规则规范化）做精确匹配。


              function normName(s) {


                return (s || '').replace(/\\r\\n/g, '\\n').replace(/\\u00A0/g, ' ').replace(/\\s+/g, ' ').trim();


              }

              // Local copy of isStableId (defined in picker-core-a.js inside an IIFE, not global).
              // b1 needs it at top-level scope for id-priority locator selection; keep identical rules:
              // reject >40 chars, reject 4+ consecutive digits (dynamic id), require ^[A-Za-z][w-]*$.
              function isStableId(id) {
                if (!id) return false;
                if (id.length > 40) return false;
                if (/[0-9]{4,}/.test(id)) return false;
                return /^[A-Za-z][A-Za-z0-9_-]*$/.test(id);
              }



              // 用规范化后的可见文本在预加载的 window.__nlsReverse 里反查 nls key；


              // 命中返回 {key, exact}：exact=true 表示 name 与 nls 值完全一致（精确匹配即可定位），


              // exact=false 表示经前缀/子串/模板命中（运行时须子串匹配，否则会因 name 比真实可访问名


              // 短/长而定位失败，如按钮文案 "Log out" 命中短标签 key "Log"）。


              function nlsKeyInfo(s) {


                if (!s) return { key: null, exact: false };


                var sn = normName(s);


                // 1) 精确反查（无模板变量的值）


                if (window.__nlsReverse && sn) {


                  var k = window.__nlsReverse[sn] || null;


                  if (k && k.trim()) return { key: k.trim(), exact: true };


                }


                // 1.5) 前缀反查：a11y name 常是「短标签 + 空格 + 其余内容」复合串


                //      （典型如页脚链接：可见短标签 "Hyperlink Policy" 在 NLS 中作为


                //      "Hyperlink Policy footer" 的 value 存在）。取「最长且为 name 前缀」


                //      的精确表值，用其 key —— 运行时按该短值做子串匹配即可定位，


                //      避免回退成拼接字面 name（如 "Hyperlink Policy Hyperlink ..."）。


                if (window.__nlsReverse && sn) {


                  var preKey = null, preLen = -1;


                  for (var vk in window.__nlsReverse) {


                    if (!Object.prototype.hasOwnProperty.call(window.__nlsReverse, vk)) continue;


                    var v = vk, kk = window.__nlsReverse[vk];


                    if (!v || !kk || !v.length) continue;


                    if (sn.indexOf(v) === 0 && v.length > preLen) { preLen = v.length; preKey = kk; }


                  }


                  if (preKey && preKey.trim()) return { key: preKey.trim(), exact: false };


                }


                // 1.6) 子串反查（兜底）：name 中部包含某精确表值（非前缀，避免与 1.5 重复），


                //      取最长匹配者，用于「说明文本包住短标签」等其它复合形态。


                if (window.__nlsReverse && sn) {


                  var subKey = null, subLen = -1;


                  for (var vk in window.__nlsReverse) {


                    if (!Object.prototype.hasOwnProperty.call(window.__nlsReverse, vk)) continue;


                    var v = vk, kk = window.__nlsReverse[vk];


                    if (!v || !kk || !v.length) continue;


                    var p = sn.indexOf(v);


                    if (p > 0 && v.length > subLen) { subLen = v.length; subKey = kk; }


                  }


                  if (subKey && subKey.trim()) return { key: subKey.trim(), exact: false };


                }


                // 2) 模板反查：遍历 window.__nlsTemplates（[[正则源, key], ...]），

                //    用归一化后的可见文本测试正则，命中取「字面前缀最长」者（更具体，避免误配短模板）。

                //    模板值含通配 (.*?)，永远非精确。

                // 【优化】预编译正则：将原始 [正则字符串, key] 缓存为 [RegExp, key]
                // 避免每次匹配时重复 new RegExp，减少 GC 压力
                var __tpl = window.__nlsTemplates;
                if (__tpl && __tpl.length) {
                  // 首次使用时惰性编译：将原始字符串模板转为预编译 RegExp 对象
                  if (!__tpl.__compiled) {
                    for (var __tpi = 0; __tpi < __tpl.length; __tpi++) {
                      var __tp = __tpl[__tpi];
                      if (__tp && typeof __tp[0] === 'string') {
                        try { __tp[0] = new RegExp(__tp[0]); } catch (e) { __tp[0] = null; }
                      }
                    }
                    __tpl.__compiled = true;
                  }

                  var txt = normName(s);


                  var best = null, bestLen = -1;


                  for (var i = 0; i < __tpl.length; i++) {


                    var re = __tpl[i];


                    if (!re || !re[0]) continue;


                    try {


                      if (re[0].test(txt)) {


                        var litLen = re[0].source.replace(/\\(\\.\\*\\?\\)/g, '').length;


                        if (litLen > bestLen) { bestLen = litLen; best = re[1]; }


                      }


                    } catch (e) {}


                  }


                  if (best) return { key: best, exact: false };


                }


                return { key: null, exact: false };


              }


              // 关联 label 文本（对齐 pause buildNoTextCandidates：仅 INPUT/TEXTAREA/SELECT 的


              // 原生关联 label（for/包裹），取第一个，归一化后截断 80）。aria-label 不在此列——


              // 它已进入可访问名，由 role+name 候选表达。


              function labelTextOf(el) {


                var tg = (el.tagName || '').toLowerCase();


                if (tg !== 'input' && tg !== 'textarea' && tg !== 'select') return '';


                try {


                  // 对齐 page.pause() 的 getElementLabels：遍历全部关联 label（含 aria-labelledby 指向的多个），


                  // 逐个归一化后拼接（PW 用 normalizeWhiteSpace 后 join(' ')），而非只取第一个。


                  var labels = el.labels;


                  if (labels && labels.length) {


                    var parts = [];


                    for (var i = 0; i < labels.length; i++) {


                      var s = normName(labels[i].textContent || '');


                      if (s) parts.push(s);


                    }


                    var joined = parts.join(' ');


                    if (joined) return joined.slice(0, 80);


                  }


                } catch (e) {}


                return '';


              }


              // 计算 pick 定位器在整页上匹配的元素集合（文档顺序），用于判定"一组元素"并给出序号。


              // 与生成端的定位策略对齐：role→role+name（exact=!cleaned）、text→最内层文本相等、


              // 属性类→属性值相等、label→关联控件。id/css 视为唯一（返回空）。


              function __normSafe(s) {


                try { return normName(s || ''); } catch (e) { return (s || '').replace(/\\s+/g, ' ').trim(); }


              }


              // 同帧内（一次同步执行流）对同一 pick 的 __matchingElements 结果缓存，避免 syncPanelToBrowser


              // 每 1s 对每个元素重复全页 querySelectorAll + getNameInfo（O(N²) 级卡顿主因，O1）。


              // 每次 syncPanelToBrowser / 每次点选会话前调用 window.__clearMatchCache() 清空。


              window.__matchingCache = window.__matchingCache || {};


              function __clearMatchCache() { try { window.__matchingCache = {}; } catch (e) {} }


              // 对齐 page.pause()：穿透 open shadow DOM 收集全页元素（Web Components 场景）。


              // 深度优先遍历 document 与各级 shadowRoot，返回扁平元素数组。


              function __allElementsInDoc(root) {


                var out = [];


                (function walk(node) {


                  if (!node) return;


                  var kids = (node.querySelectorAll ? node.querySelectorAll('*') : null);


                  if (!kids) return;


                  for (var i = 0; i < kids.length; i++) {


                    out.push(kids[i]);


                    var sr = null;


                    try { sr = kids[i].shadowRoot; } catch (e) {}


                    if (sr && (sr.mode === 'open' || sr.mode == null)) walk(sr);


                  }


                })(root);


                return out;


              }


              function __allAttrNodesInDoc(root, attr) {


                var out = [];


                (function walk(node) {


                  if (!node) return;


                  var local = null;


                  try { local = node.querySelectorAll('[' + attr + ']'); } catch (e) {}


                  if (local) for (var i = 0; i < local.length; i++) out.push(local[i]);


                  var kids = (node.querySelectorAll ? node.querySelectorAll('*') : null);


                  if (!kids) return;


                  for (var j = 0; j < kids.length; j++) {


                    var sr = null;


                    try { sr = kids[j].shadowRoot; } catch (e) {}


                    if (sr && (sr.mode === 'open' || sr.mode == null)) walk(sr);


                  }


                })(root);


                return out;


              }


              function __allFormNodesInDoc(root) {


                var out = [];


                (function walk(node) {


                  if (!node) return;


                  var local = null;


                  try { local = node.querySelectorAll('input,textarea,select'); } catch (e) {}


                  if (local) for (var i = 0; i < local.length; i++) out.push(local[i]);


                  var kids = (node.querySelectorAll ? node.querySelectorAll('*') : null);


                  if (!kids) return;


                  for (var j = 0; j < kids.length; j++) {


                    var sr = null;


                    try { sr = kids[j].shadowRoot; } catch (e) {}


                    if (sr && (sr.mode === 'open' || sr.mode == null)) walk(sr);


                  }


                })(root);


                return out;


              }


              var __rawMatchingElements = function(pick) {


                if (!pick) return [];


                var all = __allElementsInDoc(document), out = [], i, x;


                if (pick.strategy === 'role') {


                  var role = (pick.role || '').toLowerCase();


                  var tgt = __normSafe(pick.name);


                  var exact = !pick.cleaned;   // 生成端 @RoleElement exact = !cleaned


                  for (i = 0; i < all.length; i++) {


                    x = all[i];


                    if ((getRole(x) || '').toLowerCase() !== role) continue;


                    if (closestSafe(x, '[aria-hidden="true"]')) continue;   // 对齐 getByRole 默认排除 aria-hidden


                    var nm = __normSafe(getNameInfo(x).name);


                    if (!nm) continue;


                    if (exact ? (nm === tgt) : (nm.indexOf(tgt) !== -1)) out.push(x);


                  }


                  return out;


                }


                if (pick.strategy === 'text') {


                  var t2 = __normSafe(pick.name);


                  for (i = 0; i < all.length; i++) {


                    x = all[i];


                    if (__normSafe(x.textContent) !== t2) continue;


                    var childMatch = false;   // 仅保留最内层匹配，贴近 getByText 行为


                    for (var c = 0; c < x.children.length; c++) {


                      if (__normSafe(x.children[c].textContent) === t2) { childMatch = true; break; }


                    }


                    if (!childMatch) out.push(x);


                  }


                  return out;


                }


                var attr = null;


                if (pick.strategy === 'placeholder') attr = 'placeholder';


                else if (pick.strategy === 'altText') attr = 'alt';


                else if (pick.strategy === 'title') attr = 'title';


                else if (pick.strategy === 'i18n') attr = 'data-i18n';


                else if (pick.strategy === 'testid') attr = pick.attr || null;


                if (attr) {


                  var want = (pick.value != null ? pick.value : pick.name);


                  var nodes = __allAttrNodesInDoc(document, attr);


                  for (i = 0; i < nodes.length; i++) {


                    if ((nodes[i].getAttribute(attr) || '').trim() === want) out.push(nodes[i]);


                  }


                  return out;


                }


                if (pick.strategy === 'label') {


                  var t3 = __normSafe(pick.name);


                  var forms = __allFormNodesInDoc(document);


                  for (i = 0; i < forms.length; i++) {


                    var lbls = forms[i].labels;


                    if (lbls && lbls.length && __normSafe(lbls[0].textContent).indexOf(t3) !== -1) out.push(forms[i]);


                  }


                  return out;


                }


                return out;   // id / css 视为唯一


              };


              // 缓存包装：同一 __pickSig 在一帧内只算一次（全页 querySelectorAll + getNameInfo 较重）。


              function __matchingElements(pick) {


                if (!pick) return [];


                var ck = (typeof window.__pickSig === 'function') ? window.__pickSig(pick) : null;


                if (ck) {


                  var cached = window.__matchingCache[ck];


                  if (cached) return cached;


                  var res = __rawMatchingElements(pick);


                  window.__matchingCache[ck] = res;


                  return res;


                }


                return __rawMatchingElements(pick);


              }


              // 给 pick 附上该定位器在页面上的序号（index/count），生成 step 时输出 .nth(index)，


              // 对齐 page.pause() 的 first()/nth() 消歧。


              //


              // 【关键】序号必须"无条件"赋值（哪怕当前只匹配到 1 个），不能写成 if (ms.length > 1)。


              // 序号是元素身份的一部分（__pickSig 会把它拼进签名），而 __matchingElements 只反映


              // "计算签名那一刻的 DOM"。若按匹配数有条件赋值，同一个元素会在两种签名间跳变：


              //   · 区域扫描时区域内只有它一个同名元素 → ms.length===1 → 无 index → 'role:link:X'


              //   · 整页扫描时全页有多个同名元素     → ms.length>1  → index=0 → 'role:link:X#0'


              // 两个签名被 Java 侧当成不同 key，同一元素每扫一轮就多存一份（实测重复 4~5 次）。


              // 统一赋值后签名恒定为 'role:link:X#0'，既不再重复，又保留"同 role 同 name 的第 2 个


              // 元素（#1）能被独立拾取"的能力——这正是 page.pause() 的语义。


              // 对齐 page.pause() 的 getByRole 状态过滤：把元素当前可访问状态一并带出，


              // 供 @RoleElement 生成 disabled=/pressed=/expanded=/checked= 精确过滤。


              // 抽出为独立函数，所有定位策略（role/text/...）都经 __attachIndex 统一调用，


              // 修复"role 策略走 __attachIndex 直出、跳过 done() 导致状态/iframe 路径全部丢失"的问题。


              function __enrichState(pick, el) {


                if (!pick || !el) return pick;


                try {


                  // disabled：原生 disabled 属性或 aria-disabled（button/input/... 通用）


                  if (el.hasAttribute && el.hasAttribute('aria-disabled')) pick.disabled = (el.getAttribute('aria-disabled') === 'true') ? 'YES' : 'NO';


                  else if (typeof el.disabled === 'boolean' && el.disabled) pick.disabled = 'YES';


                } catch (e) {}


                try {


                  if (el.hasAttribute && el.hasAttribute('aria-pressed')) {


                    var pv = el.getAttribute('aria-pressed');


                    pick.pressed = (pv === 'true' || pv === 'mixed') ? 'YES' : 'NO';


                  }


                } catch (e) {}


                try {


                  if (el.hasAttribute && el.hasAttribute('aria-expanded')) pick.expanded = (el.getAttribute('aria-expanded') === 'true') ? 'YES' : 'NO';


                } catch (e) {}


                try {


                  var pRole = (pick.role || '').toLowerCase();


                  if (pRole === 'checkbox' || pRole === 'radio' || pRole === 'switch'


                      || pRole === 'menuitemcheckbox' || pRole === 'menuitemradio' || pRole === 'treeitem') {


                    var cb = el;


                    if (cb.type === 'checkbox' || cb.type === 'radio') pick.checked = !!cb.checked;


                    else if (cb.hasAttribute && cb.hasAttribute('aria-checked')) {


                      var _av = cb.getAttribute('aria-checked');


                      pick.checked = (_av === 'true' || _av === 'mixed');


                    }


                  }


                } catch (e) {}


                // iframe 嵌套路径：仅当元素确实位于 iframe 内时写入，供 Java 侧生成 frameLocator。


                try {


                  var fp = __framePathOf();


                  if (fp && fp.length) pick.framePath = fp;


                } catch (e) {}


                // 归属空间（space）：融合「iframe 链」+「open shadow 链」，标注该元素位于哪个空间。


                // 约定格式（与 RoleEntry.space 注释一致）：main / frame:login / shadow:hostComp /


                // frame:login>shadow:comp / frame:a>frame:b。仅主文档元素为 "main"。


                // 该字段为可读标注（不参与代码生成语义），驱动生成的是下方独立的 shadowPath（结构化）。


                try {


                  // 收集 open shadow 宿主链（自顶向下）：每跳一层 shadow 取其宿主的 CSS 选择器。


                  var shadowHosts = []; // 自底向上收集，后反转


                  try {


                    var n = el;


                    while (n) {


                      var rn = (n.getRootNode ? n.getRootNode() : null);


                      if (rn && rn.host) {


                        var h = rn.host;


                        var ht = (h.tagName || '').toLowerCase();


                        var hid = (h.getAttribute && h.getAttribute('id')) || '';


                        var hcls = (h.getAttribute && h.getAttribute('class')) || '';


                        var hsel = ht + (hid ? '#' + hid : (hcls ? '.' + hcls.trim().split(/\s+/).join('.') : ''));


                        shadowHosts.unshift(hsel); // 自底向上，最终反转得到自顶向下


                        n = h;


                      } else break;


                    }


                  } catch (se) {}


                  // 结构化 shadow 路径（自顶向下），供 Java 侧生成「显式切换 shadow」step。


                  // 注意：shadowHosts 经上方循环逐层 unshift 后已是【自顶向下】顺序（最外层宿主在前），


                  // 与下方 space 字符串的口径一致（space 用 shadowHosts[length-1] 即最顶层起拼），故此处不可再 reverse。


                  if (shadowHosts.length) pick.shadowPath = shadowHosts.slice();


                  // 组装 readable space（约定格式）


                  var fp2 = (pick.framePath && pick.framePath.length) ? pick.framePath : null;


                  var spaceParts = [];


                  if (fp2) { for (var fi = 0; fi < fp2.length; fi++) {


                    var seg = fp2[fi];


                    var mName = seg.match(/^iframe\\[name=["']([^"']+)["']\\]$/);


                    var mId = seg.match(/^#([\\w-]+)$/);


                    spaceParts.push(mName ? ('frame:' + mName[1]) : (mId ? ('frame:' + mId[1]) : seg));


                  } }


                  if (shadowHosts.length) {


                    for (var si = shadowHosts.length - 1; si >= 0; si--) {


                      spaceParts.push('shadow:' + shadowHosts[si]);


                    }


                  }


                  pick.space = (spaceParts.length ? spaceParts.join('>') : 'main');


                } catch (e) {}


                // 【关键修复"面板勾选 alert/弹窗元素只生成 click"】


                // 真实点击触发 dialog/popup 时由 Java onDialog/onPopup 回写标记；但"面板勾选扫描候选"不经


                // 真实点击，无法获得标记 → 生成 step 只是裸 click。此处启发式识别会触发 dialog / 弹窗的元素，


                // 使扫描/勾选也能带上 dialog/popup 标记，封装时生成 acceptAlert/waitForNewPage 而非裸 click。


                //   · dialog：元素任一 on* 事件属性源码含 alert( / confirm( / prompt( → 标记 dialog，


                //     alert 默认 accept、confirm/prompt 默认 dismiss（与 Java onDialog 语义一致）；


                //   · popup：a[target=_blank]（新标签）或任一 on* 事件属性含 window.open( → 标记 popup。


                try {


                  if (!pick.dialog && !pick.popup) {


                    var __evtSrc = '';


                    var __elAttrs = el.attributes;


                    if (__elAttrs) {


                      for (var __ai = 0; __ai < __elAttrs.length; __ai++) {


                        var __an = __elAttrs[__ai].name || '';


                        if (__an.indexOf('on') === 0) __evtSrc += ' ' + (__elAttrs[__ai].value || '');


                      }


                    }


                    if (__evtSrc) {


                      var __elc = __evtSrc.toLowerCase();


                      if (__elc.indexOf('alert(') !== -1 || __elc.indexOf('confirm(') !== -1 || __elc.indexOf('prompt(') !== -1) {


                        pick.dialog = true;


                        pick.dialogType = (__elc.indexOf('confirm(') !== -1) ? 'confirm'


                                : (__elc.indexOf('prompt(') !== -1) ? 'prompt' : 'alert';


                        pick.dialogAction = (pick.dialogType === 'confirm' || pick.dialogType === 'prompt') ? 'dismiss' : 'accept';


                      }


                      if (__elc.indexOf('window.open(') !== -1) pick.popup = true;


                    }


                  }


                  var __pickTag = (el.tagName || '').toLowerCase();


                  if (!pick.popup && __pickTag === 'a') {


                    var __aTgt = (el.getAttribute && el.getAttribute('target')) || '';


                    if (__aTgt && __aTgt.charAt(0) === '_' && __aTgt.toLowerCase() !== '_self') pick.popup = true;


                  }


                } catch (e) {}


                return pick;


              }


              function __attachIndex(pick, el) {


                try {


                  var ms = __matchingElements(pick);


                  var idx = ms.indexOf(el);


                  if (idx >= 0) { pick.count = ms.length; pick.index = idx; }


                // Aligned with page.pause: only assign index when the locator actually matches the target element. If ms.indexOf(el) < 0 (selector does not resolve to this element), do NOT fake index=0 -- leave it unset so the mismatch surfaces instead of producing a silently-wrong nth(0).


                } catch (e) {}


                // 统一在此做状态/iframe 路径富化（所有策略都会经过），保证 role 策略也不再漏掉。


                try { __enrichState(pick, el); } catch (e) {}


                return pick;


              }


              window.__computePick = function(t) {


                // ① 重定位（对齐 recorder retarget）：label → 控件；向上找交互角色祖先


                // originalIsLabel 标记"用户点的是 <label> 本身"而非直接点控件——


                // 点击 label 时走 getByLabel 策略（定位到其关联控件），点击控件本身时跳过本分支，


                // 改为 role+name/placeholder 等控件-centric 定位，避免"点输入框却生成 label 策略"的错位。


                var originalIsLabel = !!(t && t.tagName === 'LABEL');


                var el = resolveLabel(t);


                // Fix: label merged with input as one pick. By default resolveLabel redirects a clicked


                // <label> to its associated control, so clicking "Username" label and the input both land


                // on the same input and get deduped into one. Expectation: label and input are two


                // independent locatable elements (label for asserting/clicking text, input for filling).


                // Keep redirect only when the associated control is checkbox/radio ("click label = toggle


                // control", preserve merge). For textbox/select etc., if the label itself has data-i18n or


                // visible text, let the label stand on its own so it becomes a separate pick from the input.


                if (originalIsLabel) {


                  var __ctrl = resolveLabel(t);


                  var __ctrlRole = (__ctrl && __ctrl !== t) ? (getRole(__ctrl) || '').toLowerCase() : '';


                  if (__ctrlRole === 'checkbox' || __ctrlRole === 'radio') {


                    el = __ctrl;


                  } else if ((t.getAttribute && (t.getAttribute('data-i18n') || '').trim())


                             || (t.textContent || '').replace(/\s+/g, '').length) {


                    el = t;


                  }


                }


                // label stands on its own (clicked label with text/i18n and a non-checkbox/radio control):
                // skip the interactive-ancestor redirect so the label keeps being picked
                // independently from its associated input (fix: label independent from input).
                if (!(originalIsLabel && el === t)) {
                  var cur = t, guard = 0;
                  while (cur && guard++ < 5) {
                    var node = resolveLabel(cur);
                    if (INTERACTIVE_ROLES[(getRole(node) || '').toLowerCase()]) {
                      if (window.__roleScanRoot && (node === window.__roleScanRoot || !window.__roleScanRoot.contains(node))) break;
                      el = node; break;
                    }
                    cur = cur.parentElement;
                  }
                }
                window.__lastPickEl = el;


                var tag = (el.tagName || '').toLowerCase();


                // 对齐 page.pause() 的 frameLocator 录制：计算元素所在 iframe 的嵌套路径（自顶向下）。


                // 每帧优先取 name / id / 稳定 css 选择器；主框架（window.self === window.top）返回空数组。


                function __framePathOf() {


                  var path = [];


                  try {


                    var w = window;


                    while (w && w !== w.top) {


                      // 取当前层 iframe 的选择器：优先 frameElement 的 name / id，


                      // 若访问 frameElement 受限（如 file:// 下跨源 SecurityError）则退化为当前 frame 的 window.name。


                      var sel = null;


                      try {


                        var fe = w.frameElement;


                        if (fe) {


                          var nm = fe.getAttribute && fe.getAttribute('name');


                          if (nm && nm.trim()) sel = 'iframe[name="' + nm.trim() + '"]';


                          if (!sel) {


                            var fid = fe.getAttribute && fe.getAttribute('id');


                            if (isStableId(fid)) sel = '#' + fid;


                          }


                          if (!sel) {


                            // 无 name / 无稳定 id：用 src 片段作为稳定标签（供 page.frame 兜底 + CSS frameLocator 双通道定位）


                            var fsrc = fe.getAttribute && fe.getAttribute('src');


                            if (fsrc && fsrc.trim()) {


                              var u = fsrc.trim();


                              // 取 url 中可辨识的片段（去掉协议/域名前缀，保留路径+query），提升跨上下文可读性


                              var cut = u.indexOf('//');


                              var frag = cut >= 0 ? u.slice(u.indexOf('/', cut + 2)) : u;


                              sel = 'iframe[src*="' + frag + '"]';


                            }


                          }


                          if (!sel) {


                            var p = fe.parentElement;


                            var s = 'iframe';


                            if (p) {


                              var same = [];


                              for (var c = p.firstElementChild; c; c = c.nextElementSibling) {


                                if (c.tagName === fe.tagName) same.push(c);


                              }


                              if (same.length > 1) s += ':nth-of-type(' + (same.indexOf(fe) + 1) + ')';


                            }


                            sel = s;


                          }


                        }


                      } catch (feErr) { /* frameElement 访问受限，下面用 window.name 兜底 */ }


                      if (!sel) {


                        // 退化：用 frame 自身的 window.name（file:// / 跨源下仍可访问，不受 frameElement SecurityError 限制）


                        try { if (w.name && w.name.trim()) sel = 'iframe[name="' + w.name.trim() + '"]'; } catch (_) {}


                      }


                      if (!sel) sel = 'iframe';


                      path.unshift(sel);


                      w = w.parent;


                    }


                  } catch (e) { path = []; }


                  return path;


                }


                function done(o) {


                  o.tag = tag;


                  o.text = ownVisibleText(el).slice(0, 120);


                  // 状态/iframe 路径富化统一由 __enrichState 完成（__attachIndex 与此处共用），


                  // 保证所有策略（含 role 直出路径）都能带上 ARIA 状态、checked、framePath 等。


                  try { __enrichState(o, el); } catch (e) {}


                  // 可见文本类语义策略（text/altText/title/placeholder/label）做 NLS 反查：


                  // 命中则仅带 key（生成 @RoleElement(key=...)），未命中则字面文本。


                  // 注意：key-only 在运行时统一按 getByText(key 解析) 兜底（用户已接受该降级）。


                  // role 策略在上方已自行反查，不走这里。


                  var KEY_ONLY = { text:1, altText:1, title:1, placeholder:1, label:1 };


                  if (KEY_ONLY[o.strategy]) {


                    var ki = nlsKeyInfo(o.name);


                    o.key = ki.key;


                    o.matched = !!(o.key);


                    // 非精确命中（前缀/子串/模板）：运行时须按子串匹配才能定位，


                    // 否则按钮文案 "Log out" 命中短标签 key "Log" 时精确匹配会找不到。


                    o.cleaned = !ki.exact;


                  }


                  return __attachIndex(o, el);


                }


                // JS 侧转义选择器值（与 Java 侧 escapeSelectorValue 语义一致：转义反斜杠与双引号），


                // 供下方 [name=] / [type=] 等 css 属性选择器安全拼接。


                function escapeSelectorValue(s) {


                  return (s == null ? '' : String(s)).replace(new RegExp('\\\\', 'g'), '\\\\\\\\').replace(new RegExp('"', 'g'), '\\\\"');


                }


                // 文本候选可见性判定（对齐 page.pause 的 suitableTextAlternatives：getByText 只匹配可见文本）。


                // 隐藏元素（display:none / visibility:hidden / opacity:0）不产生 text 候选。


                function __isVisibleForText(node) {


                  try {


                    var cs = getComputedStyleSafe(node);


                    if (!cs || cs.display === 'none' || cs.visibility === 'hidden'


                        || parseFloat(cs.opacity || '1') === 0) return false;


                    return true;


                  } catch (e) { return true; }


                }


                // ② 按 pause 打分序出候选（分低者优先）


                // 1. testId（打分 1，最优；本项目扩展 data-testid/-test-id/-test/-qa 同权）


                var testAttrs = ['data-testid','data-test-id','data-test','data-qa'];

                var __testidPick = null;

                for (var i = 0; i < testAttrs.length; i++) {

                  var tv = el.getAttribute(testAttrs[i]);

                  if (!tv || !tv.trim()) continue;

                  // Same testid value on MULTIPLE elements => ambiguous. Drop testid and

                  // fall through to role strategy (more stable per user request),

                  // instead of emitting nth(index) like page.pause does by default.

                  var __same = 0;

                  try {

                    var __sel = '[' + testAttrs[i] + '=' + JSON.stringify(tv.trim()) + ']';

                    var __nodes = document.querySelectorAll(__sel);

                    __same = __nodes ? __nodes.length : 0;

                  } catch (e) { __same = 1; }

                  if (__same <= 1) { __testidPick = { strategy:'testid', attr:testAttrs[i], value:tv.trim(), name:tv.trim() }; break; }

                }

                if (__testidPick) return done(__testidPick);

                // 2. 语义角色 + 可访问名（role+name，打分 100，先于 placeholder/label）


                //    覆盖所有"有意义的" ARIA 角色（button/link/textbox/heading/img/listitem/region 等），


                //    对齐 page.pause 的 getByRole 对全部角色生效；仅 generic/none/presentation 这类


                //    无语义角色走下方 data-i18n / alt / text 兜底。


                var r = (getRole(el) || '').toLowerCase();


                if (r && !NON_ROLE[r]) {


                  var nameInfo = getNameInfo(el);


                  var nm = nameInfo.name;


                  if (nm) {


                    var lvl = (r === 'heading') ? getHeadingLevel(el) : 0;


                    // 元素若带 data-i18n，其属性值即多语言 key——语言无关、比可见文本稳定，


                    // 优先作为 @RoleElement 的 key（运行时仍按 role+name 定位，兼得 i18n 稳定与 role 抗结构）。


                    var i18nAttr = el.getAttribute('data-i18n');


                    if (i18nAttr && i18nAttr.trim()) {


                      return __attachIndex({ strategy:'role', role:r, name:nm, key:i18nAttr.trim(), matched:true,


                        cleaned:false, level:lvl,


                        tag:tag, text: ownVisibleText(el).slice(0, 120) }, el);


                    }


                    // 否则用可见 name 反查 nls key（精确 + 前缀/子串 + 模板），命中则复用真实 key，


                    // 未命中则留空回退字面 name。cleaned 触发条件：


                    //   a) nameInfo.cleaned —— 剔除了装饰性伪元素/描述文本（如 " (opens in a new window)"）；


                    //   b) !nls.exact —— NLS 非精确命中（前缀/子串/模板），运行时须子串匹配才能定位。


                    var nls = nlsKeyInfo(nm);


                    return __attachIndex({ strategy:'role', role:r, name:nm, key:nls.key, matched: !!nls.key,


                      cleaned: nameInfo.cleaned || !nls.exact, level:lvl,


                      tag:tag, text: ownVisibleText(el).slice(0, 120) }, el);


                  }


                  // 有角色无名称：不放行到下方 data-i18n/placeholder/label/alt/text/title/id 等带名候选


                  // （它们都要求有可访问名，本分支已无 name 自然不命中），而是落到下方 step 8.5 的


                  // roleWithoutName(510) 兜底（#id 优先于它，故先过 #id 再 roleWithoutName），与 pause 一致。


                }


                // 2.5 data-i18n 多语言 key（本项目约定）：走到这里说明元素无语义角色（generic 的 span/div 等，


                //     有语义角色的元素已在上方 step 2 走 role+key）。对无角色的纯文本/容器元素，data-i18n 的


                //     属性值即多语言 key——语言无关、比可见文本稳定，生成时转为 @Element("[data-i18n=\"key\"]")


                //     （CSS 属性选择器），无需在 @RoleElement 设专门字段。


                var i18n = el.getAttribute('data-i18n');


                if (i18n && i18n.trim()) return done({ strategy:'i18n', value:i18n.trim(), name:i18n.trim() });


                // 3. placeholder（120；pause 仅对 INPUT/TEXTAREA）


                if (tag === 'input' || tag === 'textarea') {


                  var ph = el.getAttribute('placeholder');


                  if (ph && ph.trim()) return done({ strategy:'placeholder', attr:'placeholder', value:ph.trim(), name:ph.trim() });


                }


                // 4. 原生关联 label → getByLabel（140，置于 role+name 之后，与 pause 一致）


                //    仅当用户"直接点击 <label> 本身"时生效（originalIsLabel）；


                //    此时 getByLabel 会定位到该 label 关联的控件，符合"点标签=操作控件"的直觉。


                //    若点击的是表单控件本身，则跳过本分支（输入控件已在上方 role+name 定位），


                //    避免"点了输入框却生成 label 策略"的错位。


                if (originalIsLabel) {


                  // 若 label 关联的控件本身是 checkbox / radio，则改用控件自身的 role 策略，


                  // 使其与"直接点击该 checkbox/radio"生成完全相同的 _sigKey（role:checkbox:name#0），


                  // 在拾取去重处天然合并为同一条，避免 step 里同时出现 label.click() 与 chk.setChecked() 的重复。


                  var __ctrlRole = (getRole(el) || '').toLowerCase();


                  if (__ctrlRole === 'checkbox' || __ctrlRole === 'radio') {


                    var __cb = getNameInfo(el);


                    if (__cb && __cb.name) return done({ strategy:'role', role:__ctrlRole, name:__cb.name, nlsKey:__cb.nlsKey, resolvedKey:__cb.resolvedKey });


                  }


                  var lbl = labelTextOf(el);


                  if (lbl) return done({ strategy:'label', name:lbl });


                }


                // 5. alt（160；pause 中限定 APPLET/AREA/IMG/INPUT，先于 text）


                if (tag === 'img' || tag === 'area' || tag === 'input') {


                  var alt = el.getAttribute('alt');


                  if (alt && alt.trim()) return done({ strategy:'altText', attr:'alt', value:alt.trim(), name:alt.trim() });


                }


                // 6. 可见文本（180；对齐 page.pause 的 getByText 稳定性取舍）：


                //    pause 中先试子串/前缀（非 exact，score 180）再试精确（exact，score 185）。


                //    对齐 pause 的 suitableTextAlternatives：隐藏元素（display:none / visibility:hidden /


                //    opacity:0）不产生 text 候选，让位给下方 title / id / css（getByText 本就只匹配可见文本）。


                //    注：可见性判定复用 getComputedStyleSafe（与拾取主流程同一实现），避免对隐藏元素的文本误生成。


                //    本项目先出非 exact 候选（稳定容忍文案微调：去掉两端空白/装饰、按归一化 name 反查，


                //    命中则运行时子串匹配），再出精确候选。


                //    · 文案 ≤80 字符：精确匹配（exact:true），语义清晰且稳定。


                //    · 长文案（>80）：跳过 text 策略，长文本作定位锚点极易随文案/排版变化而失效，


                //      让位给更稳定的 title / id / css（对齐 page.pause 的"优先最短稳定选择器"原则），


                //      从源头避免生成脆弱、易碎的整段文本定位器。


                if (__isVisibleForText(el)) {


                  var ot = ownVisibleText(el);


                  // 兜底：textContent 因含隐藏子文本（aria-label / 装饰 span / 伪元素注入）导致为空或 >80 时，


                  // 改用 innerText（仅视觉可见文本）重试，让"说明性文案"类元素优先走 getByText，


                  // 而非退化到 body 级 css 长路径兜底被 __recordPick 拦截。


                  if ((!ot || ot.length > 80) && typeof el.innerText === 'string') {


                    var ot2 = (el.innerText || '').replace(/\s+/g, ' ').trim();


                    if (ot2 && ot2.length <= 80) ot = ot2;


                  }


                  if (ot && ot.length <= 80) {


                    // 6a. 非 exact 优先：归一化可见文本反查 nls（前缀/子串/模板命中即非精确），


                    //     让运行时按子串匹配，容忍文案微调。done() 内 KEY_ONLY 会据 nlsKeyInfo(name)


                    //     重算 cleaned（= !exact），非精确命中即生成 @RoleElement(text=..., exact=false)。


                    var otNls = nlsKeyInfo(ot);


                    if (otNls.key && !otNls.exact) {


                      return done({ strategy:'text', name:ot, key:otNls.key, matched:true });


                    }


                    // 6b. 精确匹配：直接用可见文本（done 内 KEY_ONLY 精确命中 → cleaned=false → exact 默认）。


                    return done({ strategy:'text', name:ot });


                  }


                }


                // 7. title（200）


                var title = el.getAttribute('title');


                if (title && title.trim()) return done({ strategy:'title', attr:'title', value:title.trim(), name:title.trim() });
                // 8. stable id (500): placed AFTER text/title to align with page.pause scoring.
                var id = el.getAttribute('id');
                if (isStableId(id)) return done({ strategy:'id', id:id });







                // 8.5 无名称的语义角色（roleWithoutName，510，对齐 page.pause）：


                //     仅当元素有 ARIA 角色（NON_ROLE 之外）但无可见名/aria-label 时命中（如


                //     <div role="listitem"> 无文本、role="img" 无 alt 的纯结构/装饰元素）。


                //     排在 #id(500) 之后、[name=](520) 之前，与 pause 一致。生成 @RoleElement(role=...) 无 name。


                if (r && !NON_ROLE[r]) return done({ strategy:'role', role:r });


                // 8.6 属性级 css 候选（对齐 page.pause 的 score 520/521 兜底层级，


                //     排在 roleWithoutName 之后、完整 css 路径之前，使 [name=] / input[type=] 比自动长 css 路径更稳定）：


                //   · [name=...]（520）：任意带 name 属性的元素。


                //   · input/textarea/select 的 type（520/521）：如 input[type=search]。


                // （注：PW 的 tag 级 css（530）在歧义时会被更高分的完整 css 路径覆盖；本项目直接保留


                //   下方的 cssPathOf 精确长路径作为最终兜底，不退化成裸 tag 选择器，以保持定位唯一性。）


                var nmAttr = el.getAttribute('name');


                if (nmAttr && nmAttr.trim()) return done({ strategy:'css', css: tag + '[name="' + escapeSelectorValue(nmAttr.trim()) + '"]', needsReview:false });


                if (tag === 'input' || tag === 'textarea' || tag === 'select') {


                  var ty = el.getAttribute('type');


                  if (ty && ty.trim()) return done({ strategy:'css', css: tag + '[type="' + escapeSelectorValue(ty.trim()) + '"]', needsReview:false });


                }


                // 9. 完整 css 路径兜底（needsReview=true）。


                return done({ strategy:'css', css: cssPathOf(el), needsReview:true });


              };





              // 定位器签名（与 Java 端 RoleElementPageGenerator.locatorKey 规则一致）：


              // role 按 role+name/key；id/css 按选择器；其余语义策略按 strategy+name。


              // 同一签名重复点击不入列、计数不增，避免生成端才去重导致"点了但计数不变"的困惑。


              window.__pickSig = function(pick) {


                if (!pick) return '';


                var base;


                if (pick.strategy === 'role') {


                  base = 'role:' + (pick.role || '') + ':' + (pick.key || pick.name || '');


                } else if (pick.strategy === 'i18n') base = 'i18n:' + (pick.name || '');


                else if (pick.strategy === 'id') base = 'id:' + (pick.id || '').replace(/^#/, '');


                else if (pick.strategy === 'css') base = 'css:' + (pick.css || '');


                else base = pick.strategy + ':' + (pick.name || '');


                // 一组同定位器元素（如页面上两条 role/name 完全相同的 link）按序号区分签名，


                // 使 nth(0)/nth(1) 均可独立拾取、互不去重——对齐 page.pause() 用 nth() 消歧的语义。


                // __attachIndex 已保证 index 恒被赋值（唯一匹配时为 0），签名因此稳定不跳变；


                // 若此处退回"有多个匹配才加序号"，同一元素会在 'X' 与 'X#0' 间摇摆而被重复收录。


                // 页面字段仍按不含序号的 locatorKey 归一为同一个 PageElement。


                if (pick.index != null && pick.index >= 0) base += '#' + pick.index;


                return base;


              };


              // 去重复合键：签名 + 所属 Page 类。关键修复：不同页面上 role/name 完全相同的"共用元素"


              // （如各页都有的 Close / Next 按钮、页眉页脚链接）原本只按签名去重，会被误判为重复而被丢弃


              // （表现：跳到新页面，有些元素没抓到 / 关弹窗后弹窗元素丢失）。把 _pageClass 纳入去重键后，


              // 同一页内仍按签名去重（同一元素重复点只保留一份），但跨页同名元素各自独立保留。


              window.__sigKey = function(pick) {


                // 已固化过键则直接复用：键一旦生成就是该元素的永久身份，绝不因"当前在哪个页面"而改变。


                // 跨页同步下来的元素（syncPanelToBrowser 写入 _sigKey）在别的页面被重算时，会因


                // _pageClass 缺失退化到 location 兜底而算出新键，造成同一元素重复收录——此处提前返回即可根除。


                if (pick && pick._sigKey) return pick._sigKey;


                var pageKey = (pick && pick._pageClass) || '';


                // 页面类（_pageClass）就是页面身份，稳定可靠：有它时去重键只用 [pickSig, pageClass]，


                // 不再附加 URL。此前无条件把 location.href 拼进键，导致"URL change 后再回到本页"时


                // 因 query/hash 抖动使同一元素的键改变、去重失配 → role 元素整组重复收录（本次 bug 根因）。


                // 仅当 _pageClass 尚未派生（整页跳转瞬间为空）才用规范化路径（origin+pathname，去掉 query/hash）


                // 兜底区分跨页同名元素，避免旧页签名误判；query/hash 抖动被归一，不再重复。


                if (!pageKey) {


                  try { if (location) pageKey = (location.origin || '') + (location.pathname || ''); } catch (e) {}


                }


                return JSON.stringify([window.__pickSig(pick) || '', pageKey]);


              };


              // 落盘最新拾取态到 localStorage：用于"点击会触发整页跳转的元素"场景——


              // 跳转瞬间 onFrameNavigated 恢复用的 Java 快照由主循环每 ~1s 刷新，可能来不及包含刚刚这次点击，


              // 导致刚点的元素在 window 重建后被旧快照覆盖丢失（表现：点了"返回登录"按钮却没被拾取上）。


              // pagehide/beforeunload 时把最新态写入 localStorage（同域整页跳转间 localStorage 保留），


              // 新窗口 onFrameNavigated 再据此合并补回最新点击的元素。


              // 即时落盘（供 pagehide/beforeunload 调用，不被节流，确保整页跳转瞬间 localStorage 是最新态）。


              function __persistNow() {


                try {


                  var payload = JSON.stringify({


                    picks: window.__rolePicks || [],


                    steps: window.__steps || [],


                    currentStep: window.__currentStep || [],


                    sigs: window.__rolePickSigs || {}


                  });


                  // O3：内容未变则跳过落盘，避免拾取静止时仍每 ~120ms 做一次全量序列化/写 localStorage


                  // （随元素增多 O(n²) 的卡顿来源之一）。pagehide/beforeunload 触发的即时落盘若内容相同也无副作用。


                  if (window.__lastPersistJson === payload) return;


                  window.__lastPersistJson = payload;


                  localStorage.setItem('__rolePickState', payload);


                } catch (e) {}


                window.__lastPersist = Date.now();


              }


              // 企业级优化：每次点击都全量序列化 localStorage（随拾取增多 O(n²)）是拾取卡顿来源之一。


              // 改为节流——相邻高频拾取最多每 ~120ms 落盘一次；但 pagehide/beforeunload 走 __persistNow 即时落盘，


              // 保证"点击触发整页跳转"前最后一击一定已写入 localStorage（onFrameNavigated 合并恢复依赖它，不丢元素）。


              window.__persistPickState = function() {


                var now = Date.now();


                if (now - (window.__lastPersist || 0) < 120) {


                  if (!window.__persistTimer) {


                    window.__persistTimer = setTimeout(function() {


                      window.__persistTimer = null; __persistNow();


                    }, 130);


                  }


                  return;


                }


                __persistNow();


              };


              if (!window.__rolePersistHooked) {


                window.__rolePersistHooked = true;


                window.addEventListener('pagehide', __persistNow);


                window.addEventListener('beforeunload', __persistNow);


              }


              // 每次注入都从既有 picks 重建签名表，保证与 __rolePicks 严格同步；


              // 去重键改为"签名+页面类"（__sigKey），跨页同名元素不再互相误删。


              window.__rolePickerLib = true;


              }


              // 【修复"重挂清空去重键导致重复点击丢序号"】
              // 此前每轮 ensurePickingActive 重挂都会执行 window.__rolePickSigs = {} / __sigToPick = {}
              // 把已建立的去重键与 sig→pick 映射整组清空，随后仅从当时 window.__rolePicks 重建。
              // 若重建时刻 __rolePicks 尚未被 Java syncPanelToBrowser 补齐（或重建后 pick 缺 _pageClass/_sigKey
              // 导致 __sigKey 算空），去重键就永久丢失 → 二次点击 dup 判定为 false 走 push，且 push 回传链路
              // 因键不稳定而无法与 Java 权威态对齐，表现为"同一元素重复点击序号不累加/不回传"。
              // 改为【保留式】初始化：仅在映射尚不存在时建空对象，已建立的键一律保留，避免重挂破坏去重状态。
              window.__rolePickSigs = window.__rolePickSigs || {};


              window.__sigToPick = window.__sigToPick || {};


              (window.__rolePicks || []).forEach(function(p) {


                var k = window.__sigKey(p);


                // 同 __recordPick：重建签名表时也把键固化回 pick，


                // 使从快照/localStorage 恢复进来的旧元素同样拥有稳定身份，后续合并不再重算出新键。


                if (k && p && !p._sigKey) p._sigKey = k;


                if (k) window.__rolePickSigs[k] = true;


                var s = window.__pickSig(p);


                // 同时按 sig 与 key（__sigKey）写入映射，使 dup 分支用 key 查找时也能命中（与 __recordPick 口径一致）。
                if (s) window.__sigToPick[s] = p;
                if (k) window.__sigToPick[k] = p;


              });


              // 兜底压缩：window.__rolePicks 一旦因某次"sigs 清空后、异步合并前"的竞态残留重复项，


              // 会永久累积（重建签名表只会补键、不会删数组里的副本）。这里读取前按权威键 __mergeKey


              // 再做一次整组去重，使数组不再随时间成倍膨胀，localStorage 落盘与 pageshow 恢复也就不会越滚越大。


              (function(){


                if (typeof window.__mergeKey !== 'function') { window.__mergeKey = function(p){ try{


                  if (!p) return ''; if (p._sigKey) return p._sigKey;


                  if (typeof window.__sigKey === 'function') return window.__sigKey(p);


                  var pk = p._pageClass || ''; if (!pk) { try { pk = (location.origin||'') + (location.pathname||''); } catch(e){} }


                  return JSON.stringify([p._sig || '', pk]);


                }catch(e){ return ''; } }; }


                var seen = {}; var out = [];


                (window.__rolePicks||[]).forEach(function(p){ try{ var k = window.__mergeKey(p);


                  if (!k) { out.push(p); return; } if (seen[k]) return; seen[k]=true; out.push(p);


                }catch(e){ out.push(p); } });


                window.__rolePicks = out;


              })();


              // 一次性注册输入监听：点击可输入元素后键入的内容回写到对应 pick 的 value，


              // 使生成的 step 带上真实文本（对齐 page.pause() 的 fill 录制）。


              if (!window.__roleInputHooked) {


                window.__roleInputHooked = true;


                document.addEventListener('input', function(ev) {


                  var p = window.__activeInputPick, elc = window.__activeInputEl;


                  if (p && ev.target && ev.target === elc) {


                    p.value = editableValue(ev.target);


                    var s = document.getElementById('__roleStatus');


                    if (s) {


                      var base = s.textContent.replace(/（已输入：.*）/g, '');


                      s.textContent = base + (p.value ? '（已输入：' + p.value + '）' : '');


                    }


                    // 【关键修复"fill 未跟随输入"（尤其 iframe 内输入框）】


                    // iframe 内点击输入框时，__activeInputPick 是 iframe 上下文内的 pick 对象；用户随后输入，


                    // 此处只更新了 iframe 内 p.value，但顶层 __steps / Java 内存态里的拷贝仍是点击时的空值，


                    // 生成 fill("") 而非真实文本。


                    // 修复：输入变化的瞬间，把携带新 value 的 pick 立即重传 Java（__roleOnPick 幂等覆盖）


                    // 并 postMessage 上送顶层（顶层按 _sigKey 去重、覆盖 value），确保 stop 生成拿到真实输入。


                    try {


                      if (typeof window.__sigKey === 'function') p._sigKey = window.__sigKey(p);


                      if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(p));


                      try { console.log('__roleOnPick::' + JSON.stringify(p)); } catch (_) {}


                      if (window.self !== window.top) {


                        try { if (window.top && window.top !== window.self) window.top.postMessage({ __rolePickMsg: true, pick: p, __fromFrame: true }, '*'); } catch (e) {}


                      }


                    } catch (_e) {}


                  }


                }, true);


              }


              // 实时悬停高亮 + 悬停拾取（hover）模式：鼠标移到元素上即时描边，开启"悬停拾取"后


              // 停留在元素上约 0.45s 即记录为 hover 动作（生成 .hover()），与点击拾取（click）互补。


              var __hoverBox = null;


              function __ensureHoverBox() {


                if (__hoverBox) return __hoverBox;


                __hoverBox = document.createElement('div');


                __hoverBox.id = '__roleHoverBox';


                __hoverBox.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;'


                  + 'border:2px solid #29b6f6;background:rgba(41,182,246,0.12);'


                  + 'box-shadow:0 0 0 1px rgba(0,0,0,.35);border-radius:2px;display:none;';


                (document.body || document.documentElement).appendChild(__hoverBox);


                return __hoverBox;


              }


              function __showHoverBox(el) {


                var b = __ensureHoverBox();


                if (!el || !el.getBoundingClientRect) { b.style.display = 'none'; return; }


                var r = el.getBoundingClientRect();


                if (!r.width && !r.height) { b.style.display = 'none'; return; }


                b.style.display = 'block';


                b.style.left = r.left + 'px'; b.style.top = r.top + 'px';


                b.style.width = r.width + 'px'; b.style.height = r.height + 'px';


              }


              var __hoverTimer = null, __hoverTarget = null;


              // 纯数据投影：回传 Java 前把 pick 对象投影成仅含可序列化字段的普通对象。
              // 浏览器侧 pick 可能在某些路径被挂上 DOM 引用 / 循环引用（如 editable 分支、hover 残留），
              // 直接 JSON.stringify(pick) 会抛 "Converting circular structure" 被 catch 吞掉 → 回传静默丢失，
              // 表现为「重复点击序号不累加」。投影后序列化必定成功，杜绝该隐患。
              function __pickToWire(p) {
                if (!p) return p;
                var w = {};
                // 【关键修复"非 role 策略元素丢失（i18n/text 等）"】
                // 原字段列表缺少 'strategy'，导致 i18n/text 等策略的元素回传 Java 时不带 strategy 字段，
                // Java 侧 parsePick 默认当作 role 策略，但这类元素没有 role 字段 → parsePick 返回 null → 元素被丢弃。
                // 修复：在字段列表中添加 'strategy'，确保所有策略类型的元素都能被正确识别。
                var f = ['_sig','_sigKey','_pageClass','_pickNos','_pickSeq','_frameUrl','dialog','popup',
                         'strategy','role','name','key','text','css','id','xpath','tagName','type','value','href','src',
                         'optionText','optionValue','select','label','cleaned','level','nlsKey'];
                for (var i = 0; i < f.length; i++) { var k = f[i]; if (p[k] !== undefined) w[k] = p[k]; }
                try { console.log('[roleMouseDiag][toWire] sigKey=' + (p._sigKey||'') + ' strategy=' + (p.strategy||'') + ' pickNos=' + JSON.stringify((typeof w._pickNos!=='undefined'?w._pickNos:'UNDEFINED'))); } catch(_){}
                return w;
              }


              // 共享拾取逻辑：isHover=true 记录为 hover 动作，否则 click 动作；多页面标签与历史 click 行为一致。


              window.__recordPick = function(target, isHover) {


                // 性能/正确性：仅拾取激活态才记录（点击/悬停），避免 stop 后残留监听或导航瞬间


                // 跑完整 role/name 计算并把元素误回传 Java；非激活态直接返回。


                if (!window.__rolePickActive) return null;

                // 【修复"跨域跳转后新页元素点击一次却产生两个连续 index"（如 [4,5]/[6,7]）】
                // 根因：跨域导航触发 onFrameNavigated 多次 + start() 重注入，导致 document 上的
                // click 监听被重复挂载（__rolePickClick 被重新赋值、两次引用不同，单次物理点击
                // 被多个监听器各派发一次）→ __recordPick 对同一次点击被调用多次，每调用一次
                // __rolePickSeq+1 并追加一个序号，从而产生 [4,5] 这类连续双号（旧实现仅续接计数器
                // 保证不归零撞号，未消除重复触发本身）。
                // 此处对"同一目标元素在极短时间窗口内的重复进入"做防重入去抖：人类两次点击同一
                // 元素间隔远大于 DEBOUNCE_MS，不误伤；仅拦截同一次物理点击的多监听器重复派发。
                try {
                  var __now = Date.now();
                  if (target && target === window.__lastPickDedupEl
                      && (__now - (window.__lastPickDedupTs || 0)) < 120) {
                    return null;
                  }
                  window.__lastPickDedupEl = target;
                  window.__lastPickDedupTs = __now;
                } catch (e) {}


                // 【关键修复"区域选择模式下点击不触发拾取"】


                // 区域扫描态（__scanMode==='region'，用户正在点选业务区域）下，点击的语义是"选中区域并


                // 扫描其子元素"，而非"拾取被点击的元素"。若点击拾取监听未被完全摘除，点击 #sec-role 等


                // 容器会被 __recordPick 记录成整块 text 定位（"1. 角色与状态 提交 禁用按钮..."），混入


                // 页面元素/页面类。此处：区域选择态且【非扫描中】时点击直接不记录——仅当 __roleScanPage


                // 扫描时（__scanMode 被覆写为 'page' 且 __scanning=true）才记录区域内子元素。


                if (window.__scanMode === 'region' && !window.__scanning) return null;


                var t = target;


                if (!t) return null;


                // 录制根容器约束：若指定了 window.__rolePickRoot，只有落在该选择器内（含其自身）


                // 的元素才被录制；leftmenu / topbar 等全局导航区域在范围外，自然不被捕获。


                // 对整页 scan 与点击/悬停/双击录制统一生效。选择器无效（querySelector 返回 null）


                // 时退化成整页录制，避免误杀全部拾取。


                if (window.__rolePickRoot) {


                  try {


                    var __rootEl = document.querySelector(window.__rolePickRoot);


                    if (__rootEl && !(t === __rootEl || __rootEl.contains(t))) {


                      // 根外点击（多为 leftmenu/topbar 等全局导航）：面板状态条临时提示，2 秒后恢复计数。


                      try {


                        var __st = document.getElementById('__roleStatus');


                        if (__st) {


                          __st.textContent = '⚠ 点击在录制根容器（' + window.__rolePickRoot + '）之外，已忽略（导航区不录制）';


                          if (window.__roleStatusTimer) clearTimeout(window.__roleStatusTimer);


                          window.__roleStatusTimer = setTimeout(function() {


                            if (__st) __st.textContent = 'RoleElement Picker：已拾取 '


                              + (window.__rolePicks ? window.__rolePicks.length : 0) + ' 个，按 ESC 结束';


                          }, 2000);


                        }


                      } catch (e2) { /* 面板不存在时忽略 */ }


                      return null;


                    }


                  } catch (e) { /* 选择器非法时忽略约束 */ }


                }


                var pick = window.__computePick(t);


                // 剔除"整页级骨架 css 定位"：css 选择器以 body / html 开头（cssPathOf 在 5 层内遇不到


                // stable id，只能生成 "body > div:nth-of-type(...)" / "html > body > ..." 这种整页级兜底


                // 路径，无业务价值、随 DOM 微调即失效）。无论手动拾取还是扫描态都拦截——这类选择器本就不该


                // 进入拾取集（有语义角色/稳定 id 的元素不会落到这里），从源头保证面板列表与生成的页面类都不含它。


                // 补充：无锚点纯位置链 css（div:nth-of-type(3) > div > ...）只在**区域扫描态**拦截。


                // 区域扫描无差别遍历区域内全部元素，是这类噪音的唯一来源；手动点选保持 pause 语义，


                // 用户主动点的元素即便只能退化到 css 兜底也照常记录。


                // 区域态判定用 __roleScanRoot 而非 __scanMode：__roleScanPage 入口会把 __scanMode


                // 无条件覆写为 'page'（区域/整页共用该函数），此处读不到 'region'；而 __roleScanRoot


                // 仅在区域扫描期间被赋为区域根元素，整页扫描与非扫描态均为 null，是可靠的区域态标志。


                if (pick && pick.strategy === 'css' && pick.css


                    && (window.__roleScanRoot


                        // 仅"区域扫描态"拦截整页级 / 纯位置链 css 噪音；手动点选与整页扫描态放行——


                        // 用户主动点的元素即便只能退化到 css 兜底也照常记录（对齐上方注释 pause 语义），


                        // 避免"点展开后的说明文案却无回传"这类误杀。


                        ? (pick.css.indexOf('body') !== -1 || pick.css.indexOf('html') !== -1


                           || (typeof __isNthOnlyCss === 'function' && __isNthOnlyCss(pick.css)))


                        : false)) {


                  try { console.log('[rolePick][skip body/html/nth-css] css=' + pick.css); } catch (e2) {}


                  return null;


                }


                pick._pageClass = window.__rolePageName || '';


                // 记录拾取发生时所在 frame 的 URL（主框架 _pageClass 已用类名，无需；iframe 内 _pageClass


                // 为空，用 _frameUrl 供 Java console 通道回补 framePath 时按 URL 匹配 page.frames() 定位 frame）。


                // origin+pathname 与 __sigKey 兜底口径一致，去 query/hash 保持跨导航稳定；不影响 _pageClass 语义，


                // 故 iframe 元素仍归属主页面类（按 framePath 切换），不会误分到独立 iframe 页面类。


                try { pick._frameUrl = (location.origin || '') + (location.pathname || ''); } catch (e) { pick._frameUrl = ''; }


                // 页面实例序号：同一 pageClass 被打开多次（同页多标签）时区分不同实例。


                // 维护 window.__pageInstanceSeq 映射（pageClass -> 已打开实例数）；每次进入/打开一个页面


                // （__rolePageName 被赋值，见下方各种 onPopup/onLoad 钩子）时该映射 +1 并固化为该页实例号。


                // 同一页面内的多次拾取共享同一实例号（不重复 +1）。


                try {


                  window.__pageInstanceSeq = window.__pageInstanceSeq || {};


                  var __pc = pick._pageClass || '__main';


                  if (window.__currentPageInstance == null || window.__currentPageInstance.page !== __pc) {


                    window.__pageInstanceSeq[__pc] = (window.__pageInstanceSeq[__pc] || 0) + 1;


                    window.__currentPageInstance = { page: __pc, seq: window.__pageInstanceSeq[__pc] };


                  }


                  pick._pageInstanceId = window.__currentPageInstance.seq;


                } catch (e) { pick._pageInstanceId = 1; }


                try { pick._sig = window.__pickSig ? window.__pickSig(pick) : null; } catch (e) { pick._sig = null; }


                if (window.__lastPickEl && isEditable(window.__lastPickEl)) {


                  pick.value = editableValue(window.__lastPickEl);


                  window.__activeInputPick = pick;


                  window.__activeInputEl = window.__lastPickEl;


                } else {


                  window.__activeInputPick = null;


                  window.__activeInputEl = null;


                }


                pick.hover = !!isHover;


                // 对齐 page.pause() 的 source.dragTo(target)：拖拽手势（mousedown 源 → mouseup 目标）在 mouseup


                // 时触发本函数记录「源」元素；此时 window.__dragSrcEl/__dragDstKey 已由拖拽监听置好，


                // 把目标元素的定位签名挂到源 pick 上，生成端据 keyToField 反查目标字段并输出 dragTo。


                if (window.__dragSrcEl && el === window.__dragSrcEl && window.__dragDstKey) {


                  pick.dragDstKey = window.__dragDstKey;


                }


                // —— 下拉选择 / 复选框 状态捕获（对齐 page.pause() 的 selectOption 与 check/uncheck 信号）——


                // combobox/listbox（含原生 <select> 与自定义列表）：拾取时读取当前选中项，


                // 生成 step 时输出 selectByVisibleText("选项文本")（优先）/ selectByValue(...)。


                var pRole = (pick.role || '').toLowerCase();


                if (pRole === 'combobox' || pRole === 'listbox') {


                  var sel = window.__lastPickEl;


                  if (sel) {


                    try {


                      var optText = null, optVal = null;


                      if (sel.tagName === 'SELECT') {


                        var o = sel.selectedOptions && sel.selectedOptions.length ? sel.selectedOptions[0] : null;


                        if (o) { optText = (o.textContent || '').trim(); optVal = o.value; }


                      } else {


                        var selEl = sel.querySelector('[aria-selected="true"]');


                        if (selEl) optText = (selEl.getAttribute('aria-label') || selEl.textContent || '').trim();


                      }


                      if (optText != null) {


                        pick.select = true;


                        pick.optionText = optText;


                        pick.optionValue = optVal;


                      }


                    } catch (e) {}


                    // change 事件触发时（用户在下拉中选了某一项）更新选中态并回传，对齐 codegen 的 selectOption 信号。


                    try {


                      if (!sel.__pwSelBound) {


                        sel.__pwSelBound = true;


                        sel.addEventListener('change', function() {


                          try {


                            var o = sel.selectedOptions && sel.selectedOptions.length ? sel.selectedOptions[0] : null;


                            if (!o) return;


                            pick.select = true;


                            pick.optionText = (o.textContent || '').trim();


                            pick.optionValue = o.value;


                            if (typeof window.__sigKey === 'function') pick._sigKey = window.__sigKey(pick);


                            if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(__pickToWire(pick)));


                            try { console.log('__roleOnPick::' + JSON.stringify(__pickToWire(pick))); } catch (_) {}


                          } catch (e) {}


                        }, true);


                      }


                    } catch (e) {}


                  }


                }


                // checkbox：记录当前勾选状态，生成 step 时按已勾选走 check()、未勾选走 uncheck()（对齐 codegen）。


                // 修复：原 var cb = pick._el 中 pick._el 从未被赋值（真实点击元素挂在 el 上，


                // 其副本即 window.__lastPickEl），导致原生 checkbox/radio 的 checked 始终为 undefined。


                // 改用点击元素的真实引用 el；并补充 ARIA checkbox/switch 的 aria-checked 采集。


                try {


                  if (pRole === 'checkbox' || pRole === 'radio' || pRole === 'switch'


                      || pRole === 'menuitemcheckbox' || pRole === 'menuitemradio' || pRole === 'treeitem') {


                    var cb = el;


                    if (cb.type === 'checkbox' || cb.type === 'radio') {


                      pick.checked = !!cb.checked;


                    } else if (cb.hasAttribute('aria-checked')) {


                      var _av = cb.getAttribute('aria-checked');


                      pick.checked = (_av === 'true' || _av === 'mixed');


                    }


                  }


                } catch (e) {}


                var sig = window.__pickSig(pick);


                var key = window.__sigKey(pick);


                var dup = key && window.__rolePickSigs[key];

                // 【index 需求】每次拾取动作（点击/悬停，含重复点同一元素）都分配一个全局递增序号。
                // 语义：从开始拾取到停止，index 连续 1,2,3,...；同一元素被点多次时，其 _pickNos 追加当前号
                // （如 [1,5]），面板前缀据此显示、step 封装按首号（_pickNos[0]）排序 == 用户首次点它的顺序。
                // 计数器在拾取会话内连续；重新 startPicker 时由 Java 重置（window.__rolePickSeq=0）。
                // 【修复"跨域新页直接产生两个 index 又变正常"】跨域导航强制 start() 会把 __rolePickSeq 归 0，
                // 而该导航往往触发多次 onFrameNavigated（main/iframe/about:blank 过渡），与"同步激活+renderPicks"
                // 存在竞态，导致新页首个元素被 recordPick 多触发一次：若计数器硬归 0，则会从 1 连续 +1 两次 → [1,2]。
                // 此处改为【续接式】：计数器只增不回退——取 max(当前 seq, 已恢复 picks 的最大 _pickNos) 作为基线，
                // 再 +1。即使 recordPick 被竞态多调一次，新号也只会 > 已用最大号（全局连续递增语义不变），
                // 且不会回退到 1 与旧页元素（如 LogonPage 的 1,2,3,4）撞号。单页内稳定后行为完全一致。
                if (typeof window.__rolePickSeq !== 'number' || window.__rolePickSeq < 0) window.__rolePickSeq = 0;
                // 【修复"后拾取元素首号偏小（如第二个元素拿到 2 而非 4）"】
                // 旧实现每次点击都遍历 window.__rolePicks 求最大 _pickNos 来续接计数器；但 __rolePicks
                // 是【被 Java syncPanelToBrowser 周期性整体重建】的——重建时以 Java 权威态为准，而 Java 态
                // 在并发回传竞态下可能只持有短值（如 user_name 仅 [2]），于是重建后 __rolePicks 里同元素的
                // _pickNos 退化成短值，下一轮 max 计算被拉低 → 新元素首号从偏小值续接（label 拿到 2 而非 4）。
                // 修复：引入一个【独立、只增不回退】的 window.__roleMaxNo 记录"已分配出去的最大动作号"，
                // 计数器续接只依赖它（而非会被重建污染的 __rolePicks）。__rolePicks 的 max 仅作为额外兜底
                // （当它 > __roleMaxNo 时才采用，兼容跨页恢复 picks 但 __roleMaxNo 未恢复的场景）。
                if (typeof window.__roleMaxNo !== 'number' || window.__roleMaxNo < 0) window.__roleMaxNo = 0;
                var __maxNo = window.__roleMaxNo; // 基线：已分配最大号（只增不回退）
                try {
                  var __allP = window.__rolePicks || [];
                  for (var __mi = 0; __mi < __allP.length; __mi++) {
                    var __pn = __allP[__mi] && __allP[__mi]._pickNos;
                    if (Array.isArray(__pn)) {
                      for (var __nj = 0; __nj < __pn.length; __nj++) {
                        var __v = __pn[__nj];
                        if (typeof __v === 'number' && __v > __maxNo) __maxNo = __v;
                      }
                    }
                  }
                } catch (e) {}
                // 续接：取【已分配最大号基线】与【__rolePicks 当前最大号（兜底）】的较大者，只增不回退。
                if (__maxNo > window.__rolePickSeq) window.__rolePickSeq = __maxNo;
                // 【诊断】记录本次点击前计数器与续接基线，定位"新元素首号偏小"是否因 max 被拉低。
                try { console.log('[roleMouseDiag][seq-base] seq=' + window.__rolePickSeq + ' maxNo=' + __maxNo + ' roleMaxNo=' + window.__roleMaxNo); } catch(_){}
                // 【修复"整页/区域扫描的候选带累加编号、扫一次增加一次"】
                // 扫描态（window.__scanning）下收集的是「候选清单」，不是「按点击顺序的步骤动作」，
                // 不应占用手动拾取的全局序号序列（window.__rolePickSeq），否则候选带 [n] 且每次重扫
                // 会在已有号之上续接 → 表现为"扫一次编号增加一次"、污染手动拾取序号。
                // 故扫描态跳过序号分配：候选保持 _pickNos 空（面板显示 [-]），待用户在面板勾选时再按
                // 勾选顺序赋予步骤序号（见 panel-core-b.js __packageStep / 勾选逻辑）。
                if (!window.__scanning) {
                  window.__rolePickSeq += 1;
                  var __thisIndex = window.__rolePickSeq;
                  // 每次真正分配出新号，更新"只增不回退"的最大号基线（供后续续接，不被 __rolePicks 重建干扰）。
                  if (window.__rolePickSeq > window.__roleMaxNo) window.__roleMaxNo = window.__rolePickSeq;
                } else {
                  var __thisIndex = 0; // 扫描态不分配动作序号（仅占位，__appendPickNo 调用处已守卫）
                }
                // 【修复"后拾取元素首号偏小（如第二个元素拿到 2 而非 4）/ step 排序错乱"】
                // 旧实现 p._pickSeq = p._pickNos[0]：_pickSeq 取的是"拾取号数组首元素"。但 _pickNos 是
                // 被 Java 每轮 syncPanelToBrowser 整体重建的——并发回传竞态下 Java 权威态可能只持有短值
                // （如 user_name 仅 [2]），重建后浏览器侧 _pickNos 退化成短值，于是 _pickSeq 跟着变成 2，
                // 再也回不到真实的首次动作号 4。Java 端按 _pickSeq 排序，遂表现为"后点元素排到前面/序号错乱"。
                // 修复：引入【独立、只增不回退】的 window.__pickOrder（sigKey→首次全局动作号），
                // _pickSeq 直接取该 order，不再依赖会被重建污染的 _pickNos[0]。order 一旦记下永不改变，
                // 即使 _pickNos 被 Java 重建拉短，step 排序基准仍稳定 = 用户首次点它的真实顺序。
                if (typeof window.__pickOrder !== 'object' || window.__pickOrder === null) window.__pickOrder = {};
                function __appendPickNo(p) {
                  if (!p) return;
                  if (!Array.isArray(p._pickNos)) p._pickNos = [];
                  // 去重保序：同一动作号不会重复追加（理论上每次动作号唯一，仍防御性去重）。
                  // 【修复"序号追加顺序混乱"】直接 push 会导致 pickNos 顺序混乱（如 [5,2,6]）。
                  // 改为按升序插入，保持 pickNos 始终有序，便于后续重编号和步骤生成。
                  if (p._pickNos.indexOf(__thisIndex) === -1) {
                    p._pickNos.push(__thisIndex);
                    p._pickNos.sort(function(a, b) { return a - b; });  // 升序排序
                  }
                  // 首次动作号：用稳定 order 映射（按 sigKey 固化），只记不回退；dup 重拾不覆盖首号。
                  var __ordKey = (p._sigKey || window.__pickSig(p) || '');
                  if (__ordKey && typeof window.__pickOrder[__ordKey] !== 'number') {
                    window.__pickOrder[__ordKey] = __thisIndex;
                  }
                  // 兼容旧的单值 seq 字段（首次动作号，取 order 而非 _pickNos[0]）。
                  if (__ordKey && typeof window.__pickOrder[__ordKey] === 'number') {
                    p._pickSeq = window.__pickOrder[__ordKey];
                  } else if (typeof p._pickSeq !== 'number' || __thisIndex < p._pickSeq) {
                    p._pickSeq = __thisIndex;
                  }
                  // 本轮已为该元素分配序号（新拾取或重拾已存在元素）：清除 _seqStale，使其从 [-] 变为显示本轮编号。
                  try { p._seqStale = false; } catch (e) {}
                  // 标记为手动拾取元素，取消勾选后 checkbox 仍保持禁用
                  try { p._manualPick = true; } catch (e) {}
                }

                if (!dup) {


                  if (key) window.__rolePickSigs[key] = true;


                  // 【关键】入库瞬间把去重键固化到 pick 上，使其成为该元素的永久身份。


                  // 否则 pick 进入 __rolePicks 时不带 _sigKey，后续任何一次合并（load/pageshow 自愈、


                  // 导航恢复、localStorage 回灌）都会在【新页面上下文】里重算键：此时 _pageClass 可能


                  // 尚未派生而退化到 location 兜底，算出的键与登记在 __rolePickSigs 里的旧键不等


                  // → 判为新元素再次 push，每轮导航多一份（实测 4→5→6 次）。固化后键恒定，重复根除。


                  if (key) pick._sigKey = key;


                  window.__rolePicks.push(pick);
                  // 【index 需求】新元素首次被拾取：把当前动作序号写入 _pickNos（首号）。
                  // 扫描态守卫：候选不分配序号（保持 [-]）。
                  if (!window.__scanning) __appendPickNo(pick);

                  // 【diag-first】首次 push 分支：序列化确认首次拾取的 _pickNos 是否随对象带出，并打印 strategy。
                  try {
                    var __wire0 = __pickToWire(pick);
                    console.log('[roleMouseDiag][first-send] key=' + (pick._sigKey||'') + ' strategy=' + (pick.strategy||'') + ' pickNos=' + JSON.stringify((typeof __wire0._pickNos!=='undefined'?__wire0._pickNos:'UNDEFINED')));
                  } catch(_){}

                  // [issue3] auto-focus the page sub-tab that the picked element belongs to
                  // ("locate which page -> focus that page"). Only triggers when pick carries
                  // _pageClass (multi-page scene) and differs from current active page, to avoid
                  // needless re-render on single-page scene.
                  try {
                    if (pick && pick._pageClass) {
                      if (window.__roleActivePageClass !== pick._pageClass) {
                        window.__roleActivePageClass = pick._pageClass;
                      }
                      if (typeof window.__renderPicks === 'function') window.__renderPicks();
                    }
                  } catch (e) { /* panel not ready: ignore */ }


                  // 扫描态下抑制逐元素控制台日志：避免 N 次 console 事件触发 Java onConsoleMessage 监听器空转。


                  if (!window.__scanning) {


                    try { console.log('[rolePick][push] len=' + window.__rolePicks.length + ' render=' + (typeof window.__renderPicks)); } catch(_){}


                  }


                  // sig→pick / key→pick 双映射，重复点击时 O(1) 定位原 pick（避免线性扫描 __rolePicks）。
                  // 同时写入 __sigKey（与 dup 判定口径一致），确保 dup 分支用 key 查找时必能命中。


                  if (sig) window.__sigToPick[sig] = pick;
                  if (key) window.__sigToPick[key] = pick;


                } else {


                  if (!window.__scanning) {


                    try { console.log('[roleMouseDiag][diag-dup] dup=' + dup + ' key=' + key + ' sig=' + sig + ' hasExisting=' + (!!(existing || (key && window.__sigToPick[key]) || (sig && window.__sigToPick[sig])))); } catch(_){}


                  }


                  // O(1) 去重定位：优先用与 dup 判定口径一致的 __sigKey（key）取已存在 pick，
                  // 回退到 __pickSig（sig）；再不行遍历权威数组 window.__rolePicks（sync 重建后的最新对象）
                  // 按 sig/key 匹配——三重兜底，确保无论 __sigToPick 映射因任何原因丢失，dup 回传都不丢。


                  // 优先从权威数组 window.__rolePicks（Java 每轮 syncPanelToBrowser 重建后的最新对象）按
                  // _sigKey/_sig 匹配；再回退 __sigToPick 映射。原因：syncPanelToBrowser 重建 __rolePicks 时
                  // 不会刷新 __sigToPick，导致 __sigToPick 指向已被抛弃的旧 pick 对象引用；若旧引用因任何原因
                  // 失效（undef），优先扫权威数组可拿到 Java 重建的最新 pick，保证 dup 回传命中且不丢序号。
                  var existing = null;
                  if (window.__rolePicks) {
                    for (var __di = 0; __di < window.__rolePicks.length; __di++) {
                      var __dp = window.__rolePicks[__di];
                      if (__dp && ((key && __dp._sigKey === key) || (sig && window.__pickSig(__dp) === sig))) {
                        existing = __dp; break;
                      }
                    }
                  }
                  if (!existing) {
                    existing = (key && window.__sigToPick[key]) ? window.__sigToPick[key]
                              : (sig ? window.__sigToPick[sig] : null);
                  }
                  // 【诊断】dup 分支执行完查找后，若 existing 仍为空，说明 __sigToPick 与 __rolePicks 都查不到该 key/sig：
                  // 通常是 syncPanelToBrowser 重建 __rolePicks 后未同步刷新 __sigToPick 映射，或 key/sig 口径漂移。
                  if (!existing) {
                    try {
                      console.log('[roleMouseDiag][dup-miss] key=' + key + ' sig=' + sig
                        + ' sigToPickHasKey=' + !!(key && window.__sigToPick[key])
                        + ' sigToPickHasSig=' + !!(sig && window.__sigToPick[sig])
                        + ' rolePicksLen=' + (window.__rolePicks ? window.__rolePicks.length : -1));
                    } catch(_){}
                  }


                  if (existing) {
                    existing.hover = !!isHover;
                    // 【index 需求】重复拾取同一元素：把当前动作序号追加到 _pickNos（如 [1,5]）。
                    // 扫描态守卫：候选不分配序号。
                    if (!window.__scanning) __appendPickNo(existing);
                    // 【诊断】dup 命中分支：打印 existing 是否拿到、回传前 _pickNos、__roleOnPick 类型、序列化是否成功。
                    try {
                      var __wire = __pickToWire(existing);
                      var __ser = '';
                      try { __ser = JSON.stringify(__wire); } catch (se) { __ser = 'SER_FAIL:' + se.message; }
                      console.log('[roleMouseDiag][dup-send] hasExisting=true key=' + key
                        + ' strategy=' + (existing.strategy||'')
                        + ' pickNos=' + JSON.stringify(existing._pickNos)
                        + ' bindingType=' + typeof window.__roleOnPick
                        + ' serLen=' + __ser.length);
                    } catch (de) { try { console.log('[roleMouseDiag][dup-send] ERR ' + de.message); } catch(_){} }
                    // 【修复"同一元素多次点击，序号不显示在面板"】去重后外层 panel-core-a.js 不再回传
                    // __roleOnPick（__rolePickSigs[key] 已置位），导致 Java 权威内存态只持有首次的 _pickNos，
                    // 而浏览器侧 existing 已累积本次点击的全局动作号。主循环每轮 syncPanelToBrowser 用 Java 态
                    // 重建 window.__rolePicks 时，会把浏览器侧累积的 _pickNos 覆盖成 Java 的残缺值 → 面板只显示 [1]。
                    // 故对真实点击（非 hover/非扫描）主动回传最新 existing（含完整 _pickNos）给 Java，
                    // 让权威态与浏览器侧一致，重建后面板正确显示 [1,5,9…] 等完整序号。
                    if (!isHover && !window.__scanning) {
                      var __bType = typeof window.__roleOnPick;
                      var __bindOk = false;
                      if (__bType === 'function') {
                        try { window.__roleOnPick(JSON.stringify(__wire)); __bindOk = true; } catch (e) { console.log('[roleMouseDiag][dup-bind-fail] ' + (e && e.message)); }
                      }
                      // 【修复"dup 完整序号 [2,5,6,7,8] 无法回写 Java 权威态（最终只留 [2]）"】
                      // 现象：dup 回传的 BIND 调用浏览器侧看似 bindOk=true，但 Java 侧「__roleOnPick」BIND 回调
                      // 实际未处理（无 [BIND] 日志）——根因是 context 重挂后旧 binding 句柄静默失效，调用被丢弃。
                      // 后果：Java 内存态只持有首次的 [2]，而浏览器侧 accumulator 自持有完整 [2,5,6,7,8]；
                      // 主循环 syncPanelToBrowser 用 Java 短值重建浏览器 __rolePicks 时（overwrite=false 并集分支
                      // 若 __old miss）会把完整值冲掉，且 stop 生成的 step 只有 1 次 click 而非 5 次。
                      // 修复：dup 回传【始终经 console 兜底桥再发一次完整 __wire（含 _pickNos）】，
                      // console 桥基于 console.log，不受 context 重挂影响、必达 Java；Java 侧 pickMoreComplete
                      // 已保证「长集合胜出」，双发不会把完整值覆盖成短值（旧注释担心的"竞争污染"已由 pickMoreComplete 化解）。
                      try { console.log('__roleOnPick::' + JSON.stringify(__wire)); } catch (_) {}
                      console.log('[roleMouseDiag][dup-after] key=' + key + ' strategy=' + (existing.strategy||'') + ' bindingType=' + __bType + ' bindOk=' + __bindOk + ' pickNos=' + JSON.stringify((typeof __wire._pickNos!=='undefined'?__wire._pickNos:'UNDEFINED')));
                    }


                    if (window.__lastPickEl && isEditable(window.__lastPickEl)) {


                      window.__activeInputPick = existing;


                      window.__activeInputEl = window.__lastPickEl;


                    }


                  } else {


                    // 兜底：极端情况下映射缺失（如注入重建未同步），退回线性扫描保正确。


                    for (var i = 0; i < window.__rolePicks.length; i++) {


                      if (window.__pickSig(window.__rolePicks[i]) === sig) {


                        window.__rolePicks[i].hover = !!isHover;
                        // 【index 需求】兜底分支同样追加当前动作序号（扫描态守卫）。
                        if (!window.__scanning) __appendPickNo(window.__rolePicks[i]);
                        // 与上面 existing 分支一致：去重后主动回传最新 _pickNos 给 Java，避免面板重建时序号被覆盖残缺。
                        if (!isHover && !window.__scanning) {
                          var __wire2 = __pickToWire(window.__rolePicks[i]);
                          var __bindOk2 = false;
                          if (typeof window.__roleOnPick === 'function') {
                            try { window.__roleOnPick(JSON.stringify(__wire2)); __bindOk2 = true; } catch (e) {}
                          }
                          // 【关键修复：兜底分支与 existing 分支保持一致】
                          // existing 分支（lines 3085-3102）无论 bindOk 与否，都会再经 console 兜底桥
                          // 发一次完整 __wire（含 _pickNos），原因：context 重挂后旧 binding 句柄会静默失效、
                          // bindOk=true 但 Java 侧「__roleOnPick」BIND 回调实际未处理的情况已在生产被观测到。
                          // 原代码仅在 !__bindOk2 时发 console 兜底，导致兜底分支出现与 existing 分支相同的失效场景
                          // —— 浏览器侧有完整 [2,5,6,7,8] 但 Java 只持有首值 [2]。
                          // 修复：与 existing 分支完全对齐；无论 bindOk 与否，始终经 console 桥再发一次完整 __wire2。
                          // pickMoreComplete 已实现「长集合胜出 / 并集」语义，双发不会把完整值覆盖成短值。
                          try { console.log('__roleOnPick::' + JSON.stringify(__wire2)); } catch (_) {}
                        }


                        if (window.__lastPickEl && isEditable(window.__lastPickEl)) {


                          window.__activeInputPick = window.__rolePicks[i];


                          window.__activeInputEl = window.__lastPickEl;


                        }


                        break;


                      }


                    }


                  }


                }


                var __now = Date.now();


                var __rapid = sig && sig === window.__lastPickSig && (__now - (window.__lastPickTs || 0)) < 500;


                window.__lastPickSig = sig;


                window.__lastPickTs = __now;


                // 选择模型（替代原"所有拾取自动并入 currentStep = 一个 step"）：


                //   · 整页扫描期间（window.__scanning 为 true）的元素只是"候选"，不直接入选当前 step，


                //     由用户在面板上勾选后再「封装为步骤」；


                //   · 页面上的实时点选（非扫描、非重复）视为用户主动拾取，自动入选当前 step（选择集）；


                //   · 重复拾取（dup）不重复入选，避免 step 内出现重复元素。


                // 【关键修复"用户点击扫描候选不进 step"】


                // 原条件含 !dup：整页扫描会把候选 push 进 __rolePickSigs（回传 javaPickBySig），扫描后


                // 用户点击这些候选元素时 dup=true，被误判为"重复点击"而拒绝入选 step → 用户点的元素


                // 不出现在步骤里。dup 本意是防"同一元素重复入选"；应改判"该元素是否已在当前 step 中"


                // 而非"是否已收录过（扫描也算收录）"。故：非扫描态点击，只要元素不在当前 __currentStep


                // 就入选 step（用户主动点击扫描候选 = 明确选择）；已在 step 中的重复点击仍被排除。


                if (window.__currentStep && !window.__scanning && !__rapid) {


                  var __inStep = false;


                  if (dup) {


                    // 仅当元素已在 __rolePickSigs 中才需查 __currentStep 去重；全新元素直接入选。


                    try {


                      for (var __si = 0; __si < window.__currentStep.length; __si++) {


                        if (window.__currentStep[__si]


                            && ((sig && window.__currentStep[__si]._sig === sig)


                                || (key && window.__currentStep[__si]._sigKey === key))) {


                          __inStep = true; break;


                        }


                      }


                    } catch (e) { __inStep = false; }


                  }


                  if (!__inStep) window.__currentStep.push(pick);


                }


                // 修复：__renumberStep / __currentStep 等 UI 辅助函数仅定义在主框架（PANEL_SCRIPT）内。


                // iframe 内拾取时本函数在子 frame 上下文执行，这些函数未定义，直接调用会抛 TypeError 中断后续


                // 回传逻辑（__roleOnPick 绑定回传、__roleOnPick:: 控制台兜底、postMessage 上送），导致


                // 「frame 元素拾取成功（已 push 进子 frame 数组）但 Java 内存态/面板均收不到」的假象。


                // 故全部加 typeof 守卫 + try，确保子 frame 内即便 UI 辅助缺失也不影响元素回传。


                try { if (typeof window.__renumberStep === 'function') window.__renumberStep(); } catch (_) {}


