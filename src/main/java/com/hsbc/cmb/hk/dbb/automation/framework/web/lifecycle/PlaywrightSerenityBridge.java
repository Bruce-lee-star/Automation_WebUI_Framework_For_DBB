package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.AutoBrowserProcessor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.session.SessionManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serenity BDD 生命周期桥接 — 负责 Scenario/Feature 级别的初始化与清理编排
 * <p>
 * 从 PlaywrightManager 中独立出来，专注于生命周期调度：
 * - Scenario/Feature 级别初始化
 * - Context/Page 状态清理（Cookies、Storage、多余 Tab）
 * - 自定义配置重置策略
 * - 临时目录清理（下载）
 */
class PlaywrightSerenityBridge {

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
                                LoggingConfigUtil.logDebugIfVerbose(logger, "Skipping file during {} cleanup: {}", label, path);
                            }
                        }
                    });
            if (verboseLog) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[{}] Cleaned {} file(s) from {}", label, deletedCount.get(), dir.toAbsolutePath());
            } else {
                LoggingConfigUtil.logDebugIfVerbose(logger, "Cleaned {} {}: {} files from {}", label, deletedCount.get(), dir);
            }
        } catch (Exception e) {
            if (verboseLog) {
                logger.warn("[{}] Failed to clean temp files: {}", label, e.getMessage());
            } else {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to clean {}: {}", label, e.getMessage());
            }
        }
    }

    /**
     * 清理临时下载目录（target/downloads）
     */
    static void cleanupTempDownloads() {
        String downloadsPath = PlaywrightManager.config().getBrowserDownloadsPath();
        cleanupTempDirectory(Paths.get(downloadsPath), "Download", true);
    }

    // ==================== ThreadLocal 清理 ====================

    /**
     * 统一清理所有 ThreadLocal 变量（防止线程复用时引用过期对象导致内存泄漏）
     *
     * @param clearContextAndPage 是否同时清理 Context 和 Page ThreadLocal
     */
    static void cleanupThreadLocals(boolean clearContextAndPage) {
        if (clearContextAndPage) {
            PlaywrightManager.pageThreadLocal.remove();
            PlaywrightManager.contextThreadLocal.remove();
        }
        CustomOptionsManager.customContextOptionsFlag.remove();
        CustomOptionsManager.customStorageStatePath.remove();
        CustomOptionsManager.customLocale.remove();
        CustomOptionsManager.customTimezoneId.remove();
        CustomOptionsManager.customUserAgent.remove();
        CustomOptionsManager.customPermissions.remove();
        CustomOptionsManager.customIsMobile.remove();
        CustomOptionsManager.customHasTouch.remove();
        CustomOptionsManager.customColorScheme.remove();
        CustomOptionsManager.customGeolocation.remove();
        CustomOptionsManager.customDeviceScaleFactor.remove();
        CustomOptionsManager.customViewportWidth.remove();
        CustomOptionsManager.customViewportHeight.remove();
        CustomOptionsManager.customProxyEnabled.remove();
    }

    // ==================== 自定义配置重置 ====================

    /**
     * 重置所有自定义配置（核心：保证下一个场景默认不继承）。
     * <p>
     * ⭐ 修复 P3-29：原实现先判断 {@code !browser().isConnected()} 并打印
     * “Cannot reset custom options: Context is still in use. Clearing anyway.”，
     * 随后却<b>无条件</b>执行 {@code cleanupThreadLocals(true)} ——
     * 该告警分支形同虚设，且措辞自相矛盾（先说 Cannot reset，又说 Clearing anyway），
     * 会误导排障者以为"清理没生效、配置被继承了"。
     * <p>
     * 事实上无论浏览器是否仍连接，这些 ThreadLocal 都必须清理：否则线程复用时既泄漏，
     * 又会让下一个场景误继承上一个场景的自定义配置。故直接删除该死分支。
     */
    static void resetCustomContextOptions() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Resetting custom context options for next scenario...");
        cleanupThreadLocals(true);
        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom context options reset completed");
    }

    /**
     * Scenario 模式下重置自定义配置（保留 Context 实例）
     */
    static void resetCustomContextOptionsForScenarioMode() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Resetting custom context options for Scenario mode (preserving Context)...");
        cleanupThreadLocals(false);
        PlaywrightManager.pageThreadLocal.remove();
        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom context options reset completed (Context preserved)");
    }

    /**
     * Feature 模式下重置自定义配置（保留 Session 相关配置）
     */
    static void resetCustomContextOptionsForFeatureMode() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Resetting custom context options for Feature mode (preserving session config)...");
        Path preservedStorageStatePath = CustomOptionsManager.customStorageStatePath.get();
        cleanupThreadLocals(false);
        if (preservedStorageStatePath != null) {
            CustomOptionsManager.customStorageStatePath.set(preservedStorageStatePath);
            // ⭐ 修复问题3：先快照 context 存活状态，再据此设置 flag，避免"检查存活"与"设置 flag"
            // 之间的竞态窗口（若浏览器在此期间断开，flag 被设为 true 但 context 已不可用）。
            BrowserContext existingContext = PlaywrightManager.contextThreadLocal.get();
            boolean contextDead = (existingContext == null)
                    || (existingContext.browser() == null)
                    || !existingContext.browser().isConnected();
            if (contextDead) {
                CustomOptionsManager.customContextOptionsFlag.set(true);
                LoggingConfigUtil.logDebugIfVerbose(logger, "Feature mode: context null/closed, set flag to apply storage state");
            } else {
                LoggingConfigUtil.logDebugIfVerbose(logger, "Feature mode: context exists, not setting flag");
            }
        }
        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom context options reset completed (Feature mode)");
    }

    // ==================== Context + Page 重建 ====================

    /**
     * 创建新的 Context 和 Page
     */
    static void createNewContextAndPage() {
        PlaywrightManager.closePage();
        PlaywrightManager.closeContext();
        BrowserContext context = PlaywrightManager.getContext();
        PlaywrightManager.contextThreadLocal.set(context);
        Page page = PlaywrightContextManager.createPage(context);
        PlaywrightManager.pageThreadLocal.set(page);
        LoggingConfigUtil.logDebugIfVerbose(logger, "New Context and Page created");
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
        Page page = PlaywrightManager.pageThreadLocal.get();
        BrowserContext context = PlaywrightManager.contextThreadLocal.get();

        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Cleaning up page state (preserving all cookies)...");

            // 【加固】context 可能已因异常/503/浏览器关闭而失效：
            // 此时 context.pages()/newPage() 会抛 TargetClosedError，而这只是一种"清理期噪音"，
            // 既不影响测试结果判定，也容易误导成"浏览器崩溃"。故先探测存活，失效则直接跳过清理
            // （引用留空，后续 getContext()/getPage() 会自动重建），不打印冗长的 TargetClosedError。
            if (context != null && !isContextAlive(context)) {
                LoggingConfigUtil.logInfoIfVerbose(logger,
                        "Cleanup skipped: BrowserContext already closed/expired (page/context will be rebuilt on next use)");
                PlaywrightManager.pageThreadLocal.remove();
                PlaywrightManager.contextThreadLocal.remove();
                return;
            }

            // 关闭多余页面标签
            if (context != null) {
                try {
                    List<Page> allPages = context.pages();
                    int pageCount = allPages.size();
                    if (pageCount > 1) {
                        LoggingConfigUtil.logInfoIfVerbose(logger,
                                "Closing {} extra page(s) — keeping only main page", pageCount - 1);
                        for (int i = pageCount - 1; i >= 1; i--) {
                            Page extraPage = allPages.get(i);
                            try {
                                if (!extraPage.isClosed()) {
                                    extraPage.close();
                                }
                            } catch (Exception e) {
                                LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to close extra page at index {}: {}", i, e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    LoggingConfigUtil.logWarnIfVerbose(logger, "Error closing extra pages: {}", e.getMessage());
                }
            }

            // 确保 page 引用指向第一个页面
            if (context != null) {
                try {
                    List<Page> allPages = context.pages();
                    if (!allPages.isEmpty()) {
                        Page mainPage = allPages.get(0);
                        if (page != mainPage && !mainPage.isClosed()) {
                            LoggingConfigUtil.logInfoIfVerbose(logger, "Resetting page reference to main page");
                            page = mainPage;
                            PlaywrightManager.setPage(mainPage);
                        }
                    } else {
                        LoggingConfigUtil.logInfoIfVerbose(logger, "No pages left, creating new Page");
                        page = PlaywrightContextManager.createPage(context);
                        PlaywrightManager.setPage(page);
                    }
                } catch (Exception e) {
                    // context 在两次探测之间恰好失效：不打印 TargetClosedError 噪音，静默跳过并留空引用。
                    LoggingConfigUtil.logWarnIfVerbose(logger,
                            "Cleanup page reference failed (context likely closed), will rebuild on next use: {}", e.getClass().getSimpleName());
                    PlaywrightManager.pageThreadLocal.remove();
                    PlaywrightManager.contextThreadLocal.remove();
                    return;
                }
            }

            // 清理 storage（保留 cookies）
            if (page != null && !page.isClosed()) {
                cleanupPageStorage(page);
            }

            LoggingConfigUtil.logInfoIfVerbose(logger, "Page state cleaned up (cookies preserved, extra tabs closed)");
        } catch (Exception e) {
            // 兜底：不打印 TargetClosedError 的冗长 JS 栈，仅输出异常类型，避免误导与噪音。
            LoggingConfigUtil.logWarnIfVerbose(logger,
                    "Failed to cleanup page state ({}), will be rebuilt on next use",
                    e.getClass().getSimpleName());
        }
    }

    /** 探测 BrowserContext 是否仍存活：已关闭/浏览器断开时返回 false（不抛异常）。 */
    private static boolean isContextAlive(BrowserContext context) {
        if (context == null) return false;
        try {
            return context.browser() != null && context.browser().isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private static void cleanupPageStorage(Page page) {
        if (page == null || page.isClosed()) return;

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
            LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to cleanup page storage: {}", e.getMessage());
        }
    }

    // ==================== Scenario 生命周期 ====================

    /**
     * Scenario 级别的初始化
     */
    static void initializeForScenario() {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Initializing for scenario...");

        if (!PlaywrightManager.getFrameworkState().isInitialized()) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }
        // ⚠️ 修复级联：scenario 级 cleanupForScenario 会移除 currentConfigId（见 PlaywrightManager），
        //   但 frameworkState 仍 initialized。此处懒重建 configId，避免 beforeTest 误报"环境未初始化"
        //   而级联抛 IllegalStateException（场景实际仍能靠 getPage() 懒初始化正常运行）。
        if (PlaywrightManager.currentConfigId.get() == null) {
            PlaywrightManager.ensureConfigId();
        }

        String restartBrowserForEach = PlaywrightManager.config().getRestartStrategy();

        if ("scenario".equalsIgnoreCase(restartBrowserForEach)) {
            PageObjectFactory.clearAll();
            BrowserContext existingContext = PlaywrightManager.contextThreadLocal.get();
            if (existingContext != null && existingContext.browser() != null
                    && existingContext.browser().isConnected()
                    && SessionManager.isAnyFeatureSessionRestored()) {
                PlaywrightManager.closePage();
                LoggingConfigUtil.logDebugIfVerbose(logger,
                        "Scenario initialization completed (reusing existing Context with SessionManager)");
            } else {
                PlaywrightManager.closePage();
                PlaywrightManager.closeContext();
                LoggingConfigUtil.logDebugIfVerbose(logger,
                        "Scenario initialization completed (Context will rebuild on demand)");
            }
        } else {
            BrowserContext existingContext = PlaywrightManager.contextThreadLocal.get();
            Page existingPage = PlaywrightManager.pageThreadLocal.get();
            if (existingContext != null && existingPage != null && !existingPage.isClosed()) {
                LoggingConfigUtil.logDebugIfVerbose(logger,
                        "Scenario initialization completed (reusing existing Context/Page within same feature)");
            } else {
                PageObjectFactory.clearAll();
                PlaywrightManager.closePage();
                PlaywrightManager.closeContext();
                LoggingConfigUtil.logDebugIfVerbose(logger,
                        "Scenario initialization completed (Context closed, will rebuild on demand)");
            }
        }
    }

    /**
     * Scenario 级别的清理
     */
    static void cleanupForScenario() {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Cleaning up for scenario...");

        cleanupTempDownloads();
        AutoBrowserProcessor.clearProcessingState();

        String restartStrategy = PlaywrightManager.config().getRestartStrategy();

        if ("scenario".equalsIgnoreCase(restartStrategy)) {
            LoggingConfigUtil.logDebugIfVerbose(logger,
                    "Restart strategy is 'scenario' - closing Context for fresh rebuild");
            PlaywrightManager.closePage();
            PlaywrightManager.closeContext();
            resetCustomContextOptionsForScenarioMode();
            SessionManager.resetFeatureSession();
        } else {
            if (!SessionManager.isAnyFeatureSessionRestored()) {
                LoggingConfigUtil.logInfoIfVerbose(logger,
                        "Feature mode: No session restored — closing Context to avoid cookie contamination");
                PlaywrightManager.closePage();
                PlaywrightManager.closeContext();
            } else {
                LoggingConfigUtil.logDebugIfVerbose(logger,
                        "Restart strategy is 'feature' - keeping Context and Page for reuse");
                resetCustomContextOptionsForFeatureMode();
                cleanupPageState();
            }
        }
    }

    // ==================== Feature 生命周期 ====================

    /**
     * Feature 级别的初始化
     */
    static void initializeForFeature() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Initializing for feature...");

        if (!PlaywrightManager.getFrameworkState().isInitialized()) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }
        // ⚠️ 同 initializeForScenario：currentConfigId 被 scenario 级清理移除后懒重建，避免级联抛错
        if (PlaywrightManager.currentConfigId.get() == null) {
            PlaywrightManager.ensureConfigId();
        }

        SessionManager.resetFeatureSession();

        String restartStrategy = PlaywrightManager.config().getRestartStrategy();
        if ("feature".equalsIgnoreCase(restartStrategy)) {
            // ⭐ 修复 P3-35：原实现用 if/else 区分"context 为空或浏览器已断开"与"context 存在"，
            //   但两个分支<b>都只打印日志</b>，没有任何实际行为差异；且注释声称
            //   "pre-creating Context" 却并未真的创建 Context，属误导性代码。
            //   Context 的创建是懒加载的（首次 getContext() 时按需建立），此处不应预判，
            //   故合并为一条如实反映当前状态的日志。
            BrowserContext context = PlaywrightManager.contextThreadLocal.get();
            boolean reusable = context != null
                    && context.browser() != null
                    && context.browser().isConnected();
            LoggingConfigUtil.logInfoIfVerbose(logger,
                    "Feature mode: Context {} (Contexts are created lazily on first use)",
                    reusable ? "exists and will be reused across scenarios"
                            : "is not created yet — it will be built lazily on first use");
        }
        LoggingConfigUtil.logInfoIfVerbose(logger, "Feature initialization completed");
    }

    /**
     * Feature 级别的清理
     */
    static void cleanupForFeature() {
        LoggingConfigUtil.logInfoIfVerbose(logger,
                "Cleaning up for feature - closing Context (different feature requires fresh Context)...");
        PlaywrightManager.closePage();
        PlaywrightManager.closeContext();
        SessionManager.resetFeatureSession();
        LoggingConfigUtil.logInfoIfVerbose(logger,
                "Feature cleanup completed — Browser persists, Context+Page+Session cleared for next feature rebuild");
    }
}
