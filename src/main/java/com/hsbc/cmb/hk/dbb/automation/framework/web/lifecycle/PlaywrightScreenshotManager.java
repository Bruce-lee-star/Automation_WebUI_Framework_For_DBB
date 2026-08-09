package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotAnimations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 截图管理器 — 负责截图生成和文件名生成
 * <p>
 * 从 PlaywrightManager 中独立出来，专注于截图职责：
 * - 截图文件生成（含全页/视口模式）
 * - 截图前页面稳定化
 * - 唯一文件名生成
 */
class PlaywrightScreenshotManager {

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightScreenshotManager.class);

    /** 系统级唯一标识生成器，完全不依赖人为命名的 scenario 名称 */
    private static final AtomicLong screenshotIdGenerator = new AtomicLong(0);

    // ==================== 唯一标识 ====================

    private static String getScenarioIdentifier() {
        return Thread.currentThread().threadId() + "_" + screenshotIdGenerator.incrementAndGet();
    }

    // ==================== 文件名生成 ====================

    /**
     * 生成 SHA-256 哈希值，用于创建类似 Serenity HTML 文件的截图文件名
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

    // ==================== 截图前稳定化 ====================

    /**
     * 截图前页面稳定化（解决截图残留/底部重复问题，以及长页面懒加载高度不准问题）
     * 先滚到底部触发懒加载，再滚回顶部，确保 scrollHeight 准确
     */
    private static void stabilizeBeforeScreenshot(Page page) {
        try {
            page.evaluate("() => {"
                    + "  window.scrollTo(0, document.body.scrollHeight);"
                    + "  window.scrollTo(0, 0);"
                    + "}");
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Screenshot stabilization failed: {}", e.getMessage());
        }
    }

    /**
     * 截图前让页面字体立即"就绪"。
     *
     * <p>Playwright 截图默认会等待 {@code document.fonts.ready}（网络字体加载完成）——
     * 当页面存在挂起的字体请求（如登录失败后的异常/半加载页面、字体 CDN 慢速）时，
     * 该等待会超时，导致截图失败且不生成文件（日志表现为卡在 "waiting for fonts to load..."）。
     *
     * <p>此处通过 JS 用 {@code Object.defineProperty} 覆盖 {@code document.fonts.ready}，
     * 使其返回一个立即 resolve 的 Promise。驱动在截图时会重新求值 {@code document.fonts.ready}，
     * 拿到我们伪造的已就绪 Promise 后即跳过字体等待，彻底规避截图超时。
     *
     * <p>等价于官方环境变量 {@code PW_TEST_SCREENSHOT_NO_FONTS_READY=1} 的效果，
     * 但无需在 JVM 启动前设置，框架内自动生效。
     */
    private static void bypassFontsReady(Page page) {
        try {
            page.evaluate("() => {"
                    + "  try {"
                    + "    if (document.fonts) {"
                    + "      Object.defineProperty(document.fonts, 'ready', {"
                    + "        get: function() { return Promise.resolve(); },"
                    + "        configurable: true"
                    + "      });"
                    + "    }"
                    + "  } catch (e) {}"
                    + "}");
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to bypass fonts ready: {}", e.getMessage());
        }
    }

    // ==================== 截图入口 ====================

    /**
     * 截图并返回截图文件路径（核心实现）
     */
    static String takeScreenshot(String title) {
        try {
            Page page = PlaywrightManager.getPageThreadLocal();
            if (page == null || page.isClosed()) {
                return null;
            }

            // 目录（Serenity 标准）
            Path screenshotDir = Paths.get("target/site/serenity");
            Files.createDirectories(screenshotDir);

            // 唯一文件名
            String uniqueId = getScenarioIdentifier();
            String uniqueSource = title + "_" + uniqueId + "_" + System.currentTimeMillis();
            String sha256 = generateHash(uniqueSource);
            String screenshotName = sha256 + ".png";
            Path screenshotPath = screenshotDir.resolve(screenshotName);

            // 清理残留截图文件
            try {
                if (Files.exists(screenshotPath)) {
                    Files.deleteIfExists(screenshotPath);
                }
            } catch (Exception e) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to delete existing screenshot: {}", e.getMessage());
            }

            // 截图前稳定化
            stabilizeBeforeScreenshot(page);

            // 跳过字体加载等待（规避 document.fonts.ready 挂起导致截图超时）
            bypassFontsReady(page);

            // 页面等待
            int screenshotWaitTimeout = PlaywrightManager.config().getScreenshotTimeout();
            try {
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(screenshotWaitTimeout));
            } catch (Exception e) {
                LoggingConfigUtil.logDebugIfVerbose(logger,
                        "Screenshot wait timeout ({}ms) - continuing: {}", screenshotWaitTimeout, e.getMessage());
            }

            // 截图：全页模式 vs viewport 模式
            boolean fullPage = PlaywrightManager.config().isFullPageScreenshot();
            Page.ScreenshotOptions options = new Page.ScreenshotOptions()
                    .setOmitBackground(false)
                    .setTimeout((long) PlaywrightManager.config().getScreenshotTimeout())
                    .setAnimations(ScreenshotAnimations.DISABLED)
                    .setPath(screenshotPath);

            if (fullPage) {
                page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
                page.waitForTimeout(300.0);
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

}
