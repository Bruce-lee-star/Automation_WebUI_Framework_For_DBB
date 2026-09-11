package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.delegate;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WEB-P1-5 种子测试：等待/重试/断言子模块（无浏览器，纯 Mockito 隔离 BasePage/Page/PageElement）。
 * 覆盖网络空闲等待、可见性断言、重试与带校验重试。
 */
public class PageWaitsTest {

    @Test
    public void waitForNetworkIdle_waitsOnNetworkIdleLoadState() {
        BasePage bp = mock(BasePage.class);
        Page page = mock(Page.class);
        when(bp.getPage()).thenReturn(page);

        PageWaits.waitForNetworkIdle(bp, 5);

        verify(page).waitForLoadState(eq(LoadState.NETWORKIDLE), any());
    }

    @Test
    public void shouldBeVisible_passesWhenVisible() {
        BasePage bp = mock(BasePage.class);
        PageElement el = mock(PageElement.class);
        when(bp.locator(any())).thenReturn(el);
        when(el.isVisible()).thenReturn(true);

        PageWaits.shouldBeVisible(bp, "#x"); // 不抛
    }

    @Test
    public void shouldBeVisible_throwsWhenNotVisible() {
        BasePage bp = mock(BasePage.class);
        PageElement el = mock(PageElement.class);
        when(bp.locator(any())).thenReturn(el);
        when(el.isVisible()).thenReturn(false);

        assertThrows(ElementException.class, () -> PageWaits.shouldBeVisible(bp, "#x"));
    }

    @Test
    public void shouldBeNotVisible_passesWhenHidden() {
        BasePage bp = mock(BasePage.class);
        PageElement el = mock(PageElement.class);
        when(bp.locator(any())).thenReturn(el);
        when(el.isNotVisible()).thenReturn(true);

        PageWaits.shouldBeNotVisible(bp, "#x"); // 不抛
    }

    @Test
    public void retry_succeedsWithoutDelay() {
        BasePage bp = mock(BasePage.class);
        Page page = mock(Page.class);
        when(bp.getPage()).thenReturn(page);
        Runnable r = mock(Runnable.class);

        PageWaits.retry(bp, r, "desc");

        verify(r).run();
        // 首次成功路径不触发退避等待（waitForTimeout 参数为 double 基本类型，须用 anyDouble 原始 matcher）
        verify(page, never()).waitForTimeout(anyDouble());
    }

    @Test
    public void retry_succeedsAfterTransientFailures() {
        BasePage bp = mock(BasePage.class);
        Page page = mock(Page.class);
        when(bp.getPage()).thenReturn(page);
        AtomicInteger calls = new AtomicInteger();
        Runnable r = () -> {
            if (calls.getAndIncrement() < 2) {
                throw new RuntimeException("transient");
            }
        };

        PageWaits.retry(bp, r, 3, 1000, "desc");

        assertEquals(3, calls.get()); // 2 失败 + 1 成功
        verify(page, times(2)).waitForTimeout(1000.0);
    }

    @Test
    public void retryWithValidation_returnsTrueWhenValidationPasses() {
        BasePage bp = mock(BasePage.class);
        Page page = mock(Page.class);
        when(bp.getPage()).thenReturn(page);
        Runnable op = () -> {
        };
        BooleanSupplier valid = () -> true;

        boolean ok = PageWaits.retryWithValidation(bp, op, valid, 3, 100, "desc");

        assertTrue(ok);
    }

    @Test
    public void retryWithValidation_returnsFalseWhenValidationNeverPasses() {
        BasePage bp = mock(BasePage.class);
        Page page = mock(Page.class);
        when(bp.getPage()).thenReturn(page);
        Runnable op = () -> {
        };
        BooleanSupplier valid = () -> false;

        boolean ok = PageWaits.retryWithValidation(bp, op, valid, 3, 100, "desc");

        assertFalse(ok);
    }
}
