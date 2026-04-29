package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.Geolocation;
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
    
    // 锁对象
    static final Object CONTEXT_LOCK = new Object();
    static final Object PAGE_LOCK = new Object();
    
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
        Boolean customFlag = PlaywrightManager.getCustomContextOptionsFlag().get();
        if (customFlag != null && customFlag) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Applying custom context customOptionsManager...");
            configureCustomContextOptions(contextOptions);
        }

        // 初始化 Context
        BrowserContext context = currentBrowser.newContext(contextOptions);

        // 设置超时
        configureTimeouts(context);

        // 启用 tracing（如果配置了）
        enableTracing(context);

        // 重置标志
        if (customFlag != null && customFlag) {
            PlaywrightManager.getCustomContextOptionsFlag().set(false);
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
        stabilizePage(page);
        LoggingConfigUtil.logInfoIfVerbose(logger, "Page created successfully");
        return page;
    }

    /**
     * 关闭 Context
     */
    static void closeContext(BrowserContext context) {
        synchronized (CONTEXT_LOCK) {
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
    }

    /**
     * 关闭 Page
     */
    static void closePage(Page page) {
        synchronized (PAGE_LOCK) {
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
    }

    /**
     * 配置框架默认 Context 选项
     */
    private static void configureDefaultContextOptions(Browser.NewContextOptions contextOptions) {
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
        CustomOptionsManager customOptionsManager = PlaywrightManager.customOptions();

        // StorageState（session 恢复）
        Path storagePath = customOptionsManager.getStorageStatePath();
        if (storagePath != null && Files.exists(storagePath)) {
            contextOptions.setStorageStatePath(storagePath);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom storageStatePath: {}", storagePath);
        }

        // Locale
        String locale = customOptionsManager.getLocale();
        if (locale != null && !locale.isEmpty()) {
            contextOptions.setLocale(locale);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom locale: {}", locale);
        }

        // Timezone
        String timezoneId = customOptionsManager.getTimezoneId();
        if (timezoneId != null && !timezoneId.isEmpty()) {
            contextOptions.setTimezoneId(timezoneId);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom timezoneId: {}", timezoneId);
        }

        // User Agent
        String userAgent = customOptionsManager.getUserAgent();
        if (userAgent != null && !userAgent.isEmpty()) {
            contextOptions.setUserAgent(userAgent);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom userAgent: {}", userAgent);
        }

        // Permissions
        List<String> permissions = customOptionsManager.getPermissions();
        if (permissions != null && !permissions.isEmpty()) {
            contextOptions.setPermissions(permissions);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom permissions: {}", permissions);
        }

        // Geolocation
        Geolocation geolocation = customOptionsManager.getGeolocation();
        if (geolocation != null) {
            contextOptions.setGeolocation(geolocation.latitude, geolocation.longitude);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom geolocation: ({}, {})", geolocation.latitude, geolocation.longitude);
        }

        // Device Scale Factor
        Integer scaleFactor = customOptionsManager.getDeviceScaleFactor();
        if (scaleFactor != null) {
            contextOptions.setDeviceScaleFactor(scaleFactor / 100.0);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom deviceScaleFactor: {}", scaleFactor / 100.0);
        }

        // Mobile 和 Touch
        Boolean isMobile = customOptionsManager.getIsMobile();
        if (isMobile != null) {
            contextOptions.setIsMobile(isMobile);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom isMobile: {}", isMobile);
        }

        Boolean hasTouch = customOptionsManager.getHasTouch();
        if (hasTouch != null) {
            contextOptions.setHasTouch(hasTouch);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom hasTouch: {}", hasTouch);
        }

        // Color Scheme
        ColorScheme colorScheme = customOptionsManager.getColorScheme();
        if (colorScheme != null) {
            contextOptions.setColorScheme(colorScheme);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom colorScheme: {}", colorScheme);
        }

        // Viewport
        Integer customViewportWidth = customOptionsManager.getViewportWidth();
        Integer customViewportHeight = customOptionsManager.getViewportHeight();
        if (customViewportWidth != null && customViewportHeight != null) {
            contextOptions.setViewportSize(customViewportWidth, customViewportHeight);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom viewportSize: {}x{}", customViewportWidth, customViewportHeight);
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
     * 页面稳定化
     * 委托给 PlaywrightManager 处理（因为稳定化逻辑在那里有更完整的实现）
     */
    private static void stabilizePage(Page page) {
        PlaywrightManager.stabilizePage(page);
    }
}
