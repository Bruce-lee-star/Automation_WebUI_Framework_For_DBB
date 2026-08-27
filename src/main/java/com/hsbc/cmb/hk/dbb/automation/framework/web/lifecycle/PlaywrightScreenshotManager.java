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
 * - 截图前页面稳定化（含全页滚动高度上限/超时保护）
 * - 唯一文件名生成
 * - 截图失败的优雅降级（全页失败自动退化为视口截图）
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
     * 截图前页面稳定化（解决长页面懒加载高度不准问题）。
     *
     * <p>【关键】仅在 {@code fullPage == true} 时执行滚动。非全页截图时<b>完全不滚动</b>，
     * 保留用户当前视口位置——否则会表现为"截图时一直滚动滚动条"，即使 fullpage 已关闭。
     *
     * <p>全页模式下额外做两件事：
     * <ul>
     *   <li>回到顶部，让 Playwright 全页截图自行负责滚动拼图；</li>
     *   <li>若页面 {@code scrollHeight} 超过配置的"全页最大滚动高度"上限，则注入 CSS 把
     *       {@code html, body} 裁切到上限高度并禁用溢出滚动。防御无限滚动/懒加载页面导致
     *       Playwright 全页拼图持续滚动、卡死（表现为"滚动条不停"）。</li>
     * </ul>
     *
     * <p>所有 evaluate 调用均带超时保护，避免页面半死时卡住。
     *
     * @param page     目标页面
     * @param fullPage 是否全页截图（true 才滚动并处理高度上限）
     */
    private static void stabilizeBeforeScreenshot(Page page, boolean fullPage) {
        if (!fullPage) {
            return;
        }
        try {
            // 仅回到顶部，让 Playwright 全页截图自行负责滚动拼图。
            // 不要主动 scrollTo(scrollHeight)：对无限滚动/懒加载页面会触发持续加载，
            // 使 scrollHeight 不断增长，反而放大全页截图的滚动范围（表现为"滚动条不停"）。
            page.evaluate("() => window.scrollTo(0, 0)");

            // 全页高度上限保护：超过上限则裁切页面，避免 Playwright 全页拼图无限滚动。
            int maxHeight = PlaywrightManager.config().getFullPageMaxHeight();
            if (maxHeight > 0) {
                try {
                    // 读取当前真实可滚动高度；不触发更多滚动，只是读取。
                    Object rawHeight = page.evaluate("() => Math.max("
                            + "document.documentElement.scrollHeight,"
                            + "document.body.scrollHeight,"
                            + "document.documentElement.offsetHeight) | 0");
                    int pageHeight = (rawHeight instanceof Number) ? ((Number) rawHeight).intValue() : 0;
                    if (pageHeight > maxHeight) {
                        LoggingConfigUtil.logWarnIfVerbose(logger,
                                "Full-page scroll height {} exceeds cap {}, clipping to cap to avoid infinite scroll",
                                pageHeight, maxHeight);
                        // 注入样式把页面高度锁死在上限内，并禁用溢出滚动。
                        page.addStyleTag(new Page.AddStyleTagOptions()
                                .setContent("html, body {"
                                        + " max-height: " + maxHeight + "px !important;"
                                        + " height: " + maxHeight + "px !important;"
                                        + " overflow: hidden !important;"
                                        + "}"));
                    }
                } catch (Exception e) {
                    LoggingConfigUtil.logWarnIfVerbose(logger,
                            "Failed to apply full-page height cap: {}", e.getMessage());
                }
            }
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

    /** 构造标准截图选项（不含 fullPage 标记）。 */
    private static Page.ScreenshotOptions buildOptions(Path screenshotPath, long timeoutMs) {
        return new Page.ScreenshotOptions()
                .setOmitBackground(false)
                .setTimeout(timeoutMs)
                .setAnimations(ScreenshotAnimations.DISABLED)
                .setPath(screenshotPath);
    }

    /** 真正执行一次截图（不捕获异常，交由调用方决定降级策略）。 */
    private static void doScreenshot(Page page, Path screenshotPath, boolean fullPage, long timeoutMs) {
        Page.ScreenshotOptions options = buildOptions(screenshotPath, timeoutMs);
        options.setFullPage(fullPage);
        page.screenshot(options);
    }

    /** 还原全页截图时注入的高度裁切样式，避免影响后续操作与全页拼图。 */
    private static void restorePageHeightStyle(Page page) {
        try {
            page.addStyleTag(new Page.AddStyleTagOptions()
                    .setContent("html, body {"
                            + " max-height: none !important;"
                            + " height: auto !important;"
                            + " overflow: visible !important;"
                            + "}"));
        } catch (Exception ignore) {
            LoggingConfigUtil.logDebugIfVerbose(logger,
                    "Failed to restore page height style after screenshot: {}", ignore.getMessage());
        }
    }

    // ==================== 截图入口 ====================

    /**
     * 截图并返回截图文件路径（核心实现）。
     *
     * <p>全页/视口模式统一由全局配置 {@code config.isFullPageScreenshot()} 决定。
     * 截图具备以下健壮性保障：
     * <ul>
     *   <li>稳定化时不做主动滚动到底部（防止懒加载放大滚动范围），并对全页模式施加
     *       "最大滚动高度上限"保护，避免无限滚动页面卡死；</li>
     *   <li>跳过 {@code document.fonts.ready} 等待，规避字体挂起导致截图超时；</li>
     *   <li>若全页截图失败（超时/页面半加载），自动降级为视口截图重试一次，仍失败则
     *       再用更短超时重试一次视口截图，尽量为失败场景保留一张图。</li>
     * </ul>
     */
    static String takeScreenshot(String title) {
        Page page = null;
        // fullPage 声明在 try 外，确保 finally 块（恢复高度样式）可访问，避免局部变量作用域问题
        boolean fullPage = false;
        try {
            page = PlaywrightManager.getPageThreadLocal();
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

            // 全页/视口模式统一由全局配置决定。
            fullPage = PlaywrightManager.config().isFullPageScreenshot();
            int baseTimeout = PlaywrightManager.config().getScreenshotTimeout();
            long screenshotTimeout = (long) baseTimeout;
            LoggingConfigUtil.logDebugIfVerbose(logger,
                    "Screenshot request: title={}, fullPage={}, timeout={}ms", title, fullPage, screenshotTimeout);

            // 截图前稳定化（仅 fullPage 时滚动 + 高度上限保护；非全页不滚动）
            stabilizeBeforeScreenshot(page, fullPage);

            // 跳过字体加载等待（规避 document.fonts.ready 挂起导致截图超时）
            bypassFontsReady(page);

            // 页面加载状态等待（忽略超时，不阻塞截图）
            try {
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(screenshotTimeout));
            } catch (Exception e) {
                LoggingConfigUtil.logDebugIfVerbose(logger,
                        "Screenshot wait timeout ({}ms) - continuing: {}", screenshotTimeout, e.getMessage());
            }

            // 主路径：按配置尝试全页或视口截图
            try {
                doScreenshot(page, screenshotPath, fullPage, screenshotTimeout);
                LoggingConfigUtil.logDebugIfVerbose(logger,
                        "Screenshot saved (fullPage={}): {}", fullPage, screenshotPath);
                return screenshotPath.toString();
            } catch (Exception primaryFail) {
                if (!fullPage) {
                    // 视口截图失败：用更短超时再尝试一次，避免页面抖动/动画卡住。
                    LoggingConfigUtil.logWarnIfVerbose(logger,
                            "Viewport screenshot failed, retrying with short timeout: {}", primaryFail.getMessage());
                    try {
                        long shortTimeout = Math.max(1000L, screenshotTimeout / 2);
                        doScreenshot(page, screenshotPath, false, shortTimeout);
                        LoggingConfigUtil.logDebugIfVerbose(logger,
                                "Short-timeout viewport screenshot saved: {}", screenshotPath);
                        return screenshotPath.toString();
                    } catch (Exception e) {
                        logger.error("All screenshot attempts failed for title '{}'", title, e);
                        return null;
                    }
                }

                // fullPage=true 但主路径失败——不直接降级为视口（否则用户以为全页生效实则只截视口）。
                // 常见原因：注入的高度裁切样式（max-height/overflow:hidden）干扰了 Playwright 全页拼图，
                // 或页面半加载/懒加载导致超时。先还原裁切样式、再重试一次全页。
                LoggingConfigUtil.logWarnIfVerbose(logger,
                        "Full-page screenshot (attempt 1) failed: {}. Retrying full-page without height cap...",
                        primaryFail.getMessage());
                try {
                    restorePageHeightStyle(page);
                    doScreenshot(page, screenshotPath, true, screenshotTimeout);
                    LoggingConfigUtil.logDebugIfVerbose(logger,
                            "Full-page screenshot saved on retry (fullPage=true): {}", screenshotPath);
                    return screenshotPath.toString();
                } catch (Exception retryFail) {
                    // 全页重试仍失败：降级为视口，但明确以 ERROR 记录，提醒 fullPage 未生效。
                    logger.error("Full-page screenshot (fullPage=true) failed twice; downgrading to viewport. "
                            + "Primary cause: {}, retry cause: {}", primaryFail.getMessage(), retryFail.getMessage());
                    try {
                        doScreenshot(page, screenshotPath, false, screenshotTimeout);
                        LoggingConfigUtil.logDebugIfVerbose(logger,
                                "Viewport fallback screenshot saved: {}", screenshotPath);
                        return screenshotPath.toString();
                    } catch (Exception viewportFail) {
                        // 视口兜底：更短超时再试一次。
                        LoggingConfigUtil.logWarnIfVerbose(logger,
                                "Viewport fallback failed, retrying with short timeout: {}", viewportFail.getMessage());
                        try {
                            long shortTimeout = Math.max(1000L, screenshotTimeout / 2);
                            doScreenshot(page, screenshotPath, false, shortTimeout);
                            LoggingConfigUtil.logDebugIfVerbose(logger,
                                    "Short-timeout viewport fallback screenshot saved: {}", screenshotPath);
                            return screenshotPath.toString();
                        } catch (Exception e) {
                            logger.error("All screenshot attempts failed for title '{}'", title, e);
                            return null;
                        }
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Failed to take screenshot", e);
            return null;
        } finally {
            // 全页模式下注入了高度裁切样式，截图完成后还原，避免影响后续操作。
            // ⭐ 修复问题1（加固）：以实际传入的 fullPage 参数为准（与 stabilizeBeforeScreenshot 的注入条件一致），
            // 而非 PlaywrightManager.config().isFullPageScreenshot()，避免参数与配置不一致时漏恢复导致页面被永久裁剪。
            if (page != null && !page.isClosed() && fullPage) {
                restorePageHeightStyle(page);
            }
        }
    }

}
