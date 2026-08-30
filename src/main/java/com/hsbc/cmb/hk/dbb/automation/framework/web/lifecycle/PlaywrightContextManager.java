package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.RouteRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.Geolocation;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Context 和 Page 管理器 - 负责 Context 和 Page 的创建、配置和关闭
 * <p>
 * 职责：
 * - Context 创建和配置
 * - Page 创建和稳定化
 * - Context 和 Page 的关闭
 */
class PlaywrightContextManager {
    
    private static final Logger logger = LoggerFactory.getLogger(PlaywrightContextManager.class);

    /**
     * 创建新的 BrowserContext
     */
    static BrowserContext createContext() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Creating new BrowserContext...");

        Browser currentBrowser = PlaywrightManager.getBrowser();
        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions();

        // 配置框架默认项
        configureDefaultContextOptions(contextOptions);

        // 条件注入自定义配置
        Boolean customFlag = CustomOptionsManager.customContextOptionsFlag.get();
        if (customFlag != null && customFlag) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Applying custom context options...");
            configureCustomContextOptions(contextOptions);
        }

        // ⭐ 修复问题3：用 try-finally 确保 customContextOptionsFlag 在 newContext() 抛异常（浏览器断开/
        // 参数非法）时也能重置，避免该线程后续重试持续携带已失效的自定义配置而反复失败。
        BrowserContext context;
        try {
            // 初始化 Context
            context = currentBrowser.newContext(contextOptions);

            // ⭐ 监听 window.open() 等产生的新 Page，记录日志供 switchNewPage 调试
            context.onPage(newPage -> {
                LoggingConfigUtil.logInfoIfVerbose(logger,
                        "New page detected via window.open(): url={}", newPage.url());
                newPage.onLoad(pageLoad -> {
                    LoggingConfigUtil.logDebugIfVerbose(logger,
                            "New page loaded: url={}, title={}", newPage.url(), newPage.title());
                });
            });

            // 设置超时
            configureTimeouts(context);

            // 启用 tracing（如果配置了）
            enableTracing(context);
        } finally {
            // 重置标志（成功或失败都执行）
            if (customFlag != null && customFlag) {
                CustomOptionsManager.customContextOptionsFlag.set(false);
                LoggingConfigUtil.logInfoIfVerbose(logger, "Custom context options applied, flag reset to false");
            }
        }

        LoggingConfigUtil.logInfoIfVerbose(logger, "BrowserContext created successfully");
        return context;
    }

    /**
     * 创建新的 Page
     */
    static Page createPage(BrowserContext context) {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Creating new Page...");
        Page page = context.newPage();

        // 注册下载事件监听，自动保存下载文件到配置的下载目录
        String downloadsPath = PlaywrightManager.config().getBrowserDownloadsPath();
        page.onDownload(download -> {
            try {
                Path downloadDir = Paths.get(downloadsPath);
                if (!Files.exists(downloadDir)) {
                    Files.createDirectories(downloadDir);
                }
                String suggestedFilename = download.suggestedFilename();
                Path savePath = downloadDir.resolve(suggestedFilename);
                download.saveAs(savePath);
                LoggingConfigUtil.logInfoIfVerbose(logger,
                        "Download completed: {} -> {}", suggestedFilename, savePath.toAbsolutePath());
            } catch (Exception e) {
                logger.error("[Download] Failed to save file: {}", e.getMessage(), e);
            }
        });

        stabilizePage(page);
        LoggingConfigUtil.logInfoIfVerbose(logger, "Page created successfully");
        return page;
    }

    /**
     * 关闭 Context
     */
    static void closeContext(BrowserContext context) {
        if (context != null) {
            try {
                // ⭐ 先释放路由层资源：停止 MonitorSession 定时器、unroute、清理注册表与防重门控，
                //    避免调度器线程池持有已销毁 context 引用导致内存泄漏 / 对已关闭 context 无效调度。
                //    统一走 RouteLifecycle 生命周期钩子（含关闭前泄漏诊断）。
                try {
                    RouteRegistry.clearContext(context);
                } catch (Exception re) {
                    LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to clear Route resources on context close: {}", re.getMessage());
                }
                // 停止 tracing（以框架配置为准，避免与系统环境变量不一致导致误判）
                if (FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_ENABLED)) {
                    // ⭐ 修复 A-2：tracing().stop() 会写磁盘 trace 文件，无超时且可能长时间阻塞
                    //    （磁盘 IO 卡顿 / 大 trace）。外层 closeContext 在 PlaywrightManager.CONTEXT_LOCK
                    //    同步块内调用，阻塞会拖住所有线程的 context 关闭。
                    //    故改为带超时的异步执行：超时即放弃写 trace，保证 context.close() 不被拖累。
                    try {
                        String tracePath = "target/traces/trace-" + System.currentTimeMillis() + ".zip";
                        java.util.concurrent.Future<Void> traceTask = java.util.concurrent.CompletableFuture.runAsync(() ->
                                context.tracing().stop(new Tracing.StopOptions().setPath(Paths.get(tracePath))));
                        try {
                            traceTask.get(15, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (java.util.concurrent.TimeoutException toe) {
                            traceTask.cancel(true);
                            LoggingConfigUtil.logWarnIfVerbose(logger,
                                    "Tracing stop timed out (15s), skipping trace to avoid blocking context close");
                        } catch (Exception te) {
                            // tracing 未启动或已停止时 stop 会抛异常，忽略，不阻塞关闭流程
                            LoggingConfigUtil.logDebugIfVerbose(logger, "Tracing stop skipped on context close: {}", te.getMessage());
                        }
                    } catch (Exception te) {
                        LoggingConfigUtil.logDebugIfVerbose(logger, "Tracing stop skipped on context close: {}", te.getMessage());
                    }
                }
                // ⭐ 独立的 close try：即使上面任何步骤抛异常，也要保证 context.close() 被执行，
                //    否则已关闭失败会导致 context 资源泄漏。
                //    注意：BrowserContext 无 isClosed() 方法，用 browser 连接状态判断其是否仍活跃。
                if (context.browser() != null && context.browser().isConnected()) {
                    context.close();
                    LoggingConfigUtil.logInfoIfVerbose(logger, "BrowserContext closed");
                }
            } catch (Exception e) {
                logger.error("Failed to close BrowserContext: {}", e.getMessage(), e);
                throw new BrowserException("Failed to close BrowserContext", e);
            }
        }
    }

    /**
     * 关闭 Page
     */
    static void closePage(Page page) {
        if (page != null) {
            try {
                if (!page.isClosed()) {
                    // ⭐ 先释放该 Page 上的路由层资源（停止 MonitorSession 定时器、清理注册表），
                    //    再关闭 Page，避免调度器线程池持有已销毁 page 引用导致内存泄漏。
                    //    统一走 RouteRegistry.clearContext 释放。
                    try {
                        RouteRegistry.clearContext(page);
                    } catch (Exception re) {
                        LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to clear Route resources on page close: {}", re.getMessage());
                    }
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Closing Page...");
                    page.close();
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Page closed");
                }
            } catch (Exception e) {
                logger.error("Failed to close page: {}", e.getMessage(), e);
                throw new BrowserException("Failed to close page", e);
            }
        }
    }

    /**
     * 配置框架默认 Context 选项
     */
    private static void configureDefaultContextOptions(Browser.NewContextOptions contextOptions) {
        // 启用文件下载（Playwright 安全策略默认禁止下载，需显式开启）
        contextOptions.setAcceptDownloads(true);

        contextOptions.setLocale(PlaywrightManager.config().getContextLocale());
        
        String timezoneId = PlaywrightManager.config().getContextTimezone();
        if (timezoneId != null && !timezoneId.isEmpty()) {
            contextOptions.setTimezoneId(timezoneId);
        }

        String userAgent = PlaywrightManager.config().getContextUserAgent();
        if (userAgent != null && !userAgent.isEmpty()) {
            contextOptions.setUserAgent(userAgent);
        }

        String permissionsConfig = PlaywrightManager.config().getContextPermissions();
        if (permissionsConfig != null && !permissionsConfig.isEmpty()) {
            contextOptions.setPermissions(List.of(permissionsConfig.split(",")));
        }

        // 配置代理服务器（直接从统一代理配置读取）
        // 优先级：customProxyEnabled ThreadLocal > playwright.context.proxy.enabled
        Boolean customProxyOverride = PlaywrightManager.customOptions().getProxyEnabled();
        boolean proxyEnabled;
        if (customProxyOverride != null) {
            proxyEnabled = customProxyOverride;
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom proxyEnabled override: {} (from business code)", proxyEnabled);
        } else {
            proxyEnabled = PlaywrightConfigManager.config().getContextProxyEnabled();
        }

        if (proxyEnabled) {
            String proxyUrl = ProxyConfigResolver.getHttpProxyUrlForContext();
            if (proxyUrl != null) {
                contextOptions.setProxy(proxyUrl);
                LoggingConfigUtil.logInfoIfVerbose(logger, "Setting context proxy from unified config");
            } else {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Proxy enabled but no HTTP proxy configured, skipping context proxy");
            }
        }

        // 配置设备缩放因子
        String deviceScaleFactor = PlaywrightManager.config().getDeviceScaleFactor();
        if (deviceScaleFactor == null || deviceScaleFactor.trim().isEmpty()) {
            double systemDpiScaleFactor = PlaywrightManager.config().getSystemDpiScaleFactor();
            deviceScaleFactor = String.valueOf(systemDpiScaleFactor);
        }
        contextOptions.setDeviceScaleFactor(Double.parseDouble(deviceScaleFactor));

        // 设置 viewport（使用逻辑尺寸，Playwright viewport 以 CSS 像素为单位）
        Dimension screenSize = PlaywrightManager.config().getAvailableScreenSize();
        int viewportWidth = (int) screenSize.getWidth();
        int viewportHeight = (int) screenSize.getHeight();

        String browserType = PlaywrightManager.config().getBrowserType();

        // 统一 viewport 策略: viewport = 逻辑屏幕尺寸 - 浏览器 chrome 估算高度
        // stabilizePage 中 window.resizeTo(logicalWidth, logicalHeight) 将窗口外尺寸最大化到屏幕逻辑尺寸
        // 窗口外尺寸 = viewport + titleBar + tabBar + addressBar + ... ≈ viewport + 100px
        // 因此 viewport 应设为逻辑屏幕高度 - chrome估算值，才能填满最大化窗口
        int estimatedChromeHeight = 100;
        viewportHeight = Math.max(viewportHeight - estimatedChromeHeight, 600);
        contextOptions.setViewportSize(viewportWidth, viewportHeight);
        LoggingConfigUtil.logInfoIfVerbose(logger,
            "Context viewport: {}x{} (logical screen {}x{}, adjusted for browser chrome ~{}px, browser={})",
            viewportWidth, viewportHeight, (int) screenSize.getWidth(), (int) screenSize.getHeight(),
            estimatedChromeHeight, browserType);

        contextOptions.setHasTouch(PlaywrightManager.config().hasTouch());
        contextOptions.setIsMobile(PlaywrightManager.config().isMobile());

        String colorScheme = PlaywrightManager.config().getColorScheme();
        contextOptions.setColorScheme(ColorScheme.valueOf(colorScheme.toUpperCase().replace("-", "_")));

        // 配置录屏
        if (PlaywrightManager.config().isRecordVideoEnabled()) {
            String videoDir = PlaywrightManager.config().getRecordVideoDir();
            screenSize = PlaywrightManager.config().getAvailableScreenSize();
            contextOptions.setRecordVideoDir(Paths.get(videoDir));
            contextOptions.setRecordVideoSize((int) screenSize.getWidth(), (int) screenSize.getHeight());
        }
    }

    /**
     * 配置自定义 Context 选项
     */
    private static void configureCustomContextOptions(Browser.NewContextOptions contextOptions) {
        CustomOptionsManager cm = PlaywrightManager.customOptions();

        // StorageState（特殊：需要 Files.exists 检查）
        Path storagePath = cm.getStorageStatePath();
        if (storagePath != null && Files.exists(storagePath)) {
            contextOptions.setStorageStatePath(storagePath);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom storageStatePath: {}", storagePath);
        }

        applyIfNotEmpty(cm.getLocale(), "locale", () -> contextOptions.setLocale(cm.getLocale()));
        applyIfNotEmpty(cm.getTimezoneId(), "timezoneId", () -> contextOptions.setTimezoneId(cm.getTimezoneId()));
        applyIfNotEmpty(cm.getUserAgent(), "userAgent", () -> contextOptions.setUserAgent(cm.getUserAgent()));
        applyIfNotNull(cm.getPermissions(), "permissions", () -> contextOptions.setPermissions(cm.getPermissions()));

        Geolocation geo = cm.getGeolocation();
        applyIfNotNull(geo, "geolocation", () -> contextOptions.setGeolocation(geo.latitude, geo.longitude));

        Integer sf = cm.getDeviceScaleFactor();
        applyIfNotNull(sf, "deviceScaleFactor", () -> contextOptions.setDeviceScaleFactor(sf / 100.0));

        applyIfNotNull(cm.getIsMobile(), "isMobile", () -> contextOptions.setIsMobile(cm.getIsMobile()));
        applyIfNotNull(cm.getHasTouch(), "hasTouch", () -> contextOptions.setHasTouch(cm.getHasTouch()));
        applyIfNotNull(cm.getColorScheme(), "colorScheme", () -> contextOptions.setColorScheme(cm.getColorScheme()));

        // Viewport（需要两个值均非空）
        Integer vw = cm.getViewportWidth();
        Integer vh = cm.getViewportHeight();
        if (vw != null && vh != null) {
            contextOptions.setViewportSize(vw, vh);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom viewportSize: {}x{}", vw, vh);
        }
    }

    /**
     * 仅在值非空时应用自定义选项（适用于非 String 类型）
     */
    private static void applyIfNotNull(Object value, String name, Runnable applier) {
        if (value != null) {
            applier.run();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom {}: {}", name, value);
        }
    }

    /**
     * 仅在值非空且非空字符串时应用（适用于 String 类型）
     */
    private static void applyIfNotEmpty(String value, String name, Runnable applier) {
        if (value != null && !value.isEmpty()) {
            applier.run();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom {}: {}", name, value);
        }
    }

    /**
     * 配置超时
     */
    private static void configureTimeouts(BrowserContext context) {
        context.setDefaultNavigationTimeout(PlaywrightManager.config().getNavigationTimeout());
        context.setDefaultTimeout(PlaywrightManager.config().getPageTimeout());
    }

    /**
     * 启用 Tracing
     */
    private static void enableTracing(BrowserContext context) {
        if (PlaywrightManager.config().isTraceEnabled()) {
            context.tracing().start(new Tracing.StartOptions()
                    .setScreenshots(PlaywrightManager.config().isTraceScreenshots())
                    .setSnapshots(PlaywrightManager.config().isTraceSnapshots())
                    .setSources(PlaywrightManager.config().isTraceSources()));
        }
    }

    /**
     * 页面稳定化：确保页面加载完成并固定窗口/缩放状态
     */
    private static void stabilizePage(Page page) {
        try {
            LoggingConfigUtil.logDebugIfVerbose(logger, "页面稳定化：确保窗口大小正确...");

            int stabilizeWaitTimeout = PlaywrightManager.config().getStabilizeTimeout();
            LoadState loadState = getConfiguredLoadState();
            try {
                page.waitForLoadState(loadState, new Page.WaitForLoadStateOptions().setTimeout(stabilizeWaitTimeout));
            } catch (Exception e) {
                LoggingConfigUtil.logDebugIfVerbose(logger, "页面加载等待超时（LoadState: {}），继续稳定化: {}", loadState, e.getMessage());
            }

            Dimension screenSize = PlaywrightManager.config().getAvailableScreenSize();
            int logicalWidth = (int) screenSize.getWidth();
            int logicalHeight = (int) screenSize.getHeight();

            // 统一使用 window.resizeTo 最大化窗口（所有浏览器）
            // 窗口外尺寸 = viewport + chrome，这里将外尺寸设为逻辑屏幕尺寸
            // context 中已设置 viewport = logicalHeight - chromeEstimate，两者配合正好填满
            //
            // 关键：window.resizeTo 会触发 OS 窗口管理器异步重排位置（居中），
            // moveTo(0,0) 必须通过 setTimeout 延迟执行，等 resize 彻底完成后才移到左上角
            // WebKit 不支持 --window-position launch arg，完全依赖此 JS 定位，延迟尤为关键
            page.evaluate(String.format(
                    "window.resizeTo(%d, %d);"
                    + "setTimeout(function() { window.moveTo(%d, %d); }, 300);",
                    logicalWidth, logicalHeight, 0, 0
            ));
            LoggingConfigUtil.logDebugIfVerbose(logger, "窗口最大化: window.resizeTo({}, {}), moveTo(0,0) delayed 300ms", logicalWidth, logicalHeight);

            page.evaluate(
                    "document.body.style.zoom = '100%'; "
                            + "document.documentElement.style.zoom = '100%'; "
                            + "document.documentElement.style.transform = 'none'; "
                            + "document.documentElement.style.transformOrigin = '0 0';"
            );

            page.evaluate(
                    "window.addEventListener('resize', function(e) { e.stopPropagation(); }, true);"
                            + "document.addEventListener('DOMContentLoaded', function() {"
                            + "    if (window.devicePixelRatio !== 1) { "
                            + "    }"
                            + "});"
            );

            Integer customViewportWidthVal = CustomOptionsManager.customViewportWidth.get();
            Integer customViewportHeightVal = CustomOptionsManager.customViewportHeight.get();

            if (customViewportWidthVal != null && customViewportHeightVal != null) {
                // 用户自定义 viewport：直接应用
                page.setViewportSize(customViewportWidthVal, customViewportHeightVal);
                LoggingConfigUtil.logDebugIfVerbose(logger, "Custom viewport applied: {}x{}",
                        customViewportWidthVal, customViewportHeightVal);
            } else {
                // context 已统一设置 viewport = logical - chromeEstimate
                // window.resizeTo + context viewport 配合 → 窗口填满屏幕
                LoggingConfigUtil.logDebugIfVerbose(logger, "No custom viewport, keeping viewport from context (window-adapted)");
            }

            LoggingConfigUtil.logDebugIfVerbose(logger, "页面稳定化完成");
        } catch (Exception e) {
            logger.warn("页面稳定化失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取配置的页面加载状态
     * 配置属性: playwright.page.load.state
     * 可选值: LOAD, DOMCONTENTLOADED, NETWORKIDLE
     * 默认值: DOMCONTENTLOADED
     */
    private static LoadState getConfiguredLoadState() {
        String loadStateConfig = PlaywrightManager.config().getPageLoadState();
        try {
            return LoadState.valueOf(loadStateConfig.toUpperCase());
        } catch (IllegalArgumentException e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Invalid LoadState configuration: {}, using default: DOMCONTENTLOADED", loadStateConfig);
            return LoadState.DOMCONTENTLOADED;
        }
    }
}
