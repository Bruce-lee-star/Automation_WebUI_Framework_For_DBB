package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.media;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlaywrightScreenshotManager 契约测试（无浏览器，仅验证降级与配置默认值）。
 *
 * <p>真实 WebP/PNG 编码需活动页面，由 web 套件的失败截图链路间接覆盖；
 * 此处固化"无页面时安全返回 null"与"WebP 合规配置默认值"，防止误改破坏契约。
 */
class PlaywrightScreenshotManagerTest {

    @Test
    void takeScreenshot_returnsNullWhenNoPage() {
        // 当前线程无绑定页面，Serenity PNG 链路应安全降级返回 null（不抛 NPE/不初始化浏览器）
        assertNull(PlaywrightScreenshotManager.takeScreenshot("no-page"));
    }

    @Test
    void takeScreenshotWebp_returnsNullWhenNoPage() {
        // 无页面时 WebP 合规截图同样安全降级返回 null
        assertNull(PlaywrightScreenshotManager.takeScreenshotWebp("no-page"));
    }

    @Test
    void webpConfigDefaults() {
        assertTrue(WebFrameworkConfig.PLAYWRIGHT_WEBP_SCREENSHOT_ENABLED.getBooleanValue(),
                "WebP 合规截图默认应开启");
        assertEquals(80, WebFrameworkConfig.PLAYWRIGHT_WEBP_SCREENSHOT_QUALITY.getIntValue(),
                "WebP 质量默认值应为 80");
        assertTrue(WebFrameworkConfig.PLAYWRIGHT_WEBP_SCREENSHOT_ARCHIVE_DIR.getValue()
                        .endsWith("screenshots-webp"),
                "WebP 归档目录应独立于 Serenity 报告目录");
    }
}
