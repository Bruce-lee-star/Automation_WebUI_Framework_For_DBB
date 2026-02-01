package com.hsbc.cmb.dbb.hk.automation.framework.lifecycle;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.LoadState;
import com.hsbc.cmb.dbb.hk.automation.framework.config.FrameworkConfig;
import com.hsbc.cmb.dbb.hk.automation.framework.config.FrameworkConfigManager;
import com.hsbc.cmb.dbb.hk.automation.framework.core.FrameworkState;
import com.hsbc.cmb.dbb.hk.automation.framework.utils.LoggingConfigUtil;
import com.hsbc.cmb.dbb.hk.automation.screenshot.strategy.ScreenshotStrategy;
import net.thucydides.model.environment.SystemEnvironmentVariables;
import net.thucydides.model.util.EnvironmentVariables;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import net.thucydides.model.steps.ExecutedStepDescription;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hsbc.cmb.dbb.hk.automation.framework.exceptions.BrowserException;
import com.hsbc.cmb.dbb.hk.automation.framework.exceptions.InitializationException;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
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
import java.util.stream.Stream;

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

    // 配置标识
    private static String currentConfigId;

    // 框架状态引用
    private static final FrameworkState frameworkState = FrameworkState.getInstance();

    // ==================== 静态初始化块 ====================

    static {
        // 设置 Playwright 浏览器缓存路径
        initializePlaywrightPaths();
        // 清理旧的 playwright-java 临时目录（防止 C 盘爆满）
        cleanupPlaywrightTempDirs();

        // 检查并安装Playwright 浏览器（如果需要）
        if (!SKIP_DOWNLOAD_BROWSER) {
            ensureBrowsersInstalled();
        }
    }

    /**
     * 初始化 Playwright 路径配置
     */
    private static void initializePlaywrightPaths() {
        String browserPath = System.getProperty("PLAYWRIGHT_BROWSERS_PATH");
        if (browserPath == null || browserPath.trim().isEmpty()) {
            browserPath = DEFAULT_PLAYWRIGHT_BROWSER_PATH;
            System.setProperty("PLAYWRIGHT_BROWSERS_PATH", browserPath);
            LoggingConfigUtil.logInfoIfVerbose(logger, "[static init], set PLAYWRIGHT_BROWSERS_PATH to : {}", browserPath);
        } else {
            LoggingConfigUtil.logInfoIfVerbose(logger, "[static init], PLAYWRIGHT_BROWSERS_PATH already set to : {}", browserPath);
        }

        String driverTmp = System.getProperty("playwright.driver.tmpdir");
        if (driverTmp == null || driverTmp.trim().isEmpty()) {
            driverTmp = DEFAULT_PLAYWRIGHT_DRIVER_PATH;
            System.setProperty("playwright.driver.tmpdir", driverTmp);
            LoggingConfigUtil.logInfoIfVerbose(logger, "[static init], set playwright.driver.tmpdir to : {}", driverTmp);
        } else {
            LoggingConfigUtil.logInfoIfVerbose(logger, "[static init], playwright.driver.tmpdir already set to : {}", driverTmp);
        }

        Path cachePath = null;
        try {
            cachePath = Paths.get(browserPath).toAbsolutePath();
            if (!Files.exists(cachePath)) {
                Files.createDirectories(cachePath);
            }

            cachePath = Paths.get(driverTmp).toAbsolutePath();
            if (!Files.exists(cachePath)) {
                Files.createDirectories(cachePath);
            }

        } catch (Exception e) {
            throw new InitializationException("init playwright cache directory failed! cachePath is : " + cachePath, e);
        }
    }


    /**
     * 确保浏览器已安装
     */
    private static void ensureBrowsersInstalled() {
        try {
            if (SKIP_DOWNLOAD_BROWSER) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Browser download is skipped, skipping installation check");
                return;
            }

            Path cachePath = Paths.get(DEFAULT_PLAYWRIGHT_BROWSER_PATH).toAbsolutePath();
            String configuredBrowserType = getConfiguredBrowserType();

            boolean browsersInstalled = checkBrowsersInstalled(cachePath);
            if (!browsersInstalled) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] {} browser not found in cache, downloading...", configuredBrowserType);
                installBrowsers(cachePath);
            } else {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Playwright {} browser already installed in: {}", configuredBrowserType, cachePath);
            }
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "[Static Init] Failed to check browsers installation", e);
        }
    }

    /**
     * 清理旧的 playwright 临时目录
     * 清理两个位置的临时目录:
     * 1. 项目目录下的 .playwright/cache 中的 playwright-java-* 目录
     * 2. 系统临时目录中的 playwright-artifacts-* 和 playwright-java-* 目录
     */
    private static void cleanupPlaywrightTempDirs() {
        // 清理项目目录中的临时目录
        cleanupPlaywrightTempDirs(DEFAULT_PLAYWRIGHT_BROWSER_PATH);

        // 清理系统临时目录中的 playwright 临时目录
        cleanupPlaywrightTempDirs(System.getProperty("java.io.tmpdir"));
    }

    private static void cleanupPlaywrightTempDirs(String tempDir) {
        try {
            Path tempPath = Paths.get(tempDir);
            LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Starting cleanup of old playwright temp directories in projector system temp directories: {}", tempPath);

            // 查找所有 playwright-java-* 目录
            List<Path> playwrightTempDirs = new ArrayList<>();
            try (Stream<Path> stream = Files.list(tempPath)) {
                stream.filter(path -> {
                            String fileName = path.getFileName().toString();
                            return fileName.startsWith("playwright-java-") && Files.isDirectory(path);
                        })
                        .forEach(playwrightTempDirs::add);
            }

            if (playwrightTempDirs.isEmpty()) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Found 0 playwright-java temp directories in project");
                return;
            }

            LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Found {} playwright-java temp directories in project", playwrightTempDirs.size());

            int cleanedCount = 0;
            int failedCount = 0;
            long totalSize = 0;
            for (Path dir : playwrightTempDirs) {
                try {
                    long size = calculateDirectorySize(dir);
                    totalSize += size;

                    deleteDirectoryRecursively(dir);
                    cleanedCount++;
                } catch (Exception e) {
                    failedCount++;
                    LoggingConfigUtil.logDebugIfVerbose(logger, "Failed to cleanup playwright temp dir: {} - {}", dir.getFileName(), e.getMessage());
                }
            }

            // 只在有成功清理的目录时才输出详细信息
            if (cleanedCount > 0) {
                long sizeMB = totalSize / (1024 * 1024);
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Project cleanup completed: {} directories removed ({} failed), total {} MB freed",
                        cleanedCount, failedCount, sizeMB);
            } else if (failedCount > 0) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] All project directories locked or inaccessible ({} failed), skipping cleanup", failedCount);
            }

        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "[Static Init] Failed to cleanup playwright temp directories in project: {}", e.getMessage());
        }
    }

    /**
     * 递归删除目录（增强版：处理文件锁定问题）
     */
    private static void deleteDirectoryRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        if (Files.isDirectory(path)) {
            try (Stream<Path> stream = Files.list(path)) {
                stream.forEach(child -> {
                    try {
                        deleteDirectoryRecursively(child);
                    } catch (IOException e) {
                        // 只记录简短信息，避免堆栈追踪造成日志噪音
                        String errorMsg = e.getMessage();
                        if (errorMsg != null && errorMsg.contains("AccessDeniedException")) {
                            // 对于文件锁定问题，使用DEBUG级别而不是WARN
                            LoggingConfigUtil.logDebugIfVerbose(logger, "File locked, skipping deletion: {}", child.getFileName());
                        } else {
                            LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to delete child path: {} - {}", child.getFileName(), errorMsg);
                        }
                    }
                });
            }
        }

        // 尝试删除文件或空目录，忽略失败
        try {
            Files.deleteIfExists(path);
        } catch (AccessDeniedException e) {
            // 文件被锁定，这是预期行为（例如node.exe仍在运行）
            LoggingConfigUtil.logDebugIfVerbose(logger, "File locked, cannot delete: {}", path.getFileName());
        } catch (IOException e) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Failed to delete path: {} - {}", path.getFileName(), e.getMessage());
        }
    }

    /**
     * 计算目录大小
     */
    private static long calculateDirectorySize(Path path) throws IOException {
        if (!Files.exists(path)) {
            return 0;
        }

        if (Files.isRegularFile(path)) {
            return Files.size(path);
        }

        long size = 0;
        try (Stream<Path> stream = Files.walk(path)) {
            size = stream
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        }

        return size;
    }

    // ==================== 浏览器安装方法 ====================

    /**
     * 获取配置的浏览器类型
     */
    private static String getConfiguredBrowserType() {
        try {
            String browserType = getBrowserType();
            LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Configured browser type: {}", browserType);
            return browserType;
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "[Static Init] Failed to get browser type config, using default: chromium", e);
            return "chromium";
        }
    }

    /**
     * 检查浏览器是否已安装
     */
    private static boolean checkBrowsersInstalled(Path cachePath) {
        try {
            if (SKIP_DOWNLOAD_BROWSER) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Browser download is skipped, assuming browser is available");
                return true;
            }

            String browserType = getConfiguredBrowserType();
            LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Checking if {} browser is installed...", browserType);

            if (!Files.exists(cachePath)) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Cache path does not exist: {}", cachePath);
                return false;
            }

            // 检查是否有对应的浏览器目录
            // Playwright 安装的浏览器目录格式通常是: ms-playwright-[browserType]-[version]
            boolean browserInstalled = false;
            try (Stream<Path> stream = Files.list(cachePath)) {
                browserInstalled = stream
                        .filter(Files::isDirectory)
                        .anyMatch(p -> {
                            String dirName = p.getFileName().toString();
                            boolean isMatch = dirName.contains("ms-playwright-" + browserType) ||
                                    dirName.contains(browserType + "-") ||
                                    dirName.equalsIgnoreCase(browserType);
                            LoggingConfigUtil.logDebugIfVerbose(logger, "[Static Init] Checking directory: {} -> match: {}", dirName, isMatch);
                            return isMatch;
                        });
            }

            LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Browser {} installed: {}", browserType, browserInstalled);
            return browserInstalled;
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "[Static Init] Failed to check browsers installation", e);
            return false;
        }
    }

    /**
     * 下载 Playwright 浏览器到指定路径
     */
    private static void installBrowsers(Path cachePath) {
        try {
            String browserType = getConfiguredBrowserType();
            LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Downloading Playwright {} browser to: {}", browserType, cachePath);

            ProcessBuilder pb = new ProcessBuilder(
                    "java",
                    "-cp", System.getProperty("java.class.path"),
                    "com.microsoft.playwright.CLI",
                    "install",
                    browserType,  // 只下载配置的浏览器类型
                    "ffmpeg"
            );

            Map<String, String> env = pb.environment();
            // 只有不跳过浏览器下载时才设置PLAYWRIGHT_BROWSERS_PATH
            if (!SKIP_DOWNLOAD_BROWSER) {
                env.put("PLAYWRIGHT_BROWSERS_PATH", cachePath.toString());
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Playwright Install] BROWSERS_PATH: {}", cachePath);
            } else {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Playwright Install] Skipping BROWSERS_PATH configuration because download is skipped");
            }
            // 只有不跳过浏览器下载时才设置PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD
            if (!SKIP_DOWNLOAD_BROWSER) {
                env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "0");
            } else {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Playwright Install] Skipping SKIP_BROWSER_DOWNLOAD configuration because download is skipped");
            }

            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Starting Playwright {} browser download...", browserType);
            Process process = pb.start();

            // 跟踪下载进程,以便在 JVM 退出时终止
            synchronized (downloadProcesses) {
                downloadProcesses.add(process);
            }

            // 在完成后从列表中移除
            try {
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Playwright {} browser downloaded successfully!", browserType);
                } else {
                    LoggingConfigUtil.logWarnIfVerbose(logger, "[Static Init] Playwright {} browser download failed with exit code: {}", browserType, exitCode);
                    LoggingConfigUtil.logWarnIfVerbose(logger, "[Static Init] Browsers will be downloaded on first use");
                }
            } finally {
                synchronized (downloadProcesses) {
                    downloadProcesses.remove(process);
                }
            }
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "[Static Init] Failed to download Playwright browsers", e);
            LoggingConfigUtil.logWarnIfVerbose(logger, "[Static Init] Browsers will be downloaded on first use");
        }
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
     */
    public static synchronized void initialize() {
        if (frameworkState.isInitialized() && currentConfigId != null) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Playwright environment already initialized with config: {}", currentConfigId);
            return;
        }

        String configId = generateConfigId();
        LoggingConfigUtil.logInfoIfVerbose(logger, "Initializing Playwright environment with config: {}", configId);
        initializePlaywright(configId);
        initializeBrowser(configId);
        currentConfigId = configId;

        LoggingConfigUtil.logInfoIfVerbose(logger, "✅ Playwright environment initialized successfully");
    }

    /**
     * 生成配置ID
     */
    private static String generateConfigId() {
        return String.format("%s_%s_%s",
                getBrowserType(),
                isHeadless() ? "headless" : "headed",
                getBrowserChannel());
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

        // 跳过浏览器下载
        if (SKIP_DOWNLOAD_BROWSER) {
            Map<String, String> env = new HashMap<>();
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
            options.setEnv(env);
        }
        return options;
    }

    /**
     * 初始化 Browser 实例
     */
    private static void initializeBrowser(String configId) {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Initializing Browser for config: {}", configId);

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
        String browserType = getBrowserType();
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
        boolean maximizeWindow = isWindowMaximize();
        String maximizeArgs = getWindowMaximizeArgs();
        boolean hasStartMaximized = maximizeArgs.contains("--start-maximized");

        // 获取逻辑屏幕尺寸
        Dimension screenSize = getAvailableScreenSize();
        int screenWidth = (int) screenSize.getWidth();
        int screenHeight = (int) screenSize.getHeight();

        // 构建启动参数
        List<String> args = new ArrayList<>();

        // 添加基础参数（移除强制 DPI 缩放参数）
        args.add("--disable-pinch");
        args.add("--disable-blink-features=AutomationControlled");

        // 添加用户配置的浏览器启动参数
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
            args.add("--window-position=0,0");
            args.add("--window-size=" + screenWidth + "," + screenHeight);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Window maximization enabled, setting window size to: {}x{}", screenWidth, screenHeight);
        }

        // 添加用户配置的窗口参数（除了 --start-maximized）
        String[] maxArgsArray = maximizeArgs.split(",");
        for (String arg : maxArgsArray) {
            if (!arg.trim().isEmpty() && !args.contains(arg.trim()) && !arg.contains("--start-maximized")) {
                args.add(arg.trim());
            }
        }

        // 如果有 --start-maximized，则添加它
        if (hasStartMaximized) {
            args.add("--start-maximized");
        }

        if (!args.isEmpty()) {
            launchOptions.setArgs(args);
            logger.info("Browser args: {}", args);  // 保留浏览器启动参数日志，这很重要
        }

        // 设置浏览器 channel（如果配置了）
        String channel = getBrowserChannel();
        if (channel != null && !channel.isEmpty()) {
            launchOptions.setChannel(channel);
            logger.info("Browser channel: {}", channel);  // 保留浏览器channel日志，这很重要
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
     * 获取 Browser 实例
     */
    public static Browser getBrowser() {
        String configId = getCurrentConfigId();
        if (configId == null) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }

        Browser browser = browserInstances.get(configId);
        if (browser == null || !browser.isConnected()) {
            initializeBrowser(configId);
            browser = browserInstances.get(configId);
        }
        return browser;
    }

    /**
     * 创建并获取 BrowserContext（线程安全）
     */
    public static BrowserContext getContext() {
        if (!frameworkState.isInitialized()) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }

        BrowserContext context = contextThreadLocal.get();
        if (context == null || !context.browser().isConnected()) {
            context = createContext();
            contextThreadLocal.set(context);
        }
        return context;
    }

    /**
     * 获取 Page（线程安全）
     */
    public static Page getPage() {
        if (!frameworkState.isInitialized()) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }

        Page page = pageThreadLocal.get();
        if (page == null || page.isClosed()) {
            page = createPage();
            pageThreadLocal.set(page);
        }
        return page;
    }

    // ==================== Context 和 Page 创建方法 ====================

    /**
     * 创建新的 BrowserContext
     */
    private static BrowserContext createContext() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Creating new BrowserContext...");

        Browser currentBrowser = getBrowser();
        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions();

        // 配置视口大小
        configureViewport(contextOptions);

        // 配置其他 Context 选项
        configureContextOptions(contextOptions);

        BrowserContext context = currentBrowser.newContext(contextOptions);

        // 设置超时
        configureTimeouts(context);

        // 启用 tracing（如果配置了）
        enableTracing(context);

        LoggingConfigUtil.logInfoIfVerbose(logger, "BrowserContext created successfully");
        return context;
    }

    /**
     * 配置视口大小
     */
    private static void configureViewport(Browser.NewContextOptions contextOptions) {
        Dimension screenSize = getAvailableScreenSize();
        int screenWidth = (int) screenSize.getWidth();
        int screenHeight = (int) screenSize.getHeight();

        boolean maximizeWindow = isWindowMaximize();
        String maximizeArgs = getWindowMaximizeArgs();
        boolean hasStartMaximized = maximizeArgs.contains("--start-maximized");

        if (maximizeWindow && !hasStartMaximized) {
            // 使用逻辑分辨率作为 viewport（与浏览器窗口大小一致）
            contextOptions.setViewportSize(screenWidth, screenHeight);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Window maximization enabled, viewport set to logical screen size: {}x{}", screenWidth, screenHeight);
        } else if (!hasStartMaximized) {
            int viewportWidth = getViewportWidth();
            int viewportHeight = getViewportHeight();
            contextOptions.setViewportSize(viewportWidth, viewportHeight);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using configured viewport size: {}x{} (explicit viewport, no maximization)", viewportWidth, viewportHeight);
        } else {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using browser auto-sizing (--start-maximized or no viewport specified)");
        }
    }

    /**
     * 配置 Context 选项
     */
    private static void configureContextOptions(Browser.NewContextOptions contextOptions) {
        // 设置 locale
        String locale = getContextLocale();
        contextOptions.setLocale(locale);

        // 设置 timezone
        String timezoneId = getContextTimezone();
        if (timezoneId != null && !timezoneId.isEmpty()) {
            contextOptions.setTimezoneId(timezoneId);
        }

        // 设置 User Agent
        String userAgent = getContextUserAgent();
        if (userAgent != null && !userAgent.isEmpty()) {
            contextOptions.setUserAgent(userAgent);
        }

        // 设置权限
        String permissionsConfig = getContextPermissions();
        if (permissionsConfig != null && !permissionsConfig.isEmpty()) {
            List<String> permissions = List.of(permissionsConfig.split(","));
            if (!permissions.isEmpty()) {
                contextOptions.setPermissions(permissions);
            }
        }

        // 设置地理位置
        configureGeolocation(contextOptions);

        // 设置设备缩放因子
        configureDeviceScaleFactor(contextOptions);

        // 设置触摸和移动设备标识
        contextOptions.setHasTouch(hasTouch());
        contextOptions.setIsMobile(isMobile());

        // 设置颜色方案
        String colorScheme = getColorScheme();
        contextOptions.setColorScheme(ColorScheme.valueOf(colorScheme.toUpperCase().replace("-", "_")));

        // 设置录屏
        configureVideoRecording(contextOptions);
    }

    /**
     * 配置地理位置
     */
    private static void configureGeolocation(Browser.NewContextOptions contextOptions) {
        String latitudeStr = getGeolocationLatitude();
        String longitudeStr = getGeolocationLongitude();

        if (latitudeStr != null && longitudeStr != null && !latitudeStr.isEmpty() && !longitudeStr.isEmpty()) {
            try {
                double latitude = Double.parseDouble(latitudeStr);
                double longitude = Double.parseDouble(longitudeStr);
                contextOptions.setGeolocation(latitude, longitude);
            } catch (NumberFormatException e) {
                logger.warn("Invalid geolocation values: {}, {}", latitudeStr, longitudeStr);
            }
        }
    }

    /**
     * 配置设备缩放因子
     */
    private static void configureDeviceScaleFactor(Browser.NewContextOptions contextOptions) {
        String deviceScaleFactor = getDeviceScaleFactor();

        if (deviceScaleFactor == null || deviceScaleFactor.trim().isEmpty()) {
            // 自动检测系统 DPI 缩放因子
            double systemDpiScaleFactor = getSystemDpiScaleFactor();
            deviceScaleFactor = String.valueOf(systemDpiScaleFactor);

            LoggingConfigUtil.logInfoIfVerbose(logger, "deviceScaleFactor not configured, using system DPI scale: {} (auto-detected)", systemDpiScaleFactor);
        } else {
            LoggingConfigUtil.logInfoIfVerbose(logger, "deviceScaleFactor manually configured: {}", deviceScaleFactor);
        }

        contextOptions.setDeviceScaleFactor(Double.parseDouble(deviceScaleFactor));
        LoggingConfigUtil.logInfoIfVerbose(logger, "Set device scale factor to: {}", deviceScaleFactor);
    }

    /**
     * 获取系统 DPI 缩放因子
     */
    private static double getSystemDpiScaleFactor() {
        try {
            GraphicsConfiguration gc = GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration();

            AffineTransform transform = gc.getDefaultTransform();
            double scaleX = transform.getScaleX();
            double scaleY = transform.getScaleY();

            // 使用水平和垂直缩放因子的平均值
            double avgScale = (scaleX + scaleY) / 2.0;

            logger.debug("System DPI scale factors: X={}, Y={}, Average: {}", scaleX, scaleY, avgScale);

            return avgScale;
        } catch (Exception e) {
            logger.warn("Failed to get system DPI scale factor, using default 1.0: {}", e.getMessage());
            return 1.0;
        }
    }

    /**
     * 配置视频录制
     * 视频尺寸与 viewport 大小保持一致
     */
    private static void configureVideoRecording(Browser.NewContextOptions contextOptions) {
        if (isRecordVideoEnabled()) {
            String videoDir = getRecordVideoDir();
            Dimension screenSize = getAvailableScreenSize();
            int screenWidth = (int) screenSize.getWidth();
            int screenHeight = (int) screenSize.getHeight();

            // 使用与 configureViewport 相同的逻辑确定视频尺寸
            boolean maximizeWindow = isWindowMaximize();
            String maximizeArgs = getWindowMaximizeArgs();
            boolean hasStartMaximized = maximizeArgs.contains("--start-maximized");

            int videoWidth;
            int videoHeight;

            if (maximizeWindow && !hasStartMaximized) {
                // 使用逻辑分辨率作为视频尺寸（与 viewport 一致）
                videoWidth = screenWidth;
                videoHeight = screenHeight;
                LoggingConfigUtil.logInfoIfVerbose(logger, "Video size set to logical screen size: {}x{}", videoWidth, videoHeight);
            } else if (!hasStartMaximized) {
                // 使用配置的 viewport 尺寸作为视频尺寸
                videoWidth = getViewportWidth();
                videoHeight = getViewportHeight();
                LoggingConfigUtil.logInfoIfVerbose(logger, "Video size set to configured viewport: {}x{}", videoWidth, videoHeight);
            } else {
                // 使用屏幕尺寸作为默认值
                videoWidth = screenWidth;
                videoHeight = screenHeight;
                LoggingConfigUtil.logInfoIfVerbose(logger, "Video size set to screen size (browser auto-sizing): {}x{}", videoWidth, videoHeight);
            }

            contextOptions.setRecordVideoDir(Paths.get(videoDir));
            contextOptions.setRecordVideoSize(videoWidth, videoHeight);
        }
    }

    /**
     * 配置超时
     */
    private static void configureTimeouts(BrowserContext context) {
        int navigationTimeout = getNavigationTimeout();
        int defaultTimeout = getPageTimeout();
        context.setDefaultNavigationTimeout(navigationTimeout);
        context.setDefaultTimeout(defaultTimeout);
    }

    /**
     * 启用 Tracing
     */
    private static void enableTracing(BrowserContext context) {
        if (isTraceEnabled()) {
            context.tracing().start(new Tracing.StartOptions()
                    .setScreenshots(isTraceScreenshots())
                    .setSnapshots(isTraceSnapshots())
                    .setSources(isTraceSources()));
        }
    }

    /**
     * 创建新的 Page
     */
    private static Page createPage() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Creating new Page...");
        BrowserContext context = getContext();
        Page page = context.newPage();

        // 页面稳定化：防止页面加载过程中出现缩放行为
        stabilizePage(page);

        LoggingConfigUtil.logInfoIfVerbose(logger, "Page created successfully");
        return page;
    }

    /**
     * 强化页面稳定化：确保页面窗口大小稳定，防止缩放行为
     */
    private static void stabilizePage(Page page) {
        try {
            LoggingConfigUtil.logDebugIfVerbose(logger, "页面稳定化：确保窗口大小正确...");

            // 性能优化：快速等待页面DOM加载完成（可配置）
            int stabilizeWaitTimeout = getStabilizeWaitTimeout();
            LoadState loadState = getConfiguredLoadState();
            try {
                page.waitForLoadState(loadState, new Page.WaitForLoadStateOptions().setTimeout(stabilizeWaitTimeout));
            } catch (Exception e) {
                LoggingConfigUtil.logDebugIfVerbose(logger, "页面加载等待超时（LoadState: {}），继续稳定化: {}", loadState, e.getMessage());
            }

            // 检查是否使用 --start-maximized
            String maximizeArgs = getWindowMaximizeArgs();
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
            page.setViewportSize(logicalWidth, logicalHeight);

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
     */
    public static void closePage() {
        Page page = pageThreadLocal.get();
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
            } finally {
                pageThreadLocal.remove();
            }
        }
    }

    /**
     * 关闭当前线程的 Context
     */
    public static void closeContext() {
        BrowserContext context = contextThreadLocal.get();
        if (context != null) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Closing BrowserContext...");

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
            } finally {
                contextThreadLocal.remove();
            }
        }
    }

    /**
     * 重启浏览器（用于重跑测试时）
     */
    public static synchronized void restartBrowser() {
        String configId = getCurrentConfigId();
        if (configId == null) {
            logger.warn("Cannot restart browser: configId is null. Browser not initialized.");
            return;
        }

        LoggingConfigUtil.logInfoIfVerbose(logger, "🔄 Restarting browser for config: {}", configId);

        try {
            closePage();
            closeContext();

            Browser browser = browserInstances.get(configId);
            if (browser != null && browser.isConnected()) {
                browser.close();
                browserInstances.remove(configId);
                LoggingConfigUtil.logInfoIfVerbose(logger, "Browser closed for config: {}", configId);
            }

            Playwright playwright = playwrightInstances.get(configId);
            if (playwright != null) {
                try {
                    playwright.close();
                    playwrightInstances.remove(configId);
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Playwright instance closed for config: {}", configId);
                } catch (Exception e) {
                    logger.warn("Error closing Playwright instance: {}", e.getMessage());
                }
            }

            initializePlaywright(configId);
            initializeBrowser(configId);

            LoggingConfigUtil.logInfoIfVerbose(logger, "✅ Browser restarted successfully for config: {}", configId);
        } catch (Exception e) {
            logger.error("Failed to restart browser for config: {}", configId, e);
            throw new BrowserException("Failed to restart browser for config: " + configId, e);
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
        String restartBrowserForEach = getRestartStrategy();

        if ("scenario".equalsIgnoreCase(restartBrowserForEach)) {
            // Scenario 模式：每个 scenario 都创建新的 Context/Page
            createNewContextAndPage();
            LoggingConfigUtil.logDebugIfVerbose(logger, "✅ Scenario initialization completed (new Context/Page created)");
        } else {
            // Feature 模式：复用现有的 Context/Page（如果存在）
            BrowserContext existingContext = contextThreadLocal.get();
            Page existingPage = pageThreadLocal.get();

            if (existingContext != null && existingPage != null && !existingPage.isClosed()) {
                LoggingConfigUtil.logDebugIfVerbose(logger, "✅ Scenario initialization completed (reusing existing Context/Page)");
            } else {
                // 如果不存在或已关闭，则创建新的
                createNewContextAndPage();
                LoggingConfigUtil.logDebugIfVerbose(logger, "✅ Scenario initialization completed (new Context/Page created)");
            }
        }
    }

    /**
     * Scenario 级别的清理
     * 每个 scenario 结束时调用
     */
    public static void cleanupForScenario() {
        LoggingConfigUtil.logDebugIfVerbose(logger, "Cleaning up for scenario...");

        // 根据配置决定是否关闭 Context/Page 和浏览器
        String restartBrowserForEach = getRestartStrategy();

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
                // 清理页面状态，导航到空白页
                LoggingConfigUtil.logDebugIfVerbose(logger, "Cleaning up page state while keeping Context/Page open");

                // 1. 清除页面缓存
                page.evaluate("() => { if (window.performance && window.performance.clearResourceTimings) window.performance.clearResourceTimings(); }");

                // 2. 可选：导航到空白页（如果需要更彻底的清理）
                // page.navigate("about:blank");

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

        LoggingConfigUtil.logInfoIfVerbose(logger, "✅ Feature initialization completed");
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
        String restartBrowserForEach = getRestartStrategy();

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
                int screenshotWaitTimeout = getScreenshotWaitTimeout();
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
                int screenshotWaitTimeout = getScreenshotWaitTimeout();
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
     */
    public static String getBrowserType() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_TYPE);
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
     */
    public static String getBrowserArgs() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_ARGS);
    }

    /**
     * 获取浏览器 channel
     */
    public static String getBrowserChannel() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSER_CHANNEL);
    }

    /**
     * 是否最大化窗口
     */
    public static boolean isWindowMaximize() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_WINDOW_MAXIMIZE);
    }

    /**
     * 获取窗口最大化参数
     */
    public static String getWindowMaximizeArgs() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_WINDOW_MAXIMIZE_ARGS);
    }

    /**
     * 获取 Viewport 宽度
     */
    public static int getViewportWidth() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_CONTEXT_VIEWPORT_WIDTH);
    }

    /**
     * 获取 Viewport 高度
     */
    public static int getViewportHeight() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_CONTEXT_VIEWPORT_HEIGHT);
    }

    /**
     * 是否启用触摸
     */
    public static boolean hasTouch() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_HAS_TOUCH);
    }

    /**
     * 是否移动设备模式
     */
    public static boolean isMobile() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_IS_MOBILE);
    }

    /**
     * 获取 Context locale
     */
    public static String getContextLocale() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_LOCALE);
    }

    /**
     * 获取 Context timezone
     */
    public static String getContextTimezone() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_TIMEZONE_ID);
    }

    /**
     * 获取 Context User-Agent
     */
    public static String getContextUserAgent() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_USER_AGENT);
    }

    /**
     * 获取 Context 权限
     */
    public static String getContextPermissions() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_PERMISSIONS);
    }

    /**
     * 获取 ColorScheme
     */
    public static String getColorScheme() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_COLOR_SCHEME);
    }

    /**
     * 获取地理纬度
     */
    public static String getGeolocationLatitude() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_GEOLOCATION_LATITUDE);
    }

    /**
     * 获取地理经度
     */
    public static String getGeolocationLongitude() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_GEOLOCATION_LONGITUDE);
    }

    /**
     * 获取设备缩放因子
     */
    public static String getDeviceScaleFactor() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_DEVICE_SCALE_FACTOR);
    }

    /**
     * 是否启用录屏
     */
    public static boolean isRecordVideoEnabled() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_RECORD_VIDEO_ENABLED);
    }

    /**
     * 获取录屏目录
     */
    public static String getRecordVideoDir() {
        return FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_CONTEXT_RECORD_VIDEO_DIR);
    }

    /**
     * 是否启用 Trace
     */
    public static boolean isTraceEnabled() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_ENABLED);
    }

    /**
     * Trace 时是否截图
     */
    public static boolean isTraceScreenshots() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_SCREENSHOTS);
    }

    /**
     * Trace 时是否快照
     */
    public static boolean isTraceSnapshots() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_SNAPSHOTS);
    }

    /**
     * Trace 时是否记录源码
     */
    public static boolean isTraceSources() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_CONTEXT_TRACE_SOURCES);
    }

    /**
     * 获取页面超时（毫秒）
     */
    public static int getPageTimeout() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_PAGE_TIMEOUT);
    }

    /**
     * 获取页面导航超时（毫秒）
     */
    public static int getNavigationTimeout() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_PAGE_NAVIGATION_TIMEOUT);
    }

    /**
     * 获取页面稳定化等待超时（毫秒）
     */
    public static int getStabilizeWaitTimeout() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_STABILIZE_WAIT_TIMEOUT);
    }

    /**
     * 获取截图等待超时（毫秒）
     */
    public static int getScreenshotWaitTimeout() {
        return FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_SCREENSHOT_WAIT_TIMEOUT);
    }

    /**
     * 获取浏览器重启策略
     */
    public static String getRestartStrategy() {
        return FrameworkConfigManager.getString(FrameworkConfig.SERENITY_PLAYWRIGHT_RESTART_BROWSER_FOR_EACH);
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
}