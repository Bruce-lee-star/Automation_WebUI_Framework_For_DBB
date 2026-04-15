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
 * 
 * 等待策略说明：
 * - 【智能等待】：使用 Playwright 原生 locator().waitFor()，适用于元素存在、可见、隐藏等状态
 * - 【轮询等待】：使用 while+sleep 轮询，适用于属性检查(isEnabled/isSelected等)、文本内容检查等 Playwright 不原生支持的判断
 * 
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

    // ==================== 【智能等待】元素可见性/存在性 ====================
    
    /**
     * 检查元素是否可见（智能等待）
     * 使用 Playwright 原生 VISIBLE 状态等待
     */
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

    /**
     * 检查元素是否可见（指定超时，智能等待）
     */
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

    /**
     * 检查元素是否存在（智能等待）
     * 使用 Playwright 原生 ATTACHED 状态等待
     */
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

    /**
     * 检查元素是否存在（指定超时，智能等待）
     */
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

    /**
     * 检查元素是否可点击（智能等待）
     * 先等待元素可见，再检查 enabled 状态
     */
    public boolean isClickable() {
        return isClickable(TimeoutConfig.getElementCheckTimeout() / 1000);
    }

    /**
     * 检查元素是否可点击（指定超时，智能等待）
     */
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

    /**
     * 检查元素是否隐藏（智能等待）
     * 使用 Playwright 原生 HIDDEN 状态等待
     */
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

    /**
     * 检查元素是否隐藏（指定超时，智能等待）
     */
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

    // ==================== 【轮询等待】元素属性状态检查 ====================
    
    /**
     * 检查元素是否启用（轮询等待）
     * Playwright 不支持 enabled 状态的原生等待，需轮询检查属性
     */
    public boolean isEnabled() {
        int timeout = TimeoutConfig.getElementCheckTimeout();
        int interval = TimeoutConfig.getPollingInterval();
        long endTime = System.currentTimeMillis() + timeout;

        while (System.currentTimeMillis() < endTime) {
            try {
                if (locator().isEnabled()) {
                    return true;
                }
            } catch (Exception e) {
                // 元素可能还未就绪，继续重试
            }
            getPage().waitForTimeout(interval);
        }
        return false;
    }

    /**
     * 检查元素是否启用（指定超时，轮询等待）
     */
    public boolean isEnabled(int timeoutInSeconds) {
        int timeout = timeoutInSeconds * 1000;
        int interval = TimeoutConfig.getPollingInterval();
        long endTime = System.currentTimeMillis() + timeout;

        while (System.currentTimeMillis() < endTime) {
            try {
                if (locator().isEnabled()) {
                    return true;
                }
            } catch (Exception e) {
                // 忽略异常，继续重试
            }
            getPage().waitForTimeout(interval);
        }
        return false;
    }

    /**
     * 检查元素是否被选中/勾选（轮询等待）
     * 用于 checkbox/radio 元素
     */
    public boolean isSelected() {
        int timeout = TimeoutConfig.getElementCheckTimeout();
        int interval = TimeoutConfig.getPollingInterval();
        long endTime = System.currentTimeMillis() + timeout;

        while (System.currentTimeMillis() < endTime) {
            try {
                if (locator().isChecked()) {
                    return true;
                }
            } catch (Exception e) {
                // 忽略异常，继续重试
            }
            getPage().waitForTimeout(interval);
        }
        return false;
    }

    /**
     * 检查元素是否被选中（指定超时，轮询等待）
     */
    public boolean isSelected(int timeoutInSeconds) {
        int timeout = timeoutInSeconds * 1000;
        int interval = TimeoutConfig.getPollingInterval();
        long endTime = System.currentTimeMillis() + timeout;

        while (System.currentTimeMillis() < endTime) {
            try {
                if (locator().isChecked()) {
                    return true;
                }
            } catch (Exception e) {
                // 忽略异常，继续重试
            }
            getPage().waitForTimeout(interval);
        }
        return false;
    }

    /**
     * 检查元素是否禁用（轮询等待）
     */
    public boolean isDisabled() {
        int timeout = TimeoutConfig.getElementCheckTimeout();
        int interval = TimeoutConfig.getPollingInterval();
        long endTime = System.currentTimeMillis() + timeout;

        while (System.currentTimeMillis() < endTime) {
            try {
                if (locator().isDisabled()) {
                    return true;
                }
            } catch (Exception e) {
                // 忽略异常，继续重试
            }
            getPage().waitForTimeout(interval);
        }
        return false;
    }

    /**
     * 检查元素是否禁用（指定超时，轮询等待）
     */
    public boolean isDisabled(int timeoutInSeconds) {
        int timeout = timeoutInSeconds * 1000;
        int interval = TimeoutConfig.getPollingInterval();
        long endTime = System.currentTimeMillis() + timeout;

        while (System.currentTimeMillis() < endTime) {
            try {
                if (locator().isDisabled()) {
                    return true;
                }
            } catch (Exception e) {
                // 忽略异常，继续重试
            }
            getPage().waitForTimeout(interval);
        }
        return false;
    }

    /**
     * 检查元素是否可编辑（轮询等待）
     */
    public boolean isEditable() {
        int timeout = TimeoutConfig.getElementCheckTimeout();
        int interval = TimeoutConfig.getPollingInterval();
        long endTime = System.currentTimeMillis() + timeout;

        while (System.currentTimeMillis() < endTime) {
            try {
                if (locator().isEditable()) {
                    return true;
                }
            } catch (Exception e) {
                // 忽略异常，继续重试
            }
            getPage().waitForTimeout(interval);
        }
        return false;
    }

    /**
     * 检查元素是否可编辑（指定超时，轮询等待）
     */
    public boolean isEditable(int timeoutInSeconds) {
        int timeout = timeoutInSeconds * 1000;
        int interval = TimeoutConfig.getPollingInterval();
        long endTime = System.currentTimeMillis() + timeout;

        while (System.currentTimeMillis() < endTime) {
            try {
                if (locator().isEditable()) {
                    return true;
                }
            } catch (Exception e) {
                // 忽略异常，继续重试
            }
            getPage().waitForTimeout(interval);
        }
        return false;
    }

    // ==================== 【智能等待】核心等待方法 ====================
    
    public PageElement waitForVisible(int timeoutInSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(timeoutInSeconds * 1000L));
            return this;
        } catch (PlaywrightException e) {
            throw new RuntimeException("Element not visible within " + timeoutInSeconds + "s: " + selector, e);
        }
    }

    public PageElement waitForNotVisible(int timeoutInSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN)
                    .setTimeout(timeoutInSeconds * 1000L));
            return this;
        } catch (PlaywrightException e) {
            throw new RuntimeException("Element not hidden within " + timeoutInSeconds + "s: " + selector, e);
        }
    }

    public PageElement waitForExists(int timeoutInSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout(timeoutInSeconds * 1000L));
            return this;
        } catch (PlaywrightException e) {
            throw new RuntimeException("Element not attached within " + timeoutInSeconds + "s: " + selector, e);
        }
    }

    public PageElement waitForNotExists(int timeoutInSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.DETACHED)
                    .setTimeout(timeoutInSeconds * 1000L));
            return this;
        } catch (PlaywrightException e) {
            throw new RuntimeException("Element not detached within " + timeoutInSeconds + "s: " + selector, e);
        }
    }

    public PageElement waitForClickable(int timeoutInSeconds) {
        waitForVisible(timeoutInSeconds);
        if (!locator().isEnabled()) {
            throw new RuntimeException("Element visible but not clickable: " + selector);
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

    // ==================== 【轮询等待】文本内容检查与等待 ====================
    
    /**
     * 检查元素是否包含指定文本（即时检查，不等待）
     */
    public boolean containsText(String text) {
        String elementText = getText();
        return elementText != null && elementText.contains(text);
    }

    /**
     * 等待元素包含指定文本（轮询等待）
     * Playwright 不提供原生的"包含文本"状态等待，需轮询检查
     */
    public PageElement waitForContainsText(String text, int timeoutInSeconds) {
        long start = System.currentTimeMillis();
        int interval = TimeoutConfig.getPollingInterval();

        while (System.currentTimeMillis() - start < timeoutInSeconds * 1000L) {
            try {
                // 使用 Playwright 的 count() 快速判断是否存在匹配文本
                if (locator().count() > 0 && containsText(text)) {
                    return this;
                }
            } catch (Exception e) {
                // 元素可能不存在，继续等待
            }
            getPage().waitForTimeout(interval);
        }
        throw new RuntimeException("Element does not contain text: " + text + ", selector: " + selector);
    }

    /**
     * 等待元素文本等于指定文本（轮询等待）
     */
    public PageElement waitForTextEquals(String text, int timeoutInSeconds) {
        long start = System.currentTimeMillis();
        int interval = TimeoutConfig.getPollingInterval();

        while (System.currentTimeMillis() - start < timeoutInSeconds * 1000L) {
            try {
                if (text.equals(getText())) {
                    return this;
                }
            } catch (Exception e) {
                // 忽略异常，继续重试
            }
            getPage().waitForTimeout(interval);
        }
        throw new RuntimeException("Element text does not match: " + text + ", selector: " + selector);
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

    @Override
    public String toString() {
        return "PageElement{selector='" + selector + "'}";
    }
}
