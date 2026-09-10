package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.hsbc.cmb.hk.dbb.automation.framework.web.core.RuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.delegate.PageNavigation;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.delegate.PageWaits;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WEB-P0-2 验收 ②：证明经 DI seam 注入 mock 后，<b>BasePage 真实构造</b>与
 * {@link PageWaits} / {@link PageNavigation} 均可脱离真实浏览器运行。
 *
 * <p>与既有 {@code PageWaitsTest} / {@code PageNavigationTest} 的区别：那两个用
 * {@code mock(BasePage.class)}（Objenesis 绕过构造），本类走<b>真实构造 + 真实
 * {@code getPage()}</b>链路，即验证 seam 对「构造不再启动浏览器 + 运行时对象可替换」的解锁。
 *
 * @apiNote 仅框架测试使用；必须在 {@code @After} 复位 provider，避免污染后续测试。
 */
public class BasePageSeamTest {

    @After
    public void tearDown() {
        PlaywrightManager.resetProvider();
    }

    /** 注入 mock provider（Page/Context/Browser 均为 mock），返回被注入的 mock Page。 */
    private static Page installMockProvider() {
        Page page = mock(Page.class);
        BrowserContext context = mock(BrowserContext.class);
        when(page.context()).thenReturn(context);

        RuntimeProvider provider = mock(RuntimeProvider.class);
        when(provider.getPage()).thenReturn(page);
        when(provider.getContext()).thenReturn(context);
        when(provider.getBrowser()).thenReturn(mock(Browser.class));
        PlaywrightManager.setProvider(provider);
        return page;
    }

    @Test
    public void construction_doesNotAcquireRuntimeObjects() {
        installMockProvider();
        RuntimeProvider provider = PlaywrightManager.getProvider();

        new BasePage() {
        };

        // 构造仅做注解字段初始化：不得获取任何 Playwright 运行时对象（即不启动/连接浏览器）。
        // 该断言不依赖全局初始化状态，任何 JVM 环境下均可稳定生效（此前基于 FrameworkCore.isInitialized()
        // 的断言在框架已初始化时会退化为准恒真，故改为运行时对象获取次数这一更硬的证据）。
        verify(provider, never()).getPlaywright();
        verify(provider, never()).getBrowser();
        verify(provider, never()).getContext();
        verify(provider, never()).getPage();
    }

    @Test
    public void getPage_returnsSeamInjectedPage() {
        Page page = installMockProvider();
        BasePage bp = new BasePage() {
        };
        assertSame(page, bp.getPage());
    }

    @Test
    public void pageWaits_runsOnSeamInjectedPage() {
        Page page = installMockProvider();
        BasePage bp = new BasePage() {
        };

        PageWaits.waitForNetworkIdle(bp, 3);

        verify(page).waitForLoadState(eq(LoadState.NETWORKIDLE),
                any(Page.WaitForLoadStateOptions.class));
    }

    @Test
    public void pageNavigation_readsUrlFromSeamInjectedPage() {
        Page page = installMockProvider();
        when(page.url()).thenReturn("http://seam");
        BasePage bp = new BasePage() {
        };

        assertEquals("http://seam", PageNavigation.getCurrentUrl(bp));
    }
}
