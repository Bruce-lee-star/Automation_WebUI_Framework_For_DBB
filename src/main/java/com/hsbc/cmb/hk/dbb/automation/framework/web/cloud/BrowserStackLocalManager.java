package com.hsbc.cmb.hk.dbb.automation.framework.web.cloud;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * BrowserStack Local 隧道管理器。
 *
 * <h3>工作原理</h3>
 * <pre>
 *  本地机器 ──安全隧道──▶ BrowserStack Cloud ──▶ 远程浏览器
 *            (BrowserStackLocal 二进制)         (可访问内网应用)
 * </pre>
 *
 * <h3>使用方式</h3>
 * <pre>
 * // 1. 下载 BrowserStackLocal 二进制 → 放到指定路径
 * // 2. 配置 serenity.properties:
 * //    browserstack.local=true
 * //    browserstack.local.path=D:/tools/BrowserStackLocal.exe
 *
 * // 3. 框架自动启停（BrowserStackManager.connect() 时启动，cleanup 时停止）
 * </pre>
 */
public class BrowserStackLocalManager {

    private static final Logger logger = LoggerFactory.getLogger(BrowserStackLocalManager.class);

    private static volatile Process tunnelProcess;
    private static volatile boolean tunnelRunning = false;

    /**
     * 启动 BrowserStack Local 隧道。
     * <p>幂等：已运行时直接返回。
     *
     * @return true 隧道启动成功
     */
    public static synchronized boolean startTunnel() {
        if (tunnelRunning) {
            logger.info("[BS Local] Tunnel already running, skip start");
            return true;
        }

        if (!isLocalEnabled()) {
            logger.debug("[BS Local] Local testing disabled, skip tunnel");
            return false;
        }

        String key = BrowserStackManager.getAccessKeyRaw();
        if (key == null || key.isEmpty()) {
            logger.error("[BS Local] Cannot start tunnel: access key not configured");
            return false;
        }

        String binaryPath = resolveBinaryPath();
        if (binaryPath == null) {
            logger.error("[BS Local] BrowserStackLocal binary not found. Download from: https://www.browserstack.com/local-testing/automate");
            return false;
        }

        String localIdentifier = FrameworkConfigManager.getString(FrameworkConfig.BROWSERSTACK_LOCAL_IDENTIFIER);
        if (localIdentifier == null || localIdentifier.trim().isEmpty()) {
            localIdentifier = "automation_" + System.currentTimeMillis();
        }

        try {
            List<String> command = new ArrayList<>();
            command.add(binaryPath);
            command.add("--key");
            command.add(key);
            command.add("--local-identifier");
            command.add(localIdentifier);
            command.add("--force-local");   // 所有流量走本地

            // 代理透传（如果统一代理已配）
            String httpProxy = getBootstrapProxy();
            if (httpProxy != null) {
                String host = extractHost(httpProxy);
                String port = extractPort(httpProxy);
                if (host != null && port != null) {
                    command.add("--proxy-host");
                    command.add(host);
                    command.add("--proxy-port");
                    command.add(port);
                    logger.info("[BS Local] Using proxy: {}:{}", host, port);
                }
            }

            // verbose 日志
            command.add("-v");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            logger.info("[BS Local] Starting tunnel: {} (identifier={})", binaryPath, localIdentifier);
            tunnelProcess = pb.start();

            // 等待隧道就绪（读取 stdout 直到 "Press Ctrl-C to quit"）
            int timeout = FrameworkConfigManager.getInt(FrameworkConfig.BROWSERSTACK_LOCAL_TIMEOUT);
            if (timeout <= 0) timeout = 30;
            boolean ready = waitForReady(tunnelProcess, timeout);

            if (ready) {
                tunnelRunning = true;
                logger.info("[BS Local] Tunnel established successfully (identifier={})", localIdentifier);
                return true;
            } else {
                logger.error("[BS Local] Tunnel failed to start within {} seconds", timeout);
                stopTunnel();
                return false;
            }

        } catch (Exception e) {
            logger.error("[BS Local] Failed to start tunnel: {}", e.getMessage(), e);
            stopTunnel();
            return false;
        }
    }

    /**
     * 停止 BrowserStack Local 隧道。
     */
    public static synchronized void stopTunnel() {
        if (tunnelProcess != null && tunnelProcess.isAlive()) {
            logger.info("[BS Local] Stopping tunnel...");
            tunnelProcess.destroy();
            try {
                if (!tunnelProcess.waitFor(5, TimeUnit.SECONDS)) {
                    tunnelProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                tunnelProcess.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            logger.info("[BS Local] Tunnel stopped");
        }
        tunnelProcess = null;
        tunnelRunning = false;
    }

    public static boolean isTunnelRunning() {
        return tunnelRunning;
    }

    // ────────────────── private ──────────────────

    private static boolean isLocalEnabled() {
        return FrameworkConfigManager.getBoolean(FrameworkConfig.BROWSERSTACK_LOCAL);
    }

    private static String resolveBinaryPath() {
        String configured = FrameworkConfigManager.getString(FrameworkConfig.BROWSERSTACK_LOCAL_PATH);
        if (configured != null && !configured.trim().isEmpty()) {
            Path p = Paths.get(configured.trim());
            if (Files.exists(p) && Files.isExecutable(p)) {
                return p.toAbsolutePath().toString();
            }
            logger.warn("[BS Local] Configured path not found/executable: {}", configured);
        }

        // fallback: 在 PATH 中查找
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String binaryName = isWindows ? "BrowserStackLocal.exe" : "BrowserStackLocal";
        for (String dir : System.getenv("PATH").split(System.getProperty("path.separator"))) {
            Path candidate = Paths.get(dir, binaryName);
            if (Files.exists(candidate)) {
                if (!Files.isExecutable(candidate)) {
                    logger.warn("[BS Local] Found binary but not executable: {}. "
                            + "Run: chmod +x {}", candidate, candidate);
                    continue;
                }
                logger.info("[BS Local] Found binary in PATH: {}", candidate);
                return candidate.toAbsolutePath().toString();
            }
        }

        // macOS Spotlight 索引泄漏：BrowserStack 下载包解压后可能在 ~/Downloads/BrowserStackLocal-*
        // 额外搜索几个常见位置
        if (!isWindows) {
            String home = System.getProperty("user.home");
            try (var stream = Files.walk(Paths.get(home, "Downloads"), 1)) {
                Path found = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().startsWith("BrowserStackLocal")
                                && Files.isExecutable(p))
                        .findFirst().orElse(null);
                if (found != null) {
                    logger.info("[BS Local] Found binary in downloads: {}", found);
                    return found.toAbsolutePath().toString();
                }
            } catch (Exception ignored) {
                // 目录不存在或无权限
            }
        }

        return null;
    }

    /**
     * 读取 stdout 直到看到成功标志或超时。
     */
    private static boolean waitForReady(Process process, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            StringBuilder startupLog = new StringBuilder();
            String line;
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    line = reader.readLine();
                    if (line != null) {
                        startupLog.append(line).append("\n");
                        if (line.contains("Press Ctrl-C to quit")
                                || line.contains("You can now access")) {
                            logger.debug("[BS Local] Startup output:\n{}", startupLog);
                            return true;
                        }
                    }
                } else {
                    // 检查进程是否已死
                    if (!process.isAlive()) {
                        logger.error("[BS Local] Process died unexpectedly (exit={}). Output:\n{}",
                                process.exitValue(), startupLog);
                        return false;
                    }
                    Thread.sleep(200);
                }
            }
            logger.warn("[BS Local] Startup timeout. Partial output:\n{}", startupLog);
        } catch (IOException | InterruptedException e) {
            logger.error("[BS Local] Error reading tunnel output: {}", e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return false;
    }

    /**
     * 从统一代理配置获取代理 URL 用于 Local 二进制启动。
     */
    private static String getBootstrapProxy() {
        try {
            Class<?> resolverClass = Class.forName(
                    "com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.ProxyConfigResolver");
            Object httpProxy = resolverClass.getMethod("getHttpProxyUrl").invoke(null);
            return httpProxy != null ? httpProxy.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractHost(String proxyUrl) {
        if (proxyUrl == null) return null;
        String url = proxyUrl.replaceFirst("https?://", "");
        int atIdx = url.lastIndexOf('@');
        if (atIdx >= 0) url = url.substring(atIdx + 1);
        int colonIdx = url.lastIndexOf(':');
        return colonIdx >= 0 ? url.substring(0, colonIdx) : url;
    }

    private static String extractPort(String proxyUrl) {
        if (proxyUrl == null) return null;
        String url = proxyUrl.replaceFirst("https?://", "");
        int atIdx = url.lastIndexOf('@');
        if (atIdx >= 0) url = url.substring(atIdx + 1);
        int colonIdx = url.lastIndexOf(':');
        return colonIdx >= 0 ? url.substring(colonIdx + 1).replaceAll("/.*", "") : null;
    }
}
