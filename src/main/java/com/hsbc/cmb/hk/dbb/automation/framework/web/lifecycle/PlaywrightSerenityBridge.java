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
        PlaywrightManager.customContextOptionsFlag.remove();
        PlaywrightManager.customStorageStatePath.remove();
        PlaywrightManager.customLocale.remove();
        PlaywrightManager.customTimezoneId.remove();
        PlaywrightManager.customUserAgent.remove();
        PlaywrightManager.customPermissions.remove();
        PlaywrightManager.customIsMobile.remove();
        PlaywrightManager.customHasTouch.remove();
        PlaywrightManager.customColorScheme.remove();
        PlaywrightManager.customGeolocation.remove();
        PlaywrightManager.customDeviceScaleFactor.remove();
        PlaywrightManager.customViewportWidth.remove();
        PlaywrightManager.customViewportHeight.remove();
        PlaywrightManager.customProxyEnabled.remove();
    }

    // ==================== 自定义配置重置 ====================

    /**
     * 重置所有自定义配置（核心：保证下一个场景默认不继承）
     */
    static void resetCustomContextOptions() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Resetting custom context options for next scenario...");
        BrowserContext existingContext = PlaywrightManager.contextThreadLocal.get();
        if (existingContext != null && !existingContext.browser().isConnected()) {
            LoggingConfigUtil.logWarnIfVerbose(logger,
                    "Cannot reset custom options: Context is still in use. Clearing anyway.");
        }
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
        Path preservedStorageStatePath = PlaywrightManager.customStorageStatePath.get();
        cleanupThreadLocals(false);
        if (preservedStorageStatePath != null) {
            PlaywrightManager.customStorageStatePath.set(preservedStorageStatePath);
            BrowserContext existingContext = PlaywrightManager.contextThreadLocal.get();
            if (existingContext == null || (existingContext.browser() != null && !existingContext.browser().isConnected())) {
                PlaywrightManager.customContextOptionsFlag.set(true);
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

            // 关闭多余页面标签
            if (context != null) {
                try {
                    java.util.List<Page> allPages = context.pages();
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
                java.util.List<Page> allPages = context.pages();
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
            }

            // 清理 storage（保留 cookies）
            if (page != null && !page.isClosed()) {
                cleanupPageStorage(page);
            }

            LoggingConfigUtil.logInfoIfVerbose(logger, "Page state cleaned up (cookies preserved, extra tabs closed)");
        } catch (Exception e) {
            logger.warn("Failed to cleanup page state: {}", e.getMessage());
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

        if (!PlaywrightManager.getFrameworkState().isInitialized() || PlaywrightManager.currentConfigId.get() == null) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
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

        if (!PlaywrightManager.getFrameworkState().isInitialized() || PlaywrightManager.currentConfigId.get() == null) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }

        SessionManager.resetFeatureSession();

        String restartStrategy = PlaywrightManager.config().getRestartStrategy();
        if ("feature".equalsIgnoreCase(restartStrategy)) {
            BrowserContext context = PlaywrightManager.contextThreadLocal.get();
            if (context == null || (context.browser() != null && !context.browser().isConnected())) {
                LoggingConfigUtil.logInfoIfVerbose(logger,
                        "Feature mode: pre-creating Context for feature-level reuse");
            } else {
                LoggingConfigUtil.logInfoIfVerbose(logger,
                        "Feature mode: Context already exists, will be reused across scenarios");
            }
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
