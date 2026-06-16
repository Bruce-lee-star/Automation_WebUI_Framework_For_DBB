package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.Geolocation;
import com.microsoft.playwright.options.LoadState;
import net.thucydides.model.environment.SystemEnvironmentVariables;
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

        // 初始化 Context
        BrowserContext context = currentBrowser.newContext(contextOptions);

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

        // 重置标志
        if (customFlag != null && customFlag) {
            CustomOptionsManager.customContextOptionsFlag.set(false);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Custom context options applied, flag reset to false");
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
                // 停止 tracing
                if (SystemEnvironmentVariables.currentEnvironmentVariables().getPropertyAsBoolean("playwright.context.trace.enabled", false)) {
                    String tracePath = "target/traces/trace-" + System.currentTimeMillis() + ".zip";
                    context.tracing().stop(new Tracing.StopOptions().setPath(Paths.get(tracePath)));
                }
                context.close();
                LoggingConfigUtil.logInfoIfVerbose(logger, "BrowserContext closed");
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

        // 配置代理服务器（ThreadLocal 自定义覆盖优先于配置文件）
        // 优先级：customProxyEnabled ThreadLocal > playwright.context.proxy.enabled 配置
        Boolean customProxyOverride = PlaywrightManager.customOptions().getProxyEnabled();
        boolean proxyEnabled;
        if (customProxyOverride != null) {
            proxyEnabled = customProxyOverride;
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom proxyEnabled override: {} (from business code)", proxyEnabled);
        } else {
            proxyEnabled = PlaywrightManager.config().getContextProxyEnabled();
        }
        String proxyConfig = PlaywrightManager.config().getContextProxy();
        if (proxyEnabled && proxyConfig != null && !proxyConfig.isEmpty()) {
            String proxyUsername = PlaywrightManager.config().getContextProxyUsername();
            String proxyPassword = PlaywrightManager.config().getContextProxyPassword();
            // 如果提供了用户名/密码，构造带认证的代理 URL
            if (proxyUsername != null && !proxyUsername.isEmpty()
                    && proxyPassword != null && !proxyPassword.isEmpty()) {
                // URL 格式: http://user:pass@host:port
                String proxyWithAuth = proxyConfig.replaceFirst("^(https?://)", "$1" + proxyUsername + ":" + proxyPassword + "@");
                contextOptions.setProxy(proxyWithAuth);
                LoggingConfigUtil.logInfoIfVerbose(logger, "Setting context proxy with auth: {}@***", proxyUsername);
            } else {
                contextOptions.setProxy(proxyConfig);
                LoggingConfigUtil.logInfoIfVerbose(logger, "Setting context proxy: {}", proxyConfig);
            }
        } else if (proxyEnabled) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Proxy enabled but no proxy URL configured (playwright.context.proxy is empty), skipping proxy setup");
        }

        // 配置设备缩放因子
        String deviceScaleFactor = PlaywrightManager.config().getDeviceScaleFactor();
        if (deviceScaleFactor == null || deviceScaleFactor.trim().isEmpty()) {
            double systemDpiScaleFactor = PlaywrightManager.config().getSystemDpiScaleFactor();
            deviceScaleFactor = String.valueOf(systemDpiScaleFactor);
        }
        contextOptions.setDeviceScaleFactor(Double.parseDouble(deviceScaleFactor));

        Dimension screenSize = PlaywrightManager.config().getAvailableScreenSize();
        int viewportWidth = (int) screenSize.getWidth();
        int viewportHeight = (int) screenSize.getHeight();
        contextOptions.setViewportSize(viewportWidth, viewportHeight);
        LoggingConfigUtil.logInfoIfVerbose(logger, "Setting context viewport to screen size: {}x{}", viewportWidth, viewportHeight);

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

            String maximizeArgs = PlaywrightManager.config().getWindowMaximizeArgs();
            boolean hasStartMaximized = maximizeArgs.contains("--start-maximized");

            Dimension screenSize = PlaywrightManager.config().getAvailableScreenSize();
            int logicalWidth = (int) screenSize.getWidth();
            int logicalHeight = (int) screenSize.getHeight();

            if (!hasStartMaximized) {
                page.evaluate(String.format(
                        "window.resizeTo(%d, %d); window.moveTo(0, 0);",
                        logicalWidth, logicalHeight
                ));
                LoggingConfigUtil.logDebugIfVerbose(logger, "JavaScript窗口大小设置: {}x{}", logicalWidth, logicalHeight);
            } else {
                LoggingConfigUtil.logDebugIfVerbose(logger, "使用 --start-maximized，跳过 JavaScript 窗口大小设置");
            }

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
                LoggingConfigUtil.logDebugIfVerbose(logger, "Custom viewport detected ({}x{}), skipping viewport size override",
                        customViewportWidthVal, customViewportHeightVal);
            } else {
                page.setViewportSize(logicalWidth, logicalHeight);
                LoggingConfigUtil.logDebugIfVerbose(logger, "No custom viewport, setting to logical screen size: {}x{}",
                        logicalWidth, logicalHeight);
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
