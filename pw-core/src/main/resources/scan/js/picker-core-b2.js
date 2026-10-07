
                // 状态外置（对齐 page.pause）：去掉"每次点击全量序列化 localStorage"这一 O(n) 瓶颈，
                // 点击延迟不再随已拾元素增多而变慢。整页跳转前的最后点击由 pagehide/beforeunload 的
                // __persistNow 同步即时落盘兜底（见 __persistPickState 定义），不丢失。
                // —— UI 反馈 + Java 回传 ——（扫描机制已移除：原本该段由 if (!window.__scanning) 包裹）
                  var prev = t.style.outline;
                  t.style.outline = dup ? '3px solid #ff9800' : (isHover ? '3px solid #29b6f6' : '3px solid #ffeb3b');
                  // 400ms 后直接清除高亮（而非恢复 prev）：避免快速重复点选同一元素时，
                  // 第二次捕获到的 prev 已是上一次设置的黄色框，setTimeout 又把黄框"恢复"回来，
                  // 造成"选择/封装完成后页面元素上的黄色框始终不消失"的残留问题。
                  setTimeout(function() { try { t.style.outline = ''; t.style.outlineOffset = ''; } catch (e) {} }, 400);
                  var statusEl = document.getElementById('__roleStatus');
                  if (statusEl) {
                    // 【按需求移除状态栏的 key= 展示】此前命中 NLS key 时状态栏会显示"（key=xxx）"；
                    // 现在只保留"（重复，已忽略）"，避免与"没传 NLS 却出现 key"的观感混淆。
                    var extra = dup ? '（重复，已忽略）' : '';
                    if (isHover) extra = '（悬停）' + extra;
                    var stepNo = (window.__steps ? window.__steps.length : 0) + 1;
                    statusEl.textContent =
                      'RoleElement Picker：已拾取 ' + window.__rolePicks.length + ' 个 / 第 ' + stepNo + ' 个 step' + extra + '，按 ESC 结束';
                  }
                  if (window.__renderPicks) window.__renderPicks();
                  // 状态外置（对齐 page.pause）：把单个 pick 经 exposeFunction 事件驱动、零往返、O(1) 回传 Java 内存，
                  // 由 Java 侧持有权威拾取态（javaPickBySig），点击不再依赖浏览器端大数组的全量序列化/持久化。
                  //
                  // 【关键修复"dup 分支完整 _pickNos 被外层兜底旧值/null 覆盖"】
                  // 当 dup=true 且 existing 存在时，B1 里的 dup 分支（lines 3085-3102）已经：
                  //   ① 调用 __appendPickNo(existing) 把新序号追加到 existing._pickNos
                  //   ② 构造 __wire = __pickToWire(existing)（含最新完整 pickNos）
                  //   ③ 通过 BIND + console 双保险把 __wire 发送给 Java
                  // 若此处【不分 dup/非 dup】又再发送一次 pick（新构造的 pick 对象，_pickNos 为空/旧值），
                  // 则 Java 侧 pickMoreComplete 可能收到短值，覆盖 dup 分支刚写入的完整值（即使有并集保护，
                  // 并发到达顺序也可能让短值后写入、在 Java 侧 merge 时被误判）。
                  // 因此：当 dup=true 且 existing 有效时，此处【跳过回传】—— dup 分支已回传过正确、完整的值。
                  // 非 dup（全新元素）时，改用 __pickToWire 序列化，确保 _pickNos / _pickSeq 等私有字段必被携带。
                  var __isDupWithExisting = (dup && existing);
                  if (!__isDupWithExisting && typeof window.__roleOnPick === 'function') {
                    try {
                      // 附带浏览器端去重键 __sigKey，使 Java 内存去重粒度与浏览器 __rolePickSigs 完全一致。
                      if (typeof window.__sigKey === 'function') pick._sigKey = window.__sigKey(pick);
                      var __outerWire = (typeof __pickToWire === 'function') ? __pickToWire(pick) : pick;
                      window.__roleOnPick(JSON.stringify(__outerWire));
                    } catch (e) { try { console.error('[rolePick][onPick-expose-fail] ' + (e && e.message)); } catch(_){} }
                  }
                  // 控制台兜底回传：即使 exposeFunction 因导航/上下文异常失效，Java 的 onConsoleMessage 仍能捕获并落盘
                  // （按 sig 去重，与 exposeFunction 调用幂等）。同时便于排查"二次拾取没反应"。
                  // 同 BIND 一样：dup 分支已发过 __wire（完整 pickNos）→ 此处跳过，避免覆盖。
                  if (!__isDupWithExisting) {
                    try {
                      var __outerWire2 = (typeof __pickToWire === 'function') ? __pickToWire(pick) : pick;
                      console.log('__roleOnPick::' + JSON.stringify(__outerWire2));
                    } catch(_){}
                  }
                  // 跨 frame 同步：点击若发生在 iframe 内，顶层主框架可见面板读的是主框架 window.__rolePicks，
                  // 而本 frame 把 pick push 进了自己的 window.__rolePicks（主框架读不到），表现为"内存态增长、面板空白"。
                  // 故把 pick postMessage 给顶层窗口（window.top），由顶层面板监听聚合进其 window.__rolePicks 并渲染。
                  // 注意必须用 window.top 而非 window.parent：中间层 iframe 自身没有 message 监听器（面板仅顶层构建），
                  // 发给 window.parent（而非 window.top）：由每一层 frame 的消息监听逐层向父转发（见 message 监听的
                  // 向上中继逻辑），即使跨多级嵌套 / Playwright 下 window.top 直达投递异常，pick 也能可靠上送顶层。
                  // 纯前端同步，不依赖 Java 主循环轮询（postMessage 不受同源限制，跨源 iframe 同样生效）。
                  if (window.self !== window.top) {
                    // 【关键修复"嵌套 iframe 通信"】
                    // 原实现只发 window.parent、依赖每层 frame 的 message 监听逐层向上转发到顶层。
                    // 但中间层 iframe（frameOne）的 message 监听可能因 PANEL_SCRIPT 门禁/注入时机未注册，
                    // 导致 frameTwo（grandchild）的 pick 停在中间层、顶层 __currentStep 收不到（面板 '-'）。
                    // 改为"直达 + 逐层"双保险：
                    //   · 直达：window.top.postMessage 直接投递顶层（postMessage 不受同源限制；顶层监听有
                    //     __rolePickSigs 按 sigKey 去重，重复投递幂等）。
                    //   · 回退：若 window.top 跨域/受限抛异常，再发 window.parent 让已注册监听的中间层转发。
                    var __sent = false;
                    try { if (window.top && window.top !== window.self) { window.top.postMessage({ __rolePickMsg: true, pick: pick, __fromFrame: true }, '*'); __sent = true; } } catch (e) {}
                    if (!__sent && window.parent && window.parent !== window.self) {
                      try { window.parent.postMessage({ __rolePickMsg: true, pick: pick, __fromFrame: true }, '*'); } catch (e2) {}
                    }
                  }
                return pick;
              };
              // 悬停高亮 + 悬停拾取（hover）模式：开启后鼠标停在元素上约 0.45s 即记录 hover 动作。
              window.__rolePickMove = function(ev) {
                if (!window.__rolePickActive) {
                  if (window.__hoverRaf) { try { cancelAnimationFrame(window.__hoverRaf); } catch(e){} window.__hoverRaf = null; }
                  __showHoverBox(null); window.__lastHoverTarget = null; return;
                }
                var t = ev.target;
                if (t && t.closest && t.closest('#__rolePanel, #__roleCodeOverlay')) {
                  __showHoverBox(null); window.__lastHoverTarget = null; return;
                }
                // 性能：合并相邻 mousemove 到下一帧，且仅在目标元素变化时重排，
                // 避免每像素移动都触发 getBoundingClientRect + style 强制回流（整页卡顿的根因）。
                if (window.__lastHoverTarget === t) return;
                window.__lastHoverTarget = t;
                if (window.__hoverRaf) return;
                window.__hoverRaf = requestAnimationFrame(function() {
                  window.__hoverRaf = null;
                  if (window.__rolePickActive) __showHoverBox(window.__lastHoverTarget);
                });
                if (window.__roleHoverMode) {
                  if (t !== __hoverTarget) {
                    __hoverTarget = t;
                    if (__hoverTimer) { clearTimeout(__hoverTimer); __hoverTimer = null; }
                    __hoverTimer = setTimeout(function() {
                      if (window.__rolePickActive && t && t.closest
                          && !t.closest('#__rolePanel, #__roleCodeOverlay')) {
                        window.__recordPick(t, true);
                      }
                    }, 450);
                  }
                }
              };
              // 【关键修复"鼠标 hover 到 frame 后失焦，hover 高亮框未去除"】
              // 每个 frame 各自的 __rolePickMove 维护各自的 __roleHoverBox；鼠标从 iframe 移出到主框架时，
              // iframe 的 mousemove 不再触发，iframe 的 __roleHoverBox 保持显示（残留青色边框）。
              // 用 document 级 mouseleave（仅在鼠标真正离开该 frame 的 document 边界时触发，不冒泡、
              // 同一 document 内移动不会误触发）在鼠标离开该 frame 时清除其 hover box 与 hover 状态。
              window.__rolePickLeave = function() {
                try {
                  if (window.__hoverRaf) { cancelAnimationFrame(window.__hoverRaf); window.__hoverRaf = null; }
                } catch (e) {}
                try { if (window.__hoverTimer) { clearTimeout(window.__hoverTimer); window.__hoverTimer = null; } } catch (e) {}
                try { window.__hoverTarget = null; } catch (e) {}
                try { window.__lastHoverTarget = null; } catch (e) {}
                try {
                  var hb = document.getElementById('__roleHoverBox');
                  if (hb) hb.style.display = 'none';
                } catch (e) {}
              };
              document.addEventListener('mouseleave', window.__rolePickLeave, false);
              // ===== 原生对话框（alert/confirm/prompt）拦截 =====
              // 对齐 page.pause() 的 dialog 信号：在点击触发业务（业务可能调用 alert/confirm）时捕获。
              // 记录 {type, message, seq} 到 window.__pwDialogs；点击 handler 在宏任务里回查，
              // 若本次点击后产生了 dialog，则给对应 pick 打 dialog 标记并重传（覆盖内存态），
              // 生成 step 时前置插桩 page.onDialog(...)。
              window.__pwDialogs = [];
              window.__pwDlgSeq = 0;
              (function() {
                // 对话框真正产生的时刻，把"最近一次拾取的元素"打上 dialog 标记并重传 Java 内存态。
                // 不依赖点击 handler 的 setTimeout(0) 回查（当业务里 alert 是 setTimeout 异步触发时，
                // 0ms 回查会早于 dialog 产生而漏判）；此处 dialog 本体已存在，标记必然命中。
                // 即便 Java 侧 page.onDialog 因跨 page/iframe 实例未触发，内存态（javaPickBySig）也能拿到标记，
                // 生成 step 时前置插桩 acceptAlert/dismissAlert（与 page.onDialog 双保险、幂等）。
                function markLastPickDialog(type) {
                  try {
                    var sig = window.__lastPickSig || '';
                    if (!sig) return;
                    var pick = window.__sigToPick ? window.__sigToPick[sig] : null;
                    if (!pick) return;
                    // alert 默认 accept；confirm/prompt 默认 dismiss（与 Java onDialog 约定一致）
                    var action = (type === 'alert') ? 'accept' : 'dismiss';
                    pick.dialog = true;
                    pick.dialogType = type;
                    pick.dialogAction = action;
                    if (typeof window.__sigKey === 'function') pick._sigKey = window.__sigKey(pick);
                    if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(pick));
                    try { console.log('__roleOnPick::' + JSON.stringify(pick)); } catch (_) {}
                  } catch (e) {}
                }
                function hook(orig, type) {
                  return function(msg) {
                    try {
                      window.__pwDialogs.push({ type: type, message: (msg == null ? '' : String(msg)), seq: ++window.__pwDlgSeq });
                    } catch (e) {}
                    // 对话框产生的瞬间即给最近一次点击元素打 dialog 标记（双保险之一）
                    markLastPickDialog(type);
                    // 调用原始实现以维持页面既有行为（避免吞掉业务弹窗）
                    if (typeof orig === 'function') { return orig.apply(this, arguments); }
                  };
                }
                try { window.alert   = hook(window.alert,   'alert'); }   catch (e) {}
                try { window.confirm = hook(window.confirm, 'confirm'); } catch (e) {}
                try { window.prompt  = hook(window.prompt,  'prompt'); }  catch (e) {}
              })();
              document.addEventListener('mousemove', window.__rolePickMove, true);
              window.__rolePickClick = function(event) {
                // 诊断：记录最近一次点击到达 handler 的时间与当时激活态（供 Java 侧回读，确认点击是否被监听捕获）。
                window.__lastClickTs = Date.now();
                window.__lastClickActive = !!window.__rolePickActive;
                // 【模式闸门】IDLE（待命）态下点击页面不拾取任何元素——必须等用户点"▶ 开始拾取"进入 MANUAL，
                // 或点"扫描整页/区域扫描"进入对应扫描模式，点击/扫描才有意义。避免 stop 后再次进入 IDLE 时
                // 误拾取（日志里"点了 4 个却出 6 个"的诱因之一：拾取库仍注入、active 未随模式复位）。
                var _mode = window.__roleMode || 'idle';
                if (_mode === 'idle') return;
                // 对齐 page.pause()：用 composedPath()[0] 取代 event.target，穿透 open shadow DOM，
                // 点击 Web Component 内部按钮也能拿到真实目标元素（event.target 在 shadow 边界会停在宿主上）。
                var t = (typeof event.composedPath === 'function' && event.composedPath().length)
                  ? event.composedPath()[0] : event.target;
                if (t && t.closest && t.closest('#__rolePanel, #__roleCodeOverlay')) {
                  return;
                }
                // 抑制 <label> 隐式触发的关联控件 click：点击 <label for="x"> 时浏览器会先对 label
                // 派发 click（t=label → 记录为独立 label 拾取），再自动对关联 input 派发一次 click
                // （t=input）。若不抑制，面板会同时出现 label 与 input 两条，与"label 和 input 分开、
                // 各自只呈现一次"的期望冲突。此处：当本次 t 是表单控件且拥有关联 label，且该 label 在
                // 400ms 内刚被点击拾取过，则跳过本次 input 拾取（label 的独立拾取已保留）。
                if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT')) {
                  try {
                    var __lbls = (typeof t.labels === 'object' && t.labels) ? t.labels : [];
                    if (__lbls && __lbls.length && window.__lastLabelPickEl
                        && (Date.now() - (window.__lastLabelPickTs || 0)) < 400
                        && Array.prototype.indexOf.call(__lbls, window.__lastLabelPickEl) !== -1) {
                      return;
                    }
                  } catch (e) { /* labels 不可用时忽略 */ }
                }
                // 点击即用户意图：即使本次 click 即将触发 url 变化（SPA 路由/hash 切换/jumps 回上一页），
                // 导航的 onFrameNavigated→applyPickState 可能已将 __rolePickActive 置 false（竞态），
                // 导致本次 click 的拾取被 __recordPick 开头的 active 检查拦截而丢失。
                // 因此：若用户仍处于 MANUAL 拾取模式（只是导航瞬间被重置），临时恢复 active 让本次
                // click 正常记录；记录后还原（导航恢复逻辑会重新激活，无害）。这样"引起 url change 的
                // 元素"也能进面板，符合用户期望。
                var __wasActive = window.__rolePickActive;
                if (!__wasActive && (window.__roleMode === 'manual')) {
                  window.__rolePickActive = true;
                }
                var pick = window.__recordPick(t, false);
                window.__rolePickActive = __wasActive;
                if (!pick) return;
                // 记录"最近被拾取的 label"：供上方抑制逻辑识别 label 隐式触发的关联控件 click。
                if (t && t.tagName === 'LABEL') {
                  window.__lastLabelPickEl = t;
                  window.__lastLabelPickTs = Date.now();
                }
                // 记录点击前的 dialog 计数，供宏任务回查"本次点击是否触发了原生对话框"
                var dlgBefore = window.__pwDialogs ? window.__pwDialogs.length : 0;
                var clickPick = pick;
                // 对齐 page.pause()：拾取模式下【点击穿透】——元素的真实事件与默认行为照常触发
                // （button 的 onclick、链接跳转、表单提交都会真实发生），仅在此同步记录/定位该元素，
                // 不再用 preventDefault 吞掉元素的真实交互。曾经为"留在当前页连续拾取"而阻止 a[href]/form 的默认导航，
                // 但那样会吞掉点击（如点按钮却没触发其事件/业务），与 page.pause() 的"点击即真实交互 + 定位"不一致。
                // 跨页导航由 Java 侧 onFrameNavigated / onPopup（以及 SPA heal）接管，拾取状态不丢。
                // 仅按 codegen 需求给链接打 popup/download 标记（不阻止默认行为）。
                var aEl = t.closest ? t.closest('a[href]') : null;
                if (aEl) {
                  var tgt = aEl.getAttribute('target');
                  var opensNew = !!(tgt && tgt.charAt(0) === '_' && tgt.toLowerCase() !== '_self');   // _blank / _new ...
                  // 下载链接判定：带 download 属性，或 href 指向常见文件扩展名（.pdf/.xls/.csv...）。
                  // 不阻止默认行为，放行让浏览器真正发起下载；同时打 download 标记，
                  // 生成 step 时包装为 waitForDownload（对齐 page.pause() 的 codegen 输出）。
                  var href = aEl.getAttribute('href') || '';
                  var isDownloadLink = !!aEl.getAttribute('download')
                    || /\\.(pdf|zip|docx?|xlsx?|csv|pptx?|txt|json|xml|png|jpe?g|gif|exe|msi|tar|gz|rar|7z|mp3|mp4|mov)(\\?|#|$)/i.test(href);
                  if (isDownloadLink) {
                    if (pick) { pick.download = true; if (opensNew) pick.popup = true; }   // 弹窗 + 下载：嵌套 waitForDownload(waitForPopup)
                  } else if (opensNew && pick) {
                    // 标记"该点击会弹出新页面"：生成 step 时包装为
                    // page.waitForPopup(() -> element.click())（对齐 page.pause() 的 codegen 输出）。
                    pick.popup = true;
                  }
                  // 同标签页普通链接：放行真实导航（不再 preventDefault）
                }
                // 弹窗/下载标记：__recordPick 内部的回传（__roleOnPick / __roleOnPick::）发生在本 click handler
                // 设置 pick.popup / pick.download 之前（2121 行已 JSON.stringify 序列化，彼时两标记仍为 false），
                // 若不在此补发一次，Java 权威内存态 javaPickBySig 拿到的就是"无 popup/download 标记"的版本，
                // 生成 step 时就不会包 waitForNewPage / waitForDownload——表现为"waitForPopup 没起作用"。
                // 与 dialog 双保险对称：设置完标记后立刻按 _sigKey 覆盖重传，确保 stop 生成能拿到，
                // 即使 Java 侧 page.onPopup 因跨 page/iframe 实例未触发也不漏判（二者幂等）。
                if (pick && (pick.popup || pick.download)) {
                  try {
                    if (typeof window.__sigKey === 'function') pick._sigKey = window.__sigKey(pick);
                    if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(pick));
                    try { console.log('__roleOnPick::' + JSON.stringify(pick)); } catch (_) {}
                  } catch (e) {}
                }
                // 按钮/表单提交：放行真实提交（点击按钮会真实触发业务，业务可能调用 alert/confirm），
                // 不再 preventDefault。原生对话框在业务里同步弹出，故用宏任务回查本次点击是否触发了 dialog。
                // 对齐 page.pause()：dialog 作为该 action 的前置信号，生成 step 时前置插桩 page.onDialog(...)。
                try {
                  setTimeout(function() {
                    try {
                      if (!clickPick) return;
                      var after = window.__pwDialogs ? window.__pwDialogs.length : 0;
                      if (after > dlgBefore) {
                        // 取本次点击后新增的最后一个 dialog（即本次点击触发者）
                        var d = window.__pwDialogs[after - 1];
                        var type = d && d.type ? d.type : 'alert';
                        // 方案1：alert 默认 accept；confirm/prompt 默认 dismiss
                        var action = (type === 'alert') ? 'accept' : 'dismiss';
                        clickPick.dialog = true;
                        clickPick.dialogType = type;
                        clickPick.dialogAction = action;
                        // 按 _sigKey 覆盖内存态并重传，确保 stop 生成能拿到 dialog 标记
                        if (typeof window.__sigKey === 'function') clickPick._sigKey = window.__sigKey(clickPick);
                        if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(clickPick));
                        try { console.log('__roleOnPick::' + JSON.stringify(clickPick)); } catch (_) {}
                      }
                      // 复选/单选/开关：浏览器拾取模式下点击是"真实穿透"（同步阶段 __enrichState 读到的
                      // pick.checked 是【点击前】状态，但真实点击已使元素 toggle）。若按点击前状态生成
                      // setChecked(旧) 回放，会把元素设回原状态，表现为"checkbox 没选上"。
                      // 故在此宏任务（点击 toggle 已完成后）重新读取【点击后】状态作为 setCheckedTarget，
                      // 覆盖内存态并重传，使生成 setChecked(目标状态) 幂等正确勾选。
                      var pr = (clickPick.role || '').toLowerCase();
                      if (pr === 'checkbox' || pr === 'radio' || pr === 'switch'
                          || pr === 'menuitemcheckbox' || pr === 'menuitemradio' || pr === 'treeitem') {
                        try {
                          // 优先用 __recordPick 内解析好的真实控件（window.__lastPickEl），它已从
                          // composedPath[0] 穿透到 checkbox/radio 本身，状态读取最准；点击的原始 target（t）
                          // 可能是 <label>（label for= 关联的兄弟 input），需再解析。
                          var cb = (window.__lastPickEl) ? window.__lastPickEl : t;
                          if (cb && cb.tagName === 'LABEL') {
                            // 原生 <label for="x"> 关联的兄弟控件：用 label.control / htmlFor 定位；
                            // 嵌套 <label><input> 用 querySelector 向下找。
                            var lblCtrl = null;
                            try { lblCtrl = cb.control; } catch (e) {}
                            if (!lblCtrl && cb.htmlFor) { try { lblCtrl = document.getElementById(cb.htmlFor); } catch (e) {} }
                            if (!lblCtrl) { try { lblCtrl = cb.querySelector('input[type=checkbox],input[type=radio],*[role=checkbox],*[role=radio],*[role=switch],*[role=menuitemcheckbox],*[role=menuitemradio],*[role=treeitem]'); } catch (e) {} }
                            if (lblCtrl) cb = lblCtrl;
                          } else if (cb && cb.closest) {
                            var near = cb.closest('input[type=checkbox],input[type=radio],*[role=checkbox],*[role=radio],*[role=switch],*[role=menuitemcheckbox],*[role=menuitemradio],*[role=treeitem]');
                            if (near) cb = near;
                          }
                          var postChecked;
                          if (cb && (cb.type === 'checkbox' || cb.type === 'radio')) postChecked = !!cb.checked;
                          else if (cb && cb.hasAttribute && cb.hasAttribute('aria-checked')) {
                            var _av = cb.getAttribute('aria-checked'); postChecked = (_av === 'true' || _av === 'mixed');
                          } else postChecked = null;
                          if (postChecked !== null && postChecked !== clickPick.checked) {
                            clickPick.checked = postChecked;            // 同时驱动 setCheckedTarget=checked（Java 侧 setCheckedTarget=checked）
                            if (typeof window.__sigKey === 'function') clickPick._sigKey = window.__sigKey(clickPick);
                            if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(clickPick));
                            try { console.log('__roleOnPick::' + JSON.stringify(clickPick)); } catch (_) {}
                          }
                        } catch (cbErr) {}
                      }
                    } catch (e) {}
                  }, 0);
                } catch (e) {}
                // 同步把最新 currentStep 落盘：若本次点击触发整页导航，onFrameNavigated 合并恢复时
                // localStorage 已含本次点击（元素因读 Java 内存 javaPickBySig 仍存在，故"元素有、step 也有"）。
                try { window.__persistNow(); } catch (e) {}
              };
              // 双击拾取（dblclick）：对齐 page.pause() 对 doubleClick 动作的录制。
              // dblclick 在两次 click 之后触发，两次 click 已把该元素记录为 click 动作；此处按同一元素
              // 去重复位到同一 pick，追加 dblclick 标记（以最近一次交互为准），生成 step 时输出 doubleClick()。
              // 因 __recordPick 内部已"首次即时回传"一份不含 dblclick 的 pick 到 Java 内存态，
              // 故设标记后须再次经 __roleOnPick 回传（按 _sigKey 覆盖），确保 stop 时以内存态生成能拿到该标记。
              window.__rolePickDblClick = function(event) {
                var t = event.target;
                if (t && t.closest && t.closest('#__rolePanel, #__roleCodeOverlay')) return;
                var pick = window.__recordPick(t, false);
                if (!pick) return;
                pick.dblclick = true;
                try {
                  if (typeof window.__sigKey === 'function') pick._sigKey = window.__sigKey(pick);
                  if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(pick));
                  try { console.log('__roleOnPick::' + JSON.stringify(pick)); } catch(_){}
                } catch (e) {}
                try { window.__persistNow(); } catch (e) {}
              };
              window.__rolePickKey = function(event) {
                if (event.key === 'Escape') { window.__pickDone = true; return; }
                // 对齐 page.pause() 的 press 录制：输入框聚焦态下按的"实质按键"（Enter/Tab/Escape 之外的
                // 非字符键，以及方向键/功能键）记到最近录入的输入框 pick 上，生成 step 输出 locator.press("Enter")。
                // 纯字符键（a/b/1 等）不记——已由 value 走 fill/type，无需 press。
                if (!window.__rolePickActive) return;
                var inp = window.__activeInputPick;
                if (!inp || !window.__lastPickEl || !isEditable(window.__lastPickEl)) return;
                var k = event.key;
                if (!k) return;
                // 字符键（单字符、可打印）→ 忽略；只收录命名按键与组合修饰键
                var named = /^(Enter|Tab|Escape|Backspace|Delete|ArrowUp|ArrowDown|ArrowLeft|ArrowRight|Home|End|PageUp|PageDown|Space|F\\d+|Shift|Control|Alt|Meta)$/.test(k);
                if (!named) return;
                // 组合键（如 Ctrl+C）只录修饰部分会在 press 里以 "Control+C" 表达；此处仅记录主键，
                // 生成端按需拼接。简单起见记录原始 key 串（已含 Shift+ 等，page.pause 即如此）。
                inp.pressKey = k;
                try { if (typeof window.__sigKey === 'function') inp._sigKey = window.__sigKey(inp); } catch (_) {}
                try { if (window.__renderPicks) window.__renderPicks(); } catch (_) {}
              };
              // 对齐 page.pause() 的键盘可达性：Tab 聚焦到元素后（focusin），在拾取/hover 模式下
              // 记录一次"键盘拾取"，使纯键盘可达、hover 不出现的元素也能被捕获（如 hover 才显形/被遮挡的控件）。
              window.__rolePickFocus = function(event) {
                if (!window.__rolePickActive) return;
                // 【扫描机制已移除】原条件为「仅扫描模式才继续」，其等价语义是「手动态不通过 focusin 记录」
                // （否则输入框一获得焦点就逐次连发回传）。扫描模式已不存在，故直接早退，行为不变；
                // 纯键盘可达元素仍由 hover/点击路径捕获。
                return;
                var el = event.target;
                if (!el || el === window || el === document) return;
                if (el.closest && el.closest('#__rolePanel, #__roleCodeOverlay')) return;
                // 【关键修复"点击操作被 focusin 覆盖成 hover"】
                // 鼠标点击元素会连带触发 focusin（聚焦）。原实现以 isHover=true 记录并覆盖，导致用户明明是
                // "点击"，生成代码却变成 locator.hover()（尤其 checkbox/button 被误标 hover）。
                // 修复：focusin 只用于捕获"纯键盘可达、hover/click 都难拾取"的新元素，且一律按「点击（false）」
                // 记录——聚焦的语义是接下来会交互（点击/按键），记成 hover 不符合动作意图。真正的悬停由
                // mouseenter 防抖路径（isHover=true）记录，与 focusin 互不冲突。
                // 同时：若元素已被鼠标 click/mouseenter 记录过，直接放行，避免 focusin 覆盖其 hover/click 类型。
                try {
                  var _fsig = (typeof window.__pickSig === 'function') ? window.__pickSig(el) : '';
                  if (_fsig && window.__sigToPick && window.__sigToPick[_fsig]) return; // 已记录过，勿覆盖
                } catch (_fe) {}
                try { window.__recordPick(el, false); } catch (e) {}
              };
              // 滚动时重定位 hover 高亮框（__hoverBox 为 position:fixed，否则滚动后高亮框会漂移残留）。
              window.__rolePickScroll = function() {
                try { if (window.__lastHoverTarget) __showHoverBox(window.__lastHoverTarget); } catch (e) {}
              };
              // ===== 拖拽手势录制（对齐 page.pause() 的 source.dragTo(target)）=====
              // mousedown 记录「源」元素与按下坐标；mousemove 累积位移；mouseup 时若源≠目标且位移超阈值，
              // 则把目标元素的定位签名挂在源 pick 上并触发记录（源 pick 进入拾取集，目标元素也一并 recordPick），
              // 生成端据 keyToField 反查目标字段名输出 srcField.dragTo(dstField)。
              window.__dragSrcEl = null; window.__dragStartX = 0; window.__dragStartY = 0;
              window.__dragMoved = 0; window.__dragDstKey = null;
              window.__rolePickDragDown = function(e) {
                if (!window.__rolePickActive) return;
                // resolveElement 若未定义（极少数注入片段不完整的 frame），退化为 composedPath()[0]，
                // 杜绝 ReferenceError 中断拖拽拾取。
                var el;
                try {
                  if (typeof resolveElement === 'function') { el = resolveElement(e.target); }
                  else { el = (e.composedPath && e.composedPath()[0]) || e.target; }
                } catch (_re) { el = e.target; }
                if (!el || el === window || el === document) { window.__dragSrcEl = null; return; }
                window.__dragSrcEl = (typeof resolveLabel === 'function') ? resolveLabel(el) : el;
                window.__dragStartX = e.clientX; window.__dragStartY = e.clientY;
                window.__dragMoved = 0; window.__dragDstKey = null;
              };
              window.__rolePickDragUp = function(e) {
                if (!window.__rolePickActive || !window.__dragSrcEl) return;
                var srcEl = window.__dragSrcEl;
                var dstEl = e.target;
                try {
                  if (typeof resolveElement === 'function') dstEl = resolveElement(e.target);
                  else if (e.composedPath && e.composedPath()[0]) dstEl = e.composedPath()[0];
                  if (typeof resolveLabel === 'function') dstEl = resolveLabel(dstEl);
                } catch (_de) {}
                window.__dragSrcEl = null;
                if (!dstEl || dstEl === srcEl) return; // 未跨元素，非拖拽
                if (window.__dragMoved < 12) return;  // 位移过小，视为普通点击
                try {
                  var dstPick = computePick(dstEl);
                  if (!dstPick || !dstPick.key) return;
                  window.__dragDstKey = dstPick.key;
                  // 记录「源」（带 dragDstKey 注入），再记录「目标」本身（若尚未拾取）。
                  try { window.__recordPick(srcEl, true); } catch (_) {}
                  window.__dragDstKey = null;
                  try { window.__recordPick(dstEl, true); } catch (_) {}
                } catch (_) {}
              };
              window.__rolePickDragMove = function(e) {
                if (!window.__dragSrcEl) return;
                window.__dragMoved += Math.abs(e.clientX - window.__dragStartX) + Math.abs(e.clientY - window.__dragStartY);
                window.__dragStartX = e.clientX; window.__dragStartY = e.clientY;
              };
              document.addEventListener('mousedown', window.__rolePickDragDown, true);
              document.addEventListener('mousemove', window.__rolePickDragMove, true);
              document.addEventListener('mouseup', window.__rolePickDragUp, true);
              document.addEventListener('click', window.__rolePickClick, true);
              document.addEventListener('dblclick', window.__rolePickDblClick, true);
              document.addEventListener('keydown', window.__rolePickKey, true);
              document.addEventListener('focusin', window.__rolePickFocus, true);
              document.addEventListener('scroll', window.__rolePickScroll, true);
              // ===== 诊断：额外鼠标事件监听（仅记录、不拦截、不影响拾取）=====
              // 排查"刷新后点击无反应 / 点击卡顿"：记录 mousedown/up/dblclick/contextmenu 是否真到达 document。
              // 双写：window.__roleMouseLog 环形缓冲（导航后可由 Java 经 page.evaluate 回读）
              // + console.log('[roleMouseDiag]...')（由 ctx.onConsoleMessage 实时转发到 Java 日志，前缀过滤不刷屏）。
              if (!window.__roleMouseDebugHooked) {
                window.__roleMouseDebugHooked = true;
                window.__roleMouseLog = [];
                function __mouseDiag(type) {
                  return function(ev) {
                    try {
                      var el = ev.target;
                      var tag = (el && el.tagName) ? el.tagName.toLowerCase() : '?';
                      var id = (el && el.id) ? '#' + el.id : '';
                      var cls = (el && el.className && typeof el.className === 'string')
                        ? '.' + el.className.trim().split(' ').filter(Boolean).slice(0,2).join('.') : '';
                      var entry = { type: type, el: tag + id + cls, active: !!window.__rolePickActive, ts: Date.now() };
                      window.__roleMouseLog.push(entry);
                      if (window.__roleMouseLog.length > 60) window.__roleMouseLog.shift();
                      try { console.log('[roleMouseDiag] ' + JSON.stringify(entry)); } catch(_){}
                    } catch(e) {}
                  };
                }
                document.addEventListener('mousedown', __mouseDiag('mousedown'), true);
                document.addEventListener('mouseup', __mouseDiag('mouseup'), true);
                document.addEventListener('dblclick', __mouseDiag('dblclick'), true);
                document.addEventListener('contextmenu', __mouseDiag('contextmenu'), true);
              }
            })();