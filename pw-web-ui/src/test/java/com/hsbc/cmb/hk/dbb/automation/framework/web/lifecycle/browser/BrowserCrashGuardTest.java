package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BrowserCrashGuard} 崩溃识别三重判定（W-10）与重跑上限配置（W-9）的契约测试。
 * 全部为纯逻辑、不启动真实浏览器；配置经 {@code System.setProperty} 覆盖并 {@link BrowserCrashGuard#refreshCrashSignatures()} 刷新。
 */
class BrowserCrashGuardTest {

    private static final String SIGNATURES_KEY = "serenity.playwright.concurrent.crash.signatures";
    private static final String CORROBORATION_KEY = "serenity.playwright.concurrent.crash.corroboration.enabled";
    private static final String MAX_REPLAY_KEY = "playwright.concurrent.crash.guard.max.replay";

    @AfterEach
    void restoreDefaults() {
        System.clearProperty(SIGNATURES_KEY);
        System.clearProperty(CORROBORATION_KEY);
        System.clearProperty(MAX_REPLAY_KEY);
        BrowserCrashGuard.refreshCrashSignatures();
    }

    @Test
    void messageSignatureMatch_default_whiteList() {
        assertTrue(BrowserCrashGuard.isCrash(new RuntimeException("Target crashed")), "默认白名单应命中 target crashed");
        assertTrue(BrowserCrashGuard.isCrash(new RuntimeException("browser has been closed")), "默认白名单应命中 browser has been closed");
        assertTrue(BrowserCrashGuard.isCrash(new RuntimeException("connection prematurely closed")), "默认白名单应命中 connection prematurely closed");
    }

    @Test
    void nonCrashMessage_not_matched() {
        assertFalse(BrowserCrashGuard.isCrash(new RuntimeException("element not found")), "普通业务异常不应判崩溃");
        assertFalse(BrowserCrashGuard.isCrash(new AssertionError("expected true but was false")), "断言失败不应判崩溃");
    }

    @Test
    void typeOnly_does_not_crash_per_W15() {
        // W-15 防误判：纯 Playwright 异常（元素超时/等待）类型虽匹配，但无断开事件 → 不判崩溃
        assertFalse(BrowserCrashGuard.isCrash(new PlaywrightException("waiting for selector to be visible")),
                "仅异常类型命中、无断开事件，不应判崩溃（防 W-15 假绿）");
        assertFalse(BrowserCrashGuard.isCrash(new PlaywrightException("locator timeout")),
                "仅异常类型命中、无断开事件，不应判崩溃");
    }

    @Test
    void configurable_signature_whiteList() {
        System.setProperty(SIGNATURES_KEY, "custom-sig-xyz");
        BrowserCrashGuard.refreshCrashSignatures();
        assertTrue(BrowserCrashGuard.isCrash(new RuntimeException("a custom-sig-xyz was observed")),
                "覆盖白名单后应命中自定义签名");
        assertFalse(BrowserCrashGuard.isCrash(new RuntimeException("Target crashed")),
                "覆盖白名单后默认签名不应再命中");
    }

    @Test
    void corroboration_disabled_falls_back_to_message_only() {
        System.setProperty(CORROBORATION_KEY, "false");
        // 消息签名路径独立于佐证开关，仍应判崩溃
        assertTrue(BrowserCrashGuard.isCrash(new RuntimeException("Target crashed")));
        // 仅类型、无消息、无事件 → 关掉佐证后更不应判崩溃
        assertFalse(BrowserCrashGuard.isCrash(new PlaywrightException("plain timeout")));
    }

    @Test
    void handleCorruption_unchanged_narrow_signature() {
        assertTrue(BrowserCrashGuard.isHandleCorruption(new RuntimeException("cannot find object to call __adopt__ foo")),
                "句柄损坏（__adopt__）应判为损坏");
        assertFalse(BrowserCrashGuard.isHandleCorruption(new RuntimeException("browser has been closed")),
                "普通断开不应误判为句柄损坏");
    }

    @Test
    void maxReplay_configurable() {
        assertEquals(1, BrowserCrashGuard.maxReplay(), "默认重跑上限应为 1");
        System.setProperty(MAX_REPLAY_KEY, "2");
        assertEquals(2, BrowserCrashGuard.maxReplay(), "配置后重跑上限应为 2");
        System.setProperty(MAX_REPLAY_KEY, "0");
        assertEquals(1, BrowserCrashGuard.maxReplay(), "非正配置应兜底为 1");
    }
}
