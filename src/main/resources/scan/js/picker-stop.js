            (function() {
              // 区域扫描选区态的收尾：若处于选区态（window.__regionSelecting），先结束选区——
              // 否则选区监听（document 级 capture 的 click/keydown）残留会吞掉面板按钮点击、
              // 且 __regionSelecting 不清除会让 scan/region 按钮一直置灰、状态切不动。
              if (window.__regionSelecting) {
                try { if (window.__roleEndRegionSelect) window.__roleEndRegionSelect(); } catch (e) {}
                try { document.removeEventListener('mousemove', window.__roleRegionMove, true); } catch (e) {}
                try { document.removeEventListener('click', window.__roleRegionClick, true); } catch (e) {}
                try { document.removeEventListener('keydown', window.__roleRegionEsc, true); } catch (e) {}
                try { document.removeEventListener('keydown', window.__roleRegionEscB, false); } catch (e) {}
                window.__regionSelecting = false;
                window.__scanMode = null;   // 清除模式标识，回到"无模式"
                try { if (window.__roleRefreshToggle) window.__roleRefreshToggle(); } catch (e) {}
              }
              // 关键修复：无条件移除监听并置位（不再因 __rolePickActive 已为 false 而早退）。
              // 早退会在"Java 端 active[0] 与浏览器端 __rolePickActive 因竞态不一致"时，
              // 导致应停止的页面监听残留、状态错乱，进而出现"停止后再开始拾取不了"。
              // removeEventListener 对同一函数引用幂等，重复调用安全。
              document.removeEventListener('click', window.__rolePickClick, true);
              document.removeEventListener('mousemove', window.__rolePickMove, true);
              document.removeEventListener('keydown', window.__rolePickKey, true);
              document.removeEventListener('focusin', window.__rolePickFocus, true);
              document.removeEventListener('scroll', window.__rolePickScroll, true);
              window.__rolePickActive = false;
              // 停止时也清整页扫描态，避免异常路径下 scan/region 按钮卡死置灰。
              window.__pageScanning = false;
              try { if (window.__roleRefreshToggle) window.__roleRefreshToggle(); } catch (e) {}
              // 收尾当前 step：停止拾取即把"当前选中（已勾选）的候选"封装成 step 并入 __steps。
              // 面板勾选/实时点选都写入 window.__currentStep（选择集），停止时一次性打包为 step；
              // 整个选择（无论跨多少个页面）合并为【一个 step】——step 的唯一边界是"开始→停止"
              // （或一次「封装为步骤」），弹窗打开/关闭、页面跳转都只是同一 step 内的交互。
              // 优先用 __packageStep（与面板"封装为步骤"同一逻辑，保证顺序/去重一致）；兜底直接打包。
              if (window.__currentStep && window.__currentStep.length) {
                if (typeof window.__packageStep === 'function') {
                  window.__packageStep();
                } else {
                  if (!window.__steps) window.__steps = [];
                  window.__steps.push({pageClass:(window.__rolePageName||''), picks:window.__currentStep});
                }
              }
              window.__currentStep = null;
              // 【修复"步骤代码(2)"错位】停止即关闭本轮 step 边界：清空 __steps，使下一轮 ▶ 从干净状态开始，
              // step 编号重新从 1 起（否则 __steps 续接旧 step，下一轮 push 后 Java 渲染出 step2/显示"第 2 个 step"）。
              // 注意：跨页切换重启走 picker-core-a.js 的「续接」分支（不调用 stop），__currentStep 被搬运、__steps 保留，不丢步骤。
              // 同时重置全局动作序号计数器，下一轮拾取的 index 也重新从 1 连续编号。
              try { window.__steps = []; } catch (e) {}
              try { window.__rolePickSeq = 0; } catch (e) {}
              // 收起实时悬停高亮框
              try { var __hb = document.getElementById('__roleHoverBox'); if (__hb) __hb.style.display = 'none'; } catch (e) {}
              // 清除区域选择遗留的页面高亮框（绿色已选/青色悬停），停止拾取时一并清掉，避免残留。
              try { if (typeof window.__clearRegionOutlines === 'function') window.__clearRegionOutlines(); } catch (e) {}
              // 清除所有 frame（含主文档 + 各层 iframe）内元素的拾取描边（outline）与聚焦边框，
              // 避免"取消拾取 / 停止拾取后，iframe 内元素的蓝色/黄色边框未消失"的残留。
              try {
                (function __stopAll(win) {
                  if (!win || !win.document) return;
                  var d = win.document;
                  // 【关键修复"停止拾取后 iframe 内仍可点击拾取"】
                  // 原 stop 只移除当前 frame（顶层）的 document 监听，iframe 内自己的 document 监听未移除，
                  // 停止拾取后 iframe 内仍能点击拾取、边框也仍会高亮。这里在遍历每个 frame 时，
                  // 一并移除该 frame 的拾取/扫描监听，并把 __rolePickActive 置 false，彻底关闭所有 frame 的拾取态。
                  var rl = window.__rolePickClick, rm = window.__rolePickMove, rk = window.__rolePickKey,
                      rf = window.__rolePickFocus, rs = window.__rolePickScroll,
                      rp = window.__rolePickPointer, rpo = window.__rolePickOver, rr = window.__rolePickRegionClick;
                  d.removeEventListener('click', rl, true);
                  d.removeEventListener('mousemove', rm, true);
                  d.removeEventListener('keydown', rk, true);
                  d.removeEventListener('focusin', rf, true);
                  d.removeEventListener('scroll', rs, true);
                  if (rp) d.removeEventListener('pointerdown', rp, true);
                  if (rpo) d.removeEventListener('mouseover', rpo, true);
                  if (rr) d.removeEventListener('click', rr, true);
                  try { if (window.__rolePickLeave) d.removeEventListener('mouseleave', window.__rolePickLeave, false); } catch (_) {}
                  try { win.__rolePickActive = false; } catch (_) {}
                  var nodes = d.querySelectorAll('[_rolepick_outline]');
                  for (var i = 0; i < nodes.length; i++) {
                    try { nodes[i].style.outline = ''; nodes[i].style.outlineOffset = ''; nodes[i].removeAttribute('_rolepick_outline'); } catch (_) {}
                  }
                  // 兜底：清掉仍带显式拾取色描边的元素（部分老路径未打标记属性）
                  var all = d.querySelectorAll('*');
                  for (var j = 0; j < all.length; j++) {
                    var s = all[j].style;
                    if (s && (s.outline.indexOf('ffeb3b') >= 0 || s.outline.indexOf('29b6f6') >= 0 || s.outline.indexOf('ff9800') >= 0)) {
                      s.outline = ''; s.outlineOffset = '';
                    }
                  }
                  // 隐藏当前 frame 的实时悬停高亮框（青色 #29b6f6 边框）
                  try { var hb = d.getElementById('__roleHoverBox'); if (hb) hb.style.display = 'none'; } catch (_) {}
                  var fr = win.frames || [];
                  for (var k = 0; k < fr.length; k++) { try { __stopAll(fr[k]); } catch (_) {} }
                })(window);
              } catch (e) {}
            })();