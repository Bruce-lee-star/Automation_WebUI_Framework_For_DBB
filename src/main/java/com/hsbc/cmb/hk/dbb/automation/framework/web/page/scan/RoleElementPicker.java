package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.google.gson.Gson;
import com.microsoft.playwright.Page;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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

              var banner = document.createElement('div');
              banner.id = '__rolePickBanner';
              banner.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:2147483647;' +
                'background:#1e88e5;color:#fff;font:14px/1.6 monospace;padding:8px 12px;' +
                'box-shadow:0 2px 6px rgba(0,0,0,.3);text-align:center;cursor:default;pointer-events:none;';
              banner.textContent = 'RoleElement Picker：点击元素拾取 role/name，按 ESC 结束';
              (document.body || document.documentElement).appendChild(banner);
              window.__rolePickBanner = banner;

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
                  tokens.push(getPseudoContent(getPseudo(element, '::before')));
                  var child = element.firstChild;
                  while (child) {
                    if (child.nodeType === 1) {
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
                  tokens.push(getPseudoContent(getPseudo(element, '::after')));
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
              function getElementAccessibleName(element) {
                var role = getAriaRole(element) || '';
                if (kProhibitName.indexOf(role) !== -1) return '';
                return normalizeAccessibleName(getElementAccessibleNameInternal(element, {
                  includeHidden: false, visitedElements: new Set(),
                  embeddedInLabelledBy: 'none', embeddedInLabel: 'none',
                  embeddedInTextAlternativeElement: false, embeddedInTargetElement: 'self'
                }));
              }
              function getName(el) { return getElementAccessibleName(el); }

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
              // 定位策略链（忠实对齐 page.pause() 代码生成的优先级）：
              //   1. 交互角色祖先 → role + name（生成 @RoleElement，保留 NLS 多语言）
              //   2. data-testid  → [data-testid="..."]
              //   3. 表单控件 placeholder → [placeholder="..."]；无则稳定 id
              //   4. 短文本 → getByText（text="..."）
              //   5. img/area 的 alt → [alt="..."]
              //   6. title → [title="..."]
              //   7. 稳定 id → #id
              //   8. 兜底 → css 路径（needsReview）
              // 仅返回“原始片段”（strategy/role/name/attr/value/id/css），
              // 由 Java 侧负责拼接并转义选择器字符串，避免在 Java 文本块里处理引号转义。
              // ============================================================================
              // 与 Java 侧 normalize() 保持一致：把 name 归一化（回车换行→换行、nbsp→空格、折叠空白、trim），
              // 用于和预加载的 nls 反向查表（已用同样规则规范化）做精确匹配。
              function normName(s) {
                return (s || '').replace(/\\r\\n/g, '\\n').replace(/\\u00A0/g, ' ').replace(/\\s+/g, ' ').trim();
              }
              window.__computePick = function(t) {
                var cur = t, guard = 0;
                while (cur && guard++ < 5) {
                  var node = resolveLabel(cur);
                  var r = (getRole(node) || '').toLowerCase();
                  if (INTERACTIVE_ROLES[r]) {
                    var nm = getName(node);
                    if (nm) {
                      // 反查 nls key：用归一化后的 name 在预加载的 window.__nlsReverse 里查表。
                      // 命中则直接用真实 key（@RoleElement 复用既有 nls 多语言表），未命中则留空回退 slug。
                      var key = null;
                      if (window.__nlsReverse) { key = window.__nlsReverse[normName(nm)] || null; }
                      return { strategy:'role', role:r, name:nm, key:key, matched: !!key,
                        tag:(node.tagName || '').toLowerCase(),
                        text: ownVisibleText(node).slice(0, 120) };
                    }
                    break;   // 命中交互角色但无名称：不再向上找，转由下方策略处理
                  }
                  cur = cur.parentElement;
                }
                var el = t;
                var tag = (el.tagName || '').toLowerCase();
                function done(o) {
                  o.tag = tag;
                  o.text = ownVisibleText(el).slice(0, 120);
                  return o;
                }
                var testAttrs = ['data-testid','data-test-id','data-test','data-qa'];
                for (var i = 0; i < testAttrs.length; i++) {
                  var tv = el.getAttribute(testAttrs[i]);
                  if (tv && tv.trim()) return done({ strategy:'testid', attr:testAttrs[i], value:tv.trim(), name:tv.trim() });
                }
                if (tag === 'input' || tag === 'textarea' || tag === 'select') {
                  var ph = el.getAttribute('placeholder');
                  if (ph && ph.trim()) return done({ strategy:'placeholder', attr:'placeholder', value:ph.trim(), name:ph.trim() });
                  var idl = el.getAttribute('id');
                  if (isStableId(idl)) return done({ strategy:'id', id:idl });
                }
                var ot = ownVisibleText(el);
                if (ot && ot.length <= 80) return done({ strategy:'text', name:ot, exact:true });
                if (tag === 'img' || tag === 'area') {
                  var alt = el.getAttribute('alt');
                  if (alt && alt.trim()) return done({ strategy:'altText', attr:'alt', value:alt.trim(), name:alt.trim() });
                }
                var title = el.getAttribute('title');
                if (title && title.trim()) return done({ strategy:'title', attr:'title', value:title.trim(), name:title.trim() });
                var id = el.getAttribute('id');
                if (isStableId(id)) return done({ strategy:'id', id:id });
                return done({ strategy:'css', css: cssPathOf(el), needsReview:true });
              };

              window.__rolePickClick = function(event) {
                var t = event.target;
                if (t && t.closest && t.closest('#__rolePanel, #__roleCodeOverlay, #__rolePickBanner')) {
                  return;
                }
                event.preventDefault();
                event.stopImmediatePropagation();
                var pick = window.__computePick(t);
                window.__rolePicks.push(pick);
                var prev = t.style.outline;
                t.style.outline = '3px solid #ffeb3b';
                setTimeout(function() { t.style.outline = prev; }, 400);
                if (window.__rolePickBanner) {
                  var extra = (pick && pick.matched) ? '（key=' + pick.key + '）' : '';
                  window.__rolePickBanner.textContent =
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
              if (window.__rolePickBanner) window.__rolePickBanner.remove();
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

    /** 常驻主面板：命令队列 + 开始/停止/复制/终止/关闭 按钮 */
    private static final String PANEL_SCRIPT = """
    (() => {
      // 仅当 openPanel 运行期间（由 localStorage 开关标记）才注入面板；
      // 这样刷新/导航后 addInitScript 会自动重建面板，而正常访问不受影响。
      try { if (localStorage.getItem('__rolePanelEnabled') !== '1') return; } catch (e) { return; }
      function build() {
      var old = document.getElementById('__rolePanel');
      if (old) old.remove();
      window.__panelCmds = window.__panelCmds || [];
      window.__pickDone = false;

      function pushCmd(c) { window.__panelCmds.push(c); }

              var overlay = document.createElement('div');
              overlay.id = '__rolePanel';
              overlay.style.cssText = 'position:fixed;inset:0;z-index:2147483647;' +
                'background:transparent;pointer-events:none;display:flex;' +
                'align-items:flex-end;justify-content:flex-end;padding:16px;';

              var panel = document.createElement('div');
              panel.style.cssText = 'position:fixed;right:16px;bottom:16px;width:min(460px,92vw);max-height:88vh;' +
                'display:flex;flex-direction:column;pointer-events:auto;background:#1e1e1e;color:#e0e0e0;' +
                'border-radius:8px;box-shadow:0 8px 30px rgba(0,0,0,.5);' +
                'font:13px/1.5 Consolas,Monaco,monospace;overflow:hidden;';

              var header = document.createElement('div');
              header.style.cssText = 'padding:10px 14px;background:#1e88e5;color:#fff;font-weight:bold;' +
                'display:flex;align-items:center;justify-content:space-between;cursor:move;';
              var title = document.createElement('span');
              title.textContent = 'RoleElement 拾取器'
                + (window.__nlsFile ? '  (file=' + window.__nlsFile + ')' : '');
              var status = document.createElement('span');
              status.id = '__roleStatus';
              status.style.cssText = 'font-weight:normal;font-size:12px;opacity:.95;';
              status.textContent = '就绪：点『开始拾取』并在页面点击元素';
              header.appendChild(title);
              header.appendChild(status);

              var toolbar = document.createElement('div');
              toolbar.style.cssText = 'padding:8px 14px;background:#252526;display:flex;gap:8px;flex-wrap:wrap;';
              function mkBtn(t, bg) {
                var b = document.createElement('button');
                b.textContent = t;
                b.style.cssText = 'padding:7px 16px;border:0;border-radius:5px;cursor:pointer;' +
                  'font:13px/1 sans-serif;color:#fff;background:' + bg + ';';
                return b;
              }
              var startBtn = mkBtn('开始拾取', '#43a047');
              var stopBtn = mkBtn('停止拾取', '#fb8c00');
              var copyBtn = mkBtn('复制代码', '#1976d2');
              var abortBtn = mkBtn('终止运行', '#e53935');
              var doneBtn = mkBtn('关闭面板', '#616161');
              startBtn.onclick = function() { pushCmd('start'); };
              stopBtn.onclick = function() { pushCmd('stop'); };
              abortBtn.onclick = function() { pushCmd('abort'); };
              doneBtn.onclick = function() { pushCmd('done'); };
              copyBtn.onclick = function() {
                var ta = document.getElementById('__roleCodeArea');
                var code = ta ? ta.value : '';
                function ok() { status.textContent = '已复制 ✔'; }
                try {
                  if (navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(code).then(ok, function() { fb(); });
                  } else { fb(); }
                } catch (e) { fb(); }
                function fb() {
                  if (ta) { ta.focus(); ta.select(); try { document.execCommand('copy'); ok(); }
                  catch (e2) { status.textContent = '复制失败，请手动复制'; } }
                }
              };
              toolbar.appendChild(startBtn);
              toolbar.appendChild(stopBtn);
              toolbar.appendChild(copyBtn);
              toolbar.appendChild(abortBtn);
              toolbar.appendChild(doneBtn);

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
        page.evaluate("window.__nlsReverse = " + (nlsReverseJson == null ? "{}" : nlsReverseJson) + ";");
        page.evaluate(START_SCRIPT);
        log.info("[picker] 拾取模式已开启：在浏览器点击元素即可拾取，按 ESC 结束。");
    }

    /**
     * 把 nls 文件构建成“规范化后的文本值 → key”的反向查表 JSON（覆盖所有语言），
     * 供浏览器拾取时把 a11y name 反查为对应 nls key。文件缺失/解析失败时返回 "{}"（退化为不反查）。
     *
     * @param nlsFile nls 文件路径（classpath 相对或文件系统绝对），如 "nls/login.nls.json"
     */
    private static String buildNlsReverseJson(String nlsFile) {
        if (nlsFile == null || nlsFile.isBlank()) return "{}";
        try {
            Map<String, Map<String, String>> tables = NLSUtils.rawTables(nlsFile);
            Map<String, String> reverse = new LinkedHashMap<>();
            for (Map<String, String> table : tables.values()) {
                if (table == null) continue;
                for (Map.Entry<String, String> en : table.entrySet()) {
                    String norm = normalize(en.getValue());
                    if (!norm.isEmpty()) reverse.putIfAbsent(norm, en.getKey());
                }
            }
            if (reverse.isEmpty()) {
                log.warn("[picker] nls 文件无可用条目，无法反查 key：{}", nlsFile);
                return "{}";
            }
            log.info("[picker] 已加载 nls 反向查表（{} 条），拾取时将自动匹配 key：{}", reverse.size(), nlsFile);
            return GSON.toJson(reverse);
        } catch (Exception e) {
            log.warn("[picker] 加载 nls 文件失败，拾取时无法反查 key，将回退到 name 派生 slug：{}", nlsFile, e);
            return "{}";
        }
    }

    /** 与注入脚本中的 normName 保持一致：归一化空白（\r\n→\n、nbsp→空格、折叠、trim）。 */
    private static String normalize(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n")
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
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
                        result.add(new RoleEntry(role, name, tag, text, "role", null, resolvedKey));
                    } else {
                        String selector = buildSelector(strategy, m);
                        if (selector == null || selector.isBlank()) continue;
                        result.add(new RoleEntry(role, name, tag, text, strategy, selector, null));
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
            case "text": {
                String name = asString(m.get("name"));
                if (name == null || name.isBlank()) return null;
                return "text=\"" + escapeSelectorValue(name) + "\"";
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
        return pick(page, null);
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
    public static List<RoleEntry> pick(Page page, String nlsFile) {
        String reverse = buildNlsReverseJson(nlsFile);
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
                                         String pageClassName, String nlsFile) {
        List<RoleEntry> entries = pick(page, nlsFile);
        if (entries.isEmpty()) {
            log.warn("[picker] 未拾取到任何元素，未生成代码。");
            return "";
        }
        return RoleElementPageGenerator.generate(entries, packageName, pageClassName, nlsFile);
    }

    /** 拾取并打印生成的源码到日志 */
    public static void pickAndDump(Page page, String packageName,
                                   String pageClassName, String nlsFile) {
        List<RoleEntry> entries = pick(page, nlsFile);
        if (entries.isEmpty()) {
            log.warn("[picker] 未拾取到任何元素，未生成代码。");
            return;
        }
        RoleElementPageGenerator.dump(entries, packageName, pageClassName, nlsFile);
    }

    /** 拾取并直接写入文件（outputDir 为源码根，如 src/test/java） */
    public static void pickAndWrite(Page page, String outputDir, String packageName,
                                    String pageClassName, String nlsFile) {
        List<RoleEntry> entries = pick(page, nlsFile);
        if (entries.isEmpty()) {
            log.warn("[picker] 未拾取到任何元素，未生成代码。");
            return;
        }
        RoleElementPageGenerator.write(entries, outputDir, packageName, pageClassName, nlsFile);
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
                                   String pageClassName, String nlsFile) {
        List<RoleEntry> entries = pick(page, nlsFile);
        if (entries.isEmpty()) {
            log.warn("[picker] 未拾取到任何元素，未生成代码。");
            return;
        }
        String code = RoleElementPageGenerator.generate(entries, packageName, pageClassName, nlsFile);
        showCode(page, code);
    }

    /**
     * 打开一个常驻控制面板（类似 {@code page.pause()} 的 inspector），由面板按钮驱动整个拾取流程：
     * <ul>
     *   <li>『开始拾取』：进入点选模式（顶部蓝条提示），在页面点击目标元素</li>
     *   <li>『停止拾取』：退出点选，按已点元素生成 {@code @RoleElement} 代码并填入面板</li>
     *   <li>『复制代码』：一键复制面板中的代码</li>
     *   <li>『终止运行』：抛出 {@link PickerAbortedException}，中断调用方后续代码</li>
     *   <li>『关闭面板』：退出面板（已生成代码可通过复制带走）</li>
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
                                 String pageClassName, String nlsFile) {
        // 开启面板开关：刷新/导航后 addInitScript 会自动重建面板，避免“刷新后面板消失”。
        page.evaluate("try{localStorage.setItem('__rolePanelEnabled','1')}catch(e){}");
        // 把关联的 nls 文件路径暴露给面板（标题展示 file=...），并在导航重建后依然可用。
        page.evaluate("window.__nlsFile = " + GSON.toJson(nlsFile) + ";");
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
                switch (cmd) {
                    case "start":
                        start(page, buildNlsReverseJson(nlsFile));
                        setStatus(page, "拾取中：在页面点击目标元素，再点『停止拾取』结束");
                        break;
                    case "stop":
                        stop(page);
                        List<RoleEntry> entries = getEntries(page);
                        if (entries.isEmpty()) {
                            fillCode(page, "", "未拾取到元素");
                        } else {
                            String code = RoleElementPageGenerator.generate(
                                    entries, packageName, pageClassName, nlsFile);
                            int matched = 0;
                            for (RoleEntry e : entries) {
                                if (e.getResolvedKey() != null) matched++;
                            }
                            String nlsInfo = (nlsFile != null && !nlsFile.isBlank())
                                    ? "（nls=" + nlsFile + "，已反查 " + matched + " 个 key）" : "";
                            fillCode(page, code, "已生成 " + entries.size() + " 个字段，可复制" + nlsInfo);
                        }
                        break;
                    case "abort":
                        stop(page);
                        closePanel(page);
                        throw new PickerAbortedException("用户通过面板『终止运行』中止了后续代码执行");
                    case "done":
                    default:
                        stop(page);
                        closePanel(page);
                        return;
                }
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
