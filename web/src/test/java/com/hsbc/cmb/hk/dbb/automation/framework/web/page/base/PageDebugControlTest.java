package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.microsoft.playwright.Page;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WEB-P1-5 种子测试：页面调试控制（无浏览器）。
 * 环境判定依赖 JVM 系统属性 {@code ci} 与云端环境变量，故用例只断言确定性分支并在结束时还原属性，
 * 避免污染同 JVM 内其它测试。
 */
public class PageDebugControlTest {

    private static final String CI_PROPERTY = "ci";
    private String originalCiProperty;

    @After
    public void restoreSystemProperty() {
        if (originalCiProperty == null) {
            System.clearProperty(CI_PROPERTY);
        } else {
            System.setProperty(CI_PROPERTY, originalCiProperty);
        }
    }

    @Test
    public void isDebugEnvironment_returnsFalseWhenCiPropertyEnabled() {
        originalCiProperty = System.getProperty(CI_PROPERTY);
        System.setProperty(CI_PROPERTY, "true");

        assertFalse("CI 环境（ci=true）应判定为非调试环境", PageDebugControl.isDebugEnvironment());
    }

    @Test
    public void pause_skipsPagePauseWhenCiPropertyEnabled() {
        originalCiProperty = System.getProperty(CI_PROPERTY);
        System.setProperty(CI_PROPERTY, "true");
        BasePage bp = mock(BasePage.class);
        Page page = mock(Page.class);
        when(bp.getPage()).thenReturn(page);

        PageDebugControl.pause(bp);

        // 非调试环境下严禁触碰 Page，避免触发页面初始化副作用
        verify(bp, never()).getPage();
        verify(page, never()).pause();
    }
}
