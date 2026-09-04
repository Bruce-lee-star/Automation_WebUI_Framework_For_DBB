package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.delegate;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ElementOperationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.PageElement;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;

/**
 * 元素操作子模块（T5-5 拆分）。
 * <p>原 {@link BasePage} 的 click / jsClick / type / append / clear / getText / getInputValue /
 * getAttribute / getAttributeValue / selectOption / selectByVisibleText / check / uncheck /
 * is* / getElementCount / dragAndDrop / focus / hover 方法体下沉至此。
 * <p>同时补齐 Playwright {@code Page} 对齐的缺口方法：{@code dblclick} / {@code dispatchEvent}。
 * <p>仅依赖 {@link BasePage} 公开 API（{@code element()} / {@code locator()} / {@code getAttribute()} /
 * {@code normalizeText()}），行为零回归；公开 API 不变（新增 dblclick/dispatchEvent 为 additive）。
 */
public final class PageElementActions {

    private PageElementActions() {
        // 纯静态工具类，禁止实例化
    }

    public static void click(BasePage bp, String selector) {
        try {
            bp.element(selector).click();
        } catch (ElementOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new ElementOperationException("click", selector,
                    "Failed to click element: " + selector, e);
        }
    }

    public static void jsClick(BasePage bp, String selector) {
        bp.locator(selector).evaluate("el => el.click()");
    }

    public static void type(BasePage bp, String selector, String text) {
        bp.element(selector).type(text);
    }

    public static void append(BasePage bp, String selector, String text) {
        PageElement pe = bp.element(selector);
        pe.focus();
        String current = pe.getValue();
        if (current == null) current = "";
        pe.fill(current + text);
    }

    public static void clear(BasePage bp, String selector) {
        bp.element(selector).clear();
    }

    public static String getText(BasePage bp, String selector) {
        return bp.element(selector).getText();
    }

    public static String getInputValue(BasePage bp, String selector) {
        return bp.element(selector).getValue();
    }

    public static String getAttribute(BasePage bp, String selector, String attr) {
        return bp.element(selector).getAttribute(attr);
    }

    public static String getAttributeValue(BasePage bp, String selector, String attr, String defaultValue) {
        String val = bp.getAttribute(selector, attr);
        return val == null ? defaultValue : bp.normalizeText(val);
    }

    public static void selectOption(BasePage bp, String selector, int index) {
        bp.element(selector).selectByIndex(index);
    }

    public static void selectByVisibleText(BasePage bp, String selector, String text) {
        bp.element(selector).selectByVisibleText(text);
    }

    public static void check(BasePage bp, String selector) {
        bp.element(selector).check();
    }

    public static void uncheck(BasePage bp, String selector) {
        bp.element(selector).uncheck();
    }

    public static boolean isChecked(BasePage bp, String selector) {
        return bp.element(selector).isChecked();
    }

    public static boolean isEnabled(BasePage bp, String selector) {
        return bp.element(selector).isEnabled();
    }

    public static boolean isDisabled(BasePage bp, String selector) {
        return bp.element(selector).isDisabled();
    }

    public static boolean isVisible(BasePage bp, String selector) {
        return bp.element(selector).isVisible();
    }

    public static boolean isHidden(BasePage bp, String selector) {
        return bp.element(selector).isNotVisible();
    }

    public static int getElementCount(BasePage bp, String selector) {
        return bp.locator(selector).count();
    }

    public static void dragAndDrop(BasePage bp, String sourceSelector, String targetSelector) {
        bp.locator(sourceSelector).dragTo(bp.locator(targetSelector));
    }

    public static void focus(BasePage bp, String selector) {
        bp.locator(selector).focus();
    }

    public static void hover(BasePage bp, String selector) {
        bp.locator(selector).hover();
    }

    // ===================== Playwright Page 对齐的缺口方法 =====================

    public static void dblclick(BasePage bp, String selector) {
        bp.locator(selector).dblclick();
    }

    public static void dispatchEvent(BasePage bp, String selector, String type) {
        bp.locator(selector).dispatchEvent(type);
    }
}
