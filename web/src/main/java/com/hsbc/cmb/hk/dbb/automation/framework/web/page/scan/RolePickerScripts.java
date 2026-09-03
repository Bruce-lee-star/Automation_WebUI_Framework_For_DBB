package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Browser-injected script constants and resource loader, extracted from RoleElementPicker (T5-1 step 1).
 * Restored real values: the original step-1 extraction left self-referential null stubs, dropping the
 * actual scripts. Values copied verbatim from the pre-extraction source. Pure data + stateless helpers.
 *
 * 常量按【依赖顺序】归类：被其他常量引用的基础常量（MERGE_KEY_SHIM / START_SCRIPT 及其部件）一律前置，
 * 保证其在引用者之前声明（Java 禁止编译期常量表达式中引用后声明的字段），纯整理、零行为变更。
 */
public final class RolePickerScripts {

    private RolePickerScripts() {}

    // =====================================================================
    // A. 面板门控 + 合并去重键（最基础，被后续合并脚本广泛引用，必须前置）
    // =====================================================================

    public static final String PANEL_BOOTSTRAP_SCRIPT = // 墓碑门控：context 级 addInitScript 无法撤销，会话结束（closePanel/finally 置 '0'）后
            // 引导脚本必须自行退出，否则会话结束后的任意导航都会把面板重新拉起来。
            // 仅显式 '0' 视为结束；键不存在（跨源新页首个文档）仍强制重建面板（保持会话内跨源健壮性）。
            "(function(){ try{ if(localStorage.getItem('__rolePanelEnabled')==='0') return; }catch(e){}"
            + " try{localStorage.setItem('__rolePanelEnabled','1');}catch(e){}"
            + " try{window.__rolePanelForce=true;}catch(e){}"
            + " try{var n=localStorage.getItem('__rolePageName'); if(n) window.__rolePageName=n;}catch(e){} })();"
            // ===== 唯一权威的"合并去重键" =====
            // 各处合并快照（load/pageshow 自愈、弹窗关闭回灌、导航后恢复、currentStep 补齐）过去各自手搓
            // JSON.stringify([p._sig, p._pageClass])，与元素入库时 __sigKey() 的口径【不一致】：
            // __sigKey 会优先复用已固化的 p._sigKey，而手搓版本无视它、按当前上下文重算。于是同一个元素
            // 在合并时算出的键 ≠ 入库时登记在 __rolePickSigs 里的键 → 判为"新元素"被再次 push。
            // 由于每次导航/恢复都会触发多个合并点，重复份数随操作次数递增（实测 4→5→6 次）。
            // 这里定义在【引导脚本】而非 START_SCRIPT：合并点最早在文档解析初期的门控脚本里就会执行，
            // 那时 __sigKey 尚未定义，故 __mergeKey 必须自给自足（有 __sigKey 就委托，没有就用同口径兜底）。
            + "(function(){ if (window.__mergeKey) return;"
            + " window.__mergeKey = function(p){ try{"
            + "   if (!p) return '';"
            + "   if (p._sigKey) return p._sigKey;"                      // 固化键优先——与 __sigKey 完全一致
            + "   if (typeof window.__sigKey === 'function') return window.__sigKey(p);"
            + "   var pageKey = p._pageClass || '';"
            + "   if (!pageKey) { try { pageKey = (location.origin||'') + (location.pathname||''); } catch(e){} }"
            + "   return JSON.stringify([p._sig || '', pageKey]);"
            + " }catch(e){ return ''; } }; })();";

    public static final String MERGE_KEY_SHIM = " if (typeof window.__mergeKey !== 'function') { window.__mergeKey = function(p){ try{"
            + "   if (!p) return '';"
            + "   if (p._sigKey) return p._sigKey;"
            + "   if (typeof window.__sigKey === 'function') return window.__sigKey(p);"
            + "   var pk = p._pageClass || '';"
            + "   if (!pk) { try { pk = (location.origin||'') + (location.pathname||''); } catch(e){} }"
            + "   return JSON.stringify([p._sig || '', pk]);"
            + " }catch(e){ return ''; } }; }";

    // =====================================================================
    // B. 核心拾取 / 面板脚本资源（.js 文件加载 + 拼接，START_SCRIPT/PANEL_SCRIPT 被 C/D 引用，前置）
    // =====================================================================

    public static final String START_SCRIPT_A = loadScript("picker-core-a.js");

    public static final String START_SCRIPT_B1 = loadScript("picker-core-b1.js");

    public static final String START_SCRIPT_B2 = loadScript("picker-core-b2.js");

    public static final String START_SCRIPT = concat(concat(START_SCRIPT_A, START_SCRIPT_B1), START_SCRIPT_B2);

    public static final String STOP_SCRIPT = loadScript("picker-stop.js");

    public static final String SHOW_PANEL_SCRIPT = loadScript("panel-show.js");

    public static final String PANEL_SCRIPT_A = loadScript("panel-core-a.js");

    public static final String PANEL_SCRIPT_B = loadScript("panel-core-b.js");

    public static final String PANEL_SCRIPT = concat(PANEL_SCRIPT_A, PANEL_SCRIPT_B);

    // =====================================================================
    // C. 会话 / 门控开关（被引用者在先：STOP_SESSION_ON_JS→CLEANUP、REMOVE_PICK_STATE_JS→CLEAR、
    //    SET_PANEL_FORCE_JS→PANEL_FORCE_AND_ENABLE；SET_NLS_AND_SESSION 引用 START_SCRIPT 已在 B）
    // =====================================================================

    /** 开启面板（墓碑门控未置位时强制置 1）。 */
    public static final String ENABLE_PANEL_JS =
            "try{localStorage.setItem('__rolePanelEnabled','1')}catch(e){}";

    /** 关闭面板（墓碑门控复位为 0）。 */
    public static final String DISABLE_PANEL_JS =
            "try{localStorage.setItem('__rolePanelEnabled','0')}catch(e){}";

    /** 停止时清除会话开关（阻断门控脚本在后续新文档自启拾取）。 */
    public static final String STOP_SESSION_ON_JS =
            "try{localStorage.removeItem('__rolePickSessionOn');}catch(e){} try{window.__rolePickSessionOn=false;}catch(e){}";

    /** 停止时清除会话开关 + 复位面板控件状态机（wanted/stopped），随后拼接 STOP_SCRIPT。 */
    public static final String STOP_SESSION_CLEANUP_JS = STOP_SESSION_ON_JS
            + " try{window.__rolePickStopped=true;}catch(e){}"
            + " try{window.__rolePickWanted=false;}catch(e){}";

    /** 清除持久化拾取态（__rolePickState）。 */
    public static final String REMOVE_PICK_STATE_JS =
            "try{localStorage.removeItem('__rolePickState')}catch(e){}";

    /** 清除持久化拾取态 + 生成代码（双 removeItem）。 */
    public static final String CLEAR_PICKER_STATE_JS = REMOVE_PICK_STATE_JS
            + " try{localStorage.removeItem('__rolePickerCode')}catch(e){}";

    /** 强制面板重建（置 __rolePanelForce）。 */
    public static final String SET_PANEL_FORCE_JS =
            "try{window.__rolePanelForce=true;}catch(e){}";

    /** 强制面板重建 + 开启面板（墓碑门控置 1）。 */
    public static final String PANEL_FORCE_AND_ENABLE_JS = SET_PANEL_FORCE_JS
            + " try{ localStorage.setItem('__rolePanelEnabled','1'); }catch(e){}";

    /** ensurePickingActive：会话置位 + 注入反向翻译表（nls 经 JSON.parse 复原），门控函数存在则调用否则注入 START_SCRIPT。 */
    public static final String SET_NLS_AND_SESSION_JS = "(a) => {"
            + " try{localStorage.setItem('__rolePickSessionOn','1');}catch(e){}"
            + " try{window.__rolePickSessionOn=true;}catch(e){}"
            + " var __o = (a && a.nls) ? JSON.parse(a.nls) : {};"
            + " window.__nlsReverse = (__o && __o.exact) ? __o.exact : (__o && __o.templates ? {} : (__o || {}));"
            + " window.__nlsTemplates = (__o && __o.templates) ? __o.templates : [];"
            + " if (typeof window.__roleGatedStart === 'function') { window.__roleGatedStart(); }"
            + " else { " + START_SCRIPT + " } }";

    /** 写入自动生成的页面拾取代码（code 经 Playwright 序列化后作为字符串赋值给 window.__pickerCode）。 */
    public static final String SET_PICKER_CODE_JS = "(a) => { window.__pickerCode = a.code; }";

    /** 写入 NLS 文件清单（nlsFiles 经 Playwright 序列化后赋值给 window.__nlsFiles）。 */
    public static final String SET_NLS_FILES_JS = "(a) => { window.__nlsFiles = a.files; }";

    // =====================================================================
    // D. 生命周期注入（START_INJECT_JS 引用 START_SCRIPT，已在 B；其余独立）
    // =====================================================================

    /** start() 拾取注入脚本：会话置位 + nls 反查表 + 重挂监听（START_SCRIPT / __roleGatedStart）+ 录制根容器。 */
    public static final String START_INJECT_JS = "(function(args){"
            + " var __nlsArg = args ? args.nls : null;"
            + " var __rootArg = args ? args.root : null;"
            + " var __o = (__nlsArg && typeof __nlsArg === 'string') ? JSON.parse(__nlsArg) : (__nlsArg || {});"
            + " try{localStorage.setItem('__rolePickSessionOn','1');}catch(e){}"
            + " try{window.__rolePickSessionOn=true;}catch(e){}"
            + " try{ window.__rolePickWanted = null; }catch(e){}"
            + " try{ if(window.__rolePickClick) document.removeEventListener('click', window.__rolePickClick, true); }catch(e){}"
            + " try{ if(window.__rolePickMove) document.removeEventListener('mousemove', window.__rolePickMove, true); }catch(e){}"
            + " try{ if(window.__rolePickKey) document.removeEventListener('keydown', window.__rolePickKey, true); }catch(e){}"
            + " try{ if(window.__rolePickFocus) document.removeEventListener('focusin', window.__rolePickFocus, true); }catch(e){}"
            + " try{ if(window.__rolePickScroll) document.removeEventListener('scroll', window.__rolePickScroll, true); }catch(e){}"
            + " try{ window.__rolePickerLib = false; }catch(e){}"
            + " try{"
            + "   if(Array.isArray(window.__rolePicks)){"
            + "     window.__rolePicks.forEach(function(p){"
            + "       if(p){"
            + "         p._pickNos = [];"
            + "         p._pickSeq = 0;"
            + "         p._seqStale = true;"
            + "         p._manualPick = false;"
            + "       }"
            + "     });"
            + "   }"
            + "   window.__rolePickSeq = 0;"
            + "   window.__roleMaxNo = 0;"
            + "   window.__pickOrder = {};"
            + " }catch(e){}"
            + " try{"
            + "   window.__rolePickSigs = {};"
            + "   window.__sigToPick = {};"
            + "   if(Array.isArray(window.__rolePicks)){"
            + "     window.__rolePicks.forEach(function(p){"
            + "       if(p){"
            + "         var k = p._sigKey || (typeof window.__sigKey==='function' ? window.__sigKey(p) : '');"
            + "         var s = typeof window.__pickSig==='function' ? window.__pickSig(p) : '';"
            + "         if(k) { window.__rolePickSigs[k] = true; window.__sigToPick[k] = p; }"
            + "         if(s) { window.__sigToPick[s] = p; }"
            + "       }"
            + "     });"
            + "   }"
            + " }catch(e){}"
            + " try{ window.__rolePickSeq = 0; }catch(e){}"
            + " try{ window.__roleMaxNo = 0; }catch(e){}"
            + " try{ window.__pickOrder = {}; }catch(e){}"
            + " try{ window.__rolePickSeq = 0; }catch(e){}"
            + " window.__nlsReverse = (__o && __o.exact) ? __o.exact : (__o && __o.templates ? {} : (__o || {}));"
            + " window.__nlsTemplates = (__o && __o.templates) ? __o.templates : [];"
            + " if (typeof window.__roleGatedStart === 'function') { window.__roleGatedStart(); } else { " + START_SCRIPT + " }"
            + " window.__rolePickRoot = __rootArg;"
            + " try { console.log('[picker] 录制根容器 =', window.__rolePickRoot || '(整页)'); } catch(e){}"
            + " })";

    /**
     * 把拾取会话状态注入目标页面（不依赖 window.opener，兼容 rel="noopener" / 跨域弹窗）。
     * 实参 a: {nlsFiles, nlsReverseJson, stateJson}；后两者为 JSON 字符串，脚本内 JSON.parse 复原。
     */
    public static final String APPLY_PICK_STATE_JS = "(a) => {"
            + " try { localStorage.setItem('__rolePanelEnabled','1'); } catch(e){}"
            + " window.__nlsFiles = a.nlsFiles;"
            + " var __o = JSON.parse(a.nlsReverseJson || '{}');"
            + " window.__nlsReverse = (__o && __o.exact) ? __o.exact : (__o && __o.templates ? {} : (__o || {}));"
            + " window.__nlsTemplates = (__o && __o.templates) ? __o.templates : [];"
            // 保留 start() 已写入的录制根约束（弹窗恢复状态时不覆盖，避免退化成整页录制）。
            + " if (window.__rolePickRoot === undefined) window.__rolePickRoot = null;"
            + " var s = JSON.parse(a.stateJson);"
            + " window.__rolePicks = s.picks || [];"
            // 恢复即视为"已拾取完成"：清除扫描候选的 __isScan 标记，否则这些元素进不了选择集
            // （点封装按钮 return 0、Java 侧永不生成代码）。
            + " (window.__rolePicks || []).forEach(function(p){ if(p&&p.__isScan){ p.__isScan=false; } });"
            + " window.__steps = s.steps || [];"
            + " window.__currentStep = s.currentStep || [];"
            + " window.__rolePickSigs = s.sigs || {};"
            // 续接全局序号计数器：取已有 _pickNos 最大值，避免重注入归零导致跨页序号冲突/面板 index 跳回 1。
            + " (function(){ var mx=0; (window.__rolePicks||[]).forEach(function(p){"
            + "   (p&&Array.isArray(p._pickNos)?p._pickNos:[]).forEach(function(n){ if(n>mx)mx=n; }); });"
            + "   window.__rolePickSeq = mx; })();"
            + " window.__rolePickActive = false;"
            + " try { if (window.__renderPicks) window.__renderPicks(); } catch(e){}"
            + "}";

    /** 移除常驻面板：摘除点击/悬停/按键/焦点/滚动监听，复位 active，清会话开关并写面板墓碑（阻断门控自启）。 */
    public static final String CLOSE_PANEL_JS = "(function(){"
            + " try{ if(window.__rolePickClick) document.removeEventListener('click', window.__rolePickClick, true); }catch(e){}"
            + " try{ if(window.__rolePickMove) document.removeEventListener('mousemove', window.__rolePickMove, true); }catch(e){}"
            + " try{ if(window.__rolePickKey) document.removeEventListener('keydown', window.__rolePickKey, true); }catch(e){}"
            + " try{ if(window.__rolePickFocus) document.removeEventListener('focusin', window.__rolePickFocus, true); }catch(e){}"
            + " try{ if(window.__rolePickScroll) document.removeEventListener('scroll', window.__rolePickScroll, true); }catch(e){}"
            + " try{ window.__rolePickActive = false; }catch(e){}"
            + " try{ localStorage.removeItem('__rolePickSessionOn'); }catch(e){}"
            + " try{ window.__rolePickSessionOn = false; }catch(e){}"
            + " try{ localStorage.setItem('__rolePanelEnabled','0'); }catch(e){}"
            + " try{ window.__rolePanelForce = false; }catch(e){}"
            + " var p = document.getElementById('__rolePanel'); if (p) { p.remove();"
            + " try { document.body.style.marginRight = ''; document.documentElement.style.overflowX = ''; } catch(e){} }"
            + "})()";

    /** 开始整页/区域扫描前清空浏览器侧拾取全局态（与 Java 内存态对齐，使扫描从空开始）。 */
    public static final String RESET_PICKS_JS =
            "try{ window.__rolePicks = []; window.__rolePickSigs = {}; window.__sigToPick = {}; }catch(e){}";

    public static final String CLEAR_PICKS_JS = "try{"
            + " if(typeof window.__roleEndRegionSelect==='function') window.__roleEndRegionSelect();"
            + " if(Array.isArray(window.__rolePicks)){"
            + "   window.__rolePicks.forEach(function(p){"
            + "     if(p){"
            + "       p._pickNos = [];"
            + "       p._pickSeq = 0;"
            + "       p._seqStale = true;"
            + "       p._manualPick = false;"
            + "     }"
            + "   });"
            + " }"
            + " try{ if(window.__rolePickSigs) window.__rolePickSigs = {}; }catch(e){}"
            + " try{ if(window.__sigToPick) window.__sigToPick = {}; }catch(e){}"
            + " try{ window.__rolePickSeq = 0; }catch(e){}"
            + " try{ window.__roleMaxNo = 0; }catch(e){}"
            + " try{ window.__pickOrder = {}; }catch(e){}"
            + " try{ window.__currentStep = []; }catch(e){}"
            + " try{ if(window.__currentStep)window.__currentStep.raws=[]; }catch(e){}"
            + " try{ window.__steps = []; }catch(e){}"
            + " if(window.__renderPicks)window.__renderPicks();"
            + " if(window.refreshSelInfo)window.refreshSelInfo();"
            + "}catch(e){}";

    /** 清空浏览器侧进行中 step（__currentStep）；用于 followPage 把 opener 的 step 整体转移到新页后清空源页。 */
    public static final String CLEAR_CURRENT_STEP_JS = "try{ window.__currentStep = []; }catch(e){}";

    /** 标记本次停止已生效（自愈钩子据此不再复活拾取）。 */
    public static final String SET_PICK_STOPPED_JS =
            "try{window.__rolePickStopped=true;}catch(e){}";

    /** 读取浏览器侧是否已显式停止拾取（__rolePickStopped），供 onFrameNavigated 跳过重激活。 */
    public static final String IS_PICK_STOPPED_JS =
            "try { return !!window.__rolePickStopped; } catch(e){ return false; }";

    /** 读取浏览器侧拾取会话开关是否置位（localStorage / window 双判），供 onFrameNavigated 重激活判定。 */
    public static final String IS_SESSION_ON_JS =
            "try { return localStorage.getItem('__rolePickSessionOn')==='1' || !!window.__rolePickSessionOn; } catch(e){ return !!window.__rolePickSessionOn; }";

    /** 置拾取激活态并立即重渲染面板。 */
    public static final String SET_PICK_ACTIVE_AND_RENDER_JS =
            "try{ window.__rolePickActive = true; if (window.__renderPicks) window.__renderPicks(); }catch(e){}";

    /** 触发面板重渲染（若存在）。 */
    public static final String RENDER_PICKS_JS =
            "if(window.__renderPicks) window.__renderPicks();";

    /** 触发 afterFillJump 钩子（若存在）。 */
    public static final String INVOKE_AFTER_FILL_JUMP_JS =
            "try{if(window.__afterFillJump)window.__afterFillJump();}catch(e){}";

    // =====================================================================
    // E. 面板 / 页面状态控制（页类名、模式、状态栏、代码填充）
    // =====================================================================

    /** 设置拾取模式并刷新面板开关。实参 a: {mode}。 */
    public static final String SET_PICK_MODE_JS = "(a) => {"
            + " try{ window.__roleMode = a.mode; if(window.__roleRefreshToggle) window.__roleRefreshToggle(); }catch(e){} }";

    /** 仅当浏览器侧页类名与期望值不同时才设置并持久化（避免无谓写入）。实参 a: {pageName}。 */
    public static final String SET_PAGE_NAME_IF_CHANGED_JS = "(a) => {"
            + " try{ if(window.__rolePageName!==a.pageName){"
            + " window.__rolePageName=a.pageName;"
            + " try{localStorage.setItem('__rolePageName',a.pageName);}catch(e){}"
            + " } }catch(e){} }";

    /** 设置当前页类名并持久化，同时清空 __currentPageInstance。实参 a: {pageName}。 */
    public static final String SET_PAGE_NAME_AND_RESET_INSTANCE_JS = "(a) => {"
            + " window.__rolePageName = a.pageName; window.__currentPageInstance = null;"
            + " try{localStorage.setItem('__rolePageName', a.pageName);}catch(e){} }";

    /** 设置当前页类名并持久化（保留 __currentPageInstance）。实参 a: {pageName}。 */
    public static final String SET_PAGE_NAME_JS = "(a) => {"
            + " window.__rolePageName = a.pageName;"
            + " try{localStorage.setItem('__rolePageName', a.pageName);}catch(e){} }";

    /** 设置自动步骤计数。实参 a: {n}。 */
    public static final String SET_AUTO_STEP_COUNT_JS = "(a) => { try{window.__roleAutoStepCount = a.n;}catch(e){} }";

    /** 更新面板顶部状态文字（第一步：写入消息对象）。实参 a: {msg}。 */
    public static final String SET_STATUS_MSG_JS = "(a) => { window.__roleStatusMsg = a.msg; }";

    /** 更新面板顶部状态文字（第二步：刷新 DOM）。 */
    public static final String UPDATE_STATUS_DOM_JS = "var st = document.getElementById('__roleStatus');"
            + " if (st) st.textContent = window.__roleStatusMsg;";

    /** 把按页生成的页面类/步骤代码写入面板多 Tab 并更新状态。实参 a: {pageByPage, stepByPage, msg}。 */
    public static final String FILL_CODE_JS = "(a) => {"
            + " window.__fillCodeTabs({ pageByPage: a.pageByPage, stepByPage: a.stepByPage, msg: a.msg });"
            + "}";

    /** 面板是否已挂载且渲染函数就绪。 */
    public static final String HAS_PANEL_JS = "!!(document.getElementById('__rolePanel') && window.__renderPicks)";

    // =====================================================================
    // F. 区域选择（整页/区域扫描、iframe 帧列举）
    // =====================================================================

    /** 在单个 frame 内执行 __roleScanPage(null)，返回新增元素数；未就绪返回 -1（供调用方补注入）。 */
    public static final String SCAN_PAGE_IN_FRAME_JS =
            "(function(){ try { return (typeof window.__roleScanPage==='function') ? window.__roleScanPage(null) : -1; } catch(e){ return -1; } })()";

    public static final String FRAME_SCAN_JS = "(function(){ try { return (typeof window.__roleScanPage==='function') ? window.__roleScanPage(null) : -1; } catch(e){ return -1; } })()";

    /** 启动区域点选（调用 window.__roleStartRegionSelect），成功返回 true。 */
    public static final String START_REGION_SELECT_JS =
            "(function(){ try { if(typeof window.__roleStartRegionSelect==='function'){ window.__roleStartRegionSelect(); return true; } } catch(e){} return false; })()";

    /** 清理区域选区态（移除蓝色遮罩 / 事件监听）。 */
    public static final String END_REGION_SELECT_JS =
            "try{ if(typeof window.__roleEndRegionSelect==='function') window.__roleEndRegionSelect(); }catch(e){}";

    public static final String READ_REGION_FRAMES_JS = "() => {"
            + " var out = { urls:[], names:[] };"
            + " var roots = window.__regionSelected || [];"
            + " for (var i=0;i<roots.length;i++){"
            + "   var r = roots[i]; if (!r || !r.querySelectorAll) continue;"
            + "   var fs = r.querySelectorAll('iframe, frame');"
            + "   for (var j=0;j<fs.length;j++){"
            + "     var el = fs[j];"
            + "     var src = el.getAttribute && el.getAttribute('src');"
            + "     if (src) out.urls.push(src);"
            + "     if (el.name) out.names.push(el.name);"
            + "     if (el.id) out.names.push(el.id);"
            + "   }"
            + " }"
            + " return out;"
            + "}";

    // =====================================================================
    // G. 状态读取 / 诊断快照（纯读取，无跨常量依赖）
    // =====================================================================

    public static final String PICK_STATE_READER_JS = "(function(){"
            + " function norm(s){ var t=(s&&typeof s==='object')?s:null;"
            + "   var pc=(t&&typeof t.pageClass==='string')?t.pageClass:'';"
            + "   var ps=(t&&t.picks)?t.picks:(Array.isArray(s)?s:[]);"
            + "   return {pageClass:pc, picks:ps}; }"
            // 读取兜底去重：window.__rolePicks 在 start() 重注入清空 __rolePickSigs + 重建、与
            // pageshow 恢复 / syncPanelToBrowser 每轮同步交错时，可能因重建竞态残留重复项（同组元素整组重复）。
            // 这里在回传 Java 前按权威键 __mergeKey 压缩一次，保证生成链路拿到的 picks 永不重复，
            // 无论浏览器侧数组因何种时序竞态累积了副本，最终页面类都不会出现重复字段。
            + " if (typeof window.__mergeKey !== 'function') { window.__mergeKey = function(p){ try{"
            + "   if (!p) return '';"
            + "   if (p._sigKey) return p._sigKey;"
            + "   if (typeof window.__sigKey === 'function') return window.__sigKey(p);"
            + "   var pk = p._pageClass || '';"
            + "   if (!pk) { try { pk = (location.origin||'') + (location.pathname||''); } catch(e){} }"
            + "   return JSON.stringify([p._sig || '', pk]);"
            + " }catch(e){ return ''; } }; }"
            + " var __seen = {}; var __out = [];"
            + " (window.__rolePicks||[]).forEach(function(p){ try{"
            + "   var k = window.__mergeKey(p);"
            + "   if (!k) { __out.push(p); return; }"  // 极端兜底：无键者原样保留，不丢元素
            + "   if (__seen[k]) return; __seen[k] = true; __out.push(p);"
            + " }catch(e){ __out.push(p); } });"
            + " return {"
            + "   pageClass: (window.__rolePageName||''),"
            + "   picks: __out,"
            + "   steps: Array.from(window.__steps||[]).filter(function(s){"
            + "     return !(s&&typeof s==='object'&&typeof s.op==='string'); }).map(norm),"
            + "   ops: Array.from(window.__steps||[]).filter(function(s){"
            + "     return (s&&typeof s==='object'&&typeof s.op==='string'); })"
            + "     .map(function(s){ return {pageClass:(s.pageClass||''), op:s.op}; })"
            + " };"
            + "})()";

    /** repickNos 同步删除：读取浏览器侧 __rolePicks 的 sigKey 集合。 */
    public static final String READ_PICK_SIGS_JS =
            "() => (window.__rolePicks||[]).map(function(p){ return p._sigKey||p._sig||''; })";

    /** repickNos 回灌诊断：读取浏览器侧 __rolePicks 的 {k, n} 列表。 */
    public static final String READ_PICK_KEYS_JS =
            "() => (window.__rolePicks||[]).map(function(p){ return {k:(p._sigKey||p._sig||''), n:(p._pickNos||null)}; })";

    /** 读取浏览器侧已拾取元素数量（诊断用）。 */
    public static final String READ_PICK_COUNT_JS =
            "() => (window.__rolePicks||[]).length";

    /** 读取最近一次拾取签名（兜底空串）。 */
    public static final String READ_LAST_PICK_SIG_JS =
            "window.__lastPickSig || ''";

    /** 读取拾取会话状态 JSON 字符串（picks/steps/currentStep/sigs/active），供跨页面（弹窗开合）搬运。 */
    public static final String READ_PICK_STATE_JSON_JS = "(function() {"
            + " try {"
            + "   return JSON.stringify({"
            + "     picks: window.__rolePicks || [],"
            + "     steps: window.__steps || [],"
            + "     currentStep: window.__currentStep || [],"
            + "     sigs: window.__rolePickSigs || {},"
            + "     active: !!window.__rolePickActive });"
            + " } catch (e) {"
            + "   return JSON.stringify({picks:[],steps:[],currentStep:[],sigs:{},active:false});"
            + " }"
            + "})()";

    public static final String READ_FRAME_PICKS_JS = "(function(){"
            + " if (typeof window.__mergeKey !== 'function') { window.__mergeKey = function(p){ try{"
            + "   if (!p) return ''; if (p._sigKey) return p._sigKey;"
            + "   if (typeof window.__sigKey === 'function') return window.__sigKey(p);"
            + "   var pk = p._pageClass || ''; if (!pk) { try { pk = (location.origin||'') + (location.pathname||''); } catch(e){} }"
            + "   return JSON.stringify([p._sig || '', pk]);"
            + " }catch(e){ return ''; } }; }"
            + " var seen = {}; var out = [];"
            + " (window.__rolePicks||[]).forEach(function(p){ try{ var k = window.__mergeKey(p);"
            + "   if (!k) { out.push(p); return; } if (seen[k]) return; seen[k]=true; out.push(p);"
            + " }catch(e){ out.push(p); } }); return out; })()";

    /** 读取某 iframe 的 window.__rolePicks 原始 JSON 字符串（跨源 frame 经 Playwright 协议读取，不受 file:// 跨源限制）。 */
    public static final String READ_FRAME_PICKS_RAW_JS = "() => JSON.stringify(window.__rolePicks||[])";

    /** getPageOpsWithPage：读取浏览器侧 __steps 中带 op 的项并映射为 {pageClass, op}。 */
    public static final String READ_OPS_JS =
            "Array.from(window.__steps || []).filter(function(s){"
            + " return (s && typeof s === 'object' && typeof s.op === 'string'); })"
            + ".map(function(s){ return {pageClass:(s.pageClass||''), op:s.op}; })";

    /** 读取「页面级操作」step（含 op 字段，如关闭页面），供生成 closeCurrentPage() 等步骤。 */
    public static final String READ_PAGE_OPS_JS = "Array.from(window.__steps || []).filter(function(s){"
            + " return (s && typeof s === 'object' && typeof s.op === 'string'); })"
            + ".map(function(s){ return {pageClass:(s.pageClass||''), op:s.op}; })";

    /** 读取元素 step 序列并归一为 {pageClass, picks}（过滤掉含 op 的页面级操作 step）。 */
    public static final String READ_STEPS_WITH_PAGE_JS = "Array.from(window.__steps || []).filter(function(s){"
            + " return !(s && typeof s === 'object' && typeof s.op === 'string'); }).map(function(s){"
            + " var t = (s && typeof s === 'object') ? s : null;"
            + " var pc = (t && typeof t.pageClass === 'string') ? t.pageClass : '';"
            + " var ps = (t && t.picks) ? t.picks : (Array.isArray(s) ? s : []);"
            + " return {pageClass: pc, picks: ps}; })";

    /** 导航恢复/激活后的运行时诊断快照（会话开关、激活态、三大监听是否注入）。 */
    public static final String NAV_DIAG_JS = "(function(){"
            + " try {"
            + "   var ls='?'; try{ ls = localStorage.getItem('__rolePickSessionOn'); }catch(e){ ls='LS_ERR:'+e; }"
            + "   var gi = window.__gateInit || null;"
            + "   return JSON.stringify({"
            + "     url: location.href, origin: location.origin,"
            + "     lsSwitch: ls,"
            + "     winSwitch: !!window.__rolePickSessionOn,"
            + "     active: !!window.__rolePickActive,"
            + "     hasClick: typeof window.__rolePickClick === 'function',"
            + "     hasMove: typeof window.__rolePickMove === 'function',"
            + "     hasRecord: typeof window.__recordPick === 'function',"
            + "     hasLib: !!window.__rolePickerLib,"
            + "     lastClickTs: window.__lastClickTs || 0,"
            + "     lastClickActive: !!window.__lastClickActive,"
            + "     mouseLog: (window.__roleMouseLog||[]).slice(-12),"
            + "     gateInit: gi"
            + "   });"
            + " } catch(e){ return 'DIAG_ERR:'+e; }"
            + "})()";

    /** start() 注入后诊断快照（origin / 会话开关 / 激活态 / 三大监听是否真正挂载）。 */
    public static final String START_DIAG_JS = "(function(){ return JSON.stringify({"
            + " origin: location.origin,"
            + " winSwitch: !!window.__rolePickSessionOn,"
            + " active: !!window.__rolePickActive,"
            + " hasClick: typeof window.__rolePickClick==='function',"
            + " hasMove: typeof window.__rolePickMove==='function',"
            + " hasRecord: typeof window.__recordPick==='function'"
            + "}); })()";

    public static final String DRAIN_PANEL_CMDS_JS = "(function(){"
            + " try { var a = window.__panelCmds || []; window.__panelCmds = []; return a; }"
            + " catch(e){ return []; } })()";

    /** 把最近一次拾取标记为 download（下载监听触发时）。 */
    public static final String MARK_LAST_PICK_DOWNLOAD_JS = "if(window.__rolePicks && window.__rolePicks.length){"
            + " var p = window.__rolePicks[window.__rolePicks.length-1]; if(p) p.download = true; }";

    /** 把最近一次拾取标记为 upload（文件选择框监听触发时）。 */
    public static final String MARK_LAST_PICK_UPLOAD_JS = "if(window.__rolePicks && window.__rolePicks.length){"
            + " var p = window.__rolePicks[window.__rolePicks.length-1]; if(p) p.upload = true; }";

    // =====================================================================
    // H. 快照合并（均引用前置的 MERGE_KEY_SHIM）
    // =====================================================================

    /** 整页跳转后，把 pagehide 落盘到 localStorage 的拾取态按权威键合并回当前 window（补回 Java 快照未覆盖的最新点击）。 */
    public static final String MERGE_LOCALSTORAGE_PICKS_JS = "(function(){"
            + " try {"
            + "   var raw = localStorage.getItem('__rolePickState'); if(!raw) return;"
            + "   var s = JSON.parse(raw);"
            + "   window.__rolePicks = window.__rolePicks || [];"
            + "   window.__rolePickSigs = window.__rolePickSigs || {};"
            + MERGE_KEY_SHIM
            // 定位器唯一型策略按 locator 签名（_sig）全局去重；role/closeOp 仍按 [sig, pageClass|URL] 区分。
            + "   var __LOCID={id:1,css:1,i18n:1,text:1,title:1,placeholder:1,label:1,testid:1,altText:1};"
            + "   var __loc = {};"
            + "   window.__rolePicks.forEach(function(p){ if(p&&__LOCID[p.strategy]){ var ls=p._sig||''; if(ls) __loc[ls]=true; } });"
            + "   (s.picks||[]).forEach(function(p){"
            + "     var sig=(p&&p._sig)||'';"
            + "     var li=(p&&__LOCID[p.strategy]);"
            + "     if (li && sig && __loc[sig]) return;"
            + "     if (li && sig) __loc[sig]=true;"
            + "     var k = window.__mergeKey(p);"
            + "     if (k && window.__rolePickSigs[k]) return;"
            + "     if (k) window.__rolePickSigs[k] = true;"
            + "     window.__rolePicks.push(p); });"
            + "   window.__currentStep = window.__currentStep || [];"
            + "   var __cs = {};"
            + "   window.__currentStep.forEach(function(p){ var k=window.__mergeKey(p); if(k) __cs[k]=true; });"
            + "   (s.currentStep||[]).forEach(function(p){"
            + "     var k = window.__mergeKey(p);"
            + "     if (k && __cs[k]) return;"
            + "     if (k) __cs[k] = true;"
            + "     window.__currentStep.push(p); });"
            + "   window.__steps = window.__steps || [];"
            + "   (s.steps||[]).forEach(function(st2){"
            + "     var dup = window.__steps.some(function(ex){ return JSON.stringify(ex)===JSON.stringify(st2); });"
            + "     if(!dup) window.__steps.push(st2); });"
            + " } catch(e){}"
            + "})()";

    /**
     * 导航后兜底（延迟 60ms 等面板 build 完成）：用【稳定键】对 __rolePicks 做一次全局压实
     * （绝不用 location 兜底键，否则元素随跳转成倍累积），再重渲染面板、滚动到底、恢复上次生成的代码。
     */
    public static final String POST_NAV_COMPACT_AND_RENDER_JS = "(function(){ setTimeout(function(){"
            + MERGE_KEY_SHIM
            + "   try {"
            + "     var __stableKey = function(pp){"
            + "       if (!pp) return '';"
            + "       if (pp._sigKey) return pp._sigKey;"
            + "       var __s = (typeof window.__pickSig==='function') ? window.__pickSig(pp) : (pp._sig || '');"
            + "       var __pk = pp._pageClass || (window.__rolePageName || '');"
            + "       return JSON.stringify([__s, __pk]);"
            + "     };"
            + "     var __seen = {}; var __out = [];"
            + "     (window.__rolePicks||[]).forEach(function(p){"
            + "       if (!p) return;"
            + "       var k = __stableKey(p);"
            + "       if (k && __seen[k]) return;"
            + "       if (k) { __seen[k] = true; if (!p._sigKey) p._sigKey = k; }"
            + "       __out.push(p);"
            + "     });"
            + "     window.__rolePicks = __out;"
            + "     window.__rolePickSigs = __seen;"
            + "   } catch(e){}"
            + "   try { if (window.__renderPicks) window.__renderPicks(); } catch(e){}"
            + "   try { var l = document.getElementById('__rolePickList'); if (l) l.scrollTop = l.scrollHeight; } catch(e){}"
            + "   try { var code = JSON.parse(localStorage.getItem('__rolePickerCode')||'null');"
            + "     if (code) {"
            + "       var pbp = code.pageByPage || (code.page != null ? {'__merged__': code.page} : {});"
            + "       var sbp = code.stepByPage || (code.step != null ? {'__merged__': code.step} : {});"
            + "       if (window.__fillCodeTabs) window.__fillCodeTabs({pageByPage: pbp, stepByPage: sbp, msg: code.msg||''});"
            + "       window.__pickerCode = (code.page != null) ? code.page"
            + "         : Object.keys(pbp).map(function(k2){return pbp[k2];}).join('\\n\\n');"
            + "     } } catch(e){}"
            + " }, 60); })()";

    /**
     * SPA / 同 window 跳转时，把 Java 快照的 picks/steps 合并回当前 window（按 __mergeKey 去重，
     * 并在进行中 step 丢失时从快照补回）。实参 a: {stateJson}（JSON 字符串，脚本内 JSON.parse）。
     */
    public static final String MERGE_SNAPSHOT_PICKS_JS = "(a) => {"
            + " var s = JSON.parse(a.stateJson);"
            + " var picks = (s && s.picks) || [];"
            + " window.__rolePicks = window.__rolePicks || [];"
            + " window.__rolePickSigs = window.__rolePickSigs || {};"
            + MERGE_KEY_SHIM
            // 定位器唯一型策略按 _sig 全局去重，防止 SPA 路由/同页跳转合并快照时把同一 locator 元素
            // 以不同 pageClass 追加成副本；role/closeOp 保持页面作用域键。
            + " var __LOCID={id:1,css:1,i18n:1,text:1,title:1,placeholder:1,label:1,testid:1,altText:1};"
            + " var __loc = {};"
            + " window.__rolePicks.forEach(function(p){ if(p&&__LOCID[p.strategy]){ var ls=p._sig||''; if(ls) __loc[ls]=true; } });"
            + " picks.forEach(function(p){"
            + "   var sig=(p&&p._sig)||'';"
            + "   var li=(p&&__LOCID[p.strategy]);"
            + "   if (li && sig && __loc[sig]) return;"
            + "   if (li && sig) __loc[sig]=true;"
            + "   var k = window.__mergeKey(p);"
            + "   if (k && window.__rolePickSigs[k]) return;"
            + "   if (k) window.__rolePickSigs[k] = true;"
            + "   window.__rolePicks.push(p); });"
            + " window.__steps = window.__steps || [];"
            + " (s.steps||[]).forEach(function(st2){"
            + "   var dup = window.__steps.some(function(ex){ return JSON.stringify(ex)===JSON.stringify(st2); });"
            + "   if(!dup) window.__steps.push(st2); });"
            // 关键修复：URL 变化若把"进行中 step"（__currentStep）一并清空、但 window 未销毁（livePicks 为真），
            // 从快照补回，避免当前 step 元素在导航后"凭空消失"。仅当仍处于拾取中、当前 __currentStep 已丢失
            // 且快照确有内容时才补，防止 stop 后再导航被误恢复出游离 step。
            + " if (window.__rolePickActive && !Array.isArray(window.__currentStep)"
            + "     && (s.currentStep||[]).length) {"
            + "   window.__currentStep = s.currentStep; }"
            + "}";

    /** livePicks 场景补合并：仅把快照里有、而当前 window.__rolePicks 没有的元素按签名去重补回。实参 a: {stateJson}。 */
    public static final String MERGE_MISSING_PICKS_JS = "(a) => {"
            + " try {"
            + "   var s = JSON.parse(a.stateJson);"
            + "   window.__rolePicks = window.__rolePicks || [];"
            + "   window.__rolePickSigs = window.__rolePickSigs || {};"
            + MERGE_KEY_SHIM
            + "   var __LOCID={id:1,css:1,i18n:1,text:1,title:1,placeholder:1,label:1,testid:1,altText:1};"
            + "   var __loc = {};"
            + "   window.__rolePicks.forEach(function(p){ if(p&&__LOCID[p.strategy]){ var ls=p._sig||''; if(ls) __loc[ls]=true; } });"
            + "   (s.picks||[]).forEach(function(p){"
            + "     var sig=(p&&p._sig)||'';"
            + "     var li=(p&&__LOCID[p.strategy]);"
            + "     if (li && sig && __loc[sig]) return;"
            + "     if (li && sig) __loc[sig]=true;"
            + "     var k = window.__mergeKey(p);"
            + "     if (k && window.__rolePickSigs[k]) return;"
            + "     if (k) window.__rolePickSigs[k] = true;"
            + "     window.__rolePicks.push(p); });"
            + " } catch(e){}"
            + "}";

    /**
     * 关闭弹窗时把该页已抓元素"按签名合并"回父页（而非整盘覆盖）：只并入父页还没有的 pick。
     * 实参 a: {nlsFiles, nlsReverseJson, closedState}。
     */
    public static final String MERGE_CLOSED_PAGE_PICKS_JS = "(a) => {"
            + " try{localStorage.setItem('__rolePanelEnabled','1');}catch(e){}"
            + " window.__nlsFiles = a.nlsFiles;"
            + " var __o = JSON.parse(a.nlsReverseJson || '{}');"
            + " window.__nlsReverse = (__o && __o.exact) ? __o.exact : (__o && __o.templates ? {} : (__o || {}));"
            + " window.__nlsTemplates = (__o && __o.templates) ? __o.templates : [];"
            + " window.__rolePicks = window.__rolePicks || [];"
            + " window.__rolePickSigs = window.__rolePickSigs || {};"
            + MERGE_KEY_SHIM
            + " var s = JSON.parse(a.closedState);"
            // 定位器唯一型策略按 locator 签名（_sig）全局去重：弹窗打开时被 followPage 复制进来的
            // "主页元素"与主页已有元素 locator 相同，关弹窗回灌时不应再追加一份。
            // 角色/closeOp 仍按 [sig, pageClass|URL] 区分，跨页同名元素各自独立。
            + " var __LOCID={id:1,css:1,i18n:1,text:1,title:1,placeholder:1,label:1,testid:1,altText:1};"
            + " var __loc = {};"
            + " (window.__rolePicks||[]).forEach(function(p){ if(p&&__LOCID[p.strategy]){ var ls=p._sig||''; if(ls) __loc[ls]=true; } });"
            + " (s.picks||[]).forEach(function(p){"
            + "   var sig=(p&&p._sig)||'';"
            + "   var li=(p&&__LOCID[p.strategy]);"
            + "   if (li && sig && __loc[sig]) return;"
            + "   if (li && sig) __loc[sig]=true;"
            + "   var k = window.__mergeKey(p);"
            + "   if (k && window.__rolePickSigs[k]) return;"
            + "   if (k) window.__rolePickSigs[k] = true;"
            + "   window.__rolePicks.push(p); });"
            + "}";

    /**
     * 关闭弹窗时把其"进行中 step"合并回父页当前 step，并登记 _closeOp 关闭标记与 op='close' 页面级操作。
     * 实参 a: {closedState, closedCls}。
     */
    public static final String MERGE_CLOSE_OP_STEP_JS = "(a) => {"
            // 关键修复：合并弹窗 currentStep 时【绝不可】用父页全局 __rolePickSigs 去重——
            // 否则弹窗打开时被 followPage 搬运进弹窗 currentStep 的"默认页元素"会因其 sig 已存在于默认页
            // __rolePickSigs 而被误删（表现为"关弹窗 / url 变化后元素找不到"）。此处仅对
            // "弹窗 currentStep 自身"去重，被搬运来的默认页元素必须原样保留。
            + " var s = JSON.parse(a.closedState || '{}');"
            + " var closeMarker = {_closeOp:true, _pageClass:a.closedCls"
            + "   , _sig:'__close_' + ((window.__roleCloseSeq=(window.__roleCloseSeq||0)+1)), tag:'close'};"
            + MERGE_KEY_SHIM
            + " function mergeInto(arr){ if(!arr) return;"
            + "   var seen = {};"
            + "   (s.currentStep||[]).forEach(function(p){"
            + "     var k = window.__mergeKey(p);"
            + "     if (k && seen[k]) return;"
            + "     if (k) seen[k] = true;"
            + "     arr.push(p); });"
            // 关闭标记插入到"被关闭页的最后一个元素"之后（而非简单 push 到末尾），保留跨页时序。
            + "   var __ins = -1;"
            + "   for (var __i = 0; __i < arr.length; __i++) {"
            + "     var __pc = arr[__i] && (arr[__i]._pageClass || arr[__i].pageClass);"
            + "     if (__pc === a.closedCls) __ins = __i; }"
            + "   if (__ins < 0) __ins = arr.length - 1;"
            + "   arr.splice(__ins + 1, 0, closeMarker); }"
            // 仍在进行中（未停止）：并入当前 step（__currentStep 是数组）。
            + " if (Array.isArray(window.__currentStep)) { mergeInto(window.__currentStep); }"
            // 已停止：并入"最后一个已生成 step"的 picks，不新建 step（"开始-停止"才是唯一 step 边界）。
            + " else { window.__steps = window.__steps || [];"
            + "   var last = window.__steps[window.__steps.length-1];"
            + "   if (!last) { last = {pageClass:a.closedCls, picks:[]}; window.__steps.push(last); }"
            + "   if (!last.picks) last.picks = [];"
            + "   mergeInto(last.picks); }"
            // 同步登记一条 op='close' 的页面级操作，供代码生成器 inferPopupTargetVar 推断"弹窗目标页对象"
            // （修复"弹窗关闭落在 loginPage 而非 privacyAndSecurityPage"）。_closeOp 仅供 step 内联渲染。
            + " window.__steps = window.__steps || [];"
            + " window.__steps.push({op:'close', pageClass:a.closedCls});"
            + "}";

    /**
     * 把 Java 权威拾取内存态回灌浏览器侧 window.__rolePicks（参数化注入，杜绝字符串拼接破坏语法）。
     * 实参 a: [0]=Base64URL(过滤后 picks JSON)，[1]=Base64URL(删除键 JSON，恒 "[]")，[2]=overwriteNos(boolean)。
     * 由 RolePickerPanelSync.syncPanelToBrowser 外置而来，行为零变更。
     */
    public static final String SYNC_PANEL_TO_BROWSER_JS = "(a) => {"
            + " try {"
            + "   function __dec(s){ var b=s.replace(/-/g, '+').replace(/_/g, '/'); return decodeURIComponent(escape(atob(b))); }"
            + "   var arr = JSON.parse(__dec(a[0]));"
            + "   var del2 = JSON.parse(__dec(a[1]));"
            + "   var __overwrite = !!a[2];"
            + "   if (!(arr instanceof Array)) arr = [];"
            + "   if (!(del2 instanceof Array)) del2 = [];"
            + "   if (window.__clearMatchCache) window.__clearMatchCache();"
            + "   function toPick(p){ if(!p) return p; var o={};"
            + "     o.strategy=p.strategy; o.role=p.role; o.name=p.name;"
            + "     o.key=(p.resolvedKey!=null)?p.resolvedKey:undefined;"
            + "     o.id=(p.strategy==='id' && p.selector)? String(p.selector).replace(/^#/, '') : undefined;"
            + "     o.css=(p.strategy==='css')?p.selector:undefined;"
            + "     o.index=p.index; o._pageClass=p.pageClass;"
            + "     o._sigKey=(p.sigKey!=null&&p.sigKey!=='')?p.sigKey:undefined;"
            + "     o.value=p.value;"
            + "     o.text=p.text;"
            + "     o.tag=p.tag;"
            + "     o.selector=p.selector;"
            + "     o.resolvedKey=p.resolvedKey;"
            + "     o._pickNos=(p.pickNos)?p.pickNos:undefined;"
            + "     o._seqStale=(p.pickNos==null||p.pickNos.length===0)?true:false;"
            + "     o._manualPick=false;"
            + "     return o; }"
            + "   var __oldNos = {};"
            + "   (window.__rolePicks||[]).forEach(function(p){ try{ var kk=(p&&p._sigKey)||(typeof window.__pickSig==='function'?window.__pickSig(p):''); if(kk&&Array.isArray(p._pickNos)) __oldNos[kk]=p._pickNos; }catch(e){} });"
            + "   window.__rolePicks = [];"
            + "   window.__rolePickSigs = {};"
            + "   arr.forEach(function(p){"
            + "     var o = toPick(p);"
            + "     o._sig = (typeof window.__pickSig==='function') ? (window.__pickSig(o)||'') : '';"
            + "     var k = (typeof window.__sigKey==='function') ? window.__sigKey(o)"
            + "            : ((o&&(o._sigKey||o._sig))||null);"
            + "     if (k) o._sigKey = k;"
            + "     var __old = (__overwrite) ? null : ((k && __oldNos[k]) ? __oldNos[k] : null);"
            + "     if (__old && Array.isArray(o._pickNos)) {"
            + "       var __set = {}; var __keep = [];"
            + "       __old.concat(o._pickNos).forEach(function(n){ if(n!=null && !__set['_'+n]){ __set['_'+n]=1; __keep.push(n); } });"
            + "       o._pickNos = __keep;"
            + "     } else if (__old) { o._pickNos = __old; }"
            + "     var __del = window.__deletedSigs || {};"
            + "     if (k && (__del[k] || del2.indexOf(k) >= 0)) return;"
            + "     if (k) window.__rolePickSigs[k]=true;"
            + "     window.__rolePicks.push(o); });"
            + "   (function(){ var __a=window.__rolePicks||[]; var __ns=[];"
            + "     for(var __i=0;__i<__a.length;__i++){ var __p=__a[__i]; if(__p&&Array.isArray(__p._pickNos)){ for(var __j=0;__j<__p._pickNos.length;__j++){ if(typeof __p._pickNos[__j]==='number') __ns.push(__p._pickNos[__j]); } } }"
            + "     __ns.sort(function(a,b){return a-b;}); var __m={}; for(var __k=0;__k<__ns.length;__k++) __m[__ns[__k]]=__k+1;"
            + "     for(var __i2=0;__i2<__a.length;__i2++){ var __p2=__a[__i2]; if(__p2&&Array.isArray(__p2._pickNos)){ var __nn=[]; for(var __j2=0;__j2<__p2._pickNos.length;__j2++){ var __o=__p2._pickNos[__j2]; if(typeof __o==='number'&&__m[__o]!==undefined) __nn.push(__m[__o]); } __nn.sort(function(a,b){return a-b;}); __p2._pickNos=__nn; __p2._pickSeq=__nn.length>0?__nn[__nn.length-1]:0; } }"
            + "     window.__rolePickSeq=__ns.length; window.__roleMaxNo=__ns.length;"
            + "   })();"
            + "   if (window.__renderPicks) window.__renderPicks();"
            + " } catch(e){} }";

    // =====================================================================
    // I. waitForFunction 条件
    // =====================================================================

    /** waitForFunction 条件：用户点击结束拾取（__pickDone 置位）。 */
    public static final String WAIT_PICK_DONE_JS = "() => window.__pickDone === true";

    /** waitForFunction 条件：代码面板已关闭（__codePanelClosed 置位）。 */
    public static final String WAIT_CODE_PANEL_CLOSED_JS = "() => window.__codePanelClosed === true";

    /**
     * 构造 evaluate 实参（k1, v1, k2, v2 ...），避免每个调用点重复 new Map + 多次 put。
     * 供 `page.evaluate(SCRIPT, args(...))` 使用，使调用点保持单行。
     */
    public static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    static String concat(String a, String b) {
        return a + b;
    }

    static String loadScript(String fileName) {
        String path = "/scan/js/" + fileName;
        try (InputStream in = RolePickerScripts.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing picker script resource: " + path);
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read picker script resource: " + path, e);
        }
    }

}
