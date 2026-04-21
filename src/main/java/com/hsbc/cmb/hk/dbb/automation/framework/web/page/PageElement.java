package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.TimeoutConfig;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.MouseButton;
import com.microsoft.playwright.options.WaitForSelectorState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 页面元素包装类，支持链式调用
 * 稳定版：保留原生超时 + 轻量级重试 + 强化点击（解决按钮偶尔点不动）
 */
public class PageElement {
    private static final Logger logger = LoggerFactory.getLogger(PageElement.class);

    private static final int ACTION_POST_DELAY = 200;
    private static final int MAX_RETRY = 2;
    private static final int RETRY_DELAY_MS = 300;

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

    public Locator locator(String relativeSelector) {
        return locator().locator(relativeSelector);
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

    // ==================== 稳定重试核心：保留原生超时 ====================
    private void executeWithRetry(Runnable action, String operationName) {
        Exception lastException = null;

        for (int retry = 0; retry <= MAX_RETRY; retry++) {
            try {
                action.run();
                return;
            } catch (PlaywrightException e) {
                lastException = e;

                if (retry == MAX_RETRY) {
                    break;
                }

                if (!isRetriableError(e)) {
                    break;
                }

                logger.debug("[Retry {}] {} on {} temporarily failed: {}",
                        retry + 1, operationName, selector, e.getMessage());

                getPage().waitForTimeout(RETRY_DELAY_MS);
            }
        }

        throw new RuntimeException(
                String.format("Failed to perform '%s' on element: %s", operationName, selector),
                lastException
        );
    }

    /**
     * 只重试临时可恢复异常：遮挡、拦截、DOM瞬态 detached
     */
    private static boolean isRetriableError(PlaywrightException e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("click intercepted")
                || lower.contains("obscures")
                || lower.contains("not interactable")
                || lower.contains("detached")
                || lower.contains("not attached");
    }

    // ==================== 强化版点击：解决 Search 按钮偶尔点不动 ====================
    public PageElement click() {
        executeWithRetry(() -> {
            // 先滚动到视图，避免点在视口外
            locator().scrollIntoViewIfNeeded();
            getPage().waitForTimeout(100);

            try {
                // 常规点击：偏移点击，避开伪元素/边框
                locator().click(new Locator.ClickOptions()
                        .setPosition(5, 5)
                        .setDelay(100));
            } catch (Exception e) {
                // 兜底 JS 点击（金融系统、按钮嵌套 SVG 必备）
                logger.warn("Normal click failed, fallback to JS click: {}", selector);
                locator().evaluate("el => el.click()");
            }

            getPage().waitForTimeout(ACTION_POST_DELAY);
        }, "click");
        return this;
    }

    public PageElement type(String text) {
        executeWithRetry(() -> {
            locator().scrollIntoViewIfNeeded();
            locator().fill(text);
        }, "fill");
        return this;
    }

    public PageElement doubleClick() {
        executeWithRetry(() -> {
            locator().scrollIntoViewIfNeeded();
            locator().dblclick();
            getPage().waitForTimeout(ACTION_POST_DELAY);
        }, "doubleClick");
        return this;
    }

    public PageElement rightClick() {
        executeWithRetry(() -> {
            locator().scrollIntoViewIfNeeded();
            locator().click(new Locator.ClickOptions().setButton(MouseButton.RIGHT));
            getPage().waitForTimeout(ACTION_POST_DELAY);
        }, "rightClick");
        return this;
    }

    public PageElement clear() {
        executeWithRetry(() -> locator().clear(), "clear");
        return this;
    }

    public PageElement hover() {
        executeWithRetry(() -> locator().hover(), "hover");
        return this;
    }

    // ==================== 文本/属性获取 ====================
    public String getText() {
        return locator().textContent();
    }

    public String getValue() {
        return locator().inputValue();
    }

    public String getAttribute(String attributeName) {
        return locator().getAttribute(attributeName);
    }

    // ==================== 可见/存在/可点判断 ====================
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

    // ==================== 轮询状态 ====================
    public boolean isEnabled() {
        int timeout = TimeoutConfig.getElementCheckTimeout();
        int interval = TimeoutConfig.getPollingInterval();
        long endTime = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < endTime) {
            try {
                if (locator().isEnabled()) return true;
            } catch (Exception ignored) {}
            getPage().waitForTimeout(interval);
        }
        return false;
    }

    public boolean isEnabled(int timeoutInSeconds) {
        int timeout = timeoutInSeconds * 1000;
        int interval = TimeoutConfig.getPollingInterval();
        long endTime = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < endTime) {
            try {
                if (locator().isEnabled()) return true;
            } catch (Exception ignored) {}
            getPage().waitForTimeout(interval);
        }
        return false;
    }

    public boolean isSelected() {
        int timeout = TimeoutConfig.getElementCheckTimeout();
        int interval = TimeoutConfig.getPollingInterval();
        long endTime = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < endTime) {
            try {
                if (locator().isChecked()) return true;
            } catch (Exception ignored) {}
            getPage().waitForTimeout(interval);
        }
        return false;
    }

    public boolean isSelected(int timeoutInSeconds) {
        int timeout = timeoutInSeconds * 1000;
        int interval = TimeoutConfig.getPollingInterval();
        long endTime = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < endTime) {
            try {
                if (locator().isChecked()) return true;
            } catch (Exception ignored) {}
            getPage().waitForTimeout(interval);
        }
        return false;
    }

    public boolean isDisabled() {
        int timeout = TimeoutConfig.getElementCheckTimeout();
        int interval = TimeoutConfig.getPollingInterval();
        long endTime = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < endTime) {
            try {
                if (locator().isDisabled()) return true;
            } catch (Exception ignored) {}
            getPage().waitForTimeout(interval);
        }
        return false;
    }

    public boolean isDisabled(int timeoutInSeconds) {
        int timeout = timeoutInSeconds * 1000;
        int interval = TimeoutConfig.getPollingInterval();
        long endTime = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < endTime) {
            try {
                if (locator().isDisabled()) return true;
            } catch (Exception ignored) {}
            getPage().waitForTimeout(interval);
        }
        return false;
    }

    public boolean isEditable() {
        int timeout = TimeoutConfig.getElementCheckTimeout();
        int interval = TimeoutConfig.getPollingInterval();
        long endTime = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < endTime) {
            try {
                if (locator().isEditable()) return true;
            } catch (Exception ignored) {}
            getPage().waitForTimeout(interval);
        }
        return false;
    }

    public boolean isEditable(int timeoutInSeconds) {
        int timeout = timeoutInSeconds * 1000;
        int interval = TimeoutConfig.getPollingInterval();
        long endTime = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < endTime) {
            try {
                if (locator().isEditable()) return true;
            } catch (Exception ignored) {}
            getPage().waitForTimeout(interval);
        }
        return false;
    }

    // ==================== 精准等待 ====================
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

    // ==================== 下拉/滚动/文件/键盘等 ====================
    public PageElement selectByValue(String value) {
        locator().selectOption(value);
        return this;
    }

    public PageElement selectByIndex(int index) {
        locator().evaluate("el => { el.selectedIndex = " + index + "; el.dispatchEvent(new Event('change')); }");
        return this;
    }

    public boolean containsText(String text) {
        String elementText = getText();
        return elementText != null && elementText.contains(text);
    }

    public PageElement waitForContainsText(String text, int timeoutInSeconds) {
        long start = System.currentTimeMillis();
        int interval = TimeoutConfig.getPollingInterval();
        while (System.currentTimeMillis() - start < timeoutInSeconds * 1000L) {
            try {
                if (locator().count() > 0 && containsText(text)) return this;
            } catch (Exception ignored) {}
            getPage().waitForTimeout(interval);
        }
        throw new RuntimeException("Element does not contain text: " + text + ", selector: " + selector);
    }

    public PageElement waitForTextEquals(String text, int timeoutInSeconds) {
        long start = System.currentTimeMillis();
        int interval = TimeoutConfig.getPollingInterval();
        while (System.currentTimeMillis() - start < timeoutInSeconds * 1000L) {
            try {
                if (text.equals(getText())) return this;
            } catch (Exception ignored) {}
            getPage().waitForTimeout(interval);
        }
        throw new RuntimeException("Element text does not match: " + text + ", selector: " + selector);
    }

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

    public PageElement clickAtCenter() {
        locator().click();
        return this;
    }

    public PageElement dragToCoordinates(int targetX, int targetY) {
        locator().dragTo(getPage().locator("body"), new Locator.DragToOptions().setTargetPosition(targetX, targetY));
        return this;
    }

    public int[] getCenter() {
        BoundingBox box = locator().boundingBox();
        return new int[]{(int) (box.x + box.width / 2), (int) (box.y + box.height / 2)};
    }

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

    public PageElement screenshot() {
        locator().screenshot();
        return this;
    }

    public BoundingBox getBoundingBox() {
        return locator().boundingBox();
    }

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