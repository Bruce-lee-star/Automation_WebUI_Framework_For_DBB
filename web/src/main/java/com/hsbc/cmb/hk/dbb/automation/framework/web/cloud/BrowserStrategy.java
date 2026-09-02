package com.hsbc.cmb.hk.dbb.automation.framework.web.cloud;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;

import java.util.Map;

/**
 * 浏览器启动策略。
 * <p>消除 {@code PlaywrightManager} 中 "if (BrowserStack enabled) cloud else local" 的硬编码分支，
 * 使新增浏览器接入方式（其它云厂商、Selenium Grid 等）时无需修改 lifecycle 源码，符合 OCP（T4-1 / T4-3）。</p>
 */
public interface BrowserStrategy {

    /** 浏览器启动重试次数：云端连接失败重试无意义，返回 1；本地允许进程抖动重试，返回 2。 */
    int maxRetries();

    /**
     * 在构造 Node.js 子进程环境变量时注入策略相关条目（如 BrowserStack Local 代理透传）。
     * 默认空实现；本地策略无需注入。
     */
    default void injectEnvironment(Map<String, String> env) { }

    /**
     * 建立浏览器连接（本地 {@code launch} / 云端 {@code connect}）。
     *
     * @throws IllegalArgumentException 不支持的浏览器类型
     */
    Browser connect(Playwright playwright, String browserType, BrowserType.LaunchOptions launchOptions);

    /** 清理策略相关资源（如关闭云端隧道）。默认空实现。 */
    default void cleanup() { }
}
