package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * applyStorageState（1.59+ 就地换会话，免重建）固化测试（走 DI seam + 直设 CONTEXT_KEY，全程 mock，不启动真实浏览器）：
 * 验证有活 Context 时在 Context 上 setStorageState（不走重建）、无活 Context 时退化为设置 customOptions、Path 变体、null 安全。
 */
public class PlaywrightManagerApplyStorageStateTest {

    @AfterEach
    public void tearDown() {
        TestContextHolder.get().remove(PlaywrightManager.CONTEXT_KEY);
        // 清理 customOptions 的 storageState（null 安全：等价移除，避免跨测试串扰）
        PlaywrightManager.customOptions().setStorageStateWithoutRebuild(null);
        PlaywrightManager.customOptions().setStorageStatePathWithoutRebuild(null);
        PlaywrightManager.resetProvider();
    }

    private static BrowserContext liveContext() {
        BrowserContext ctx = mock(BrowserContext.class);
        Browser browser = mock(Browser.class);
        when(ctx.browser()).thenReturn(browser);
        when(browser.isConnected()).thenReturn(true);
        TestContextHolder.get().set(PlaywrightManager.CONTEXT_KEY, ctx);
        return ctx;
    }

    @Test
    public void applyStorageState_appliesInPlaceWhenLiveContextExists() {
        BrowserContext ctx = liveContext();
        String json = "{\"cookies\":[],\"origins\":[]}";

        PlaywrightManager.applyStorageState(json);

        // 就地换会话：在 Context 上 setStorageState(Path) 被调用（不走重建）
        verify(ctx, times(1)).setStorageState(any(Path.class));
        // customOptions 同步（不置重建 flag）
        assertEquals(json, PlaywrightManager.customOptions().getStorageState());
    }

    @Test
    public void applyStorageState_skipsInPlaceWhenNoLiveContext() {
        TestContextHolder.get().remove(PlaywrightManager.CONTEXT_KEY);
        BrowserContext ctx = mock(BrowserContext.class); // 未注入 CONTEXT_KEY

        String json = "{\"cookies\":[],\"origins\":[]}";
        PlaywrightManager.applyStorageState(json);

        verify(ctx, never()).setStorageState(any(Path.class));
        // 退化为设置 customOptions，待下次 getContext 创建时应用
        assertEquals(json, PlaywrightManager.customOptions().getStorageState());
    }

    @Test
    public void applyStorageStatePath_appliesInPlace() {
        BrowserContext ctx = liveContext();
        Path p = Paths.get("target/.sessions/x.json");

        PlaywrightManager.applyStorageStatePath(p);

        verify(ctx, times(1)).setStorageState(eq(p));
        assertEquals(p, PlaywrightManager.customOptions().getStorageStatePath());
    }

    @Test
    public void applyStorageState_nullSafe() {
        TestContextHolder.get().remove(PlaywrightManager.CONTEXT_KEY);
        BrowserContext ctx = mock(BrowserContext.class);

        PlaywrightManager.applyStorageState(null);
        PlaywrightManager.applyStorageState("");
        verify(ctx, never()).setStorageState(any());
        assertNull(PlaywrightManager.customOptions().getStorageState());
    }
}
