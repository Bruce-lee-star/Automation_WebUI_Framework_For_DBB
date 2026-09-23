package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;


import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickMode;

/**
 * 共享态宿主（T5-1 第二步 keystone）：收敛 {@link RoleElementPicker} 中全部按
 * {@link BrowserContext} / {@link Page} 分桶的静态态——原 {@code CTX_*} Map/Set 及
 * {@code LAST_PICK_ORIGIN} / {@code FORCE_START_TS} 等统一收口到本类，
 * 配套清理/标记逻辑一并迁来。
 *
 * <p>本类仅为「状态容器 + 收口」，不引入任何新行为；{@code RoleElementPicker} 的公开静态方法
 * （{@code markFrameworkClose} / {@code cleanupContext} / {@code cleanupPage} / {@code clearAll}）
 * 保留为委托壳，对外 API 零变更，供后续把耦合核心按职责（命令桥 / 拾取循环 / 面板同步）安全拆分。
 */
final class RolePickerSessionState {

    private RolePickerSessionState() {}

    static final Map<BrowserContext, BlockingQueue<RolePickerBridgeRegistry.CmdEvent>> CTX_CMD_QUEUES =
            new ConcurrentHashMap<>();
    static final Map<BrowserContext, LinkedHashMap<String, RoleEntry>> CTX_PICK_STATES =
            new ConcurrentHashMap<>();
    static final Set<BrowserContext> CTX_BRIDGED = Collections.newSetFromMap(new ConcurrentHashMap<>());
    static final Map<BrowserContext, PickMode> CTX_PICK_MODES = new ConcurrentHashMap<>();
    static final Map<BrowserContext, java.util.Set<Page>> CTX_FRAMEWORK_CLOSED = new ConcurrentHashMap<>();
    static final Set<BrowserContext> CTX_PANEL_SCRIPTED = Collections.newSetFromMap(new ConcurrentHashMap<>());
    static final Map<BrowserContext, String> CTX_PICKER_NLS = new ConcurrentHashMap<>();
    static final Map<Page, String> LAST_PICK_ORIGIN = new ConcurrentHashMap<>();
    static final Map<Page, Long> FORCE_START_TS = new ConcurrentHashMap<>();
    //  N-18：原 STATE_DELETED（Map<LinkedHashMap<String,RoleEntry>, Set<String>>）已删除。两处原因：
    //    ① 它【已无任何写入点】—— 自「删除的语义 = 从当前拾取列表移除、允许重新拾取」定稿后，
    //       两处写入点被刻意移除（RolePickerBridgeRegistry 的 exposeBinding / 绑定通道），
    //       唯一读取点（RolePickerPanelSync 的已删判定）因此恒为 false：死状态 + 死分支，
    //       且其存在会让人误以为「已删元素复活」已被防住（假保护比没有更危险）。
    //    ② 即使写入点还在，用它也【不可靠】：键是【可变的】LinkedHashMap（CTX_PICK_STATES 的活体），
    //       一旦该 map 在登记后又被写入，其 hashCode 变化 → 在 ConcurrentHashMap 中再也查不回来
    //       （静默失效）。若将来确需重建「已删屏蔽」，键必须用稳定标识（如 BrowserContext 或不可变键），
    //       不可再拿活体容器当键。

    /** 标记"由框架主动关闭（BasePage.closeCurrentPage 调 page.close()）"的页面，按 context 隔离。 */
    static void markFrameworkClose(Page page) {
        if (page == null) return;
        BrowserContext ctx = page.context();
        CTX_FRAMEWORK_CLOSED.computeIfAbsent(ctx, c -> ConcurrentHashMap.newKeySet()).add(page);
    }

    /** 供 onClose 判断：本次关闭是否来自框架主动调用；读取后清除标记（页面关后即失效）。 */
    static boolean consumeFrameworkClose(Page closed) {
        if (closed == null) return false;
        try {
            BrowserContext ctx = closed.context();
            java.util.Set<Page> set = CTX_FRAMEWORK_CLOSED.get(ctx);
            if (set != null && set.remove(closed)) {
                if (set.isEmpty()) CTX_FRAMEWORK_CLOSED.remove(ctx);
                return true;
            }
        } catch (Exception ignore) { /* 页面已关，context 可能失效，按未标记处理 */ }
        return false;
    }

    /**
     * 彻底清理指定 BrowserContext 在所有静态 Map 中的状态。
     * 调用时机：BrowserContext 关闭之后（PlaywrightManager.closeContext 钩子 / @AfterMethod / hook）。
     *
     * <p>CT2-09：补齐此前<b>两个清理入口都未清理</b>的三张静态态
     * （{@code CTX_PANEL_SCRIPTED} / {@code CTX_PICKER_NLS} 按 context，{@code FORCE_START_TS} 按 page），
     * 并把 {@code EVAL_LOCKS}（CT2-09）与 {@code LAST_SYNC_SIG}（CT2-20）按其归属类一并回收 ——
     * 触发面是任何自定义 Context 选项（locale / viewport / storageState）触发的重建，属<b>高频路径</b>，
     * 漏清会让"彻底清理"名不副实，并长期持有旧 context / 已关闭 Page 的强引用。
     */
    static void cleanupContext(BrowserContext ctx) {
        if (ctx == null) return;
        CTX_CMD_QUEUES.remove(ctx);
        CTX_PICK_STATES.remove(ctx);
        CTX_BRIDGED.remove(ctx);
        CTX_PICK_MODES.remove(ctx);
        CTX_FRAMEWORK_CLOSED.remove(ctx);
        // CT2-09：此前遗漏的两张「按 context」Map
        CTX_PANEL_SCRIPTED.remove(ctx);
        CTX_PICKER_NLS.remove(ctx);
        // 同步清理 LAST_PICK_ORIGIN / FORCE_START_TS 中属于该 context 的 page（CT2-09：后者此前遗漏）
        LAST_PICK_ORIGIN.keySet().removeIf(p -> pageBelongsToContext(p, ctx));
        FORCE_START_TS.keySet().removeIf(p -> pageBelongsToContext(p, ctx));
        // CT2-09 / CT2-20：回收归属其它类的按 Page / 按 context 静态缓存
        RoleElementPicker.releaseEvalLocksForContext(ctx);
        RolePickerPanelSync.cleanupContext(ctx);
    }

    /** CT2-09：page 是否属于该 context；句柄失效时保守返回 {@code true}（清理优先）。 */
    private static boolean pageBelongsToContext(Page p, BrowserContext ctx) {
        if (p == null) {
            return false;
        }
        try {
            return p.context() == ctx;
        } catch (Exception ignore) {
            return true; // 页面已关闭，保守清理
        }
    }

    /** 清理指定 Page 的 page-level 状态（frameNavigated 跟踪）。 */
    static void cleanupPage(Page page) {
        if (page == null) return;
        LAST_PICK_ORIGIN.remove(page);
        // CT2-09 / CT2-20：同属 page-level、此前漏清的两张静态缓存与一类串行锁
        FORCE_START_TS.remove(page);
        RoleElementPicker.releaseEvalLocksForPage(page);
        RolePickerPanelSync.cleanupPage(page);
    }

    /** 清空所有静态 Map —— 主要用于 JVM 关闭或测试集群重置。 */
    static void clearAll() {
        CTX_CMD_QUEUES.clear();
        CTX_PICK_STATES.clear();
        CTX_BRIDGED.clear();
        CTX_PICK_MODES.clear();
        CTX_FRAMEWORK_CLOSED.clear();
        LAST_PICK_ORIGIN.clear();
        // CT2-09：补齐此前遗漏的三张静态 Map（面板已注入标记 / picker 语言 / 强制启动时间戳）。
        //  漏清会让"重置/清空"名不副实：重置后仍持有旧站点的面板注入标记与旧语言设置。
        CTX_PANEL_SCRIPTED.clear();
        CTX_PICKER_NLS.clear();
        FORCE_START_TS.clear();
        // CT2-09 / CT2-20：回收归属其它类的静态缓存（evaluate 串行锁 / 面板同步 ETag）
        RoleElementPicker.clearEvalLocks();
        RolePickerPanelSync.clearAll();
        // N-18：STATE_DELETED 已删除，其清理动作随之移除（清理已不存在的状态无意义）。
        //  补齐此前遗漏的两个静态缓存。
        //    GLOBAL_URL_TO_CLASS（URL → 派生类名）与 NLS_REVERSE_CACHE（nls 反查 JSON）
        //    都是 JVM 生命周期的静态 Map，clearAll 漏掉会让"重置/清空"名不副实：
        //    重置后仍持有旧站点 URL 与旧 nls 解析结果。
        RolePickerClassNameResolver.clear();
        RolePickerNlsCache.clear();
    }
}
