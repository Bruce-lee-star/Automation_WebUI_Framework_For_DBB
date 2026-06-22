package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.InitializationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private static final String DEFAULT_PLAYWRIGHT_BROWSER_PATH = ".playwright/browsers";
    private static final String DEFAULT_PLAYWRIGHT_DRIVER_PATH = ".playwright/driver";
    static final String DEFAULT_PLAYWRIGHT_TEMP_PATH = ".playwright/temp";
    
    // 下载进程列表
    private static final List<Process> downloadProcesses = new ArrayList<>();
    
    /**
     * 初始化 Playwright 路径配置
     */
    static void initializePlaywrightPaths() {
        String browserPath = FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSERS_PATH);
        if (browserPath == null || browserPath.trim().isEmpty()) {
            browserPath = DEFAULT_PLAYWRIGHT_BROWSER_PATH;
        }
        System.setProperty("PLAYWRIGHT_BROWSERS_PATH", browserPath);
        LoggingConfigUtil.logInfoIfVerbose(logger, "[static init], PLAYWRIGHT_BROWSERS_PATH set to: {}", browserPath);

        String driverTmp = FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_DRIVER_TMPDIR);
        if (driverTmp == null || driverTmp.trim().isEmpty()) {
            driverTmp = DEFAULT_PLAYWRIGHT_DRIVER_PATH;
        }
        System.setProperty("playwright.driver.tmpdir", driverTmp);
        LoggingConfigUtil.logInfoIfVerbose(logger, "[static init], playwright.driver.tmpdir set to: {}", driverTmp);

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

            // 创建浏览器临时目录（Firefox profile 等临时文件）
            Path tempPath = Paths.get(DEFAULT_PLAYWRIGHT_TEMP_PATH).toAbsolutePath();
            if (!Files.exists(tempPath)) {
                Files.createDirectories(tempPath);
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
        cleanupPlaywrightTempDirs(DEFAULT_PLAYWRIGHT_DRIVER_PATH);
        cleanupPlaywrightTempDirs(DEFAULT_PLAYWRIGHT_TEMP_PATH);

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
                            return fileName.startsWith("playwright") && Files.isDirectory(path);
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
     * 确保浏览器已安装。
     * <p>根据 browserType + channel 判断：
     * <ul>
     *   <li>channel 场景（chrome/msedge）→ 使用系统本地浏览器，跳过下载</li>
     *   <li>纯浏览器类型（chromium/firefox/webkit）→ 检查缓存，必要时下载</li>
     * </ul>
     */
    static boolean ensureBrowsersInstalled() {
        try {
            String browserType = PlaywrightManager.config().getBrowserType();
            String channel = PlaywrightManager.config().getBrowserChannel();

            // channel 场景仅对 chromium 有效：使用系统本地 Chrome/Edge
            if (isChannelBased(channel) && "chromium".equalsIgnoreCase(browserType)) {
                LoggingConfigUtil.logInfoIfVerbose(logger,
                        "[Static Init] Channel={} configured for chromium, using system browser (no download needed)",
                        channel);
                return false;
            }

            String configuredPath = FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSERS_PATH);
            if (configuredPath == null || configuredPath.trim().isEmpty()) {
                configuredPath = DEFAULT_PLAYWRIGHT_BROWSER_PATH;
            }
            Path cachePath = Paths.get(configuredPath).toAbsolutePath();

            boolean browsersInstalled = checkBrowsersInstalled(cachePath);
            if (!browsersInstalled) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] {} browser not found in cache, downloading...", browserType);
                installBrowsers(cachePath);
                // 下载后验证二进制文件确实存在且可执行
                if (!checkBrowsersInstalled(cachePath)) {
                    LoggingConfigUtil.logWarnIfVerbose(logger,
                        "[Static Init] Browser download completed but verification failed for {} at {}", browserType, cachePath);
                    return false;
                }
                LoggingConfigUtil.logInfoIfVerbose(logger,
                    "[Static Init] Browser {} downloaded and verified successfully", browserType);
                return true;
            } else {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Playwright {} browser already installed in: {}", browserType, cachePath);
                return false;
            }
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "[Static Init] Failed to check browsers installation", e);
            return false;
        }
    }

    /**
     * 检查浏览器是否已安装。
     * <p>根据 browserType + channel 综合判断缓存目录中是否有对应的浏览器：
     * <ul>
     *   <li>纯 chromium → 匹配 chromium-* / ms-playwright-chromium-*</li>
     *   <li>chromium + channel=chrome → 匹配 chrome-* / ms-playwright-chrome-*（本地 Chrome 不在此处管理，直接返回 true）</li>
     *   <li>chromium + channel=msedge → 匹配 msedge-* / ms-playwright-msedge-*（本地 Edge 不在此处管理，直接返回 true）</li>
     *   <li>firefox → 匹配 firefox-*</li>
     *   <li>webkit → 匹配 webkit-*</li>
     * </ul>
     */
    private static boolean checkBrowsersInstalled(Path cachePath) {
        try {
            String browserType = PlaywrightManager.config().getBrowserType();
            String channel = PlaywrightManager.config().getBrowserChannel();

            // channel 仅对 chromium 有效，使用系统本地浏览器
            if (isChannelBased(channel) && "chromium".equalsIgnoreCase(browserType)) {
                LoggingConfigUtil.logInfoIfVerbose(logger,
                        "[Static Init] Channel={} configured for chromium, using system browser (not managed by Playwright cache)", channel);
                return true;
            }

            LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Checking if {} browser is installed...", browserType);

            if (!Files.exists(cachePath)) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Cache path does not exist: {}", cachePath);
                return false;
            }

            // 根据 browserType 构建匹配关键词列表
            List<String> matchKeywords = buildBrowserMatchKeywords(browserType);

            boolean browserInstalled = false;
            try (Stream<Path> stream = Files.list(cachePath)) {
                browserInstalled = stream
                        .filter(Files::isDirectory)
                        .anyMatch(p -> {
                            String dirName = p.getFileName().toString();
                            boolean isMatch = matchKeywords.stream()
                                    .anyMatch(keyword -> dirName.contains(keyword));
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
     * channel 场景（chrome / msedge / chrome-beta 等）使用系统本地浏览器，
     * 不依赖 Playwright 缓存目录，直接返回已安装。
     */
    private static boolean isChannelBased(String channel) {
        return channel != null && !channel.trim().isEmpty();
    }

    /**
     * 根据 browserType + channel 构建浏览器缓存目录匹配关键词。
     * <p>Playwright 缓存目录典型命名：
     * <ul>
     *   <li>chromium → chromium-1150 / ms-playwright-chromium-1150</li>
     *   <li>chrome → chrome-126 / ms-playwright-chrome-126（CLI install chrome）</li>
     *   <li>msedge → msedge-126 / ms-playwright-msedge-126（CLI install msedge）</li>
     *   <li>firefox → firefox-1450 / ms-playwright-firefox-1450</li>
     *   <li>webkit → webkit-2100 / ms-playwright-webkit-2100</li>
     * </ul>
     */
    private static List<String> buildBrowserMatchKeywords(String browserType) {
        List<String> keywords = new ArrayList<>();
        String bt = browserType.trim().toLowerCase();
        keywords.add("ms-playwright-" + bt + "-");
        keywords.add(bt + "-");
        keywords.add(bt);
        return keywords;
    }

    /**
     * 下载 Playwright 浏览器到指定路径。
     * <p>自动处理：
     * <ol>
     *   <li>设置 Node.js 临时目录到工程 .playwright/driver（跨平台兼容）</li>
     *   <li>设置 PLAYWRIGHT_BROWSERS_PATH 到工程 .playwright/browsers</li>
     *   <li>注入 HTTP_PROXY / HTTPS_PROXY（支持带用户名密码的代理认证，特殊字符自动 URL 编码）</li>
     * </ol>
     */
    private static void installBrowsers(Path cachePath) {
        try {
            String browserType = PlaywrightManager.config().getBrowserType();
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
            env.put("PLAYWRIGHT_BROWSERS_PATH", cachePath.toString());
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "0");

            // ── 1. 设置 Node.js 临时目录到工程 .playwright/driver（跨平台）──
            Path driverTmpPath = Paths.get(DEFAULT_PLAYWRIGHT_DRIVER_PATH).toAbsolutePath();
            try {
                if (!Files.exists(driverTmpPath)) {
                    Files.createDirectories(driverTmpPath);
                }
            } catch (IOException e) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "[Static Init] Failed to create driver tmp dir: {}", driverTmpPath);
            }
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                env.put("TMP", driverTmpPath.toString());
                env.put("TEMP", driverTmpPath.toString());
            } else {
                env.put("TMPDIR", driverTmpPath.toString());
            }
            LoggingConfigUtil.logInfoIfVerbose(logger, "[Static Init] Node.js temp directory set to: {}", driverTmpPath);

            // ── 2. 注入代理 HTTP_PROXY / HTTPS_PROXY（含 URL 编码的认证信息）──
            injectDownloadProxy(env);

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
     * 将浏览器下载代理注入到 CLI 进程的环境变量中。
     * <p>直接从统一代理配置读取，无需为下载场景单独配置。
     * <p>用户名/密码中的特殊字符（@ % $ 等）会自动进行 URL 编码。
     * <p>受 {@code playwright.download.proxy.enabled} 独立开关控制。
     */
    private static void injectDownloadProxy(Map<String, String> env) {
        boolean downloadProxyEnabled = FrameworkConfigManager.getBoolean(FrameworkConfig.PLAYWRIGHT_DOWNLOAD_PROXY_ENABLED);
        if (!downloadProxyEnabled) {
            LoggingConfigUtil.logDebugIfVerbose(logger,
                    "[Static Init] Download proxy disabled (playwright.download.proxy.enabled=false)");
            return;
        }

        String httpProxy = ProxyConfigResolver.getHttpProxyUrl();
        String httpsProxy = ProxyConfigResolver.getHttpsProxyUrl();

        if (!isBlank(httpProxy)) {
            env.put("HTTP_PROXY", httpProxy);
            LoggingConfigUtil.logInfoIfVerbose(logger,
                    "[Static Init] HTTP_PROXY set: {}", sanitizeProxyForLog(httpProxy));
        }

        if (!isBlank(httpsProxy)) {
            env.put("HTTPS_PROXY", httpsProxy);
            LoggingConfigUtil.logInfoIfVerbose(logger,
                    "[Static Init] HTTPS_PROXY set: {}", sanitizeProxyForLog(httpsProxy));
        }

        if (isBlank(httpProxy) && isBlank(httpsProxy)) {
            LoggingConfigUtil.logDebugIfVerbose(logger,
                    "[Static Init] No download proxy configured — direct connection will be used");
        }
    }

    /**
     * 脱敏代理 URL 用于日志输出（隐藏密码）。
     */
    private static String sanitizeProxyForLog(String proxyUrl) {
        if (proxyUrl == null) return null;
        // 匹配 user:password@ 部分，替换 password 为 ***
        int atIndex = proxyUrl.lastIndexOf('@');
        if (atIndex > 0) {
            int slashIndex = proxyUrl.indexOf("://");
            int userInfoStart = slashIndex >= 0 ? slashIndex + 3 : 0;
            String userInfo = proxyUrl.substring(userInfoStart, atIndex);
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                return proxyUrl.substring(0, userInfoStart + colon + 1) + "***"
                        + proxyUrl.substring(atIndex);
            }
        }
        return proxyUrl;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

}
