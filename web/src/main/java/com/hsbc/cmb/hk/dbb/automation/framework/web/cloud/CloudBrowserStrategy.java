package com.hsbc.cmb.hk.dbb.automation.framework.web.cloud;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * BrowserStack 云端浏览器启动策略：{@code playwright.*.connect()} 到 BrowserStack CDP endpoint。
 */
public class CloudBrowserStrategy implements BrowserStrategy {

    private static final Logger logger = LoggerFactory.getLogger(CloudBrowserStrategy.class);

    @Override
    public int maxRetries() {
        return 1;
    }

    @Override
    public void injectEnvironment(Map<String, String> env) {
        BrowserStackManager.injectProxyEnv(env);
    }

    @Override
    public Browser connect(Playwright playwright, String browserType, BrowserType.LaunchOptions launchOptions) {
        logger.info("Using BrowserStack for browser: {}", browserType);
        BrowserStackManager.setCurrentSessionId("auto-" + System.currentTimeMillis());
        Browser browser = BrowserStackManager.connect(playwright);
        logger.info("[BrowserStack] Connected successfully via CDP");
        return browser;
    }

    @Override
    public void cleanup() {
        BrowserStackManager.cleanup();
    }
}
