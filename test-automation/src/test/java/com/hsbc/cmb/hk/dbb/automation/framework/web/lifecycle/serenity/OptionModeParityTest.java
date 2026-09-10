package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.scenario.ScenarioLifecycle;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 偏差 B-2 表征测试（浏览器选项治理专项，doc18 §4.2）：仅固化 Feature / Scenario 两种模式在
 * 同一组自定义选项下的<b>当前</b>清理契约差异，不改行为。作为回归基线，明确记录两模式的差异，
 * 防止后续误改破坏「Feature 跨 scenario 保留登录态、Scenario 全清」的既有契约。
 */
public class OptionModeParityTest {

    private static final String STORAGE_STATE_JSON =
            "{\"cookies\":[{\"name\":\"sid\",\"value\":\"abc\"}],\"origins\":[]}";

    private final CustomOptionsManager customOptions = CustomOptionsManager.getInstance();

    @After
    public void tearDown() {
        TestContextHolder.get().remove(PlaywrightManager.CONTEXT_KEY);
        TestContextHolder.get().remove(PlaywrightManager.PAGE_KEY);
        customOptions.removeAllThreadLocals();
    }

    private void installAliveMockContext() {
        Browser browser = mock(Browser.class);
        when(browser.isConnected()).thenReturn(true);
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.browser()).thenReturn(browser);
        TestContextHolder.get().set(PlaywrightManager.CONTEXT_KEY, ctx);
    }

    @Test
    public void scenarioMode_clearsAllCustomOptions_includingStorageState() {
        customOptions.setStorageState(STORAGE_STATE_JSON);
        customOptions.setLocale("en-US");

        ScenarioLifecycle.cleanupForScenario();

        assertNull("Scenario 模式不应保留 storageState", customOptions.getStorageState());
        assertNull("Scenario 模式应清除 locale", customOptions.getLocale());
        // cleanupForScenario → removeAllThreadLocals 清除 flag；消费者 customFlag!=null&&customFlag 安全处理 null
        assertNull("Scenario 模式 flag 应被清除（返回 null）", customOptions.isCustomContextOptionsFlag());
    }

    @Test
    public void featureMode_preservesStorageState_butClearsOtherOptions() {
        customOptions.setStorageState(STORAGE_STATE_JSON);
        customOptions.setLocale("en-US");
        installAliveMockContext();

        PlaywrightSerenityBridge.resetCustomContextOptionsForFeatureMode();

        assertEquals("Feature 模式应保留 storageState（跨 scenario 复用登录态）",
                STORAGE_STATE_JSON, customOptions.getStorageState());
        assertNull("Feature 模式应清除非 session 配置 locale", customOptions.getLocale());
        // 存活 context 下 flag 为 null（非 false）；storageState 应用需待下次重建经 flag=true 触发
        assertNull("存活 context 下 flag 应为 null", customOptions.isCustomContextOptionsFlag());
    }
}
