package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享态宿主（T5-1 第二步 keystone）：收敛 {@link RoleElementPicker} 中全部按
 * {@link BrowserContext} / {@link Page} 分桶的静态态——原 {@code CTX_*} Map/Set 及
 * {@code LAST_PICK_ORIGIN} / {@code FORCE_START_TS} / {@code STATE_DELETED} 等统一收口到本类，
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
    static final Map<BrowserContext, RoleElementPicker.PickMode> CTX_PICK_MODES = new ConcurrentHashMap<>();
    static final Map<BrowserContext, java.util.Set<Page>> CTX_FRAMEWORK_CLOSED = new ConcurrentHashMap<>();
    static final Set<BrowserContext> CTX_PANEL_SCRIPTED = Collections.newSetFromMap(new ConcurrentHashMap<>());
    static final Map<BrowserContext, String> CTX_PICKER_NLS = new ConcurrentHashMap<>();
    static final Map<Page, String> LAST_PICK_ORIGIN = new ConcurrentHashMap<>();
    static final Map<Page, Long> FORCE_START_TS = new ConcurrentHashMap<>();
    static final Map<LinkedHashMap<String, RoleEntry>, Set<String>> STATE_DELETED = new ConcurrentHashMap<>();

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
     */
    static void cleanupContext(BrowserContext ctx) {
        if (ctx == null) return;
        CTX_CMD_QUEUES.remove(ctx);
        CTX_PICK_STATES.remove(ctx);
        CTX_BRIDGED.remove(ctx);
        CTX_PICK_MODES.remove(ctx);
        CTX_FRAMEWORK_CLOSED.remove(ctx);
        // 同步清理 LAST_PICK_ORIGIN 中属于该 context 的 page
        LAST_PICK_ORIGIN.keySet().removeIf(p -> {
            try {
                return p != null && p.context() == ctx;
            } catch (Exception ignore) {
                return true; // 页面已关闭，保守清理
            }
        });
    }

    /** 清理指定 Page 的 page-level 状态（frameNavigated 跟踪）。 */
    static void cleanupPage(Page page) {
        if (page == null) return;
        LAST_PICK_ORIGIN.remove(page);
    }

    /** 清空所有静态 Map —— 主要用于 JVM 关闭或测试集群重置。 */
    static void clearAll() {
        CTX_CMD_QUEUES.clear();
        CTX_PICK_STATES.clear();
        CTX_BRIDGED.clear();
        CTX_PICK_MODES.clear();
        CTX_FRAMEWORK_CLOSED.clear();
        LAST_PICK_ORIGIN.clear();
        // STATE_DELETED 中的 map 引用虽长，但已无法命中任何 CTX_PICK_STATES，安全清理
        STATE_DELETED.clear();
        //  补齐此前遗漏的两个静态缓存。
        //    GLOBAL_URL_TO_CLASS（URL → 派生类名）与 NLS_REVERSE_CACHE（nls 反查 JSON）
        //    都是 JVM 生命周期的静态 Map，clearAll 漏掉会让"重置/清空"名不副实：
        //    重置后仍持有旧站点 URL 与旧 nls 解析结果。
        RolePickerClassNameResolver.clear();
        RolePickerNlsCache.clear();
    }
}
