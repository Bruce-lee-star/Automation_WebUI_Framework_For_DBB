package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Browser-injected script constants and resource loader, extracted from RoleElementPicker (T5-1 step 1).
 * Restored real values: the original step-1 extraction left self-referential null stubs, dropping the
 * actual scripts. Values copied verbatim from the pre-extraction source. Pure data + stateless helpers.
 */
final class RolePickerScripts {

    private RolePickerScripts() {}

    static final String PANEL_BOOTSTRAP_SCRIPT = // 墓碑门控：context 级 addInitScript 无法撤销，会话结束（closePanel/finally 置 '0'）后
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

    static final String MERGE_KEY_SHIM = " if (typeof window.__mergeKey !== 'function') { window.__mergeKey = function(p){ try{"
            + "   if (!p) return '';"
            + "   if (p._sigKey) return p._sigKey;"
            + "   if (typeof window.__sigKey === 'function') return window.__sigKey(p);"
            + "   var pk = p._pageClass || '';"
            + "   if (!pk) { try { pk = (location.origin||'') + (location.pathname||''); } catch(e){} }"
            + "   return JSON.stringify([p._sig || '', pk]);"
            + " }catch(e){ return ''; } }; }";

    static final String START_SCRIPT_A = loadScript("picker-core-a.js");

    static final String START_SCRIPT_B1 = loadScript("picker-core-b1.js");

    static final String START_SCRIPT_B2 = loadScript("picker-core-b2.js");

    static final String START_SCRIPT = concat(concat(START_SCRIPT_A, START_SCRIPT_B1), START_SCRIPT_B2);

    static final String STOP_SCRIPT = loadScript("picker-stop.js");

    static final String SHOW_PANEL_SCRIPT = loadScript("panel-show.js");

    static final String PANEL_SCRIPT_A = loadScript("panel-core-a.js");

    static final String PANEL_SCRIPT_B = loadScript("panel-core-b.js");

    static final String PANEL_SCRIPT = concat(PANEL_SCRIPT_A, PANEL_SCRIPT_B);

    static final String PICK_STATE_READER_JS = "(function(){"
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

    static final String DRAIN_PANEL_CMDS_JS = "(function(){"
            + " try { var a = window.__panelCmds || []; window.__panelCmds = []; return a; }"
            + " catch(e){ return []; } })()";

    static final String READ_REGION_FRAMES_JS = "() => {"
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

    static final String CLEAR_PICKS_JS = "try{"
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

    static final String READ_FRAME_PICKS_JS = "(function(){"
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

    static final String FRAME_SCAN_JS = "(function(){ try { return (typeof window.__roleScanPage==='function') ? window.__roleScanPage(null) : -1; } catch(e){ return -1; } })()";

    /** 整页跳转后，把 pagehide 落盘到 localStorage 的拾取态按权威键合并回当前 window（补回 Java 快照未覆盖的最新点击）。 */
    static final String MERGE_LOCALSTORAGE_PICKS_JS = "(function(){"
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

    /** 导航恢复/激活后的运行时诊断快照（会话开关、激活态、三大监听是否注入）。 */
    static final String NAV_DIAG_JS = "(function(){"
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

    /** 移除常驻面板：摘除点击/悬停/按键/焦点/滚动监听，复位 active，清会话开关并写面板墓碑（阻断门控自启）。 */
    static final String CLOSE_PANEL_JS = "(function(){"
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

    /** 读取拾取会话状态 JSON 字符串（picks/steps/currentStep/sigs/active），供跨页面（弹窗开合）搬运。 */
    static final String READ_PICK_STATE_JSON_JS = "(function() {"
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

    /** 读取「页面级操作」step（含 op 字段，如关闭页面），供生成 closeCurrentPage() 等步骤。 */
    static final String READ_PAGE_OPS_JS = "Array.from(window.__steps || []).filter(function(s){"
            + " return (s && typeof s === 'object' && typeof s.op === 'string'); })"
            + ".map(function(s){ return {pageClass:(s.pageClass||''), op:s.op}; })";

    /** 读取元素 step 序列并归一为 {pageClass, picks}（过滤掉含 op 的页面级操作 step）。 */
    static final String READ_STEPS_WITH_PAGE_JS = "Array.from(window.__steps || []).filter(function(s){"
            + " return !(s && typeof s === 'object' && typeof s.op === 'string'); }).map(function(s){"
            + " var t = (s && typeof s === 'object') ? s : null;"
            + " var pc = (t && typeof t.pageClass === 'string') ? t.pageClass : '';"
            + " var ps = (t && t.picks) ? t.picks : (Array.isArray(s) ? s : []);"
            + " return {pageClass: pc, picks: ps}; })";

    /** 面板是否已挂载且渲染函数就绪。 */
    static final String HAS_PANEL_JS = "!!(document.getElementById('__rolePanel') && window.__renderPicks)";

    /** 把最近一次拾取标记为 download（下载监听触发时）。 */
    static final String MARK_LAST_PICK_DOWNLOAD_JS = "if(window.__rolePicks && window.__rolePicks.length){"
            + " var p = window.__rolePicks[window.__rolePicks.length-1]; if(p) p.download = true; }";

    /** 把最近一次拾取标记为 upload（文件选择框监听触发时）。 */
    static final String MARK_LAST_PICK_UPLOAD_JS = "if(window.__rolePicks && window.__rolePicks.length){"
            + " var p = window.__rolePicks[window.__rolePicks.length-1]; if(p) p.upload = true; }";

    /**
     * 导航后兜底（延迟 60ms 等面板 build 完成）：用【稳定键】对 __rolePicks 做一次全局压实
     * （绝不用 location 兜底键，否则元素随跳转成倍累积），再重渲染面板、滚动到底、恢复上次生成的代码。
     */
    static final String POST_NAV_COMPACT_AND_RENDER_JS = "(function(){ setTimeout(function(){"
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
