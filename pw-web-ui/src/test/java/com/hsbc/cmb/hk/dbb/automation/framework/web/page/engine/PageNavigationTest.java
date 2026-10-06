package com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.AccessDeniedException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.NavigationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WEB-P1-5 种子测试：导航子模块（无浏览器，纯 Mockito 隔离 BasePage/Page）。
 * 覆盖 url/title 委托、loadState→WaitUntilState 映射、导航异常收敛为 NavigationException、刷新/前进后退/setContent 编排。
 */
public class PageNavigationTest {

    private static BasePage bp() {
        BasePage bp = mock(BasePage.class);
        Page page = mock(Page.class);
        PlaywrightConfigManager config = mock(PlaywrightConfigManager.class);
        when(bp.getPage()).thenReturn(page);
        when(bp.getConfig()).thenReturn(config);
        return bp;
    }

    @Test
    public void getCurrentUrl_delegatesToPage() {
        BasePage bp = bp();
        Page page = bp.getPage();
        when(page.url()).thenReturn("https://example.com");
        assertEquals("https://example.com", PageNavigation.getCurrentUrl(bp));
        verify(page).url();
    }

    @Test
    public void getTitle_delegatesToPage() {
        BasePage bp = bp();
        Page page = bp.getPage();
        when(page.title()).thenReturn("Title");
        assertEquals("Title", PageNavigation.getTitle(bp));
    }

    @Test
    public void navigateTo_mapsLoadStateToWaitUntil_andResetsFrameContext() {
        for (String state : new String[]{"networkidle", "domcontentloaded", "commit", "load", "unknown"}) {
            BasePage bp = bp();
            Page page = bp.getPage();
            PlaywrightConfigManager config = bp.getConfig();
            when(config.getPageLoadState()).thenReturn(state);
            when(config.getNavigationTimeout()).thenReturn(30000);

            PageNavigation.navigateTo(bp, "https://x.com");

            // 各 loadState 分支均被本循环覆盖；选项对象已构建并传入 navigate（waitUntil 映射在主线已按 state 分支赋值）
            verify(page).navigate(eq("https://x.com"), any(Page.NavigateOptions.class));
            verify(bp).resetFrameContextAfterNavigation();
        }
    }

    @Test
    public void navigateTo_timeoutError_mapsToNavigationException() {
        BasePage bp = bp();
        Page page = bp.getPage();
        PlaywrightConfigManager config = bp.getConfig();
        when(config.getPageLoadState()).thenReturn("load");
        when(config.getNavigationTimeout()).thenReturn(30000);
        when(page.navigate(anyString(), any())).thenThrow(new TimeoutError("boom"));

        NavigationException ex = assertThrows(NavigationException.class, () -> PageNavigation.navigateTo(bp, "https://x.com"));
        assertTrue( ex.getMessage().contains("https://x.com"), "异常应携带目标 URL 便于定位");
    }

    @Test
    public void navigateTo_playwrightException_mapsToNavigationException() {
        BasePage bp = bp();
        Page page = bp.getPage();
        PlaywrightConfigManager config = bp.getConfig();
        when(config.getPageLoadState()).thenReturn("load");
        when(config.getNavigationTimeout()).thenReturn(30000);
        when(page.navigate(anyString(), any())).thenThrow(new PlaywrightException("boom"));

        assertThrows(NavigationException.class, () -> PageNavigation.navigateTo(bp, "https://x.com"));
    }

    @Test
    public void refresh_reloadsAndResetsFrameContext() {
        BasePage bp = bp();
        Page page = bp.getPage();
        PageNavigation.refresh(bp);
        verify(page).reload();
        verify(bp).resetFrameContextAfterNavigation();
    }

    @Test
    public void backAndForward_navigateAndResetFrameContext() {
        BasePage bp = bp();
        Page page = bp.getPage();
        PageNavigation.back(bp);
        verify(page).goBack();
        PageNavigation.forward(bp);
        verify(page).goForward();
        verify(bp, times(2)).resetFrameContextAfterNavigation();
    }

    @Test
    public void setContent_replacesAndResetsFrameContext() {
        BasePage bp = bp();
        Page page = bp.getPage();
        PageNavigation.setContent(bp, "<html/>");
        verify(page).setContent("<html/>");
        verify(bp).resetFrameContextAfterNavigation();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 驱动竞态自愈（2026-09-26）：判据见 DriverRaceErrors，开关见
    // playwright.navigation.selfheal.enabled。契约：有界（至多一次）、可关、
    // 可观测、绝不掩盖失败（原异常 addSuppressed 保留）。
    // ═══════════════════════════════════════════════════════════════════════════════

    private static final String NAV_URL = "https://sit.example.com/portalserver/gbbr/en-us/home";

    /** 可导航的 BasePage 桩：已就绪的 loadState / 超时配置（避免每个用例重复 stub）。 */
    private static BasePage navigable() {
        BasePage bp = bp();
        PlaywrightConfigManager config = bp.getConfig();
        when(config.getPageLoadState()).thenReturn("domcontentloaded");
        when(config.getNavigationTimeout()).thenReturn(30000);
        return bp;
    }

    /** 驱动侧"对象句柄生命周期竞态"文案（playwright 1.62 {@code Connection.getExistingObject} 唯一产出）。 */
    private static PlaywrightException objectGoneRace() {
        return new PlaywrightException("Object doesn't exist: response@1256921968353cbde3445ff7a129b1ce");
    }

    /** 驱动侧"导航被另一次导航打断"文案（典型：会话校验 home → 302 /logon）。 */
    private static PlaywrightException interruptedRace() {
        return new PlaywrightException("Navigation to \"" + NAV_URL + "/logon\" is interrupted by another "
                + "navigation to \"" + NAV_URL + "\"");
    }

    @Test
    @DisplayName("对象句柄竞态：先收敛再重试一次并成功（不抛异常）")
    public void navigateTo_objectGoneRace_selfHealsOnceAndSucceeds() {
        BasePage bp = navigable();
        Page page = bp.getPage();
        Response mainResponse = mock(Response.class); // navigate(String, NavigateOptions) 的返回类型是 Response
        when(page.navigate(anyString(), any(Page.NavigateOptions.class)))
                .thenThrow(objectGoneRace()).thenReturn(mainResponse);

        PageNavigation.navigateTo(bp, NAV_URL);

        verify(page, times(2)).navigate(eq(NAV_URL), any(Page.NavigateOptions.class));
        //  两次 waitForLoadState 按超时区分（同一 LoadState、不同用途）：
        //    · 5s（NAVIGATION_SETTLE_TIMEOUT_MS）＝ 重试前的文档收敛等待，否则重试会再撞上在途导航/事件分发；
        //    · 30s（配置的 navigationTimeout）＝ 两步导航的第二步：等业务配置的加载状态。
        verify(page).waitForLoadState(eq(LoadState.DOMCONTENTLOADED),
                argThat((Page.WaitForLoadStateOptions o) -> o != null && Double.valueOf(5_000).equals(o.timeout)));
        verify(page).waitForLoadState(eq(LoadState.DOMCONTENTLOADED),
                argThat((Page.WaitForLoadStateOptions o) -> o != null && Double.valueOf(30_000).equals(o.timeout)));
        verify(bp).resetFrameContextAfterNavigation();
    }

    @Test
    @DisplayName("导航被打断竞态：先收敛再重试一次并成功")
    public void navigateTo_interruptedRace_selfHealsOnceAndSucceeds() {
        BasePage bp = navigable();
        Page page = bp.getPage();
        Response mainResponse = mock(Response.class);
        when(page.navigate(anyString(), any(Page.NavigateOptions.class)))
                .thenThrow(interruptedRace()).thenReturn(mainResponse);

        PageNavigation.navigateTo(bp, NAV_URL);

        verify(page, times(2)).navigate(eq(NAV_URL), any(Page.NavigateOptions.class));
    }

    @Test
    @DisplayName("非竞态错误（语义失败）绝不重试：一次调用即抛 NavigationException")
    public void navigateTo_nonRaceError_doesNotRetry() {
        BasePage bp = navigable();
        Page page = bp.getPage();
        when(page.navigate(anyString(), any())).thenThrow(new PlaywrightException("net::ERR_CONNECTION_REFUSED"));

        assertThrows(NavigationException.class, () -> PageNavigation.navigateTo(bp, NAV_URL));

        verify(page, times(1)).navigate(eq(NAV_URL), any(Page.NavigateOptions.class));
        verify(page, never()).waitForLoadState(any(LoadState.class), any(Page.WaitForLoadStateOptions.class));
    }

    @Test
    @DisplayName("自愈至多一次：两次都失败仍抛 NavigationException，原竞态异常 addSuppressed 保留")
    public void navigateTo_selfHealAlsoFails_keepsBothCauses() {
        BasePage bp = navigable();
        Page page = bp.getPage();
        PlaywrightException first = objectGoneRace();
        PlaywrightException second = objectGoneRace();
        when(page.navigate(anyString(), any())).thenThrow(first).thenThrow(second);

        NavigationException ex = assertThrows(NavigationException.class, () -> PageNavigation.navigateTo(bp, NAV_URL));

        verify(page, times(2)).navigate(eq(NAV_URL), any(Page.NavigateOptions.class)); // 不循环、不退避
        assertSame(second, ex.getCause(), "cause 应为重试失败原因（更接近现状）");
        assertEquals(1, ex.getSuppressed().length, "原始竞态异常必须经 addSuppressed 保留（不掩盖根因）");
        assertSame(first, ex.getSuppressed()[0]);
        assertTrue(ex.getMessage().contains("OBJECT_LIFECYCLE_RACE"), "失败信息须携带竞态类别，便于统计与检索");
    }

    @Test
    @DisplayName("开关关闭（严格模式）：命中竞态也不重试")
    public void navigateTo_selfHealDisabled_doesNotRetry() {
        System.setProperty("playwright.navigation.selfheal.enabled", "false");
        FrameworkConfigManager.disableCache(); // 确保读到本次系统属性而非缓存值
        try {
            BasePage bp = navigable();
            Page page = bp.getPage();
            when(page.navigate(anyString(), any())).thenThrow(objectGoneRace());

            assertThrows(NavigationException.class, () -> PageNavigation.navigateTo(bp, NAV_URL));

            verify(page, times(1)).navigate(eq(NAV_URL), any(Page.NavigateOptions.class));
        } finally {
            System.clearProperty("playwright.navigation.selfheal.enabled");
            FrameworkConfigManager.clearCache();
            FrameworkConfigManager.enableCache();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 导航落点状态校验：服务端 403（访问被拒）时导航"成功"也必须直接抛错，
    // 否则真因会被后续"元素不可见超时"掩盖（现象就是"页面导航出现 403 就不动了"）。
    // 判定硬编码、不加配置，见 NavigationStatusGuard / NavigationStatusGuardTest。
    // ═══════════════════════════════════════════════════════════════════════════════

    /** 可导航的 BasePage 桩 + 指定状态码的导航响应（403/5xx 场景共用）。 */
    private static BasePage navigableWithStatus(String finalUrl, int status) {
        BasePage bp = navigable();
        Page page = bp.getPage();
        Response response = mock(Response.class);
        when(response.status()).thenReturn(status);
        when(response.url()).thenReturn(finalUrl);
        when(response.headers()).thenReturn(Map.of());
        when(page.navigate(anyString(), any())).thenReturn(response);
        return bp;
    }

    @Test
    @DisplayName("导航落点 403：抛 AccessDeniedException（含状态码/URL），不再静默继续")
    public void navigateTo_blocked403_throwsAccessDeniedException() {
        BasePage bp = navigableWithStatus("https://sit.example.com/error/403", 403);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> PageNavigation.navigateTo(bp, NAV_URL));

        assertEquals(403, ex.getStatus());
        assertEquals(NAV_URL, ex.getRequestedUrl());
        assertTrue(ex.getMessage().contains("403"), "失败信息须含状态码");
        assertTrue(ex.getMessage().contains(NAV_URL), "失败信息须含请求 URL");
    }

    @Test
    @DisplayName("导航落点 401：同属访问被拒，也直接抛错")
    public void navigateTo_blocked401_throwsAccessDeniedException() {
        BasePage bp = navigableWithStatus("https://sit.example.com/error/401", 401);

        assertEquals(401, assertThrows(AccessDeniedException.class,
                () -> PageNavigation.navigateTo(bp, NAV_URL)).getStatus());
    }

    @Test
    @DisplayName("导航落点 5xx：不属访问被拒一族（本轮只拦 401/403/407），放行不误伤")
    public void navigateTo_gatewayError_isTolerated() {
        BasePage bp = navigableWithStatus("https://sit.example.com/error/503", 503);
        assertDoesNotThrow(() -> PageNavigation.navigateTo(bp, NAV_URL));
    }

    @Test
    @DisplayName("导航落点 404：默认放行（企业级默认只拦 AUTH + SERVER）")
    public void navigateTo_404ByDefault_isTolerated() {
        BasePage bp = navigableWithStatus("https://sit.example.com/missing", 404);
        assertDoesNotThrow(() -> PageNavigation.navigateTo(bp, NAV_URL));
        verify(bp).resetFrameContextAfterNavigation();
    }

    @Test
    @DisplayName("刷新落点 403：同样直接抛错（refresh 不是法外之地）")
    public void refresh_blocked403_throwsAccessDeniedException() {
        BasePage bp = navigable();
        Page page = bp.getPage();
        Response response = mock(Response.class);
        when(response.status()).thenReturn(403);
        when(response.url()).thenReturn("https://sit.example.com/error/403");
        when(response.headers()).thenReturn(Map.of());
        when(page.reload()).thenReturn(response);

        assertThrows(AccessDeniedException.class, () -> PageNavigation.refresh(bp));
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 两步导航（2026-10-06）：第一步固定 COMMIT 只为"尽快拿到响应状态码"，第二步再等配置的加载状态。
    // 契约：403 必须在第一步就抛 —— 绝不因为"加载状态没达到"而退化成"导航超时"。
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("403：用 COMMIT 拿到响应即抛错，且【不等】配置的加载状态（networkidle 也没等）")
    public void navigateTo_403_throwsBeforeWaitingForLoadState() {
        BasePage bp = navigable();
        Page page = bp.getPage();
        PlaywrightConfigManager config = bp.getConfig();
        when(config.getPageLoadState()).thenReturn("networkidle"); // 故意配成最"黏"的状态
        Response response = mock(Response.class);
        when(response.status()).thenReturn(403);
        when(response.url()).thenReturn("https://sit.example.com/error/403");
        when(response.headers()).thenReturn(Map.of());
        when(page.navigate(anyString(), any())).thenReturn(response);

        ArgumentCaptor<Page.NavigateOptions> options = ArgumentCaptor.forClass(Page.NavigateOptions.class);
        assertThrows(AccessDeniedException.class, () -> PageNavigation.navigateTo(bp, NAV_URL));
        verify(page).navigate(eq(NAV_URL), options.capture());

        assertEquals(WaitUntilState.COMMIT, options.getValue().waitUntil,
                "第一步必须用 COMMIT：否则 403 页面等不到 load/networkidle 时会退化成'导航超时'");
        verify(page, never()).waitForLoadState(eq(LoadState.NETWORKIDLE), any(Page.WaitForLoadStateOptions.class));
        verify(bp, never()).resetFrameContextAfterNavigation();
    }

    @Test
    @DisplayName("正常路径：COMMIT 拿响应后，再显式等配置的加载状态")
    public void navigateTo_success_waitsForConfiguredState() {
        BasePage bp = navigable();
        Page page = bp.getPage();
        PlaywrightConfigManager config = bp.getConfig();
        when(config.getPageLoadState()).thenReturn("domcontentloaded");
        when(page.navigate(anyString(), any())).thenReturn(null); // about:blank 语义：无主文档响应

        PageNavigation.navigateTo(bp, NAV_URL);

        verify(page).waitForLoadState(eq(LoadState.DOMCONTENTLOADED), any(Page.WaitForLoadStateOptions.class));
        verify(bp).resetFrameContextAfterNavigation();
    }

    @Test
    @DisplayName("加载状态等待超时：仍按原有语义映射为 NavigationException")
    public void navigateTo_loadStateTimeout_mapsToNavigationException() {
        BasePage bp = navigable();
        Page page = bp.getPage();
        PlaywrightConfigManager config = bp.getConfig();
        when(config.getPageLoadState()).thenReturn("domcontentloaded");
        when(page.navigate(anyString(), any())).thenReturn(null);
        // waitForLoadState 返回 void，故用 doThrow 而非 when(...).thenThrow(...)
        doThrow(new TimeoutError("load state timeout"))
                .when(page).waitForLoadState(eq(LoadState.DOMCONTENTLOADED), any(Page.WaitForLoadStateOptions.class));

        assertThrows(NavigationException.class, () -> PageNavigation.navigateTo(bp, NAV_URL));
    }
}
