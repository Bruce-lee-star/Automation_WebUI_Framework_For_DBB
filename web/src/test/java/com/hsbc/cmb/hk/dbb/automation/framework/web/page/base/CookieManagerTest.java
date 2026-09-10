package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WEB-P1-5 种子测试：Cookie 操作工厂（无浏览器，纯 Mockito 隔离 BasePage/BrowserContext）。
 * 覆盖 getCookies 多形态、按名取/存在性、增/删/清、当前页 Cookie。
 */
public class CookieManagerTest {

    private static BasePage bp(BrowserContext ctx, Page page) {
        BasePage bp = mock(BasePage.class);
        when(bp.getContext()).thenReturn(ctx);
        when(bp.getPage()).thenReturn(page);
        return bp;
    }

    @Test
    public void getCookies_returnsAllContextCookies() {
        BrowserContext ctx = mock(BrowserContext.class);
        Cookie a = new Cookie("a", "1");
        Cookie b = new Cookie("b", "2");
        when(ctx.cookies()).thenReturn(List.of(a, b));
        BasePage bp = bp(ctx, mock(Page.class));

        assertEquals(2, CookieManager.getCookies(bp).size());
        verify(ctx).cookies();
    }

    @Test
    public void getCookies_byUrl_delegatesToContext() {
        BrowserContext ctx = mock(BrowserContext.class);
        Cookie a = new Cookie("a", "1");
        when(ctx.cookies("https://x.com")).thenReturn(List.of(a));
        BasePage bp = bp(ctx, mock(Page.class));

        assertEquals(1, CookieManager.getCookies(bp, "https://x.com").size());
        verify(ctx).cookies("https://x.com");
    }

    @Test
    public void getCookie_byName_filtersFromAll() {
        BrowserContext ctx = mock(BrowserContext.class);
        Cookie a = new Cookie("a", "1");
        Cookie b = new Cookie("b", "2");
        when(ctx.cookies()).thenReturn(List.of(a, b));
        BasePage bp = bp(ctx, mock(Page.class));

        assertEquals(a, CookieManager.getCookie(bp, "a"));
        assertNull(CookieManager.getCookie(bp, "missing"));
    }

    @Test
    public void hasCookie_reflectsExistence() {
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.cookies()).thenReturn(List.of(new Cookie("a", "1")));
        BasePage bp = bp(ctx, mock(Page.class));

        assertTrue(CookieManager.hasCookie(bp, "a"));
        assertFalse(CookieManager.hasCookie(bp, "z"));
    }

    @Test
    public void addCookie_delegatesToContext() {
        BrowserContext ctx = mock(BrowserContext.class);
        BasePage bp = bp(ctx, mock(Page.class));
        Cookie c = new Cookie("x", "v");

        CookieManager.addCookie(bp, c);
        verify(ctx).addCookies(List.of(c));
    }

    @Test
    public void addCookies_delegatesToContext() {
        BrowserContext ctx = mock(BrowserContext.class);
        BasePage bp = bp(ctx, mock(Page.class));
        List<Cookie> list = List.of(new Cookie("x", "v"), new Cookie("y", "w"));

        CookieManager.addCookies(bp, list);
        verify(ctx).addCookies(list);
    }

    @Test
    public void deleteCookie_clearsByName() {
        BrowserContext ctx = mock(BrowserContext.class);
        BasePage bp = bp(ctx, mock(Page.class));

        CookieManager.deleteCookie(bp, "token");
        verify(ctx).clearCookies(any(BrowserContext.ClearCookiesOptions.class));
    }

    @Test
    public void clearCookies_delegatesToContext() {
        BrowserContext ctx = mock(BrowserContext.class);
        BasePage bp = bp(ctx, mock(Page.class));

        CookieManager.clearCookies(bp);
        verify(ctx).clearCookies();
    }

    @Test
    public void getCookiesForCurrentPage_usesPageUrl() {
        BrowserContext ctx = mock(BrowserContext.class);
        Page page = mock(Page.class);
        Cookie a = new Cookie("a", "1");
        when(page.url()).thenReturn("https://cur.com");
        when(ctx.cookies("https://cur.com")).thenReturn(List.of(a));
        BasePage bp = bp(ctx, page);

        assertEquals(1, CookieManager.getCookiesForCurrentPage(bp).size());
        verify(page).url();
    }
}
