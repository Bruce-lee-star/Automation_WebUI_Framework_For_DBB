package com.hsbc.cmb.hk.dbb.automation.framework.web.cloud;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.ProxyConfigResolver;
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
            command.add("--force-local");       // 所有流量走本地
            command.add("--only-automate");     // 仅允许 Automate 请求，禁止交互式浏览器登录

            // 代理配置（受 browserstack.local.proxy.enabled 独立开关控制）
            if (FrameworkConfigManager.getBoolean(FrameworkConfig.BROWSERSTACK_LOCAL_PROXY_ENABLED)) {
                String httpProxy = ProxyConfigResolver.getHttpProxyUrlForBrowserStackLocal();
                if (httpProxy != null) {
                    String host = ProxyConfigResolver.extractHost(httpProxy);
                    String port = ProxyConfigResolver.extractPort(httpProxy);
                    if (host != null && port != null) {
                        command.add("--proxy-host");
                        command.add(host);
                        command.add("--proxy-port");
                        command.add(port);
                        command.add("--force-proxy");   // 所有流量（含控制通道）强制走代理
                        logger.info("[BS Local] Using proxy: {}:{} (force-proxy)", host, port);

                        // 代理认证（BrowserStackLocal 支持 --proxy-user / --proxy-pass）
                        String proxyUser = ProxyConfigResolver.extractUser(httpProxy);
                        String proxyPass = ProxyConfigResolver.extractPass(httpProxy);
                        if (proxyUser != null && proxyPass != null) {
                            command.add("--proxy-user");
                            command.add(proxyUser);
                            command.add("--proxy-pass");
                            command.add(proxyPass);
                            logger.info("[BS Local] Proxy authentication configured for user: {}", proxyUser);
                        }
                    } else {
                        logger.warn("[BS Local] Proxy enabled but host/port extraction failed from URL: {}",
                                BrowserStackManager.sanitizeMessage(httpProxy));
                    }
                }
            }

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
            String trimmed = configured.trim();
            // 相对路径基于 JVM user.dir 解析（和 java.io.File 行为一致）
            Path p = Paths.get(trimmed).toAbsolutePath().normalize();
            if (Files.exists(p) && Files.isExecutable(p)) {
                return p.toString();
            }
            logger.warn("[BS Local] Configured path not found/executable: {} (resolved to: {})", configured, p);
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

        return null;
    }

    /**
     * 读取 stdout 直到看到成功标志或超时。
     * <p>使用独立守护线程阻塞读取，避免了 {@code reader.ready()} 在 Windows 上不可靠的问题。
     * <p>兼容 BrowserStackLocal 纯文本和 JSON 格式输出。
     */
    private static boolean waitForReady(Process process, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        StringBuilder startupLog = new StringBuilder();

        // 成功标志：纯文本或 JSON 状态
        final String[] SUCCESS_MARKERS = {
                "Press Ctrl-C to quit",
                "You can now access",
                "\"state\":\"connected\"",
                "\"status\":\"connected\"",
                "\"message\":\"Connected\""
        };

        // 用数组承载闭包副作用
        final boolean[] ready = {false};
        final boolean[] error = {false};
        final String[] errorMsg = {null};

        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while (!ready[0] && (line = reader.readLine()) != null) {
                    startupLog.append(line).append("\n");

                    // 检查错误
                    if (line.contains("[ERROR]") || line.contains("ERROR]")
                            || line.contains("\"level\":\"error\"")) {
                        error[0] = true;
                        errorMsg[0] = BrowserStackManager.sanitizeMessage(line);
                    }

                    // 检查成功标志
                    for (String marker : SUCCESS_MARKERS) {
                        if (line.contains(marker)) {
                            ready[0] = true;
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                logger.debug("[BS Local] Reader thread ended: {}", e.getMessage());
            }
        }, "bs-local-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        try {
            while (System.currentTimeMillis() < deadline) {
                if (ready[0]) {
                    logger.debug("[BS Local] Startup output:\n{}",
                            BrowserStackManager.sanitizeMessage(startupLog.toString()));
                    return true;
                }
                if (!process.isAlive()) {
                    int exitCode = process.exitValue();
                    logger.error("[BS Local] Process died unexpectedly (exit={}). Output:\n{}",
                            exitCode, BrowserStackManager.sanitizeMessage(startupLog.toString()));
                    return false;
                }
                if (error[0]) {
                    logger.error("[BS Local] Error detected: {}", errorMsg[0]);
                    // 不立即返回 false，可能是非致命错误，继续等待
                    error[0] = false;
                }
                readerThread.join(500);
            }

            logger.warn("[BS Local] Startup timeout after {}s. Partial output:\n{}",
                    timeoutSeconds, BrowserStackManager.sanitizeMessage(startupLog.toString()));
            readerThread.interrupt();
            return false;

        } catch (InterruptedException e) {
            readerThread.interrupt();
            Thread.currentThread().interrupt();
            return false;
        }
    }

}

