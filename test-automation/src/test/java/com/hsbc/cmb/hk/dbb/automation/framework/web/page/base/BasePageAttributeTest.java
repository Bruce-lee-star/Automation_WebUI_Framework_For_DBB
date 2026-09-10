package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * BasePage.getAttributeValue 行为护盾：验证「null→默认值」与「非空→归一化」语义。
 * 纯 Mockito 隔离、不依赖浏览器；因 BasePage 为抽象类，以匿名子类 + spy 驱动真实方法体。
 */
public class BasePageAttributeTest {

    private static BasePage spyBp() {
        return spy(new BasePage() {});
    }

    @Test
    public void getAttributeValue_normalizesViaBasePage() {
        BasePage bp = spyBp();
        PageElement pe = mock(PageElement.class);
        doReturn(pe).when(bp).element("x");
        when(pe.getAttribute("href")).thenReturn("  RAW  ");
        assertEquals("RAW", bp.getAttributeValue("x", "href", "def"));
    }

    @Test
    public void getAttributeValue_returnsDefaultWhenNull() {
        BasePage bp = spyBp();
        PageElement pe = mock(PageElement.class);
        doReturn(pe).when(bp).element("x");
        when(pe.getAttribute("href")).thenReturn(null);
        assertEquals("def", bp.getAttributeValue("x", "href", "def"));
    }
}
