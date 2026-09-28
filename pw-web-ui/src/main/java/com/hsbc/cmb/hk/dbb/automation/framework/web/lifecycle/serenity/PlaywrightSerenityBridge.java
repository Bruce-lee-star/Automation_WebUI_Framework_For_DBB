package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.CloseGuard;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.DownloadLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightContextManager;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.AutoBrowserProcessor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.session.SessionManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.common.logging.LogContext;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.microsoft.playwright.BrowserContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.HangWatchdog;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.trace.ScenarioTraceRecorder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextExecutor;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

/**
 * Serenity BDD 生命周期桥接 — 负责 Scenario/Feature 级别的初始化与清理编排
 * <p>
 * 从 PlaywrightManager 中独立出来，专注于生命周期调度：
 * - Scenario/Feature 级别初始化
 * - Context/Page 状态清理（Cookies、Storage、多余 Tab）
 * - 自定义配置重置策略
 * - 临时目录清理（下载）
 */
public class PlaywrightSerenityBridge {

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightSerenityBridge.class);

    // ==================== 临时目录清理 ====================

    /**
     * 通用临时目录清理方法
     */
    private static void cleanupTempDirectory(Path dir, String label, boolean verboseLog) {
        try {
            if (!Files.exists(dir)) {
                return;
            }
            AtomicInteger deletedCount = new AtomicInteger(0);
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        if (!path.equals(dir)) {
                            try {
                                Files.deleteIfExists(path);
                                deletedCount.incrementAndGet();
                            } catch (Exception ignored) {
                                VerboseLogging.logDebugIfVerbose(logger, "Skipping file during {} cleanup: {}", label, path);
                            }
                        }
                    });
            if (verboseLog) {
                VerboseLogging.logInfoIfVerbose(logger, "[{}] Cleaned {} file(s) from {}", label, deletedCount.get(), dir.toAbsolutePath());
            } else {
                VerboseLogging.logDebugIfVerbose(logger, "Cleaned {} {}: {} files from {}", label, deletedCount.get(), dir);
            }
        } catch (Exception e) {
            if (verboseLog) {
                logger.warn("[{}] Failed to clean temp files: {}", label, e.getMessage());
            } else {
                VerboseLogging.logWarnIfVerbose(logger, "Failed to clean {}: {}", label, e.getMessage());
            }
        }
    }

    /**
     * 清理<b>本线程</b>的下载目录（{@code <downloadsPath>/thread-<threadId>}）。
     *
     * <p><b>2026-09-21 修复（跨用例干扰）</b>：原实现清空<b>全局</b>下载目录 —— 真并行下会把并发 scenario
     * 正在下载/刚下载的文件一起删掉。现按线程隔离：<b>哪个线程收尾就只清它自己的目录</b>，与
     * {@link PlaywrightManager#downloadDirectoryForCurrentThread()}（下载保存所用的同一命名）成对。</p>
     *
     * <p><b>CT2-15（与异步保存的竞态）</b>：{@code download.saveAs(...)} 被卸载到 {@code AsyncPool}
     * 异步执行（见 {@link PlaywrightContextManager#saveDownloadAsync}），而本方法在场景收尾
     * <b>直接删除</b>该目录 —— 两者存在竞态：可能删掉尚未保存完的文件，并使 {@code DownloadRegistry}
     * 登记的路径指向不存在的文件（表现为「下载记录在、文件没了」）。</p>
     *
     * <p><b>处置：非阻塞跳过（刻意不等待）</b>。本方法位于<b>每个 scenario 的关键路径</b>上，
     * 在其中阻塞等待（哪怕设上限）会把后台异步保存的耗时转嫁给场景收尾：下载较多或
     * {@code AsyncPool} 繁忙时，逐场景累加可把并发下载类场景拖长到分钟级。
     * 而这里要保证的只是「不删未写完的文件」—— 检测到该目录仍有在途保存（
     * {@link DownloadLifecycle#pendingCount(java.nio.file.Path)} {@code > 0}）即<b>放弃本轮删除</b>：
     * 既不影响在途写入，也不让收尾多花一毫秒；保存正常在毫秒级完成，目录由下一轮收尾正常清除
     * （自愈，且不会无限滞留）。</p>
     */
    static void cleanupTempDownloads() {
        Path downloadDir = PlaywrightManager.downloadDirectoryForCurrentThread();
        int inFlight = DownloadLifecycle.pendingCount(downloadDir);
        if (inFlight > 0) {
            logger.info("[Download] deferred temp-dir cleanup: {} save(s) still in flight under {} — "
                    + "deleting it now would destroy unfinished files; a later cleanup round will remove it",
                    inFlight, downloadDir);
            return;
        }
        cleanupTempDirectory(downloadDir, "Download", true);
    }

    // ==================== ThreadLocal 清理 ====================

    /**
     * 统一清理所有 ThreadLocal 变量（防止线程复用时引用过期对象导致内存泄漏）
     *
     * @param clearContextAndPage 是否同时清理 Context 和 Page ThreadLocal
     */
    public static void cleanupThreadLocals(boolean clearContextAndPage) {
        if (clearContextAndPage) {
            TestContextHolder.get().remove(PlaywrightManager.PAGE_KEY);
            TestContextHolder.get().remove(PlaywrightManager.CONTEXT_KEY);
        }
        CustomOptionsManager.removeAllThreadLocals();
    }

    // ==================== 自定义配置重置 ====================

    /**
     * 重置所有自定义配置（核心：保证下一个场景默认不继承）。
     * <p>
     *  原实现先判断 {@code !browser().isConnected()} 并打印
     * “Cannot reset custom options: Context is still in use. Clearing anyway.”，
     * 随后却<b>无条件</b>执行 {@code cleanupThreadLocals(true)} ——
     * 该告警分支形同虚设，且措辞自相矛盾（先说 Cannot reset，又说 Clearing anyway），
     * 会误导排障者以为"清理没生效、配置被继承了"。
     * <p>
     * 事实上无论浏览器是否仍连接，这些 ThreadLocal 都必须清理：否则线程复用时既泄漏，
     * 又会让下一个场景误继承上一个场景的自定义配置。故直接删除该死分支。
     */
    static void resetCustomContextOptions() {
        VerboseLogging.logInfoIfVerbose(logger, "Resetting custom context options for next scenario...");
        cleanupThreadLocals(true);
        VerboseLogging.logInfoIfVerbose(logger, "Custom context options reset completed");
    }

    /**
     * Scenario 模式下重置自定义配置（保留 Context 实例）
     */
    static void resetCustomContextOptionsForScenarioMode() {
        VerboseLogging.logInfoIfVerbose(logger, "Resetting custom context options for Scenario mode (preserving Context)...");
        cleanupThreadLocals(false);
        TestContextHolder.get().remove(PlaywrightManager.PAGE_KEY);
        VerboseLogging.logInfoIfVerbose(logger, "Custom context options reset completed (Context preserved)");
    }

    /**
     * Feature 模式下重置自定义配置（保留 Session 相关配置）
     */
    static void resetCustomContextOptionsForFeatureMode() {
        VerboseLogging.logInfoIfVerbose(logger, "Resetting custom context options for Feature mode (preserving session config)...");
        CustomOptionsManager customOptions = CustomOptionsManager.getInstance();
        Path preservedStorageStatePath = customOptions.getStorageStatePath();
        String preservedStorageState = customOptions.getStorageState();
        cleanupThreadLocals(false);
        // 原样回填 storageState 路径与内存内容（Feature 模式跨 scenario 复用登录态所需），
        // 不触发 customContextOptionsFlag 置位、不触发 Context 重建（等价于直接 set 原 TestContext key）。
        if (preservedStorageStatePath != null || preservedStorageState != null) {
            customOptions.preserveStorageState(preservedStorageStatePath, preservedStorageState);
        }
        // 只要保留了任一 session 配置（路径或内存内容），即按 context 存活状态决定是否触发重建以应用 storageState
        if (preservedStorageStatePath != null || preservedStorageState != null) {
            //  修复问题3：先快照 context 存活状态，再据此设置 flag，避免"检查存活"与"设置 flag"
            // 之间的竞态窗口（若浏览器在此期间断开，flag 被设为 true 但 context 已不可用）。
            BrowserContext existingContext = TestContextHolder.get().get(PlaywrightManager.CONTEXT_KEY);
            boolean contextDead = (existingContext == null)
                    || (existingContext.browser() == null)
                    || !existingContext.browser().isConnected();
            if (contextDead) {
                customOptions.enableCustomOptions();
                VerboseLogging.logDebugIfVerbose(logger, "Feature mode: context null/closed, set flag to apply storage state");
            } else {
                VerboseLogging.logDebugIfVerbose(logger, "Feature mode: context exists, not setting flag");
            }
        }
        VerboseLogging.logInfoIfVerbose(logger, "Custom context options reset completed (Feature mode)");
    }

    // ==================== Context + Page 重建 ====================

    /**
     * 创建新的 Context 和 Page
     */
    public static void createNewContextAndPage() {
        PlaywrightManager.closePage();
        PlaywrightManager.closeContext();
        BrowserContext context = PlaywrightManager.getContext();
        TestContextHolder.get().set(PlaywrightManager.CONTEXT_KEY,context);
        Page page = PlaywrightContextManager.createPage(context);
        TestContextHolder.get().set(PlaywrightManager.PAGE_KEY,page);
        VerboseLogging.logDebugIfVerbose(logger, "New Context and Page created");
    }

    // ==================== Page 状态清理 ====================

    /**
     * 清理页面状态（但不关闭 Context/Page）
     * <p>
     * 用于 Feature 模式下 scenario 之间复用 Context/Page：
     * - 保留所有 Cookie（维持登录状态）
     * - 清理 LocalStorage/SessionStorage
     * - 关闭多余页面标签
     */
    static void cleanupPageState() {
        Page page = TestContextHolder.get().get(PlaywrightManager.PAGE_KEY);
        BrowserContext context = TestContextHolder.get().get(PlaywrightManager.CONTEXT_KEY);
        //  复用模式（reuse.context.within.feature）下，用例级键可能已被 @After 清空 →
        //  回退线程级记录，确保「关闭多余 tab / 复位主页面引用」在复用路径上依然生效。
        if (page == null) {
            page = PlaywrightManager.currentPageForThread();
        }
        if (context == null) {
            context = PlaywrightManager.currentContextForThread();
        }

        try {
            VerboseLogging.logInfoIfVerbose(logger, "Cleaning up page state (preserving all cookies)...");

            // 【加固】context 可能已因异常/503/浏览器关闭而失效：
            // 此时 context.pages()/newPage() 会抛 TargetClosedError，而这只是一种"清理期噪音"，
            // 既不影响测试结果判定，也容易误导成"浏览器崩溃"。故先探测存活，失效则直接跳过清理
            // （引用留空，后续 getContext()/getPage() 会自动重建），不打印冗长的 TargetClosedError。
            if (context != null && !isContextAlive(context)) {
                VerboseLogging.logInfoIfVerbose(logger,
                        "Cleanup skipped: BrowserContext already closed/expired (page/context will be rebuilt on next use)");
                TestContextHolder.get().remove(PlaywrightManager.PAGE_KEY);
                TestContextHolder.get().remove(PlaywrightManager.CONTEXT_KEY);
                return;
            }

            // 关闭多余页面标签
            if (context != null) {
                try {
                    List<Page> allPages = context.pages();
                    int pageCount = allPages.size();
                    if (pageCount > 1) {
                        VerboseLogging.logInfoIfVerbose(logger,
                                "Closing {} extra page(s) — keeping only main page", pageCount - 1);
                        for (int i = pageCount - 1; i >= 1; i--) {
                            Page extraPage = allPages.get(i);
                            try {
                                if (!extraPage.isClosed()) {
                                    extraPage.close();
                                }
                            } catch (Exception e) {
                                VerboseLogging.logWarnIfVerbose(logger, "Failed to close extra page at index {}: {}", i, e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    VerboseLogging.logWarnIfVerbose(logger, "Error closing extra pages: {}", e.getMessage());
                }
            }

            // 确保 page 引用指向第一个页面
            if (context != null) {
                try {
                    List<Page> allPages = context.pages();
                    if (!allPages.isEmpty()) {
                        Page mainPage = allPages.get(0);
                        if (page != mainPage && !mainPage.isClosed()) {
                            VerboseLogging.logInfoIfVerbose(logger, "Resetting page reference to main page");
                            page = mainPage;
                            PlaywrightManager.setPage(mainPage);
                        }
                    } else {
                        VerboseLogging.logInfoIfVerbose(logger, "No pages left, creating new Page");
                        page = PlaywrightContextManager.createPage(context);
                        PlaywrightManager.setPage(page);
                    }
                } catch (Exception e) {
                    // context 在两次探测之间恰好失效：不打印 TargetClosedError 噪音，静默跳过并留空引用。
                    VerboseLogging.logWarnIfVerbose(logger,
                            "Cleanup page reference failed (context likely closed), will rebuild on next use: {}", e.getClass().getSimpleName());
                    TestContextHolder.get().remove(PlaywrightManager.PAGE_KEY);
                    TestContextHolder.get().remove(PlaywrightManager.CONTEXT_KEY);
                    return;
                }
            }

            // 清理 storage（保留 cookies）
            if (page != null && !page.isClosed()) {
                cleanupPageStorage(page);
            }

            VerboseLogging.logInfoIfVerbose(logger, "Page state cleaned up (cookies preserved, extra tabs closed)");
        } catch (Exception e) {
            // 兜底：不打印 TargetClosedError 的冗长 JS 栈，仅输出异常类型，避免误导与噪音。
            VerboseLogging.logWarnIfVerbose(logger,
                    "Failed to cleanup page state ({}), will be rebuilt on next use",
                    e.getClass().getSimpleName());
        }
    }

    /** 探测 BrowserContext 是否仍存活：已关闭/浏览器断开时返回 false（不抛异常）。 */
    private static boolean isContextAlive(BrowserContext context) {
        if (context == null)  {return false;} 
        try {
            return context.browser() != null && context.browser().isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private static void cleanupPageStorage(Page page) {
        if (page == null || page.isClosed())  {return;} 

        try {
            page.evaluate("() => { try { localStorage.clear(); } catch(e) {} }");
            page.evaluate("() => { try { sessionStorage.clear(); } catch(e) {} }");
            page.evaluate("() => { "
                    + "try { "
                    + "  if (window.performance && window.performance.clearResourceTimings) "
                    + "    window.performance.clearResourceTimings(); "
                    + "} catch(e) {} "
                    + "}");
            page.evaluate("() => { "
                    + "try { "
                    + "  if (window._timeouts) window._timeouts.forEach(t => clearTimeout(t)); "
                    + "  if (window._intervals) window._intervals.forEach(t => clearInterval(t)); "
                    + "} catch(e) {} "
                    + "}");
        } catch (Exception e) {
            VerboseLogging.logWarnIfVerbose(logger, "Failed to cleanup page storage: {}", e.getMessage());
        }
    }

    // ==================== Scenario 生命周期 ====================

    /**
     * 跨用例收尾栅栏：有界等待<b>本线程</b> Context 上「上一用例的 route 收尾」收工。
     *
     * <p><b>为什么必须在 scenario 初始化时做</b>：route 的 teardown worker
     * （{@code RuleRepository.BoundedUnrouteTask}）是 fire-and-forget 守护线程，feature 模式下常在
     * <b>本用例已经开始之后</b>才收工。实测证据（2026-09-26 内网运行）：用例2 收尾 19:20:24.08 spawn worker，
     * 用例3 于 19:20:24.689 已初始化、24.71 注册新路由、24.817 导航 home，而 worker 直到
     * 19:20:25.733 才结束。窗口内 worker 在<b>同一个</b> Context 上派发页面事件
     * （日志：{@code [route-unroute-2076083498] PlaywrightContextManager - New page loaded: ...}）
     * 并与用例线程并发操作同一个 Playwright {@code Connection}，导致
     * {@code Object doesn't exist: response@...}（导航线程处理到已被驱动回收的 response 句柄）→
     * 会话校验导航失败 → 会话缓存被删除 → 每轮用例都完整重登。
     *
     * <p>无在途收尾时<b>零开销</b>（一次 Map 查询，不加锁、不阻塞）；超时仅告警并继续（语义不退化）。
     * 阈值单一定义点见 {@link RouteLifecycleRegistry#teardownFenceMs()}。
     */
    private static void awaitRouteTeardownFence() {
        RouteLifecycle routeLifecycle = RouteLifecycleRegistry.get();
        if (routeLifecycle == null) {
            return; // route 模块未启用（纯 web 场景）：无收尾可等
        }
        try {
            long timeoutMs = RouteLifecycleRegistry.teardownFenceMs();
            BrowserContext context = PlaywrightManager.currentContextForThread();
            if (context != null) {
                routeLifecycle.awaitTeardownFor(context, timeoutMs);
            }
            //  Page 所属 Context 可能与线程级记录不同（业务曾 setPage）：一并栅栏，避免盲区。
            Page page = PlaywrightManager.currentPageForThread();
            if (page != null) {
                BrowserContext pageContext = page.context();
                if (pageContext != null && pageContext != context) {
                    routeLifecycle.awaitTeardownFor(pageContext, timeoutMs);
                }
            }
        } catch (Exception e) {
            //  栅栏是"降低并发风险"的加固手段，任何异常都不得影响用例初始化主链路。
            VerboseLogging.logDebugIfVerbose(logger, "Route teardown fence skipped: {}", e.getMessage());
        }
    }

    /**
     * 「连接无响应」恢复：上一用例被判定 Playwright 协议往返无响应时，关闭并重建本线程的 Context 与 Browser。
     *
     * <p><b>要解决的问题</b>：Playwright 中有一类命令<b>无客户端超时</b>（实测
     * {@code setNetworkInterceptionPatterns}，即 {@code context.route()/unroute()}）。浏览器不回 ACK 时调用方
     * 永久阻塞（实测 {@code main} 线程在 {@code context.route()} 上停 300s+、两次采样同帧）。
     * route 侧已为该往返加有界预算，超时即登记「连接无响应」标记并让用例快速失败；本方法负责
     * <b>不让损坏的连接级联到后续用例</b>。
     *
     * <p><b>为何是这三步（全部复用既有、且都<b>有界</b>的原语，不新造机制）</b>：
     * <ol>
     *   <li>{@code closeContext()}：有界 1.5s（{@code CloseGuard}），摘除框架记录的 Context 并停其路由引擎
     *       ——顺带清掉「无响应」标记（标记随 Context 生命周期存活）；</li>
     *   <li>{@code closeBrowserForCurrentThread()}：有界 3s，超时即<b>强关本线程 driver</b>
     *       （{@code forceReapThreadPlaywright}）——这正是让"卡住的连接"真正失效的手段；</li>
     *   <li>{@code rebuildBrowser()}：此时旧实例已不在册（第 2 步已摘除），故其内部的
     *       {@code current.close()} 不会执行（该方法本身无界，必须靠第 2 步先行摘除），
     *       仅执行「新建 Browser + 置位 storageState 重放标记」，使新 Context 自动重新应用登录态。</li>
     * </ol>
     * 三步均只作用于<b>本线程</b>（每线程独立 Browser 模型），不影响并行邻居。
     *
     * <p>未启用 route 模块（{@code RouteLifecycleRegistry.get() == null}）或本线程无 Context 时为空操作。
     */
    private static void recoverIfConnectionUnresponsive() {
        RouteLifecycle routeLifecycle = RouteLifecycleRegistry.get();
        if (routeLifecycle == null) {
            return;
        }
        BrowserContext context = PlaywrightManager.currentContextForThread();
        if (context == null) {
            return; // 本用例将新建 Context：不存在"承接上一用例的坏连接"问题
        }
        boolean unresponsive;
        try {
            unresponsive = routeLifecycle.isConnectionUnresponsive(context);
        } catch (Exception e) {
            VerboseLogging.logDebugIfVerbose(logger, "Unresponsive-connection probe skipped: {}", e.getMessage());
            return;
        }
        if (!unresponsive) {
            return;
        }
        logger.warn("[Framework] Route connection flagged UNRESPONSIVE (no-ACK on Playwright round-trip without "
                        + "client timeout, e.g. context.route/unroute on {}) — rebuilding this thread's Context and "
                        + "Browser before the scenario to prevent the broken connection from cascading",
                context.getClass().getSimpleName());
        try {
            PlaywrightManager.closeContext();
            PlaywrightManager.closeBrowserForCurrentThread();
            PlaywrightManager.rebuildBrowser();
            logger.info("[Framework] Unresponsive-connection recovery completed — new Context/Browser will be "
                    + "created lazily with storageState re-applied");
        } catch (Exception e) {
            //  恢复失败也必须让流程继续：后续 getPage()/getBrowser() 仍会走各自的懒重建与断连重建路径。
            logger.warn("[Framework] Unresponsive-connection recovery failed (continuing): {}", e.getMessage());
        }
    }

    /**
     * Scenario 级别的初始化
     */
    public static void initializeForScenario() {
        VerboseLogging.logDebugIfVerbose(logger, "Initializing for scenario...");
        //  卡住诊断（2026-09-26）：用例一开始就武装看门狗 —— 用例超过采样间隔即打印线程现场。
        //  健康用例远短于 60s，故正常情况下零输出；出现输出即"该用例确实跑久了"。
        HangWatchdog.onScenarioStart(LogContext.currentScenarioId());

        if (!FrameworkState.getInstance().isInitialized()) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }
        //  跨用例栅栏（2026-09-26）：本用例很可能复用上一个用例的 Context，而上一用例的 route teardown
        //  可能仍在守护线程上操作该 Context（详见 awaitRouteTeardownFence）。必须在本用例开始碰
        //  Context/Page（注册路由、导航）之前有界等待其收工，否则并发操作同一 Playwright Connection 会抛
        //  Object doesn't exist: response@... → 会话校验失败 → 缓存误删 → 后续用例卡死。
        awaitRouteTeardownFence();

        // ⚠️ 修复级联：scenario 级 cleanupForScenario 会移除 currentConfigId（见 PlaywrightManager），
        //   但 frameworkState 仍 initialized。此处懒重建 configId，避免 beforeTest 误报"环境未初始化"
        //   而级联抛 IllegalStateException（场景实际仍能靠 getPage() 懒初始化正常运行）。
        if (TestContextHolder.get().get(PlaywrightManager.CURRENT_CONFIG_ID_KEY) == null) {
            PlaywrightManager.ensureConfigId();
        }
        //  连接无响应恢复（2026-09-26）：上一用例若在「无客户端超时」的协议往返上超时
        //  （Playwright 的 setNetworkInterceptionPatterns —— 即 context.route()/unroute()），该 Context 的连接
        //  已不可靠；继续复用会让本用例每次路由操作各付一次超时预算并快速失败。
        //  必须放在 ensureConfigId() 之后：恢复依赖本线程 configId 定位 Browser（重建前需先有 configId）。
        recoverIfConnectionUnresponsive();

        String restartBrowserForEach = PlaywrightManager.config().getRestartStrategy();

        if ("scenario".equalsIgnoreCase(restartBrowserForEach)) {
            PageObjectFactory.clearAll();
            //  线程级记录（不受用例边界影响）：用例级 CONTEXT_KEY 在收尾/跨用例时已被清空，
            //    用它判断会导致「明明有可复用 Context 却判为无」→ 每用例重建（重复开窗 + 丢登录态）。
            BrowserContext existingContext = PlaywrightManager.currentContextForThread();
            if (existingContext != null && existingContext.browser() != null
                    && existingContext.browser().isConnected()
                    && SessionManager.isAnyFeatureSessionRestored()) {
                PlaywrightManager.closePage();
                VerboseLogging.logDebugIfVerbose(logger,
                        "Scenario initialization completed (reusing existing Context with SessionManager)");
            } else {
                PlaywrightManager.closePage();
                PlaywrightManager.closeContext();
                VerboseLogging.logDebugIfVerbose(logger,
                        "Scenario initialization completed (Context will rebuild on demand)");
            }
        } else {
            //  Feature 模式：同一 feature 内复用<b>同一个</b> Context/Page（同 sessionKey 不再重建）。
            BrowserContext existingContext = PlaywrightManager.currentContextForThread();
            Page existingPage = PlaywrightManager.currentPageForThread();
            boolean contextAlive = existingContext != null && existingContext.browser() != null
                    && existingContext.browser().isConnected();
            if (existingContext != null && existingPage != null && !existingPage.isClosed()) {
                VerboseLogging.logDebugIfVerbose(logger,
                        "Scenario initialization completed (reusing existing Context/Page within same feature)");
            } else if (contextAlive && PlaywrightManager.currentContextSessionKeyForThread() != null) {
                //  A5（2026-09-26）：Context 仍承载登录态时，Page 缺失/已关闭**不构成**重建 Context 的理由 ——
                //  保留 Context 中的 Cookie/登录态，避免"前一个用例失败把 Page 弄坏 → 连 Context 一起关 →
                //  后续用例被迫完整重登"。
                //
                //  刻意**不对 Page 发任何关闭命令**：
                //  ① 进到本分支意味着 Page 已缺失或已关闭，对其 close() 只是记账，无实际收益；
                //  ② PlaywrightManager.closePage() 会回退到用例级 PAGE_KEY，理论上可能关掉那个键里
                //     仍存活的另一个 Page 实例（如业务曾用 setPage）；不调用即彻底没有这一风险；
                //  ③ 懒重建本就由 PageRegistryImpl.getPage() 负责（记录为空或 isClosed() 时在锁内新建），
                //     它会把已关闭的线程级记录正确替换掉，故此处无需任何显式关闭。
                PageObjectFactory.clearAll();
                VerboseLogging.logDebugIfVerbose(logger,
                        "Scenario initialization completed (keeping Context bound to session; Page rebuilds "
                                + "lazily via getPage())");
            } else {
                PageObjectFactory.clearAll();
                PlaywrightManager.closePage();
                PlaywrightManager.closeContext();
                VerboseLogging.logDebugIfVerbose(logger,
                        "Scenario initialization completed (Context closed, will rebuild on demand)");
            }
        }
    }

    /**
     * Scenario 级别的清理（等价于 {@link #cleanupForScenario(boolean)} 传"结果未知"，保持既有公开 API 不变）。
     */
    public static void cleanupForScenario() {
        cleanupForScenario(false);
    }

    /**
     * Scenario 级别的清理（带本用例结果）。
     *
     * <p><b>A5（2026-09-26）</b>：feature 模式下本用例<b>失败</b>时，除保留 Context（登录态/Cookie → 免登录）
     * 外，还要丢弃本用例的 <b>Page</b> —— 失败用例的 Page 可能处于"存活但已坏"状态（导航失败/半死），
     * 若原样交给下一个用例，脏状态会跨用例传染（实证 1.txt：场景2 导航失败后，场景3 复用同一 Page 直接卡死）。
     * 丢弃后由 {@code getPage()} 在<b>同一个 Context</b> 内懒重建 → 新文档 + 旧 Cookie，
     * 做到「既不带脏数据、又免登录」。
     *
     * @param scenarioFailed 本用例是否失败；{@code true} 时<b>仅丢弃 Page，绝不动 Context</b>
     */
    public static void cleanupForScenario(boolean scenarioFailed) {
        VerboseLogging.logDebugIfVerbose(logger, "Cleaning up for scenario...");
        //  卡住诊断：在收尾<b>入口</b>解除武装 —— 即使收尾本身抛异常也不会留下长期武装的采样；
        //  同时保证最后一个用例收尾后不再有采样（套件结束零残留）。
        HangWatchdog.onScenarioEnd();

        //  方案 A（2026-09-17）：先把本用例的 trace chunk 导出（此刻 context 仍存活），再做清理/关闭。
        //  这是 scenario 收尾的**唯一共同出口**（scenario 模式关 context / feature 模式保留 context 都经此），
        //  故在此收口可同时覆盖两条路径；与 PlaywrightListener 中带结果的调用互为幂等兜底（先到者生效）。
        //  MDC 此刻仍绑定（LogContext.endScenario 在监听器 finally 中），故 scenarioId 可稳定取得。
        ScenarioTraceRecorder.onScenarioEnd(LogContext.currentScenarioId(), null);

        cleanupTempDownloads();
        AutoBrowserProcessor.clearProcessingState();
        //  评审修复（2026-09-17）：请求作用域的 PageObject 现在以「用例」为键（不再是线程名），
        //  故必须在用例收尾处回收，否则实例会随用例数累积（且它们持有 Page/Context 引用）。
        PageObjectFactory.endRequestScope();

        String restartStrategy = PlaywrightManager.config().getRestartStrategy();
        //  标记本 scenario 是否真正关闭了 Context —— Browser 跟随 Context 边界，仅此时才关闭本线程 Browser。
        boolean scenarioClosedContext = false;

        if ("scenario".equalsIgnoreCase(restartStrategy)) {
            VerboseLogging.logDebugIfVerbose(logger,
                    "Restart strategy is 'scenario' - closing Context for fresh rebuild");
            //  关闭前吸收迟到事件（见 absorbPendingEventsBeforeClose 说明）：必须在 Context 仍存活时执行
            absorbPendingEventsBeforeClose();
            PlaywrightManager.closePage();
            PlaywrightManager.closeContext();
            //  窗口堆积修复：兜底回收本线程 Browser 上仍残留的 Context（closeContext 可能因 CONTEXT_KEY 丢失被跳过）
            PlaywrightManager.reapOrphanContexts();
            resetCustomContextOptionsForScenarioMode();
            SessionManager.resetFeatureSession();
            scenarioClosedContext = true;
        } else {
            boolean reuseWithinFeature =
                    WebFrameworkConfig.SERENITY_PLAYWRIGHT_REUSE_CONTEXT_WITHIN_FEATURE.getBooleanValue();
            //  A5（2026-09-26）：判据改用「当前 Context 是否仍承载登录态」（随 Context 生命周期存活的绑定），
            //  而非 SessionManager 的 "feature session restored" 标志 —— 后者会被用例失败清空
            //  （session 校验失败 → clearSession），从而在"同一个 sessionKey、本用例失败"时误关 Context，
            //  迫使后续同 key 用例完整重登（实证：1.txt 场景2 校验失败后场景3 重登并卡死）。
            //  语义：同 sessionKey 下失败也不关 Context（Cookie 仍在 → 免登录）；完全无登录态的用例
            //  仍按原语义关闭 Context 以防跨用例页面状态串扰。
            boolean contextHoldsLogin = PlaywrightManager.currentContextSessionKeyForThread() != null;
            if (!contextHoldsLogin && !SessionManager.isAnyFeatureSessionRestored() && !reuseWithinFeature) {
                VerboseLogging.logInfoIfVerbose(logger,
                        "Feature mode: no session bound to the current Context — closing it to avoid cookie contamination");
                //  关闭前吸收迟到事件（同上）：本分支同样会关 Context，故一并覆盖
                absorbPendingEventsBeforeClose();
                PlaywrightManager.closePage();
                PlaywrightManager.closeContext();
                //  窗口堆积修复：兜底回收残留 Context
                PlaywrightManager.reapOrphanContexts();
                scenarioClosedContext = true;
            } else {
                VerboseLogging.logDebugIfVerbose(logger,
                        "Restart strategy is 'feature' - keeping Context for reuse"
                                + (SessionManager.isAnyFeatureSessionRestored() ? "" : " (reuse-context-within-feature)"));
                resetCustomContextOptionsForFeatureMode();
                //  先清页面级状态（localStorage/sessionStorage/定时器；Cookie 保留），此刻 Page 仍存活
                cleanupPageState();
                if (scenarioFailed) {
                    //  A5：失败用例的 Page 可能"存活但已坏" → 丢弃它，绝不让脏 Page 跨用例传染。
                    //  只关 Page、**不关 Context**：Cookie/登录态仍在，下个用例 getPage() 在同一个
                    //  Context 内懒重建 → 新文档 + 免登录。这是"同一 sessionKey 下失败也不重建 Context、
                    //  但也不带脏数据"的落点。
                    VerboseLogging.logInfoIfVerbose(logger,
                            "Previous scenario FAILED — discarding its Page to avoid cross-scenario dirty state "
                                    + "(Context/cookies preserved for login-free reuse)");
                    PlaywrightManager.closePage();
                } else {
                    //  可观测性：让"本次判据=false（未丢 Page）"也可见，便于核对结果是否正确传入收尾链路
                    VerboseLogging.logDebugIfVerbose(logger,
                            "Previous scenario passed — keeping Context and Page for reuse");
                }
            }
        }

        // Browser 关闭时机（零配置默认行为）：Browser 跟随 Context 边界（per-scenario）。
        // 仅当本 scenario 实际关闭了 Context 才关闭本线程 Browser，下一 scenario 首次 getBrowser() 懒重建；
        // feature 模式跨场景复用同一 Context 时（session 恢复 / reuse-within-feature）不关 Browser，避免 Context 失活。
        // 自定义并发执行器分区模式跳过（跨线程键错配防护）。
        if (scenarioClosedContext) {
            closeBrowserForCurrentThreadIfApplicable();
        }
    }

    /**
     * 关闭 Context 前的「迟到事件吸收」静默窗口（毫秒）。
     *
     * <p>可通过 {@code -Dplaywright.teardown.eventAbsorbMs=N} 调整（设为 0 即关闭该机制）。
     */
    private static final int EVENT_ABSORB_SETTLE_MS =
            Integer.getInteger("playwright.teardown.eventAbsorbMs", 150);

    /**
     * 关闭 Context 前「吸收迟到事件」—— 实测 Flake（随机用例报
     * {@code Object doesn't exist: response@…}）的根治方向。
     *
     * <p><b>为什么需要</b>：Playwright Java <b>只在有命令等待返回时</b>才处理连接上的 incoming 消息。
     * 本场景 Context 关闭后，其上在途响应的 {@code response} 事件仍会到达；当下个命令（常见为
     * <b>下一场景</b>的首次 {@code page.evaluate}）的等待线程处理到它时，若事件引用的对象已随
     * Context 关闭被移除，客户端 {@code Connection.getExistingObject} 会抛
     * {@code Object doesn't exist}，并<b>顺着那条命令抛出</b> —— 失败被算到无关场景头上
     * （表现为随机用例挂、重试即过）。上游同名问题见 playwright-java#1439，无官方修复版本。
     *
     * <p><b>做法</b>：先给一个短静默窗口让在途响应抵达，再用一条<b>轻量服务端往返</b>命令
     * （{@code context.cookies()}）在本线程把已入队事件消化掉 —— 于是迟到事件大概率在
     * <b>收尾期</b>被吸收（异常隔离，仅记日志），而不是抛到下个场景。
     *
     * <p><b>耗时</b>：默认每场景约 {@value #EVENT_ABSORB_SETTLE_MS}ms（可用系统属性调整/关闭）。
     */
    /** 有界静默窗口（替代 {@code Thread.sleep}，满足框架 ArchUnit frameworkCodeMustNotCallThreadSleep）：
     *  用 {@code CompletableFuture.delayedExecutor} 调度一个完成信号、再 {@code join} 阻塞当前（收尾/清理）线程；
     *  join 不占用 Playwright 事件循环、不调 {@code Thread.sleep}，语义与 {@code Thread.sleep} 等价（仍阻塞调用线程 ms 毫秒）。 */
    private static void settle(long ms) {
        final java.util.concurrent.CompletableFuture<Void> f = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture.delayedExecutor((int) ms, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> f.complete(null));
        try {
            f.join();
        } catch (java.util.concurrent.CompletionException ce) {
            //  调度任务不会抛；防御性吞掉（interrupted 时重置标志，与原 Thread.sleep 分支语义一致）
            if (ce.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void absorbPendingEventsBeforeClose() {
        if (EVENT_ABSORB_SETTLE_MS <= 0) {
            return;
        }
        BrowserContext context;
        try {
            context = PlaywrightManager.currentContextForThread();
        } catch (Exception e) {
            return;   // 无法解析上下文（已关闭/无上下文）⇒ 无需吸收
        }
        if (context == null) {
            return;
        }
        try {
            settle(EVENT_ABSORB_SETTLE_MS);
            //  轻量往返：驱动客户端在本线程处理已入队的 incoming 事件。
            //  有界执行：cookies() 也可能因浏览器无响应而挂，限时防止 scenario 线程被卡。
            CloseGuard.runBounded("absorb-before-close", () -> context.cookies(), CloseGuard.ABSORB_OP_LIMIT_MS);
            VerboseLogging.logDebugIfVerbose(logger,
                    "Absorbed pending Playwright events before closing Context (settle={}ms)",
                    EVENT_ABSORB_SETTLE_MS);
        } catch (Exception e) {
            //  吸收失败绝不影响收尾（上下文已关闭/浏览器断开等）
            VerboseLogging.logDebugIfVerbose(logger,
                    "Event absorb skipped (context/browser gone): {}", e.toString());
        }
    }

    /**
     * 关闭 Context 之后的迟到事件：
     * 经删除「新建-关闭临时 Context」的做法——那属于<b>收尾期创建资源（操作）</b>，违反"只监控不操作"；
     * 且 live 监听器（{@code onPage/onResponse/onConsoleMessage/onPageError}）已在页面/上下文创建接缝处注册，
     * 迟到事件本就由它们监控。真正的"线程必释放"由 {@code CloseGuard} 有界关闭 + 超时强收本线程 Playwright 保证，
     * 无需在收尾期额外发起命令。关闭前的 {@code absorbPendingEventsBeforeClose}（只读 {@code cookies} 往返）已足够
     * 消化大多数在途事件，剩余的极小窗口丢失是可接受的监控代价。
     */

    // ==================== Feature 生命周期 ====================

    /**
     * Feature 级别的初始化
     */
    static void initializeForFeature() {
        VerboseLogging.logInfoIfVerbose(logger, "Initializing for feature...");

        if (!FrameworkState.getInstance().isInitialized()) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }
        // ⚠️ 同 initializeForScenario：currentConfigId 被 scenario 级清理移除后懒重建，避免级联抛错
        if (TestContextHolder.get().get(PlaywrightManager.CURRENT_CONFIG_ID_KEY) == null) {
            PlaywrightManager.ensureConfigId();
        }

        SessionManager.resetFeatureSession();

        String restartStrategy = PlaywrightManager.config().getRestartStrategy();
        if ("feature".equalsIgnoreCase(restartStrategy)) {
            //  原实现用 if/else 区分"context 为空或浏览器已断开"与"context 存在"，
            //   但两个分支<b>都只打印日志</b>，没有任何实际行为差异；且注释声称
            //   "pre-creating Context" 却并未真的创建 Context，属误导性代码。
            //   Context 的创建是懒加载的（首次 getContext() 时按需建立），此处不应预判，
            //   故合并为一条如实反映当前状态的日志。
            BrowserContext context = TestContextHolder.get().get(PlaywrightManager.CONTEXT_KEY);
            boolean reusable = context != null
                    && context.browser() != null
                    && context.browser().isConnected();
            VerboseLogging.logInfoIfVerbose(logger,
                    "Feature mode: Context {} (Contexts are created lazily on first use)",
                    reusable ? "exists and will be reused across scenarios"
                            : "is not created yet — it will be built lazily on first use");
        }
        VerboseLogging.logInfoIfVerbose(logger, "Feature initialization completed");
    }

    /**
     * Feature 级别的清理
     */
    public static void cleanupForFeature() {
        VerboseLogging.logInfoIfVerbose(logger,
                "Cleaning up for feature - closing Context (different feature requires fresh Context)...");
        PlaywrightManager.closePage();
        PlaywrightManager.closeContext();
        //  窗口堆积修复：兜底回收残留 Context
        PlaywrightManager.reapOrphanContexts();
        SessionManager.resetFeatureSession();
        // Browser 关闭时机（零配置默认行为）：feature 收尾关本线程 Browser（下一 feature 懒重建）；
        // 自定义并发执行器分区模式跳过（跨线程键错配防护）。
        closeBrowserForCurrentThreadIfApplicable();
        VerboseLogging.logInfoIfVerbose(logger,
                "Feature cleanup completed — Context+Page+Session cleared for next feature rebuild");
    }

    /**
     * 收尾时关闭<b>本线程</b>当前 Browser（零配置、默认行为）。
     *
     * <p><b>设计铁律：Browser 跟随其服务 Context 的边界（per-scenario）</b>——即「Browser 跟随 Context 走」。
     * scenario 收尾在 context 关闭后顺手关闭本线程 Browser，下一 scenario 首次 {@code getBrowser()} 懒重建，
     * 对外行为零回归。并行下因 Browser 键为 {@code <threadId>:<configId>}，天然线程隔离，绝不误伤邻居线程。</p>
     *
     * <p><b>护栏</b>：自定义并发执行器（{@link ConcurrentContextExecutor}）分区模式走「每线程独立 Browser 复用」语义，
     * 不适用按 scenario 关闭，故该模式跳过（避免线程池复用下的跨线程键错配）；其余情况一律执行。</p>
     */
    private static void closeBrowserForCurrentThreadIfApplicable() {
        // 并发执行器模式下默认也及时关闭本线程浏览器（多线程跑 case 不等全部跑完才关）；
        // 需要线程池跨任务复用浏览器（牺牲及时性换启动成本）时置 playwright.browser.close.after.concurrent.task=false。
        if (ConcurrentContextExecutor.isConcurrentModeActive()
                && !FrameworkConfigManager.getBoolean(
                        WebFrameworkConfig.PLAYWRIGHT_BROWSER_CLOSE_AFTER_CONCURRENT_TASK)) {
            return;
        }
        PlaywrightManager.closeBrowserForCurrentThread();
    }
}