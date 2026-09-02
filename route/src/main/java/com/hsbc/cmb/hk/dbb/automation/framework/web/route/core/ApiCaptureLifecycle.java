package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ⭐ Phase 5 抽离：Page 生命周期门面 + 当前上下文绑定（原 {@code ApiCaptureContext} 的会话管理域）。
 *
 * <p>职责：按 {@link BrowserContext} 隔离管理其下活动 {@link Page} 的采集会话
 * （{@code start}/{@code attach}/{@code stop}/{@code detach}），维护线程级当前上下文绑定
 * （{@code CURRENT_CONTEXT} ThreadLocal），并在 Context / Page 关闭时编排清理
 * （RouteEngine / RouteRegistry / {@link ApiCaptureContext} 实例存储）。
 *
 * <p><b>零行为变更</b>：所有公开 static 方法与 {@code ApiCaptureContext} 完全等价；
 * {@code ApiCaptureContext} 保留同名公开壳并委托本类，调用方代码与既有集成护盾无需改动。
 * 本类与 {@code ApiCaptureContext} 形成清晰分层：本类 = 会话生命周期 + 当前绑定，
 * {@code ApiCaptureContext} = 上下文实例存储 + 查询 API。
 */
final class ApiCaptureLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiCaptureLifecycle.class);

    // ── 当前线程绑定的 BrowserContext（供 getCurrent 解析与隔离）──
    //   WeakReference 设计：BrowserContext 被 GC 后 ThreadLocal 不会阻止其回收，
    //   且 currentContextOrNull 解引用到 null 时顺手清掉失效条目。
    private static final ThreadLocal<WeakReference<BrowserContext>> CURRENT_CONTEXT = new ThreadLocal<>();

    // ⭐ 按 Context 隔离管理其下活动的 Page（不再维护 CaptureEngine 字段，旁路采集已移除）
    private static final ConcurrentHashMap<BrowserContext, Set<Page>> CONTEXT_PAGES =
            new ConcurrentHashMap<>();
    /** ⭐ 已注册 Context 关闭钩子的实例集合（幂等注册防重复，Playwright 无移除 listener API） */
    private static final ConcurrentHashMap<BrowserContext, Boolean> CONTEXT_CLOSE_REGISTERED =
            new ConcurrentHashMap<>();
    /** ⭐ 已注册 Page 级 onClose/onResponse 监听器的实例集合（幂等注册防重复，Playwright 无移除 listener API） */
    private static final ConcurrentHashMap<Page, Boolean> PAGE_LISTENER_REGISTERED =
            new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════
    // 当前上下文绑定
    // ═══════════════════════════════════════════════════

    /** 解引用当前线程绑定的 BrowserContext；引用已被 GC 清空时顺手移除 ThreadLocal 条目。 */
    static BrowserContext currentContextOrNull() {
        WeakReference<BrowserContext> ref = CURRENT_CONTEXT.get();
        if (ref == null) return null;
        BrowserContext context = ref.get();
        if (context == null) {
            CURRENT_CONTEXT.remove();
        }
        return context;
    }

    /** 将当前测试线程绑定到指定 BrowserContext，供旧兼容 API 正确隔离。 */
    static void bindCurrentContext(BrowserContext context) {
        if (context == null) CURRENT_CONTEXT.remove();
        else {
            CURRENT_CONTEXT.set(new WeakReference<>(context));
            ApiCaptureContext.forContext(context);
        }
    }

    /** 判断当前线程是否正绑定在指定 BrowserContext 上。 */
    static boolean isCurrentContext(BrowserContext context) {
        return context != null && currentContextOrNull() == context;
    }

    /** 清除当前测试线程的 Context 绑定，防止线程池线程污染后续测试。 */
    static void unbindCurrentContext() {
        CURRENT_CONTEXT.remove();
    }

    // ═══════════════════════════════════════════════════
    // 启动 / 停止 / 会话管理
    // ═══════════════════════════════════════════════════

    /** 启动 BrowserContext 级采集；已有 Page 需随后 attach。 */
    static void start(BrowserContext context) {
        if (context == null) throw new IllegalArgumentException("BrowserContext must not be null");
        RouteEngine.startContextEngine(context);
        CONTEXT_PAGES.computeIfAbsent(context, ignored -> ConcurrentHashMap.newKeySet());
        bindCurrentContext(context);
        registerContextCloseHook(context);
    }

    /**
     * ⭐ 注册 Context 级关闭钩子（幂等）：中途 Context 被关闭（登录态切换重建 / 浏览器退出等）时，
     * 自动停止该 Context 下全部 Page 采集，并清理 {@link ApiCaptureContext} 与 Context 级路由引擎。
     * 无论从 {@link #start(BrowserContext)} 还是 {@link #start(Page)} 进入都只注册一次。
     */
    private static void registerContextCloseHook(BrowserContext context) {
        if (context == null) return;
        if (CONTEXT_CLOSE_REGISTERED.putIfAbsent(context, Boolean.TRUE) == null) {
            context.onClose(ignored -> stop(context));
        }
    }

    /** 将 Page 加入所属 BrowserContext 的采集会话。 */
    static void attach(Page page) {
        if (page == null) throw new IllegalArgumentException("Page must not be null");
        BrowserContext context = page.context();
        start(context);
        start(page);
        CONTEXT_PAGES.get(context).add(page);
    }

    /** 从 Context 会话中移除 Page，不影响其它 Page。 */
    static void detach(Page page) {
        if (page == null) return;
        BrowserContext context = null;
        try { context = page.context(); } catch (Exception ignored) { }
        if (context != null) {
            Set<Page> pages = CONTEXT_PAGES.get(context);
            if (pages != null) {
                pages.remove(page);
                if (pages.isEmpty()) CONTEXT_PAGES.remove(context, pages);
            }
        }
        stop(page);
    }

    /** 停止 Context 下全部 Page 采集。 */
    static void stop(BrowserContext context) {
        if (context == null) return;
        // ⭐ 与 start(Page)/stop(Page)/stop() 共用 ApiCaptureContext.class 锁，防止并发修改 CONTEXT_PAGES
        synchronized (ApiCaptureContext.class) {
            Set<Page> pages = CONTEXT_PAGES.remove(context);
            if (pages != null) {
                for (Page page : new ArrayList<>(pages)) stop(page);
            }
            ApiCaptureContext.removeContext(context);
            RouteEngine.stopContextEngine(context);
            if (isCurrentContext(context)) {
                unbindCurrentContext();
            }
            // ⭐ 修复 P0-2：Context 关闭后清理幂等注册标记，避免 BrowserContext 强引用常驻 Map 导致泄漏
            CONTEXT_CLOSE_REGISTERED.remove(context);
            // ⭐ 显式清理 RouteRegistry 中该 Context 的残留条目（弱引用 ContextKey 失效后由本调用兜底清除，
            // 不依赖已移除的 purgeDeadEntries 定时扫描），避免死条目残留导致的内存泄漏。
            RouteRegistry.clearContext(context);
        }
    }

    /** 活动 Context 采集会话数，便于排查泄漏。 */
    static int activeContextCount() {
        return CONTEXT_PAGES.size();
    }

    // ═══════════════════════════════════════════════════
    // 启动 / 停止
    // ═══════════════════════════════════════════════════

    /**
     * 快速启动 — 一行代码开启全量 API 采集。
     *
     * <p>⭐ 旁路采集已移除：本方法仅绑定当前线程到 Page 所属 Context、登记 Page 到会话、
     * 注册关闭钩子，真实的响应采集由各 Route Handler 在 route 事件线程内同步完成。
     *
     * @param page Playwright Page 实例
     */
    static void start(Page page) {
        if (page == null) {
            throw new IllegalArgumentException("Page must not be null");
        }
        synchronized (ApiCaptureContext.class) {
            BrowserContext pageContext = page.context();
            CONTEXT_PAGES.computeIfAbsent(pageContext, ignored -> ConcurrentHashMap.newKeySet()).add(page);
            registerContextCloseHook(pageContext);
            // ⭐ 幂等注册 Page 级监听器：防止 attach/start 被重复调用时叠加多个 onClose/onResponse，
            //   导致兜底采集重复记录（破坏去重与计数）、监听器泄漏（Playwright 无移除 listener API，
            //   仅能在 Page 关闭时自动解绑，重复注册会累积至页面关闭）。stop(page) 清理标记后允许安全重注册。
            if (PAGE_LISTENER_REGISTERED.putIfAbsent(page, Boolean.TRUE) == null) {
                page.onClose(ignored -> detach(page));
                // ⭐ 挂接全局 onResponse 兜底监听器（Playwright 原生非侵入事件流）：
                //   捕获未注册流量，与各 Route Handler 零竞争；监听器随 Page 关闭自动解绑，无泄漏。
                if (ApiCaptureManager.isEnabled()) {
                    page.onResponse(response -> {
                        try {
                            ApiCaptureManager.getInstance().recordPassthrough(
                                    response.url(),
                                    response.status(),
                                    response.request().method(),
                                    response.request().headers(),
                                    response.headers());
                        } catch (Exception e) {
                            LOGGER.debug("[ApiCapture] onResponse skipped: {}", e.getMessage());
                        }
                    });
                }
            }
            // ⭐ 将调用线程绑定到该 Page 所属 BrowserContext，使 getCurrent() 指向正确的捕获上下文，
            //   消除跨用例数据串扰问题。
            bindCurrentContext(pageContext);
            LOGGER.info("[ApiCapture] Started for Page (activePages={})", activePageCount());
        }
    }

    /**
     * 链式启动入口（保留扩展空间）。
     *
     * <pre>{@code
     * ApiCaptureContext.on(page).start();
     * }</pre>
     */
    static ApiCaptureStart on(Page page) {
        return new ApiCaptureStart(page);
    }

    /** 停止采集并释放资源。 */
    static void stop() {
        synchronized (ApiCaptureContext.class) {
            for (Map.Entry<BrowserContext, Set<Page>> entry : new ArrayList<>(CONTEXT_PAGES.entrySet())) {
                for (Page page : new ArrayList<>(entry.getValue())) {
                    stop(page);
                }
            }
            LOGGER.info("[ApiCapture] Stopped all Page capture sessions");
        }
    }

    /** 停止并移除指定 Page 的采集会话，不影响同一 Context 的其它 Page。 */
    static void stop(Page page) {
        if (page == null) return;
        synchronized (ApiCaptureContext.class) {
            // ⭐ 清理 Page 级监听器注册标记，允许页面后续被重新 attach 时再次注册 onClose/onResponse
            PAGE_LISTENER_REGISTERED.remove(page);
            releaseContextIfOrphaned(page);
            LOGGER.info("[ApiCapture] Stopped Page capture session (activePages={})", activePageCount());
        }
    }

    /**
     * ⭐ 当 Page 所属 BrowserContext 已无其它活动采集会话时，清理该 Context 的
     * 捕获上下文（BY_CONTEXT 实例）与当前线程绑定，防止跨用例数据串扰与实例泄漏。
     */
    private static void releaseContextIfOrphaned(Page page) {
        BrowserContext pageContext;
        try {
            pageContext = page.context();
        } catch (Exception e) {
            return;
        }
        if (pageContext == null) return;
        // 仍存在同 Context 的活动采集会话 → 保留 Context 级绑定
        for (Map.Entry<BrowserContext, Set<Page>> entry : CONTEXT_PAGES.entrySet()) {
            if (entry.getKey() == pageContext) continue;
            for (Page other : entry.getValue()) {
                try {
                    if (other.context() == pageContext) return;
                } catch (Exception ignored) {
                    // 其它 Page 已关闭，忽略
                }
            }
        }
        Set<Page> pages = CONTEXT_PAGES.get(pageContext);
        if (pages != null) {
            pages.remove(page);
            if (pages.isEmpty()) {
                CONTEXT_PAGES.remove(pageContext, pages);
            }
        }
        ApiCaptureContext.removeContext(pageContext);
        if (isCurrentContext(pageContext)) {
            unbindCurrentContext();
        }
    }

    /** 当前活动 Page 采集会话数。 */
    static int activePageCount() {
        int total = 0;
        for (Set<Page> pages : CONTEXT_PAGES.values()) {
            total += pages.size();
        }
        return total;
    }
}
