package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.hsbc.cmb.hk.dbb.automation.framework.web.core.RuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BasePage 线程归属守卫（对齐 playwright-java 1.58.0 官方并发模型）：
 * Playwright 的 Page/Context/Locator 非线程安全，一个 BasePage 实例只能被绑定它的
 * scenario 线程使用。本测试固化 {@link BasePage#getPage()}（底层 {@code ensurePageValid}）
 * 的跨线程访问拒绝行为。
 *
 * <p>无浏览器依赖：经 WEB-P0-2 DI seam（{@link PlaywrightManager#setProvider}）注入 mock，
 * 仅在 JVM 内验证线程归属语义，与既有 {@code BasePagePageSwitchLockConcurrencyTest} 同范式。
 */
public class BasePageThreadOwnershipConcurrencyTest {

    private static RuntimeProvider mockProviderReturning(Page page, BrowserContext ctx) {
        RuntimeProvider p = mock(RuntimeProvider.class);
        when(p.getPage()).thenReturn(page);
        when(p.getContext()).thenReturn(ctx);
        return p;
    }

    @Test
    public void singleThreadUseSucceeds() {
        Page page = mock(Page.class);
        BrowserContext ctx = mock(BrowserContext.class);
        PlaywrightManager.setProvider(mockProviderReturning(page, ctx));
        try {
            BasePage bp = new BasePage() {};
            // 同一线程内反复使用不应抛异常，且返回的 Page 与注入一致
            assertSame(page, bp.getPage());
            bp.getPage();
        } finally {
            PlaywrightManager.resetProvider();
        }
    }

    @Test
    public void crossThreadAccessIsRejected() throws Exception {
        Page page = mock(Page.class);
        BrowserContext ctx = mock(BrowserContext.class);
        PlaywrightManager.setProvider(mockProviderReturning(page, ctx));
        try {
            BasePage bp = new BasePage() {};
            // 在主测试线程确立归属
            bp.getPage();

            ExecutorService ex = Executors.newSingleThreadExecutor();
            Future<Throwable> fut = ex.submit(() -> {
                try {
                    bp.getPage();
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Throwable thrown = fut.get(5, TimeUnit.SECONDS);
            ex.shutdown();

            assertNotNull("跨线程访问 BasePage 必须被拒绝（抛 IllegalStateException）", thrown);
            assertTrue("应为线程归属 IllegalStateException，实际: " + thrown,
                    thrown instanceof IllegalStateException);
        } finally {
            PlaywrightManager.resetProvider();
        }
    }

    @Test
    public void distinctInstancesOnDifferentThreadsBothSucceed() throws Exception {
        // 证明守卫是 per-instance 的：两个独立 BasePage 实例分别由各自线程使用均合法，
        // 不会互相误伤（隔离性由实例而非全局锁保证）。
        Page page = mock(Page.class);
        BrowserContext ctx = mock(BrowserContext.class);
        PlaywrightManager.setProvider(mockProviderReturning(page, ctx));
        try {
            ExecutorService ex = Executors.newFixedThreadPool(2);
            Future<Throwable> fa = ex.submit(() -> {
                try {
                    BasePage a = new BasePage() {};
                    a.getPage();
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Future<Throwable> fb = ex.submit(() -> {
                try {
                    BasePage b = new BasePage() {};
                    b.getPage();
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            assertNull("实例 A 在其线程内使用应成功", fa.get(5, TimeUnit.SECONDS));
            assertNull("实例 B 在其线程内使用应成功", fb.get(5, TimeUnit.SECONDS));
            ex.shutdown();
        } finally {
            PlaywrightManager.resetProvider();
        }
    }
}
