package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.PlaywrightSerenityBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightContextManager;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.core.lifecycle.ShutdownCoordinator;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;

/** 
Browser cleanup and disconnect guard (WEB-P1-1 Step 5).
 Package-private internal collaborator. */
public final class BrowserCleanupImpl implements BrowserCleanup {

    /** Default singleton instance (stateless, thread-safe). */
    public static final BrowserCleanupImpl INSTANCE = new BrowserCleanupImpl();

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightManager.class);

    /** N-22：单步 / 整体清理耗时达到该值即在默认级别告警（毫秒）。正常为毫秒级，故不制造噪音。 */
    private static final long SLOW_CLOSE_WARN_MS = 3_000L;

    /**
     * N-22：单次 {@code Browser.close()} 的看门狗上限（毫秒）。
     *
     * <p><b>为什么必须由框架自己设限</b>：Playwright 1.62.0 的 {@code Browser.CloseOptions} 只有
     * {@code setReason(String)}、<b>没有 timeout</b>（已按字节码核实；{@code BrowserContext.CloseOptions} 同）。
     * 故 {@code close()} 会一直等浏览器进程退出 —— 实测某次 pw-web-ui 单测退出时该等待长达 <b>26.5s</b>，
     * 直接越过 Surefire「{@code System.exit(0)} 后 30s」宽限，导致 fork 被<b>硬杀</b>：后续关闭任务从未执行，
     * 日志还留下 {@code [ERROR] Surefire is going to kill self fork JVM}（详见 doc 21 §8.6）。</p>
     *
     * <p>取值 3s：正常关闭为毫秒级；超限即<b>放弃等待</b>（该关闭仍在 daemon 线程上继续，JVM 退出后由
     * Playwright driver 的进程树回收兜底），并记 ERROR + 计入 {@code ShutdownCoordinator} 失败计数 ——
     * 关键是<b>不阻断</b> {@code cleanupAll} 后续的路由排空 / AsyncPool / ThreadLocal 清理。</p>
     */
    private static final long BROWSER_CLOSE_LIMIT_MS = 3_000L;

    /**
     * N-23：单次 {@code BrowserContext.close()} 的看门狗上限（毫秒）。
     *
     * <p>与 {@link #BROWSER_CLOSE_LIMIT_MS} 同因：{@code BrowserContext.CloseOptions} 也只有
     * {@code setReason(String)}、<b>无 timeout</b>（1.62.0 字节码已核实）。取 1.5s：context 关闭比 browser 轻，
     * 且一个 Browser 可能挂多个 context，须给后续步骤留出预算。</p>
     */
    private static final long CONTEXT_CLOSE_LIMIT_MS = 1_500L;

    /**
     * N-23：{@code cleanupAll} 自身的内部预算（毫秒）—— 超过即<b>跳过剩余的 context / browser 关闭</b>，
     * 直接执行后面的路由排空 / AsyncPool / ThreadLocal 等收尾步骤。
     *
     * <p><b>为何需要它</b>：{@code ShutdownCoordinator} 的整任务上限（默认 8s）只保证"JVM 退出不被拖住"，
     * 一旦 {@code cleanupAll} 把预算耗在关闭环节，任务会被<b>整体放弃</b> —— 后面那些<b>便宜但重要</b>的收尾
     * 反而没做。故在内部先设 6s 预算（&lt; 8s 任务上限）：慢关闭属<b>可放弃项</b>，收尾步骤优先。</p>
     */
    private static final long CLEANUP_INTERNAL_BUDGET_MS = 6_000L;

    /** N-23：{@code cleanupAll} 内部预算是否仍有剩余（包级可见以便单测直接断言）。 */
    static boolean hasBudgetLeft(long startNanos, long budgetMs) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos) < budgetMs;
    }

    private BrowserCleanupImpl() {
    }

    /**
     * 清理所有资源
     */
    public void cleanupAll() {
        long cleanupStartNanos = System.nanoTime();
        //  N-23：内部预算耗尽只记一次账（避免每个 context/browser 都记一条）
        java.util.concurrent.atomic.AtomicBoolean budgetExhaustedReported =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable reportBudgetExhausted = () -> {
            if (budgetExhaustedReported.compareAndSet(false, true)) {
                ShutdownCoordinator.recordFailure("cleanupAll internal budget " + CLEANUP_INTERNAL_BUDGET_MS
                        + "ms exhausted — skipping the remaining context/browser closes so the remaining "
                        + "teardown steps (route drain / AsyncPool / ThreadLocal) still run", null);
            }
        };
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
                        if (bc == null)  {continue;} 
                        if (!hasBudgetLeft(cleanupStartNanos, CLEANUP_INTERNAL_BUDGET_MS)) {
                            //  N-23：内部预算已耗尽 —— 记一次账后停止关闭剩余 context（Loop B 也会立即跳过），
                            //    把有限时间留给后面的收尾步骤：慢关闭是可放弃项，收尾不是。
                            reportBudgetExhausted.run();
                            break;
                        }
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
                            //  N-23：看门狗执行 —— context.close() 与 Browser 同，无 API 超时
                            //    （BrowserContext.CloseOptions 只有 setReason）。超限即放弃等待 + 记账。
                            long contextLimitMs = Math.max(1, Math.min(CONTEXT_CLOSE_LIMIT_MS,
                                    CLEANUP_INTERNAL_BUDGET_MS - TimeUnit.NANOSECONDS.toMillis(
                                            System.nanoTime() - cleanupStartNanos)));
                            runBounded("context-close", () -> PlaywrightContextManager.closeContext(bc), contextLimitMs);
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
            if (!hasBudgetLeft(cleanupStartNanos, CLEANUP_INTERNAL_BUDGET_MS)) {
                //  N-23：内部预算已耗尽 → 跳过剩余 browser 关闭（已记账），直接做收尾步骤
                reportBudgetExhausted.run();
                break;
            }
            if (browser != null && browser.isConnected()) {
                try {
                    long closeStartNanos = System.nanoTime();
                    //  N-22：改走既有收口 closeBrowserInstance（先 markClosing 再 close）。
                    //    原先直接 browser.close() 时：该 Browser 仍在实例表中且【未】标记 closing →
                    //    onDisconnected 判定为「非预期」→ 关机日志出现
                    //    "[browser-disconnected] Browser disconnected unexpectedly" ERROR
                    //    —— 框架自己关闭的浏览器被记成"崩溃/被杀"（实测 fw-inst3.log 两条）。
                    //    这与下载专项里同源的「预期关闭未登记」是同一类问题，一处收口即覆盖。
                    //  N-22：看门狗执行 —— Playwright 的 close() 无 timeout 选项（字节码已核实），
                    //    故由框架设限：超限即放弃等待、记 ERROR + 计数，并继续后面的清理步骤
                    //    （整任务级预算只兜住 JVM 退出，救不了 cleanupAll 内后续步骤）。
                    String browserLabel = "browser-close:" + browserTypeName(browser);
                    long browserLimitMs = Math.max(1, Math.min(BROWSER_CLOSE_LIMIT_MS,
                            CLEANUP_INTERNAL_BUDGET_MS - TimeUnit.NANOSECONDS.toMillis(
                                    System.nanoTime() - cleanupStartNanos)));
                    boolean closedWithinLimit = runBounded(browserLabel,
                            () -> PlaywrightRuntime.instance().browserCleanup.closeBrowserInstance(browser),
                            browserLimitMs);
                    long closeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStartNanos);
                    if (!closedWithinLimit) {
                        //  放弃等待后【绝不】再触发第二次 close（会叠加一次同样的长阻塞）；
                        //  本次放弃已由 runBounded 记 ERROR + 计数，末尾 clearBrowsers() 会统一摘除该实例。
                        continue;
                    }
                    //  N-22 可观测性：慢关闭是「JVM 退出越过 surefire 30s 宽限 → fork 被硬杀」的唯一事后归因手段。
                    if (closeMs >= SLOW_CLOSE_WARN_MS) {
                        logger.warn("[cleanupAll] Browser.close() took {}ms (slow close — this is what can push JVM exit past "
                                + "surefire's 30s grace and get the fork hard-killed)", closeMs);
                    }
                    VerboseLogging.logInfoIfVerbose(logger, "Browser instance closed in {}ms", closeMs);
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

        //  N-22：整体耗时（含上面各步与 BrowserStack 隧道清理）。关机路径上"慢"必须可见 ——
        //    否则只会以 surefire 的 "[ERROR] ... kill self fork JVM" 形式间接暴露，无从归因。
        long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cleanupStartNanos);
        if (totalMs >= SLOW_CLOSE_WARN_MS) {
            logger.warn("[cleanupAll] cleanupAll took {}ms — JVM exit may exceed surefire's 30s grace "
                    + "(hard kill truncates the remaining shutdown tasks and can orphan browsers)", totalMs);
        }
        VerboseLogging.logInfoIfVerbose(logger, "All Playwright resources cleaned up ({}ms)", totalMs);
    }

    /**
     * 有界执行一次关闭动作（N-22）：超限即放弃等待并记 ERROR + 计数，返回 {@code false}。
     *
     * <p>用专属 daemon 线程执行是"可设限"的前提（Playwright 的 {@code close()} 本身无 timeout 选项，
     * 已按 1.62.0 字节码核实）。被放弃的动作仍在该线程上继续，JVM 退出后由 Playwright driver 的进程树
     * 回收兜底 —— 关键是<b>绝不让 JVM 退出被它拖住</b>（实测曾拖 26.5s 导致 fork 被硬杀）。</p>
     *
     * @param step    可定位的动作名（用于日志与失败计数）
     * @param action  关闭动作
     * @param limitMs 等待上限（毫秒）
     * @return true = 在限内完成；false = 超限被放弃（已记 ERROR + 计数）
     */
    static boolean runBounded(String step, Runnable action, long limitMs) {
        Thread worker = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                //  语义与原先 cleanupAll 的 catch 一致（warn 但不阻断后续清理）。动作已改到本线程执行，
                //  故必须在此兜住 —— 否则关闭异常只会变成"线程未捕获异常"的 stderr 噪声，反而更难排查。
                logger.warn("[cleanupAll] Cleanup step '{}' failed (continuing): {}", step, t.getMessage());
            }
        }, "close-bounded-" + step);
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(limitMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            //  复用 N-06 的失败收口：非 verbose 门控的 ERROR + 可断言计数（绝不静默拖住退出）
            ShutdownCoordinator.recordFailure(step + " exceeded " + limitMs + "ms and was abandoned "
                    + "(Playwright close() has no timeout option; JVM exit must not be blocked)", null);
            return false;
        }
        return true;
    }

    /** 浏览器类型名（仅用于日志/记账；取不到时回落 unknown，绝不因标签本身抛异常）。 */
    private static String browserTypeName(Browser browser) {
        try {
            return browser.browserType() != null ? browser.browserType().name() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
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
     * 关闭<b>本线程</b>当前持有的 Browser（{@code "<threadId>:<configId>"} 键）并从状态根移除。
     *
     * <p>仅作用于当前线程的 Browser，不触碰并发邻居线程；经 {@link #closeBrowserInstance} 收口关闭，
     * 使 {@code onDisconnected} 识别为预期关闭。保留同键 Playwright 实例以便下一场景廉价重建 Browser。</p>
     *
     * @return 实际关闭的 Browser 数（当前线程无存活 Browser 时为 0）
     */
    public int closeBrowserForCurrentThread() {
        String configId = PlaywrightManager.getCurrentConfigId();
        if (configId == null) {
            return 0;
        }
        String key = PlaywrightRuntime.instance().browserRegistry.keyFor(configId);
        Browser browser = PlaywrightRuntime.instance().state.getBrowser(key);
        if (browser != null && browser.isConnected()) {
            try {
                closeBrowserInstance(browser);
            } catch (Exception e) {
                logger.warn("[closeBrowserForCurrentThread] Error closing browser: {}", e.getMessage());
            }
            PlaywrightRuntime.instance().state.removeBrowser(key);
            VerboseLogging.logInfoIfVerbose(logger,
                    "[closeBrowserForCurrentThread] closed browser for key={}", key);
            return 1;
        }
        return 0;
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