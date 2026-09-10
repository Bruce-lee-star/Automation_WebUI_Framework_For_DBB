package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import net.thucydides.model.screenshots.ScreenshotAndHtmlSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.regex.Pattern;

/**
 *  收口 {@code PlaywrightListener} 的截图捕获与文件名清洗职责（原 takeScreenshot / sanitize* 簇）。
 *
 * <p>截图是监听器中最独立的子系统：仅依赖 {@link PlaywrightManager} 产出 PNG/HTML 路径并封装为
 * Serenity 的 {@link ScreenshotAndHtmlSource}。迁移后日志路由回本类 logger（保持生产溯源一致），
 * 重入守卫（takingScreenshot）经 core 的 {@code TestContext}/{@code ContextKey} 收拢为 per-thread 持有（T3-1 收口），避免污染监听器的失败守卫状态。</p>
 *
 * <p><b>线程安全：</b>{@code takingScreenshot} 为 per-thread ThreadLocal，并行 scenario 互不干扰；
 * 截图计数经 {@link ListenerPerfStats} 的原子计数器，无竞态。</p>
 */
final class FailureScreenshotHandler {

    private static final Logger logger = LoggerFactory.getLogger(FailureScreenshotHandler.class);

    private static final Pattern SANITIZE_FILENAME_PATTERN = Pattern.compile("[^a-zA-Z0-9_-]");
    private static final Pattern SANITIZE_NAME_PATTERN = Pattern.compile("[^a-zA-Z0-9]");

    private static final ContextKey<Boolean> TAKING_SCREENSHOT_KEY =
            ContextKey.of("failureScreenshotHandler.takingScreenshot", Boolean.class);

    private FailureScreenshotHandler() {
    }

    /**
     * 截图并封装为 Serenity 对象（不注册到步骤列表，由调用方决定是否加入）。
     *
     * @param screenshotName 截图名称
     * @return 截图对象；递归重入或未生成文件时返回 null
     */
    static ScreenshotAndHtmlSource capture(String screenshotName) {
        if (Boolean.TRUE.equals(TestContextHolder.get().get(TAKING_SCREENSHOT_KEY))) {
            VerboseLogging.logDebugIfVerbose(logger,
                    "Skipping screenshot - already taking screenshot to prevent recursion");
            return null;
        }
        try {
            TestContextHolder.get().set(TAKING_SCREENSHOT_KEY, Boolean.TRUE);
            String screenshotPath = PlaywrightManager.takeScreenshot(screenshotName);
            if (screenshotPath != null) {
                File pngFile = new File(screenshotPath);
                File htmlFile = new File(screenshotPath.replace(".png", ".html"));
                if (pngFile.exists()) {
                    ScreenshotAndHtmlSource result = new ScreenshotAndHtmlSource(
                            pngFile, htmlFile.exists() && htmlFile.length() > 0 ? htmlFile : null);
                    VerboseLogging.logDebugIfVerbose(logger, "Screenshot captured: {} -> {}",
                            screenshotName, pngFile.getName());
                    ListenerPerfStats.incrementScreenshot();
                    return result;
                }
                VerboseLogging.logInfoIfVerbose(logger, "Screenshot file not found: {}", screenshotPath);
            }
        } catch (Exception e) {
            VerboseLogging.logInfoIfVerbose(logger, "Failed to capture screenshot: {}", screenshotName, e);
        } finally {
            TestContextHolder.get().set(TAKING_SCREENSHOT_KEY, Boolean.FALSE);
        }
        return null;
    }

    /** 仅替换非 a-zA-Z0-9_- 字符（用于文件名）。 */
    static String sanitizeFilename(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "unnamed";
        }
        return SANITIZE_FILENAME_PATTERN.matcher(name).replaceAll("_");
    }

    /** 替换所有非 a-zA-Z0-9 字符（用于 display name）。 */
    static String sanitizeName(String name) {
        return name == null ? "unnamed" : SANITIZE_NAME_PATTERN.matcher(name).replaceAll("_");
    }

    /** scenario 结束时清理 per-context 重入标记（由 TestContext.resetForCurrentThread 统一解绑）。 */
    static void clearThreadState() {
        TestContextHolder.get().remove(TAKING_SCREENSHOT_KEY);
    }
}
