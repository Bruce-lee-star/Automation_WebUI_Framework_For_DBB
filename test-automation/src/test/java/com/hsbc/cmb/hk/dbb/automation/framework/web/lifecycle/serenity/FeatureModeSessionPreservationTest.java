package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 偏差 B-1 表征测试（浏览器选项治理专项，doc18 §4.1）：仅固化 Feature 模式会话保留的<b>当前</b>行为，不改行为。
 *
 * <p>覆盖 {@link PlaywrightSerenityBridge#resetCustomContextOptionsForFeatureMode()} 的 storageState 保留
 * 与「修复问题3」竞态快照分支（context 存活 / 断开 / null 三种快照结果）。测试用 Mockito 模拟
 * {@link BrowserContext}/{@link Browser}（不启动真实浏览器），直接断言 per-thread TestContext 状态。
 *
 * <p>关键语义（当前实现，作为回归基线）：
 * <ul>
 *   <li>{@code cleanupThreadLocals(false)} 会清除全部自定义配置键<b>含 flag</b>，随后仅当 context 死亡才经
 *       {@code enableCustomOptions()} 重新置位 flag；故存活 context 下 flag 为 <b>null</b>（非 false）。
 *       消费者 {@code PlaywrightContextManager} 以 {@code customFlag != null && customFlag} 安全处理 null。</li>
 *   <li>storageState 由 {@code preserveStorageState} 原样回填（与 flag 解耦）；但 storageState 的实际「应用」
 *       发生在 {@code configureCustomContextOptions}，而该方法被 flag 门控——存活 context 重置后 flag=null，
 *       需待下一次 Context 重建（flag 重新置位）才生效。此为偏差 B-1 待治理点的当前语义，修复前勿改断言。</li>
 * </ul>
 */
public class FeatureModeSessionPreservationTest {

    private static final String STORAGE_STATE_JSON =
            "{\"cookies\":[{\"name\":\"sid\",\"value\":\"abc\"}],\"origins\":[]}";

    private final CustomOptionsManager customOptions = CustomOptionsManager.getInstance();

    @After
    public void tearDown() {
        // 隔离 per-thread 状态，避免串扰
        TestContextHolder.get().remove(PlaywrightManager.CONTEXT_KEY);
        TestContextHolder.get().remove(PlaywrightManager.PAGE_KEY);
        customOptions.removeAllThreadLocals();
    }

    /** 安装一个 mock context：isConnected 由参数决定；null ctx 由调用方自行不设置 CONTEXT_KEY。 */
    private BrowserContext installMockContext(boolean connected) {
        Browser browser = mock(Browser.class);
        when(browser.isConnected()).thenReturn(connected);
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.browser()).thenReturn(browser);
        TestContextHolder.get().set(PlaywrightManager.CONTEXT_KEY, ctx);
        return ctx;
    }

    @Test
    public void featureReset_preservesStorageState_whenContextNull() {
        customOptions.setStorageState(STORAGE_STATE_JSON); // 先设选项（此时 CONTEXT_KEY 为空，scheduleContextRebuild 无操作）
        // 不设 CONTEXT_KEY → 快照为 null → contextDead=true

        PlaywrightSerenityBridge.resetCustomContextOptionsForFeatureMode();

        assertEquals("Feature 模式应保留 storageState", STORAGE_STATE_JSON, customOptions.getStorageState());
        assertTrue("context 为 null 时应置位 flag 以应用 storageState",
                Boolean.TRUE.equals(customOptions.isCustomContextOptionsFlag()));
        assertNull("非 session 配置（locale）应被清除", customOptions.getLocale());
    }

    @Test
    public void featureReset_preservesStorageState_whenContextAlive_andFlagStaysNull() {
        customOptions.setStorageState(STORAGE_STATE_JSON);
        installMockContext(true); // 存活 context

        PlaywrightSerenityBridge.resetCustomContextOptionsForFeatureMode();

        assertEquals("Feature 模式应保留 storageState", STORAGE_STATE_JSON, customOptions.getStorageState());
        // 存活 context：flag 被清除且未重新置位 → 返回 null（非 false）；消费者 customFlag!=null&&customFlag 安全处理
        assertNull("存活 context 下 flag 应为 null（修复问题3：避免 flag 置位但 context 不可用；"
                + "storageState 需待下次重建经 flag=true 才应用）", customOptions.isCustomContextOptionsFlag());
        assertNull("非 session 配置（locale）应被清除", customOptions.getLocale());
    }

    @Test
    public void featureReset_preservesStorageState_whenContextDisconnected_andSetsFlag() {
        customOptions.setStorageState(STORAGE_STATE_JSON);
        installMockContext(false); // context 存在但已断开

        PlaywrightSerenityBridge.resetCustomContextOptionsForFeatureMode();

        assertEquals("Feature 模式应保留 storageState", STORAGE_STATE_JSON, customOptions.getStorageState());
        assertTrue("已断开 context 应置位 flag 以应用 storageState",
                Boolean.TRUE.equals(customOptions.isCustomContextOptionsFlag()));
        assertNull("非 session 配置（locale）应被清除", customOptions.getLocale());
    }

    @Test
    public void featureReset_storageStateSurvivesRepeatedOptionSets_andMultipleResets() {
        // 第一轮：设多个选项（期间 CONTEXT_KEY 为空，scheduleContextRebuild 无操作）
        customOptions.setStorageState(STORAGE_STATE_JSON);
        customOptions.setLocale("en-US");
        customOptions.setViewportSize(1000, 800);
        installMockContext(true);
        PlaywrightSerenityBridge.resetCustomContextOptionsForFeatureMode();

        assertEquals("storageState 应保留", STORAGE_STATE_JSON, customOptions.getStorageState());
        assertNull("locale 应清除", customOptions.getLocale());
        assertNull("viewport 应清除", customOptions.getViewportWidth());
        assertNull("存活 context 下 flag 应为 null", customOptions.isCustomContextOptionsFlag());

        // 第二轮：再次设选项 + 重新安装存活 context（setStorageState 的 scheduleContextRebuild 会移除 CONTEXT_KEY）
        // + Feature 重置，storageState 不应丢失
        customOptions.setStorageState(STORAGE_STATE_JSON);
        customOptions.setTimezone("Asia/Shanghai");
        installMockContext(true);
        PlaywrightSerenityBridge.resetCustomContextOptionsForFeatureMode();

        assertEquals("跨多次设选项 + Feature 重置，storageState 仍应保留", STORAGE_STATE_JSON, customOptions.getStorageState());
        assertNull("timezone 应清除", customOptions.getTimezoneId());
        assertNull("存活 context 下 flag 应为 null", customOptions.isCustomContextOptionsFlag());
    }
}
