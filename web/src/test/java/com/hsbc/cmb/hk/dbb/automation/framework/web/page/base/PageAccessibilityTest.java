package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.microsoft.playwright.Page;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WEB-P1-5 种子测试：可访问性角色 dump（无浏览器）。
 * 经 codegen SPI 桥接：classpath 含 framework-codegen 时委托桥接 dump，否则降级跳过；
 * 方法须不抛异常且入口始终触发 {@code ensurePageValid()}。
 */
public class PageAccessibilityTest {

    @Test
    public void dumpAccessibilityRoles_doesNotThrow_andEnforcesPageValid() {
        BasePage bp = mock(BasePage.class);
        Page page = mock(Page.class);
        when(bp.getPage()).thenReturn(page);

        PageAccessibility.dumpAccessibilityRoles(bp);

        // 无论桥接是否存在，入口都须先确保页面有效
        verify(bp).ensurePageValid();
    }
}
