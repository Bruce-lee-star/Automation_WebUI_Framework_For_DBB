package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.google.gson.Gson;
import com.microsoft.playwright.Page;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交互式拾取器：像 {@code page.pause()} 一样，在浏览器里点击想要的元素，
 * 自动抓取被点元素的 a11y role / name，再交给 {@link RoleElementPageGenerator} 生成 {@code @RoleElement} 代码。
 *
 * <p>与整页 {@code dumpAccessibilityRoles} 不同，本类只采集“用户主动点击”的元素，
 * 适合页面导航/列表很冗杂、只需其中少数几个控件的场景。
 *
 * <h3>原理</h3>
 * 在页面注入捕获阶段的 click / keydown 监听：点击任意元素即拦截其默认行为（避免跳转），
 * 用 {@code element.computedRole}/{@code element.computedName}（带回退）取出 a11y 信息并暂存；
 * 按 ESC 结束拾取。测试线程在 {@code pick(...)} 内阻塞等待，用户在真实浏览器中点选。
 *
 * <h3>前置</h3>
 * 须在<b>有头（headed）</b>浏览器中运行，且页面已导航到目标页。
 *
 * <pre>
 * Page pw = loginPage.getPage();
 * List&lt;RoleEntry&gt; picks = RoleElementPicker.pick(pw);
 * RoleElementPageGenerator.write(picks, "src/test/java",
 *         "com.hsbc...tests.pages", "LoginPage", "nls/login.nls.json");
 * // 或一步到位：
 * RoleElementPicker.pickAndWrite(pw, "src/test/java",
 *         "com.hsbc...tests.pages", "LoginPage", "nls/login.nls.json");
 * </pre>
 */
public final class RoleElementPicker {

    private static final Logger log = LoggerFactory.getLogger(RoleElementPicker.class);

    /** 拾取等待上限（毫秒）：10 分钟 */
    private static final long PICK_TIMEOUT_MS = 600_000;

    /** 用于把参数安全序列化为 JS 字面量（避免手动拼接转义错误） */
    private static final Gson GSON = new Gson();

    /** 开启拾取模式：注入监听 + 顶部提示条 */
    private static final String START_SCRIPT = """
            (() => {
              if (window.__rolePickActive) return;
              window.__rolePickActive = true;
              window.__rolePicks = window.__rolePicks || [];
              window.__pickDone = false;

              // 拾取提示与计数不再用左下角独立控件，而是写入主面板标题状态条（#__roleStatus），
              // 由 openPanel(...) 的常驻面板呈现（见 PANEL_SCRIPT 与 __rolePickClick）。

              // ============================================================================
              // Playwright 注入脚本 roleUtils.ts 算法的忠实移植（getAriaRole + getElementAccessibleName）
              // 来源：microsoft/playwright packages/playwright-core/src/server/injected/roleUtils.ts
              // page.pause()/Inspector 的“拾取元素”正是用这套 W3C ARIA + accname 算法计算 role 与 name，
              // 因此直接移植，保证 picker 结果与 Inspector 完全一致（不再依赖浏览器 computedRole/computedName）。
              // ============================================================================
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
                if (!explicitRole) return getImplicitAriaRole(element);
                if ((explicitRole === 'none' || explicitRole === 'presentation') && !hasGlobalAriaAttribute(element)) return getImplicitAriaRole(element);
                return explicitRole;
              }
              function getRole(el) { return getAriaRole(el) || 'generic'; }

              // 把 <label> 解析为其关联的表单控件：点击复选框/单选/文本框的“标签文字”时，
              // 对齐 page.pause()/Inspector——直接拾取它所指代的控件（role + name=标签文本），
              // 而非无语义的 label 文本节点。
              //   - HTMLLabelElement.control 覆盖“包裹式”关联（含 display:none 的隐藏控件）；
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
                  // 否则“ (opens in a new window)”等提示会被算进 name，与可见文本不一致。
                  tokens.push(options.includeAdvisory ? getPseudoContent(getPseudo(element, '::before')) : '');
                  // aria-describedby 指向的是“描述”而非“名称”，其引用的后代不应计入可访问名，
                  // 否则描述文本会污染定位用 name（与 Playwright getByRole 行为对齐）。
                  var descRefs = options.includeAdvisory ? [] : getIdRefs(element, element.getAttribute('aria-describedby'));
                  var child = element.firstChild;
                  while (child) {
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

              // 无语义角色：这类元素通常不是用户想捕获的“控件”，应向上回溯
              var NON_ROLE = { generic:1, none:1, presentation:1 };
              // 可作为点击目标的“交互角色”：仅这些角色才用 role+name 定位（对齐 pause 优先级）；
              // region/navigation/list 等容器角色不算，避免点文本时误抓到外层容器。
              var INTERACTIVE_ROLES = { button:1, link:1, checkbox:1, radio:1, tab:1,
                menuitem:1, menuitemcheckbox:1, menuitemradio:1, option:1, 'switch':1,
                textbox:1, combobox:1, listbox:1, searchbox:1, slider:1, spinbutton:1, treeitem:1 };

              // 判断 id 是否“稳定”（可作为持久选择器）：排除自动生成/含长数字/非法字符的 id
              function isStableId(id) {
                if (!id) return false;
                if (id.length > 40) return false;
                if (/\\d{4,}/.test(id)) return false;               // 连续 4+ 数字，疑似动态
                return /^[A-Za-z][\\w-]*$/.test(id);                 // 首字母 + 单词字符/连字符
              }
              function ownVisibleText(el) {
                return (el.textContent || '').replace(/\\s+/g, ' ').trim();
              }
              // 生成最短可用 css 路径（tag + :nth-of-type 链，遇稳定 id 祖先即止）——兜底用，标记需人工确认
              function cssPathOf(el) {
                var parts = [], node = el, depth = 0;
                while (node && node.nodeType === 1 && depth++ < 5) {
                  var idv = node.getAttribute && node.getAttribute('id');
                  if (isStableId(idv)) { parts.unshift('#' + idv); break; }
                  var sel = node.tagName.toLowerCase();
                  var parent = node.parentElement;
                  if (parent) {
                    var same = [];
                    for (var c = parent.firstElementChild; c; c = c.nextElementSibling) {
                      if (c.tagName === node.tagName) same.push(c);
                    }
                    if (same.length > 1) sel += ':nth-of-type(' + (same.indexOf(node) + 1) + ')';
                  }
                  parts.unshift(sel);
                  node = parent;
                }
                return parts.join(' > ');
              }

              // ============================================================================
              // 定位策略链（忠实对齐 page.pause() 的 selectorGenerator 打分序，分低者优先）：
              //   testId(1) < placeholder(100) < label(120) < role+name(140)
              //     < altText(160) < text(180) < title(200) < css #id(500) < css 兜底
              // 算法分两步（与 recorder 一致）：
              //   ① 重定位：label → 关联控件；再向上回溯（≤5 层）找交互角色祖先，
              //      点按钮内文字/图标时抓按钮本身；
              //   ② 在重定位后的目标元素上按打分序依次尝试候选：
              //      1. data-testid 族        → getByTestId
              //      2. INPUT/TEXTAREA 的 placeholder → getByPlaceholder（pause 仅对这两类标签）
              //      3. 表单控件原生关联 label → getByLabel（先于 role+name，打分 120 < 140）
              //         注：仅“直接点击控件”时生效；若点是 <label> 本身（被 resolveLabel 重定位成控件），
              //         则跳过此步回退到 ④ role+name，避免 label 与 input 产出同一种 label 策略。
              //      4. 交互角色 + 可访问名    → getByRole（生成 @RoleElement，保留 NLS 多语言）
              //      4.5 非交互元素的 data-i18n 多语言 key → @RoleElement(i18n=...)（本项目扩展，仅在上方
              //          role+name 未命中时生效：交互控件已走 role，这里专补 generic span/div 等稳定定位）
              //      5. IMG/AREA 的 alt        → getByAltText（pause 中先于 text，160 < 180）
              //      6. 可见文本（≤80 字符，pause 截断阈值）→ getByText
              //      7. title                  → getByTitle
              //      8. 稳定 id                → #id（500 分，必须排在语义候选之后）
              //      9. 兜底                   → css 路径（needsReview）
              // 仅返回“原始片段”（strategy/role/name/attr/value/id/css），
              // 由 Java 侧负责拼接并转义选择器字符串，避免在 Java 文本块里处理引号转义。
              // ============================================================================
              // 与 Java 侧 normalize() 保持一致：把 name 归一化（回车换行→换行、nbsp→空格、折叠空白、trim），
              // 用于和预加载的 nls 反向查表（已用同样规则规范化）做精确匹配。
              function normName(s) {
                return (s || '').replace(/\\r\\n/g, '\\n').replace(/\\u00A0/g, ' ').replace(/\\s+/g, ' ').trim();
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
                if (window.__nlsTemplates && window.__nlsTemplates.length) {
                  var txt = normName(s);
                  var best = null, bestLen = -1;
                  for (var i = 0; i < window.__nlsTemplates.length; i++) {
                    var re = window.__nlsTemplates[i];
                    if (!re || !re[0]) continue;
                    try {
                      if (new RegExp(re[0]).test(txt)) {
                        var litLen = re[0].replace(/\\(\\.\\*\\?\\)/g, '').length;
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
                  var labels = el.labels;
                  if (labels && labels.length) {
                    var s = normName(labels[0].textContent || '');
                    if (s) return s.slice(0, 80);
                  }
                } catch (e) {}
                return '';
              }
              window.__computePick = function(t) {
                // ① 重定位（对齐 recorder retarget）：label → 控件；向上找交互角色祖先
                // originalIsLabel 标记“用户点的是 <label> 本身”而非直接点控件——
                // 此时应解析到控件后走 role+name（见 resolveLabel 注释“role + name=标签文本”），
                // 不能再用 getByLabel，否则 label 与 input 会产出同一种 label 策略。
                var originalIsLabel = !!(t && t.tagName === 'LABEL');
                var el = resolveLabel(t);
                var cur = t, guard = 0;
                while (cur && guard++ < 5) {
                  var node = resolveLabel(cur);
                  if (INTERACTIVE_ROLES[(getRole(node) || '').toLowerCase()]) { el = node; break; }
                  cur = cur.parentElement;
                }
                var tag = (el.tagName || '').toLowerCase();
                function done(o) {
                  o.tag = tag;
                  o.text = ownVisibleText(el).slice(0, 120);
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
                  return o;
                }
                // ② 按 pause 打分序出候选
                // 1. testId（打分 1，最优）
                var testAttrs = ['data-testid','data-test-id','data-test','data-qa'];
                for (var i = 0; i < testAttrs.length; i++) {
                  var tv = el.getAttribute(testAttrs[i]);
                  if (tv && tv.trim()) return done({ strategy:'testid', attr:testAttrs[i], value:tv.trim(), name:tv.trim() });
                }
                // 2. placeholder（100；pause 仅对 INPUT/TEXTAREA）
                if (tag === 'input' || tag === 'textarea') {
                  var ph = el.getAttribute('placeholder');
                  if (ph && ph.trim()) return done({ strategy:'placeholder', attr:'placeholder', value:ph.trim(), name:ph.trim() });
                }
                // 3. 原生关联 label → getByLabel（120，先于 role+name）
                //    仅当用户“直接点击表单控件”时生效；若点是 <label> 本身（originalIsLabel），
                //    该控件是 resolveLabel 重定位来的，应回退到下方 role+name，避免 label/input 重复为 label 策略。
                if (!originalIsLabel) {
                  var lbl = labelTextOf(el);
                  if (lbl) return done({ strategy:'label', name:lbl });
                }
                // 4. 交互角色 + 可访问名（140）
                var r = (getRole(el) || '').toLowerCase();
                if (INTERACTIVE_ROLES[r]) {
                  var nameInfo = getNameInfo(el);
                  var nm = nameInfo.name;
                  if (nm) {
                    // 反查 nls key：用归一化后的 name 在预加载的反查表里查表（精确 + 前缀/子串 + 模板）。
                    // 命中则直接用真实 key（@RoleElement 复用既有 nls 多语言表），未命中则留空回退 slug。
                    // cleaned 触发条件：
                    //   a) nameInfo.cleaned —— 剔除了装饰性伪元素/描述文本（如 " (opens in a new window)"）；
                    //   b) !nls.exact —— NLS 非精确命中（前缀/子串/模板），运行时须子串匹配才能定位。
                    var nls = nlsKeyInfo(nm);
                    return { strategy:'role', role:r, name:nm, key:nls.key, matched: !!nls.key,
                      cleaned: nameInfo.cleaned || !nls.exact,
                      tag:tag, text: ownVisibleText(el).slice(0, 120) };
                  }
                  // 有角色无名称（pause 打 510 分，比 #id 还差）：继续走下方候选
                }
                // 4.5 data-i18n 多语言 key（本项目扩展）：走到这里说明不是交互控件（交互控件已在上方 role+name 命中）。
                //     对非交互元素（generic 的 span/div 等），data-i18n 的属性值即多语言 key——语言无关、
                //     比可见文本（会随语言变化、且可能重复）稳定得多，故排在 alt/text 之前。
                var i18n = el.getAttribute('data-i18n');
                if (i18n && i18n.trim()) return done({ strategy:'i18n', value:i18n.trim(), name:i18n.trim() });
                // 5. alt（160；pause 中先于 text）
                if (tag === 'img' || tag === 'area') {
                  var alt = el.getAttribute('alt');
                  if (alt && alt.trim()) return done({ strategy:'altText', attr:'alt', value:alt.trim(), name:alt.trim() });
                }
                // 6. 可见文本（180；pause 截断阈值 80 字符）
                var ot = ownVisibleText(el);
                if (ot && ot.length <= 80) return done({ strategy:'text', name:ot, exact:true });
                // 7. title（200）
                var title = el.getAttribute('title');
                if (title && title.trim()) return done({ strategy:'title', attr:'title', value:title.trim(), name:title.trim() });
                // 8. 稳定 id（500）
                var id = el.getAttribute('id');
                if (isStableId(id)) return done({ strategy:'id', id:id });
                // 9. css 兜底
                return done({ strategy:'css', css: cssPathOf(el), needsReview:true });
              };

              // 定位器签名（与 Java 端 RoleElementPageGenerator.locatorKey 规则一致）：
              // role 按 role+name/key；id/css 按选择器；其余语义策略按 strategy+name。
              // 同一签名重复点击不入列、计数不增，避免生成端才去重导致“点了但计数不变”的困惑。
              window.__pickSig = function(pick) {
                if (!pick) return '';
                if (pick.strategy === 'role') {
                  return 'role:' + (pick.role || '') + ':' + (pick.key || pick.name || '');
                }
                if (pick.strategy === 'i18n') return 'i18n:' + (pick.name || '');
                if (pick.strategy === 'id') return 'id:' + (pick.id || '');
                if (pick.strategy === 'css') return 'css:' + (pick.css || '');
                return pick.strategy + ':' + (pick.name || '');
              };
              // 每次注入都从既有 picks 重建签名表，保证与 __rolePicks 严格同步
              window.__rolePickSigs = {};
              (window.__rolePicks || []).forEach(function(p) {
                var s = window.__pickSig(p);
                if (s) window.__rolePickSigs[s] = true;
              });
              window.__rolePickClick = function(event) {
                var t = event.target;
                if (t && t.closest && t.closest('#__rolePanel, #__roleCodeOverlay')) {
                  return;
                }
                event.preventDefault();
                event.stopImmediatePropagation();
                var pick = window.__computePick(t);
                var sig = window.__pickSig(pick);
                var dup = sig && window.__rolePickSigs[sig];
                if (!dup) {
                  if (sig) window.__rolePickSigs[sig] = true;
                  window.__rolePicks.push(pick);
                }
                var prev = t.style.outline;
                t.style.outline = dup ? '3px solid #ff9800' : '3px solid #ffeb3b';
                setTimeout(function() { t.style.outline = prev; }, 400);
                // 拾取提示与计数写入主面板标题状态条（替代原左下角 banner）
                var statusEl = document.getElementById('__roleStatus');
                if (statusEl) {
                  var extra = dup ? '（重复，已忽略）'
                    : ((pick && pick.matched) ? '（key=' + pick.key + '）' : '');
                  statusEl.textContent =
                    'RoleElement Picker：已拾取 ' + window.__rolePicks.length + ' 个' + extra + '，按 ESC 结束';
                }
              };
              window.__rolePickKey = function(event) {
                if (event.key === 'Escape') { window.__pickDone = true; }
              };
              document.addEventListener('click', window.__rolePickClick, true);
              document.addEventListener('keydown', window.__rolePickKey, true);
            })();
            """;

    /** 关闭拾取模式：移除监听 + 移除提示条 */
    private static final String STOP_SCRIPT = """
            (() => {
              if (!window.__rolePickActive) return;
              document.removeEventListener('click', window.__rolePickClick, true);
              document.removeEventListener('keydown', window.__rolePickKey, true);
              window.__rolePickActive = false;
            })();
            """;

    /** 弹出可复制代码面板（参数 code 为生成的源码） */
    private static final String SHOW_PANEL_SCRIPT = """
            (() => {
              var code = window.__pickerCode || '';
              var old = document.getElementById('__roleCodeOverlay');
              if (old) old.remove();
              window.__codePanelClosed = false;

              var overlay = document.createElement('div');
              overlay.id = '__roleCodeOverlay';
              overlay.style.cssText = 'position:fixed;inset:0;z-index:2147483647;' +
                'background:rgba(0,0,0,.55);display:flex;align-items:center;justify-content:center;';

              var panel = document.createElement('div');
              panel.style.cssText = 'position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);' +
                'width:min(860px,92vw);max-height:86vh;display:flex;flex-direction:column;' +
                'background:#1e1e1e;color:#e0e0e0;border-radius:8px;box-shadow:0 8px 30px rgba(0,0,0,.5);' +
                'font:13px/1.5 Consolas,Monaco,monospace;overflow:hidden;';

              var header = document.createElement('div');
              header.style.cssText = 'padding:10px 14px;background:#1e88e5;color:#fff;font-weight:bold;' +
                'display:flex;align-items:center;justify-content:space-between;cursor:move;';
              var title = document.createElement('span');
              title.textContent = '生成的 @RoleElement Page 代码';
              header.appendChild(title);
              var status = document.createElement('span');
              status.style.cssText = 'font-weight:normal;font-size:12px;opacity:.9;';
              header.appendChild(status);

              (function() {
                var dragging = false, sx = 0, sy = 0, ox = 0, oy = 0;
                header.addEventListener('mousedown', function(e) {
                  if (e.button !== 0) return;
                  dragging = true;
                  sx = e.clientX; sy = e.clientY;
                  ox = panel.offsetLeft; oy = panel.offsetTop;
                  e.preventDefault();
                });
                document.addEventListener('mousemove', function(e) {
                  if (!dragging) return;
                  var nl = ox + e.clientX - sx;
                  var nt = oy + e.clientY - sy;
                  // 视口边界约束：保证标题栏始终留在屏幕内，不会被顶边/侧边裁掉而抓不住
                  var w = panel.offsetWidth, h = panel.offsetHeight;
                  if (nl < 0) nl = 0;
                  if (nl + w > window.innerWidth) nl = window.innerWidth - w;
                  if (nt < 0) nt = 0;
                  if (h <= window.innerHeight && nt + h > window.innerHeight) nt = window.innerHeight - h;
                  panel.style.left = nl + 'px';
                  panel.style.top = nt + 'px';
                  panel.style.right = 'auto';
                  panel.style.bottom = 'auto';
                  panel.style.transform = 'none';
                });
                document.addEventListener('mouseup', function() { dragging = false; });
              })();

              var ta = document.createElement('textarea');
              ta.readOnly = true;
              ta.value = code;
              ta.style.cssText = 'flex:1;min-height:340px;margin:0;padding:12px 14px;border:0;resize:none;' +
                'background:#1e1e1e;color:#d4d4d4;font:13px/1.55 Consolas,Monaco,monospace;outline:none;white-space:pre;';

              var footer = document.createElement('div');
              footer.style.cssText = 'padding:10px 14px;background:#252526;display:flex;gap:10px;justify-content:flex-end;';

              function mkBtn(text, bg) {
                var b = document.createElement('button');
                b.textContent = text;
                b.style.cssText = 'padding:7px 18px;border:0;border-radius:5px;cursor:pointer;' +
                  'font:13px/1 sans-serif;color:#fff;background:' + bg + ';';
                return b;
              }
              var copyBtn = mkBtn('复制代码', '#43a047');
              var closeBtn = mkBtn('关闭', '#616161');

              copyBtn.onclick = function() {
                function ok() { status.textContent = '已复制到剪贴板 ✔'; copyBtn.textContent = '已复制'; }
                try {
                  if (navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(code).then(ok, function() { fallback(); });
                  } else { fallback(); }
                } catch (e) { fallback(); }
                function fallback() {
                  ta.focus(); ta.select();
                  try { document.execCommand('copy'); ok(); }
                  catch (e2) { status.textContent = '复制失败，请手动选中复制'; }
                }
              };
              closeBtn.onclick = function() {
                window.__codePanelClosed = true;
                overlay.remove();
              };

              footer.appendChild(copyBtn);
              footer.appendChild(closeBtn);
              panel.appendChild(header);
              panel.appendChild(ta);
              panel.appendChild(footer);
              overlay.appendChild(panel);
              (document.body || document.documentElement).appendChild(overlay);
            })();
            """;

    /** 常驻主面板：图标化命令控件（开始/停止合并为切换、复制、终止，关闭用标题栏右侧 X） */
    private static final String PANEL_SCRIPT = """
    (() => {
      // 仅当 openPanel 运行期间（由 localStorage 开关标记）才注入面板；
      // 这样刷新/导航后 addInitScript 会自动重建面板，而正常访问不受影响。
      try { if (localStorage.getItem('__rolePanelEnabled') !== '1') return; } catch (e) { return; }
      function build() {
      var old = document.getElementById('__rolePanel');
      if (old) old.remove();
      // 重建面板时清理上一次遗留的定时器，避免叠加
      if (window.__roleToggleTimer) { clearInterval(window.__roleToggleTimer); window.__roleToggleTimer = null; }
      window.__panelCmds = window.__panelCmds || [];
      window.__pickDone = false;

      function pushCmd(c) { window.__panelCmds.push(c); }

      // 内联 SVG 图标（24x24，currentColor 继承按钮文字色）
      function svg(inner) {
        return '<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" xmlns="http://www.w3.org/2000/svg">' + inner + '</svg>';
      }
      var ICON = {
        start: svg('<path d="M8 5v14l11-7z"/>'),                                                // ▶ 开始
        stop:  svg('<path d="M6 6h12v12H6z"/>'),                                               // ⏹ 停止
        copy:  svg('<path d="M16 1H4a2 2 0 0 0-2 2v12h2V3h12V1zm3 4H8a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z"/>'), // 📋 复制
        abort: svg('<path d="M13 3h-2v10h2V3zm4.83 2.17-1.42 1.42A7 7 0 1 1 7.58 6.59L6.17 5.17a9 9 0 1 0 11.66 0z"/>'), // ⏻ 终止
        close: svg('<path d="M18.3 5.7 12 12l6.3 6.3-1.4 1.4L10.6 13.4 4.3 19.7 2.9 18.3 9.2 12 2.9 5.7 4.3 4.3l6.3 6.3 6.3-6.3z"/>') // ✕ 关闭
      };
      // 统一的图标按钮：圆形/圆角方块、hover 提亮、带 title 作为无障碍提示
      function mkIconBtn(svgHtml, bg, title, onClick) {
        var b = document.createElement('button');
        b.type = 'button';
        b.title = title;
        b.innerHTML = svgHtml;
        b.style.cssText = 'width:34px;height:34px;padding:0;border:0;border-radius:8px;cursor:pointer;' +
          'display:inline-flex;align-items:center;justify-content:center;color:#fff;background:' + bg + ';' +
          'transition:filter .15s;';
        b.onmouseenter = function(){ b.style.filter = 'brightness(1.12)'; };
        b.onmouseleave = function(){ b.style.filter = 'none'; };
        b.onclick = onClick;
        return b;
      }

              var overlay = document.createElement('div');
              overlay.id = '__rolePanel';
              overlay.style.cssText = 'position:fixed;inset:0;z-index:2147483647;' +
                'background:transparent;pointer-events:none;display:flex;' +
                'align-items:flex-end;justify-content:flex-end;padding:16px;';

              var panel = document.createElement('div');
              panel.style.cssText = 'position:fixed;right:16px;bottom:16px;width:min(460px,92vw);max-height:88vh;' +
                'display:flex;flex-direction:column;pointer-events:auto;background:#1e1e1e;color:#e0e0e0;' +
                'border-radius:10px;box-shadow:0 8px 30px rgba(0,0,0,.5);' +
                'font:13px/1.5 Consolas,Monaco,monospace;overflow:hidden;';

              var header = document.createElement('div');
              header.style.cssText = 'padding:10px 12px;background:#1e88e5;color:#fff;font-weight:bold;' +
                'display:flex;align-items:center;gap:10px;cursor:move;';
              var title = document.createElement('span');
              title.style.cssText = 'flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;';
              var nf = window.__nlsFiles || [];
              title.textContent = 'RoleElement 拾取器'
                + (nf.length ? '  (files=' + (nf.length === 1 ? nf[0] : nf[0] + ' (+' + (nf.length - 1) + ')') + ')' : '');
              var status = document.createElement('span');
              status.id = '__roleStatus';
              status.style.cssText = 'font-weight:normal;font-size:12px;opacity:.95;' +
                'white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:190px;flex-shrink:0;';
              status.textContent = '就绪：点 ▶ 开始拾取并在页面点击元素';
              // 关闭面板：标题栏右侧 X 图标（对齐 page.pause() 的 inspector 关闭按钮）
              var closeBtn = mkIconBtn(ICON.close, 'transparent', '关闭面板', function() {
                if (window.__roleToggleTimer) { clearInterval(window.__roleToggleTimer); window.__roleToggleTimer = null; }
                pushCmd('done');
              });
              closeBtn.style.background = 'transparent';
              closeBtn.onmouseenter = function(){ closeBtn.style.background = 'rgba(255,255,255,.2)'; closeBtn.style.filter = 'none'; };
              closeBtn.onmouseleave = function(){ closeBtn.style.background = 'transparent'; closeBtn.style.filter = 'none'; };
              closeBtn.onmousedown = function(e){ e.stopPropagation(); };
              header.appendChild(title);
              header.appendChild(status);
              header.appendChild(closeBtn);

              var toolbar = document.createElement('div');
              toolbar.style.cssText = 'padding:10px 14px;background:#252526;display:flex;gap:8px;align-items:center;';
              // 开始/停止合并为同一个切换控件：空闲显示 ▶ 开始，拾取中显示 ⏹ 停止
              var toggleBtn = mkIconBtn(ICON.start, '#43a047', '开始拾取', function() {
                pushCmd(window.__rolePickActive ? 'stop' : 'start');
              });
              var copyBtn = mkIconBtn(ICON.copy, '#1976d2', '复制代码', function() {
                var ta = document.getElementById('__roleCodeArea');
                var code = ta ? ta.value : '';
                function ok() { status.textContent = '已复制 ✔'; copyBtn.title = '已复制'; }
                try {
                  if (navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(code).then(ok, function() { fb(); });
                  } else { fb(); }
                } catch (e) { fb(); }
                function fb() {
                  if (ta) { ta.focus(); ta.select(); try { document.execCommand('copy'); ok(); }
                  catch (e2) { status.textContent = '复制失败，请手动复制'; } }
                }
              });
              var abortBtn = mkIconBtn(ICON.abort, '#e53935', '终止运行', function() { pushCmd('abort'); });
              toolbar.appendChild(toggleBtn);
              toolbar.appendChild(copyBtn);
              toolbar.appendChild(abortBtn);

              // 根据 window.__rolePickActive 实时同步切换控件的状态（图标/文案/颜色）
              function refreshToggle() {
                var picking = !!window.__rolePickActive;
                toggleBtn.innerHTML = picking ? ICON.stop : ICON.start;
                toggleBtn.title = picking ? '停止拾取' : '开始拾取';
                toggleBtn.style.background = picking ? '#fb8c00' : '#43a047';
              }
              refreshToggle();
              window.__roleToggleTimer = setInterval(refreshToggle, 300);

              var ta = document.createElement('textarea');
              ta.id = '__roleCodeArea';
              ta.readOnly = true;
              ta.value = '';
              ta.style.cssText = 'flex:1;min-height:360px;margin:0;padding:12px 14px;border:0;resize:none;' +
                'background:#1e1e1e;color:#d4d4d4;font:13px/1.55 Consolas,Monaco,monospace;outline:none;white-space:pre;';

              panel.appendChild(header);
              panel.appendChild(toolbar);
              panel.appendChild(ta);

              (function() {
                var dragging = false, sx = 0, sy = 0, ox = 0, oy = 0;
                header.addEventListener('mousedown', function(e) {
                  if (e.button !== 0) return;
                  dragging = true;
                  sx = e.clientX; sy = e.clientY;
                  ox = panel.offsetLeft; oy = panel.offsetTop;
                  e.preventDefault();
                });
                document.addEventListener('mousemove', function(e) {
                  if (!dragging) return;
                  var nl = ox + e.clientX - sx;
                  var nt = oy + e.clientY - sy;
                  // 视口边界约束：保证标题栏始终留在屏幕内，不会被顶边/侧边裁掉而抓不住
                  var w = panel.offsetWidth, h = panel.offsetHeight;
                  if (nl < 0) nl = 0;
                  if (nl + w > window.innerWidth) nl = window.innerWidth - w;
                  if (nt < 0) nt = 0;
                  if (h <= window.innerHeight && nt + h > window.innerHeight) nt = window.innerHeight - h;
                  panel.style.left = nl + 'px';
                  panel.style.top = nt + 'px';
                  panel.style.right = 'auto';
                  panel.style.bottom = 'auto';
                });
                document.addEventListener('mouseup', function() { dragging = false; });
              })();

              overlay.appendChild(panel);
              (document.body || document.documentElement).appendChild(overlay);
      }
      // Playwright addInitScript 在每个新文档解析早期执行，body 可能尚未就绪；
      // 若挂载点（body/documentElement）还未出现则短轮询重试，确保 appendChild 可靠。
      (function waitMount() {
        if (!document.body && !document.documentElement) { setTimeout(waitMount, 10); return; }
        build();
      })();
    })();
    """;

    /** 拾取命令循环的统一返回：动作 + 生成的代码 + 状态文案 */
    private enum PickerAction { CONTINUE, ABORT, DONE }
    private static final class PickerResult {
        final PickerAction action;
        final String code;
        final String statusMsg;
        PickerResult(PickerAction action, String code, String statusMsg) {
            this.action = action;
            this.code = code;
            this.statusMsg = statusMsg;
        }
    }

    /**
     * 统一处理面板命令（start/stop/abort/done）：开始拾取、收集并生成代码、终止、关闭。
     * 结果由调用方用 {@code setStatus/fillCode} 呈现到面板 UI。
     *
     * @return 含后续动作与（stop 时的）生成代码
     */
    private static PickerResult runPickerCommand(Page page, String cmd,
                                                  String packageName, String pageClassName, String... nlsFiles) {
        switch (cmd) {
            case "start":
                start(page, buildNlsReverseJson(Arrays.asList(nlsFiles)));
                return new PickerResult(PickerAction.CONTINUE, null,
                        "RoleElement Picker：点击元素拾取 role/name，按 ESC 结束");
            case "stop": {
                stop(page);
                List<RoleEntry> entries = getEntries(page);
                if (entries.isEmpty()) {
                    return new PickerResult(PickerAction.CONTINUE, "", "未拾取到元素");
                }
                String code = RoleElementPageGenerator.generate(entries, packageName, pageClassName, nlsFiles);
                int matched = 0;
                for (RoleEntry e : entries) {
                    if (e.getResolvedKey() != null) matched++;
                }
                String nlsInfo = (nlsFiles != null && nlsFiles.length > 0)
                        ? "（nls=" + (nlsFiles.length == 1 ? nlsFiles[0] : nlsFiles.length + " 个文件")
                            + "，已反查 " + matched + " 个 key）" : "";
                return new PickerResult(PickerAction.CONTINUE, code,
                        "已生成 " + entries.size() + " 个字段，可复制" + nlsInfo);
            }
            case "abort":
                stop(page);
                return new PickerResult(PickerAction.ABORT, null, null);
            case "done":
            default:
                stop(page);
                return new PickerResult(PickerAction.DONE, null, null);
        }
    }

    /**
     * 用户在面板点击『终止运行』时抛出，用于中断调用方后续代码执行。
     * 调用方可按需捕获以决定是失败退出还是降级处理。
     */
    public static final class PickerAbortedException extends RuntimeException {
        public PickerAbortedException(String message) {
            super(message);
        }
    }

    private RoleElementPicker() {}

    /** 开启拾取模式（手动控制起止时可单独调用，不预加载 nls 反向查表） */
    public static void start(Page page) {
        start(page, "{}");
    }

    /**
     * 开启拾取模式，并预加载 nls 反向查表（JSON：规范化文本值 → key）。
     * 拾取交互角色元素时，会用 a11y name 反查该表，命中则直接用真实 nls key。
     *
     * @param page              Playwright Page
     * @param nlsReverseJson    nls 反向查表 JSON；为 null/空/“{}” 时退化为不反查（回退 slug）
     */
    public static void start(Page page, String nlsReverseJson) {
        // 兼容两种格式：新格式 {exact, templates} 拆开注入；旧格式（纯精确表）整体作为 exact。
        page.evaluate(
                "var __o = " + (nlsReverseJson == null ? "{}" : nlsReverseJson) + ";"
                + " window.__nlsReverse = (__o && __o.exact) ? __o.exact : (__o && __o.templates ? {} : (__o || {}));"
                + " window.__nlsTemplates = (__o && __o.templates) ? __o.templates : [];");
        page.evaluate(START_SCRIPT);
        log.info("[picker] 拾取模式已开启：在浏览器点击元素即可拾取，按 ESC 结束。");
    }

    /**
     * 把 nls 文件构建成“规范化后的文本值 → key”的反向查表 JSON（覆盖所有语言），
     * 供浏览器拾取时把 a11y name 反查为对应 nls key。文件缺失/解析失败时返回 "{}"（退化为不反查）。
     *
     * @param nlsFile nls 文件路径（classpath 相对或文件系统绝对），如 "nls/login.nls.json"
     */
    /**
     * 合并多个 nls 文件构建反向查表（覆盖所有语言）：精确表与模板表均跨文件合并，
     * 同一规范化文本/正则源以首个文件优先（putIfAbsent）。供拾取时把 a11y name 反查为对应 key，
     * 从而支持「一个页面用到多个 nls json」的场景。
     */
    private static String buildNlsReverseJson(List<String> nlsFiles) {
        if (nlsFiles == null || nlsFiles.isEmpty()) return "{}";
        try {
            Map<String, String> exact = new LinkedHashMap<>();
            Map<String, String> templates = new LinkedHashMap<>();
            for (String nlsFile : nlsFiles) {
                if (nlsFile == null || nlsFile.isBlank()) continue;
                Map<String, Map<String, String>> tables = NLSUtils.rawTables(nlsFile);
                for (Map<String, String> table : tables.values()) {
                    if (table == null) continue;
                    for (Map.Entry<String, String> en : table.entrySet()) {
                        // 反查 key 必须基于「页面可见文本」：nls 值里常内嵌 <a>/<strong>/<img> 与
                        // &nbsp;/&copy; 等实体，浏览器渲染后可见文本已无标签，故精确表与模板正则
                        // 一律用 NLSUtils.visibleText / templateRegexSource（二者都会剥 HTML + 解码实体）。
                        // 否则如 tab_security_device("保安編碼器&nbsp; <img...>") 的 key 会带 <img>，
                        // 与浏览器算出的可访问名 "保安編碼器" 对不上，反查失败退化为字面值。
                        String visible = NLSUtils.visibleText(en.getValue());
                        if (visible.isEmpty()) continue;
                        if (en.getValue().contains("{{")) {
                            // 含模板变量：无法精确反查，改用正则源（跨语言匹配替换后的可见文本）
                            String src = NLSUtils.templateRegexSource(en.getValue());
                            if (!src.isEmpty()) templates.putIfAbsent(src, en.getKey());
                        } else {
                            exact.putIfAbsent(visible, en.getKey());
                        }
                    }
                }
            }
            if (exact.isEmpty() && templates.isEmpty()) {
                log.warn("[picker] nls 文件无可用条目，无法反查 key：{}", nlsFiles);
                return "{}";
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("exact", exact);
            out.put("templates", templates.entrySet().stream()
                    .map(e -> new String[]{e.getKey(), e.getValue()})
                    .toArray(String[][]::new));
            log.info("[picker] 已加载 nls 反向查表（精确 {} 条 / 模板 {} 条），拾取时将自动匹配 key：{}",
                    exact.size(), templates.size(), nlsFiles);
            return GSON.toJson(out);
        } catch (Exception e) {
            log.warn("[picker] 加载 nls 文件失败，拾取时无法反查 key，将回退到 name 派生 slug：{}", nlsFiles, e);
            return "{}";
        }
    }

    /** 单文件便捷重载（向后兼容） */
    private static String buildNlsReverseJson(String nlsFile) {
        return buildNlsReverseJson(List.of(nlsFile));
    }

    /** 关闭拾取模式，清理注入的监听与提示条 */
    public static void stop(Page page) {
        page.evaluate(STOP_SCRIPT);
    }

    /**
     * 读取当前已拾取的元素列表（不阻塞、不生成）。
     * 可在 {@link #start} / {@link #stop} 之间多次调用，实时查看进度。
     */
    @SuppressWarnings("unchecked")
    public static List<RoleEntry> getEntries(Page page) {
        List<RoleEntry> result = new ArrayList<>();
        Object raw = page.evaluate("Array.from(window.__rolePicks || [])");
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (o instanceof Map) {
                    Map<Object, Object> m = (Map<Object, Object>) o;
                    String strategy = asString(m.get("strategy"));
                    if (strategy == null || strategy.isBlank()) {
                        strategy = "role";
                    }
                    String role = asString(m.get("role"));
                    String name = asString(m.get("name"));
                    String tag = asString(m.get("tag"));
                    String text = asString(m.get("text"));
                    if ("role".equals(strategy)) {
                        if (role == null) continue;   // 角色策略但无角色：跳过
                        String resolvedKey = asString(m.get("key"));
                        if (resolvedKey != null && resolvedKey.isBlank()) resolvedKey = null;
                        boolean cleaned = Boolean.parseBoolean(asString(m.get("cleaned")));
                        result.add(new RoleEntry(role, name, tag, text, "role", null, resolvedKey, cleaned));
                    } else {
                        String selector = buildSelector(strategy, m);
                        if (selector == null || selector.isBlank()) continue;
                        String resolvedKey = asString(m.get("key"));
                        if (resolvedKey != null && resolvedKey.isBlank()) resolvedKey = null;
                        boolean cleaned = Boolean.parseBoolean(asString(m.get("cleaned")));
                        result.add(new RoleEntry(role, name, tag, text, strategy, selector, resolvedKey, cleaned));
                    }
                }
            }
        }
        return result;
    }

    /**
     * 从拾取脚本返回的原始片段构建 Playwright 字符串选择器（在 Java 侧统一转义，
     * 避免在 JS 文本块里处理引号转义）。
     */
    private static String buildSelector(String strategy, Map<Object, Object> m) {
        switch (strategy) {
            case "testid":
            case "placeholder":
            case "altText":
            case "title": {
                String attr = asString(m.get("attr"));
                String value = asString(m.get("value"));
                if (attr == null || value == null) return null;
                return "[" + attr + "=\"" + escapeSelectorValue(value) + "\"]";
            }
            case "i18n": {
                String value = asString(m.get("value"));
                if (value == null || value.isBlank()) return null;
                return "[data-i18n=\"" + escapeSelectorValue(value) + "\"]";
            }
            case "text": {
                String name = asString(m.get("name"));
                if (name == null || name.isBlank()) return null;
                return "text=\"" + escapeSelectorValue(name) + "\"";
            }
            case "label": {
                // 对齐 page.pause 的 getByLabel：selector 仅作占位/人工核对，
                // 生成注解与运行期定位均走 @RoleElement(label=...) → byLabel。
                String name = asString(m.get("name"));
                if (name == null || name.isBlank()) return null;
                return "label=\"" + escapeSelectorValue(name) + "\"";
            }
            case "id": {
                String id = asString(m.get("id"));
                return (id == null || id.isBlank()) ? null : "#" + id;
            }
            case "css": {
                String css = asString(m.get("css"));
                return (css == null || css.isBlank()) ? null : css;
            }
            default:
                return null;
        }
    }

    /** 转义选择器值中的反斜杠与双引号，供 CSS 属性选择器 / text= 引擎安全使用。 */
    private static String escapeSelectorValue(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 阻塞式拾取：开启模式 → 等待用户点击（ESC 结束或超时）→ 关闭模式 → 返回拾取列表。
     * 期间测试线程会等待，用户在真实（有头）浏览器中点击目标元素。
     *
     * @return 已拾取的 {@link RoleEntry} 列表（可能为空的草稿）
     */
    /**
     * 阻塞式拾取：开启模式 → 等待用户点击（ESC 结束或超时）→ 关闭模式 → 返回拾取列表。
     * 期间测试线程会等待，用户在真实（有头）浏览器中点击目标元素。
     *
     * @return 已拾取的 {@link RoleEntry} 列表（可能为空的草稿）
     */
    public static List<RoleEntry> pick(Page page) {
        // 显式传空 String[]（非 varargs 调用，类型精确），避免 null 传给 String... 触发 imprecise varargs 警告
        return pick(page, new String[0]);
    }

    /**
     * 阻塞式拾取：开启模式（预加载 nls 反向查表）→ 等待用户点击 → 关闭 → 返回列表。
     * 若 {@code nlsFile} 非 null，拾取交互角色元素时会用 a11y name 反查 nls key，
     * 命中则生成的 {@code @RoleElement} 直接复用真实 key，未命中回退 slug。
     *
     * @param page      Playwright Page（须已导航到目标页，且为 headed 浏览器）
     * @param nlsFile   nls 文件路径（classpath 相对或文件系统绝对）；null 表示不反查
     * @return 已拾取的 {@link RoleEntry} 列表（可能为空的草稿）
     */
    public static List<RoleEntry> pick(Page page, String... nlsFiles) {
        String reverse = buildNlsReverseJson(Arrays.asList(nlsFiles));
        start(page, reverse);
        try {
            page.waitForFunction("() => window.__pickDone === true", null,
                    new Page.WaitForFunctionOptions().setTimeout(PICK_TIMEOUT_MS));
        } catch (Exception e) {
            log.warn("[picker] 拾取等待结束（超时或中断），将生成已拾取的部分。");
        }
        List<RoleEntry> entries = getEntries(page);
        stop(page);
        log.info("[picker] 已拾取 {} 个元素。", entries.size());
        return entries;
    }

    /** 拾取并直接生成源码字符串（不落盘） */
    public static String pickAndGenerate(Page page, String packageName,
                                         String pageClassName, String... nlsFiles) {
        List<RoleEntry> entries = pick(page, nlsFiles);
        if (entries.isEmpty()) {
            log.warn("[picker] 未拾取到任何元素，未生成代码。");
            return "";
        }
        return RoleElementPageGenerator.generate(entries, packageName, pageClassName, nlsFiles);
    }

    /** 拾取并打印生成的源码到日志 */
    public static void pickAndDump(Page page, String packageName,
                                   String pageClassName, String... nlsFiles) {
        List<RoleEntry> entries = pick(page, nlsFiles);
        if (entries.isEmpty()) {
            log.warn("[picker] 未拾取到任何元素，未生成代码。");
            return;
        }
        RoleElementPageGenerator.dump(entries, packageName, pageClassName, nlsFiles);
    }

    /** 拾取并直接写入文件（outputDir 为源码根，如 src/test/java） */
    public static void pickAndWrite(Page page, String outputDir, String packageName,
                                    String pageClassName, String... nlsFiles) {
        List<RoleEntry> entries = pick(page, nlsFiles);
        if (entries.isEmpty()) {
            log.warn("[picker] 未拾取到任何元素，未生成代码。");
            return;
        }
        RoleElementPageGenerator.write(entries, outputDir, packageName, pageClassName, nlsFiles);
    }

    /**
     * 在页面上弹出一个可复制的代码面板（类似 {@code page.pause()} 的浮层）。
     * 面板含只读代码框 + 「复制代码」/「关闭」按钮；点「关闭」后本方法返回。
     * 阻塞等待用户关闭，最长 {@link #PICK_TIMEOUT_MS}。
     *
     * @param code 要展示/复制的源码
     */
    public static void showCode(Page page, String code) {
        page.evaluate("window.__pickerCode = " + GSON.toJson(code));
        page.evaluate(SHOW_PANEL_SCRIPT);
        log.info("[picker] 代码面板已弹出：点『复制代码』复制，点『关闭』结束。");
        try {
            page.waitForFunction("() => window.__codePanelClosed === true", null,
                    new Page.WaitForFunctionOptions().setTimeout(PICK_TIMEOUT_MS));
        } catch (Exception e) {
            log.warn("[picker] 代码面板等待结束（超时或页面跳转）。");
        }
    }

    /**
     * 一站式：点选元素 → 生成代码 → 在页面弹出可复制的代码面板。
     * 最贴近 {@code page.pause()} 的体验：拾取完直接弹框，复制即用。
     */
    public static void pickAndShow(Page page, String packageName,
                                   String pageClassName, String... nlsFiles) {
        List<RoleEntry> entries = pick(page, nlsFiles);
        if (entries.isEmpty()) {
            log.warn("[picker] 未拾取到任何元素，未生成代码。");
            return;
        }
        String code = RoleElementPageGenerator.generate(entries, packageName, pageClassName, nlsFiles);
        showCode(page, code);
    }

    /**
     * 打开一个常驻控制面板（类似 {@code page.pause()} 的 inspector），由图标控件驱动整个拾取流程：
     * <ul>
     *   <li>▶/⏹ 切换控件：空闲时显示“开始拾取”（▶，绿），点后进入点选模式并在页面点击目标元素；
     *       拾取中自动变为“停止拾取”（⏹，橙），再点即退出点选并按已点元素生成 {@code @RoleElement} 代码填入面板</li>
     *   <li>📋 复制代码：一键复制面板中的代码</li>
     *   <li>⏻ 终止运行：抛出 {@link PickerAbortedException}，中断调用方后续代码</li>
     *   <li>✕ 关闭面板：标题栏右上角的 X 图标（对齐 {@code page.pause()} 的 inspector 关闭），退出面板</li>
     * </ul>
     * 调用后本方法会阻塞，直到用户关闭面板或终止运行。
     *
     * @param page          Playwright Page（须已导航到目标页，且为 headed 浏览器）
     * @param packageName   生成类的包名
     * @param pageClassName 生成类名
     * @param nlsFile       类级 {@code @RoleFile} 路径
     * @throws PickerAbortedException 用户点击『终止运行』时
     */
    public static void openPanel(Page page, String packageName,
                                 String pageClassName, String... nlsFiles) {
        // 开启面板开关：刷新/导航后 addInitScript 会自动重建面板，避免“刷新后面板消失”。
        page.evaluate("try{localStorage.setItem('__rolePanelEnabled','1')}catch(e){}");
        // 把关联的 nls 文件路径暴露给面板（标题展示 files=...），并在导航重建后依然可用。
        page.evaluate("window.__nlsFiles = " + GSON.toJson(nlsFiles) + ";");
        page.addInitScript(PANEL_SCRIPT);
        page.evaluate(PANEL_SCRIPT);
        log.info("[picker] 面板已打开：开始拾取 → 点击元素 → 停止拾取 → 复制代码；或终止运行。");
        try {
            while (true) {
                try {
                    page.waitForFunction("() => window.__panelCmds && window.__panelCmds.length > 0", null,
                            new Page.WaitForFunctionOptions().setTimeout(PICK_TIMEOUT_MS));
                } catch (Exception e) {
                    log.warn("[picker] 面板等待超时，自动关闭。");
                    closePanel(page);
                    return;
                }
                String cmd = asString(page.evaluate("window.__panelCmds.shift()"));
                PickerResult r = runPickerCommand(page, cmd, packageName, pageClassName, nlsFiles);
                if (r.action == PickerAction.ABORT) {
                    closePanel(page);
                    throw new PickerAbortedException("用户通过面板『终止运行』中止了后续代码执行");
                }
                if (r.action == PickerAction.DONE) {
                    closePanel(page);
                    return;
                }
                if (r.code != null) fillCode(page, r.code, r.statusMsg);
                else setStatus(page, r.statusMsg);
            }
        } finally {
            // 关闭开关并移除面板：之后导航不再自动注入面板。
            try { page.evaluate("try{localStorage.removeItem('__rolePanelEnabled')}catch(e){}"); } catch (Exception ignore) {}
            closePanel(page);
        }
    }

    /** 移除常驻面板 */
    private static void closePanel(Page page) {
        page.evaluate("var p = document.getElementById('__rolePanel'); if (p) p.remove();");
    }

    /** 更新面板顶部状态文字 */
    private static void setStatus(Page page, String msg) {
        page.evaluate("window.__roleStatusMsg = " + GSON.toJson(msg));
        page.evaluate("var st = document.getElementById('__roleStatus'); if (st) st.textContent = window.__roleStatusMsg;");
    }

    /** 把生成的代码写入面板文本框并更新状态 */
    private static void fillCode(Page page, String code, String msg) {
        Map<String, String> arg = new java.util.HashMap<>();
        arg.put("code", code);
        arg.put("msg", msg);
        page.evaluate("window.__roleCodeMsg = " + GSON.toJson(arg));
        page.evaluate("var ta = document.getElementById('__roleCodeArea');"
                + " if (ta) ta.value = window.__roleCodeMsg.code;"
                + " var st = document.getElementById('__roleStatus');"
                + " if (st) st.textContent = window.__roleCodeMsg.msg;");
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
