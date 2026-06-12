package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStackManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.AutoBrowserProcessor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.InitializationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.strategy.ScreenshotStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.session.SessionManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import net.thucydides.model.environment.SystemEnvironmentVariables;
import net.thucydides.model.steps.ExecutedStepDescription;
import net.thucydides.model.util.EnvironmentVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

    // ==================== 非 ThreadLocal 静态变量 ====================
    // 线程安全的实例存储
    private static final ConcurrentMap<String, Playwright> playwrightInstances = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Browser> browserInstances = new ConcurrentHashMap<>();
    // 线程安全的下载进程存储（使用 CopyOnWriteArrayList 保证多线程并发安全）
    private static final List<Process> downloadProcesses = new CopyOnWriteArrayList<>();

    // 线程安全锁：保护共享资源（Browser 实例）
    private static final Object BROWSER_LOCK = new Object();

    // Context/Page 细粒度锁：保护 Context 和 Page 创建/销毁
    private static final Object CONTEXT_LOCK = new Object();
    private static final Object PAGE_LOCK = new Object();

    // 框架状态引用
    private static final FrameworkState frameworkState = FrameworkState.getInstance();

    // ==================== ThreadLocal 变量（17个，集中管理） ====================

    // ---- 核心 Page/Context ----
    private static final ThreadLocal<BrowserContext> contextThreadLocal = new ThreadLocal<>();
    private static final ThreadLocal<Page> pageThreadLocal = new ThreadLocal<>();

    // ---- 配置标识 ----
    private static final ThreadLocal<String> currentConfigId = new ThreadLocal<>();

    // ---- 自定义 Context 选项（用户自定义优先于框架配置，13个） ----
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
    private static final ThreadLocal<Boolean> customProxyEnabled = new ThreadLocal<>();

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
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
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
                    Long.toHexString(Thread.currentThread().threadId());
        }
    }

    /**
     * 清理临时截图目录（target/screenshots）
     * 仅删除临时截图，不触碰 Serenity 报告目录 (target/site/serenity)
     * 在每个 scenario 结束时调用，避免临时文件累积
     */
    private static void cleanupTempScreenshots() {
        try {
            Path screenshotDir = Paths.get("target", "screenshots");
            if (!Files.exists(screenshotDir)) {
                return;
            }

            AtomicInteger deletedCount = new AtomicInteger(0);
            Files.walk(screenshotDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    if (!path.equals(screenshotDir)) {
                        try {
                            Files.deleteIfExists(path);
                            deletedCount.incrementAndGet();
                        } catch (Exception ignored) {
                            LoggingConfigUtil.logDebugIfVerbose(logger, "Skipping file during screenshot cleanup: {}", path);
                        }
                    }
                });

            LoggingConfigUtil.logDebugIfVerbose(logger,
                "Cleaned temp screenshots: {} files from {}", deletedCount.get(), screenshotDir);
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to clean temp screenshots: {}", e.getMessage());
        }
    }

    /**
     * 清理临时下载目录（target/downloads）
     * 在每个 scenario 结束时调用，避免下载文件跨用例累积
     */
    private static void cleanupTempDownloads() {
        try {
            String downloadsPath = config().getBrowserDownloadsPath();
            Path downloadDir = Paths.get(downloadsPath);
            if (!Files.exists(downloadDir)) {
                return;
            }

            AtomicInteger deletedCount = new AtomicInteger(0);
            Files.walk(downloadDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    if (!path.equals(downloadDir)) {
                        try {
                            Files.deleteIfExists(path);
                            deletedCount.incrementAndGet();
                        } catch (Exception ignored) {
                            LoggingConfigUtil.logDebugIfVerbose(logger, "Skipping file during download cleanup: {}", path);
                        }
                    }
                });

            logger.info("[Download] Cleaned {} file(s) from {}", deletedCount.get(), downloadDir.toAbsolutePath());
        } catch (Exception e) {
            logger.warn("[Download] Failed to clean temp downloads: {}", e.getMessage());
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
     * 获取当前截图策略（统一入口，消除重复的 EnvironmentVariables + ScreenshotStrategy 初始化）
     */
    private static ScreenshotStrategy getScreenshotStrategy() {
        try {
            EnvironmentVariables environmentVariables = SystemEnvironmentVariables.currentEnvironmentVariables();
            return ScreenshotStrategy.from(environmentVariables);
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to determine screenshot strategy", e);
            return null;
        }
    }

    /**
     * 根据截图策略检查是否应该截图（针对步骤）
     */
    private static boolean shouldTakeScreenshotForStep(ExecutedStepDescription step, TestResult result) {
        if (step == null) {
            return false;
        }
        ScreenshotStrategy strategy = getScreenshotStrategy();
        if (strategy == null) return true; // 异常时默认截图
        return strategy.shouldTakeScreenshotFor(step);
    }

    /**
     * 根据截图策略检查是否应该截图（针对测试结果）
     */
    private static boolean shouldTakeScreenshotForTestResult(TestResult result) {
        if (result == null) {
            return false;
        }
        ScreenshotStrategy strategy = getScreenshotStrategy();
        if (strategy == null) return true; // 异常时默认截图
        return strategy.shouldTakeScreenshotFor(result);
    }

    /**
     * 根据截图策略检查是否应该截图（针对测试结果）
     */
    private static boolean shouldTakeScreenshotForTestOutcome(TestOutcome testOutcome) {
        if (testOutcome == null) {
            return false;
        }
        ScreenshotStrategy strategy = getScreenshotStrategy();
        if (strategy == null) return true; // 异常时默认截图
        return strategy.shouldTakeScreenshotFor(testOutcome);
    }

    /**
     * 获取配置的页面加载状态（可配置）
     * 配置属性: playwright.page.load.state
     * 可选值: LOAD, DOMCONTENTLOADED, NETWORKIDLE
     * 默认值: DOMCONTENTLOADED
     */
    private static LoadState getConfiguredLoadState() {
        String loadStateConfig = config().getPageLoadState();
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
        if (frameworkState.isInitialized() && currentConfigId.get() != null) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Playwright environment already initialized with config: {}", currentConfigId.get());
            return;
        }

        String configId = generateConfigId();
        LoggingConfigUtil.logInfoIfVerbose(logger, "Initializing Playwright environment with config: {}", configId);
        initializePlaywright(configId);
        // 不在此处初始化浏览器，延迟到首次访问时启动
        // 这样可以支持 @AutoBrowser 动态浏览器切换
        // initializeBrowser(configId);
        currentConfigId.set(configId);

        LoggingConfigUtil.logInfoIfVerbose(logger, " Playwright environment initialized successfully (browser will be launched on first access)");
    }

    /**
     * 生成配置ID
     * 格式：{browserType}_{headless/headed}_{channel}
     * 
     * 注意：Firefox 和 WebKit 不支持 channel，会显示为空
     */
    private static String generateConfigId() {
        String browserType = config().getBrowserType();
        String headlessMode = config().isHeadless() ? "headless" : "headed";
        String channel = config().getBrowserChannel();
        
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

        // ==================== BrowserStack 代理透传 ====================
        // 公司网络环境下，域名解析和外网直连均被阻断
        // 需要将 HTTPS_PROXY 注入 Playwright 底层 Node.js 子进程，
        // 让 CDP WebSocket 连接通过公司 HTTP 代理建立 CONNECT 隧道
        if (BrowserStackManager.isBrowserStackEnabled()) {
            String proxyHost = FrameworkConfigManager.getString(
                FrameworkConfig.BROWSERSTACK_PROXY_HOST);
            String proxyPort = FrameworkConfigManager.getString(
                FrameworkConfig.BROWSERSTACK_PROXY_PORT);
            String proxyUser = FrameworkConfigManager.getString(
                FrameworkConfig.BROWSERSTACK_PROXY_USERNAME);
            String proxyPass = FrameworkConfigManager.getString(
                FrameworkConfig.BROWSERSTACK_PROXY_PASSWORD);

            if (proxyHost != null && !proxyHost.trim().isEmpty()
                    && proxyPort != null && !proxyPort.trim().isEmpty()) {
                String proxyUrl;
                if (proxyUser != null && !proxyUser.trim().isEmpty()
                        && proxyPass != null && !proxyPass.trim().isEmpty()) {
                    proxyUrl = String.format("http://%s:%s@%s:%s",
                            proxyUser, proxyPass, proxyHost, proxyPort);
                } else {
                    proxyUrl = String.format("http://%s:%s", proxyHost, proxyPort);
                }
                env.put("HTTPS_PROXY", proxyUrl);
                env.put("HTTP_PROXY", proxyUrl);
                env.put("NO_PROXY", "localhost,127.0.0.1,::1");
                LoggingConfigUtil.logInfoIfVerbose(logger,
                        "[BrowserStack Proxy] Injecting proxy for Playwright Node process: {}:{}",
                        proxyHost, proxyPort);
            }
        }

        options.setEnv(env);

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
        boolean userConfig = config().isSkipBrowserDownload();

        String browserType = getBrowserType();
        String channel = config().getBrowserChannel();

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
                    String chromePath = config().getBrowserExecutablePath();
                    boolean hasLocalChrome = chromePath != null && !chromePath.trim().isEmpty();
                    return hasLocalChrome || userConfig;
                } else if ("msedge".equalsIgnoreCase(channel) || "edge".equalsIgnoreCase(channel)) {
                    // Edge: 如果设置了 executablePath 或用户配置跳过，则跳过
                    String edgePath = config().getBrowserExecutablePath();
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
            long checkStart = System.currentTimeMillis();
            ensureBrowserInstalledForType(browserType);
            long checkElapsed = System.currentTimeMillis() - checkStart;
            LoggingConfigUtil.logInfoIfVerbose(logger, "[Browser Init] Browser check completed in {}ms", checkElapsed);
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
        boolean headless = config().isHeadless();
        int slowMo = config().getBrowserSlowMo();
        int timeout = config().getBrowserTimeout();

        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setSlowMo(slowMo)
                .setTimeout(timeout);

        // 配置窗口大小和启动参数
        configureBrowserLaunchOptions(launchOptions);


        long initStart = System.currentTimeMillis();
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "[Browser Init] Starting browser launch: type={}, channel={}, headless={}", 
                browserType, config().getBrowserChannel(), config().isHeadless());
            // 启动浏览器
            Browser browser = setupBrowser(playwright, browserType, launchOptions);
            browserInstances.put(configId, browser);

            long elapsed = System.currentTimeMillis() - initStart;
            LoggingConfigUtil.logInfoIfVerbose(logger, "[Browser Init] Browser initialized successfully in {}ms: {} for config: {}", 
                elapsed, browserType, configId);
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
        boolean maximizeWindow = config().isWindowMaximize();
        String maximizeArgs = config().getWindowMaximizeArgs();
        boolean hasStartMaximized = maximizeArgs.contains("--start-maximized");

        // 获取逻辑屏幕尺寸
        Dimension screenSize = getAvailableScreenSize();
        int screenWidth = (int) screenSize.getWidth();
        int screenHeight = (int) screenSize.getHeight();

        // 获取浏览器类型
        String browserType = getBrowserType();
        boolean isChromium = config().isChromiumBased(browserType);

        // 构建启动参数
        List<String> args = new ArrayList<>();

        // 添加用户配置的浏览器启动参数（已根据浏览器类型自动选择）
        String browserArgs = config().getBrowserArgs();
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
        String channel = config().getBrowserChannel();
        if (channel != null && !channel.isEmpty() && isChromium) {
            launchOptions.setChannel(channel);
            logger.info("Browser channel: {}", channel);
        } else if (channel != null && !channel.isEmpty() && !isChromium) {
            LoggingConfigUtil.logDebugIfVerbose(logger,
                "Ignoring browser channel '{}' for browser type '{}' (channel only applies to Chromium-based browsers)",
                channel, browserType);
        }

        // 设置浏览器可执行文件路径（用于启动本地安装的浏览器）
        String executablePath = config().getBrowserExecutablePath();
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
     * 设置 BrowserStack 浏览器连接（使用新API）
     */
    private static Browser setupBrowserStackBrowser(Playwright playwright, String browserType) {
        try {
            // 使用新的企业级 BrowserStackManager.connect() API
            BrowserStackManager.setCurrentSessionId("auto-" + System.currentTimeMillis());
            Browser browser = BrowserStackManager.connect(playwright);
            logger.info("[BrowserStack] Connected successfully via CDP");
            return browser;
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
        if (currentConfigId.get() == null) {
            currentConfigId.set(generateConfigId());
        }
        return currentConfigId.get();
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
        long methodStart = System.currentTimeMillis();
        
        // 获取当前的配置ID
        String currentConfig = getCurrentConfigId();
        if (currentConfig == null) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }

        // 获取期望的浏览器类型（可能来自 @AutoBrowser 标签）
        String desiredBrowserType = getBrowserType();
        
        // 快速路径：检查当前浏览器实例是否有效（无锁）
        Browser currentBrowser = browserInstances.get(currentConfig);
        if (currentBrowser != null && currentBrowser.isConnected()) {
            // 浏览器已存在且连接正常，检查是否需要切换
            String[] configParts = currentConfig.split("_");
            String currentBrowserType = configParts.length > 0 ? configParts[0] : "chromium";
            
            if (!currentBrowserType.equalsIgnoreCase(desiredBrowserType)) {
                return handleBrowserTypeSwitch(currentConfig, currentBrowser, currentBrowserType, desiredBrowserType);
            }
            
            return currentBrowser;
        }
        
        // 慢速路径：浏览器不存在或断开，加锁创建
        synchronized (BROWSER_LOCK) {
            // 双重检查：另一个线程可能已在等待期间创建了浏览器
            currentBrowser = browserInstances.get(currentConfig);
            if (currentBrowser != null && currentBrowser.isConnected()) {
                return currentBrowser;
            }
            
            logger.info("[getBrowser] Browser not initialized yet, initializing with desired type: {}", desiredBrowserType);
            
            // 如果 currentConfig 中的浏览器类型与期望类型不同，更新 configId
            String[] configParts = currentConfig.split("_");
            String configBrowserType = configParts.length > 0 ? configParts[0] : "chromium";
            
            if (!configBrowserType.equalsIgnoreCase(desiredBrowserType)) {
                // 生成新的 configId（使用期望的浏览器类型）
                String newConfigId = generateConfigId();
                logger.info("[getBrowser] Updating configId from {} to {} for browser type: {}",
                    currentConfig, newConfigId, desiredBrowserType);
                currentConfigId.set(newConfigId);
                currentConfig = newConfigId;
            }
            
            // 初始化浏览器
            initializeBrowser(currentConfig);
            
            return browserInstances.get(currentConfig);
        }
    }

    /**
     * 处理浏览器类型切换逻辑
     *
     * <p>⭐ 锁安全设计：closePage/closeContext 在 BROWSER_LOCK 之外执行，
     * 避免 BROWSER_LOCK → PAGE_LOCK → CONTEXT_LOCK 与 getPage() 的
     * PAGE_LOCK → CONTEXT_LOCK 形成死锁链。
     */
    private static Browser handleBrowserTypeSwitch(String currentConfig, Browser currentBrowser,
                                                    String currentBrowserType, String desiredBrowserType) {
        logger.info("[getBrowser] Browser type changed: {} -> {}", currentBrowserType, desiredBrowserType);
        logger.info("[getBrowser] Switching browser...");

        // ⭐ 1. 在 BROWSER_LOCK 之外关闭旧 Context 和 Page（避免死锁）
        closePage();
        closeContext();

        // ⭐ 2. 在 BROWSER_LOCK 内关闭旧浏览器 + 初始化新浏览器
        synchronized (BROWSER_LOCK) {
            // 关闭旧浏览器
            Browser oldBrowser = browserInstances.get(currentConfig);
            if (oldBrowser != null && oldBrowser.isConnected()) {
                logger.info("[getBrowser] Closing old browser: {}", currentBrowserType);
                try {
                    oldBrowser.close();
                } catch (Exception e) {
                    logger.warn("[getBrowser] Error closing old browser: {}", e.getMessage());
                }
                browserInstances.remove(currentConfig);
            }

            // 生成新的 configId
            String newConfigId = generateConfigId();
            logger.info("[getBrowser] New configId: {}", newConfigId);

            // 更新 currentConfigId
            currentConfigId.set(newConfigId);

            // 初始化新浏览器
            initializeBrowser(newConfigId);

            return browserInstances.get(newConfigId);
        }
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
                
                // 【Context 生命周期钩子】通知规则管理器 Context 已重建，需要重绑规则
                ContextLifecycleHookManager.onContextRebuilt(context);
            }
        }
        return context;
    }

    /**
     * 调度 Context 重建（立即生效机制）
     * <p>
     * 当设置自定义配置时调用此方法，立即关闭现有的 Page 和 Context
     * 下次 getContext() 或 getPage() 时会使用新配置创建全新的 Context
     * 多次连续 set 只会触发一次关闭（因为 Context 已不存在）
     */
    static void scheduleContextRebuild() {
        // 先关闭 Page
        Page existingPage = pageThreadLocal.get();
        if (existingPage != null && !existingPage.isClosed()) {
            try {
                LoggingConfigUtil.logInfoIfVerbose(logger, "Closing existing page for context rebuild");
                existingPage.close();
            } catch (Exception e) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to close existing page: {}", e.getMessage());
            }
        }
        pageThreadLocal.remove();

        // 立即关闭 Context（如果有），确保新配置立即生效
        BrowserContext existingContext = contextThreadLocal.get();
        if (existingContext != null) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Closing existing context to apply new custom configurations...");

            // 【Context 生命周期钩子】通知规则管理器 Context 即将重建
            ContextLifecycleHookManager.onContextAboutToRebuild(existingContext);

            try {
                if (existingContext.browser() != null && existingContext.browser().isConnected()) {
                    existingContext.close();
                }
            } catch (Exception e) {
                logger.warn("Failed to close existing context: {}", e.getMessage());
            } finally {
                contextThreadLocal.remove();
            }
            LoggingConfigUtil.logInfoIfVerbose(logger, "Context closed, new context will be created with updated configurations on next access");
        }

        // customContextOptionsFlag 已经在各个 setCustom*() 方法中被设置为 true
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
            
            // 【Context 生命周期钩子】通知规则管理器 Context 即将重建，需要捕获规则
            ContextLifecycleHookManager.onContextAboutToRebuild(existingContext);
            
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
     * 设置自定义代理启用开关（ThreadLocal 覆盖，优先于配置文件）
     * 自定义配置优先于框架默认配置
     * <p>
     * 调用此方法会自动启用自定义配置模式（setCustomContextOptionsFlag(true)）
     * 配置会在下一次创建 Context 时生效
     *
     * @param enabled 是否启用代理（true=启用，false=禁用）
     */
    public static void setCustomProxyEnabled(Boolean enabled) {
        customProxyEnabled.set(enabled);
        customContextOptionsFlag.set(true); // 自动启用自定义配置
        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom proxyEnabled set: {} (custom context options auto-enabled)", enabled);
        scheduleContextRebuild();
    }

    /**
     * 设置当前线程的 Page（用于 BasePage 切换页面后同步，不触发创建）。
     */
    public static void setPage(Page page) {
        if (page != null && !page.isClosed()) {
            pageThreadLocal.set(page);
        } else {
            logger.warn("[PlaywrightManager] setPage() ignored: page is {}",
                    page == null ? "null" : "closed");
        }
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

        // 先检查是否已有有效 Page（快速路径，避免不必要的锁竞争）
        Page page = pageThreadLocal.get();
        if (page != null && !page.isClosed()) {
            return page;
        }

        // 【关键】统一在锁内创建 Page，避免锁外创建 + 锁内再创建导致资源泄漏
        synchronized (PAGE_LOCK) {
            page = pageThreadLocal.get();
            if (page == null || page.isClosed()) {
                BrowserContext context = getContext();
                page = createPage(context);
                pageThreadLocal.set(page);

                // 【Page 生命周期钩子】通知规则管理器 Page 已创建，需要绑定 Page 级别规则
                ContextLifecycleHookManager.rebindRulesToPage(page);
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
     * 统一清理所有 ThreadLocal 变量（防止线程复用时引用过期对象导致内存泄漏）
     * 集中管理所有 ThreadLocal（见顶部 "ThreadLocal 变量" 统一声明区块），避免遗漏
     * <p>
     * ⭐ currentConfigId 不在此清除：Browser 整个测试生命周期只创建一次，
     * currentConfigId 标识 Browser 配置，必须持续存活直到 cleanupAll() 彻底清理。
     *
     * @param clearContextAndPage 是否同时清理 Context 和 Page ThreadLocal（true=清理，false=仅自定义配置）
     */
    private static void cleanupThreadLocals(boolean clearContextAndPage) {
        if (clearContextAndPage) {
            // 清理 Context 和 Page ThreadLocal（currentConfigId 持续存活，保证 Browser 复用）
            pageThreadLocal.remove();
            contextThreadLocal.remove();
        }
        // 始终清理自定义配置 ThreadLocal（13 个）
        customContextOptionsFlag.remove();
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
        customProxyEnabled.remove();
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
        }

        // 统一清理所有 ThreadLocal（含 Context/Page，Scenario 模式需要完全隔离）
        cleanupThreadLocals(true);

        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom context options reset completed");
    }

    /**
     * Scenario 模式下重置自定义配置（保留 Context 实例不复用）
     * 与 resetCustomContextOptions() 的区别：
     * - 本方法保留 contextThreadLocal 中的 Context 引用
     * - 清除所有自定义配置 ThreadLocal（确保下一个 scenario 不受污染）
     * - Context 本身的状态（cookies/storage）由 cleanupContextState() 单独清理
     */
    private static void resetCustomContextOptionsForScenarioMode() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Resetting custom context options for Scenario mode (preserving Context)...");

        // 使用 false：清除自定义配置但不移除 Context/Page ThreadLocal
        // Page ThreadLocal 已由 closePage() 清理，这里确保不误删 Context
        cleanupThreadLocals(false);
        // closePage() 已经清除了 pageThreadLocal，再确保一次
        pageThreadLocal.remove();

        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom context options reset completed (Context preserved)");
    }

    /**
     * Feature 模式下重置自定义配置（保留 Session 相关配置）
     * 只重置运行时配置，不重置影响 Context 创建的配置（如 customStorageStatePath）
     * 确保 Context 可以复用 Session 缓存
     */
    private static void resetCustomContextOptionsForFeatureMode() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Resetting custom context options for Feature mode (preserving session config)...");

        // 保留 Session 相关配置
        Path preservedStorageStatePath = customStorageStatePath.get();

        // 统一清理所有自定义配置 ThreadLocal（不清理 Context/Page/ConfigId）
        cleanupThreadLocals(false);

        // 恢复 Session 相关配置
        if (preservedStorageStatePath != null) {
            customStorageStatePath.set(preservedStorageStatePath);

            // 检查 Context 是否存在，如果不存在，设置 flag 以应用 storage state
            BrowserContext existingContext = contextThreadLocal.get();
            if (existingContext == null || (existingContext.browser() != null && !existingContext.browser().isConnected())) {
                customContextOptionsFlag.set(true);
                LoggingConfigUtil.logDebugIfVerbose(logger, "Feature mode: context is null/closed, set flag to apply storage state");
            } else {
                LoggingConfigUtil.logDebugIfVerbose(logger, "Feature mode: context exists, not setting flag (session already applied)");
            }
        }

        LoggingConfigUtil.logInfoIfVerbose(logger, "Custom context options reset completed (Feature mode)");
    }

    /**
     * 强化页面稳定化：确保页面窗口大小稳定，防止缩放行为
     * 此方法供 PlaywrightContextManager 调用
     */
    static void stabilizePage(Page page) {
        try {
            LoggingConfigUtil.logDebugIfVerbose(logger, "页面稳定化：确保窗口大小正确...");

            // 性能优化：快速等待页面DOM加载完成（可配置）
            int stabilizeWaitTimeout = config().getStabilizeTimeout();
            LoadState loadState = getConfiguredLoadState();
            try {
                page.waitForLoadState(loadState, new Page.WaitForLoadStateOptions().setTimeout(stabilizeWaitTimeout));
            } catch (Exception e) {
                LoggingConfigUtil.logDebugIfVerbose(logger, "页面加载等待超时（LoadState: {}），继续稳定化: {}", loadState, e.getMessage());
            }


            // 检查是否使用 --start-maximized
            String maximizeArgs = config().getWindowMaximizeArgs();
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
     * 使用 getContext()/createPage() 确保自定义配置和稳定化流程被执行
     */
    public static void createNewContextAndPage() {
        closePage();
        closeContext();

        // 通过 getContext() 而非直接 createContext()，确保自定义配置（如 storageState）被正确应用
        BrowserContext context = getContext();
        contextThreadLocal.set(context);

        // 通过 createPage() 而非直接 context.newPage()，确保页面稳定化流程被执行
        Page page = createPage(context);
        pageThreadLocal.set(page);

        LoggingConfigUtil.logDebugIfVerbose(logger, "New Context and Page created for thread: {}", Thread.currentThread().threadId());
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
     * 检查当前线程是否有存活的 Context（不创建新 Context）
     * <p>
     * 与 getContext() 的区别：getContext() 在 Context 不存在时会创建新的，
     * 本方法仅检查存在性，用于 SessionManager 判断是否已有活跃的 Context
     *
     * @return true 如果 ThreadLocal 中有存活的 Context
     */
    public static boolean hasContext() {
        BrowserContext context = contextThreadLocal.get();
        return context != null && context.browser() != null && context.browser().isConnected();
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
            currentConfigId.set(newConfigId);

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

        // 关闭所有浏览器实例（每个 try-catch 独立保护，防止单个失败阻断后续清理）
        for (Browser browser : new ArrayList<>(browserInstances.values())) {
            if (browser != null && browser.isConnected()) {
                try {
                    browser.close();
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Browser instance closed");
                } catch (Exception e) {
                    logger.warn("Error closing browser instance during cleanupAll: {}", e.getMessage());
                }
            }
        }
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

        // 统一清理所有 ThreadLocal（防止线程复用/线程池场景下的内存泄漏）
        cleanupThreadLocals(true);
        // ⭐ 最终清理：Browser 已关闭，currentConfigId 可以安全清除
        currentConfigId.remove();

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
                        boolean exited = process.waitFor(5, TimeUnit.SECONDS);
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

        if (!frameworkState.isInitialized() || currentConfigId.get() == null) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }


        // 根据配置决定是否复用 Context/Page
        String restartBrowserForEach = config().getRestartStrategy();

        if ("scenario".equalsIgnoreCase(restartBrowserForEach)) {
            // ⭐ Scenario 模式：默认关闭 Context → 每个 Scenario 独立全新 Context
            //    仅当业务层使用 SessionManager 时才复用 Context（避免重复新窗口）
            PageObjectFactory.clearAll();
            BrowserContext existingContext = contextThreadLocal.get();
            if (existingContext != null && existingContext.browser() != null && existingContext.browser().isConnected() && SessionManager.isAnyFeatureSessionRestored()) {
                // Context 存活 + SessionManager 已使用 → 复用，只关闭 Page
                closePage();
                LoggingConfigUtil.logDebugIfVerbose(logger, " Scenario initialization completed (reusing existing Context with SessionManager)");
            } else {
                // Context 不可用 或 业务层未使用 SessionManager → 关闭残留后延迟重建
                closePage();
                closeContext();
                LoggingConfigUtil.logDebugIfVerbose(logger, " Scenario initialization completed (Context will rebuild on demand)");
            }
        } else {
            // Feature 模式：复用现有的 Context/Page（如果存在）
            // 同 feature 内 context 持续存活；不同 feature 已在 cleanupForFeature 中关闭
            BrowserContext existingContext = contextThreadLocal.get();
            Page existingPage = pageThreadLocal.get();

            if (existingContext != null && existingPage != null && !existingPage.isClosed()) {
                LoggingConfigUtil.logDebugIfVerbose(logger, " Scenario initialization completed (reusing existing Context/Page within same feature)");
            } else {
                // Context/page 不可用（首次 scenario 或跨 feature）：关闭残留 → 延迟重建
                PageObjectFactory.clearAll();
                closePage();
                closeContext();
                LoggingConfigUtil.logDebugIfVerbose(logger, " Scenario initialization completed (Context closed, will rebuild on demand)");
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

        // 清理临时截图目录（每个 scenario 结束后清理，避免残留累积）
        cleanupTempScreenshots();

        // 清理临时下载目录（每个 scenario 结束后清理，避免下载文件跨用例累积）
        cleanupTempDownloads();

        // 清除 AutoBrowser 处理状态和浏览器覆盖配置
        AutoBrowserProcessor.clearProcessingState();

        // 根据配置决定是否重置自定义配置和关闭 Context/Page
        String restartStrategy = config().getRestartStrategy();

        if ("scenario".equalsIgnoreCase(restartStrategy)) {
            // ⭐ Scenario 模式：关闭 Page + Context → 下一个 Scenario 重建全新 Context（加载缓存 storageState）
            // Scenario 之间独立：每个 Scenario 都有自己的 Context（新窗口），通过 storageState 恢复登录态
            LoggingConfigUtil.logDebugIfVerbose(logger, "Restart strategy is 'scenario' - closing Context for fresh rebuild with cached storageState");
            closePage();
            closeContext();
            // 重置自定义配置（Context 已关闭，无需保留）
            resetCustomContextOptionsForScenarioMode();

            // 【关键】Scenario 模式：重置 Feature 级别 Session 缓存，确保下一个 scenario 重新登录
            SessionManager.resetFeatureSession();
        } else {
            // Feature 模式：不关闭 Context/Page，让下一个 scenario 复用
            // 也不重置自定义配置（保留 Session 状态，确保 Context 可以复用缓存）

            // ⭐ 如果业务层未使用 SessionManager（无 restore/save 调用）
            //   → Context 中的 Cookie 残留会导致下一 Scenario 登录流程异常
            //   → 必须销毁 Context，下个 Scenario 重建全新 Context
            if (!SessionManager.isAnyFeatureSessionRestored()) {
                LoggingConfigUtil.logInfoIfVerbose(logger,
                        "Feature mode: No session restored via SessionManager in this Feature "
                                + "— closing Context to avoid cookie contamination");
                closePage();
                closeContext();
                // note: cleanupPageState/FeatureMode option reset skipped — context is destroyed anyway
            } else {
                LoggingConfigUtil.logDebugIfVerbose(logger, "Restart strategy is 'feature' - keeping Context and Page for reuse");
                // 【优化】Feature 模式：智能重置自定义配置，保留 Session 相关配置
                resetCustomContextOptionsForFeatureMode();
                // 只清理页面状态，不关闭 Context/Page
                cleanupPageState();
            }
            // 【关键】Feature 模式：不重置 Feature 级别 Session 缓存，让下一个 scenario 复用
        }
    }



    /**
     * 清理页面状态（但不关闭 Context/Page）
     * 用于 Feature 模式下，在 scenario 之间复用 Context/Page
     * <p>
     * 策略：不清理 Cookie（维持登录状态），只清理缓存
     * - 保留所有 Cookie（包括 Session Cookie）
     * - 清理 LocalStorage/SessionStorage（确保测试独立性）
     * - 清理页面缓存和监听器
     * - ⭐ 关闭多余页面标签，确保下一个 Scenario 只有一个 tab
     * <p>
     * 优点：简单可靠，不依赖 Cookie 识别逻辑
     */
    public static void cleanupPageState() {
        Page page = pageThreadLocal.get();
        BrowserContext context = contextThreadLocal.get();

        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Cleaning up page state (preserving all cookies)...");

            // ⭐ 关闭多余页面标签（Scenario 间复用 Context/Page 时确保只有 1 个 tab）
            //    上一个 Scenario 可能通过 switchNewPage 打开了新 tab
            if (context != null) {
                try {
                    java.util.List<Page> allPages = context.pages();
                    int pageCount = allPages.size();
                    if (pageCount > 1) {
                        LoggingConfigUtil.logInfoIfVerbose(logger,
                                "Closing {} extra page(s) — keeping only main page (index 0)", pageCount - 1);
                        for (int i = pageCount - 1; i >= 1; i--) {
                            Page extraPage = allPages.get(i);
                            try {
                                if (!extraPage.isClosed()) {
                                    extraPage.close();
                                    LoggingConfigUtil.logDebugIfVerbose(logger, "Closed extra page at index {}", i);
                                }
                            } catch (Exception e) {
                                LoggingConfigUtil.logWarnIfVerbose(logger,
                                        "Failed to close extra page at index {}: {}", i, e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    LoggingConfigUtil.logWarnIfVerbose(logger,
                            "Error closing extra pages during cleanupPageState: {}", e.getMessage());
                }
            }

            // 确保 page 引用指向第一个（唯一）页面
            if (context != null) {
                java.util.List<Page> allPages = context.pages();
                if (!allPages.isEmpty()) {
                    Page mainPage = allPages.get(0);
                    if (page != mainPage && !mainPage.isClosed()) {
                        LoggingConfigUtil.logInfoIfVerbose(logger,
                                "Resetting page reference to main page (page was pointing to a now-closed tab)");
                        page = mainPage;
                        setPage(mainPage);
                    }
                } else {
                    // 所有页面都被关闭了 → 重建新的 Page
                    LoggingConfigUtil.logInfoIfVerbose(logger, "No pages left in context, creating new Page");
                    page = PlaywrightContextManager.createPage(context);
                    setPage(page);
                }
            }

            // 只清理 LocalStorage 和 SessionStorage，不清理 Cookie
            if (page != null && !page.isClosed()) {
                cleanupPageStorage(page);
            }

            LoggingConfigUtil.logInfoIfVerbose(logger, "Page state cleaned up (all cookies preserved, extra tabs closed)");

        } catch (Exception e) {
            logger.warn("Failed to cleanup page state: {}", e.getMessage());
        }
    }


    /**
     * 清理页面存储（LocalStorage、SessionStorage、缓存）
     *
     * @param page Page
     */
    private static void cleanupPageStorage(Page page) {
        if (page == null || page.isClosed()) {
            return;
        }

        try {
            // 清理 LocalStorage
            page.evaluate("() => { try { localStorage.clear(); } catch(e) {} }");

            // 清理 SessionStorage
            page.evaluate("() => { try { sessionStorage.clear(); } catch(e) {} }");

            // 清理页面缓存
            page.evaluate("() => { " +
                "try { " +
                "  if (window.performance && window.performance.clearResourceTimings) " +
                "    window.performance.clearResourceTimings(); " +
                "} catch(e) {} " +
                "}");

            // 清理页面监听器和超时
            page.evaluate("() => { " +
                "try { " +
                "  if (window._timeouts) window._timeouts.forEach(t => clearTimeout(t)); " +
                "  if (window._intervals) window._intervals.forEach(t => clearInterval(t)); " +
                "} catch(e) {} " +
                "}");

        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger,
                "Failed to cleanup page storage: {}", e.getMessage());
        }
    }


    /**
     * 深度清理 Context 状态（不关闭 Context 实例）
     * 用于 Scenario 模式：复用 Context 但清除其内部状态，避免 browser.newContext() 弹出新窗口
     * <p>
     * 清理内容：
     * - 清除所有 Cookies（等价于全新 Context 的无 cookie 状态）
     * - 清除所有 Permissions（地理位置、通知等授权）
     * <p>
     * 注意：不清理 Context 级别的 StorageState 文件绑定，这由 SessionManager 控制
     *
     * @param context 要清理的 BrowserContext
     */
    private static void cleanupContextState(BrowserContext context) {
        if (context == null) {
            return;
        }

        try {
            // 清除所有 cookies（下一个 scenario 将处于无 cookie 的初始状态）
            context.clearCookies();

            // 清除所有权限
            context.clearPermissions();

            LoggingConfigUtil.logInfoIfVerbose(logger, "Context state deep-cleaned: cookies and permissions cleared (Context instance reused)");
        } catch (Exception e) {
            logger.warn("Failed to deep-clean context state: {}", e.getMessage());
        }
    }

    /**
     * 直接向现有 Context 注入 storageState JSON 中的 Cookies（避免 Context 重建）
     * <p>
     * 使用场景：Scenario 模式 + 缓存登录
     * - cleanupContextState() 已清除 cookies
     * - 本方法从 storageState JSON 文件解析 cookies 并注入到复用的 Context
     * - 不触发 browser.newContext()，不弹出新窗口
     * <p>
     * 与 createContext(setStorageStatePath(...)) 的区别：
     * - 本方法：直接 context.addCookies() → 不重建 Context → 始终 1 个窗口
     * - createContext 重建方式：browser.newContext() → 新建 Context → 新窗口
     * <p>
     * 注意：localStorage 需要 Page 先导航到对应域名后才能注入，cookies 通常已足够恢复登录态
     *
     * @param context           现有的 BrowserContext（不会被关闭或替换）
     * @param storageStatePath  Playwright storageState JSON 文件路径
     */
    public static void applyStorageStateToExistingContext(BrowserContext context, Path storageStatePath) {
        if (context == null || storageStatePath == null || !Files.exists(storageStatePath)) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "applyStorageStateToExistingContext skipped: context={}, storagePath={}, exists={}",
                    context != null, storageStatePath, storageStatePath != null && Files.exists(storageStatePath));
            return;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(storageStatePath.toFile());
            JsonNode cookiesNode = root.get("cookies");

            if (cookiesNode == null || !cookiesNode.isArray() || cookiesNode.size() == 0) {
                LoggingConfigUtil.logDebugIfVerbose(logger, "No cookies found in storageState file: {}", storageStatePath);
                return;
            }

            List<Cookie> cookies = new ArrayList<>();
            for (JsonNode cookieNode : cookiesNode) {
                if (!cookieNode.has("name") || !cookieNode.has("value")) {
                    continue;
                }

                String name = cookieNode.get("name").asText();
                String value = cookieNode.get("value").asText();
                Cookie cookie = new Cookie(name, value);

                if (cookieNode.has("domain") && !cookieNode.get("domain").isNull()) {
                    cookie.setDomain(cookieNode.get("domain").asText());
                }
                if (cookieNode.has("path") && !cookieNode.get("path").isNull()) {
                    cookie.setPath(cookieNode.get("path").asText());
                }
                if (cookieNode.has("expires") && !cookieNode.get("expires").isNull()) {
                    cookie.setExpires(cookieNode.get("expires").asDouble());
                }
                if (cookieNode.has("httpOnly")) {
                    cookie.setHttpOnly(cookieNode.get("httpOnly").asBoolean());
                }
                if (cookieNode.has("secure")) {
                    cookie.setSecure(cookieNode.get("secure").asBoolean());
                }
                if (cookieNode.has("sameSite") && !cookieNode.get("sameSite").isNull()) {
                    try {
                        cookie.setSameSite(SameSiteAttribute.valueOf(cookieNode.get("sameSite").asText().toUpperCase()));
                    } catch (IllegalArgumentException ignored) {
                        // 忽略无法识别的 sameSite 值，使用默认值
                    }
                }

                cookies.add(cookie);
            }

            context.addCookies(cookies);

            LoggingConfigUtil.logInfoIfVerbose(logger,
                    "Applied {} cookies from storageState to existing Context (no Context rebuild, no new window)",
                    cookies.size());
        } catch (Exception e) {
            logger.warn("Failed to apply storageState to existing context: {} - {}", storageStatePath, e.getMessage());
        }
    }

    /**
     * Feature 级别的初始化
     * 每个 feature 开始时由 FrameworkCore 调用
     */
    public static void initializeForFeature() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Initializing for feature...");

        if (!frameworkState.isInitialized() || currentConfigId.get() == null) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }

        // 【关键】重置 Feature 级别 Session 缓存，确保新 Feature 重新登录
        SessionManager.resetFeatureSession();

        // 【优化】Feature 模式：预先创建 Context，确保整个 Feature 只创建一次
        String restartStrategy = config().getRestartStrategy();
        if ("feature".equalsIgnoreCase(restartStrategy)) {
            // 预先创建 Context（如果不存在），确保后续 scenario 复用同一个 Context
            BrowserContext context = contextThreadLocal.get();
            if (context == null || (context.browser() != null && !context.browser().isConnected())) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "Feature mode: pre-creating Context for feature-level reuse");
                // 不立即创建，延迟到第一个 scenario 需要时
            } else {
                LoggingConfigUtil.logInfoIfVerbose(logger, "Feature mode: Context already exists, will be reused across scenarios");
            }
        }
        LoggingConfigUtil.logInfoIfVerbose(logger, " Feature initialization completed");
    }

    /**
     * Feature 级别的清理
     * 每个 feature 结束时调用
     * <p>
     * ⭐ 设计原则：整个测试生命周期中 Browser 和 Context 都只创建一次，
     * 由 JVM Shutdown Hook 最终关闭。Feature/Scenario 边界只清理 Page 和 Context 内部状态（Cookie/Storage），
     * Browser 和 Context 实例持续复用，保证只有 1 个 Chrome 窗口。
     * <p>
     * 不重置 frameworkState，确保下一个 Feature 的 beforeTest() → initializeForScenario()
     * 能复用现有 Browser 和 Context。
     */
    public static void cleanupForFeature() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Cleaning up for feature - closing Context (different feature requires fresh Context)...");
        closePage();
        closeContext();
        // ⭐ 跨 Feature 必须重置 Session 缓存，防止 ThreadLocal 残留导致下一个 Feature
        //    误判 isFeatureSessionRestored()=true（Context 已关闭但缓存标记未清）
        SessionManager.resetFeatureSession();
        LoggingConfigUtil.logInfoIfVerbose(logger, "Feature cleanup completed — Browser persists, Context+Page+Session cache cleared for next feature rebuild");
    }

    // ==================== 截图方法 ====================

    /**
     * 截图前页面稳定化（解决截图残留/底部重复问题，以及长页面懒加载高度不准问题）
     * 先滚到底部触发懒加载，再滚回顶部，确保 scrollHeight 准确
     */
    private static void stabilizeBeforeScreenshot(Page page) {
        try {
            // 先滚到底部触发所有懒加载内容，再滚回顶部作为截图起点
            page.evaluate("() => {"
                    + "  window.scrollTo(0, document.body.scrollHeight);"
                    + "  window.scrollTo(0, 0);"
                    + "}");
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Screenshot stabilization failed: {}", e.getMessage());
        }
    }

    /**
     * 截图后恢复页面状态
     * 仅在处理了固定元素时才执行恢复（性能优化）
     */
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
     * 生成系统级唯一标识，完全不依赖人为命名的scenario名称
     * 使用AtomicLong保证跨线程唯一，每次调用返回不同值，确保截图文件名绝对唯一
     */
    private static final AtomicLong screenshotIdGenerator = new AtomicLong(0);
    private static String getScenarioIdentifier() {
        return Thread.currentThread().threadId() + "_" + screenshotIdGenerator.incrementAndGet();
    }

    /**
     * 截图并返回截图文件路径
     */
    public static String takeScreenshot(String title) {
        try {
            Page page = pageThreadLocal.get();
            if (page == null || page.isClosed()) {
                return null;
            }

            // 1. 目录（Serenity 标准）
            Path screenshotDir = Paths.get("target/site/serenity");
            Files.createDirectories(screenshotDir);

            // 唯一文件名：使用系统级唯一ID，不依赖人为命名
            String uniqueId = getScenarioIdentifier();
            String uniqueSource = title + "_" + uniqueId + "_" + System.currentTimeMillis();
            String sha256 = generateHash(uniqueSource);
            String screenshotName = sha256 + ".png";
            Path screenshotPath = screenshotDir.resolve(screenshotName);

            // 清理残留截图文件（解决文件锁定或残留问题）
            try {
                if (Files.exists(screenshotPath)) {
                    Files.deleteIfExists(screenshotPath);
                }
            } catch (Exception e) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to delete existing screenshot: {}", e.getMessage());
            }

            // 截图前稳定化（解决截图残留/底部重复问题）
            stabilizeBeforeScreenshot(page);

            // 页面等待：先等 load 事件，再等网络空闲（确保异步加载的左侧菜单栏已渲染）
            int screenshotWaitTimeout = config().getScreenshotTimeout();
            try {
                page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(screenshotWaitTimeout));
            } catch (Exception e) {
                LoggingConfigUtil.logDebugIfVerbose(logger, "Screenshot wait timeout ({}ms) - continuing: {}", screenshotWaitTimeout, e.getMessage());
            }

            // 截图：全页模式使用动态 clip 按实际内容尺寸截图，viewport 模式保持原样
            boolean fullPage = config().isFullPageScreenshot();
            Page.ScreenshotOptions options = new Page.ScreenshotOptions()
                    .setOmitBackground(false)
                    .setTimeout((long) config().getScreenshotTimeout())
                    .setAnimations(ScreenshotAnimations.DISABLED)
                    .setPath(screenshotPath);

            if (fullPage) {
                // 全页截图：Playwright 原生 fullPage，自动滚动拼接
                // 先滚到底部触发懒加载，等渲染完成后滚回顶部
                page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
                page.waitForTimeout(300.0); // 等待懒加载内容渲染
                page.evaluate("() => window.scrollTo(0, 0)");
                options.setFullPage(true);
            } else {
                options.setFullPage(false);
            }

            page.screenshot(options);

            LoggingConfigUtil.logDebugIfVerbose(logger, "Screenshot saved: {}", screenshotPath);
            return screenshotPath.toString();

        } catch (Exception e) {
            logger.error("Failed to take screenshot", e);
            return null;
        }
    }


    /**
     * 截图并返回截图文件（用于页面变化检测等场景）。
     * <p>委托 {@link #takeScreenshot(String)} 避免重复实现，
     * 仅包装路径 → File 转换。
     */
    public static File takeScreenshotWithReturn(String title) {
        String path = takeScreenshot(title);
        if (path == null) {
            return null;
        }
        return new File(path);
    }

    // ==================== 配置访问方法（封装层） ====================

    /**
     * 获取浏览器类型
     * 优先使用测试用例级别的覆盖配置（如果存在）
     *
     * @return 浏览器类型
     */
    /**
     * 获取浏览器类型（委托给 PlaywrightConfigManager）
     */
    public static String getBrowserType() {
        return config().getBrowserType();
    }

    // ==================== 配置访问（通过 config() 代理到 PlaywrightConfigManager） ====================
    // 使用 PlaywrightManager.config().getXXX() 或 PlaywrightConfigManager.config().getXXX() 访问配置

    // ==================== 公共访问方法（用于其他类访问自定义配置） ====================

    /**
     * 获取自定义 Context 选项标志
     */
    static ThreadLocal<Boolean> getCustomContextOptionsFlag() {
        return customContextOptionsFlag;
    }

    /**
     * 获取自定义 Context 选项标志的值
     */
    static Boolean getCustomContextOptionsFlagValue() {
        return customContextOptionsFlag.get();
    }

    /**
     * 获取自定义选项管理器（提供 PlaywrightManager.customOptions().getXXX() 风格的 API）
     */
    public static CustomOptionsManager customOptions() {
        return CustomOptionsManager.getInstance();
    }

    /**
     * 获取配置管理器（提供 PlaywrightManager.config().getXXX() 风格的 API）
     */
    public static PlaywrightConfigManager config() {
        return PlaywrightConfigManager.config();
    }

    // ==================== 包内方法（供 CustomOptionsManager 访问 ThreadLocal） ====================

    static Path getCustomStorageStatePath() {

        return customStorageStatePath.get();
    }

    static String getCustomLocale() {
        return customLocale.get();
    }

    static String getCustomTimezoneId() {
        return customTimezoneId.get();
    }

    static String getCustomUserAgent() {
        return customUserAgent.get();
    }

    static List<String> getCustomPermissions() {
        return customPermissions.get();
    }

    static Geolocation getCustomGeolocation() {
        return customGeolocation.get();
    }

    static Integer getCustomDeviceScaleFactor() {
        return customDeviceScaleFactor.get();
    }

    static Boolean getCustomIsMobile() {
        return customIsMobile.get();
    }

    static Boolean getCustomHasTouch() {
        return customHasTouch.get();
    }

    static ColorScheme getCustomColorScheme() {
        return customColorScheme.get();
    }

    static Integer getCustomViewportWidth() {
        return customViewportWidth.get();
    }

    static Integer getCustomViewportHeight() {
        return customViewportHeight.get();
    }

    static Boolean getCustomProxyEnabled() {
        return customProxyEnabled.get();
    }
}