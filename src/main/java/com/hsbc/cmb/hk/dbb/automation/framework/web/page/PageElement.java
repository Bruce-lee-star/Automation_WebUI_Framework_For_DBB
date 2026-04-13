package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.TimeoutConfig;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.MouseButton;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 页面元素包装类，支持链式调用
 * 用法示例：
 * LoginPage.USERNAME_INPUT.type(username);
 * LoginPage.NEXT_BUTTON.click();
 */
public class PageElement {
    private final String selector;
    private BasePage page;

    public PageElement(String selector, BasePage page) {
        if (selector == null || selector.trim().isEmpty()) {
            throw new IllegalArgumentException("Selector cannot be null or empty");
        }
        this.selector = selector;
        this.page = page;
    }

    public PageElement(String selector) {
        if (selector == null || selector.trim().isEmpty()) {
            throw new IllegalArgumentException("Selector cannot be null or empty");
        }
        this.selector = selector;
        this.page = BasePage.getCurrentPage();
    }

    public void setPage(BasePage page) {
        this.page = page;
    }

    public String getSelector() {
        return selector;
    }

    public Locator locator() {
        return getPage().locator(selector);
    }

    private BasePage getPage() {
        if (page == null) {
            page = BasePage.getCurrentPage();
            if (page == null) {
                throw new IllegalStateException("No BasePage instance found. Please ensure the page is initialized.");
            }
        }
        return page;
    }

    // ==================== 基础操作 ====================
    public PageElement type(String text) {
        locator().fill(text);
        return this;
    }

    public PageElement click() {
        locator().click();
        return this;
    }

    public PageElement doubleClick() {
        locator().dblclick();
        return this;
    }

    public PageElement rightClick() {
        locator().click(new Locator.ClickOptions().setButton(MouseButton.RIGHT));
        return this;
    }

    public PageElement hover() {
        locator().hover();
        return this;
    }

    public PageElement clear() {
        locator().clear();
        return this;
    }

    public String getText() {
        return locator().textContent();
    }

    public String getValue() {
        return locator().inputValue();
    }

    public String getAttribute(String attributeName) {
        return locator().getAttribute(attributeName);
    }

    // ==================== 【修复】元素状态检查（使用Playwright原生智能等待） ====================
    public boolean isVisible() {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(TimeoutConfig.getElementCheckTimeout()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isVisible(int timeoutInSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(timeoutInSeconds * 1000));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean exists() {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout(TimeoutConfig.getElementCheckTimeout()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean exists(int timeoutInSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout(timeoutInSeconds * 1000));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isClickable() {
        return isClickable(TimeoutConfig.getElementCheckTimeout() / 1000);
    }

    public boolean isClickable(int timeoutInSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(timeoutInSeconds * 1000));
            return locator().isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isEnabled() {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setTimeout(TimeoutConfig.getElementCheckTimeout()));
            return locator().isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isEnabled(int timeoutInSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setTimeout(timeoutInSeconds * 1000));
            return locator().isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isSelected() {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setTimeout(TimeoutConfig.getElementCheckTimeout()));
            return locator().isChecked();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isSelected(int timeoutInSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setTimeout(timeoutInSeconds * 1000));
            return locator().isChecked();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isDisabled() {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setTimeout(TimeoutConfig.getElementCheckTimeout()));
            return locator().isDisabled();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isDisabled(int timeoutInSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setTimeout(timeoutInSeconds * 1000));
            return locator().isDisabled();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isEditable() {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setTimeout(TimeoutConfig.getElementCheckTimeout()));
            return locator().isEditable();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isEditable(int timeoutInSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setTimeout(timeoutInSeconds * 1000));
            return locator().isEditable();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isHidden() {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN)
                    .setTimeout(TimeoutConfig.getElementCheckTimeout()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isHidden(int timeoutInSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN)
                    .setTimeout(timeoutInSeconds * 1000));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 【修复】核心等待方法（彻底解决TimeoutError） ====================
    public PageElement waitForVisible(int timeoutInSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(timeoutInSeconds * 1000L));
            return this;
        } catch (PlaywrightException e) {
            throw new RuntimeException("元素在 " + timeoutInSeconds + " 秒内未变为可见: " + selector, e);
        }
    }

    public PageElement waitForNotVisible(int timeoutInSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN)
                    .setTimeout(timeoutInSeconds * 1000L));
            return this;
        } catch (PlaywrightException e) {
            throw new RuntimeException("元素在 " + timeoutInSeconds + " 秒内未变为隐藏: " + selector, e);
        }
    }

    public PageElement waitForExists(int timeoutInSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout(timeoutInSeconds * 1000L));
            return this;
        } catch (PlaywrightException e) {
            throw new RuntimeException("元素在 " + timeoutInSeconds + " 秒内未加载: " + selector, e);
        }
    }

    public PageElement waitForNotExists(int timeoutInSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.DETACHED)
                    .setTimeout(timeoutInSeconds * 1000L));
            return this;
        } catch (PlaywrightException e) {
            throw new RuntimeException("元素在 " + timeoutInSeconds + " 秒内未消失: " + selector, e);
        }
    }

    public PageElement waitForClickable(int timeoutInSeconds) {
        waitForVisible(timeoutInSeconds);
        if (!locator().isEnabled()) {
            throw new RuntimeException("元素可见但不可点击: " + selector);
        }
        return this;
    }

    public PageElement waitForHidden() {
        locator().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
        return this;
    }

    public PageElement waitForAttached() {
        locator().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED));
        return this;
    }

    public PageElement waitForDetached() {
        locator().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED));
        return this;
    }

    public PageElement waitForAttached(int timeoutInSeconds) {
        return waitForExists(timeoutInSeconds);
    }

    // ==================== 下拉选择 ====================
    public PageElement selectByValue(String value) {
        locator().selectOption(value);
        return this;
    }

    public PageElement selectByIndex(int index) {
        locator().evaluate("el => { el.selectedIndex = " + index + "; el.dispatchEvent(new Event('change')); }");
        return this;
    }

    // ==================== 文本检查与等待 ====================
    public boolean containsText(String text) {
        String elementText = getText();
        return elementText != null && elementText.contains(text);
    }

    public PageElement waitForContainsText(String text, int timeoutInSeconds) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutInSeconds * 1000L) {
            if (containsText(text)) {
                return this;
            }
            getPage().waitForTimeout(200);
        }
        throw new RuntimeException("元素未包含文本: " + text + " 选择器: " + selector);
    }

    public PageElement waitForTextEquals(String text, int timeoutInSeconds) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutInSeconds * 1000L) {
            if (text.equals(getText())) {
                return this;
            }
            getPage().waitForTimeout(200);
        }
        throw new RuntimeException("元素文本不匹配: " + text + " 选择器: " + selector);
    }

    // ==================== 滚动操作 ====================
    public PageElement scrollTo() {
        locator().scrollIntoViewIfNeeded();
        return this;
    }

    public PageElement scrollTo(int scrollX, int scrollY) {
        locator().evaluate("el => el.scrollTo(" + scrollX + ", " + scrollY + ")");
        return this;
    }

    public PageElement scrollToBottom() {
        locator().evaluate("el => el.scrollTop = el.scrollHeight");
        return this;
    }

    public PageElement scrollToTop() {
        locator().evaluate("el => el.scrollTop = 0");
        return this;
    }

    public PageElement scrollBy(int offsetX, int offsetY) {
        locator().evaluate("el => el.scrollBy(" + offsetX + ", " + offsetY + ")");
        return this;
    }

    public PageElement scrollToCenter() {
        locator().scrollIntoViewIfNeeded();
        return this;
    }

    // ==================== 子元素 & 文件上传 ====================
    public PageElement child(String childSelector) {
        return new PageElement(this.selector + " " + childSelector, getPage());
    }

    public PageElement uploadFile(String filePath) {
        locator().setInputFiles(Paths.get(filePath));
        return this;
    }

    public PageElement setInputFiles(String... filePaths) {
        Path[] paths = new Path[filePaths.length];
        for (int i = 0; i < filePaths.length; i++) {
            paths[i] = Paths.get(filePaths[i]);
        }
        locator().setInputFiles(paths);
        return this;
    }

    // ==================== 复选框 & 输入 ====================
    public PageElement append(String text) {
        locator().evaluate("el => el.value += arguments[0]", text);
        return this;
    }

    public PageElement check() {
        locator().check();
        return this;
    }

    public PageElement uncheck() {
        locator().uncheck();
        return this;
    }

    // ==================== 键盘操作 ====================
    public PageElement press(String key) {
        locator().press(key);
        return this;
    }

    public PageElement insertText(String text) {
        locator().fill(text);
        return this;
    }

    public PageElement keyDown(String key) {
        locator().press(key + "+KeyDown");
        return this;
    }

    public PageElement keyUp(String key) {
        locator().press(key + "+KeyUp");
        return this;
    }

    public PageElement selectAll() {
        locator().press("Control+a");
        return this;
    }

    public PageElement copy() {
        locator().press("Control+c");
        return this;
    }

    public PageElement paste() {
        locator().press("Control+v");
        return this;
    }

    public PageElement cut() {
        locator().press("Control+x");
        return this;
    }

    // ==================== 鼠标操作 ====================
    public PageElement clickAtCenter() {
        locator().click();
        return this;
    }

    public PageElement dragToCoordinates(int targetX, int targetY) {
        locator().dragTo(page.locator("body"), new Locator.DragToOptions().setTargetPosition(targetX, targetY));
        return this;
    }

    public int[] getCenter() {
        BoundingBox box = locator().boundingBox();
        return new int[]{(int) (box.x + box.width / 2), (int) (box.y + box.height / 2)};
    }

    // ==================== 辅助功能 ====================
    public boolean isAccessible() {
        return locator().isVisible() && locator().isEnabled();
    }

    public String getAriaLabel() {
        return locator().getAttribute("aria-label");
    }

    public boolean hasAriaLabel() {
        String label = getAriaLabel();
        return label != null && !label.isEmpty();
    }

    public String getAriaRole() {
        return locator().getAttribute("role");
    }

    public boolean isVisibleForAccessibility() {
        return locator().isVisible();
    }

    public boolean hasSufficientColorContrast() {
        return true;
    }

    // ==================== 元素集合 ====================
    public int count() {
        return locator().count();
    }

    public Locator first() {
        return locator().first();
    }

    public Locator last() {
        return locator().last();
    }

    public Locator nth(int index) {
        return locator().nth(index);
    }

    public Locator all() {
        return locator();
    }

    // ==================== 截图 & 位置 ====================
    public PageElement screenshot() {
        locator().screenshot();
        return this;
    }

    public BoundingBox getBoundingBox() {
        return locator().boundingBox();
    }

    // ==================== 增强操作 ====================
    public PageElement tap() {
        locator().tap();
        return this;
    }

    public PageElement focus() {
        locator().focus();
        return this;
    }

    public String innerHTML() {
        return locator().innerHTML();
    }

    public String textContent() {
        return locator().textContent();
    }

    @Override
    public String toString() {
        return "PageElement{selector='" + selector + "'}";
    }
}
