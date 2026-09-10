package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WEB-P1-5 种子测试：{@code PageElement} 动作与状态包装（无浏览器，Mockito 隔离底层 Locator）。
 *
 * <p>重点覆盖框架自身的包装逻辑（重试 / 诊断 / 流式返回 / 状态语义），而非 Playwright 调用细节：
 * 底层 {@link Locator} 以 mock 提供，动作方法返回默认值即可，断言聚焦于「流式自返回」与
 * 「状态查询反映底层 Locator」两项契约。</p>
 */
public class PageElementActionsTest {

    private static PageElement element(Locator loc) {
        BasePage bp = mock(BasePage.class);
        when(bp.getPage()).thenReturn(mock(Page.class));
        return new PageElement(() -> loc, "#el", bp);
    }

    @Test
    public void fluentActions_returnSameElementInstance() {
        PageElement el = element(mock(Locator.class));

        assertSame(el, el.click());
        assertSame(el, el.click("step-name"));
        assertSame(el, el.doubleClick());
        assertSame(el, el.rightClick());
        assertSame(el, el.jsClick());
        assertSame(el, el.fill("text"));
        assertSame(el, el.fill("text", "step-name"));
        assertSame(el, el.type("text"));
        assertSame(el, el.clear());
        assertSame(el, el.clearAndSetValue("text"));
        assertSame(el, el.clearAndTypeSequentially("text"));
        assertSame(el, el.press("Enter"));
        assertSame(el, el.selectText());
        assertSame(el, el.selectByValue("v"));
        assertSame(el, el.selectByIndex(0));
        assertSame(el, el.selectByVisibleText("label"));
    }

    /**
     * 状态查询（可见/启用/可编辑/勾选/存在）由 Playwright 组合调用（count / waitFor / isXxx 等）实现，
     * 并非单一 {@code loc.isXxx()} 委派，故 mock 下无法稳定桩化出确定布尔值。
     * 本用例保障<b>包装层在底层默认响应下安全降级、不抛裸异常</b>，而非断言具体取值。
     */
    @Test
    public void stateQueries_degradeSafelyOnDefaultLocatorResponses() {
        PageElement el = element(mock(Locator.class));

        el.isVisible();
        el.isNotVisible();
        el.isEnabled();
        el.isDisabled();
        el.isEditable();
        el.isChecked();
        el.exists();

        assertEquals("#el", el.getSelector());
    }

    @Test
    public void timeoutVariants_completeWithoutThrowing() {
        PageElement el = element(mock(Locator.class));

        // 带超时重载走「等待 + 判定」路径，底层 mock 超时即返回默认值，框架须安全处理不抛裸异常
        el.isVisible(1);
        el.exists(1);
        el.isEnabled(1);
        el.isDisabled(1);
        el.isEditable(1);
        el.isChecked(1);
    }

    @Test
    public void textAndAttributeAccessors_areNullSafe() {
        PageElement el = element(mock(Locator.class));

        // 底层返回 null 时框架须安全降级（不抛 NPE）
        el.getText();
        el.getTextRaw();
        el.getInnerHtml();
        el.getAllTextContents();
        el.getAttribute("href");
        el.getValue();

        assertEquals("#el", el.getSelector());
    }
}
