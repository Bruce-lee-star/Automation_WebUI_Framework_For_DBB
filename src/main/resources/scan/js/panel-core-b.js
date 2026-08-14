
              var classContent = document.createElement('div');
              classContent.style.cssText = 'flex:1;display:none;min-height:0;flex-direction:column;';
              var classSubBar = document.createElement('div');
              classSubBar.id = '__roleClassSubTabBar';
              classSubBar.style.cssText = 'display:flex;gap:0;overflow-x:auto;background:#1b1b1b;border-bottom:1px solid #111;flex:0 0 auto;';
              var classAreas = document.createElement('div');
              classAreas.id = '__roleClassAreas';
              classAreas.style.cssText = 'flex:1;min-height:0;display:flex;flex-direction:column;overflow:hidden;';
              classContent.appendChild(classSubBar);
              classContent.appendChild(classAreas);

              var stepContent = document.createElement('div');
              stepContent.style.cssText = 'flex:1;display:none;min-height:0;flex-direction:column;';
              var stepSubBar = document.createElement('div');
              stepSubBar.id = '__roleStepSubTabBar';
              stepSubBar.style.cssText = 'display:flex;gap:0;overflow-x:auto;background:#1b1b1b;border-bottom:1px solid #111;flex:0 0 auto;';
              var stepAreas = document.createElement('div');
              stepAreas.id = '__roleStepAreas';
              stepAreas.style.cssText = 'flex:1;min-height:0;display:flex;flex-direction:column;overflow:hidden;';
              stepContent.appendChild(stepSubBar);
              stepContent.appendChild(stepAreas);

              function showTab() {
                var t = window.__roleActiveTab;
                pageContent.style.display = (t === 'page') ? 'flex' : 'none';
                classContent.style.display = (t === 'class') ? 'flex' : 'none';
                stepContent.style.display = (t === 'step') ? 'flex' : 'none';
                // 【修复"Tab 聚焦背景/状态文字颜色不生效"】
                // 旧实现这里用硬编码 #1e1e1e/#2d2d2d 覆盖 mkTab 初始样式，导致聚焦 Tab 永远是深灰、
                // 与未聚焦差异极小；且每次切 Tab 都把状态文字强制设为绿色 #43a047，在蓝色 header 上几乎不可读
                // （用户反馈的"深绿色、看不清"正来源于此，而非最初 mkTab 的继承问题）。
                // 统一改为与 mkTab 一致：聚焦 Tab 蓝色背景 #1565c0 + 粗体 + 3px 亮蓝下边框；状态文字白色。
                tabPage.style.background = (t === 'page') ? '#1565c0' : '#2d2d2d';
                tabPage.style.fontWeight = (t === 'page') ? 'bold' : 'normal';
                tabPage.style.borderBottom = (t === 'page') ? '3px solid #42a5f5' : '3px solid transparent';
                tabClass.style.background = (t === 'class') ? '#1565c0' : '#2d2d2d';
                tabClass.style.fontWeight = (t === 'class') ? 'bold' : 'normal';
                tabClass.style.borderBottom = (t === 'class') ? '3px solid #42a5f5' : '3px solid transparent';
                tabStep.style.background = (t === 'step') ? '#1565c0' : '#2d2d2d';
                tabStep.style.fontWeight = (t === 'step') ? 'bold' : 'normal';
                tabStep.style.borderBottom = (t === 'step') ? '3px solid #42a5f5' : '3px solid transparent';
                // 切换 Tab 时复位「复制」按钮状态（避免上一轮"已复制"残留提示误导用户）。
                // 注意：ICON.copy 是内联 SVG 字符串，必须用 innerHTML 注入才能渲染成图标；
                // 若误用 textContent，SVG 标签会被当成纯文本显示，复制按钮变成一个 XML 字符串（图标"不显示/显示异常"）。
                if (copyBtn) { copyBtn.title = '复制'; copyBtn.innerHTML = ICON.copy; }
                if (status) { status.textContent = '就绪'; status.style.color = '#fff'; status.style.fontWeight = 'bold'; }
              }
              // 暴露给 Java 侧调用（扫描完成后自动切到"页面类"Tab，让用户即时看到生成的页面类代码）。
              window.__roleShowTab = showTab;

              // 按 pageClass 把页面类 / 步骤代码分栏渲染（对齐"页面元素"Tab 的子 Tab 布局）：
              // 每个 pageClass 一个子 Tab，点击切换显示对应代码文本框；支持按页对照查看与复制。
              window.__fillCodeTabs = function(obj) {
                if (!obj) return;
                var pageMap = obj.pageByPage || null;
                var stepMap = obj.stepByPage || null;
                if (!pageMap && obj.page != null) pageMap = { '__merged__': obj.page };
                if (!stepMap && obj.step != null) stepMap = { '__merged__': obj.step };
                pageMap = pageMap || {};
                stepMap = stepMap || {};
                __renderCodeTabs('__roleClassSubTabBar', '__roleClassAreas', pageMap, '__roleCodeArea');
                __renderCodeTabs('__roleStepSubTabBar', '__roleStepAreas', stepMap, '__roleCodeArea2');
                var st = document.getElementById('__roleStatus'); if (st) st.textContent = obj.msg || '';
                try { localStorage.setItem('__rolePickerCode',
                  JSON.stringify({ pageByPage: pageMap, stepByPage: stepMap, msg: obj.msg || '' })); } catch (e) {}
              };
              function __renderCodeTabs(barId, areasId, map, taPrefix) {
                var bar = document.getElementById(barId);
                var areas = document.getElementById(areasId);
                if (!bar || !areas) return;
                var keys = Object.keys(map);
                bar.innerHTML = '';
                areas.innerHTML = '';
                if (!keys.length) {
                  var empty = document.createElement('div');
                  empty.style.cssText = 'flex:1;padding:12px;color:#888;font:13px/1.5 sans-serif;';
                  empty.textContent = '（暂无生成）';
                  areas.appendChild(empty);
                  window[barId + '_active'] = null;
                  return;
                }
                var actKey = window[barId + '_active'];
                if (!actKey || keys.indexOf(actKey) < 0) actKey = keys[0];
                window[barId + '_active'] = actKey;
                function mkSub(label, key) {
                  var b = document.createElement('button');
                  b.type = 'button';
                  b.textContent = label;
                  b.style.cssText = 'flex:0 0 auto;padding:6px 12px;border:0;cursor:pointer;font:12px/1.4 sans-serif;' +
                    'color:#ccc;background:' + (actKey === key ? '#1e88e5' : '#2d2d2d') + ';' +
                    'border-bottom:2px solid ' + (actKey === key ? '#1e88e5' : 'transparent') + ';';
                  b.onclick = function() { window[barId + '_active'] = key; __renderCodeTabs(barId, areasId, map, taPrefix); };
                  return b;
                }
                for (var i = 0; i < keys.length; i++) bar.appendChild(mkSub(keys[i], keys[i]));
                for (var j = 0; j < keys.length; j++) {
                  var k = keys[j];
                  var ta = document.createElement('textarea');
                  ta.id = taPrefix + '__' + k;
                  ta.readOnly = true;
                  ta.value = map[k] || '';
                  ta.style.cssText = 'flex:1;min-height:0;margin:0;padding:12px 14px;border:0;resize:none;display:' +
                    (k === actKey ? 'flex' : 'none') + ';background:#1e1e1e;color:#d4d4d4;' +
                    'font:13px/1.55 Consolas,Monaco,monospace;outline:none;white-space:pre;';
                  areas.appendChild(ta);
                }
              }

              // 封装为步骤后精准跳转：由 Java 侧 fillCode 完成后调用。切到「步骤代码」Tab，
              // 激活目标 step 所属 pageClass 子 Tab，并选中高亮该 step 方法文本（滚动定位到目标 step），
              // 而非泛泛切到步骤 Tab 让用户自己找——对齐"封装即定位到刚生成的 step"。
              window.__afterFillJump = function() {
                try {
                  var pj = window.__pendingJump;
                  if (!pj) return;
                  window.__pendingJump = null;
                  var pc = (pj.pages && pj.pages[0]) || null;   // 目标 step 所属的 pageClass
                  // 1) 切到「步骤代码」Tab
                  window.__roleActiveTab = 'step';
                  if (window.__roleShowTab) window.__roleShowTab();
                  // 2) 用最新 localStorage（已含新 step）重渲染，并激活目标子 Tab
                  var code = null;
                  try { code = JSON.parse(localStorage.getItem('__rolePickerCode') || 'null'); } catch (e) {}
                  var stepMap = (code && code.stepByPage) || {};
                  if (pc) {
                    window.__roleStepSubTabBar_active = pc;   // 记忆目标子 Tab 为激活
                    if (window.__fillCodeTabs && code) {
                      window.__fillCodeTabs({ pageByPage: (code.pageByPage || {}), stepByPage: stepMap, msg: code.msg || '' });
                    }
                  }
                  // 3) 选中高亮目标 step 方法（取该视图中最后一个 step 方法，即刚封装的），并聚焦滚动定位
                  var ta = pc ? document.getElementById('__roleCodeArea2__' + pc) : document.getElementById('__roleCodeArea2');
                  if (!ta) ta = document.querySelector('#__roleStepAreas textarea');
                  if (ta) {
                    var txt = ta.value || '';
                    var re = /public void step\\d+\\(\\)/g;
                    var m, last = null;
                    while ((m = re.exec(txt)) != null) last = m;
                    if (last) {
                      var s0 = last.index;
                      re.lastIndex = last.index + 1;
                      var m2 = re.exec(txt);
                      // 终点：下一 step 方法起始，或类结尾最近的 '}'
                      var e0 = m2 ? m2.index : (txt.lastIndexOf('}') > s0 ? txt.lastIndexOf('}') : txt.length);
                      ta.focus();
                      if (ta.setSelectionRange) ta.setSelectionRange(s0, e0);
                    }
                  }
                } catch (e) {}
              };

              panel.appendChild(header);
              panel.appendChild(toolbar);
              panel.appendChild(tabBar);
              panel.appendChild(pageContent);
              panel.appendChild(classContent);
              panel.appendChild(stepContent);
              // 注：window.__renumberStep 已在 START 顶层（iframe 早退 return 之前）无条件定义，
              // 此处不再重复；保证顶层与 iframe 子文档的拾取依赖一致。

              // 候选清单渲染（节流到每帧一次）：按 pageClass 分组成子 Tab，每项带勾选框。
              // 后台标签/无头环境下 requestAnimationFrame 可能不触发，用 setTimeout 兜底保证一定会执行。
              window.__renderPicks = function() {
                if (window.__renderScheduled) return;
                window.__renderScheduled = true;
                var flush = function() { window.__renderScheduled = false; __renderPicksNow(); };
                if (window.requestAnimationFrame && !document.hidden) window.requestAnimationFrame(flush);
                else setTimeout(flush, 0);
              };
              // 把当前勾选（window.__currentStep 选择集）按 pageClass 分组、按整页拾取顺序封装为一个/多个 step。
              // 常见单页选择即封装为一个 step；跨页选择会按 pageClass 各自成 step（步骤顺序 = 各页选择出现的顺序）。
              window.__packageStep = function() {
                try {
                  var all = window.__rolePicks || [];
                  var selSet = {};
                  var _src = (window.__currentStep && window.__currentStep.length)
                      ? window.__currentStep
                      : all;   // 【关键修复"导航恢复/扫描后点封装没反应"】勾选集为空时兜底用全部已拾元素：
                               // ① 整页扫描出的候选默认不进 __currentStep（__isScan 守卫），用户未手动勾选即点"封装"应视为"封装全部"；
                               // ② 跨页导航 applyPickState 恢复的 currentStep=0，但 javaPickBySig/__rolePicks 仍有元素，
                               //    点封装按钮时若死守空勾选集会 return 0、不推送 package 命令、Java 侧永远不生成代码。
                  _src.forEach(function(p) {
                    // 【关键】用全局健壮键 __mergeKey（优先 _sigKey，否则实时 __sigKey 重算），
                    // 不再强依赖 p._sigKey||p._sig 已固化。部分链路（区域选择/导航恢复回灌）写入的
                    // pick 签名字段可能缺失，导致此处 selSet 为空 → 封装 0 条 step（表现为"选中也生成不了步骤"）。
                    var k = (typeof window.__mergeKey==='function') ? window.__mergeKey(p) : (p && (p._sigKey || p._sig));
                    if (k) selSet[k] = true;
                  });
                  if (!Object.keys(selSet).length) return 0;
                  window.__steps = window.__steps || [];
                  var startIndex = window.__steps.length;   // 本次新封装 step 的全局起始索引（供跳转到目标 step）
                  // 单步语义：一次「封装为步骤」或一次「开始→停止」拾取 = 一个 step，
                  // 即使选择跨多个页面也合并为【一个 StepRec】（每个 pick 自带 _pageClass，
                  // 生成器据此引用对应页变量），不再按 pageClass 拆成多个 step。
                  // 顺序沿用全局拾取序（window.__rolePicks），跨页操作天然按发生先后排布；
                  // 关闭当前页（_closeOp 标记）也作为 step 内的一笔操作内联，不另成 step。
                  var picks = [];
                  var owner = '';
                  // 【关键修复"页面元素没有按顺序封装步骤"】
                  // 旧实现遍历 all=window.__rolePicks（按「拾取发生先后」push 的全局候选序），再按 selSet 过滤。
                  // 但 iframe 内元素经 postMessage 异步上送、syncPanelToBrowser 回灌，push 进 __rolePicks 的
                  // 顺序可能与用户「点选/勾选」的先后不一致 → 封装出的 step 顺序错乱。
                  // 用户意图应以「勾选顺序」为准：__currentStep 是按用户点击/勾选先后维护的选择集，
                  // 故改为按 __currentStep 顺序遍历，每个勾选元素再从候选 all 中取完整 pick（含 framePath 等增强）。
                  var _selOrder = _src;   // 与上面 _src 一致：勾选集优先，为空时兜底全部已拾元素
                  var _byKey = {};
                  for (var _bi = 0; _bi < all.length; _bi++) {
                    var _bp = all[_bi] || {};
                    var _bk = (typeof window.__mergeKey==='function') ? window.__mergeKey(_bp) : (_bp._sigKey || _bp._sig);
                    if (_bk && !_byKey[_bk]) _byKey[_bk] = _bp;
                  }
                  // 【每序号一个步骤】不再按"元素"归并去重，而是把每个被拾取元素的每个拾取序号
                  // （_pickNos 数组）展开为独立的 pick 条目。这样同一元素被点多次（如 _pickNos=[1,2,3,4,5,13]）
                  // 会生成多个步骤/操作行（每个序号一个），与面板序号前缀一一对应。
                  // 跨页/iframe 元素同样按各自序号展开，全局按序号排序，保证步骤顺序 == 点击先后。
                  var _pickedSeen = {};
                  var _expanded = [];   // { base: 原始pick, no: 序号 } 的列表
                  for (var _si2 = 0; _si2 < _selOrder.length; _si2++) {
                    var _cp = _selOrder[_si2] || {};
                    var _ck2 = (typeof window.__mergeKey==='function') ? window.__mergeKey(_cp) : (_cp._sigKey || _cp._sig);
                    if (!_ck2 || _pickedSeen[_ck2]) continue;
                    _pickedSeen[_ck2] = true;
                    var _fp = _byKey[_ck2] || _cp;   // 优先候选里的完整 pick
                    if (!owner) owner = (_fp._pageClass) || (_cp._pageClass) || (window.__rolePageName || '');
                    // 展开该元素的所有拾取序号为独立条目（保留顺序）
                    var _nos = (Array.isArray(_fp._pickNos) && _fp._pickNos.length)
                        ? _fp._pickNos.slice()
                        : (typeof _fp._pickSeq === 'number' && _fp._pickSeq > 0 ? [_fp._pickSeq] : []);
                    if (!_nos.length) _nos = [0];   // 兜底：无序号元素给一个占位号，排序垫底
                    for (var _nx = 0; _nx < _nos.length; _nx++) {
                      _expanded.push({ base: _fp, no: _nos[_nx] });
                    }
                  }
                  // 全局按拾取序号升序排列，保证步骤顺序 == 点击发生先后
                  _expanded.sort(function(a, b) { return (a.no || 0) - (b.no || 0); });
                  // 每个序号生成一个独立 pick 克隆（浅拷贝定位字段，剥离 _pickNos/_pickSeq 以承载单序号）
                  picks = [];
                  for (var _pi = 0; _pi < _expanded.length; _pi++) {
                    var _eb = _expanded[_pi].base || {};
                    var _clone = {};
                    for (var _ek in _eb) {
                      if (_ek === '_pickNos' || _ek === '_pickSeq') continue;
                      _clone[_ek] = _eb[_ek];
                    }
                    _clone._pickNos = [_expanded[_pi].no];
                    _clone._pickSeq = _expanded[_pi].no;
                    _clone.seq = _pi + 1;   // 步骤内重新连续编号
                    picks.push(_clone);
                  }
                  if (!owner) owner = (window.__rolePageName || '');
                  window.__steps.push({ pageClass: owner, picks: picks });
                  window.__currentStep = [];   // 已封装，清空选择集
                  // 清除区域选择遗留的页面高亮框（绿色已选/青色悬停），否则不按 Esc 直接封装时绿框会残留。
                  try { if (typeof window.__clearRegionOutlines === 'function') window.__clearRegionOutlines(); } catch (e) {}
                  // 仅轻量刷新勾选态（复选框复位为未勾选），不重建整张候选列表——
                  // 扫描出海量子元素时全量重建会卡 UI，正是"封装 step 慢"的根因。
                  window.__applySelection();
                  // 返回结构化信息：本次封装涉及的 pageClass（按序）与全局起始索引，
                  // 供 pkgBtn 记录"目标 step"，Java 生成代码后由 window.__afterFillJump 精准定位。
                  return { pages: [owner], start: startIndex, count: 1 };
                } catch (e) { return 0; }
              };
              // 轻量刷新勾选态：仅更新已存在行的复选框与高亮，【不重建整个列表 DOM】。
              // 供「封装为步骤」等"仅选择集变化、候选集合不变"的场景调用，避免对扫描出的海量元素做 O(n) 全量重建（卡 UI 的根因）。
              window.__applySelection = function() {
                try {
                  var listEl = document.getElementById('__rolePickList');
                  if (!listEl) return;
                  var selSet = {};
                  (window.__currentStep || []).forEach(function(p) {
                    var k = (typeof window.__mergeKey==='function') ? window.__mergeKey(p) : (p && (p._sigKey || p._sig)); if (k) selSet[k] = true;
                  });
                  var rows = listEl.children;
                  for (var i = 0; i < rows.length; i++) {
                    var row = rows[i];
                    if (!row || !row.__cb) continue;
                    var sk = row.__sig;
                    var sel = !!(sk && selSet[sk]);
                    row.__cb.checked = sel;
                    row.style.background = sel ? 'rgba(30,136,229,.18)' : '';
                  }
                  // 【修复】同步"全选"复选框：selAllCb 是列表外的独立复选框、不在上面 children 遍历范围内。
                  // 其勾选状态必须由【真实选择集 window.__currentStep】唯一决定，否则会出现
                  // "元素行全未选中、但全选框仍勾选"的不一致（封装为步骤后切回页面元素 tab 尤为明显）。
                  // 双保险：① refreshSelInfo 统一刷新计数与全选框（依赖闭包 selAllCb）；
                  //        ② 直接用全局引用 window.__roleSelAllCb（指向真实 DOM checkbox）按真实选择集兜底复位，
                  //           不依赖闭包捕获，杜绝因闭包错位导致的复位失效。
                  try {
                    if (typeof refreshSelInfo === 'function') { try { refreshSelInfo(); } catch (e3) {} }
                    var _sa = window.__roleSelAllCb;
                    if (_sa) {
                      var _cset = window.__currentStep || [];
                      var _visN = (typeof curVisiblePicks === 'function') ? curVisiblePicks().length : 0;
                      _sa.checked = (_visN > 0 && _cset.length === _visN);
                    }
                  } catch (e2) {}
                } catch (e) {}
              }
              // 【删除单个拾取序号 + 全局重编号】用户点击面板里某序号超链接时调用：
              // 从「全局序号空间」删除该号，并把所有比它大的号紧凑前移（-1），其余元素序号同步重排；
              // 同步刷新浏览器侧 __rolePicks / __currentStep 的 _pickNos，并通知 Java 权威内存态按 mergeKey
              // 更新对应 RoleEntry.pickNos（步骤生成依赖它按号展开），最后重渲染面板。
              // 因 _pickNos 是跨所有元素共享的全局递增序号，删除 no 即等价于"该号之后的所有动作前移一位"。
              // 【修复"同一元素点 + 序号没加 / 删除不刷新"】由于 syncPanelToBrowser 每轮会"先清空再重建"
              // window.__rolePicks，渲染闭包里捕获的 pick 引用会成为弃用的孤儿对象。直接改它：
              // ① 面板重渲染遍历的是当前新数组，孤儿对象的修改不可见；② repickNos 虽能把新号同步回 Java，
              // 但本地视图要等到下一轮 Java 重建才刷新，中间出现"点了没加"的观感。
              // 故改为：按 mergeKey 从【当前】window.__rolePicks 实时查找真实对象再改，再 __renderPicksNow，
              // 点击即刻可见；同时仍发 repickNos 让 Java 权威态保持一致。
              function __findPickByMergeKey(mk) {
                if (!mk) return null;
                var all = window.__rolePicks || [];
                for (var i = 0; i < all.length; i++) {
                  var p = all[i];
                  if (p && typeof window.__mergeKey === 'function' && window.__mergeKey(p) === mk) return p;
                }
                return null;
              }
              window.__deletePickNo = function(pick, no) {
                try {
                  if (no == null || typeof no !== 'number') return;
                  var mk0 = (typeof window.__mergeKey === 'function') ? window.__mergeKey(pick) : null;
                  var target = __findPickByMergeKey(mk0) || pick;   // 优先用当前面板真实对象
                  if (!target || !Array.isArray(target._pickNos)) {
                    if (target) target._pickNos = []; else return;
                  }
                  var p = target;
                  // 1) 移除被删的号
                  var next = [];
                  for (var j = 0; j < p._pickNos.length; j++) {
                    var v = p._pickNos[j];
                    if (v === no) continue;                 // 删除该序号
                    next.push(v);
                  }
                  p._pickNos = next;
                  // 【修复"序号减到 0 仍显示 1"】删除最后一个序号后 next 为空数组，但若 _pickSeq 仍 > 0
                  // （下方只减 1 却未清零），渲染逻辑会兜底走 _pickSeq 分支、仍显示 [1]。故当该元素序号
                  // 已全部删空时，把 _pickSeq 一并清零，使其落入 [1] 之外的 [-] 占位分支，正确反映"已无序号"。
                  if (next.length === 0) {
                    p._pickSeq = 0;
                  } else if (typeof p._pickSeq === 'number' && p._pickSeq > no) {
                    p._pickSeq = p._pickSeq - 1;
                  }
                  // 2) 全局重编号：收集所有剩余序号，按原始序号排序后重新分配连续序号
                  var __allPicks = window.__rolePicks || [];
                  // 【关键】先保存所有元素的原始 _pickNos，避免重编号过程中引用已修改的数据
                  var __origPickNosMap = [];
                  var __allNos = [];
                  for (var __i = 0; __i < __allPicks.length; __i++) {
                    var __p = __allPicks[__i];
                    if (__p && Array.isArray(__p._pickNos) && __p._pickNos.length > 0) {
                      var __copy = __p._pickNos.slice();
                      __origPickNosMap[__i] = __copy;  // 保存副本
                      for (var __j = 0; __j < __copy.length; __j++) {
                        if (typeof __copy[__j] === 'number') __allNos.push(__copy[__j]);
                      }
                    } else {
                      __origPickNosMap[__i] = [];
                    }
                  }
                  // 按原始序号顺序排序，建立 旧号->新号 映射
                  __allNos.sort(function(a, b) { return a - b; });
                  var __noMap = {};
                  for (var __k = 0; __k < __allNos.length; __k++) {
                    __noMap[__allNos[__k]] = __k + 1;
                  }
                  // 基于原始副本重新写入新号
                  for (var __i = 0; __i < __allPicks.length; __i++) {
                    var __p = __allPicks[__i];
                    var __orig = __origPickNosMap[__i] || [];
                    if (__orig.length > 0) {
                      var __newNos = [];
                      for (var __j = 0; __j < __orig.length; __j++) {
                        var __old = __orig[__j];
                        if (typeof __old === 'number' && __noMap[__old] !== undefined) {
                          __newNos.push(__noMap[__old]);
                        }
                      }
                      __newNos.sort(function(a, b) { return a - b; });
                      __p._pickNos = __newNos;
                      if (__newNos.length > 0) {
                        __p._pickSeq = __newNos[__newNos.length - 1];
                      } else {
                        __p._pickSeq = 0;
                      }
                    }
                  }
                  window.__rolePickSeq = __allNos.length;   // 同步全局计数器
                  window.__roleMaxNo = __allNos.length;     // 【关键修复】同步 __roleMaxNo，保证新序号从重排后最大值+1 开始
                  // 3) 同步回 Java 权威内存态（所有有 _pickNos 的元素）
                  for (var __si = 0; __si < __allPicks.length; __si++) {
                    var __sp = __allPicks[__si];
                    if (__sp && Array.isArray(__sp._pickNos) && __sp._pickNos.length > 0) {
                      var __smk = (typeof window.__mergeKey === 'function') ? window.__mergeKey(__sp) : null;
                      if (__smk) {
                        try { window.__rolePickerCmd({ type: 'repickNos', mergeKey: __smk, nos: __sp._pickNos.slice() }); } catch (e) {}
                      }
                    }
                  }
                  // 4) 同步 __currentStep 中引用这些 pick 的序号（若有缓存的快照）
                  if (window.__currentStep && Array.isArray(window.__currentStep.raws)) {
                    for (var k = 0; k < window.__currentStep.raws.length; k++) {
                      var rp = window.__currentStep.raws[k];
                      if (rp && Array.isArray(rp._pickNos)) {
                        var rpk = (typeof window.__mergeKey === 'function') ? window.__mergeKey(rp) : null;
                        for (var __ri = 0; __ri < __allPicks.length; __ri++) {
                          var __rp = __allPicks[__ri];
                          var __rmk = (typeof window.__mergeKey === 'function') ? window.__mergeKey(__rp) : null;
                          if (__rmk && __rmk === rpk) {
                            rp._pickNos = __rp._pickNos.slice();
                            break;
                          }
                        }
                      }
                    }
                  }
                  window.__renderPicksNow();   // 重渲染面板（序号超链接重新生成）
                } catch (e) {
                  try { window.__rolePickerCmd({ type: 'diag', msg: 'deletePickNo failed: ' + (e && e.message) }); } catch (e2) {}
                }
              };
              // 【新增序号 +】点击面板里某元素序号后的加号，给该元素【追加一次拾取动作】：
              // 取全局最大序号 +1 作为新号，追加进该元素 _pickNos 末尾，并同步 Java 权威内存态，最后重渲染面板。
              // 效果等价于"在页面上再点一次该元素"——序号列表增长（如 [1,4,7] → [1,4,7,8]），步骤生成同步增加一步。
              window.__addPickNo = function(pick) {
                try {
                  // 【修复"点任意元素加号却都加到最后一个元素"】原实现先用 mergeKey 在 window.__rolePicks
                  // 中查找 target，当渲染闭包里 pick 的引用/键在重建后与数组内元素错位（典型：多元素 _sigKey
                  // 缺失或共享）时，__findPickByMergeKey 会命中最后一个元素，导致所有加号的新号都 push 到
                  // 同一个（最后）元素上。
                  // 修复策略：优先直接使用面板渲染时传入的真实对象 pick 自身——只要它是数组里按引用存在的
                  // 真实元素（或至少持有独立的 _pickNos 数组），就不再做间接查找；仅当 pick 本身无 _pickNos
                  // 数组时才退化为 mergeKey 查找（兜底去重/恢复场景）。这样点哪个元素的 + 就给哪个元素追加。
                  var mk = (typeof window.__mergeKey === 'function') ? window.__mergeKey(pick) : null;
                  var target = pick;
                  if (!target || !Array.isArray(target._pickNos)) {
                    target = __findPickByMergeKey(mk) || pick;
                    if (!target) return;
                    if (!Array.isArray(target._pickNos)) target._pickNos = [];
                  }
                  // 计算全局新号：所有 pick 的 _pickNos 最大值与 __rolePickSeq 的较大值 +1。
                  var maxNo = 0;
                  var all = window.__rolePicks || [];
                  for (var i = 0; i < all.length; i++) {
                    var pn = all[i] && all[i]._pickNos;
                    if (Array.isArray(pn)) {
                      for (var j = 0; j < pn.length; j++) {
                        if (typeof pn[j] === 'number' && pn[j] > maxNo) maxNo = pn[j];
                      }
                    }
                  }
                  if (typeof window.__rolePickSeq === 'number' && window.__rolePickSeq > maxNo) maxNo = window.__rolePickSeq;
                  var newNo = maxNo + 1;
                  target._pickNos.push(newNo);          // 追加新序号
                  target._pickNos.sort(function(a, b) { return a - b; });  // 【修复"序号顺序混乱"】追加后排序，确保 pickNos 始终有序
                  if (typeof target._pickSeq !== 'number' || target._pickSeq < newNo) target._pickSeq = newNo;
                  window.__rolePickSeq = newNo;        // 同步全局计数器，保证后续新增/拾取不撞号
                  window.__roleMaxNo = newNo;          // 【关键修复】同步 __roleMaxNo，保证新序号从当前最大值+1 开始
                  // 同步回 Java 权威内存态（按 mergeKey 精确命中 RoleEntry）。
                  if (mk) {
                    try { window.__rolePickerCmd({ type: 'repickNos', mergeKey: mk, nos: target._pickNos.slice() }); } catch (e) {}
                  }
                  // 同步 __currentStep 中该 pick 的快照（若有）。
                  if (window.__currentStep && Array.isArray(window.__currentStep.raws)) {
                    for (var k = 0; k < window.__currentStep.raws.length; k++) {
                      var rp = window.__currentStep.raws[k];
                      if (rp && (rp === target || (mk && typeof window.__mergeKey === 'function' && window.__mergeKey(rp) === mk))) {
                        rp._pickNos = target._pickNos.slice();
                      }
                    }
                  }
                  window.__renderPicksNow();   // 重渲染面板（序号超链接 + 加号重新生成），立即显示新号
                } catch (e) {
                  try { window.__rolePickerCmd({ type: 'diag', msg: 'addPickNo failed: ' + (e && e.message) }); } catch (e2) {}
                }
              };
              // 【删除单个完整元素（垃圾箱）+ 全局重编号 + 步骤刷新】
              // 用户点击面板里某元素行末尾的垃圾箱图标时调用：从 window.__rolePicks / __rolePickSigs /
              // __currentStep / __steps 中彻底移除该元素，通知 Java 权威内存态同步删除，然后重排
              // 剩余元素的全局序号、重渲染面板、并触发代码刷新（步骤里的引用同步清除）。
              window.__deleteSinglePick = function(pick) {
                try {
                  if (!pick) return;
                  // 1) 收集目标元素的所有去重键（与 bulk delete 同样的口径）
                  var rsig = (typeof window.__pickSig === 'function') ? window.__pickSig(pick) : (pick._sig || '');
                  var rkey = (typeof window.__mergeKey === 'function') ? window.__mergeKey(pick)
                            : ((typeof window.__sigKey === 'function') ? window.__sigKey(pick) : (pick._sigKey || ''));
                  if (!rkey && !rsig) return;
                  var dead = {};
                  var deadKeys = [];
                  if (rkey) { dead[rkey] = true; deadKeys.push(rkey); }
                  if (rsig) dead[rsig] = true;
                  // 【修复"已删除元素无法重新拾取"】
                  // 旧逻辑：写入 __deletedSigs 永久屏蔽，导致 syncPanelToBrowser 检查命中后跳过元素，
                  // 用户永远无法重新拾取已删除的元素。
                  // 新逻辑：不再写入 __deletedSigs，允许用户重新拾取。删除的语义是"从当前拾取列表移除"，
                  // 而非"永久封杀该元素"。Java 侧已不再写入 STATE_DELETED，浏览器侧也应保持一致。
                  // var delSigs = window.__deletedSigs = (window.__deletedSigs || {});
                  // if (rkey) delSigs[rkey] = true;
                  // 2) 从 __rolePicks 移除
                  window.__rolePicks = (window.__rolePicks || []).filter(function(x) {
                    if (!x) return false;
                    return !(dead[x._sigKey] || dead[x._sig]);
                  });
                  // 3) 从 __rolePickSigs 清除去重登记
                  try {
                    var sigs = window.__rolePickSigs || {};
                    deadKeys.forEach(function(k) { if (k) delete sigs[k]; });
                    window.__rolePickSigs = sigs;
                  } catch (e2) {}
                  // 4) 从 __currentStep 移除
                  window.__currentStep = (window.__currentStep || []).filter(function(x) {
                    if (!x) return false;
                    var mk = (typeof window.__mergeKey === 'function') ? window.__mergeKey(x) : null;
                    return !((mk && dead[mk]) || (x._sigKey && dead[x._sigKey]));
                  });
                  // 5) 从 __steps 中清除对该元素的引用
                  try {
                    var _steps = window.__steps || [];
                    var _kept = [];
                    _steps.forEach(function(s) {
                      if (!s || typeof s !== 'object' || typeof s.op === 'string') { _kept.push(s); return; }
                      var ps = (s.picks || []).filter(function(p) {
                        if (!p) return false;
                        var mk = (typeof window.__mergeKey === 'function') ? window.__mergeKey(p) : null;
                        return !((mk && dead[mk]) || (p._sigKey && dead[p._sigKey]));
                      });
                      if (!ps.length) return;
                      s.picks = ps;
                      _kept.push(s);
                    });
                    window.__steps = _kept;
                  } catch (e6) {}
                  // 6) 通知 Java 权威内存态同步删除（与 bulk delete 相同的上报格式）
                  var delPicks = [{
                    strategy: pick.strategy,
                    _sig: rsig || pick._sig,
                    _sigKey: rkey || pick._sigKey,
                    _pageClass: pick._pageClass,
                    role: pick.role,
                    key: pick.key,
                    name: pick.name,
                    index: pick.index,
                    selector: pick.selector,
                    css: pick.css,
                    resolvedKey: pick.resolvedKey,
                    tag: pick.tag,
                    text: pick.text
                  }];
                  try {
                    var payload = JSON.stringify(delPicks);
                    if (typeof window.__roleOnDelete === 'function') window.__roleOnDelete(payload);
                    try { console.log('__roleOnDelete::' + payload); } catch (e4) {}
                  } catch (e3) {}
                  // 7) 全局重编号：收集所有剩余序号，按原始序号排序后重新分配连续序号
                  var __allPicks = window.__rolePicks || [];
                  // 【关键】先保存所有元素的原始 _pickNos，避免重编号过程中引用已修改的数据
                  var __origPickNosMap = [];
                  var __allNos = [];
                  for (var __i = 0; __i < __allPicks.length; __i++) {
                    var __p = __allPicks[__i];
                    if (__p && Array.isArray(__p._pickNos) && __p._pickNos.length > 0) {
                      var __copy = __p._pickNos.slice();
                      __origPickNosMap[__i] = __copy;  // 保存副本
                      for (var __j = 0; __j < __copy.length; __j++) {
                        if (typeof __copy[__j] === 'number') __allNos.push(__copy[__j]);
                      }
                    } else {
                      __origPickNosMap[__i] = [];
                    }
                  }
                  // 按原始序号顺序排序，建立 旧号->新号 映射
                  __allNos.sort(function(a, b) { return a - b; });
                  var __noMap = {};
                  for (var __k = 0; __k < __allNos.length; __k++) {
                    __noMap[__allNos[__k]] = __k + 1;
                  }
                  // 基于原始副本重新写入新号
                  for (var __i = 0; __i < __allPicks.length; __i++) {
                    var __p = __allPicks[__i];
                    var __orig = __origPickNosMap[__i] || [];
                    if (__orig.length > 0) {
                      var __newNos = [];
                      for (var __j = 0; __j < __orig.length; __j++) {
                        var __old = __orig[__j];
                        if (typeof __old === 'number' && __noMap[__old] !== undefined) {
                          __newNos.push(__noMap[__old]);
                        }
                      }
                      __newNos.sort(function(a, b) { return a - b; });
                      __p._pickNos = __newNos;
                      if (__newNos.length > 0) {
                        __p._pickSeq = __newNos[__newNos.length - 1];
                      } else {
                        __p._pickSeq = 0;
                      }
                    }
                  }
                  window.__rolePickSeq = __allNos.length;   // 同步全局计数器
                  window.__roleMaxNo = __allNos.length;     // 【关键修复】同步 __roleMaxNo，保证新序号从重排后最大值+1 开始
                  window.__renumberStep();
                  // 7.5) 将重编号后的 _pickNos 同步回 Java 权威内存态（repickNos 先于 refreshCode 入队，
                  // 主循环按序处理，保证 Java 侧 pickNos 已更新后再生成代码，否则 refreshCode 仍读旧号）。
                  for (var __si = 0; __si < __allPicks.length; __si++) {
                    var __sp = __allPicks[__si];
                    if (__sp && Array.isArray(__sp._pickNos) && __sp._pickNos.length > 0) {
                      var __smk = (typeof window.__mergeKey === 'function') ? window.__mergeKey(__sp) : null;
                      if (__smk) {
                        try { window.__rolePickerCmd({ type: 'repickNos', mergeKey: __smk, nos: __sp._pickNos.slice() }); } catch (e) {}
                      }
                    }
                  }
                  // 8) 触发代码刷新（页面类 + 步骤代码同步更新）
                  try {
                    if (typeof window.__rolePickerCmd === 'function') {
                      window.__rolePickerCmd('refreshCode');
                    } else {
                      window.__panelCmds = window.__panelCmds || [];
                      window.__panelCmds.push('refreshCode');
                    }
                  } catch (e7) {}
                  // 9) 持久化当前态，避免整页跳转后被删元素恢复
                  try { if (typeof window.__persistPickState === 'function') window.__persistPickState(); } catch (e5) {}
                  // 10) 重渲染面板
                  window.__renderPicks();
                } catch (e) {
                  try { window.__rolePickerCmd({ type: 'diag', msg: 'deleteSinglePick failed: ' + (e && e.message) }); } catch (e2) {}
                }
              };
              function __renderPicksNow() {
                try {
                  var subBar = document.getElementById('__roleSubTabBar');
                  var listEl = document.getElementById('__rolePickList');
                  if (!subBar || !listEl) return;  // 面板尚未挂载，等下次拾取/恢复时再渲染
                  // iframe（含嵌套 frame）内的拾取元素会经 postMessage 逐层上送顶层，
                  // 由顶层 message 监听（见 START_SCRIPT 末尾）push 进【顶层】window.__rolePicks 聚合。
                  // 因此面板只需渲染顶层 window.__rolePicks 即可，无需（也不能）跨域遍历 window.frames
                  // —— file:// 场景下 iframe origin 为 "null"，主框架访问子 frame 的 window.__rolePicks 会被
                  // 浏览器同源策略直接拦截（"Blocked a frame with origin null"），导致面板反而为空。
                  var picks = window.__rolePicks || [];
                  // 统计各 pageClass 候选数，用于子 Tab 命名与计数（命名以 page class）。
                  var counts = {};
                  for (var i = 0; i < picks.length; i++) {
                    var pc = (picks[i] && picks[i]._pageClass) || (window.__rolePageName || '未知页');
                    counts[pc] = (counts[pc] || 0) + 1;
                  }
                  var pageClasses = Object.keys(counts);
                  // 默认激活第一个 pageClass（不再提供「全部」汇总 tab，页面元素按页分组各自独立展示）。
                  var act = window.__roleActivePageClass;
                  if (!act || pageClasses.indexOf(act) < 0) act = pageClasses[0] || (window.__rolePageName || '未知页');
                  window.__roleActivePageClass = act;
                  // 渲染页面类子 Tab（命名以 page class，按页分组，无「全部」）
                  subBar.innerHTML = '';
                  function mkSub(label, key) {
                    var b = document.createElement('button');
                    b.type = 'button';
                    b.textContent = label + ((counts[key] != null) ? (' (' + counts[key] + ')') : '');
                    b.style.cssText = 'flex:0 0 auto;padding:6px 12px;border:0;cursor:pointer;font:12px/1.4 sans-serif;' +
                      'color:#ccc;background:' + (act === key ? '#1e88e5' : '#2d2d2d') + ';' +
                      'border-bottom:2px solid ' + (act === key ? '#1e88e5' : 'transparent') + ';';
                    b.onclick = function() { window.__roleActivePageClass = key; window.__renderPicks(); };
                    return b;
                  }
                  for (var c = 0; c < pageClasses.length; c++) subBar.appendChild(mkSub(pageClasses[c], pageClasses[c]));

                  // 渲染候选项（带勾选框）。勾选态 = 该 pick 的 sig 在选择集 window.__currentStep 中。
                  listEl.innerHTML = '';
                  if (!picks.length) {
                    listEl.textContent = '（暂无拾取：点 🔍 扫描整页，或在页面点击元素）';
                    // 【关键修复"删除全部元素后全选 checkbox 仍勾选且可用、已选计数陈旧"】
                    // 旧实现此处直接 return，跳过了下方 selBar 全选框与 refreshSelInfo 的更新——删除全部后
                    // 列表为空，refreshSelInfo 不再执行，全选框保持删除前的勾选/可用状态、计数仍显示旧的
                    // "已选 10/10"。修复：空列表时也复位全选框（禁用 + 取消勾选）并把计数归零，
                    // 使"删除全部 → 全选框 disabled、已选 0/0"与真实选择集一致。
                    var __selCb = window.__roleSelAllCb;
                    if (__selCb) { __selCb.disabled = true; __selCb.checked = false; }
                    var __selInfo = window.__roleSelInfo;
                    if (__selInfo) __selInfo.textContent = '已选 0 / 0';
                    return;
                  }
                  // 全选 / 全不选 工具栏：作用于"当前可见范围"（act 过滤集），与下方候选渲染用同一过滤规则。
                  // 注意：本栏在 __renderPicks 每次重渲染时都会被调用（手动拾取每个元素都会触发），
                  // 因此【只创建一次并复用】——否则每次重渲染都会在 pageContent 里堆积出新的全选条。
                  var selBar = window.__roleSelBar || null;
                  if (!selBar) {
                    selBar = document.createElement('div');
                    selBar.id = '__roleSelBar';
                    selBar.style.cssText = 'position:sticky;top:0;z-index:2;display:flex;gap:8px;align-items:center;' +
                      'padding:6px 4px;background:#1f1f1f;border-bottom:1px solid #333;color:#cfcfcf;font:12px/1.4 sans-serif;';
                    var selAll = document.createElement('label');
                    selAll.style.cssText = 'display:flex;gap:5px;align-items:center;cursor:pointer;user-select:none;';
                    var selAllCb = document.createElement('input');
                    selAllCb.type = 'checkbox';
                    selAllCb.style.cssText = 'width:14px;height:14px;cursor:pointer;';
                    var selAllTxt = document.createElement('span');
                    selAllTxt.textContent = '全选';
                    selAll.appendChild(selAllCb); selAll.appendChild(selAllTxt);
                    var selInfo = document.createElement('span');
                    selInfo.style.cssText = 'margin-left:auto;color:#9aa0a6;';
                    selBar.appendChild(selAll); selBar.appendChild(selInfo);
                    // 暴露全局引用，供 __applySelection 在"封装为步骤"等仅清空选择集的场景下同步复位"全选"复选框。
                    window.__roleSelAllCb = selAllCb;
                    window.__roleSelBar = selBar;
                    window.__roleSelInfo = selInfo;
                  } else {
                    // 复用已存在的工具栏：重新指向内部"全选"复选框与信息节点。
                    var selAllCb = selBar.querySelector('input[type=checkbox]');
                    var selInfo = window.__roleSelInfo;
                    window.__roleSelAllCb = selAllCb;
                  }
                  function curVisiblePicks() {
                    return picks.filter(function(p) {
                      if (!p) return false;
                      var pc = (p._pageClass) || (window.__rolePageName || '未知页');
                      return pc === act;
                    });
                  }
                  // 「封装为步骤」按钮与「全选」复选框合并到同一行（selInfo 的 margin-left:auto 已把计数+按钮推到右侧）
                  // selBar 持久复用：先移除上次渲染留下的同名按钮，再追加新按钮，避免每次重渲染堆积多个封装按钮。
                  var _oldPkg = document.getElementById('__rolePkgBtn');
                  if (_oldPkg) _oldPkg.remove();
                  selBar.appendChild(pkgBtn);
                  // 删除按钮（小垃圾桶图标）：位于「全部」工具栏这一行；无任何选中项时灰暗禁用，有选中时高亮可点。
                  var delBtn = document.createElement('button');
                  delBtn.type = 'button';
                  delBtn.id = '__roleDelBtn';   // 稳定 id：selBar 复用持久存在时，每次重渲染先移除旧按钮再追加，避免堆积
                  delBtn.title = '删除选中的全部元素';
                  // 图标放大到 18px 并改用「桶身描边 + 内部竖线」的高对比画法：
                  // 原先 15px 纯色实心块在深色工具栏上糊成一团、辨识度低，这里让桶盖/桶身/竖纹层次分明。
                  delBtn.innerHTML = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" ' +
                    'stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
                    '<path d="M3 6h18"/>' +
                    '<path d="M8 6V4h8v2"/>' +
                    '<path d="M19 6l-1 14H6L5 6"/>' +
                    '<path d="M10 11v5M14 11v5"/>' +
                    '</svg>';
                  function syncDelBtn() {
                    var has = (window.__currentStep || []).length > 0;
                    if (has) {
                      // 可用态：亮红底 + 白图标 + 亮红描边 + 红色投影，在深色工具栏里一眼可见。
                      delBtn.disabled = false;
                      delBtn.style.cssText = 'flex:0 0 auto;display:flex;align-items:center;justify-content:center;' +
                        'width:32px;height:28px;padding:0;color:#fff;border:1px solid #ff6b60;border-radius:6px;' +
                        'cursor:pointer;background:#f4433a;opacity:1;box-shadow:0 0 0 2px rgba(244,67,58,.35);' +
                        'transition:filter .15s,box-shadow .15s;';
                    } else {
                      // 禁用态：不再用浅灰底（在深灰工具栏上几乎看不见），改为暗红描边的虚线轮廓 + 暗底，
                      // 既保持尺寸不跳、又让"垃圾桶"形状在禁用时也清晰可辨（淡红图标 + 红虚线边框）。
                      delBtn.disabled = true;
                      delBtn.style.cssText = 'flex:0 0 auto;display:flex;align-items:center;justify-content:center;' +
                        'width:32px;height:28px;padding:0;color:#f4877f;border:1px dashed rgba(244,67,58,.55);border-radius:6px;' +
                        'cursor:not-allowed;background:#2a2a2a;opacity:1;';
                    }
                  }
                  delBtn.onclick = function(e) {
                    if (e && e.stopPropagation) e.stopPropagation();
                    if (delBtn.disabled) return;
                    var cset = window.__currentStep || [];
                    if (!cset.length) return;
                    var dead = {};
                    // 同时收集 _sigKey 与 _sig 两种键：浏览器端去重 / 清理用（过滤 __rolePicks、__rolePickSigs）。
                    var deadKeys = [];
                    // 【关键修复"删除无效"】浏览器侧只上报 _sigKey/_sig 这两个字符串是不够的：
                    // Java 内存态 javaPickBySig 的 map key 由 pickDedupKey 决定——role/closeOp 策略用 _sigKey，
                    // 但「定位器唯一型策略」(id/css/i18n/text) 用的是 _sig（即 __pickSig 结果），
                    // 而这两种键的 *取值* 并不相同（_sigKey 是含_pageClass的 JSON 串、_sig 是 "id:xxx#0" 形式），
                    // 过去只按字符串判断，对定位器策略常因「上报键」与「内存态 key」对不上而删不掉，
                    // 主循环每 ~1s 又把 javaPickBySig 整体 merge 回浏览器，元素随即"复活"，表现为「删除没起作用」。
                    // 故此处额外上报**完整 pick 对象**（剥离 _el 等 DOM 引用，避免循环 JSON 失败），
                    // Java 侧 __roleOnDelete 用与入库时完全相同的 pickDedupKey 重新算 key 精确命中删除。
                    var delPicks = [];
                    // 【修复"扫描新增元素漏删"】cset 只是用户在面板里【勾选】的选择集，
                    // 而整页扫描新增的元素（如 StatusConfirmationLightIcon）会进入 __rolePicks、
                    // 被 Java 内存态收录，却可能尚未进入 __currentStep（用户全选后、删除前才扫描出来）。
                    // 这类"已存在但未被勾选"的同页元素，点删除时 cset.forEach 遍历不到 → 删不掉 → 残留。
                    // 故此处：先收集 cset 涉及的 pageClass 集合，再把 __rolePicks 中【同一 pageClass】
                    // 的全部元素并入删除集。仍严格按 pageClass 隔离，绝不会波及另一页的共用元素。
                    var csetPages = {};
                    // 【修复"点删除时只删当前页元素"】
                    // 旧逻辑把 cset（用户勾选集）涉及的【所有 pageClass】都纳入删除目标；若用户在其他页面也勾选/全选，
                    // csetPages 会包含多页 → 点一次删除把多页元素一并删掉。按需求：删除只作用于【当前激活页】
                    // （window.__roleActivePageClass，即面板顶部高亮的页面子 Tab），即便其它页也处于全选状态也不波及。
                    // 故此处只保留"勾选元素中、_pageClass === 当前激活页"的页，作为删除目标页集合。
                    var __actPage = window.__roleActivePageClass || window.__rolePageName || '';
                    cset.forEach(function(x) {
                      if (x) {
                        var p = (x._pageClass || window.__rolePageName || '');
                        if (p && p === __actPage) csetPages[p] = true;
                      }
                    });
                    var allCandidates = cset.slice();
                    // 收集顶层 + 所有 iframe（含嵌套）中的同页 pick。
                    // 实测：testid 等元素若位于 iframe 内，浏览器侧"合并 iframe 拾取到主框架"失败时，
                    // 它只留在 iframe 自己的 window.__rolePicks，不会进入顶层；而 Java 内存态(__roleOnPick)
                    // 是独立上送的，所以 Java 有、顶层浏览器没有 → 删除遍历不到 → 残留下来的正是这类元素。
                    // 故此处必须跨 frame 收集，否则同页 iframe 元素漏删。
                    function __collectFromWin(w) {
                      try {
                        var picks = (w && w.__rolePicks) ? w.__rolePicks : [];
                        var cur = (w && w.__currentStep) ? w.__currentStep : [];
                        [].concat(picks, cur).forEach(function(x) {
                          if (!x) return;
                          var p = (x._pageClass || (w && w.__rolePageName) || window.__rolePageName || '');
                          // 仅收集当前激活页（__actPage）的同页元素；即便其它页已全选，也不并入删除集。
                          if (p && p === __actPage && allCandidates.indexOf(x) < 0) allCandidates.push(x);
                        });
                      } catch (e2) { /* 跨 frame 访问可能因 detached 失败，忽略 */ }
                    }
                    __collectFromWin(window);
                    try {
                      var __fr = (window.frames && window.frames.length) ? window.frames : [];
                      for (var __fi = 0; __fi < __fr.length; __fi++) {
                        try { __collectFromWin(__fr[__fi]); } catch (e3) {}
                      }
                    } catch (e4) {}
                    // 【修复"已删除元素无法重新拾取"】
                    // 旧逻辑：写入 __deletedSigs 永久屏蔽，导致 syncPanelToBrowser 检查命中后跳过元素，
                    // 用户永远无法重新拾取已删除的元素。
                    // 新逻辑：不再写入 __deletedSigs，允许用户重新拾取。删除的语义是"从当前拾取列表移除"，
                    // 而非"永久封杀该元素"。Java 侧已不再写入 STATE_DELETED，浏览器侧也应保持一致。
                    // var delSigs = window.__deletedSigs = (window.__deletedSigs || {});
                    allCandidates.forEach(function(x) {
                      if (!x) return;
                      // 【关键】实时重算签名/去重键，而非依赖 pick 对象上碰巧缺失的字段。
                      // 部分链路（如区域选择、导航恢复回灌）写入 __currentStep 的 pick 可能没固化 _sig，
                      // 导致上报给 Java 的 _sig 为空、pickDedupKey 算不出 key → 定位器策略删不掉、随后复活。
                      // 用全量重算可保证 _sig/_sigKey 一定存在且与入库时完全一致。
                      // 删除键一律走 __mergeKey 口径（含 pageClass，如 "[\"role:link:Language:#0\",\"LogonPage\"]"），
                      // 绝不用裸 _sig（如 "role:link:Language:#0"）——否则 LoginPage 与 SetupSecondPwdPage 上
                      // 同名共用元素（Language、HSBC App tab、各页脚链接，_sig 完全相同）会共享同一裸键，
                      // 删一页即误删/屏蔽另一页。__mergeKey 已对全部策略（role/id/css/i18n/text）返回含 pc 的键，
                      // 故删除命中率不受影响；冗余的裸 _sig 兜底既不安全也无必要。
                      var rsig = (typeof window.__pickSig === 'function') ? window.__pickSig(x) : (x._sig || '');
                      var rkey = (typeof window.__mergeKey === 'function') ? window.__mergeKey(x)
                                : ((typeof window.__sigKey === 'function') ? window.__sigKey(x) : (x._sigKey || ''));
                      if (rkey) { dead[rkey] = true; deadKeys.push(rkey); /* delSigs[rkey] = true; */ }
                      // 仅抽取 Java 侧 collectDeleteKeys 所需的纯数据字段（不含 _el/DOM，JSON 安全）。
                      // 注意：collectDeleteKeys 只用 strategy/_sig/_sigKey/_pageClass/role/key/name/index，
                      // 故不再冗余携带 id/css 等未参与去重键计算的字段。
                      delPicks.push({
                        strategy: x.strategy,
                        _sig: rsig || x._sig,
                        _sigKey: rkey || x._sigKey,
                        _pageClass: x._pageClass,
                        role: x.role,
                        key: x.key,
                        name: x.name,
                        index: x.index,
                        // 【修复"删除所有元素后页面类残留"】collectDeleteKeys 对 id/css 型元素要用
                        // locatorKey(=strategy+selector) 命中 Java 权威内存态 key；此前 delPick 精简对象
                        // 缺 selector/resolvedKey，Java 侧算出的 locatorKey 为空 → 这类元素删除 miss。
                        // 这里补上 selector / resolvedKey / tag / text，保证删除侧与入库侧 key 计算一致。
                        selector: x.selector,
                        // 同步透传 css 字段：[name=]/[type=]/历史 css 候选的拾取对象里定位值存于 css 字段，
                        // collectDeleteKeys 的 locatorKey 主路径经 buildSelector 读 m.get("css") 计算权威内存态 key，
                        // 透传后可直接命中，无需单一依赖 _sig/_sigKey 兜底（与拾取侧完全对称）。
                        css: x.css,
                        resolvedKey: x.resolvedKey,
                        tag: x.tag,
                        text: x.text
                      });
                    });
                    // 从拾取数组移除所有选中项（按 sigKey/sig 精确匹配）
                    window.__rolePicks = (window.__rolePicks || []).filter(function(x) {
                      if (!x) return false;
                      return !(dead[x._sigKey] || dead[x._sig]);
                    });
                    // 同步清除去重登记表中对应的键，否则该元素被视为"已存在"，
                    // 之后重新点选同一元素时会因命中 __rolePickSigs 而被当作重复丢弃，再也拾不回来。
                    try {
                      var sigs = window.__rolePickSigs || {};
                      deadKeys.forEach(function(k) { if (k) delete sigs[k]; });
                      window.__rolePickSigs = sigs;
                    } catch (e2) {}
                    // 【关键】同步清理已封装的 step 中对该元素的引用。
                    // 页面类字段来自 picks，而 step 代码来自 window.__steps —— 两者是独立数据源。
                    // 只删 picks 的话，step 里仍留着这个"幽灵 pick"：生成时页面类已无对应字段，
                    // 代码生成侧靠 field==null 静默 continue 跳过，于是编译虽能通过，
                    // 但该动作会凭空消失（且弹窗元素还会连带丢掉 closeCurrentPage 闭环），排查极困难。
                    // 故在源头把它从每个 step 的 picks 里摘掉，并移除因此变空的 step，保持两侧一致。
                    try {
                      var _steps = window.__steps || [];
                      var _kept = [];
                      _steps.forEach(function(s) {
                        // 页面级操作条目（如 {op:'close'}）不含 picks，原样保留。
                        if (!s || typeof s !== 'object' || typeof s.op === 'string') { _kept.push(s); return; }
                        var ps = (s.picks || []).filter(function(p) {
                          if (!p) return false;
                          // 优先按 __mergeKey（去索引稳定键，与删除主逻辑 dead 集合一致）判定是否在删除集，
                          // 同时兜底兼容老格式含索引的 _sig/_sigKey，避免 i18n 等定位器策略因 #index 变化清不掉。
                          // 只按 __mergeKey（含 pageClass）判定是否在删除集，杜绝跨页同名裸 _sig 误删。
                          var mk = (typeof window.__mergeKey === 'function') ? window.__mergeKey(p) : null;
                          return !((mk && dead[mk]) || (p._sigKey && dead[p._sigKey]));
                        });
                        // picks 被删空的 step 整条丢弃，避免生成出一个没有任何语句的空 @Step 方法。
                        if (!ps.length) return;
                        s.picks = ps;
                        _kept.push(s);
                      });
                      window.__steps = _kept;
                    } catch (e6) {}
                    // 【关键】同步删除 Java 权威内存态：主循环每轮空闲都会把 javaPickBySig 整体 merge
                    // 回 window.__rolePicks，不通知 Java 的话被删元素约 1s 后就会"复活"（删除看似无效），
                    // 且代码生成读的正是 javaPickBySig。exposeBinding 与 console 兜底双通道上报（幂等）。
                    try {
                      // 上报完整 pick 对象（delPicks）而非仅 key 数组，供 Java 侧用 pickDedupKey 精确命中删除；
                      // 旧通道的 deadKeys 仅保留用于下方本地 __rolePicks / __rolePickSigs 的过滤。
                      var payload = JSON.stringify(delPicks);
                      if (typeof window.__roleOnDelete === 'function') window.__roleOnDelete(payload);
                      try { console.log('__roleOnDelete::' + payload); } catch (e4) {}
                    } catch (e3) {}
                    // 清空选择集并重新渲染（含子 Tab 计数同步）
                    window.__currentStep = [];
                    // 清除区域选择遗留的页面高亮框，删除元素后页面上的绿框/青框一并清掉。
                    try { if (typeof window.__clearRegionOutlines === 'function') window.__clearRegionOutlines(); } catch (e) {}
                    // 落盘最新态，避免整页跳转时被 localStorage 里的旧快照把已删元素恢复回来。
                    // 用 window.__persistPickState（面板脚本可见的公开 API）；__persistNow 是拾取脚本内的
                    // 局部函数，不在本脚本作用域内，直接调用取不到。
                    // 必须放在 __currentStep 清空【之后】，否则会把已删元素当作"选择集"一并写进快照。
                    try { if (typeof window.__persistPickState === 'function') window.__persistPickState(); } catch (e5) {}
                    window.__renderPicks();
                    // 【关键】「页面类」「步骤代码」两个 Tab 里的内容是生成时一次性写入 textarea 的
                    // 静态文本快照，不会跟随数据变化。仅删数据的话，已生成代码里该元素的字段声明与
                    // step 引用依然原样显示着（用户看到的就是"删了但代码没变"）。
                    // 故通知 Java 按最新状态重算并回填两个 Tab，使变量与引用一并消失。
                    try { pushCmd('refreshCode'); } catch (e7) {}
                  };
                  delBtn.onmouseenter = function() { if (!delBtn.disabled) delBtn.style.filter = 'brightness(1.12)'; };
                  delBtn.onmouseleave = function() { delBtn.style.filter = 'none'; };
                  // selBar 持久复用：先移除上次渲染留下的同名按钮，再追加新按钮，避免每次重渲染堆积多个删除按钮。
                  var _oldDel = document.getElementById('__roleDelBtn');
                  if (_oldDel) _oldDel.remove();
                  selBar.appendChild(delBtn);
                  // 把"全选"工具栏从候选列表内部移出，作为 subTabBar（按页分组的子 Tab 行）正下方
                  // 的兄弟元素，使"全选"这一行与子 Tab 行紧挨着。
                  try { pageContent.insertBefore(selBar, listEl); } catch (e) { listEl.appendChild(selBar); }
                  function refreshSelInfo() {
                    var vis = curVisiblePicks();
                    var sel = 0;
                    var cset = window.__currentStep || [];
                    function __mkey(q){ return (typeof window.__mergeKey==='function') ? window.__mergeKey(q) : (q && (q._sigKey || q._sig)); }
                    for (var vi = 0; vi < vis.length; vi++) {
                      var s = __mkey(vis[vi]);
                      if (s && cset.some(function(x){ return __mkey(x) === s; })) sel++;
                    }
                    selInfo.textContent = '已选 ' + sel + ' / ' + vis.length;
                    syncDelBtn();   // 选中数量变化后同步垃圾桶按钮高亮
                  }
                  var selSet = {};
                  (window.__currentStep || []).forEach(function(p) {
                    var k = (typeof window.__mergeKey==='function') ? window.__mergeKey(p) : (p && (p._sigKey || p._sig)); if (k) selSet[k] = true;
                  });
                  for (var i2 = 0; i2 < picks.length; i2++) {
                    let p = picks[i2] || {};
                    var pc = (p._pageClass) || (window.__rolePageName || '未知页');
                    if (pc !== act) continue;
                    var sk = (typeof window.__mergeKey==='function') ? window.__mergeKey(p) : (p._sigKey || p._sig);
                    var sel = !!(sk && selSet[sk]);
                    var row = document.createElement('label');
                    row.style.cssText = 'display:flex;gap:6px;align-items:flex-start;padding:3px 4px;cursor:pointer;' +
                      (sel ? 'background:rgba(30,136,229,.18);' : '') + 'border-bottom:1px solid #111;';
                    var cb = document.createElement('input');
                    cb.type = 'checkbox'; cb.checked = sel;
                    // checkbox 禁用逻辑：手动拾取的元素（_manualPick=true）或已有序号的元素，checkbox 禁用
                    // 扫描元素（无 _pickNos 且 _manualPick!=true）：checkbox 可用，勾选时分配序号
                    var _cbDisabled = p._manualPick === true || (Array.isArray(p._pickNos) && p._pickNos.length > 0);
                    cb.disabled = _cbDisabled;
                    cb.style.cssText = 'margin-top:3px;flex:0 0 auto;' + (_cbDisabled ? 'opacity:0.5;cursor:not-allowed;' : '');
                    // 关键修复：cb/row 均为 var 函数作用域的循环共享变量，若闭包直接引用外层 cb/row，
                    // 等 onchange 真正触发时它们已指向【最后一次】迭代创建的元素，导致读到的 checked 状态
                    // 错乱、勾选不生效（表现为"勾选不了"）。故用 IIFE 参数按迭代捕获当前 cb/row/pk，
                    // 并改为【直接更新该行高亮】，不再全量重建列表（消除闪烁与 O(n) 重建竞态）。
                    (function(pk, checkbox, rowEl) {
                      checkbox.onchange = function(e) {
                        // 阻断冒泡：勾选只做 O(1) 增量更新（仅该行高亮 + 选择集维护），
                        // 不冒泡到 document 的拾取监听链路，避免意外的 tab 切换/卡顿。
                        if (e && e.stopPropagation) e.stopPropagation();
                        window.__currentStep = window.__currentStep || [];
                        // 【关键】用全局健壮键，避免 pk 签名字段缺失时 s 为空、直接 return 导致勾选不生效
                        //（即"选中了却没进选择集、封装 step 时 SelSet 为空 → 生成 0 条"）。
                        var s = (typeof window.__mergeKey==='function') ? window.__mergeKey(pk) : (pk && (pk._sigKey || pk._sig));
                        if (!s) return;
                        var idx = -1;
                        for (var j = 0; j < window.__currentStep.length; j++) {
                          var e = window.__currentStep[j];
                          var ek = (typeof window.__mergeKey==='function') ? window.__mergeKey(e) : (e && (e._sigKey || e._sig));
                          if (ek === s) { idx = j; break; }
                        }
                        if (checkbox.checked) {
                          // 勾选：分配序号（全局拾取序号 +1）
                          if (idx < 0) window.__currentStep.push(pk);
                          // 如果该元素还没有 _pickNos，分配全局序号
                          if (!Array.isArray(pk._pickNos) || pk._pickNos.length === 0) {
                            window.__rolePickSeq = (window.__rolePickSeq || 0) + 1;
                            pk._pickNos = [window.__rolePickSeq];
                            pk._pickSeq = window.__rolePickSeq;
                          }
                          // 标记为手动拾取，取消勾选后 checkbox 仍保持禁用
                          pk._manualPick = true;
                          rowEl.style.background = 'rgba(30,136,229,.18)';
                        } else {
                          // 取消勾选：重置该元素序号为 [-]，其他元素序号重新排序
                          if (idx >= 0) window.__currentStep.splice(idx, 1);
                          // 重置该元素的 _pickNos 为空（但 _manualPick 保持 true，checkbox 继续禁用）
                          pk._pickNos = [];
                          pk._pickSeq = 0;
                          pk._seqStale = true;
                          rowEl.style.background = '';
                          // 其他已选元素重新分配连续序号
                          for (var k = 0; k < window.__currentStep.length; k++) {
                            var selPick = window.__currentStep[k];
                            if (selPick && Array.isArray(selPick._pickNos) && selPick._pickNos.length > 0) {
                              selPick._pickNos = [k + 1];
                              selPick._pickSeq = k + 1;
                            }
                          }
                          // 更新全局计数器
                          window.__rolePickSeq = window.__currentStep.length;
                        }
                        // 选中变化后同步「已选计数」与垃圾桶按钮高亮（轻量，仅刷新工具栏）
                        if (typeof refreshSelInfo === 'function') refreshSelInfo();
                        // 刷新所有行的步骤序号显示
                        try { if (typeof window.__renderPicks === 'function') window.__renderPicks(); } catch (_) {}
                      };
                    })(p, cb, row);
                    var txt = document.createElement('span');
                    // 序号前缀：把【全局拾取顺序号】_pickNos 渲染为一组独立可点击超链接。
                    // 语义：每次拾取动作（含重复点同一元素）全局序号 +1；同一元素多次点击会累加多个号，
                    // 如 [1,4,7]。每个号渲染为 <a>，鼠标 hover 显示删除线，点击即【删除该序号】并全局重编号。
                    // 需求：同一元素多次点击 → 序号列表 [1,2,3]，每个号可删；删后其余号紧凑重排，step 同步重排。
                    var _seqNos = [];
                    if (Array.isArray(p._pickNos) && p._pickNos.length) {
                      _seqNos = p._pickNos.slice();
                    } else if (typeof p._pickSeq === 'number' && p._pickSeq > 0) {
                      _seqNos = [p._pickSeq];            // 单值兜底
                    } else if (!p._seqStale) {
                      // 兜底：个别回灌 pick 无序号时，按其在 __rolePicks 中的位次推导首号。
                      var _all = window.__rolePicks || [];
                      for (var _ai = 0; _ai < _all.length; _ai++) { if (_all[_ai] === p) { _seqNos = [_ai + 1]; break; } }
                    }
                    // 构建序号前缀：整体包在一个方括号内、序号用逗号分隔、末尾「+」加号也在括号内，
                    // 形如 [1,2,7,+]。每个序号与加号均为独立超链接（hover 删除线、点击删除/新增）。
                    var _prefixWrap = document.createElement('span');
                    _prefixWrap.style.cssText = 'margin-right:2px;white-space:nowrap;font-family:monospace;';
                    if (_seqNos.length) {
                      _prefixWrap.appendChild(document.createTextNode('['));
                      _seqNos.forEach(function(_n, _idx) {
                        var _a = document.createElement('a');
                        _a.textContent = '' + _n;          // 仅数字，逗号由下方文本节点提供
                        _a.href = 'javascript:void(0)';
                        _a.title = '点击删除该拾取序号（' + _n + '），其余序号自动重排';
                        _a.style.cssText = 'color:#1565c0;text-decoration:none;cursor:pointer;';
                        // 【删除线显式红色】hover 时文本+删除线统一设为红色警示，避免继承浏览器默认 link 色
                        // （曾因未显式控制而呈现不一致/意外的红色）。红色 line-through 明确暗示"点击将删除该序号"。
                        _a.addEventListener('mouseenter', function() { _a.style.color = '#ff4d4f'; _a.style.textDecoration = 'line-through'; });
                        _a.addEventListener('mouseleave', function() { _a.style.color = '#1565c0'; _a.style.textDecoration = 'none'; });
                        _a.addEventListener('click', function(ev) {
                          ev.preventDefault();
                          ev.stopPropagation();
                          window.__deletePickNo(p, _n);
                          return false;
                        });
                        _prefixWrap.appendChild(_a);
                        if (_idx < _seqNos.length - 1) _prefixWrap.appendChild(document.createTextNode(','));
                      });
                      // 末尾「+」加号：点击给该元素追加一个拾取序号（等价于在页面再点一次该元素）。
                      var _plus = document.createElement('a');
                      _plus.textContent = '+';
                      _plus.href = 'javascript:void(0)';
                      _plus.title = '点击给该元素新增一个拾取序号（追加到末尾，生成步骤 +1）';
                      _plus.style.cssText = 'color:#2e7d32;font-weight:bold;text-decoration:none;cursor:pointer;';
                      // 【加号不要删除线】加号是"新增序号"语义，与序号（删除语义）不同，hover 仅高亮加粗、不显示删除线。
                      _plus.addEventListener('mouseenter', function() { _plus.style.color = '#1b5e20'; _plus.style.fontWeight = 'bold'; });
                      _plus.addEventListener('mouseleave', function() { _plus.style.color = '#2e7d32'; _plus.style.fontWeight = 'bold'; });
                      _plus.addEventListener('click', function(ev) {
                        ev.preventDefault();
                        ev.stopPropagation();
                        window.__addPickNo(p);
                        return false;
                      });
                      _prefixWrap.appendChild(document.createTextNode(','));
                      _prefixWrap.appendChild(_plus);
                      _prefixWrap.appendChild(document.createTextNode(']'));
                    } else {
                      // 未分配序号时显示 [-,+]，加号可点击新增序号
                      _prefixWrap.appendChild(document.createTextNode('['));
                      var _dash2 = document.createElement('span');
                      _dash2.textContent = '-';
                      _dash2.style.cssText = 'margin:0 1px;color:#999;';
                      _prefixWrap.appendChild(_dash2);
                      var _plus2 = document.createElement('a');
                      _plus2.textContent = '+';
                      _plus2.href = 'javascript:void(0)';
                      _plus2.title = '点击给该元素新增一个拾取序号';
                      _plus2.style.cssText = 'color:#2e7d32;font-weight:bold;text-decoration:none;cursor:pointer;';
                      _plus2.addEventListener('mouseenter', function() { _plus2.style.color = '#1b5e20'; });
                      _plus2.addEventListener('mouseleave', function() { _plus2.style.color = '#2e7d32'; });
                      _plus2.addEventListener('click', function(ev) {
                        ev.preventDefault();
                        ev.stopPropagation();
                        window.__addPickNo(p);
                        return false;
                      });
                      _prefixWrap.appendChild(document.createTextNode(','));
                      _prefixWrap.appendChild(_plus2);
                      _prefixWrap.appendChild(document.createTextNode(']'));
                    }
                    var s = (p.strategy || 'role');
                    if (p.role) s += ' role=' + p.role;
                    if (p.name) s += ' name="' + p.name + '"';
                    if (p.key) s += ' key=' + p.key;
                    if (p.id) s += ' id=' + p.id;
                    if (p.css) s += ' css=' + p.css;
                    if (p.index != null && p.index >= 0) s += ' #' + p.index;
                    if (p.popup) s += ' [popup]';
                    if (p.download) s += ' [download]';
                    if (p.hover) s += ' [hover]';
                    if (p.dblClick) s += ' [dbl]';
                    // 【面板"被点多次"计数显示】同一元素被点击多次时 clickCount>1，在面板显示"点N次"，
                    // 让用户在拾取过程中直观看到该元素被点了几次（如 checkbox 反复点、同一按钮多次点击）。
                    // 注意：拾取阶段的重复点击在 recordPick 内部按 sig 去重复用同一条 pick（clickCount 累加），
                    // 不新增行，故此处仅以标记呈现次数，不重复罗列元素。
                    if (p.clickCount && p.clickCount > 1) s += ' [点' + p.clickCount + '次]';
                    txt.textContent = s;
                    txt.style.cssText = 'flex:1;white-space:pre-wrap;word-break:break-all;';
                    row.appendChild(cb); row.appendChild(_prefixWrap); row.appendChild(txt);
                    // 每行末尾的垃圾箱图标：点击删除该元素（完整删除，非仅删序号），
                    // 并从 window.__rolePicks / __currentStep / __steps 及 Java 权威内存态中彻底移除，
                    // 剩余元素自动重编号，代码同步刷新。与"序号超链接删除"不同——后者只删一个点击号。
                    // 【修复"hover垃圾桶变红错乱"】使用 let 声明 _trash，确保每次循环创建独立的块级作用域，
                    // 避免所有事件监听器共享同一个 _trash 变量（var 是函数作用域，会导致闭包捕获最后一个元素）。
                    let _trash = document.createElement('a');
                    _trash.innerHTML = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" ' +
                      'stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
                      '<path d="M3 6h18"/><path d="M8 6V4h8v2"/><path d="M19 6l-1 14H6L5 6"/>' +
                      '<path d="M10 11v5M14 11v5"/></svg>';
                    _trash.href = 'javascript:void(0)';
                    _trash.title = '删除该元素（从所有拾取、步骤和代码中移除）';
                    _trash.style.cssText = 'flex:0 0 auto;color:#888;text-decoration:none;cursor:pointer;' +
                      'padding:2px 4px;margin-left:auto;border-radius:4px;transition:all .15s;';
                    _trash.addEventListener('mouseenter', function() { _trash.style.color = '#ff4d4f'; _trash.style.background = 'rgba(244,67,58,.12)'; });
                    _trash.addEventListener('mouseleave', function() { _trash.style.color = '#888'; _trash.style.background = 'transparent'; });
                    _trash.addEventListener('click', function(ev) {
                      ev.preventDefault();
                      ev.stopPropagation();
                      window.__deleteSinglePick(p);
                      return false;
                    });
                    row.appendChild(_trash);
                    // 记录复选框与签名引用，供 __applySelection 增量刷新（不重建列表）。
                    row.__cb = cb; row.__sig = sk;
                    listEl.appendChild(row);
                  }
                  // 绑定"全选"复选框：勾选即对当前可见范围全选，取消勾选即全不选；复选框状态与计数保持同步。
                  try {
                    function applySelectAll(on) {
                      var vis = curVisiblePicks();
                      if (on) {
                        window.__currentStep = window.__currentStep || [];
                        for (var vi = 0; vi < vis.length; vi++) {
                          var pk = vis[vi]; if (!pk) continue;
                          var s = (typeof window.__mergeKey==='function') ? window.__mergeKey(pk) : (pk._sigKey || pk._sig); if (!s) continue;
                          if (!window.__currentStep.some(function(x){ return ((typeof window.__mergeKey==='function')?window.__mergeKey(x):(x&&(x._sigKey||x._sig))) === s; })) window.__currentStep.push(pk);
                        }
                      } else {
                        var visSigs = {};
                        vis.forEach(function(pk) { if (pk) { var s = (typeof window.__mergeKey==='function')?window.__mergeKey(pk):(pk._sigKey||pk._sig); if (s) visSigs[s] = true; } });
                        window.__currentStep = (window.__currentStep || []).filter(function(x) { var sk=(typeof window.__mergeKey==='function')?window.__mergeKey(x):(x&&(x._sigKey||x._sig)); return !(sk && visSigs[sk]); });
                      }
                      // 全选/全不选后重排连续编号。
                      window.__renumberStep();
                    }
                    selAllCb.onclick = function(e) {
                      e && e.stopPropagation && e.stopPropagation();
                      applySelectAll(selAllCb.checked);
                      window.__renderPicks();
                    };
                    // 计数刷新时同步"全选"复选框状态：可见项全部选中 -> 勾选，否则不勾选。
                    var _refreshSelInfo = refreshSelInfo;
                    window.refreshSelInfo = refreshSelInfo = function() {
                      var vis = curVisiblePicks();
                      var cset = window.__currentStep || [];
                      var sel = 0;
                      // 统一用 __mergeKey 计算勾选键（与 onchange / applySelectAll / __packageStep 完全一致）：
                      // 旧实现用 vis[vi]._sigKey || vis[vi]._sig，若面板 picks 对象未固化这些字段则 s 恒为空，
                      // 导致"已选计数"永远显示 0、全选框永远不勾选（用户误以为 i18n/无 _sigKey 元素没被选中）。
                      function __mkey2(q){ return (typeof window.__mergeKey==='function') ? window.__mergeKey(q) : (q && (q._sigKey || q._sig)); }
                      for (var vi = 0; vi < vis.length; vi++) {
                        var s = __mkey2(vis[vi]);
                        if (s && cset.some(function(x){ return __mkey2(x) === s; })) sel++;
                      }
                      selInfo.textContent = '已选 ' + sel + ' / ' + vis.length;
                      // 【关键修复"删除全部后全选 checkbox 未复位"】
                      // 无可见元素时（如删除全部选中项后 __rolePicks 被清空），全选框应【禁用且未勾选】，
                      // 而不是保持可勾选/勾选状态；否则用户看到的是"还能全选、已选未清空"的错觉。
                      // 有元素时恢复可用，并仅在"可见项全部已选"时勾选。
                      if (vis.length === 0) {
                        selAllCb.disabled = true;
                        selAllCb.checked = false;
                      } else {
                        selAllCb.disabled = false;
                        selAllCb.checked = (sel === vis.length);
                      }
                    };
                    refreshSelInfo();
                  } catch (e) {}
                } catch (e) {
                  try { var le = document.getElementById('__rolePickList'); if (le) le.textContent = '（列表渲染失败）'; } catch (_) {}
                }
              }
              window.__renderScheduled = false;
              window.__renderPicks();
              // 暴露幂等"确保面板存在"入口：供门控脚本在 SPA 路由切换（pushState/hashchange 等）自愈时调用——
              // 框架重渲染 document 子树可能把 docked 面板 DOM 冲掉，此时仅重建面板并立即重渲染已拾元素
              // （已拾元素存于 window.__rolePicks，不随 DOM 重建丢失）。build() 内部已对"已存在面板"做 remove 再重建，故幂等。
              window.__roleEnsurePanel = function() {
                try {
                  if (!document.getElementById('__rolePanel')) { build(); }
                  if (window.__renderPicks) window.__renderPicks();
                } catch (e) {}
              };

              showTab();
              (document.body || document.documentElement).appendChild(panel);
      }
      // Playwright addInitScript 在每个新文档解析早期执行，body 可能尚未就绪；
      // 若挂载点（body/documentElement）还未出现则短轮询重试，确保 appendChild 可靠。
      (function waitMount() {
        // build() 需要 document.body 才能安全设置 docked 布局（document.body.style）；
        // addInitScript/onPopup 注入时新文档可能尚未解析出 body，故必须等到 body 就绪再构建。
        if (!document.body) { setTimeout(waitMount, 10); return; }
        build();
      })();
    })();