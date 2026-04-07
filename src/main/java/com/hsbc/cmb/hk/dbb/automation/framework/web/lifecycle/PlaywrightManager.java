package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.TimeoutConfig;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.Geolocation;
import com.microsoft.playwright.options.LoadState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.AutoBrowserProcessor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.BrowserOverrideManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.strategy.ScreenshotStrategy;
import net.thucydides.model.environment.SystemEnvironmentVariables;
import net.thucydides.model.util.EnvironmentVariables;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import net.thucydides.model.steps.ExecutedStepDescription;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.InitializationException;

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
        // 设置 Playwright 浏览器缓存路径
        initializePlaywrightPaths();
        // 清理旧的 playwright-java 临时目录（防止 C 盘爆满）
        cleanupPlaywrightTempDirs();
        // 浏览器下载延迟到实际需要时，不在静态初始化阶段下载
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
     * 确保浏览器已安装（延迟到实际需要时）
     * 
     * 注意：此方法不再在静态初始化阶段调用
     * 而是在首次启动浏览器时调用，这样可以确保使用正确的浏览器类型
     */
    private static void ensureBrowserInstalledForType(String browserType) {
        try {
            // 检查特定浏览器类型是否已安装
            Path cachePath = Paths.get(DEFAULT_PLAYWRIGHT_BROWSER_PATH).toAbsolutePath();
            
            boolean browserInstalled = checkSpecificBrowserInstalled(cachePath, browserType);
            
            if (!browserInstalled) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Browser Init] {} browser not found in cache, downloading to: {}", browserType, cachePath);
                installSpecificBrowser(cachePath, browserType);
            } else {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Browser Init] {} browser already installed in: {}", browserType, cachePath);
            }
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "[Browser Init] Failed to check/install browser", e);
        }
    }

    /**
     * 检查特定浏览器类型是否已安装
     */
    private static boolean checkSpecificBrowserInstalled(Path cachePath, String browserType) {
        try {
            if (!Files.exists(cachePath)) {
                return false;
            }

            try (Stream<Path> stream = Files.list(cachePath)) {
                return stream
                        .filter(Files::isDirectory)
                        .anyMatch(p -> {
                            String dirName = p.getFileName().toString().toLowerCase();
                            return dirName.startsWith(browserType.toLowerCase());
                        });
            }
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to check browser installation", e);
            return false;
        }
    }

    /**
     * 下载特定浏览器类型
     */
    private static void installSpecificBrowser(Path cachePath, String browserType) {
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "[Browser Install] Downloading {} browser to: {}", browserType, cachePath);

            ProcessBuilder pb = new ProcessBuilder(
                    "java",
                    "-cp", System.getProperty("java.class.path"),
                    "com.microsoft.playwright.CLI",
                    "install",
                    browserType  // 只下载指定的浏览器类型
            );

            Map<String, String> env = pb.environment();
            env.put("PLAYWRIGHT_BROWSERS_PATH", cachePath.toString());
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "0");

            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            Process process = pb.start();

            synchronized (downloadProcesses) {
                downloadProcesses.add(process);
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Browser Install] {} browser downloaded successfully!", browserType);
            } else {
                LoggingConfigUtil.logWarnIfVerbose(logger, "[Browser Install] {} browser download failed with exit code: {}", browserType, exitCode);
            }
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "[Browser Install] Failed to download {} browser", browserType, e);
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
        cleanupPlaywrightTempDirs(DEFAULT_PLAYWRIGHT_DRIVER_PATH);

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
            // 设置浏览器下载路径为 .playwright/browser
            env.put("PLAYWRIGHT_BROWSERS_PATH", cachePath.toString());
            LoggingConfigUtil.logInfoIfVerbose(logger, "[Playwright Install] BROWSERS_PATH: {}", cachePath);
            
            // 允许下载
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "0");

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
        boolean maximizeWindow = isWindowMaximize();
        String maximizeArgs = getWindowMaximizeArgs();
        boolean hasStartMaximized = maximizeArgs.contains("--start-maximized");

        // 获取逻辑屏幕尺寸
        Dimension screenSize = getAvailableScreenSize();
        int screenWidth = (int) screenSize.getWidth();
        int screenHeight = (int) screenSize.getHeight();

        // 获取浏览器类型
        String browserType = getBrowserType();
        boolean isChromium = isChromiumBased(browserType);

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
     */
    private static Page createPage(BrowserContext context) {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Creating new Page...");
        Page page = context.newPage();

        // 页面稳定化：防止页面加载过程中出现缩放行为
        stabilizePage(page);

        LoggingConfigUtil.logInfoIfVerbose(logger, "Page created successfully with initial URL: {}", page.url());
        return page;
    }

    // ==================== Context 和 Page 创建方法 ====================

    /**
     * 创建新的 BrowserContext（保证场景间配置隔离）
     * <p>
     * 核心机制：
     * 1. 每次都创建全新的默认配置对象，杜绝场景间配置残留
     * 2. 仅当 customContextOptionsFlag=true 时，才叠加自定义配置
     * 3. Context 创建后立即重置 customContextOptionsFlag，确保下一次访问不会重复重建
     * 4. 自定义配置的数据在 scenario 结束时通过 cleanupForScenario 重置
     */
    private static BrowserContext createContext() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Creating new BrowserContext...");

        Browser currentBrowser = getBrowser();
        // 关键：每次都新建全新的默认配置对象，杜绝场景间配置残留
        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions();

        // 1. 先配置框架默认项（所有场景都需要的默认配置）
        configureDefaultContextOptions(contextOptions);

        // 2. 条件注入自定义配置（仅当 flag=true 时叠加）
        Boolean customFlag = customContextOptionsFlag.get();
        if (customFlag != null && customFlag) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Applying custom context options...");
            configureCustomContextOptions(contextOptions);
        }

        // 初始化 Context
        BrowserContext context = currentBrowser.newContext(contextOptions);

        // 设置超时
        configureTimeouts(context);

        // 启用 tracing（如果配置了）
        enableTracing(context);

        // 【关键】Context创建成功后，立即重置customContextOptionsFlag
        // 这样下一次getContext()调用不会误以为需要重建Context
        if (customFlag != null && customFlag) {
            customContextOptionsFlag.set(false);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Custom context options applied, flag reset to false");
        }

        LoggingConfigUtil.logInfoIfVerbose(logger, "BrowserContext created successfully");
        return context;
    }

    /**
     * 配置框架默认 Context 选项（所有场景都会应用）
     */
    private static void configureDefaultContextOptions(Browser.NewContextOptions contextOptions) {
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

        // 设置设备缩放因子
        configureDeviceScaleFactor(contextOptions);

        // 设置触摸和移动设备标识
        contextOptions.setHasTouch(hasTouch());
        contextOptions.setIsMobile(isMobile());

        // 设置颜色方案
        String colorScheme = getColorScheme();
        contextOptions.setColorScheme(ColorScheme.valueOf(colorScheme.toUpperCase().replace("-", "_")));

        // 设置 Viewport（如果未启用窗口最大化，则使用配置的 viewport）
        boolean maximizeWindow = isWindowMaximize();
        if (!maximizeWindow) {
            int viewportWidth = getViewportWidth();
            int viewportHeight = getViewportHeight();
            contextOptions.setViewportSize(viewportWidth, viewportHeight);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Setting viewport size from config: {}x{}", viewportWidth, viewportHeight);
        } else {
            // 窗口最大化模式：使用逻辑屏幕尺寸作为 viewport
            Dimension screenSize = getAvailableScreenSize();
            int logicalWidth = (int) screenSize.getWidth();
            int logicalHeight = (int) screenSize.getHeight();
            contextOptions.setViewportSize(logicalWidth, logicalHeight);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Window maximize enabled, setting viewport to screen size: {}x{}", logicalWidth, logicalHeight);
        }

        // 设置录屏
        configureVideoRecording(contextOptions);
    }

    /**
     * 配置自定义 Context 选项（仅在 customContextOptionsFlag=true 时应用）
     * 自定义配置优先级高于框架默认配置
     */
    private static void configureCustomContextOptions(Browser.NewContextOptions contextOptions) {
        // StorageState（session 恢复）
        Path storagePath = customStorageStatePath.get();
        if (storagePath != null) {
            if (Files.exists(storagePath)) {
                contextOptions.setStorageStatePath(storagePath);
                LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom storageStatePath: {}", storagePath);
            } else {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Custom storageStatePath does not exist: {}", storagePath);
            }
        }

        // Locale
        String locale = customLocale.get();
        if (locale != null && !locale.isEmpty()) {
            contextOptions.setLocale(locale);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom locale: {}", locale);
        }

        // Timezone
        String timezoneId = customTimezoneId.get();
        if (timezoneId != null && !timezoneId.isEmpty()) {
            contextOptions.setTimezoneId(timezoneId);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom timezoneId: {}", timezoneId);
        }

        // User Agent
        String userAgent = customUserAgent.get();
        if (userAgent != null && !userAgent.isEmpty()) {
            contextOptions.setUserAgent(userAgent);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom userAgent: {}", userAgent);
        }

        // Permissions
        List<String> permissions = customPermissions.get();
        if (permissions != null && !permissions.isEmpty()) {
            contextOptions.setPermissions(permissions);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom permissions: {}", permissions);
        }

        // Geolocation
        Geolocation geolocation = customGeolocation.get();
        if (geolocation != null) {
            contextOptions.setGeolocation(geolocation.latitude, geolocation.longitude);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom geolocation: ({}, {})", geolocation.latitude, geolocation.longitude);
        }

        // Device Scale Factor
        Integer scaleFactor = customDeviceScaleFactor.get();
        if (scaleFactor != null) {
            contextOptions.setDeviceScaleFactor(scaleFactor / 100.0);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom deviceScaleFactor: {}", scaleFactor / 100.0);
        }

        // Mobile 和 Touch
        Boolean isMobile = customIsMobile.get();
        if (isMobile != null) {
            contextOptions.setIsMobile(isMobile);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom isMobile: {}", isMobile);
        }

        Boolean hasTouch = customHasTouch.get();
        if (hasTouch != null) {
            contextOptions.setHasTouch(hasTouch);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom hasTouch: {}", hasTouch);
        }

        // Color Scheme
        ColorScheme colorScheme = customColorScheme.get();
        if (colorScheme != null) {
            contextOptions.setColorScheme(colorScheme);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom colorScheme: {}", colorScheme);
        }

        // Viewport
        Integer customViewportWidthVal = customViewportWidth.get();
        Integer customViewportHeightVal = customViewportHeight.get();
        if (customViewportWidthVal != null && customViewportHeightVal != null) {
            contextOptions.setViewportSize(customViewportWidthVal, customViewportHeightVal);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Using custom viewportSize: {}x{}", customViewportWidthVal, customViewportHeightVal);
        }
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
            int stabilizeWaitTimeout = TimeoutConfig.getStabilizeTimeout();
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
     */
    public static void closePage() {
        synchronized (PAGE_LOCK) {
            Page page = pageThreadLocal.get();
            if (page != null) {
                try {
                    if (!page.isClosed()) {
                        LoggingConfigUtil.logInfoIfVerbose(logger, "Closing Page...");
                        page.close();
                        LoggingConfigUtil.logInfoIfVerbose(logger, "Page closed");
                    }
                } catch (Exception e) {
                    logger.warn("Failed to close!");
                } finally {
                    pageThreadLocal.remove();
                }
            }
        }
    }

    /**
     * 关闭当前线程的 Context
     */
    public static void closeContext() {
        synchronized (CONTEXT_LOCK) {
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
        String restartBrowserForEach = getRestartStrategy();

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
                // 清理页面状态，导航到空白页
                LoggingConfigUtil.logDebugIfVerbose(logger, "Cleaning up page state while keeping Context/Page open");

                // 1. 清除页面缓存
                page.evaluate("() => { if (window.performance && window.performance.clearResourceTimings) window.performance.clearResourceTimings(); }");

                // 2. 可选：导航到空白页（如果需要更彻底的清理）
                 page.navigate("about:blank");

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
     * 判断浏览器类型是否是 Chromium 系列
     * Chromium 系列浏览器包括：chromium, chrome, edge
     */
    private static boolean isChromiumBased(String browserType) {
        if (browserType == null) {
            return false;
        }
        return browserType.equalsIgnoreCase("chromium") ||
               browserType.equalsIgnoreCase("chrome") ||
               browserType.equalsIgnoreCase("edge");
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
     * @deprecated 使用 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.utils.TimeoutConfig#getStabilizeTimeout()}
     */
    @Deprecated
    public static int getStabilizeWaitTimeout() {
        return TimeoutConfig.getStabilizeTimeout();
    }

    /**
     * 获取截图等待超时（毫秒）
     * @deprecated 使用 {@link com.hsbc.cmb.hk.dbb.automation.framework.web.utils.TimeoutConfig#getScreenshotTimeout()}
     */
    @Deprecated
    public static int getScreenshotWaitTimeout() {
        return TimeoutConfig.getScreenshotTimeout();
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

        public com.microsoft.playwright.options.Geolocation getGeolocation() {
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