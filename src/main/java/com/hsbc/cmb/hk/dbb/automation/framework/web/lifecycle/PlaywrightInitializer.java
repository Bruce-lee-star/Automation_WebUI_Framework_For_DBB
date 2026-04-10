package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.InitializationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Playwright 初始化器 - 负责初始化相关逻辑
 * <p>
 * 职责：
 * - Playwright 实例初始化
 * - 浏览器安装和下载
 * - 临时目录清理
 * - Playwright 路径配置
 */
class PlaywrightInitializer {
    
    private static final Logger logger = LoggerFactory.getLogger(PlaywrightInitializer.class);
    
    // Playwright 浏览器缓存路径（项目根目录下的 .playwright 目录）
    private static final String DEFAULT_PLAYWRIGHT_BROWSER_PATH = ".playwright/browser";
    private static final String DEFAULT_PLAYWRIGHT_DRIVER_PATH = ".playwright/driver";
    
    private static final Boolean SKIP_DOWNLOAD_BROWSER = FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD);
    
    // 下载进程列表
    private static final List<Process> downloadProcesses = new ArrayList<>();
    
    /**
     * 初始化 Playwright 路径配置
     */
    static void initializePlaywrightPaths() {
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
     * 清理旧的 playwright 临时目录
     */
    static void cleanupPlaywrightTempDirs() {
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

    /**
     * 确保浏览器已安装
     */
    static void ensureBrowsersInstalled() {
        try {
            if (SKIP_DOWNLOAD_BROWSER) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Browser download is skipped, assuming browser is available");
                return;
            }

            Path cachePath = Paths.get(DEFAULT_PLAYWRIGHT_BROWSER_PATH).toAbsolutePath();
            String configuredBrowserType = PlaywrightManager.getBrowserType();

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
     * 检查浏览器是否已安装
     */
    private static boolean checkBrowsersInstalled(Path cachePath) {
        try {
            if (SKIP_DOWNLOAD_BROWSER) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Browser download is skipped, assuming browser is available");
                return true;
            }

            String browserType = PlaywrightManager.getBrowserType();
            LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Checking if {} browser is installed...", browserType);

            if (!Files.exists(cachePath)) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Cache path does not exist: {}", cachePath);
                return false;
            }

            // 检查是否有对应的浏览器目录
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
            String browserType = PlaywrightManager.getBrowserType();
            LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Downloading Playwright {} browser to: {}", browserType, cachePath);

            ProcessBuilder pb = new ProcessBuilder(
                    "java",
                    "-cp", System.getProperty("java.class.path"),
                    "com.microsoft.playwright.CLI",
                    "install",
                    browserType,
                    "ffmpeg"
            );

            Map<String, String> env = pb.environment();
            if (!SKIP_DOWNLOAD_BROWSER) {
                env.put("PLAYWRIGHT_BROWSERS_PATH", cachePath.toString());
                env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "0");
            }

            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Starting Playwright {} browser download...", browserType);
            Process process = pb.start();

            // 跟踪下载进程,以便在 JVM 退出时终止
            synchronized (downloadProcesses) {
                downloadProcesses.add(process);
            }

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

    /**
     * 初始化 Playwright 实例
     */
    static Playwright initializePlaywright(String configId) {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Initializing Playwright for config: {}", configId);

        try {
            Playwright.CreateOptions createOptions = getCreateOptions();
            Playwright playwright = Playwright.create(createOptions);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Playwright initialized successfully for config: {}", configId);
            return playwright;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to initialize Playwright for config: {}", configId, e);
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
     * 终止所有浏览器下载进程
     */
    static void terminateDownloadProcesses() {
        synchronized (downloadProcesses) {
            if (downloadProcesses.isEmpty()) {
                return;
            }

            LoggingConfigUtil.logInfoIfVerbose(logger, "Terminating {} browser download processes...", downloadProcesses.size());

            for (Process process : downloadProcesses) {
                try {
                    if (process.isAlive()) {
                        process.destroy();
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
}
