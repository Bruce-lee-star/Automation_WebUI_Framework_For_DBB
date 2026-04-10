package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.AutoBrowserProcessor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.BrowserOverrideManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.InitializationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.strategy.ScreenshotStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.TimeoutConfig;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.Geolocation;
import com.microsoft.playwright.options.LoadState;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import net.thucydides.model.environment.SystemEnvironmentVariables;
import net.thucydides.model.steps.ExecutedStepDescription;
import net.thucydides.model.util.EnvironmentVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 企业级 Playwright Manager - 管理 Playwright 实例、Browser、Context 和 Page
 * 特性：
 * - 支持 Serenity BDD 集成
 * - 线程安全
 * - 灵活的浏览器生命周期管理
 * - 支持不同级别的浏览器启动策略
 * - 避免静态初始化问题
 */
public class PlaywrightManager {

    // ==================== 静态常量 ====================

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightManager.class);

    // Playwright 浏览器缓存路径（项目根目录下的 .playwright 目录）
    private static final String DEFAULT_PLAYWRIGHT_BROWSER_PATH = ".playwright/browser";
    private static final String DEFAULT_PLAYWRIGHT_DRIVER_PATH = ".playwright/driver";

    // ==================== 静态变量 ====================
    private static final Boolean SKIP_DOWNLOAD_BROWSER = FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD);
    // 线程安全的实例存储
    private static final ConcurrentMap<String, Playwright> playwrightInstances = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Browser> browserInstances = new ConcurrentHashMap<>();
    private static final ThreadLocal<BrowserContext> contextThreadLocal = new ThreadLocal<>();
    private static final ThreadLocal<Page> pageThreadLocal = new ThreadLocal<>();
    private static final List<Process> downloadProcesses = new ArrayList<>();
    
    // 线程安全锁：保护共享资源（Browser 实例）
    private static final Object BROWSER_LOCK = new Object();
    
    // Context/Page 细粒度锁：保护 Context 和 Page 创建/销毁
    private static final Object CONTEXT_LOCK = new Object();
    private static final Object PAGE_LOCK = new Object();

    // ==================== 自定义 Context 选项（用户自定义优先于框架配置） ====================
    private static final ThreadLocal<Boolean> customContextOptionsFlag = new ThreadLocal<>();
    private static final ThreadLocal<Path> customStorageStatePath = new ThreadLocal<>();
    private static final ThreadLocal<String> customLocale = new ThreadLocal<>();
    private static final ThreadLocal<String> customTimezoneId = new ThreadLocal<>();
    private static final ThreadLocal<String> customUserAgent = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> customPermissions = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> customIsMobile = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> customHasTouch = new ThreadLocal<>();
    private static final ThreadLocal<ColorScheme> customColorScheme = new ThreadLocal<>();
    private static final ThreadLocal<Geolocation> customGeolocation = new ThreadLocal<>();
    private static final ThreadLocal<Integer> customDeviceScaleFactor = new ThreadLocal<>();
    private static final ThreadLocal<Integer> customViewportWidth = new ThreadLocal<>();
    private static final ThreadLocal<Integer> customViewportHeight = new ThreadLocal<>();
    // 配置标识
    private static String currentConfigId;

    // 框架状态引用
    private static final FrameworkState frameworkState = FrameworkState.getInstance();

    // ==================== 静态初始化块 ====================

    static {
        // 委托给 PlaywrightInitializer 处理初始化逻辑
        PlaywrightInitializer.initializePlaywrightPaths();
        PlaywrightInitializer.cleanupPlaywrightTempDirs();
        // 浏览器下载延迟到实际需要时，不在静态初始化阶段下载
    }


    // ==================== 初始化相关方法（委托给 PlaywrightInitializer） ====================
    
    /**
     * 确保浏览器已安装
     * 委托给 PlaywrightInitializer
     */
    private static void ensureBrowserInstalledForType(String browserType) {
        PlaywrightInitializer.ensureBrowsersInstalled();
    }

    // ==================== 浏览器安装方法 ====================



    // ==================== 工具方法 ====================


    /**
     * 生成 SHA-256 哈希值，用于创建类似 Serenity HTML 文件的截图文件名
     *
     * @param input 输入字符串
     * @return SHA-256 哈希值的十六进制表示
     */
    private static String generateHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            StringBuilder hexString = new StringBuilder();

            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to generate hash, using fallback method", e);
            return Long.toHexString(System.currentTimeMillis()) +
                    Long.toHexString(System.nanoTime()) +
                    Long.toHexString(Thread.currentThread().getId());
        }
    }

    /**
     * 获取逻辑屏幕分辨率（用于 viewport 和窗口大小）
     */
    private static Dimension getAvailableScreenSize() {
        try {
            GraphicsConfiguration gc = GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration();

            Rectangle bounds = gc.getBounds();
            int logicalWidth = bounds.width;
            int logicalHeight = bounds.height;

            LoggingConfigUtil.logInfoIfVerbose(logger, "Using logical screen size: {}x{}", logicalWidth, logicalHeight);

            return new Dimension(logicalWidth, logicalHeight);
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to get screen size, using default: {}", e.getMessage());
            return new Dimension(1920, 1080);
        }
    }

    /**
     * 根据截图策略检查是否应该截图
     */
    private static boolean shouldTakeScreenshotForStep(ExecutedStepDescription step, TestResult result) {
        if (step == null) {
            return false;
        }

        try {
            EnvironmentVariables environmentVariables = SystemEnvironmentVariables.currentEnvironmentVariables();
            ScreenshotStrategy strategy = ScreenshotStrategy.from(environmentVariables);
            return strategy.shouldTakeScreenshotFor(step);
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to determine screenshot strategy, taking screenshot anyway", e);
            return true;
        }
    }

    /**
     * 根据截图策略检查是否应该截图（针对测试结果）
     */
    private static boolean shouldTakeScreenshotForTestResult(TestResult result) {
        if (result == null) {
            return false;
        }

        try {
            EnvironmentVariables environmentVariables = SystemEnvironmentVariables.currentEnvironmentVariables();
            ScreenshotStrategy strategy = ScreenshotStrategy.from(environmentVariables);
            return strategy.shouldTakeScreenshotFor(result);
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to determine screenshot strategy, taking screenshot anyway", e);
            return true;
        }
    }

    /**
     * 根据截图策略检查是否应该截图（针对测试结果）
     */
    private static boolean shouldTakeScreenshotForTestOutcome(TestOutcome testOutcome) {
        if (testOutcome == null) {
            return false;
        }

        try {
            EnvironmentVariables environmentVariables = SystemEnvironmentVariables.currentEnvironmentVariables();
            ScreenshotStrategy strategy = ScreenshotStrategy.from(environmentVariables);
            return strategy.shouldTakeScreenshotFor(testOutcome);
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to determine screenshot strategy, taking screenshot anyway", e);
            return true;
        }
    }

    /**
     * 获取配置的页面加载状态（可配置）
     * 配置属性: playwright.page.load.state
     * 可选值: LOAD, DOMCONTENTLOADED, NETWORKIDLE
     * 默认值: DOMCONTENTLOADED
     */
    private static LoadState getConfiguredLoadState() {
        String loadStateConfig = FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_PAGE_LOAD_STATE);
        try {
            return LoadState.valueOf(loadStateConfig.toUpperCase());
        } catch (IllegalArgumentException e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Invalid LoadState configuration: {}, using default: DOMCONTENTLOADED", loadStateConfig);
            return LoadState.DOMCONTENTLOADED;
        }
    }


    // ==================== 生命周期管理方法 ====================

    /**
     * 初始化整个 Playwright 环境
     * 由 FrameworkCore 调用，用于测试套件开始时
     * 
     * 注意：此方法只初始化 Playwright 实例，不启动浏览器
     * 浏览器会在首次调用 getBrowser() 或 getPage() 时启动
     * 这样可以支持 @AutoBrowser 动态浏览器切换，避免启动多余的浏览器实例
     */
    public static synchronized void initialize() {
        if (frameworkState.isInitialized() && currentConfigId != null) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Playwright environment already initialized with config: {}", currentConfigId);
            return;
        }

        String configId = generateConfigId();
        LoggingConfigUtil.logInfoIfVerbose(logger, "Initializing Playwright environment with config: {}", configId);
        initializePlaywright(configId);
        // 不在此处初始化浏览器，延迟到首次访问时启动
        // 这样可以支持 @AutoBrowser 动态浏览器切换
        // initializeBrowser(configId);
        currentConfigId = configId;

        LoggingConfigUtil.logInfoIfVerbose(logger, " Playwright environment initialized successfully (browser will be launched on first access)");
    }

    /**
     * 生成配置ID
     * 格式：{browserType}_{headless/headed}_{channel}
     * 
     * 注意：Firefox 和 WebKit 不支持 channel，会显示为空
     */
    private static String generateConfigId() {
        String browserType = getBrowserType();
        String headlessMode = isHeadless() ? "headless" : "headed";
        String channel = getBrowserChannel();
        
        // Firefox 和 WebKit 不支持 channel，忽略配置
        if ("firefox".equalsIgnoreCase(browserType) || "webkit".equalsIgnoreCase(browserType)) {
            return String.format("%s_%s", browserType, headlessMode);
        }
        
        // Chromium 系列浏览器包含 channel 信息
        if (channel != null && !channel.trim().isEmpty()) {
            return String.format("%s_%s_%s", browserType, headlessMode, channel);
        }
        
        // 无 channel 的 Chromium
        return String.format("%s_%s", browserType, headlessMode);
    }

    /**
     * 初始化 Playwright 实例
     */
    private static void initializePlaywright(String configId) {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Initializing Playwright for config: {}", configId);

        try {
            Playwright.CreateOptions createOptions = getCreateOptions();
            Playwright playwright = Playwright.create(createOptions);
            playwrightInstances.put(configId, playwright);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Playwright initialized successfully for config: {}", configId);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to initialize Playwright for config: {}", configId, e);
            // 清理已创建的实例（如果有）
            if (playwrightInstances.containsKey(configId)) {
                playwrightInstances.remove(configId);
            }
            throw new InitializationException("Failed to initialize Playwright for config: " + configId, e);
        }
    }

    private static Playwright.CreateOptions getCreateOptions() {
        Playwright.CreateOptions options = new Playwright.CreateOptions();
        Map<String, String> env = new HashMap<>();

        // 设置浏览器缓存路径
        String browserPath = System.getProperty("PLAYWRIGHT_BROWSERS_PATH");
        if (browserPath != null && !browserPath.trim().isEmpty()) {
            env.put("PLAYWRIGHT_BROWSERS_PATH", browserPath);
            LoggingConfigUtil.logDebugIfVerbose(logger, "Playwright browsers path: {}", browserPath);
        }

        // 关键：总是跳过自动下载，因为我们使用 ensureBrowserInstalledForType() 来控制下载
        // 这样可以避免 Playwright 自动下载所有浏览器
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        LoggingConfigUtil.logInfoIfVerbose(logger, "Playwright instance created with browser download disabled (managed separately)");
        
        if (!env.isEmpty()) {
            options.setEnv(env);
        }
        
        return options;
    }

    /**
     * 智能判断是否应该跳过浏览器下载
     * 
     * 规则：
     * - Firefox: false（必须下载 Playwright 版本）
     * - WebKit: false（必须下载 Playwright 版本）
     * - Chrome: 如果设置了 executablePath 或 channel="chrome" 则 true，否则 false
     * - Edge: 如果设置了 executablePath 或 channel="msedge" 则 true，否则 false
     * - Chromium 无 channel: false（需要下载）
     */
    private static boolean shouldSkipBrowserDownload() {
        // 首先检查用户是否手动配置了跳过下载
        boolean userConfig = FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD);
        
        String browserType = getBrowserType();
        String channel = getBrowserChannel();
        
        switch (browserType.toLowerCase()) {
            case "firefox":
            case "webkit":
                // Firefox 和 WebKit 必须使用 Playwright 版本，不能跳过下载
                if (userConfig) {
                    logger.warn("Cannot skip download for {} - Playwright version is required. Ignoring playwright.skip.browser.download=true", browserType);
                }
                return false;
                
            case "chromium":
                if ("chrome".equalsIgnoreCase(channel)) {
                    // Chrome: 如果设置了 executablePath 或用户配置跳过，则跳过
                    String chromePath = FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_CHROME_EXECUTABLE_PATH);
                    boolean hasLocalChrome = chromePath != null && !chromePath.trim().isEmpty();
                    return hasLocalChrome || userConfig;
                } else if ("msedge".equalsIgnoreCase(channel) || "edge".equalsIgnoreCase(channel)) {
                    // Edge: 如果设置了 executablePath 或用户配置跳过，则跳过
                    String edgePath = FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_EDGE_EXECUTABLE_PATH);
                    boolean hasLocalEdge = edgePath != null && !edgePath.trim().isEmpty();
                    return hasLocalEdge || userConfig;
                } else {
                    // Chromium 无 channel: 需要下载 Playwright 版本
                    return false;
                }
                
            default:
                return userConfig;
        }
    }

    /**
     * 初始化 Browser 实例
     */
    private static synchronized void initializeBrowser(String configId) {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Initializing Browser for config: {}", configId);

        // 双重检查：如果已经有连接的浏览器实例，直接返回
        Browser existingBrowser = browserInstances.get(configId);
        if (existingBrowser != null && existingBrowser.isConnected()) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Browser already initialized and connected for config: {}", configId);
            return;
        }

        // 关闭现有浏览器实例（如果存在但未连接）
        if (existingBrowser != null) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Closing existing browser instance for config: {}", configId);
            try {
                existingBrowser.close();
                LoggingConfigUtil.logInfoIfVerbose(logger, "Existing browser closed successfully");
            } catch (Exception e) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to close existing browser, continuing with new initialization", e);
            }
        }

        // 获取浏览器类型（这一步很关键，必须在初始化 Playwright 之前）
        String browserType = getBrowserType();
        
        // 确保所需的浏览器已安装（延迟下载）
        // 这一步必须在 initializePlaywright() 之前，否则 Playwright 会自动下载所有浏览器
        if (!shouldSkipBrowserDownload()) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "[Browser Init] Checking if {} browser is installed...", browserType);
            ensureBrowserInstalledForType(browserType);
        }

        // 初始化 Playwright 实例（浏览器已确保安装）
        if (playwrightInstances.containsKey(configId)) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Playwright instance already exists for config: {}, skipping initialization", configId);
        } else {
            initializePlaywright(configId);
        }

        Playwright playwright = playwrightInstances.get(configId);
        if (playwright == null) {
            throw new InitializationException("Playwright instance is null after initialization for config: " + configId);
        }

        // 获取浏览器配置
        boolean headless = isHeadless();
        int slowMo = getBrowserSlowMo();
        int timeout = getBrowserTimeout();

        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setSlowMo(slowMo)
                .setTimeout(timeout);

        // 配置窗口大小和启动参数
        configureBrowserLaunchOptions(launchOptions);

        // 设置下载路径
        String downloadsPath = getBrowserDownloadsPath();
        launchOptions.setDownloadsPath(Paths.get(downloadsPath));

        try {
            // 启动浏览器
            Browser browser = setupBrowser(playwright, browserType, launchOptions);
            browserInstances.put(configId, browser);

            LoggingConfigUtil.logInfoIfVerbose(logger, "Browser initialized successfully: {} for config: {}", browserType, configId);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to initialize Browser for config: {}", configId, e);

            // 清理已创建的实例（如果有）
            if (browserInstances.containsKey(configId)) {
                browserInstances.remove(configId);
            }

            throw new BrowserException("Failed to initialize Browser for config: " + configId, e);
        }
    }

    /**
     * 配置浏览器启动选项
     */
    private static void configureBrowserLaunchOptions(BrowserType.LaunchOptions launchOptions) {
        boolean maximizeWindow = PlaywrightConfigManager.isWindowMaximize();
        String maximizeArgs = PlaywrightConfigManager.getWindowMaximizeArgs();
        boolean hasStartMaximized = maximizeArgs.contains("--start-maximized");

        // 获取逻辑屏幕尺寸
        Dimension screenSize = getAvailableScreenSize();
        int screenWidth = (int) screenSize.getWidth();
        int screenHeight = (int) screenSize.getHeight();

        // 获取浏览器类型
        String browserType = getBrowserType();
        boolean isChromium = PlaywrightConfigManager.isChromiumBased(browserType);

        // 构建启动参数
        List<String> args = new ArrayList<>();

        // 添加用户配置的浏览器启动参数（已根据浏览器类型自动选择）
        String browserArgs = getBrowserArgs();
        if (browserArgs != null && !browserArgs.trim().isEmpty()) {
            String[] argsArray = browserArgs.split(",");
            for (String arg : argsArray) {
                if (!arg.trim().isEmpty() && !args.contains(arg.trim())) {
                    args.add(arg.trim());
                }
            }
        }

        // 检查是否启用窗口最大化但不包含 --start-maximized
        if (maximizeWindow && !hasStartMaximized) {
            if (isChromium) {
                // Chromium 系列浏览器：添加窗口位置和大小参数
                args.add("--window-position=0,0");
                args.add("--window-size=" + screenWidth + "," + screenHeight);
                LoggingConfigUtil.logInfoIfVerbose(logger, "Window maximization enabled for Chromium, setting window size to: {}x{}", screenWidth, screenHeight);
            } else {
                // Firefox/WebKit：通过 BrowserContext viewport 实现最大化
                LoggingConfigUtil.logInfoIfVerbose(logger, 
                    "Window maximization for {} will be handled via viewport in BrowserContext", browserType);
            }
        }

        if (!args.isEmpty()) {
            launchOptions.setArgs(args);
            logger.info("Browser args: {}", args);
        }

        // 设置浏览器 channel（仅适用于 Chromium 系列浏览器）
        String channel = getBrowserChannel();
        if (channel != null && !channel.isEmpty() && isChromium) {
            launchOptions.setChannel(channel);
            logger.info("Browser channel: {}", channel);
        } else if (channel != null && !channel.isEmpty() && !isChromium) {
            LoggingConfigUtil.logDebugIfVerbose(logger, 
                "Ignoring browser channel '{}' for browser type '{}' (channel only applies to Chromium-based browsers)", 
                channel, browserType);
        }

        // 设置浏览器可执行文件路径（用于启动本地安装的浏览器）
        String executablePath = getBrowserExecutablePath();
        if (executablePath != null && !executablePath.trim().isEmpty()) {
            launchOptions.setExecutablePath(Paths.get(executablePath.trim()));
            logger.info("Browser executable path: {}", executablePath);  // 保留浏览器路径日志，这很重要
        }
    }

    /**
     * 根据配置选择浏览器类型
     */
    private static Browser setupBrowser(Playwright playwright, String browserType, BrowserType.LaunchOptions
            launchOptions) {
        if (playwright == null) {
            throw new IllegalArgumentException("Playwright instance cannot be null");
        }

        try {
            // 检查是否启用 BrowserStack
            if (com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStackManager.isBrowserStackEnabled()) {
                logger.info("Using BrowserStack for browser: {}", browserType);
                return setupBrowserStackBrowser(playwright, browserType);
            }

            // 本地浏览器
            switch (browserType.toLowerCase()) {
                case "chromium":
                    return playwright.chromium().launch(launchOptions);
                case "firefox":
                    return playwright.firefox().launch(launchOptions);
                case "webkit":
                    return playwright.webkit().launch(launchOptions);
                default:
                    throw new IllegalArgumentException("Unsupported browser type: " + browserType);
            }
        } catch (Exception e) {
            logger.error("Failed to launch browser {} with options: {}", browserType, launchOptions, e);

            // 提供更详细的错误信息
            if (e instanceof TimeoutError) {
                logger.error("Browser launch timed out. Consider increasing timeout or checking browser installation.");
            }

            throw new BrowserException("Failed to launch browser " + browserType, e);
        }
    }

    /**
     * 设置 BrowserStack 浏览器连接
     */
    private static Browser setupBrowserStackBrowser(Playwright playwright, String browserType) {
        try {
            // 获取 BrowserStack 连接 URL
            String browserStackUrl = com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStackManager.getBrowserStackUrl();
            
            // 获取 BrowserStack 能力配置
            java.util.Map<String, String> capabilities = 
                com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStackManager.getBrowserStackCapabilities(browserType);
            
            logger.info("Connecting to BrowserStack: {}", browserStackUrl.replaceAll(":([^@]+)@", ":****@"));
            logger.info("BrowserStack capabilities: {}", capabilities);
            
            // 连接到 BrowserStack
            BrowserType.ConnectOptions connectOptions = 
                com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStackManager.getConnectOptions(browserType);
            
            switch (browserType.toLowerCase()) {
                case "chromium":
                    return playwright.chromium().connect(browserStackUrl, connectOptions);
                case "firefox":
                    return playwright.firefox().connect(browserStackUrl, connectOptions);
                case "webkit":
                    return playwright.webkit().connect(browserStackUrl, connectOptions);
                default:
                    throw new IllegalArgumentException("Unsupported browser type for BrowserStack: " + browserType);
            }
        } catch (Exception e) {
            logger.error("Failed to connect to BrowserStack for browser: {}", browserType, e);
            throw new BrowserException("Failed to connect to BrowserStack", e);
        }
    }


    // ==================== 实例访问方法 ====================

    /**
     * 获取当前配置ID
     */
    private static String getCurrentConfigId() {
        if (currentConfigId == null) {
            currentConfigId = generateConfigId();
        }
        return currentConfigId;
    }

    /**
     * 获取 Playwright 实例
     */
    public static Playwright getPlaywright() {
        String configId = getCurrentConfigId();
        if (configId == null) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }
        return playwrightInstances.get(configId);
    }

    /**
     * 获取 Browser 实例（支持动态浏览器切换）
     *
     * 新特性：自动检测浏览器类型，不依赖Cucumber hooks
     * 在首次访问时自动从scenario标签中提取浏览器类型并切换
     */
    public static Browser getBrowser() {
        // 获取当前的配置ID
        String currentConfig = getCurrentConfigId();
        if (currentConfig == null) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }

        // 获取期望的浏览器类型（可能来自 @AutoBrowser 标签）
        String desiredBrowserType = getBrowserType();
        
        // 检查当前浏览器实例是否存在
        Browser currentBrowser = browserInstances.get(currentConfig);
        
        // 如果浏览器还不存在（首次启动），直接使用期望的浏览器类型初始化
        if (currentBrowser == null || !currentBrowser.isConnected()) {
            logger.info("[getBrowser] Browser not initialized yet, initializing with desired type: {}", desiredBrowserType);
            
            // 如果 currentConfig 中的浏览器类型与期望类型不同，更新 configId
            String[] configParts = currentConfig.split("_");
            String configBrowserType = configParts.length > 0 ? configParts[0] : "chromium";
            
            if (!configBrowserType.equalsIgnoreCase(desiredBrowserType)) {
                // 生成新的 configId（使用期望的浏览器类型）
                String newConfigId = generateConfigId();
                logger.info("[getBrowser] Updating configId from {} to {} for browser type: {}", 
                    currentConfig, newConfigId, desiredBrowserType);
                currentConfigId = newConfigId;
                currentConfig = newConfigId;
            }
            
            // 初始化浏览器
            synchronized (PlaywrightManager.class) {
                initializeBrowser(currentConfig);
            }
            
            return browserInstances.get(currentConfig);
        }
        
        // 浏览器已存在，检查是否需要切换
        String[] configParts = currentConfig.split("_");
        String currentBrowserType = configParts.length > 0 ? configParts[0] : "chromium";
        
        if (!currentBrowserType.equalsIgnoreCase(desiredBrowserType)) {
            logger.info("[getBrowser] Browser type changed: {} -> {}", currentBrowserType, desiredBrowserType);
            logger.info("[getBrowser] Switching browser...");

            // 关闭旧浏览器的 Context 和 Page
            closePage();
            closeContext();

            // 关闭旧浏览器
            Browser oldBrowser = browserInstances.get(currentConfig);
            if (oldBrowser != null && oldBrowser.isConnected()) {
                logger.info("[getBrowser] Closing old browser: {}", currentBrowserType);
                oldBrowser.close();
                browserInstances.remove(currentConfig);
            }

            // 生成新的 configId
            String newConfigId = generateConfigId();
            logger.info("[getBrowser] New configId: {}", newConfigId);

            // 更新 currentConfigId
            currentConfigId = newConfigId;

            // 初始化新浏览器
            synchronized (PlaywrightManager.class) {
                initializeBrowser(newConfigId);
            }

            return browserInstances.get(newConfigId);
        }

        // 浏览器类型没有变化，返回现有浏览器
        logger.info("[getBrowser] Using existing browser with configId: {}", currentConfig);
        return currentBrowser;
    }

    /**
     * 创建并获取 BrowserContext（线程安全）
     * <p>
     * 支持延迟重建机制：当检测到自定义配置时,自动重建Context
     */
    public static BrowserContext getContext() {
        if (!frameworkState.isInitialized()) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }

        BrowserContext context = contextThreadLocal.get();
        
        // 检测是否需要重建Context（因为设置了自定义配置）
        Boolean customFlag = customContextOptionsFlag.get();
        if (context != null && customFlag != null && customFlag) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Custom context options detected, recreating context to apply them...");
            recreateContextIfCustomConfigNeeded();
            context = null;
        }
        
        // 【关键】线程安全：使用锁保护 Context 创建
        synchronized (CONTEXT_LOCK) {
            if (context == null || (context.browser() != null && !context.browser().isConnected())) {
                context = createContext();
                contextThreadLocal.set(context);
            }
        }
        return context;
    }

    /**
     * 调度 Context 重建（延迟重建机制）
     * <p>
     * 当设置自定义配置时调用此方法，标记需要重建 Context
     * 实际重建会在下次 getContext() 或 getPage() 时执行
     * 这样可以支持多次设置自定义配置只触发一次Context重建
     */
    private static void scheduleContextRebuild() {
        // 清空现有的 Page，确保下次操作会触发重建
        Page existingPage = pageThreadLocal.get();
        if (existingPage != null && !existingPage.isClosed()) {
            try {
                LoggingConfigUtil.logInfoIfVerbose(logger, "Clearing existing page to force context rebuild");
                existingPage.close();
            } catch (Exception e) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to clear existing page: {}", e.getMessage());
            }
        }
        pageThreadLocal.remove();
        
        // customContextOptionsFlag 已经在各个 setCustom*() 方法中被设置为 true
        // 不需要在这里设置，因为调用此方法前已经设置过了
    }


    /**
     * 设置自定义 Context 选项标志
     * <p>
     * 注意：通常不需要手动调用此方法
     * - 调用任何 setCustom*() 方法（如 setCustomLocale(), setStorageStatePath() 等）会自动设置此标志为 true
     * - 此方法主要用于显式禁用自定义配置（设置为 false）或内部框架使用
     *
     * @param contextOptionsFlag 是否启用自定义配置（true: 启用，false: 禁用）
     */
    public static void setCustomContextOptionsFlag(Boolean contextOptionsFlag){
        customContextOptionsFlag.set(contextOptionsFlag);
    }

    /**
     * 设置自定义 StorageState 路径（用于 session 恢复）
     * 自定义配置优先于框架默认配置
     * <p>
     * 调用此方法会自动启用自定义配置模式（setCustomContextOptionsFlag(true)）
     * 如果 Context 已存在，会关闭它以便应用新配置（确保 session 恢复生效）
     * <p>
     * 注意：此方法是特殊处理，因为 session 恢复需要在 Context 创建时应用 storageState
     *
     * @param storageStatePath StorageState 文件路径
     */
    public static void setStorageStatePath(Path storageStatePath) {
        customStorageStatePath.set(storageStatePath);
        customContextOptionsFlag.set(true); // 自动启用自定义配置
        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom storageStatePath set: {} (custom context options auto-enabled)", storageStatePath);

        // 【关键】如果 Context 已存在，需要重建它以应用 storageState
        // 使用延迟重建机制：设置标记，在下次 getContext() 或 getPage() 时重建
        // 这样可以支持多次设置自定义配置只触发一次Context重建
        scheduleContextRebuild();
    }

    /**
     * 如果 Context 已存在且设置了自定义配置，重建它
     * <p>
     * 此方法用于在需要应用自定义配置时关闭现有Context
     * 会在以下情况调用：
     * - getContext() 检测到自定义配置时
     * - 确保所有自定义配置（包括 storageState）都能正确应用
     */
    private static void recreateContextIfCustomConfigNeeded() {
        BrowserContext existingContext = contextThreadLocal.get();
        if (existingContext != null) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Context already exists, closing it to apply custom configurations...");
            try {
                // 关闭 Page
                Page existingPage = pageThreadLocal.get();
                if (existingPage != null && !existingPage.isClosed()) {
                    existingPage.close();
                }
                pageThreadLocal.remove();
                
                // 关闭 Context（只有浏览器还连接着才关闭）
                if (existingContext.browser() != null && existingContext.browser().isConnected()) {
                    existingContext.close();
                }
            } catch (Exception e) {
                logger.warn("Failed to close existing context: {}", e.getMessage());
            } finally {
                contextThreadLocal.remove();
            }
            LoggingConfigUtil.logInfoIfVerbose(logger, "Context closed, will create new one with custom configurations on next access");
        }
    }

    /**
     * 设置自定义 Locale
     * 自定义配置优先于框架默认配置
     * <p>
     * 调用此方法会自动启用自定义配置模式（setCustomContextOptionsFlag(true)）
     * 配置会在下一次创建 Context 时生效
     *
     * @param locale Locale 字符串（如 "zh-CN", "en-US"）
     */
    public static void setCustomLocale(String locale) {
        customLocale.set(locale);
        customContextOptionsFlag.set(true); // 自动启用自定义配置
        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom locale set: {} (custom context options auto-enabled)", locale);
        scheduleContextRebuild();
    }

    /**
     * 设置自定义 Timezone
     * 自定义配置优先于框架默认配置
     * <p>
     * 调用此方法会自动启用自定义配置模式（setCustomContextOptionsFlag(true)）
     * 配置会在下一次创建 Context 时生效
     *
     * @param timezoneId Timezone ID（如 "Asia/Shanghai", "America/New_York"）
     */
    public static void setCustomTimezone(String timezoneId) {
        customTimezoneId.set(timezoneId);
        customContextOptionsFlag.set(true); // 自动启用自定义配置
        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom timezoneId set: {} (custom context options auto-enabled)", timezoneId);
        scheduleContextRebuild();
    }

    /**
     * 设置自定义 User Agent
     * 自定义配置优先于框架默认配置
     * <p>
     * 调用此方法会自动启用自定义配置模式（setCustomContextOptionsFlag(true)）
     * 配置会在下一次创建 Context 时生效
     *
     * @param userAgent User-Agent 字符串
     */
    public static void setCustomUserAgent(String userAgent) {
        customUserAgent.set(userAgent);
        customContextOptionsFlag.set(true); // 自动启用自定义配置
        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom userAgent set: {} (custom context options auto-enabled)", userAgent);
        scheduleContextRebuild();
    }

    /**
     * 设置自定义 Permissions
     * 自定义配置优先于框架默认配置
     * <p>
     * 调用此方法会自动启用自定义配置模式（setCustomContextOptionsFlag(true)）
     * 配置会在下一次创建 Context 时生效
     *
     * @param permissions 权限列表（如 List.of("geolocation", "notifications")）
     */
    public static void setCustomPermissions(List<String> permissions) {
        customPermissions.set(permissions);
        customContextOptionsFlag.set(true); // 自动启用自定义配置
        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom permissions set: {} (custom context options auto-enabled)", permissions);
        scheduleContextRebuild();
    }

    /**
     * 设置自定义 Mobile 标识
     * 自定义配置优先于框架默认配置
     * <p>
     * 调用此方法会自动启用自定义配置模式（setCustomContextOptionsFlag(true)）
     * 配置会在下一次创建 Context 时生效
     *
     * @param isMobile 是否为移动设备
     */
    public static void setCustomIsMobile(boolean isMobile) {
        customIsMobile.set(isMobile);
        customContextOptionsFlag.set(true); // 自动启用自定义配置
        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom isMobile set: {} (custom context options auto-enabled)", isMobile);
        scheduleContextRebuild();
    }

    /**
     * 设置自定义 Touch 标识
     * 自定义配置优先于框架默认配置
     * <p>
     * 调用此方法会自动启用自定义配置模式（setCustomContextOptionsFlag(true)）
     * 配置会在下一次创建 Context 时生效
     *
     * @param hasTouch 是否支持触摸
     */
    public static void setCustomHasTouch(boolean hasTouch) {
        customHasTouch.set(hasTouch);
        customContextOptionsFlag.set(true); // 自动启用自定义配置
        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom hasTouch set: {} (custom context options auto-enabled)", hasTouch);
        scheduleContextRebuild();
    }

    /**
     * 设置自定义 Color Scheme
     * 自定义配置优先于框架默认配置
     * <p>
     * 调用此方法会自动启用自定义配置模式（setCustomContextOptionsFlag(true)）
     * 配置会在下一次创建 Context 时生效
     *
     * @param colorScheme 颜色方案（如 ColorScheme.LIGHT, ColorScheme.DARK）
     */
    public static void setCustomColorScheme(ColorScheme colorScheme) {
        customColorScheme.set(colorScheme);
        customContextOptionsFlag.set(true); // 自动启用自定义配置
        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom colorScheme set: {} (custom context options auto-enabled)", colorScheme);
        scheduleContextRebuild();
    }

    /**
     * 设置自定义 Geolocation
     * 自定义配置优先于框架默认配置
     * <p>
     * 调用此方法会自动启用自定义配置模式（setCustomContextOptionsFlag(true)）
     * 配置会在下一次创建 Context 时生效
     *
     * @param latitude 纬度
     * @param longitude 经度
     */
    public static void setCustomGeolocation(double latitude, double longitude) {
        customGeolocation.set(new Geolocation(latitude, longitude));
        customContextOptionsFlag.set(true); // 自动启用自定义配置
        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom geolocation set: ({}, {}) (custom context options auto-enabled)", latitude, longitude);
        scheduleContextRebuild();
    }

    /**
     * 设置自定义 Device Scale Factor
     * 自定义配置优先于框架默认配置
     * <p>
     * 调用此方法会自动启用自定义配置模式（setCustomContextOptionsFlag(true)）
     * 配置会在下一次创建 Context 时生效
     *
     * @param deviceScaleFactor 设备缩放因子（如 1.0, 2.0, 3.0）
     */
    public static void setCustomDeviceScaleFactor(double deviceScaleFactor) {
        customDeviceScaleFactor.set((int) (deviceScaleFactor * 100)); // 存储为整数避免浮点精度问题
        customContextOptionsFlag.set(true); // 自动启用自定义配置
        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom deviceScaleFactor set: {} (custom context options auto-enabled)", deviceScaleFactor);
        scheduleContextRebuild();
    }

    /**
     * 设置自定义 Viewport 尺寸
     * 自定义配置优先于框架默认配置
     * <p>
     * 调用此方法会自动启用自定义配置模式（setCustomContextOptionsFlag(true)）
     * 配置会在下一次创建 Context 时生效
     *
     * @param width  宽度
     * @param height 高度
     */
    public static void setCustomViewportSize(int width, int height) {
        customViewportWidth.set(width);
        customViewportHeight.set(height);
        customContextOptionsFlag.set(true); // 自动启用自定义配置
        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom viewportSize set: {}x{} (custom context options auto-enabled)", width, height);
        scheduleContextRebuild();
    }

    /**
     * 获取 Page（线程安全）
     * <p>
     * 支持延迟重建机制：在获取 Page 时检查是否需要重建 Context
     * 先调用 getContext() 确保重建检查被执行
     */
    public static Page getPage() {
        // 框架层自动处理 @AutoBrowser 注解（在真正需要操作页面时触发）
        AutoBrowserProcessor.processAutoBrowserAnnotation();
        
        if (!frameworkState.isInitialized()) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }

        Page page = pageThreadLocal.get();
        if (page == null || page.isClosed()) {
            // 创建 Page 前先检查 Context，确保自定义配置被应用
            BrowserContext context = getContext();
            page = createPage(context);
            pageThreadLocal.set(page);
        }
        
        // 【关键】线程安全：使用锁保护 Page 创建/设置
        synchronized (PAGE_LOCK) {
            page = pageThreadLocal.get();
            if (page == null || page.isClosed()) {
                BrowserContext context = getContext();
                page = createPage(context);
                pageThreadLocal.set(page);
            }
        }
        return page;
    }

    /**
     * 创建新的 Page（使用指定的 Context）
     * 委托给 PlaywrightContextManager 处理
     */
    private static Page createPage(BrowserContext context) {
        return PlaywrightContextManager.createPage(context);
    }

    // ==================== Context 和 Page 创建方法 ====================

    /**
     * 创建新的 BrowserContext（保证场景间配置隔离）
     * 委托给 PlaywrightContextManager 处理
     */
    private static BrowserContext createContext() {
        return PlaywrightContextManager.createContext();
    }

    /**
     * 重置所有自定义配置（核心：保证下一个场景默认不继承）
     * 在创建 Context 后调用，确保场景隔离
     */
    private static void resetCustomContextOptions() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Resetting custom context options for next scenario...");
        
        // 【关键】线程安全检查：确没有正在使用的 Context 才清除配置
        BrowserContext existingContext = contextThreadLocal.get();
        if (existingContext != null && !existingContext.browser().isConnected()) {
            LoggingConfigUtil.logWarnIfVerbose(logger, 
                "Cannot reset custom options: Context is still in use by thread: {}. Clearing configuration anyway.", 
                Thread.currentThread().getName());
            // 继续清除，但记录警告
        }
        
        // 重置 flag
        customContextOptionsFlag.remove();
        
        // 重置所有自定义配置 ThreadLocal
        customStorageStatePath.remove();
        customLocale.remove();
        customTimezoneId.remove();
        customUserAgent.remove();
        customPermissions.remove();
        customIsMobile.remove();
        customHasTouch.remove();
        customColorScheme.remove();
        customGeolocation.remove();
        customDeviceScaleFactor.remove();
        customViewportWidth.remove();
        customViewportHeight.remove();
        
        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom context options reset completed");
    }

    /**
     * 强化页面稳定化：确保页面窗口大小稳定，防止缩放行为
     * 此方法供 PlaywrightContextManager 调用
     */
    static void stabilizePage(Page page) {
        try {
            LoggingConfigUtil.logDebugIfVerbose(logger, "页面稳定化：确保窗口大小正确...");

            // 性能优化：快速等待页面DOM加载完成（可配置）
            int stabilizeWaitTimeout = TimeoutConfig.getStabilizeTimeout();
            LoadState loadState = getConfiguredLoadState();
            try {
                page.waitForLoadState(loadState, new Page.WaitForLoadStateOptions().setTimeout(stabilizeWaitTimeout));
            } catch (Exception e) {
                LoggingConfigUtil.logDebugIfVerbose(logger, "页面加载等待超时（LoadState: {}），继续稳定化: {}", loadState, e.getMessage());
            }


            // 检查是否使用 --start-maximized
            String maximizeArgs = PlaywrightConfigManager.getWindowMaximizeArgs();
            boolean hasStartMaximized = maximizeArgs.contains("--start-maximized");


            // 获取逻辑屏幕尺寸
            Dimension screenSize = getAvailableScreenSize();
            int logicalWidth = (int) screenSize.getWidth();
            int logicalHeight = (int) screenSize.getHeight();

            if (!hasStartMaximized) {
                // 强制设置窗口大小到逻辑分辨率
                page.evaluate(String.format(
                        "window.resizeTo(%d, %d); window.moveTo(0, 0);",
                        logicalWidth, logicalHeight
                ));
                LoggingConfigUtil.logDebugIfVerbose(logger, "JavaScript窗口大小设置: {}x{}", logicalWidth, logicalHeight);
            } else {
                LoggingConfigUtil.logDebugIfVerbose(logger, "使用 --start-maximized，跳过 JavaScript 窗口大小设置");
            }

            // 固定缩放级别为100%，防止页面缩放
            page.evaluate(
                    "document.body.style.zoom = '100%'; " +
                            "document.documentElement.style.zoom = '100%'; " +
                            "document.documentElement.style.transform = 'none'; " +
                            "document.documentElement.style.transformOrigin = '0 0';"
            );

            // 禁用页面自身的缩放逻辑
            page.evaluate(
                    "window.addEventListener('resize', function(e) { e.stopPropagation(); }, true);" +
                            "document.addEventListener('DOMContentLoaded', function() {" +
                            "    if (window.devicePixelRatio !== 1) { " +
                            "    }" +
                            "});"
            );

            // 使用 Playwright 的 setViewportSize 确保 viewport 与窗口大小一致
            // 但要检查是否设置了自定义 viewport，如果是则不覆盖
            Integer customViewportWidthVal = customViewportWidth.get();
            Integer customViewportHeightVal = customViewportHeight.get();
            
            if (customViewportWidthVal != null && customViewportHeightVal != null) {
                // 保持自定义 viewport，不覆盖
                LoggingConfigUtil.logDebugIfVerbose(logger, "Custom viewport detected ({}x{}), skipping viewport size override", 
                    customViewportWidth, customViewportHeight);
            } else {
                // 使用逻辑分辨率作为 viewport（与浏览器窗口大小一致）
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
     * 创建新的 Context 和 Page
     */
    public static void createNewContextAndPage() {
        closePage();
        closeContext();

        BrowserContext context = createContext();
        contextThreadLocal.set(context);

        Page page = context.newPage();
        pageThreadLocal.set(page);

        LoggingConfigUtil.logDebugIfVerbose(logger, "New Context and Page created for thread: {}", Thread.currentThread().getId());
    }

    // ==================== 关闭和清理方法 ====================

    /**
     * 关闭当前线程的 Page
     * 委托给 PlaywrightContextManager 处理
     */
    public static void closePage() {
        synchronized (PAGE_LOCK) {
            Page page = pageThreadLocal.get();
            if (page != null) {
                try {
                    PlaywrightContextManager.closePage(page);
                } finally {
                    pageThreadLocal.remove();
                }
            }
        }
    }

    /**
     * 关闭当前线程的 Context
     * 委托给 PlaywrightContextManager 处理
     */
    public static void closeContext() {
        synchronized (CONTEXT_LOCK) {
            BrowserContext context = contextThreadLocal.get();
            if (context != null) {
                try {
                    PlaywrightContextManager.closeContext(context);
                } finally {
                    contextThreadLocal.remove();
                }
            }
        }
    }

    /**
     * 重启浏览器（用于重跑测试时或浏览器类型切换）
     */
    public static synchronized void restartBrowser() {
        String oldConfigId = getCurrentConfigId();
        if (oldConfigId == null) {
            logger.warn("Cannot restart browser: configId is null. Browser not initialized.");
            return;
        }

        LoggingConfigUtil.logInfoIfVerbose(logger, "🔄 Restarting browser for config: {}", oldConfigId);

        try {
            closePage();
            closeContext();

            // 关闭所有浏览器实例，确保没有残留的实例
            for (Map.Entry<String, Browser> entry : browserInstances.entrySet()) {
                Browser browser = entry.getValue();
                if (browser != null && browser.isConnected()) {
                    try {
                        browser.close();
                        LoggingConfigUtil.logInfoIfVerbose(logger, "Browser closed for config: {}", entry.getKey());
                    } catch (Exception e) {
                        logger.warn("Error closing browser instance for config {}: {}", entry.getKey(), e.getMessage());
                    }
                }
            }
            browserInstances.clear();

            // 关闭所有Playwright实例
            for (Map.Entry<String, Playwright> entry : playwrightInstances.entrySet()) {
                Playwright playwright = entry.getValue();
                if (playwright != null) {
                    try {
                        playwright.close();
                        LoggingConfigUtil.logInfoIfVerbose(logger, "Playwright instance closed for config: {}", entry.getKey());
                    } catch (Exception e) {
                        logger.warn("Error closing Playwright instance for config {}: {}", entry.getKey(), e.getMessage());
                    }
                }
            }
            playwrightInstances.clear();

            // 生成新的 configId（此时浏览器类型可能已经更新）
            String newConfigId = generateConfigId();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Generating new configId: {} (old was: {})", newConfigId, oldConfigId);

            initializePlaywright(newConfigId);
            initializeBrowser(newConfigId);
            currentConfigId = newConfigId;

            LoggingConfigUtil.logInfoIfVerbose(logger, " Browser restarted successfully for config: {}", newConfigId);
        } catch (Exception e) {
            logger.error("Failed to restart browser for config: {}", oldConfigId, e);
            throw new BrowserException("Failed to restart browser for config: " + oldConfigId, e);
        }
    }

    /**
     * 清理所有资源
     */
    public static void cleanupAll() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Cleaning up all Playwright resources...");

        // 终止所有浏览器下载进程
        terminateDownloadProcesses();

        // 关闭当前线程的页面和上下文
        closePage();
        closeContext();

        // 关闭所有浏览器实例
        browserInstances.values().forEach(browser -> {
            if (browser.isConnected()) {
                browser.close();
                LoggingConfigUtil.logInfoIfVerbose(logger, "Browser instance closed");
            }
        });
        browserInstances.clear();

        // 关闭所有 Playwright 实例
        playwrightInstances.values().forEach(playwright -> {
            try {
                playwright.close();
                LoggingConfigUtil.logInfoIfVerbose(logger, "Playwright instance closed");
            } catch (Exception e) {
                logger.warn("Error closing Playwright instance", e);
            }
        });
        playwrightInstances.clear();

        currentConfigId = null;
        LoggingConfigUtil.logInfoIfVerbose(logger, "All Playwright resources cleaned up");
    }

    /**
     * 终止所有浏览器下载进程
     */
    private static void terminateDownloadProcesses() {
        synchronized (downloadProcesses) {
            if (downloadProcesses.isEmpty()) {
                return;
            }

            LoggingConfigUtil.logInfoIfVerbose(logger, "Terminating {} browser download processes...", downloadProcesses.size());

            for (Process process : downloadProcesses) {
                try {
                    if (process.isAlive()) {
                        process.destroy();
                        // 等待最多 5 秒让进程正常退出
                        boolean exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                        if (!exited) {
                            LoggingConfigUtil.logWarnIfVerbose(logger, "Download process did not exit gracefully, forcing termination");
                            process.destroyForcibly();
                        }
                        LoggingConfigUtil.logInfoIfVerbose(logger, "Download process terminated");
                    }
                } catch (Exception e) {
                    logger.warn("Failed to terminate download process: {}", e.getMessage());
                }
            }

            downloadProcesses.clear();
            LoggingConfigUtil.logInfoIfVerbose(logger, "All download processes terminated");
        }
    }

    /**
     * 清理资源（向后兼容）
     */
    public static void cleanup() {
        cleanupForScenario();
    }

    // ==================== Serenity BDD 集成方法 ====================

    /**
     * Scenario 级别的初始化
     * 每个 scenario 开始时由 FrameworkCore 调用
     */
    public static void initializeForScenario() {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Initializing for scenario...");

        if (!frameworkState.isInitialized() || currentConfigId == null) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }


        // 根据配置决定是否复用 Context/Page
        String restartBrowserForEach = PlaywrightConfigManager.getRestartStrategy();

        if ("scenario".equalsIgnoreCase(restartBrowserForEach)) {
            // 清理缓存的PageObject, 避免使用已经关闭的context/page
            PageObjectFactory.clearAll();
            // Scenario 模式：关闭旧的 Context/Page，延迟创建新的（等测试步骤真正需要时）
            closePage();
            closeContext();
            // 【优化】不立即创建 BrowserContext，延迟到 getContext()/getPage() 时创建
            // 这样如果测试步骤设置了自定义配置（如 session 恢复），可以直接应用，避免重复创建
            LoggingConfigUtil.logDebugIfVerbose(logger, " Scenario initialization completed (Context/Page will be created on demand)");
        } else {
            // Feature 模式：复用现有的 Context/Page（如果存在）
            BrowserContext existingContext = contextThreadLocal.get();
            Page existingPage = pageThreadLocal.get();

            if (existingContext != null && existingPage != null && !existingPage.isClosed()) {
                LoggingConfigUtil.logDebugIfVerbose(logger, " Scenario initialization completed (reusing existing Context/Page)");
            } else {
                PageObjectFactory.clearAll();
                // 关闭旧的 Context/Page，延迟创建新的
                closePage();
                closeContext();
                // 【优化】不立即创建 BrowserContext，延迟创建以支持自定义配置
                LoggingConfigUtil.logDebugIfVerbose(logger, " Scenario initialization completed (Context/Page will be created on demand)");
            }
        }
    }

    /**
     * Scenario 级别的清理
     * 每个 scenario 结束时调用
     *
     * 新设计：简化浏览器管理，不依赖Cucumber hooks
     * - 浏览器覆盖配置会自动在下一个scenario开始时更新
     * - 如果下一个scenario需要不同的浏览器，PlaywrightManager会自动切换
     */
    public static void cleanupForScenario() {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Cleaning up for scenario...");

        // 清除 AutoBrowser 处理状态和浏览器覆盖配置
        AutoBrowserProcessor.clearProcessingState();

        // 【关键】重置所有自定义配置,确保scenario之间配置隔离
        resetCustomContextOptions();

        // 根据配置决定是否关闭 Context/Page 和浏览器
        String restartBrowserForEach = PlaywrightConfigManager.getRestartStrategy();

        if ("scenario".equalsIgnoreCase(restartBrowserForEach)) {
            // Scenario 模式：只关闭 Context 和 Page，保持 Browser 实例
            // 这样下一个 scenario 可以复用同一个 Browser，避免重复启动的开销
            LoggingConfigUtil.logDebugIfVerbose(logger, "Restart strategy is 'scenario' - closing Context and Page (keeping Browser alive)");
            closePage();
            closeContext();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Context and Page closed for scenario (Browser kept alive)");
        } else {
            // Feature 模式：不关闭 Context/Page，让下一个 scenario 复用
            LoggingConfigUtil.logDebugIfVerbose(logger, "Restart strategy is 'feature' - keeping Context and Page for reuse");
            // 只清理页面状态，不关闭 Context/Page
            cleanupPageState();
        }

        // 注意：浏览器覆盖配置的清除已移至 AutoBrowserProcessor.clearProcessingState()
        // 这样可以确保浏览器状态在scenario之间正确传递
    }

    /**
     * 清理页面状态（但不关闭 Context/Page）
     * 用于 Feature 模式下，在 scenario 之间复用 Context/Page
     */
    public static void cleanupPageState() {
        BrowserContext context = contextThreadLocal.get();
        Page page = pageThreadLocal.get();

        if (page != null && !page.isClosed()) {
            try {
                // 清理页面状态
                LoggingConfigUtil.logDebugIfVerbose(logger, "Cleaning up page state while keeping Context/Page open");

                // 清除页面缓存
                page.evaluate("() => { if (window.performance && window.performance.clearResourceTimings) window.performance.clearResourceTimings(); }");

                // 不需要导航到空白页，这会导致浏览器打开新标签页
                // 下一个测试会自动导航到目标页面

                LoggingConfigUtil.logDebugIfVerbose(logger, "Page state cleaned up");
            } catch (Exception e) {
                logger.warn("Failed to cleanup page state: {}", e.getMessage());
            }
        }
    }

    /**
     * Feature 级别的初始化
     * 每个 feature 开始时由 FrameworkCore 调用
     */
    public static void initializeForFeature() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Initializing for feature...");

        if (!frameworkState.isInitialized() || currentConfigId == null) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }

        LoggingConfigUtil.logInfoIfVerbose(logger, " Feature initialization completed");
    }

    /**
     * Feature 级别的清理
     * 每个 feature 结束时调用
     */
    public static void cleanupForFeature() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Cleaning up for feature...");
        closePage();
        closeContext();

        // 根据配置决定是否关闭浏览器
        String restartBrowserForEach = PlaywrightConfigManager.getRestartStrategy();

        if ("feature".equalsIgnoreCase(restartBrowserForEach)) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Restart strategy is 'feature' - closing browser at feature end");
            String configId = getCurrentConfigId();
            if (configId != null) {
                Browser browser = browserInstances.get(configId);
                if (browser != null && browser.isConnected()) {
                    browser.close();
                    browserInstances.remove(configId);
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Browser closed for feature (strategy: {})", restartBrowserForEach);
                }
            }
        }
    }

    // ==================== 截图方法 ====================

    /**
     * 截图并返回文件路径
     */
    public static String takeScreenshot() {
        return takeScreenshot("Screenshot");
    }

    /**
     * 根据步骤和结果执行截图（受策略控制）
     */
    public static String takeScreenshot(ExecutedStepDescription step, TestResult result) {
        if (!shouldTakeScreenshotForStep(step, result)) {
            logger.debug("Screenshot skipped for step: {} (strategy: {})",
                    step != null ? step.getTitle() : "unknown",
                    ScreenshotStrategy.from(SystemEnvironmentVariables.currentEnvironmentVariables()));
            return null;
        }

        String stepTitle = step != null ? step.getTitle() : "Unknown Step";
        return takeScreenshot(stepTitle);
    }

    /**
     * 截图并返回截图文件（用于页面变化检测）
     */
    public static File takeScreenshotWithReturn(String title) {
        try {
            Page page = pageThreadLocal.get();
            if (page != null && !page.isClosed()) {
                // 确保截图目录存在
                Path screenshotDir = Paths.get("target/site/serenity");
                Files.createDirectories(screenshotDir);

                // 生成文件名
                String hashInput = title + "_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId();
                String screenshotHash = generateHash(hashInput);
                String screenshotName = screenshotHash + ".png";
                Path screenshotPath = screenshotDir.resolve(screenshotName);

                // 性能优化：快速等待页面稳定（可配置）
                int screenshotWaitTimeout = TimeoutConfig.getScreenshotTimeout();
                LoadState loadState = getConfiguredLoadState();
                try {
                    page.waitForLoadState(loadState, new Page.WaitForLoadStateOptions().setTimeout(screenshotWaitTimeout));
                } catch (Exception e) {
                    // 将DEBUG日志降级为TRACE，减少日志噪音（截图等待超时是常见且正常的情况）
                    if (logger.isTraceEnabled()) {
                        logger.trace("Screenshot wait timeout ({}ms) - continuing with screenshot: {}", screenshotWaitTimeout, e.getMessage());
                    }
                }

                // 截图模式：根据配置选择全页截图或viewport截图
                boolean fullPage = isFullPageScreenshot();
                page.screenshot(new Page.ScreenshotOptions()
                        .setFullPage(fullPage)
                        .setPath(screenshotPath));

                LoggingConfigUtil.logDebugIfVerbose(
                        logger, "Screenshot saved: {} (fullPage: {})", screenshotPath, fullPage);

                return screenshotPath.toFile();
            }
        } catch (Exception e) {
            logger.error("Failed to take screenshot", e);
        }
        return null;
    }

    /**
     * 截图并返回截图文件路径
     */
    public static String takeScreenshot(String title) {
        try {
            Page page = pageThreadLocal.get();
            if (page != null && !page.isClosed()) {
                // 确保截图目录存在
                Path screenshotDir = Paths.get("target/site/serenity");
                Files.createDirectories(screenshotDir);

                // 生成文件名
                String hashInput = title + "_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId();
                String screenshotHash = generateHash(hashInput);
                String screenshotName = screenshotHash + ".png";
                Path screenshotPath = screenshotDir.resolve(screenshotName);

                // 性能优化：快速等待页面稳定（可配置）
                int screenshotWaitTimeout = TimeoutConfig.getScreenshotTimeout();
                LoadState loadState = getConfiguredLoadState();
                try {
                    page.waitForLoadState(loadState, new Page.WaitForLoadStateOptions().setTimeout(screenshotWaitTimeout));
                } catch (Exception e) {
                    // 将DEBUG日志降级为TRACE，减少日志噪音（截图等待超时是常见且正常的情况）
                    if (logger.isTraceEnabled()) {
                        logger.trace("Screenshot wait timeout ({}ms) - continuing with screenshot: {}", screenshotWaitTimeout, e.getMessage());
                    }
                }

                // 截图模式：根据配置选择全页截图或viewport截图
                // 全页截图对于长页面较慢但捕获完整内容，viewport截图速度快但只捕获可见区域
                boolean fullPage = isFullPageScreenshot();
                Page.ScreenshotOptions screenshotOptions = new Page.ScreenshotOptions()
                        .setFullPage(fullPage)
                        .setPath(screenshotPath);

                page.screenshot(screenshotOptions);

                LoggingConfigUtil.logDebugIfVerbose(
                        logger, "Screenshot saved: {} (fullPage: {})", screenshotPath, fullPage);

                // 返回截图文件路径
                return screenshotPath.toString();
            }
        } catch (Exception e) {
            logger.error("Failed to take screenshot", e);
        }
        return null;
    }

    // ==================== 配置访问方法（封装层） ====================

    /**
     * 获取浏览器类型
     * 优先使用测试用例级别的覆盖配置（如果存在）
     *
     * @return 浏览器类型
     */
    public static String getBrowserType() {
        // 优先级1: 检查是否有测试用例级别的浏览器覆盖
        if (BrowserOverrideManager.hasOverride()) {
            String overrideBrowser = BrowserOverrideManager.getEffectiveBrowserType();
            LoggingConfigUtil.logDebugIfVerbose(logger,
                "Using override browser type: {}", overrideBrowser);
            return overrideBrowser;
        }

        // 优先级2: 使用配置文件中的默认值
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_TYPE);
    }

    /**
     * 检查是否需要重启浏览器
     * 通过比较当前configId中的浏览器类型和期望的浏览器类型
     *
     * @return true 如果需要重启浏览器
     */
    private static boolean needsBrowserRestart() {
        String configId = getCurrentConfigId();
        logger.info("🔍 [needsBrowserRestart] Checking if browser restart needed...");
        logger.info("   configId: {}", configId);
        logger.info("   currentConfigId field: {}", currentConfigId);

        if (configId == null) {
            logger.warn("   configId is null, skipping restart check");
            return false;
        }

        // 从configId中提取当前浏览器类型（格式：browserType_headless_channel）
        String[] configIdParts = configId.split("_");
        logger.info("   configId parts: {} (length: {})",
            java.util.Arrays.toString(configIdParts), configIdParts.length);

        if (configIdParts.length < 1) {
            logger.warn("   Invalid configId format, skipping restart check");
            return false;
        }
        String currentBrowserType = configIdParts[0];
        logger.info("   Current browser type from configId: {}", currentBrowserType);

        // 获取期望的浏览器类型（考虑override）
        boolean hasOverride = BrowserOverrideManager.hasOverride();
        String expectedBrowserType = getBrowserType();
        logger.info("   Expected browser type: {} (hasOverride: {})",
            expectedBrowserType, hasOverride);

        // 如果类型不同，需要重启
        if (!currentBrowserType.equalsIgnoreCase(expectedBrowserType)) {
            logger.info(" [needsBrowserRestart] Browsers differ, needs restart: '{}' vs '{}'",
                currentBrowserType, expectedBrowserType);
            return true;
        }

        logger.info(" [needsBrowserRestart] Browsers match, no restart needed");
        return false;
    }

    /**
     * 是否为 headless 模式
     */
    public static boolean isHeadless() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_BROWSER_HEADLESS);
    }

    /**
     * 获取浏览器慢动作延迟（毫秒）
     */
    public static int getBrowserSlowMo() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_BROWSER_SLOWMO);
    }

    /**
     * 获取浏览器超时（毫秒）
     */
    public static int getBrowserTimeout() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_BROWSER_TIMEOUT);
    }

    /**
     * 获取浏览器下载路径
     */
    public static String getBrowserDownloadsPath() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_DOWNLOADS_PATH);
    }

    /**
     * 获取浏览器启动参数
     * 根据浏览器类型返回对应的启动参数
     */
    public static String getBrowserArgs() {
        String browserType = getBrowserType();
        String channel = getBrowserChannel();
        
        // 根据浏览器类型和 channel 确定使用哪个 args
        String args = null;
        
        switch (browserType.toLowerCase()) {
            case "firefox":
                // Firefox 浏览器（必须使用 Playwright 版本）
                args = FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_FIREFOX_ARGS);
                if (args != null && !args.trim().isEmpty()) {
                    LoggingConfigUtil.logDebugIfVerbose(logger, "Using Firefox args: {}", args);
                    return args;
                }
                break;
                
            case "webkit":
                // WebKit 浏览器（必须使用 Playwright 版本）
                args = FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_WEBKIT_ARGS);
                if (args != null && !args.trim().isEmpty()) {
                    LoggingConfigUtil.logDebugIfVerbose(logger, "Using WebKit args: {}", args);
                    return args;
                }
                break;
                
            case "chromium":
                // Chromium 系列浏览器
                if ("msedge".equalsIgnoreCase(channel) || "edge".equalsIgnoreCase(channel)) {
                    // Edge 浏览器
                    args = FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_EDGE_ARGS);
                    if (args != null && !args.trim().isEmpty()) {
                        LoggingConfigUtil.logDebugIfVerbose(logger, "Using Edge args: {}", args);
                        return args;
                    }
                } else if ("chrome".equalsIgnoreCase(channel)) {
                    // Chrome 浏览器
                    args = FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_CHROME_ARGS);
                    if (args != null && !args.trim().isEmpty()) {
                        LoggingConfigUtil.logDebugIfVerbose(logger, "Using Chrome args: {}", args);
                        return args;
                    }
                } else {
                    // Chromium 无 channel
                    args = FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_CHROMIUM_ARGS);
                    if (args != null && !args.trim().isEmpty()) {
                        LoggingConfigUtil.logDebugIfVerbose(logger, "Using Chromium args: {}", args);
                        return args;
                    }
                }
                break;
        }
        
        return "";
    }

    /**
     * 获取浏览器 channel
     * channel 仅适用于 Chromium 系列浏览器（Chrome、Edge）
     * 
     * 常用的 channel 值：
     * - "chrome" - 使用本地安装的 Chrome 浏览器
     * - "chrome-beta" - 使用 Chrome Beta 版本
     * - "chrome-dev" - 使用 Chrome Dev 版本
     * - "chrome-canary" - 使用 Chrome Canary 版本
     * - "msedge" - 使用本地安装的 Edge 浏览器
     * - "msedge-beta" - 使用 Edge Beta 版本
     * - "msedge-dev" - 使用 Edge Dev 版本
     * - "msedge-canary" - 使用 Edge Canary 版本
     */
    public static String getBrowserChannel() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_CHANNEL);
    }

    /**
     * 获取浏览器可执行文件路径
     * 根据浏览器类型返回对应的可执行文件路径
     * 
     * 注意：Firefox 和 WebKit 必须使用 Playwright 编译的版本，不支持 executablePath
     */
    public static String getBrowserExecutablePath() {
        String browserType = getBrowserType();
        String channel = getBrowserChannel();
        
        // 根据浏览器类型和 channel 确定使用哪个 executablePath
        String executablePath = null;
        
        switch (browserType.toLowerCase()) {
            case "firefox":
                // Firefox 必须使用 Playwright 编译的版本，不支持 executablePath
                LoggingConfigUtil.logDebugIfVerbose(logger, "Firefox uses Playwright's compiled version (no executablePath support)");
                return null;
                
            case "webkit":
                // WebKit 必须使用 Playwright 编译的版本，不支持 executablePath
                LoggingConfigUtil.logDebugIfVerbose(logger, "WebKit uses Playwright's compiled version (no executablePath support)");
                return null;
                
            case "chromium":
                // Chromium 系列浏览器（Chrome, Edge）支持本地 executablePath
                if ("msedge".equalsIgnoreCase(channel) || "edge".equalsIgnoreCase(channel)) {
                    // Edge 浏览器
                    executablePath = FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_EDGE_EXECUTABLE_PATH);
                    if (executablePath != null && !executablePath.trim().isEmpty()) {
                        LoggingConfigUtil.logDebugIfVerbose(logger, "Using Edge executablePath: {}", executablePath);
                        return executablePath;
                    }
                } else if ("chrome".equalsIgnoreCase(channel)) {
                    // Chrome 浏览器
                    executablePath = FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_CHROME_EXECUTABLE_PATH);
                    if (executablePath != null && !executablePath.trim().isEmpty()) {
                        LoggingConfigUtil.logDebugIfVerbose(logger, "Using Chrome executablePath: {}", executablePath);
                        return executablePath;
                    }
                } else {
                    // Chromium 无 channel，使用 Playwright 版本
                    LoggingConfigUtil.logDebugIfVerbose(logger, "Chromium without channel uses Playwright's version");
                    return null;
                }
                break;
        }
        
        return null;
    }

    /**
     * 是否全页截图
     */
    public static boolean isFullPageScreenshot() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_SCREENSHOT_FULLPAGE);
    }


    public static String getProjectName() {
        return FrameworkConfigManager.getString(FrameworkConfig.SERENITY_PROJECT_NAME);
    }

    // ==================== Axe-core 配置方法 ====================

    /**
     * 是否启用 axe-core 扫描
     */
    public static boolean isAxeScanEnabled() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.AXE_SCAN_ENABLED);
    }

    /**
     * 获取 axe-core WCAG 标签
     */
    public static String getAxeScanTags() {
        return FrameworkConfigManager.getString(FrameworkConfig.AXE_SCAN_TAGS);
    }

    /**
     * 获取 axe-core 报告输出目录
     */
    public static String getAxeScanOutputDir() {
        return FrameworkConfigManager.getString(FrameworkConfig.AXE_SCAN_OUTPUT_DIR);
    }

    // ==================== 公共访问方法（用于其他类访问自定义配置） ====================

    /**
     * 获取自定义 Context 选项标志
     */
    static ThreadLocal<Boolean> getCustomContextOptionsFlag() {
        return customContextOptionsFlag;
    }

    /**
     * 获取自定义选项管理器实例
     */
    static CustomOptions getCustomOptions() {
        return CustomOptions.INSTANCE;
    }

    // ==================== 自定义配置内部类 ====================

    /**
     * 自定义配置管理器（内部类）
     * 负责管理所有自定义 Context 配置
     */
    public static class CustomOptions {
        private static final CustomOptions INSTANCE = new CustomOptions();

        // 所有自定义配置的 getter 方法

        public java.nio.file.Path getStorageStatePath() {
            return customStorageStatePath.get();
        }

        public String getLocale() {
            return customLocale.get();
        }

        public String getTimezoneId() {
            return customTimezoneId.get();
        }

        public String getUserAgent() {
            return customUserAgent.get();
        }

        public java.util.List<String> getPermissions() {
            return customPermissions.get();
        }

        public Geolocation getGeolocation() {
            return customGeolocation.get();
        }

        public Integer getDeviceScaleFactor() {
            return customDeviceScaleFactor.get();
        }

        public Boolean getIsMobile() {
            return customIsMobile.get();
        }

        public Boolean getHasTouch() {
            return customHasTouch.get();
        }

        public com.microsoft.playwright.options.ColorScheme getColorScheme() {
            return customColorScheme.get();
        }

        public Integer getViewportWidth() {
            return customViewportWidth.get();
        }

        public Integer getViewportHeight() {
            return customViewportHeight.get();
        }
    }
}