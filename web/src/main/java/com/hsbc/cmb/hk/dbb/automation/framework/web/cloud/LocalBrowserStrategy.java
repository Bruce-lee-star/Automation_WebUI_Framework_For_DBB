package com.hsbc.cmb.hk.dbb.automation.framework.web.cloud;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地浏览器启动策略：直接 {@code playwright.*.launch()}。
 */
public class LocalBrowserStrategy implements BrowserStrategy {

    private static final Logger logger = LoggerFactory.getLogger(LocalBrowserStrategy.class);

    @Override
    public int maxRetries() {
        return 2;
    }

    @Override
    public Browser connect(Playwright playwright, String browserType, BrowserType.LaunchOptions launchOptions) {
        return switch (browserType.toLowerCase()) {
            case "chromium" -> playwright.chromium().launch(launchOptions);
            case "firefox" -> playwright.firefox().launch(launchOptions);
            case "webkit" -> playwright.webkit().launch(launchOptions);
            default -> throw new IllegalArgumentException("Unsupported browser type: " + browserType);
        };
    }

    @Override
    public void cleanup() {
        // 保持与原 PlaywrightManager.cleanupAll 的无条件调用语义一致；
        // BrowserStackManager.cleanup() 在非 Local 隧道模式下为 no-op，安全。
        BrowserStackManager.cleanup();
    }
}
