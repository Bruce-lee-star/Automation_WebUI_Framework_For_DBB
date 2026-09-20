package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.engine.BasePage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PageElement#child(String)} 定位语义回归测试（固化评审 F-12 修复，不启动浏览器）。
 *
 * <p><b>修复前的缺陷</b>：{@code ChildPageElement} 把「展示串」
 * {@code parent[<父选择器>] >> child[<子选择器>]} 交给父类当<b>真实选择器</b>解析
 * （{@code parent[...]} 不是合法 CSS 元素），于是父级 locator 恒 0 元素 →
 * {@code child(...)} <b>静默恒空</b>，不抛错、不告警。</p>
 *
 * <p><b>修复后语义</b>：子元素必须经「父级真实 Locator」链式下钻
 * （{@code parent.locatorInternal().locator(childSelector)}），父级自身的
 * iframe/shadow/动态供应商解析结果随之被继承。</p>
 *
 * <p>与 {@link PageElement} 同包，故可直接驱动包内可见的 {@code locatorInternal()} 断言底层 Locator。</p>
 */
public class PageElementChildLocatorTest {

    @Test
    public void childDelegatesToRealParentLocatorInsteadOfDisplayString() {
        BasePage bp = mock(BasePage.class);
        Page pw = mock(Page.class);
        when(bp.getPage()).thenReturn(pw);

        Locator parentLocator = mock(Locator.class);
        when(bp.locatorInternal("div.card")).thenReturn(parentLocator);
        Locator childLocator = mock(Locator.class);
        when(parentLocator.locator("span.name")).thenReturn(childLocator);

        PageElement parent = new PageElement("div.card", bp);
        PageElement child = parent.child("span.name");

        // 子元素底层 Locator == 父级真实 Locator 的作用域内下钻结果
        assertSame(childLocator, child.locatorInternal(),
                "child() 必须基于父级真实 Locator 下钻（Locator.locator 嵌套定位）");

        // 父级用「真实选择器」解析（而非展示串）
        verify(bp).locatorInternal("div.card");
        verify(bp, never()).locatorInternal("parent[div.card] >> child[span.name]");
        verify(parentLocator).locator("span.name");
    }

    @Test
    public void chainedChildKeepsUsingParentChain() {
        BasePage bp = mock(BasePage.class);
        when(bp.getPage()).thenReturn(mock(Page.class));

        Locator l1 = mock(Locator.class);
        Locator l2 = mock(Locator.class);
        Locator l3 = mock(Locator.class);
        when(bp.locatorInternal(anyString())).thenReturn(l1);
        when(l1.locator("ul")).thenReturn(l2);
        when(l2.locator("li")).thenReturn(l3);

        PageElement root = new PageElement("div.card", bp);
        PageElement grandChild = root.child("ul").child("li");

        assertSame(l3, grandChild.locatorInternal(), "多级 child 必须逐级在父级作用域内下钻");
    }
}
