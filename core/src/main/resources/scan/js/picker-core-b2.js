
                // 状态外置（对齐 page.pause）：去掉"每次点击全量序列化 localStorage"这一 O(n) 瓶颈，
                // 点击延迟不再随已拾元素增多而变慢。整页跳转前的最后点击由 pagehide/beforeunload 的
                // __persistNow 同步即时落盘兜底（见 __persistPickState 定义），不丢失。
                // —— UI 反馈 + Java 回传（仅在非扫描态执行）——
                // 扫描态（window.__scanning 为 true）下整体跳过：避免对整页每个元素都触发
                //   · __renderPicks() —— 逐元素全量重渲染，N 个元素即 O(N²) 的致命瓶颈；
                //   · __roleOnPick 单次绑定回传 + console.log —— N 次 Java 往返 / 控制台事件洪流。
                // 改为扫描结束统一「一次性渲染 + 批量回传」（见 __roleScanPage 尾部），可访问名/去重等核心逻辑仍照常执行。
                if (!window.__scanning) {
                  var prev = t.style.outline;
                  t.style.outline = dup ? '3px solid #ff9800' : (isHover ? '3px solid #29b6f6' : '3px solid #ffeb3b');
                  // 400ms 后直接清除高亮（而非恢复 prev）：避免快速重复点选同一元素时，
                  // 第二次捕获到的 prev 已是上一次设置的黄色框，setTimeout 又把黄框"恢复"回来，
                  // 造成"选择/封装完成后页面元素上的黄色框始终不消失"的残留问题。
                  setTimeout(function() { try { t.style.outline = ''; t.style.outlineOffset = ''; } catch (e) {} }, 400);
                  var statusEl = document.getElementById('__roleStatus');
                  if (statusEl) {
                    var extra = dup ? '（重复，已忽略）'
                      : ((pick && pick.matched) ? '（key=' + pick.key + '）' : '');
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
                }
                return pick;
              };
              // 整页 role 树扫描（对齐 page.pause 的 role-centric 理念，但更完整）：
              // 遍历整页所有元素，凡"有语义角色（非 generic/none/presentation）且有可访问名"的可见元素，
              // 一律经 __recordPick 记录为 pick——复用点击拾取的全套链路（去重 / 面板渲染 / __roleOnPick 回传 Java）。
              // 与点击录制"录到什么才有什么"互补：扫描把整页所有语义角色元素一次性收全，用户随后停止即生成。
              // 扫描并收录语义角色元素。
              // @param scanRoot {Element|null|Array<Element>} 区域扫描根：
              //   - 单个 Element：仅遍历该根（含后代）子树，实现"只 scan 这块"；
              //   - 数组 Element[]：依次遍历每个根子树并合并（多选区域同时扫描）；
              //   - null：退化为整页扫描（原行为）。
              window.__roleScanPage = function(scanRoot) {
                if (typeof window.__recordPick !== 'function') return -1;
                // 归一化：单根 → 数组，便于统一遍历；null → 整页标记
                var roots = null;
                if (scanRoot == null) {
                  roots = null; // 整页
                } else if (Array.isArray(scanRoot)) {
                  roots = scanRoot.filter(function(r){ return r && r.nodeType === 1; });
                  if (!roots.length) roots = null;
                } else if (scanRoot.nodeType === 1) {
                  roots = [scanRoot];
                }
                var isRegion = !!roots;
                function __visForScan(el) {
                  try {
                    if (!el || !el.getBoundingClientRect) return false;
                    var cs = window.getComputedStyle(el);
                    if (!cs || cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity || '1') === 0) return false;
                    var r = el.getBoundingClientRect();
                    // 【关键修复"区域扫描穿透 iframe 却看不到 iframe 内语义元素"】
                    // 在 iframe 内执行穿透自扫时（window.self !== window.top），docked 右侧拾取面板会把页面
                    // 内容压缩/遮挡，iframe 内语义元素（button/checkbox 等）的 getBoundingClientRect 可能
                    // 被算成 0 宽/高，从而被下方 r.width>0&&r.height>0 过滤 → iframe 语义元素一个都不收录，
                    // 只剩 body/容器整块 text 被回传（用户日志正是只有 text:iframe 嵌套子页面...）。
                    // 故对 iframe 内元素放宽宽高判定：只要 display/visibility/opacity 正常就视为可见并收录。
                    // 主框架元素仍按原宽高判定，避免误收不可见元素。
                    if (window.self !== window.top) return true;
                    return (r.width > 0 && r.height > 0);
                  } catch (e) { return true; }
                }
                var prevActive = window.__rolePickActive;
                window.__rolePickActive = true;   // 扫描期间强制激活，使 __recordPick 记录（结束后还原）
                var prevMode = window.__scanMode;
                window.__scanMode = 'page';       // 整页/区域扫描模式（与手动拾取 'pick' 区分）
                // 关键修复：扫描期间临时清空「字符串根约束」__rolePickRoot。
                // 区域扫描的范围由调用方传入的 roots 数组（已选容器）决定，不应再叠加
                // document.querySelector(__rolePickRoot) 的字符串约束——否则 rootToSelector 生成的
                // .class / tag[role] 选择器可能匹配到页面上「第一个」同名元素而非扫描目标容器，
                // 导致目标容器内所有子元素被误判为"根外"而全部忽略（表现：只有区域容器自身被定位，
                // 区域内的子元素一个都没扫到）。扫描结束后还原，不影响后续点击/悬停拾取的根约束。
                var prevRoot = window.__rolePickRoot;
                window.__rolePickRoot = null;
                // 区域扫描时记录"区域根"，供 __computePick 在重定位时约束上界——
                // 子元素最多重定位到区域根"内部"的交互角色，绝不被重定位到区域根本身或根之上
                // （否则会将"整个区域容器"作为定位结果，表现：选了区域却只定位到整个区域）。
                var prevScanRoot = window.__roleScanRoot;
                window.__roleScanRoot = (isRegion && roots.length) ? roots[0] : null;
                window.__scanning = true;         // 抑制 __recordPick 的逐元素 UI 反馈/Java 回传，结束统一处理
                var added = 0;
                // 本次扫描真正新增的 pick 列表：扫描结束只回传这些，避免把历史 / 其它页面同步下来的
                // 元素按当前页上下文重算键后重复写入 Java 内存态（见函数尾部批量回传处说明）。
                var __scanAdded = [];
                // 区域根面积（用于下方"剔除与整个区域等大的元素"的面积阈值）
                var rootArea = 0;
                if (isRegion && roots.length) {
                  var r0 = roots[0].getBoundingClientRect ? roots[0].getBoundingClientRect() : null;
                  if (r0) rootArea = (r0.width || 0) * (r0.height || 0);
                }
                try {
                  var els;
                  if (!isRegion) {
                    // 穿透 shadow DOM 收集全文档元素（含各级 open shadowRoot）
                    els = __allElementsInDoc(document);
                  } else {
                    // 多根：先把每棵子树的元素收集进一个数组（querySelectorAll 返回的是各根并列的实时集合，
                    // 用数组快照避免遍历中 DOM 变动影响；重复元素由 __recordPick 内部去重兜底）。
                    // 同时穿透每棵子树内的 open shadowRoot。
                    els = [];
                    for (var ri = 0; ri < roots.length; ri++) {
                      var sub = __allElementsInDoc(roots[ri]);
                      for (var ni = 0; ni < sub.length; ni++) els.push(sub[ni]);
                    }
                  }
                  for (var i = 0; i < els.length; i++) {
                    var el = els[i];
                    // 【关键修复"整页/区域扫描对 iframe 支持不好"】
                    // 原扫描只遍历当前 frame 的 document，遇到 iframe 元素因 getRole(iframe)=generic
                    // 被 NON_ROLE 过滤，iframe 内元素一个都扫不到。这里遇到 iframe/frame 时，递归进入其
                    // contentWindow，用该 frame 自己的 __roleScanPage（其上下文有自带的 __recordPick/
                    // __framePathOf，能正确计算嵌套 framePath）执行扫描；结果记录进该 frame 的 __rolePicks
                    // 并逐层上送，与手动点选同链路。
                    if (el && (el.tagName === 'IFRAME' || el.tagName === 'FRAME')) {
                      // 【关键修复"整页/区域扫描未穿透 iframe"——跨源穿透】
                      // 主 frame 的 JS 访问 iframe 的 contentWindow.document 在 file://（origin=null）
                      // 或跨域下会抛 SecurityError，导致 iframe 内元素一个都扫不到。分两条路径穿透：
                      //   · 同源可访问：直接递归调用 __iw.__roleScanPage(null)（该 frame 上下文自带
                      //     __recordPick/__framePathOf，能正确算嵌套 framePath）；
                      //   · 跨源受限：改用 contentWindow.postMessage 通知该 iframe 自扫（iframe 内
                      //     注册的 message 监听收到 __roleScanRequest 后执行自己的 __roleScanPage(null)，
                      //     结果经绑定桥/console 兜底回传 Java，并 push 进 iframe 的 __rolePicks）。
                      var __inRootOk = true;
                      if (isRegion && roots.length) {
                        // 区域态：若该 iframe 不落在任何选中根内，不扫描其内部（保持"只扫所选区域"语义）
                        __inRootOk = false;
                        for (var __ri = 0; __ri < roots.length; __ri++) {
                          try { if (roots[__ri].contains(el)) { __inRootOk = true; break; } } catch (_) {}
                        }
                      }
                      if (__inRootOk) {
                        try {
                          var __iw = el.contentWindow;
                          var __accessed = false;
                          try {
                            if (__iw && __iw.document && typeof __iw.__roleScanPage === 'function' && __iw !== window) {
                              __iw.__roleScanPage(null);
                              __accessed = true;
                            }
                          } catch (__acErr) { __accessed = false; }
                          if (!__accessed && __iw && __iw.postMessage) {
                            // 跨源：无法 JS 直调，postMessage 通知 iframe 自扫
                            try { __iw.postMessage({ __roleScanRequest: true, __roleScanRoot: 'page' }, '*'); } catch (_pm) {}
                          }
                        } catch (__ie) {}
                      }
                      continue;   // iframe 容器自身不作为可拾取元素
                    }
                    if (isRegion && roots.indexOf(el) !== -1) continue;   // 区域根容器自身不作为定位记录
                    if (el.closest && el.closest('#__rolePanel, #__roleCodeOverlay, #__roleHoverBox')) continue;
                    // 区域扫描：剔除"与整个区域几乎等大"的元素（面积 >= 区域根 90%）。
                    // 这类元素本质上就是区域根本身或其等价大容器（哪怕它不是 DOM 树上的 root 节点，
                    // 例如 root 内部一个覆盖整区域的 list/table 容器），记录它会表现为"只定位了整个区域"。
                    if (isRegion && roots.length && rootArea > 0) {
                      var ea = (el.getBoundingClientRect ? el.getBoundingClientRect() : null);
                      if (ea && ea.width > 0 && ea.height > 0) {
                        var eaArea = ea.width * ea.height;
                        // 与整个区域几乎等大（>=90%）：区域根自身或其等价大容器，跳过
                        if (eaArea >= rootArea * 0.9) continue;
                        // 占区域一半以上（>=50%）且只是"容器拼凑的文字"定位（text 策略）：
                        // 这类元素本质是整个区域容器的整块文本（如把卡片内所有按钮名拼成一条），
                        // 记录它会表现为"整区域定位"，跳过。
                        if (eaArea >= rootArea * 0.5) {
                          try {
                            var _probe = window.__computePick(el);
                            if (_probe && _probe.strategy === 'text') continue;
                          } catch (e) {}
                        }
                      }
                    }
                    // 区域扫描：只保留"有稳定定位策略"的元素（getByRole / 带 id / 带 testid 等）。
                    // 跳过 css 路径从 body 开头的无名布局 div——这类元素 5 层内遇不到 stable id，
                    // cssPathOf 会一路拼到 body，生成 "body > div:nth-of-type(...)" 这种"整区域级"定位，
                    // 无业务价值（用户要区域内可稳定定位的子元素，而非整个区域）。
                    // 有 id 的容器 / 带 id 祖先的元素，其 css 路径以 #id 开头，不会被跳过。
                    // 仅区域扫描做无锚点 css 过滤。
                    // 原因：区域扫描是 querySelectorAll('*') 无差别遍历，区域内大量无语义的布局 div
                    // 会退化出 "div:nth-of-type(3) > div > div > ..." 这种纯位置链兜底路径，是噪音主源。
                    // 手动点选是用户主动指定的单个元素、整页扫描已有角色+可访问名双重约束，
                    // 都应保持与 page.pause() 一致：该生成 css 兜底就正常生成，不额外丢弃元素。
                    if (isRegion) {
                      var _cssProbe = (typeof cssPathOf === 'function') ? cssPathOf(el) : '';
                      // 区域态：css 兜底路径只要"退化到 body/html 骨架级"（无论是否带锚点前缀如
                      // ".Mozilla body > ..."，原判定仅拦 body 开头，会漏掉带前缀的形态）或"纯位置链
                      // （__isNthOnlyCss）"，即视为无稳定定位价值的噪音，仅在 __computePick 也只剩
                      // css 兜底时才跳过（保留具备 role/name/testid 等语义定位能力的元素）。
                      var _isBodyLevel = _cssProbe && (_cssProbe.indexOf('body') !== -1 || _cssProbe.indexOf('html') !== -1);
                      var _isNthOnly = _cssProbe && (typeof __isNthOnlyCss === 'function') && __isNthOnlyCss(_cssProbe);
                      if (_cssProbe && (_isBodyLevel || _isNthOnly)) {
                        // 该元素自身没有可锚定的 css 路径，但它可能仍具备语义定位能力
                        // （role+name / testid / label / placeholder 等），那类元素不该被误杀。
                        // 因此只有当 __computePick 最终也只能退化到 css 策略时才跳过。
                        var _pk = null;
                        try { _pk = window.__computePick(el); } catch (e) {}
                        if (!_pk || _pk.strategy === 'css') continue;
                      }
                    }
                    // 1) 先按语义角色过滤：最便宜的判定，可剔除绝大多数 generic div/span，
                    //    避免后续对它们做昂贵的可见性（getComputedStyle）/可访问名计算。
                    var role = (getRole(el) || '').toLowerCase();
                    if (!role || NON_ROLE[role]) continue;
                    // 2) 再按可访问名过滤：只需"有名字"，用 clean 名称一次计算即可（省去 getNameInfo 的二次计算）。
                    var name = (typeof getElementAccessibleName === 'function') ? getElementAccessibleName(el, false) : null;
                    if (!name) continue;
                    // 3) 最后才做昂贵的可见性判定（getComputedStyle + getBoundingClientRect）。
                    //    绝大多数元素已在 1)/2) 被过滤，getComputedStyle 调用量从「全体元素」降至「语义角色+带名元素」。
                    if (!__visForScan(el)) continue;
                    var before = window.__rolePicks ? window.__rolePicks.length : 0;
                    var pk = window.__recordPick(el, false);   // 内部核心去重仍执行；UI/回传副作用因 __scanning 被跳过
                    var after = window.__rolePicks ? window.__rolePicks.length : 0;
                    // 【关键修复"区域扫描额外生成整块文本定位"】
                    // 区域/整页扫描会把"整区域文本拼接"的元素定位成 text 策略（如 #sec-role 自身被
                    // __computePick 退化为 text，名称为其所有子元素文本拼接："1. 角色与状态 提交 禁用按钮..."），
                    // 这类元素不是可交互子元素、无业务价值，却会出现在页面元素/页面类里。
                    // 此处对扫描态新增的 text 策略元素做"整块文本"判定：名称超长（>=25 字符，明显是多子元素
                    // 拼接）即从 __rolePicks 回退移除，不计入本次新增。短文本的 text 元素（如独立段落/标签）
                    // 不受影响；点击拾取（__scanning=false）不受影响。
                    if (pk && pk.strategy === 'text' && (pk.name || '').length >= 25) {
                      if (after > before && window.__rolePicks) { window.__rolePicks.pop(); }
                      try { if (window.__rolePickSigs && pk._sigKey && window.__rolePickSigs[pk._sigKey]) delete window.__rolePickSigs[pk._sigKey]; } catch (e) {}
                      continue;
                    }
                    // 只登记「本次扫描真正新增」的 pick，供扫描结束后精确批量回传。
                    // 不能在结束时遍历整个 __rolePicks：该数组还含历史拾取与 syncPanelToBrowser
                    // 从 Java 同步下来的**其它页面**元素，全量回传会把它们按当前页上下文重算键后再写一遍。
                    if (pk && after > before) { added++; __scanAdded.push(window.__rolePicks[after - 1]); }
                  }
                } catch (e) {
                  try { console.error('[roleScan] ' + (e && e.message)); } catch (_) {}
                }
                window.__scanning = false;
                window.__rolePickActive = prevActive;
                window.__rolePickRoot = prevRoot;  // 还原字符串根约束（不影响后续点击/悬停拾取）
                // 一次性渲染 + 批量回传 Java：替代逐元素的 O(N²) 渲染与 N 次回传 / 控制台事件洪流。
                try { if (window.__renderPicks) window.__renderPicks(); } catch (e) {}
                // 只回传本次扫描新增的 pick（__scanAdded），不再遍历整个 window.__rolePicks。
                // 原因（修复"区域扫描 A 页 → 跳转 B 页 → 整页扫描 B，A 页元素成倍重复"）：
                // __rolePicks 除本次新增外，还含 ① 本页历史拾取 ② syncPanelToBrowser 从 Java 内存态
                // 同步下来的**其它页面**元素。全量回传时第 __sigKey(p) 会在**当前页上下文**重算去重键，
                // 而同步下来的元素只带 _sig 不带 _sigKey，一旦其 _pageClass 为空就退化到用当前 location
                // 兜底（见 __sigKey 第 1282 行），算出与 Java 中原键不同的新键 → 同一元素被重复写入内存态，
                // 每扫描一次翻一倍（用户实测同组 12 个元素重复 4 次）。
                if (__scanAdded.length) {
                  for (var k = 0; k < __scanAdded.length; k++) {
                    var p = __scanAdded[k];
                    if (!p) continue;
                    try {
                      if (typeof window.__sigKey === 'function') p._sigKey = window.__sigKey(p);
                      if (typeof window.__roleOnPick === 'function') window.__roleOnPick(JSON.stringify(p));
                      // 【关键修复"整页/区域扫描未穿透 iframe"】
                      // iframe 内递归扫描走的是 iframe 自己的 __roleScanPage，批量回传仅经绑定桥 __roleOnPick。
                      // 但 iframe 内绑定桥常失效（跨 frame/上下文隔离，前面分析过），扫描结果会整批丢失，
                      // 表现为"扫描扫不到 iframe 内元素"。此处补 console 兜底回传（__roleOnPick::...），
                      // 与手动点选的"绑定桥 + console 双保险"对齐，确保 iframe 内扫描结果必达 Java。
                      try { console.log('__roleOnPick::' + JSON.stringify(p)); } catch (_) {}
                      // 【关键修复"页面元素面板只有主框架元素"】
                      // __recordPick 里的"iframe 内元素 postMessage 上送顶层 __rolePicks"逻辑（点击拾取路径）
                      // 被包裹在 `if (!window.__scanning)` 内——扫描态 __scanning=true 时被整段跳过。
                      // 因此扫描出的 iframe 元素只 push 进【本 frame】的 __rolePicks 并经上面回传 Java，
                      // 从不会上送到顶层主框架 __rolePicks → 顶层面板"页面元素"Tab 只显示主框架元素、
                      // 扫描后即时生成的页面类也漏掉 iframe 内元素（Java 内存态却有 40 个，面板只见 32 个）。
                      // 修复：本 frame 若是 iframe，批量回传时把新增元素 postMessage 上送顶层聚合（与点击
                      // 拾取同链路，顶层 __rolePickSigs 按 sigKey 去重幂等）。上层 message 监听收到
                      // __rolePickMsg 后 push 进顶层 __rolePicks 并触发 __renderPicks，面板随即可见。
                      if (window.self !== window.top) {
                        // 【关键修复"checkbox 被默认勾选进选择集"】
                        // 本批量回传在 __roleScanPage 尾部、__scanning 已置 false 后执行；若此时把 iframe
                        // 元素 postMessage 上送顶层，顶层 message 监听读到 __scanning=false 会误判为"点击
                        // 拾取"而把元素 push 进 __currentStep（选择集）→ 面板里 checkbox 等扫描候选被默认勾选。
                        // 修复：上送时打上 __isScan 标记，顶层据此【不入选 step】，仅聚合进 __rolePicks 供面板
                        // 展示，保持"扫描出的元素是候选、由用户勾选后封装"的语义。
                        var __sent2 = false;
                        try {
                          if (window.top && window.top !== window.self) {
                            window.top.postMessage({ __rolePickMsg: true, __isScan: true, pick: p, __fromFrame: true }, '*');
                            __sent2 = true;
                          }
                        } catch (__e2) {}
                        if (!__sent2 && window.parent && window.parent !== window.self) {
                          try { window.parent.postMessage({ __rolePickMsg: true, __isScan: true, pick: p, __fromFrame: true }, '*'); } catch (__e3) {}
                        }
                      }
                    } catch (e) {}
                  }
                }
                var statusEl = document.getElementById('__roleStatus');
                if (statusEl) {
                  var pickedN = window.__rolePicks ? window.__rolePicks.length : 0;
                  statusEl.textContent = 'RoleElement Picker：' + (isRegion ? ('区域扫描完成（' + roots.length + ' 个区域）') : '整页扫描完成') + '，已拾取 ' + pickedN + ' 个语义角色元素，按 ESC 结束';
                }
                // 扫描结束：清除整页扫描态（区域扫描点击也会调用本函数，但区域态标志独立，此处只清 __pageScanning），
                // 恢复 scan/region 按钮可用；若异常也保证清除，避免按钮卡死置灰。
                // 还原模式标识（整页/区域扫描都经此路径，区域态由 __regionSelecting 独立控制，不影响）。
                window.__pageScanning = false;
                window.__scanMode = prevMode;
                window.__roleScanRoot = prevScanRoot;   // 还原区域根（整页/非扫描时为 null）
                try { if (window.__roleRefreshToggle) window.__roleRefreshToggle(); } catch (e) {}
                // 【修复"整页扫描完成后应聚焦到当前页 Tab"】
                // 整页扫描（非区域扫描）结束后，自动把面板切到「页面元素」Tab，并把激活的子 Tab
                // 设为【当前扫描页】（window.__rolePageName，即刚整页扫描的那个 pageClass），
                // 让用户即时看到本次整页扫描出来的元素清单；即便此前停留在其它页的子 Tab 也不受影响。
                // 区域扫描为「叠加补充」语义，不强制切 Tab，避免打断用户连续选区。
                if (!isRegion) {
                  try {
                    var __curPage = window.__rolePageName || '';
                    window.__roleActiveTab = 'page';
                    if (__curPage) window.__roleActivePageClass = __curPage;
                    if (typeof window.__roleShowTab === 'function') window.__roleShowTab();
                    if (typeof window.__renderPicks === 'function') window.__renderPicks();
                  } catch (e2) {}
                }
                return added;
              };
              // 区域扫描（悬停聚焦 + 点击多选模式）：一个页面常有多个业务区域，支持「选取多个区域」。
              // 交互：鼠标移入区域即实时高亮（跟随切换）；点击 = 把当前区域加入/移出「已选集合」（多选、可重复点取消）；
              // 按 Esc 完成选区，合并扫描所有已选区域（__roleScanPage 支持数组多根）；未选任何区域则退化为整页。
              // 颜色：青色=悬停预览，绿色=已选中。面板内不参与选区。
              window.__roleStartRegionSelect = function() {
                if (typeof window.__roleScanPage !== 'function') return;
                // 显式模式标识：区域扫描态。三种模式（手动拾取 / 整页扫描 / 区域扫描）复用同一套
                // __rolePickActive / __recordPick，但靠 __scanMode 显式区分，避免"区域被当成点击拾取的元素"。
                window.__scanMode = 'region';
                // 进入区域扫描时【不再清空】__rolePicks / __rolePickSigs / __deletedSigs / __currentStep。
                // 修复：整页扫描 → 停止拾取 → 再区域选择时，整页扫描的页面元素被清空。
                // 原因：区域扫描 __roleScanPage 本身是「追加」语义（__scanAdded 记录本次新增并 push 进
                // __rolePicks），只有此处入口清空了拾取集才导致整页扫描结果丢失。现在保留已有拾取集，
                // 让区域扫描结果与整页扫描元素【叠加】，符合"整页扫描 + 区域选择互补补充"的期望。
                // 同时保留 __rolePickSigs（去重表）防止同 sig 元素重复、保留 __deletedSigs（已删屏蔽）
                // 防止已删元素在区域扫描时复活、保留 __currentStep（已勾选封装的选择集）防止已封装元素丢失。
                // 区域扫描结束后用户仍可手动勾选追加，行为不变。
                // 进入选区态时临时摘掉 START_SCRIPT 的文档级「点击拾取」「悬停高亮」监听，避免与区域聚焦冲突；
                // 选区结束后再把原监听加回。
                var hadPickClick = !!window.__rolePickClick;
                var hadPickMove = !!window.__rolePickMove;
                try { if (window.__rolePickClick) document.removeEventListener('click', window.__rolePickClick, true); } catch (e) {}
                try { if (window.__rolePickMove) document.removeEventListener('mousemove', window.__rolePickMove, true); } catch (e) {}
                // 视觉提示遮罩（不拦截事件，pointer-events:none），仅告知用户处于「选区态」
                var mask = document.createElement('div');
                mask.style.cssText = 'position:fixed;inset:0;z-index:2147483646;pointer-events:none;cursor:crosshair;background:rgba(33,150,243,.04)';
                document.body.appendChild(mask);
                // 区域扫描收敛：点击位置（多为按钮/链接/单元格等叶子）向上找到"所在业务区域容器"，
                // 并把它作为该区域的扫描根——这样扫描的是「区域里所有可定位元素」，而不是仅那个叶子本身。
                // 之前 acceptable 太宽松，会直接接受叶子元素，导致 querySelectorAll('*') 只扫到叶子内部、
                // 几乎无有意义元素（表现：只有区域那个元素被定位，而非区域的元素）。现改为"向上收敛到区域块"。
                function pickRoot(target) {
                  if (!target || target.nodeType !== 1) return null;
                  var LANDMARK = 'nav, header, aside, footer, [role=navigation], [role=banner], [role=complementary], [role=contentinfo]';
                  if (target.id === '__rolePanel' || target.id === '__roleCodeOverlay' || target.id === '__roleHoverBox') return null;
                  if (target.matches && target.matches(LANDMARK)) return null;
                  // 明确"业务区域语义"：这些标签/role 即代表一块业务区，应作为收敛目标，而非更大的页面骨架。
                  var REGION_STRICT = 'main, [role=main], section, article, form, fieldset,'
                    + ' [role=region], [role=dialog], [role=group], [role=list], [role=menu], [role=tablist], [role=toolbar]';
                  // 具体业务块 class：仅认真正业务块（card/panel/box/item/modal/dialog/list/group...），
                  // 排除 app/content/container/wrapper/layout/main 等"页面骨架"class（它们面积大、不是用户想选的区域）。
                  var BUSINESS_CLS = /(card|panel|box|section|block|modal|dialog|form-|item|widget|tile|cell|row-group|list-|group|fieldset|accordion|tab-)/i;
                  // 面积上限：超过视口 40% 的一律视为"页面骨架/整页"，不当作可收敛的业务区域（避免选区过大，
                  // 退化成整页扫描）。该阈值也用于兜底向上收敛时对"有 id/class 祖先"的面积约束。
                  var MAX_AREA = (window.innerWidth || 1280) * (window.innerHeight || 800) * 0.4;
                  function elArea(el) { var r = el.getBoundingClientRect ? el.getBoundingClientRect() : null; return r ? (r.width || 0) * (r.height || 0) : 0; }
                  var INLINE = { A:1, SPAN:1, BUTTON:1, INPUT:1, SELECT:1, TEXTAREA:1, LABEL:1, TD:1, TH:1, TR:1, LI:1, IMG:1, I:1, B:1, STRONG:1, EM:1, CODE:1, SMALL:1, SUB:1, SUP:1 };
                  // 是否为"合适的区域容器"：必须带明确业务语义标识，且面积未超骨架阈值。
                  function isContainer(el) {
                    if (!el || el === document.body) return false;
                    if (el.id === '__rolePanel' || el.id === '__roleCodeOverlay' || el.id === '__roleHoverBox') return false;
                    if (el.matches && el.matches(LANDMARK)) return false;     // 导航类 landmark 不收敛
                    var tag = (el.tagName || '').toUpperCase();
                    if (INLINE[tag]) return false;                            // 行内/叶子语义标签不收敛
                    if (el.matches && el.matches(REGION_STRICT)) return true; // 业务区域语义标签/role（高优先）
                    var area = elArea(el);
                    if (area > MAX_AREA) return false;                        // 过大=页面骨架，排除
                    var role = el.getAttribute && el.getAttribute('role');
                    if (role && !el.matches(LANDMARK)) return true;           // 显式非导航 role（list/menu/tablist...）
                    var cls = (el.className && el.className.baseVal !== undefined ? el.className.baseVal : (el.className || '')) + '';
                    if (BUSINESS_CLS.test(cls)) return true;                 // 具体业务块 class
                    if (el.id) return true;                                  // 有 id 且面积未超限的容器
                    return false;
                  }
                  // 1) 自身若是业务容器，直接返回（如点了 section/article/form 本身）
                  if (isContainer(target)) return target;
                  // 2) 向上找「最近的、合格业务容器」：大骨架因面积超限被跳过，继续向上；更大祖先通常也超限，
                  //    故实际会在"最近的、带语义标识/有 id 且不过大"的祖先处停下，避免选到整个页面。
                  var cur = target.parentElement;
                  var lastIdBlock = null;   // 仅记录"带 id 且面积未超限"的块级祖先（拒绝无名布局 div 当区域）
                  while (cur && cur !== document.body) {
                    if (isContainer(cur)) return cur;
                    if (cur.id && elArea(cur) <= MAX_AREA) lastIdBlock = cur;
                    cur = cur.parentElement;
                  }
                  // 3) 兜底：绝不退化到 main/body（否则区域扫描会退化成"整页扫描"，
                  //    表现：选了一小块区域却定位了整个页面/大区域）。
                  //    优先用带 id 且不过大的块级祖先；都没有则向上找"最近的非叶子、非骨架语义祖先"
                  //    （即使是无名 div，也比 main/body 小得多，更贴近用户点选位置）；
                  //    极端情况下退回点击元素自身（扫描其后代），确保区域小而精准。
                  if (lastIdBlock) return lastIdBlock;
                  var cur2 = target.parentElement;
                  while (cur2 && cur2 !== document.body) {
                    var tg = (cur2.tagName || '').toUpperCase();
                    if (INLINE[tg]) { cur2 = cur2.parentElement; continue; }
                    if (cur2.matches && cur2.matches(LANDMARK)) { cur2 = cur2.parentElement; continue; }
                    if (elArea(cur2) > MAX_AREA) { cur2 = cur2.parentElement; continue; } // 过大骨架跳过
                    // 命中"有业务语义标识"的祖先即停（role/class/id），否则继续向上，直到贴近的小容器
                    if (cur2.getAttribute && (cur2.getAttribute('role') || cur2.className || cur2.id)) return cur2;
                    cur2 = cur2.parentElement;
                  }
                  return target;   // 极端：裸页面无任何语义祖先，退回点击元素自身
                }
                var selected = window.__regionSelected = (window.__regionSelected || []);  // 已选区域根数组（暴露为全局，供封装/删除/停止时统一清高亮）
                var lastHover = null;
                function labelOf(el) { return (el.tagName ? el.tagName.toLowerCase() : '?') + (el.id ? '#' + el.id : ''); }
                function clearOutline(el) { try { el.style.outline = el.__prevOutline || ''; el.style.outlineOffset = el.__prevOffset || ''; } catch (e) {} }
                function setOutline(el, color) { try { el.__prevOutline = el.style.outline; el.__prevOffset = el.style.outlineOffset; el.style.outline = '2px solid ' + color; el.style.outlineOffset = '2px'; } catch (e) {} }
                function containsRoot(arr, el) { for (var i = 0; i < arr.length; i++) if (arr[i] === el) return true; return false; }
                // 重绘所有已选区域（绿色）并把悬停区叠加青色
                function repaint(hover) {
                  for (var i = 0; i < selected.length; i++) setOutline(selected[i], '#43a047'); // 绿色=已选
                  if (hover && !containsRoot(selected, hover)) setOutline(hover, '#0097a7');     // 青色=悬停预览
                }
                function status(msg) { var st = document.getElementById('__roleStatus'); if (st) st.textContent = msg; }
                function onMove(e) {
                  var t = e.target;
                  if (t && t.closest && t.closest('#__rolePanel, #__roleCodeOverlay, #__roleHoverBox')) {
                    if (lastHover) { clearOutline(lastHover); lastHover = null; repaint(null); }
                    status('鼠标在录制面板上，移出面板再选区域');
                    return;
                  }
                  var root = pickRoot(t);
                  if (root === lastHover) return;            // 同一区域不重复重绘
                  if (lastHover) clearOutline(lastHover);
                  lastHover = root;
                  repaint(root);
                  if (root) status('当前区域：' + labelOf(root) + '（点击=选/取消；已选 ' + selected.length + ' 个；Esc 完成扫描）');
                  else status('该位置不在业务容器内，挪到业务区（如表单/内容区）再点');
                }
                // 统一清除区域选择遗留的页面高亮框（绿色 #43a047 已选 / 青色 #0097a7 悬停预览）。
                // 暴露为全局，供「封装为步骤」「删除」「停止拾取」等收尾动作调用——
                // 否则用户不按 Esc 而直接封装/停止时，区域绿框会一直残留在页面上。
                window.__clearRegionOutlines = function() {
                  try {
                    var arr = window.__regionSelected || [];
                    for (var i = 0; i < arr.length; i++) { try { clearOutline(arr[i]); } catch (e) {} }
                    arr.length = 0;
                    if (lastHover) { try { clearOutline(lastHover); } catch (e) {} lastHover = null; }
                  } catch (e) {}
                };
                function finish() {
                  document.removeEventListener('mousemove', onMove, true);
                  document.removeEventListener('click', onClick, true);
                  document.removeEventListener('keydown', onEsc, true);
                  if (mask && mask.parentNode) mask.remove();
                  window.__clearRegionOutlines();
                  if (lastHover) clearOutline(lastHover);
                  // 各区域已在点击时即时扫描并展示，此处仅收尾；恢复整页拾取（不设字符串根约束，避免误约束）。
                  window.__rolePickRoot = null;
                  window.__regionSelecting = false;   // 退出区域选择态：恢复 scan/region 按钮可用
                  window.__scanMode = null;           // 清除模式标识（回到"无模式"，等待手动拾取/扫描指令）
                  try { window.__roleRefreshToggle && window.__roleRefreshToggle(); } catch (e) {}
                  restorePick();
                }
                function onEsc(e) {
                  if (e && (e.key === 'Escape' || e.keyCode === 27)) { e.preventDefault(); e.stopPropagation(); finish(); }
                }
                // 兼容：某些环境 keydown 不冒泡到 capture 阶段被拦截，也挂到 document（非 capture）兜底。
                function onEscBubble(e) {
                  if (e && (e.key === 'Escape' || e.keyCode === 27)) { e.preventDefault(); e.stopPropagation(); finish(); }
                }
                function onClick(e) {
                  var t = e.target;
                  // 关键修复：点击落在录制面板/代码浮层内时，必须「原样放行」事件（不 preventDefault/不 stopPropagation），
                  // 否则 document 级 capture 监听会吞掉面板内按钮（如"停止拾取"）的点击，导致状态切不动。
                  if (t && t.closest && t.closest('#__rolePanel, #__roleCodeOverlay, #__roleHoverBox')) {
                    return;   // 直接放行，交给面板按钮正常处理
                  }
                  e.preventDefault(); e.stopPropagation();
                  var root = pickRoot(t);
                  if (!root) { status('该位置无法收敛到业务容器，请点具体的业务区域（如表单/内容区）'); return; }
                  // 点击区域 = 立即扫描并展示该区域内所有可定位子元素（松开鼠标即出结果，无需按 Esc）。
                  // 区域容器自身不作为定位目标：__roleScanPage 内部用 root.querySelectorAll('*') 仅遍历后代，不含 root 自身。
                  if (!containsRoot(selected, root)) { selected.push(root); }
                  setOutline(root, '#43a047');   // 绿色=已扫描区域
                  try { window.__roleScanPage([root]); } catch (err) { try { console.error('[roleScan] ' + (err && err.message)); } catch (_) {} }
                  // 通知 Java 侧：区域元素已入 window.__rolePicks，请重新读取快照并生成"页面类"（与整页扫描一致，
                  // 否则区域扫描只会收集元素却永远不生成页面类）。用命令桥事件驱动，无需 Java 轮询。
                  // 【关键修复"区域扫描穿透不了 frame"】
                  // 区域根内若含 iframe，__roleScanPage 走 postMessage 异步穿透（file:// 下跨源受限），
                  // iframe 自扫结果进【各自 iframe】的 __rolePicks 需要一点事件循环时间。若立即发
                  // regionScanned，Java 侧 mergeFramePicksToMain 可能读到空的 iframe.__rolePicks，iframe
                  // 语义元素合并不进主框架/面板 → 表象"穿透不了 frame"。故延迟一拍再通知，确保 iframe
                  // 自扫完成、iframe.__rolePicks 已填充后再让 Java 合并（主循环空闲的 mergeFramePicksToMain
                  // 仍作最终兜底）。
                  try { setTimeout(function(){ if (window.__rolePickerCmd) window.__rolePickerCmd('regionScanned'); }, 250); } catch (e) {}
                  status('已扫描区域：' + labelOf(root) + '（已生成页面类；可继续点其他区域，按 Esc 结束）');
                  if (lastHover) { clearOutline(lastHover); lastHover = null; }
                }
                function restorePick() {
                  try { if (hadPickClick && window.__rolePickClick) document.addEventListener('click', window.__rolePickClick, true); } catch (e) {}
                  try { if (hadPickMove && window.__rolePickMove) document.addEventListener('mousemove', window.__rolePickMove, true); } catch (e) {}
                }
                // 把选区监听引用挂到 window，便于 STOP_SCRIPT（停止命令）在区域选区态中也能移除它们（否则残留导致状态卡死）。
                window.__roleRegionMove = onMove;
                window.__roleRegionClick = onClick;
                window.__roleRegionEsc = onEsc;
                window.__roleRegionEscB = onEscBubble;
                window.__roleEndRegionSelect = finish;   // 统一收尾入口（finish 内已清标志+restorePick+刷新按钮）
                document.addEventListener('mousemove', onMove, true);
                document.addEventListener('click', onClick, true);
                document.addEventListener('keydown', onEsc, true);
                document.addEventListener('keydown', onEscBubble, false);   // 兜底：capture 被吞时仍能 Esc 结束
                status('鼠标移入区域即聚焦（挪动跟随）；点击区域即扫描并展示该区域内元素；按 Esc 结束选区');
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
                // 【模式约束】focusin 仅在扫描模式记录键盘可达元素（用于捕获纯键盘可达、hover/click 难拾取的元素）。
                // 手动拾取模式（MANUAL）下不通过 focusin 记录，否则输入框获得焦点即连发回传（日志里 input 连发根因）。
                var _mode = window.__roleMode || 'idle';
                if (_mode !== 'scanPage' && _mode !== 'scanRegion') return;
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