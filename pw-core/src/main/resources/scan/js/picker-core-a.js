            (function() {
              // 保活（幂等）：任何 start 都确保 document 级点击/键盘/悬浮监听已挂载。
              // 导航（尤其某些 SPA/微前端框架在路由切换时重建 document 子树）可能移除这些监听，
              // 而 window.__rolePickActive 仍为 true；若直接早退会导致"导航到新页面后点了没反应、拾取不到"。
              // 故先重挂（addEventListener 对同一函数引用幂等），再判断是否已在拾取中。
              if (window.__rolePickClick) document.addEventListener('click', window.__rolePickClick, true);
              if (window.__rolePickDblClick) document.addEventListener('dblclick', window.__rolePickDblClick, true);
              if (window.__rolePickMove) document.addEventListener('mousemove', window.__rolePickMove, true);
              if (window.__rolePickKey) document.addEventListener('keydown', window.__rolePickKey, true);
              if (window.__rolePickFocus) document.addEventListener('focusin', window.__rolePickFocus, true);
              if (window.__rolePickScroll) document.addEventListener('scroll', window.__rolePickScroll, true);
              if (window.__rolePickActive) return;   // 已在拾取中：仅保活监听，不重置状态/不重复定义库
              window.__rolePickActive = true;
              window.__scanMode = 'pick';   // 手动拾取模式（点击元素即定位）
              window.__rolePicks = window.__rolePicks || [];
              window.__steps = window.__steps || [];
              // 保留"进行中的 step"：跨页面切换（弹窗打开/关闭）会先 applyPickState 把父页/弹窗的
              // __currentStep 搬过来，再执行本 START 重启监听。若这里硬置为 []，会把刚搬来的当前 step
              // 整个抹掉——这正是"元素定位在但步骤没了"的根因。用 || [] 续接：
              //  - 正常首次 ▶：__currentStep 为 undefined → 得 []（新 step）；
              //  - stop 后再 ▶：stop 已把 __currentStep 置 null → 得 []（新 step）；
              //  - 跨页切换重启：__currentStep 已被搬运为非空数组 → 原样续接，不丢步骤。
              window.__currentStep = window.__currentStep || [];
              window.__pickDone = false;

              // 拾取提示与计数不再用左下角独立控件，而是写入主面板标题状态条（#__roleStatus），
              // 由 openPanel(...) 的常驻面板呈现（见 PANEL_SCRIPT 与 __rolePickClick）。

              // ============================================================================
              // Playwright 注入脚本 roleUtils.ts 算法的忠实移植（getAriaRole + getElementAccessibleName）
              // 来源：microsoft/playwright packages/playwright-core/src/server/injected/roleUtils.ts
              // page.pause()/Inspector 的"拾取元素"正是用这套 W3C ARIA + accname 算法计算 role 与 name，
              // 因此直接移植，保证 picker 结果与 Inspector 完全一致（不再依赖浏览器 computedRole/computedName）。
              // ============================================================================
              // 企业级优化：roleUtils 移植 + 各拾取/去重/落盘辅助函数定义是"静态库"，
              // 同一 window 内只需解析/编译一次（首次 start）。后续 start（stop→再 start、
              // 或多页跟随）用一次性守卫跳过这近千行的重解析/重编译，点击"开始"的端到端延迟显著下降；
              // 所有对外入口（window.__recordPick / __computePick / __pickSig / __sigKey /
              // __persistPickState 等）都挂在 window 上会持续存活，跳过定义后仍可被点击 handler 正常调用。
              // 自动推断录制根容器（辅助函数，当前不再被默认使用）：
              // 历史曾用于"开始拾取默认避开导航"，但用户需要时可整页拾取，故默认不再调用。
              // 现保留为可选能力——区域扫描与显式根选择走各自逻辑；如需"默认避开导航"可单独调用。
              // 定义在此处（库守卫之外）并挂到 window，避免被守卫跳过导致潜在"未定义"。
              // 策略：① 优先 <main>；② 否则返回"面积最大、且自身不是 landmark"的内容容器；③ 都找不到返回 null。
              function autoDetectRoot() {
                try {
                  var mainEl = document.querySelector('main');
                  if (mainEl) return 'main';
                  var LANDMARK = 'nav, header, aside, footer, [role=navigation], [role=banner], [role=complementary], [role=contentinfo]';
                  var best = null, bestArea = 0;
                  var all = document.querySelectorAll('body > *, body');
                  for (var i = 0; i < all.length; i++) {
                    var el = all[i];
                    if (!el || el.nodeType !== 1) continue;
                    if (el.matches && el.matches(LANDMARK)) continue;            // 跳过导航类 landmark
                    var r = el.getBoundingClientRect();
                    var area = (r.width || 0) * (r.height || 0);
                    // 直接子级里若含 landmark（如 leftmenu 与内容并列），取该非 landmark子级为根
                    if (area > bestArea) { bestArea = area; best = el; }
                  }
                  if (best && best !== document.body) {
                    // 用稳定选择器表达：优先 id，其次 tag
                    if (best.id) return '#' + best.id;
                    return best.tagName ? best.tagName.toLowerCase() : null;
                  }
                } catch (e) { /* 推断失败忽略 */ }
                return null;
              }
              window.autoDetectRoot = autoDetectRoot;
              if (!window.__rolePickerLib) {
              var kGlobalAriaAttributes = ['aria-atomic','aria-busy','aria-controls','aria-current','aria-describedby','aria-details','aria-disabled','aria-dropeffect','aria-errormessage','aria-flowto','aria-grabbed','aria-haspopup','aria-hidden','aria-invalid','aria-keyshortcuts','aria-label','aria-labelledby','aria-live','aria-owns','aria-relevant','aria-roledescription'];
              function hasGlobalAriaAttribute(e) {
                for (var i = 0; i < kGlobalAriaAttributes.length; i++) if (e.hasAttribute(kGlobalAriaAttributes[i])) return true;
                return false;
              }
              var kAncestorPreventingLandmark = 'article:not([role]), aside:not([role]), main:not([role]), nav:not([role]), section:not([role]), [role=article], [role=complementary], [role=main], [role=navigation], [role=region]';
              function closestSafe(el, sel) {
                try { return el.closest ? el.closest(sel) : null; } catch (e) { return null; }
              }
              var kImplicitRoleByTagName = {
                'A': function(e){ return e.hasAttribute('href') ? 'link' : null; },
                'AREA': function(e){ return e.hasAttribute('href') ? 'link' : null; },
                'ARTICLE': function(){ return 'article'; },
                'ASIDE': function(){ return 'complementary'; },
                'BLOCKQUOTE': function(){ return 'blockquote'; },
                'BUTTON': function(){ return 'button'; },
                'CAPTION': function(){ return 'caption'; },
                'CODE': function(){ return 'code'; },
                'DATALIST': function(){ return 'listbox'; },
                'DD': function(){ return 'definition'; },
                'DEL': function(){ return 'deletion'; },
                'DETAILS': function(){ return 'group'; },
                'DFN': function(){ return 'term'; },
                'DIALOG': function(){ return 'dialog'; },
                'DT': function(){ return 'term'; },
                'EM': function(){ return 'emphasis'; },
                'FIELDSET': function(){ return 'group'; },
                'FIGURE': function(){ return 'figure'; },
                'FOOTER': function(e){ return closestSafe(e, kAncestorPreventingLandmark) ? null : 'contentinfo'; },
                'FORM': function(e){ return (e.hasAttribute('aria-label') || e.hasAttribute('aria-labelledby')) ? 'form' : null; },
                'H1': function(){ return 'heading'; }, 'H2': function(){ return 'heading'; },
                'H3': function(){ return 'heading'; }, 'H4': function(){ return 'heading'; },
                'H5': function(){ return 'heading'; }, 'H6': function(){ return 'heading'; },
                'HEADER': function(e){ return closestSafe(e, kAncestorPreventingLandmark) ? null : 'banner'; },
                'HR': function(){ return 'separator'; },
                'HTML': function(){ return 'document'; },
                'IMG': function(e){ return (e.getAttribute('alt') === '') && !hasGlobalAriaAttribute(e) && isNaN(Number(String(e.getAttribute('tabindex')))) ? 'presentation' : 'img'; },
                'INPUT': function(e){
                  var type = (e.getAttribute('type') || 'text').toLowerCase();
                  if (type === 'search') return e.hasAttribute('list') ? 'combobox' : 'searchbox';
                  if (['email','tel','text','url',''].indexOf(type) !== -1) {
                    var list = getIdRefs(e, e.getAttribute('list'))[0];
                    return (list && list.tagName === 'DATALIST') ? 'combobox' : 'textbox';
                  }
                  if (type === 'hidden') return '';
                  var map = { 'button':'button','checkbox':'checkbox','image':'button','number':'spinbutton','radio':'radio','range':'slider','reset':'button','submit':'button' };
                  return map[type] || 'textbox';
                },
                'INS': function(){ return 'insertion'; },
                'LI': function(){ return 'listitem'; },
                'MAIN': function(){ return 'main'; },
                'MARK': function(){ return 'mark'; },
                'MATH': function(){ return 'math'; },
                'MENU': function(){ return 'list'; },
                'METER': function(){ return 'meter'; },
                'NAV': function(){ return 'navigation'; },
                'OL': function(){ return 'list'; },
                'OPTGROUP': function(){ return 'group'; },
                'OPTION': function(){ return 'option'; },
                'OUTPUT': function(){ return 'status'; },
                'P': function(){ return 'paragraph'; },
                'PROGRESS': function(){ return 'progressbar'; },
                'SECTION': function(e){ return (e.hasAttribute('aria-label') || e.hasAttribute('aria-labelledby')) ? 'region' : null; },
                'SELECT': function(e){ return (e.hasAttribute('multiple') || e.size > 1) ? 'listbox' : 'combobox'; },
                'STRONG': function(){ return 'strong'; },
                'SUB': function(){ return 'subscript'; },
                'SUP': function(){ return 'superscript'; },
                'SVG': function(){ return 'img'; },
                'TABLE': function(){ return 'table'; },
                'TBODY': function(){ return 'rowgroup'; },
                'TD': function(e){ var table = closestSafe(e,'table'); var role = table ? getExplicitAriaRole(table) : ''; return (role==='grid'||role==='treegrid') ? 'gridcell' : 'cell'; },
                'TEXTAREA': function(){ return 'textbox'; },
                'TFOOT': function(){ return 'rowgroup'; },
                'TH': function(e){
                  if (e.getAttribute('scope') === 'col') return 'columnheader';
                  if (e.getAttribute('scope') === 'row') return 'rowheader';
                  var table = closestSafe(e,'table'); var role = table ? getExplicitAriaRole(table) : '';
                  return (role==='grid'||role==='treegrid') ? 'gridcell' : 'cell';
                },
                'THEAD': function(){ return 'rowgroup'; },
                'TIME': function(){ return 'time'; },
                'TR': function(){ return 'row'; },
                'UL': function(){ return 'list'; }
              };
              var kPresentationInheritanceParents = {
                'DD': ['DL','DIV'], 'DIV': ['DL'], 'DT': ['DL','DIV'], 'LI': ['OL','UL'],
                'TBODY': ['TABLE'], 'TD': ['TR'], 'TFOOT': ['TABLE'], 'TH': ['TR'],
                'THEAD': ['TABLE'], 'TR': ['THEAD','TBODY','TFOOT','TABLE']
              };
              function getImplicitAriaRole(element) {
                var fn = kImplicitRoleByTagName[element.tagName.toUpperCase()];
                var implicitRole = (fn ? fn(element) : null) || '';
                if (!implicitRole) return null;
                var ancestor = element;
                while (ancestor) {
                  var parent = ancestor.parentElement;
                  var parents = kPresentationInheritanceParents[ancestor.tagName];
                  if (!parents || !parent || parents.indexOf(parent.tagName) === -1) break;
                  var per = getExplicitAriaRole(parent);
                  if ((per === 'none' || per === 'presentation') && !hasGlobalAriaAttribute(parent)) return per;
                  ancestor = parent;
                }
                return implicitRole;
              }
              var allRoles = ['alert','alertdialog','application','article','banner','blockquote','button','caption','cell','checkbox','code','columnheader','combobox','command','complementary','composite','contentinfo','definition','deletion','dialog','directory','document','emphasis','feed','figure','form','generic','grid','gridcell','group','heading','img','input','insertion','landmark','link','list','listbox','listitem','log','main','marquee','math','meter','menu','menubar','menuitem','menuitemcheckbox','menuitemradio','navigation','none','note','option','paragraph','presentation','progressbar','radio','radiogroup','range','region','roletype','row','rowgroup','rowheader','scrollbar','search','searchbox','section','sectionhead','select','separator','slider','spinbutton','status','strong','structure','subscript','superscript','switch','tab','table','tablist','tabpanel','term','textbox','time','timer','toolbar','tooltip','tree','treegrid','treeitem','widget','window'];
              var abstractRoles = ['command','composite','input','landmark','range','roletype','section','sectionhead','select','structure','widget','window'];
              var validRoles = allRoles.filter(function(r){ return abstractRoles.indexOf(r) === -1; });
              function getExplicitAriaRole(element) {
                var roles = (element.getAttribute('role') || '').split(' ').map(function(r){ return r.trim(); });
                for (var i = 0; i < roles.length; i++) if (validRoles.indexOf(roles[i]) !== -1) return roles[i];
                return null;
              }
              function getAriaRole(element) {
                var explicitRole = getExplicitAriaRole(element);
                if (explicitRole) {
                  if ((explicitRole === 'none' || explicitRole === 'presentation') && !hasGlobalAriaAttribute(element)) return getImplicitAriaRole(element);
                  return explicitRole;
                }
                // 对齐 Playwright 1.58 getAriaRole：无显式 role 时，可编辑元素视为 textbox，
                // 否则富文本/可编辑 div 会退化成 generic 被 NON_ROLE 跳过，无法被 role+name 捕获
                // （page.pause() 会识别为 textbox）。
                try { if (element.isContentEditable) return 'textbox'; } catch (e) {}
                try { if (element.tagName === 'LI' && (element.value)) return 'listitem'; } catch (e) {}
                return getImplicitAriaRole(element);
              }
              function getRole(el) { return getAriaRole(el) || 'generic'; }

              // 把 <label> 解析为其关联的表单控件：点击复选框/单选/文本框的"标签文字"时，
              // 对齐 page.pause()/Inspector——直接拾取它所指代的控件（role + name=标签文本），
              // 而非无语义的 label 文本节点。
              //   - HTMLLabelElement.control 覆盖"包裹式"关联（含 display:none 的隐藏控件）；
              //   - 否则回退到 for 属性指向的控件；都没有则不解析，保留 label 自身走 text 兜底。
              function resolveLabel(el) {
                if (!el || el.tagName !== 'LABEL') return el;
                try { if (el.control) return el.control; } catch (e) {}
                var forAttr = el.getAttribute && el.getAttribute('for');
                if (forAttr) {
                  try { var byId = el.ownerDocument.getElementById(forAttr); if (byId) return byId; } catch (e) {}
                }
                return el;
              }
              // 【关键修复"Uncaught ReferenceError: resolveElement is not defined"】
              // __rolePickDragDown / __rolePickDragUp 使用 resolveElement 解析拖拽源/目标元素，但该函数
              // 一直缺失（拖拽触发 mousedown 时在浏览器 console 抛 ReferenceError）。这里补上：
              // 用 composedPath 穿透 open shadow DOM，并把 SVG/文本等无语义 target 上溯到最近的可拾取祖先
              // （SVG path/circle 在拾取里 role=graphic/generic 且无稳定 sig，拖拽目标应解析到其宿主控件）。
              function resolveElement(t) {
                if (!t) return t;
                try {
                  if (typeof t.composedPath === 'function' && t.composedPath().length) {
                    t = t.composedPath()[0];
                  }
                } catch (e) {}
                var n = 0;
                while (t && t.nodeType === 1 && n < 12) {
                  var tag = (t.tagName || '').toUpperCase();
                  if (t.closest && (t.closest('#__rolePanel, #__roleCodeOverlay'))) return null;
                  // SVG 内部节点（path/circle/rect/g/line/polyline 等）或无 role 的图形节点 → 上溯宿主
                  var isSvgNode = tag === 'SVG' || tag === 'PATH' || tag === 'CIRCLE' || tag === 'RECT'
                    || tag === 'G' || tag === 'LINE' || tag === 'POLYLINE' || tag === 'POLYGON'
                    || tag === 'TEXT' || tag === 'ELLIPSE' || tag === 'USE' || tag === 'DEFS';
                  var hasOwnRole = t.getAttribute && (t.getAttribute('role') || t.getAttribute('aria-label') || t.getAttribute('aria-labelledby'));
                  if (!isSvgNode || hasOwnRole) break;
                  t = t.parentElement;
                  n++;
                }
                return t;
              }

              // ===== 可访问名称（W3C accname 算法，移植自 roleUtils.getElementAccessibleName）=====
              function normalizeAccessibleName(s) {
                return (s || '')
                  .replace(/\\r\\n/g, '\\n')
                  .replace(/\\u00A0/g, ' ')
                  .replace(/\\s\\s+/g, ' ')
                  .trim();
              }
              function getComputedStyleSafe(el) {
                try { var w = el.ownerDocument && el.ownerDocument.defaultView; return w ? w.getComputedStyle(el) : null; } catch (e) { return null; }
              }
              function getPseudo(el, pseudo) {
                try { var w = el.ownerDocument && el.ownerDocument.defaultView; return w ? w.getComputedStyle(el, pseudo) : null; } catch (e) { return null; }
              }
              function isHiddenForAria(element) {
                if (!element || !element.tagName) return false;
                var t = element.tagName;
                if (t === 'STYLE' || t === 'SCRIPT' || t === 'NOSCRIPT' || t === 'TEMPLATE') return true;
                var ah = element.getAttribute && element.getAttribute('aria-hidden');
                if (ah === 'true') return true;
                var cs = getComputedStyleSafe(element);
                if (cs && (cs.display === 'none' || cs.visibility === 'hidden')) return true;
                var parent = element.parentElement;
                if (parent && parent !== element) return isHiddenForAria(parent);
                return false;
              }
              function getIdRefs(element, ref) {
                if (!ref) return [];
                var root = element.ownerDocument;
                if (!root) return [];
                var ids = ref.split(' ').filter(function(id){ return !!id; });
                var set = [];
                for (var i = 0; i < ids.length; i++) {
                  try {
                    var first = root.querySelector('#' + (window.CSS && CSS.escape ? CSS.escape(ids[i]) : ids[i]));
                    if (first && set.indexOf(first) === -1) set.push(first);
                  } catch (e) {}
                }
                return set;
              }
              var kProhibitName = ['caption','code','definition','deletion','emphasis','generic','insertion','mark','paragraph','presentation','strong','subscript','suggestion','superscript','term','time'];
              var kAlwaysNameFromContent = ['button','cell','checkbox','columnheader','gridcell','heading','link','menuitem','menuitemcheckbox','menuitemradio','option','radio','row','rowheader','switch','tab','tooltip','treeitem'];
              var kDescendantNameFromContent = ['','caption','code','contentinfo','definition','deletion','emphasis','insertion','list','listitem','mark','none','paragraph','presentation','region','row','rowgroup','section','strong','subscript','superscript','table','term','time'];
              function allowsNameFromContent(role, targetDescendant) {
                if (kAlwaysNameFromContent.indexOf(role) !== -1) return true;
                if (targetDescendant && kDescendantNameFromContent.indexOf(role) !== -1) return true;
                return false;
              }
              function getAriaLabelledByElements(element) {
                var ref = element.getAttribute('aria-labelledby');
                if (ref === null) return null;
                return getIdRefs(element, ref);
              }
              function findOwned(element, selector) {
                var res = Array.prototype.slice.call(element.querySelectorAll(selector));
                getIdRefs(element, element.getAttribute('aria-owns')).forEach(function(o){
                  if (o.matches && o.matches(selector)) res.push(o);
                  res.push.apply(res, Array.prototype.slice.call(o.querySelectorAll(selector)));
                });
                return res;
              }
              function getPseudoContent(pseudoStyle) {
                if (!pseudoStyle) return '';
                var content = pseudoStyle.content;
                var first = content.charAt(0);
                var last = content.charAt(content.length - 1);
                if ((first === "'" && last === "'") || (first === '"' && last === '"')) {
                  var unquoted = content.slice(1, -1);
                  var disp = pseudoStyle.display || 'inline';
                  if (disp !== 'inline') return ' ' + unquoted + ' ';
                  return unquoted;
                }
                return '';
              }
              function getAccessibleNameFromAssociatedLabels(labels, options) {
                var res = [];
                for (var i = 0; i < labels.length; i++) {
                  var n = getElementAccessibleNameInternal(labels[i], {
                    includeHidden: options.includeHidden, visitedElements: options.visitedElements,
                    embeddedInLabelledBy: 'none', embeddedInLabel: 'self',
                    embeddedInTextAlternativeElement: false, embeddedInTargetElement: 'none'
                  });
                  if (n) res.push(n);
                }
                return res.join(' ');
              }
              function getElementAccessibleNameInternal(element, options) {
                if (options.visitedElements.has(element)) return '';
                var childOptions = {
                  includeHidden: options.includeHidden,
                  visitedElements: options.visitedElements,
                  embeddedInLabelledBy: options.embeddedInLabelledBy === 'self' ? 'descendant' : options.embeddedInLabelledBy,
                  embeddedInLabel: options.embeddedInLabel === 'self' ? 'descendant' : options.embeddedInLabel,
                  embeddedInTextAlternativeElement: options.embeddedInTextAlternativeElement,
                  embeddedInTargetElement: options.embeddedInTargetElement === 'self' ? 'descendant' : options.embeddedInTargetElement
                };
                if (!options.includeHidden && options.embeddedInLabelledBy !== 'self' && isHiddenForAria(element)) {
                  options.visitedElements.add(element);
                  return '';
                }
                var labelledBy = getAriaLabelledByElements(element);
                if (options.embeddedInLabelledBy === 'none') {
                  var lbName = (labelledBy || []).map(function(ref){
                    return getElementAccessibleNameInternal(ref, {
                      includeHidden: options.includeHidden, visitedElements: options.visitedElements,
                      embeddedInLabelledBy: 'self', embeddedInLabel: 'none',
                      embeddedInTextAlternativeElement: false, embeddedInTargetElement: 'none'
                    });
                  }).join(' ');
                  if (lbName) return lbName;
                }
                var role = getAriaRole(element) || '';
                if (options.embeddedInLabel !== 'none' || options.embeddedInLabelledBy !== 'none') {
                  var labels = (element.labels || []);
                  var isOwnLabel = false;
                  for (var li = 0; li < labels.length; li++) { if (labels[li] === element) { isOwnLabel = true; break; } }
                  var isOwnLabelledBy = labelledBy ? labelledBy.indexOf(element) !== -1 : false;
                  if (!isOwnLabel && !isOwnLabelledBy) {
                    if (role === 'textbox') {
                      options.visitedElements.add(element);
                      if (element.tagName === 'INPUT' || element.tagName === 'TEXTAREA') return element.value || '';
                      return element.textContent || '';
                    }
                    if (role === 'combobox' || role === 'listbox') {
                      options.visitedElements.add(element);
                      var selected = [];
                      if (element.tagName === 'SELECT') {
                        selected = Array.prototype.slice.call(element.selectedOptions);
                        if (!selected.length && element.options.length) selected.push(element.options[0]);
                      } else {
                        var lb = role === 'combobox' ? findOwned(element, '*').filter(function(e){ return getAriaRole(e) === 'listbox'; })[0] : element;
                        if (lb) selected = findOwned(lb, '[aria-selected="true"]').filter(function(e){ return getAriaRole(e) === 'option'; });
                      }
                      return selected.map(function(o){ return getElementAccessibleNameInternal(o, childOptions); }).join(' ');
                    }
                    if (role === 'progressbar' || role === 'scrollbar' || role === 'slider' || role === 'spinbutton' || role === 'meter') {
                      options.visitedElements.add(element);
                      if (element.hasAttribute('aria-valuetext')) return element.getAttribute('aria-valuetext') || '';
                      if (element.hasAttribute('aria-valuenow')) return element.getAttribute('aria-valuenow') || '';
                      return element.getAttribute('value') || '';
                    }
                    if (role === 'menu') {
                      options.visitedElements.add(element);
                      return '';
                    }
                  }
                }
                var ariaLabel = element.getAttribute('aria-label') || '';
                if (ariaLabel.trim()) { options.visitedElements.add(element); return ariaLabel; }
                if (role !== 'presentation' && role !== 'none') {
                  if (element.tagName === 'INPUT' && ['button','submit','reset'].indexOf(element.type) !== -1) {
                    options.visitedElements.add(element);
                    var val = element.value || '';
                    if (val.trim()) return val;
                    if (element.type === 'submit') return 'Submit';
                    if (element.type === 'reset') return 'Reset';
                    return element.getAttribute('title') || '';
                  }
                  if (element.tagName === 'INPUT' && element.type === 'image') {
                    options.visitedElements.add(element);
                    var ilabels = (element.labels || []);
                    if (ilabels.length && options.embeddedInLabelledBy === 'none') return getAccessibleNameFromAssociatedLabels(ilabels, options);
                    var alt = element.getAttribute('alt') || '';
                    if (alt.trim()) return alt;
                    var ititle = element.getAttribute('title') || '';
                    if (ititle.trim()) return ititle;
                    return 'Submit';
                  }
                  if (element.tagName === 'BUTTON' && !labelledBy) {
                    options.visitedElements.add(element);
                    var blabels = (element.labels || []);
                    if (blabels.length) return getAccessibleNameFromAssociatedLabels(blabels, options);
                  }
                  if (element.tagName === 'OUTPUT' && !labelledBy) {
                    options.visitedElements.add(element);
                    var olabels = (element.labels || []);
                    if (olabels.length) return getAccessibleNameFromAssociatedLabels(olabels, options);
                    return element.getAttribute('title') || '';
                  }
                  if ((element.tagName === 'TEXTAREA' || element.tagName === 'SELECT' || element.tagName === 'INPUT') && !labelledBy) {
                    options.visitedElements.add(element);
                    var flabels = (element.labels || []);
                    if (flabels.length) return getAccessibleNameFromAssociatedLabels(flabels, options);
                    var usePlaceholder = (element.tagName === 'INPUT' && ['text','password','search','tel','email','url'].indexOf(element.type) !== -1) || element.tagName === 'TEXTAREA';
                    var placeholder = element.getAttribute('placeholder') || '';
                    var title = element.getAttribute('title') || '';
                    if (!usePlaceholder || title) return title;
                    return placeholder;
                  }
                  if (element.tagName === 'FIELDSET' && !labelledBy) {
                    options.visitedElements.add(element);
                    for (var c = element.firstElementChild; c; c = c.nextElementSibling) {
                      if (c.tagName === 'LEGEND') return getElementAccessibleNameInternal(c, { includeHidden: options.includeHidden, visitedElements: options.visitedElements, embeddedInLabelledBy: 'none', embeddedInLabel: 'none', embeddedInTextAlternativeElement: true, embeddedInTargetElement: 'none' });
                    }
                    return element.getAttribute('title') || '';
                  }
                  if (element.tagName === 'FIGURE' && !labelledBy) {
                    options.visitedElements.add(element);
                    for (var fc = element.firstElementChild; fc; fc = fc.nextElementSibling) {
                      if (fc.tagName === 'FIGCAPTION') return getElementAccessibleNameInternal(fc, { includeHidden: options.includeHidden, visitedElements: options.visitedElements, embeddedInLabelledBy: 'none', embeddedInLabel: 'none', embeddedInTextAlternativeElement: true, embeddedInTargetElement: 'none' });
                    }
                    return element.getAttribute('title') || '';
                  }
                  if (element.tagName === 'IMG') {
                    options.visitedElements.add(element);
                    var imalt = element.getAttribute('alt') || '';
                    if (imalt.trim()) return imalt;
                    var imtitle = element.getAttribute('title') || '';
                    if (imtitle.trim()) return imtitle;
                    return '';
                  }
                  if (element.tagName === 'TABLE') {
                    options.visitedElements.add(element);
                    for (var tc = element.firstElementChild; tc; tc = tc.nextElementSibling) {
                      if (tc.tagName === 'CAPTION') return getElementAccessibleNameInternal(tc, { includeHidden: options.includeHidden, visitedElements: options.visitedElements, embeddedInLabelledBy: 'none', embeddedInLabel: 'none', embeddedInTextAlternativeElement: true, embeddedInTargetElement: 'none' });
                    }
                    var summary = element.getAttribute('summary') || '';
                    if (summary) return summary;
                  }
                  if (element.tagName === 'AREA') {
                    options.visitedElements.add(element);
                    var aalt = element.getAttribute('alt') || '';
                    if (aalt.trim()) return aalt;
                    var atitle = element.getAttribute('title') || '';
                    if (atitle.trim()) return atitle;
                    return '';
                  }
                  if (element.tagName.toUpperCase() === 'SVG' || element.ownerSVGElement) {
                    options.visitedElements.add(element);
                    for (var sc = element.firstElementChild; sc; sc = sc.nextElementSibling) {
                      if (sc.tagName.toUpperCase() === 'TITLE' && sc.ownerSVGElement) return getElementAccessibleNameInternal(sc, { includeHidden: options.includeHidden, visitedElements: options.visitedElements, embeddedInLabelledBy: 'self', embeddedInLabel: 'none', embeddedInTextAlternativeElement: false, embeddedInTargetElement: 'none' });
                    }
                    if (element.ownerSVGElement && element.tagName.toUpperCase() === 'A') {
                      var xtitle = element.getAttribute('xlink:title') || '';
                      if (xtitle.trim()) { options.visitedElements.add(element); return xtitle; }
                    }
                  }
                }
                if (allowsNameFromContent(role, options.embeddedInTargetElement === 'descendant') || options.embeddedInLabelledBy !== 'none' || options.embeddedInLabel !== 'none' || options.embeddedInTextAlternativeElement) {
                  options.visitedElements.add(element);
                  var tokens = [];
                  // 生成定位 name 时（includeAdvisory=false）忽略装饰性伪元素内容，
                  // 否则" (opens in a new window)"等提示会被算进 name，与可见文本不一致。
                  tokens.push(options.includeAdvisory ? getPseudoContent(getPseudo(element, '::before')) : '');
                  // aria-describedby 指向的是"描述"而非"名称"，其引用的后代不应计入可访问名，
                  // 否则描述文本会污染定位用 name（与 Playwright getByRole 行为对齐）。
                  var descRefs = options.includeAdvisory ? [] : getIdRefs(element, element.getAttribute('aria-describedby'));
                  var child = element.firstChild;
                  // 性能：超大子树（如可点击的大容器 / 列表）逐子节点 getComputedStyle 会拖慢单次点击；
                  // 设预算封顶（本层新访问元素上限），超阈值即停止下钻，避免拾取卡顿。
                  // 定位用 name 过长本就无意义，封顶对生成结果影响可忽略。
                  var __budget0 = options.visitedElements.size;
                  while (child) {
                    if (options.visitedElements.size - __budget0 > 4096) break;
                    if (child.nodeType === 1) {
                      if (descRefs.indexOf(child) !== -1) { child = child.nextSibling; continue; }
                      var cs = getComputedStyleSafe(child);
                      var d = cs ? (cs.display || 'inline') : 'inline';
                      var tk = getElementAccessibleNameInternal(child, childOptions);
                      if (d !== 'inline' || child.tagName === 'BR') tk = ' ' + tk + ' ';
                      tokens.push(tk);
                    } else if (child.nodeType === 3) {
                      tokens.push(child.textContent || '');
                    }
                    child = child.nextSibling;
                  }
                  tokens.push(options.includeAdvisory ? getPseudoContent(getPseudo(element, '::after')) : '');
                  var name = tokens.join('');
                  if (name.trim()) return name;
                }
                if (role !== 'presentation' && role !== 'none' || element.tagName === 'IFRAME') {
                  options.visitedElements.add(element);
                  var ttl = element.getAttribute('title') || '';
                  if (ttl.trim()) return ttl;
                }
                options.visitedElements.add(element);
                return '';
              }
              function getElementAccessibleName(element, includeAdvisory) {
                var role = getAriaRole(element) || '';
                if (kProhibitName.indexOf(role) !== -1) return '';
                return normalizeAccessibleName(getElementAccessibleNameInternal(element, {
                  includeHidden: false, visitedElements: new Set(),
                  embeddedInLabelledBy: 'none', embeddedInLabel: 'none',
                  embeddedInTextAlternativeElement: false, embeddedInTargetElement: 'self',
                  includeAdvisory: (includeAdvisory !== false)
                }));
              }
              // 返回 {name, cleaned}：name 为剔除装饰性伪元素/描述文本后的干净可见文本；
              // cleaned=true 表示原 name 含被剔除的提示性文本，生成 @RoleElement 时需用 exact=false 子串匹配。
              function getNameInfo(el) {
                var clean = getElementAccessibleName(el, false);
                var raw = getElementAccessibleName(el, true);
                return { name: clean, cleaned: (clean !== raw) };
              }

              // 推导标题层级：优先 aria-level，否则 h1–h6 标签，无则 0（不限层级）。
              // 供 step 4 的 heading 角色生成 @RoleElement(level=N)，对齐 getByRole(HEADING).setLevel(n)。
              function getHeadingLevel(el) {
                var al = el.getAttribute && el.getAttribute('aria-level');
                if (al) { var n = parseInt(al, 10); if (!isNaN(n) && n > 0) return n; }
                var m = /^H([1-6])$/.exec((el.tagName || '').toUpperCase());
                if (m) return parseInt(m[1], 10);
                return 0;
              }

              // 无语义角色：这类元素通常不是用户想捕获的"控件"，应向上回溯
              var NON_ROLE = { generic:1, none:1, presentation:1 };
              // 可作为点击目标的"交互角色"：仅这些角色才用 role+name 定位（对齐 pause 优先级）；
              // region/navigation/list 等容器角色不算，避免点文本时误抓到外层容器。
              var INTERACTIVE_ROLES = { button:1, link:1, checkbox:1, radio:1, tab:1,
                menuitem:1, menuitemcheckbox:1, menuitemradio:1, option:1, 'switch':1,
                textbox:1, combobox:1, listbox:1, searchbox:1, slider:1, spinbutton:1, treeitem:1 };

              // 判断 id 是否"稳定"（可作为持久选择器）：排除自动生成/含长数字/非法字符的 id
              function isStableId(id) {
                if (!id) return false;
                if (id.length > 40) return false;
                if (/\\d{4,}/.test(id)) return false;               // 连续 4+ 数字，疑似动态
                return /^[A-Za-z][\\w-]*$/.test(id);                 // 首字母 + 单词字符/连字符
              }
              function ownVisibleText(el) {
                return (el.textContent || '').replace(/\\s+/g, ' ').trim();
              }
              // 是否"可输入"元素（对齐 page.pause() 的 fill 录制范围）：文本框/多行/数字/搜索/
              // contenteditable，以及 role 为 textbox/searchbox/spinbutton 的控件。
              function isEditable(el) {
                if (!el) return false;
                var tg = (el.tagName || '').toLowerCase();
                if (tg === 'textarea') return true;
                if (tg === 'input') {
                  var type = (el.getAttribute('type') || 'text').toLowerCase();
                  return ['text','search','email','tel','url','password','number'].indexOf(type) !== -1;
                }
                if (el.getAttribute && el.getAttribute('contenteditable') === 'true') return true;
                var r = (getRole(el) || '').toLowerCase();
                return r === 'textbox' || r === 'searchbox' || r === 'spinbutton';
              }
              // 取可输入元素的当前值（input/textarea 用 .value；contenteditable 用 textContent）
              function editableValue(el) {
                if (!el) return '';
                var tg = (el.tagName || '').toLowerCase();
                if (tg === 'input' || tg === 'textarea') return el.value != null ? el.value : '';
                return (el.textContent || '');
              }
              // 生成最短可用 css 路径（tag + :nth-of-type 链，遇稳定 id 祖先即止）——兜底用，标记需人工确认
              function cssPathOf(el) {
                // 对齐 page.pause()：shadow DOM 场景下，攀父链遇到 shadowRoot 边界时停在"影子宿主"上，
                // 不再跨出 shadow（否则会产出跨越 shadow 边界、运行期无效的 css）。得到的路径仅在该 shadow
                // 树内有效；若整条都无锚点则为纯位置链，由 __isNthOnlyCss 按原规则拦截/标记。
                function __cssParent(n) {
                  if (!n) return null;
                  var p = n.parentElement;
                  if (p) return p;
                  // 若在 shadowRoot 内，parentElement 为空；取 shadow host 作为边界锚（不继续向上穿出）。
                  var rn = null;
                  try { rn = n.getRootNode ? n.getRootNode() : null; } catch (e) {}
                  if (rn && rn.host) return rn.host;   // 影子宿主：作为该链在 shadow 边界的停止锚点
                  return null;
                }
                var parts = [], node = el, depth = 0;
                // 第一趟：与原算法一致，最多 5 层内若遇到稳定 #id 则用作锚点并停止。
                // shadow DOM 边界处理：元素在 shadow 内时 parent 为影子宿主（host）；host 属于外层树，
                // 下一轮其 __cssParent 取外层 parentElement 继续向上，故不在此"break"，而是把 host 标签
                // 作为路径普通一段带入（与内部元素标签并列），既保留 shadow 内目标元素自身的 tag，
                // 又通过 host 自然衔接外层祖先，产出的 css 形如 "host > #inner-id"（仍仅在该 shadow 树内有效）。
                while (node && node.nodeType === 1 && depth++ < 5) {
                  var idv = node.getAttribute && node.getAttribute('id');
                  if (isStableId(idv)) { parts.unshift('#' + idv); break; }
                  var sel = node.tagName.toLowerCase();
                  var parent = __cssParent(node);
                  if (parent && parent !== (node.getRootNode ? node.getRootNode().host : null)) {
                    // 普通父节点：计算同级 nth-of-type（与 Playwright css 引擎一致）
                    var same = [];
                    for (var c = parent.firstElementChild; c; c = c.nextElementSibling) {
                      if (c.tagName === node.tagName) same.push(c);
                    }
                    if (same.length > 1) sel += ':nth-of-type(' + (same.indexOf(node) + 1) + ')';
                  }
                  // 若 parent 是 shadow host：不额外处理，sel 已是 node 自身标签（已含 nth），下一步 node=host 进入外层树。
                  parts.unshift(sel);
                  node = parent;
                }
                var css = parts.join(' > ');
                // 【稳定性增强】若 5 层内无任何锚点（#id/.class/[attr]），得到的是纯 tag 位置链
                // （如 "div:nth-of-type(3) > div"），这类选择器随布局微变即失效（page.pause 也仅把 css 作最后兜底）。
                // 继续向上回溯到第一个带锚点的祖先，把它作为前缀锚点，使最终 css 形如
                // "#app > div:nth-of-type(2) > span"，既有稳定锚点又保留局部层级，鲁棒性显著提升。
                // 上限放宽到 12 层（仅作锚点寻找，不计入选择器长度），避免极端深嵌套时仍退化为纯布局链。
                //
                // 【关键限定】该锚点增强**仅在"非区域扫描态"启用**：
                //   区域扫描（window.__roleScanRoot 非空）无差别遍历区域内全部元素，大量无语义布局 div
                //   会退化出纯 tag 位置链，本就该被 __recordPick 的 __isNthOnlyCss 拦截；若此处先给它加上
                //   "#app " 锚点前缀，__isNthOnlyCss 因见到 '#' 而 return false，导致拦截失效、记录下
                //   "#app > div:nth-of-type(2) > ..." 这种长 css（即"区域选择出现很长 css"的回归根因）。
                //   因此区域态保持原始纯位置链，__isNthOnlyCss 才能正确判真并拦截噪音；
                //   手动点选态保留锚点前缀，与 page.pause 一致地提升单点元素的 css 兜底稳定性。
                // 附加防御：若原始 css 已是整页级骨架链（含 body / html 段落），绝不画蛇添足加锚点——
                // 这类链无业务价值，加了锚点（如 ".Mozilla body > ..."）反而绕开 __recordPick 的
                // body 开头拦截、记录下更长更废的选择器。
                if (__isNthOnlyCss(css) && !window.__roleScanRoot
                    && css.indexOf('body') === -1 && css.indexOf('html') === -1) {
                  var anchor = node, aDepth = 0;
                  while (anchor && anchor.nodeType === 1 && aDepth++ < 12) {
                    var aid = anchor.getAttribute && anchor.getAttribute('id');
                    if (isStableId(aid)) { return aid ? ('#' + aid + ' ' + css) : css; }
                    // 带 .class 或 [attr] 的祖先也可作锚点（优先 #id，这里退一步取首个含 class/attr 的）
                    if (anchor.className && ('' + anchor.className).trim()) {
                      var cls = ('' + anchor.className).trim().split(/\\s+/)[0];
                      if (cls) return '.' + cls + ' ' + css;
                    }
                    var attrs = anchor.attributes;
                    if (attrs) {
                      for (var ai = 0; ai < attrs.length; ai++) {
                        var an = attrs[ai].name;
                        if (an !== 'class' && an !== 'style' && an !== 'id' && an.indexOf('data-') === 0) {
                          return '[' + an + '] ' + css;
                        }
                      }
                    }
                    anchor = anchor.parentElement;
                  }
                }
                return css;
              }
              // 判断 css 兜底路径是否为"纯布局型"（无锚点路径）。
              // 典型垃圾形态：
              //   div:nth-of-type(3) > div > div > div:nth-of-type(3) > div:nth-of-type(17)
              //   div > div > span
              // 判定标准：整条路径里找不到任何**语义锚点**——即没有 #id、没有 .class、
              // 没有属性选择器（[data-testid=...] / [data-i18n=...]），只由 tag 名与
              // 位置索引（nth-of-type / nth-child）拼接而成。
              // 这类选择器完全依赖 DOM 的兄弟顺序与层级，页面任何布局微调（插一个 div、
              // 换一次栅格）即全部失效，且对阅读者毫无业务含义。
              // 注意：只要路径中**任意一段**带 #id / .class / [attr]，就认为有锚点而放行，
              // 因为该段能把定位约束在一个语义节点上，后续的 nth-of-type 只是相对偏移。
              //
              // 【适用范围】仅用于**区域扫描**，由 window.__roleScanRoot 非空判定区域态
              // （注意不能用 __scanMode：__roleScanPage 入口会把它统一覆写为 'page'）。
              // 区域扫描 querySelectorAll('*') 无差别遍历，区域内大量无语义布局 div 会退化出这类
              // 路径，是噪音的唯一来源，需要过滤。而手动点选 / 整页扫描不使用本判定——page.pause()
              // 的 selectorGenerator 永远产出选择器、从不拒绝录制元素，那两个场景保持与 pause 一致。
              function __isNthOnlyCss(css) {
                if (!css) return false;
                var segs = css.split(' > ');
                for (var si = 0; si < segs.length; si++) {
                  var sg = segs[si];
                  if (sg.indexOf('#') !== -1) return false;   // #id 锚点
                  if (sg.indexOf('.') !== -1) return false;   // .class 锚点
                  if (sg.indexOf('[') !== -1) return false;   // [attr=...] 锚点（testid / data-i18n 等）
                }
                // 全程无锚点：无论有没有 nth-of-type，都是纯 tag 位置链 → 判为噪音。
                // （含 nth 的靠索引，不含 nth 的靠层级，稳定性同样为零。）
                return true;
              }

              // ============================================================================
              // 定位策略链（忠实对齐 page.pause() 的 selectorGenerator 打分序，分低者优先）：
              //   testId(1) < role+name(100) < placeholder(120) < label(140)
              //     < altText(160) < text(180) < title(200) < css #id(500)
              //     < roleWithoutName(510) < [name=](520) / [type=](521) < css 路径(needsReview)
              //   （roleWithoutName(510) 已对齐：纯无 name 语义角色生成 @RoleElement(role=...) 无 name，
              //     经 Binder 无 name 重载 getByRole(role) 定位，与 pause 的 roleWithoutName 一致。）
              // 算法分两步（与 recorder 一致）：
              //   ① 重定位：label → 关联控件；再向上回溯（≤5 层）找交互角色祖先，
              //      点按钮内文字/图标时抓按钮本身；
              //   ② 在重定位后的目标元素上按打分序依次尝试候选：
              //      1. data-testid 族        → getByTestId（本项目扩展：data-testid/-test-id/-test/-qa 同权）
              //      2. 交互角色 + 可访问名    → getByRole（打分 100，先于 placeholder/label；生成 @RoleElement，保留 NLS 多语言）
              //         注：仅 NON_ROLE（generic/none/presentation）外的"有意义"语义角色命中；
              //         无语义角色元素继续走下方 data-i18n / alt / text 兜底。
              //      2.5 非交互元素的 data-i18n 多语言 key → @Element("[data-i18n=...]")（CSS 属性选择器，本项目约定，
              //          仅在上方 role+name 未命中时生效：交互控件已走 role，这里专补 generic span/div 等稳定定位）
              //      3. INPUT/TEXTAREA 的 placeholder → getByPlaceholder（pause 仅对这两类标签，打分 120）
              //      4. 表单控件原生关联 label → getByLabel（打分 140，置于 role+name 之后，与 pause 一致）
              //         注：仅"直接点击 <label> 本身"时生效（originalIsLabel）；若点是表单控件本身，则跳过本分支，
              //         避免"点了输入框却生成 label 策略"的错位。
              //      5. IMG/AREA/INPUT 的 alt  → getByAltText（pause 限定 APPLET/AREA/IMG/INPUT，打分 160，先于 text）
              //      6. 可见文本（≤80 字符，pause 截断阈值）→ getByText
              //         注：pause 中先试子串/前缀（非 exact，score 180）再试精确（exact，score 185）；
              //         本项目先出非 exact 候选（稳定容忍文案微调），再出 exact。
              //      7. title                  → getByTitle（200）
              //      8. 稳定 id                → #id（500 分，必须排在语义候选之后）
              //      8.5 [name=]（520）/ input|textarea|select[type=]（521）→ css 属性选择器
              //          （对齐 pause 属性级 css 兜底层级，排在长 css 路径之前）
              //      9. css 路径兜底（needsReview=true）
              // 顺序短路模型：本项目以"按打分序短路尝试、首个命中即返回"来等价 pause 的打分排序；
              // 故无需单独实现 penalizeScoreForLength（长文本 >80 字符直接跳过 text 即为长度惩罚的特例）。
              // 仅返回"原始片段"（strategy/role/name/attr/value/id/css），