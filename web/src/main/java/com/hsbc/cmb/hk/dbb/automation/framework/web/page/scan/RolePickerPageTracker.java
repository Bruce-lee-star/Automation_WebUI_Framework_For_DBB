package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * 页面跟踪引擎：维护「被跟踪页面 ↔ 页类名」映射，并在新页面打开/手动跳转/漏登场景下
 * 补做可被拾取的最小初始化与跟随。从 {@link RoleElementPicker} 抽出，方法体原样迁移，行为零变更。
 *
 * <p>说明：followPage 与仍留在 RoleElementPicker 的 registerPopupFollow 互递归，故本类通过包级可见的
 * RoleElementPicker 静态方法（registerPopupFollow / applyPickState / readPickStateJson / start）回调用；
 * 注入脚本常量统一取自 RolePickerScripts，调用点零字符串拼接。
 */
final class RolePickerPageTracker {

    private static final Logger log = LoggerFactory.getLogger(RolePickerPageTracker.class);

    private RolePickerPageTracker() {}

    /**
     * 开始拾取前，将浏览器当前真实打开的所有页面（context.pages()）与内存跟踪表 pageNames 对齐。
     * 兜底：任何在 stop→再 start 之间、或 onPage/onPopup/followPage 因异常而未登记进 pageNames 的打开页面，
     * 都会被补做最小初始化并纳入跟踪，使该页面在随后的 start 遍历中可被激活拾取，
     * 彻底消除"停止后再点开始，某个已打开页面点了开始却拾取不了"的问题。
     * 已登记页面不重复初始化（幂等）：命令桥/拾取桥用 Map 守卫仅注册一次；面板 addInitScript 仅对漏登页调用一次。
     */
    static void reconcileTrackedPages(RolePickerContext ctx, Page trigger) {
        LinkedHashMap<Page, String> pageNames = ctx.pageNames;
        LinkedHashMap<Page, String> snapshots = ctx.snapshots;
        LinkedHashMap<String, String> urlToClass = ctx.urlToClass;
        List<Page> openedPages = ctx.openedPages;
        BlockingQueue<RoleElementPicker.CmdEvent> cmdQueue = ctx.cmdQueue;
        LinkedHashMap<String, RoleEntry> javaPickBySig = ctx.javaPickBySig;
        if (trigger == null || trigger.isClosed()) return;
        for (Page p : trigger.context().pages()) {
            if (p == null || p.isClosed()) continue;
            if (!pageNames.containsKey(p)) {
                ensurePageTracked(ctx, p);
            } else {
                // 【修复"手动跳转后删除跨页误伤 / 再扫描为 0"】
                // 用户可能在面板之外手动导航（如直接改 URL、点原生链接跳转），这类跳转不经过
                // followPage/onPopup 钩子，window.__rolePageName 仍停留在旧页类名，导致新页拾取的元素
                // 被打上旧 pageClass；两个真实不同的页因此共享同一 pageClass，删除时整桶/值级兜底 +
                // RolePickerSessionState.STATE_DELETED 会把两页当一页一并清除，且已删键永久屏蔽后续扫描。
                // 此处对【每个已登记页】按当前 URL 重新解析 pageClass 并刷新其自身 window.__rolePageName，
                // 确保手动跳转后的页面拿到正确类名（每页写的是"它自己"的类名，而非当前激活页的），
                // 从源头杜绝跨页 pageClass 串味。幂等、仅当解析结果变化时写回。
                try {
                    String curCls = pageNames.get(p);
                    String newCls = RolePickerClassNameResolver.resolvePageClassForUrl(p.url(), pageNames.values(), urlToClass);
                    if (newCls != null && !newCls.equals(curCls)) {
                        pageNames.put(p, newCls);
                    }
                    p.evaluate(RolePickerScripts.SET_PAGE_NAME_IF_CHANGED_JS, RolePickerScripts.args("pageName", newCls));
                } catch (Exception refreshEx) {
                    log.warn("[picker] reconcile 刷新页面类名失败：{}", refreshEx.getMessage());
                }
            }
        }
    }

    /**
     * 对漏登记页面补做"可被拾取"的最小初始化（不搬运 opener 的当前 step，start 自身会续接/重置）。
     * 与 followPage 的区别：不注册子页跟随（避免重复 onPopup/onClose 监听），仅靠每次 start 的 reconcile
     * 形成闭环——若漏登页再开子页，子页也会在下次 start 时被补登。
     */
    static void ensurePageTracked(RolePickerContext ctx, Page p) {
        LinkedHashMap<Page, String> pageNames = ctx.pageNames;
        LinkedHashMap<Page, String> snapshots = ctx.snapshots;
        LinkedHashMap<String, String> urlToClass = ctx.urlToClass;
        List<Page> openedPages = ctx.openedPages;
        BlockingQueue<RoleElementPicker.CmdEvent> cmdQueue = ctx.cmdQueue;
        LinkedHashMap<String, RoleEntry> javaPickBySig = ctx.javaPickBySig;
        try {
            // 命令桥/拾取桥/面板重建脚本均已在 context 级一次性注册（registerContextBridges /
            // registerContextInitScripts），本页自动持有，无需逐页补注册。
            // 按 URL 解析并登记本页类名（复用会话稳定映射 urlToClass）
            String cls = RolePickerClassNameResolver.resolvePageClassForUrl(p.url(), pageNames.values(), urlToClass);
            pageNames.put(p, cls);
            if (!openedPages.contains(p)) openedPages.add(p);
            // 暴露页面类名 + 开启面板开关（与 openPanel/followPage 一致）
            p.evaluate(RolePickerScripts.SET_PAGE_NAME_JS, RolePickerScripts.args("pageName", cls));
            p.evaluate(RolePickerScripts.ENABLE_PANEL_JS + RolePickerScripts.SET_PANEL_FORCE_JS);
            p.evaluate(RolePickerScripts.PANEL_SCRIPT);   // 立即重建当前已加载文档的面板
            snapshots.put(p, RoleElementPicker.readPickStateJson(p));
            log.info("[picker][reconcile] 已补登漏跟踪页面并可被拾取：{} -> {}", p.url(), cls);
        } catch (Exception e) {
            log.warn("[picker][reconcile] 补登页面失败（该页本次 start 可能仍无法拾取）：{}", e.getMessage());
        }
    }

    /**
     * 把 inspector 跟随到 newPage 并重建面板（单实例，供 onPopup 与 context.onPage 共用）。
     * 多页面模型：新页面加载当前面板（携带已抓元素），并继续把新页面拾取的元素归属到对应 Page 类；
     * 元素按各页 window.__rolePageName 打 _pageClass 标签，生成时据此分组，实现"打开新页显示之前抓的元素"。
     */
    static void followPage(RolePickerContext ctx, Page opener, Page newPage) {
        Page[] current = ctx.current;
        boolean[] rootClosed = ctx.rootClosed;
        boolean[] active = ctx.active;
        String nlsReverseJson = ctx.nlsReverseJson;
        String[] nlsFiles = ctx.nlsFiles;
        String packageName = ctx.packageName;
        String pageClassName = ctx.pageClassName;
        String stepClassName = ctx.stepClassName;
        LinkedHashMap<Page, String> pageNames = ctx.pageNames;
        LinkedHashMap<Page, String> snapshots = ctx.snapshots;
        LinkedHashMap<String, String> urlToClass = ctx.urlToClass;
        List<Page> openedPages = ctx.openedPages;
        BlockingQueue<RoleElementPicker.CmdEvent> cmdQueue = ctx.cmdQueue;
        Set<Page> navigatedPages = ctx.navigatedPages;
        Object closeSignal = ctx.closeSignal;
        LinkedHashMap<String, RoleEntry> javaPickBySig = ctx.javaPickBySig;
        try {
            final boolean sessionActive = active[0];
            // 命令桥/拾取桥已在 context 级一次性注册（registerContextBridges），新页面自动持有绑定，
            // 无需逐页注册；面板按钮点击/拾取回传经 BindingCallback.Source 天然区分来源页面。
            // 多实例：保留原页面（默认页）面板，不关闭、也不停止其拾取态，
            // 使默认页面板在打开新页时不消失；新页面另行注入一个独立面板。
            // 两个页面各自维护自己的面板与 active 状态，互不干扰（不再调用 closePanel/stop(opener)）。
            // 由 URL 解析新页面的 Page 类名（优先复用 urlToClass 稳定映射，同一 URL 复用同一类名），
            // 登记进 pageNames / openedPages，供生成时落到对应类。
            String cls = RolePickerClassNameResolver.resolvePageClassForUrl(newPage.url(), pageNames.values(), urlToClass);
            pageNames.put(newPage, cls);
            openedPages.add(newPage);
            // 关键修复：打开新页面【不再】把默认页当前步收尾成一个 step。step 的唯一边界是"开始→停止"，
            // 弹窗打开/关闭都只是同一 step 内的交互，绝不该切分出额外 step（用户明确要求"只有一个条件：开始-停止"）。
            // 因此此处只把 opener 的"进行中 step"（__currentStep，已带各元素原 _pageClass）整体搬运到新页继续累积，
            // 并把 opener 的 __currentStep 清空（转移而非复制），避免关闭弹窗合并回来时出现重复元素。
            // 旧页 pick 已带原 _pageClass，新页面板会显示之前抓的元素；新页拾取的元素再打上 cls。
            // 当前页始终持有全部页面的 pick 并集，故代码生成可在单一窗口按各元素自身 _pageClass 归类。
            RoleElementPicker.applyPickState(newPage, RoleElementPicker.readPickStateJson(opener), nlsReverseJson, nlsFiles);
            if (opener != null && !opener.isClosed()) {
                opener.evaluate(RolePickerScripts.CLEAR_CURRENT_STEP_JS);
            }
            newPage.evaluate(RolePickerScripts.SET_PAGE_NAME_AND_RESET_INSTANCE_JS, RolePickerScripts.args("pageName", cls));
            // 跨源/新页面：localStorage 往往为空或不可写，若直接跑 PANEL_SCRIPT 会因
            // __rolePanelEnabled!=='1' 提前 return，导致新页面没有面板。故显式置位开关，
            // 并用 window.__rolePanelForce 兜底（即使 localStorage 不可用也能重建面板）。
            // 面板重建 addInitScript 已在 context 级注册（registerContextInitScripts），
            // 新页面后续导航（弹窗常伴随重定向）会自动重建面板，无需逐页注册。
            newPage.evaluate(RolePickerScripts.ENABLE_PANEL_JS + RolePickerScripts.SET_PANEL_FORCE_JS);
            // 若会话仍处于拾取中，则在新页面重启点击捕获监听（applyPickState 已把新页 active 置 false）：
            // 经 start() 同时置位会话开关 + 注入 nls，使该页后续导航由 context 门控注入脚本原生保活。
            // 【算法：零等待窗口的双保险注入，杜绝"卡住"】
            // onPopup 回调触发时新页文档可能还是 about:blank（尚未导航到真实 URL）。早期版本把 start() 延迟到
            // onLoadState 触发——但这引入"卡住窗口"：SPA 重定向/不触发 DOMContentLoaded 的页面会让监听永不挂载，
            // 表现为"新页无蓝框、换了个操作才突然好"（实则是别的导航触发 onFrameNavigated 补注入）。
            // 现改为【同步立即 start()】注入当前文档作为兜底，且 start() 已修复：about:blank 不再污染全局
            // RolePickerSessionState.LAST_PICK_ORIGIN。随后真实页导航由两条路径无缝接管，无任何等待间隙：
            //   ① context 级门控 addInitScript 在每个新文档早期自动跑，同源导航读得到 localStorage 开关即注入；
            //   ② onFrameNavigated 对跨域导航强制 start() 重注入（RolePickerSessionState.LAST_PICK_ORIGIN 未污染故能正确判跨域）。
            // 故弹出瞬间即具备基础监听，导航完成后即被真实库接管，用户体感"立即能拾取、不卡"。
            if (sessionActive) {
                try { RoleElementPicker.start(newPage, nlsReverseJson); } catch (Exception ex) {
                    log.warn("[picker] 新页面同步注入失败（导航中可忽略，将由 onFrameNavigated 补注入）：{}", ex.getMessage());
                }
            }
            // 面板脚本：初始文档也先注入一次；若后续导航重建，onFrameNavigated/PANEL addInitScript 会兜底。
            try { newPage.evaluate(RolePickerScripts.PANEL_SCRIPT); } catch (Exception ignore) {}
            current[0] = newPage;
            // 记录新页初始快照（含搬运来的并集），供导航重建（onFrameNavigated）与关闭回退（onClose）使用。
            snapshots.put(newPage, RoleElementPicker.readPickStateJson(newPage));
            // 新页面若再弹窗/再开页，继续跟随；把"是否处于拾取态"传下去，供其 onClose 回退父页时恢复。
            RoleElementPicker.registerPopupFollow(ctx, newPage, opener);
            log.info("[picker] 已在新页面（{}）注入独立面板，默认页面板保留不消失。", cls);
        } catch (Exception e) {
            log.warn("[picker] 页面跟随失败：{}", e.getMessage());
        }
    }
}
