package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.state.PlaywrightRuntimeState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.provider.DefaultRuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTask;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.PageEventMonitor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.ProxyConfigResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestart;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestartImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCrashGuard;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.scenario.ScenarioLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.PlaywrightSerenityBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.SerenityBusBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.TestContextBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightContextManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightInitializer;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.media.PlaywrightScreenshotManager;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStackManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.CloudBrowserStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.LocalBrowserStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.InitializationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.codegen.spi.RoleCodegenBridgeRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import java.nio.file.Paths;
import java.awt.Dimension;

/** 
Browser cleanup and disconnect guard (WEB-P1-1 Step 5).
 Package-private internal collaborator. */
public final class BrowserCleanupImpl implements BrowserCleanup {

    /** Default singleton instance (stateless, thread-safe). */
    public static final BrowserCleanupImpl INSTANCE = new BrowserCleanupImpl();

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightManager.class);

    private BrowserCleanupImpl() {
    }

    /**
     * 清理所有资源
     */
    public void cleanupAll() {
        VerboseLogging.logInfoIfVerbose(logger, "Cleaning up all Playwright resources...");

        // 关闭当前线程的页面和上下文
        PlaywrightRuntime.instance().pageRegistry.closePage();
        PlaywrightRuntime.instance().contextRegistry.closeContext();

        // 关键遍历每个 Browser 实例，先关闭其所有 BrowserContext，再关闭 Browser，
        // 否则 BrowserContext 可能被静默丢弃（即便本框架不鼓励多线程持有 context，长跑+并发场景
        // 下仍有其他线程创建的 context 残留）。
        for (Browser browser : new ArrayList<>(PlaywrightRuntime.instance().state.allBrowsers())) {
            if (browser != null && browser.isConnected()) {
                try {
                    for (BrowserContext bc : browser.contexts()) {
                        if (bc == null) continue;
                        try {
                            //  修复 L3：改走 PlaywrightContextManager.closeContext，其内部已包含
                            //    ① 带 15s 超时的 tracing.stop（原 bc.close() 会跳过 trace 落盘，
                            //       留下不完整/残留 trace 文件）
                            //    ② RouteRegistry.clearContext（释放路由层对该 context 的引用，
                            //       否则调度器线程池会持有已销毁 context → 内存泄漏）
                            //    ③ 受保护的 context.close()
                            //  修复 L4：删除原死代码空 if 块，以及
                            //    bc.pages().removeIf(p -> !p.isClosed()) —— 该语句把【仍打开】的 page
                            //    从列表移除（与注释"已关闭的 page 跳过"语义相反），且 pages() 返回
                            //    不可变列表时 removeIf 会抛 UnsupportedOperationException，
                            //    而它与 bc.close() 在同一 try 块内 → 异常会【吞掉 close】导致 context 泄漏。
                            //    直接交给 closeContext 统一处理即可，无需手工增删 page 列表。
                            PlaywrightContextManager.closeContext(bc);
                        } catch (Exception ex) {
                            logger.warn("Error closing browser context: {}", ex.getMessage());
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Error iterating browser contexts during cleanupAll: {}", e.getMessage());
                }
            }
        }

        // 关闭所有浏览器实例（每个 try-catch 独立保护，防止单个失败阻断后续清理）
        for (Browser browser : new ArrayList<>(PlaywrightRuntime.instance().state.allBrowsers())) {
            if (browser != null && browser.isConnected()) {
                try {
                    browser.close();
                    VerboseLogging.logInfoIfVerbose(logger, "Browser instance closed");
                } catch (Exception e) {
                    logger.warn("Error closing browser instance during cleanupAll: {}", e.getMessage());
                }
            }
        }
        PlaywrightRuntime.instance().state.clearBrowsers();

        // 关闭所有 Playwright 实例
        PlaywrightRuntime.instance().state.allPlaywrights().forEach(playwright -> {
            try {
                playwright.close();
                VerboseLogging.logInfoIfVerbose(logger, "Playwright instance closed");
            } catch (Exception e) {
                logger.warn("Error closing Playwright instance", e);
            }
        });
        PlaywrightRuntime.instance().state.clearPlaywrights();

        // 统一清理所有 ThreadLocal（防止线程复用/线程池场景下的内存泄漏）
        PlaywrightSerenityBridge.cleanupThreadLocals(true);
        //  最终清理：Browser 已关闭，currentConfigId 可以安全清除
        TestContextHolder.get().remove(PlaywrightManager.CURRENT_CONFIG_ID_KEY);

        //  关闭 BrowserStack Local 隧道（由当前策略决定：本地策略内仍委托 BrowserStackManager.cleanup，非 Local 模式为 no-op）
        PlaywrightRuntime.instance().browserStartup.resolveStrategy().cleanup();

        //  修复 R5/R7：Browser 实例已全部关闭后，统一清理全局 Route 注册表与异步调度器，
        // 防止直接 close browser（未逐 context 关闭）场景下 DISPATCHED_ROUTES / 原生 route handler /
        // ContextRouteEngineManager 调度任务残留导致的泄漏与跨场景路由串扰。
        safeClean("ContextRouteEngineManager.stopAll", () -> RouteLifecycleRegistry.get().stopAllContextEngines());
        //  套件收尾：排空 route 侧在途工作（在途观测/body 读重试链）并让文件 sink 落盘
        safeClean("RouteLifecycle.drainForSuiteTeardown", () -> RouteLifecycleRegistry.get().drainForSuiteTeardown());
        safeClean("RouteRegistry.clearAll", () -> RouteLifecycleRegistry.get().clearAll());
        safeClean("AsyncPool.shutdown", () -> com.hsbc.cmb.hk.dbb.automation.framework.common.async.AsyncPool.shutdown());

        VerboseLogging.logInfoIfVerbose(logger, "All Playwright resources cleaned up");
    }

    /** 清理步骤包装：单步失败记录 warn 但不阻断后续清理（ 修复 R6）。 */
    public void safeClean(String step, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            logger.warn("[PlaywrightManager] Cleanup step '{}' failed (continuing): {}", step, t.getMessage());
        }
    }

    /**
     * 注册浏览器断开守卫：浏览器进程意外断开（崩溃/被杀/连接丢失）时记录严重错误，
     * 并标记该 Browser 已断开，使后续 PlaywrightManager.getPage()/PlaywrightManager.getContext() 快速失败并给出语义化异常，
     * 而非抛出晦涩的 Playwright 底层 NPE/StateError。
     */
    public void registerBrowserDisconnectGuard(Browser browser) {
        browser.onDisconnected(disconnected -> {
            // 快速失败标记照旧填充（getPage()/getContext() 的语义与改造前一致）。
            PlaywrightRuntime.instance().state.markDisconnected(browser);
            // 区分「框架主动关闭」与「真实意外断开」（WEB-P3-N14）：主动关闭路径先登记 closingBrowsers 再 close，
            // 或从实例表移除后再 close —— 二者均属预期；仍在管理表中却断开 = 崩溃/被杀，才需 ERROR 告警。
            boolean expected = PlaywrightRuntime.instance().state.isClosing(browser)
                    || !PlaywrightRuntime.instance().state.containsBrowserInstance(browser);
            String type = disconnected.browserType() != null ? disconnected.browserType().name() : "unknown";
            if (expected) {
                logger.debug("[browser-disconnected] Browser closed by framework (expected): type={}", type);
            } else {
                logger.error("[browser-disconnected] Browser disconnected unexpectedly: type={}", type);
            }
        });
    }

    /**
     * 主动关闭 Browser 实例的收口：关闭前置位 {@code closingBrowsers}，使 {@code onDisconnected}
     * 能识别「预期关闭」而非崩溃，避免收尾期把正常关闭记成 ERROR（WEB-P3-N14）。
     *
     * <p>异常不在此吞掉，交由调用点既有的 try-catch 处理，保持原有错误处理行为零变更。
     */
    public void closeBrowserInstance(Browser browser) {
        if (browser == null) {
            return;
        }
        PlaywrightRuntime.instance().state.markClosing(browser);
        browser.close();
    }

    /** 当前测试线程关联的 Browser 是否已被标记为断开。 */
    public boolean isCurrentBrowserDisconnected() {
        BrowserContext ctx = TestContextHolder.get().get(PlaywrightManager.CONTEXT_KEY);
        Browser b = ctx != null ? ctx.browser() : null;
        return b != null && PlaywrightRuntime.instance().state.isDisconnected(b);
    }

    /**
     * 兜底回收：关闭<b>本线程</b>所有 Browser 上仍打开的 BrowserContext（headed 模式即 OS 窗口）。
     *
     * <p><b>要解决的问题（窗口堆积）</b>：收尾链路 {@code cleanupForScenario}/{@code cleanupForFeature}
     * 关闭 Context 依赖 {@code TestContextHolder} 的 {@code CONTEXT_KEY}；当用例级上下文已解绑
     * （Cucumber {@code @After} 早于 Serenity {@code testFinished}）时该键取不到，{@code closeContext()}
     * 整段被静默跳过 —— Context/窗口既不关闭也不报错，跨用例持续堆积，直到套件级
     * {@link #cleanupAll()} 才随 Browser 释放（表现为"跑几十个用例后满屏浏览器窗口"）。
     *
     * <p>本方法<b>不依赖 {@code CONTEXT_KEY}</b>：直接从本线程的 Browser 枚举 contexts 逐个关闭；
     * 仅作用于 {@code "<threadId>:"} 前缀的实例，不触碰并发邻居线程的 Browser/Context。
     *
     * @return 实际关闭的 Context 数（供日志/单测观测）
     */
    public int closeOrphanContextsForCurrentThread() {
        String prefix = Thread.currentThread().threadId() + ":";
        int closed = 0;
        //  保护「本线程在用/复用的 Context」：绝不被兜底回收误关
        //  （例如 feature 模式复用同一 Context 时，即便本方法被触达也不动它）。
        BrowserContext protectedCtx = PlaywrightRuntime.instance().contextRegistry.currentContextForThread();
        for (Map.Entry<String, Browser> entry
                : new ArrayList<>(PlaywrightRuntime.instance().state.browserEntries())) {
            if (entry.getKey() == null || !entry.getKey().startsWith(prefix)) {
                continue;
            }
            Browser browser = entry.getValue();
            if (browser == null || !browser.isConnected()) {
                continue;
            }
            try {
                for (BrowserContext bc : browser.contexts()) {
                    if (bc == null) {
                        continue;
                    }
                    if (bc == protectedCtx) {
                        continue;
                    }
                    //  「context 关闭 → 所有活动立即停止」：先停该 context 引擎（置关闭标记 + 取消在途任务
                    //  + 关 per-context 调度器），再关采集，最后关 Context 本身。
                    PlaywrightRuntime.instance().browserCleanup.safeClean(
                            "RouteEngine.stopContextEngine", () -> RouteLifecycleRegistry.get().stopContextEngine(bc));
                    PlaywrightRuntime.instance().browserCleanup.safeClean(
                            "ApiCaptureContext.stop", () -> RouteLifecycleRegistry.get().stopCaptureFor(bc));
                    try {
                        // 复用统一收口：含 tracing 落盘、RouteRegistry.clearContext、受保护 close
                        PlaywrightContextManager.closeContext(bc);
                        closed++;
                    } catch (Exception ex) {
                        logger.warn("[closeOrphanContexts] Failed to close a context: {}", ex.getMessage());
                    }
                }
            } catch (Exception ex) {
                logger.warn("[closeOrphanContexts] Failed to iterate browser contexts: {}", ex.getMessage());
            }
        }
        if (closed > 0) {
            VerboseLogging.logInfoIfVerbose(logger,
                    "[closeOrphanContextsForCurrentThread] reaped {} leaked BrowserContext(s)", closed);
        }
        return closed;
    }

    /**
     * 枚举<b>本线程</b>所有 Browser 上仍打开的 BrowserContext（不关闭、不清理，仅返回引用）。
     *
     * <p>仅遍历 {@code "<threadId>:"} 前缀的 Browser —— <b>不触碰</b>并发邻居线程，
     * 供线程级资源清理（清路由/停引擎/停采集）使用。
     */
    public java.util.List<BrowserContext> contextsForCurrentThread() {
        String prefix = Thread.currentThread().threadId() + ":";
        java.util.List<BrowserContext> contexts = new ArrayList<>();
        for (Map.Entry<String, Browser> entry
                : new ArrayList<>(PlaywrightRuntime.instance().state.browserEntries())) {
            if (entry.getKey() == null || !entry.getKey().startsWith(prefix)) {
                continue;
            }
            Browser browser = entry.getValue();
            if (browser == null || !browser.isConnected()) {
                continue;
            }
            try {
                for (BrowserContext bc : browser.contexts()) {
                    if (bc != null) {
                        contexts.add(bc);
                    }
                }
            } catch (Exception ex) {
                logger.warn("[contextsForCurrentThread] Failed to iterate browser contexts: {}", ex.getMessage());
            }
        }
        return contexts;
    }

}
