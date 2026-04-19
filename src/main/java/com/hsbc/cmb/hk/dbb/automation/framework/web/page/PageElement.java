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
 *
 * 核心设计理念：
 * 1. 页面稳定化由框架层负责（PlaywrightContextManager.createPage → stabilizePage → DOMContentLoaded）
 * 2. Playwright action API (click/fill/dblclick) 内部已自动 wait until actionable
 * 3. 本类仅提供操作级重试机制，处理临时性失败（超时/DOM抖动）
 * 4. 移除硬编码通用loading选择器，无业务耦合
 */
public class PageElement {
    private static final Logger logger = LoggerFactory.getLogger(PageElement.class);

    // 操作后微小休眠，适配前端异步事件渲染
    private static final int ACTION_POST_DELAY = 200;
    // 重试配置（总时间上限由 getElementActionTimeout 控制）
    private static final int MAX_RETRY = 5;
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

    /**
     * 根据相对选择器查找子元素（返回 Playwright Locator）
     * 
     * 使用 Playwright 原生的 locator 嵌套机制，支持：
     * - CSS + CSS: parent.locator(".child")
     * - XPath + XPath: parent.locator("//child")
     * - CSS + XPath: parent.locator("//child")
     * - XPath + CSS: parent.locator(".child")
     * 
     * @param relativeSelector 相对选择器（支持 CSS 选择器或 XPath）
     * @return 子元素的 Locator
     */
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

    // ==================== 核心：操作级重试机制 ====================
    /**
     * 重试执行器：仅对 PlaywrightException（超时/DOM抖动/元素遮挡）进行重试
     *
     * 设计理念：
     *   页面稳定化由框架层负责：
     *     1. PlaywrightContextManager.createPage() → stabilizePage()
     *     2. → PlaywrightManager.stabilizePage() → waitForLoadState(DOMCONTENTLOADED)
     *   Playwright action API 内部已自动 wait until actionable
     *   本类仅处理临时性失败的重试，不重复等待页面稳定
     */

    // ==================== 基础操作（内置重试，解决临时性失败） ====================
    public PageElement click() {
        executeWithRetry(() -> {
            locator().click();
            getPage().waitForTimeout(ACTION_POST_DELAY);
        }, "click");
        return this;
    }

    public PageElement type(String text) {
        executeWithRetry(() -> locator().fill(text), "fill");
        return this;
    }

    public PageElement doubleClick() {
        executeWithRetry(() -> {
            locator().dblclick();
            getPage().waitForTimeout(ACTION_POST_DELAY);
        }, "dblclick");
        return this;
    }

    public PageElement rightClick() {
        executeWithRetry(() -> {
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

    // ==================== 重试核心逻辑 ====================
    /**
     * 纯重试机制：基于时间预算的重试，总耗时不超过 getElementActionTimeout（默认30s）
     *
     * 设计原则：
     *   1. 页面稳定化由框架层 PlaywrightContextManager.createPage() 统一处理
     *   2. Playwright action API 内部已自动 wait until actionable
     *   3. 本方法仅处理临时性失败的重试，受时间预算约束
     */
    private void executeWithRetry(Runnable action, String operationName) {
        Exception lastException = null;
        int timeoutMs = TimeoutConfig.getElementActionTimeout();
        long deadline = System.currentTimeMillis() + timeoutMs;
        int attempt = 0;

        while (true) {
            attempt++;
            try {
                action.run();
                return;
            } catch (PlaywrightException e) {
                lastException = e;
                long remaining = deadline - System.currentTimeMillis();

                // 超时或不可重试异常 → 立即抛出
                if (remaining <= 0 || !isRetriableError(e)) {
                    break;
                }

                logger.debug("[Retry] {} on '{}' failed (attempt {}, {}ms remaining): {}",
                        operationName, selector, attempt, remaining, e.getMessage());

                // 动态延迟：剩余时间越少，延迟越短
                long delay = Math.min(RETRY_DELAY_MS, Math.max(100, remaining / (MAX_RETRY - attempt + 1)));
                getPage().waitForTimeout((int) delay);
            }
        }

        throw new RuntimeException(
                String.format("'%s' %s failed after %d attempts within %dms: %s",
                        selector, operationName, attempt, timeoutMs,
                        lastException != null ? lastException.getMessage() : "unknown"),
                lastException);
    }

    /**
     * 可重试异常判定：仅拦截元素临时不可操作、超时、DOM抖动、页面异步未就绪类错误
     */
    private static boolean isRetriableError(PlaywrightException e) {
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase();
        if (lower.contains("timeout")) {
            return true;
        }
        if (lower.contains("not visible") || lower.contains("element click intercepted")
                || lower.contains("obscures") || lower.contains("disabled")
                || lower.contains("not interactable") || lower.contains("cannot focus")) {
            return true;
        }
        if (lower.contains("detached") || lower.contains("not attached") || lower.contains("not found in dom")) {
            return true;
        }
        if (lower.contains("target closed") || lower.contains("page closed") || lower.contains("network error")) {
            return true;
        }
        return false;
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

    // ==================== 智能等待：可见/存在/隐藏 ====================
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

    // ==================== 轮询等待：enable/selected/editable ====================
    public boolean isEnabled() {
        int timeout = TimeoutConfig.getElementCheckTimeout();
        int interval = TimeoutConfig.getPollingInterval();
        long endTime = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < endTime) {
            try {
                if (locator().isEnabled()) {
                    return true;
                }
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
                if (locator().isEnabled()) {
                    return true;
                }
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
                if (locator().isChecked()) {
                    return true;
                }
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
                if (locator().isChecked()) {
                    return true;
                }
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
                if (locator().isDisabled()) {
                    return true;
                }
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
                if (locator().isDisabled()) {
                    return true;
                }
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
                if (locator().isEditable()) {
                    return true;
                }
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
                if (locator().isEditable()) {
                    return true;
                }
            } catch (Exception ignored) {}
            getPage().waitForTimeout(interval);
        }
        return false;
    }

    // ==================== 精准等待方法 ====================
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

    // ==================== 下拉/滚动/文件/键盘/鼠标/子集元素 ====================
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
                if (locator().count() > 0 && containsText(text)) {
                    return this;
                }
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
                if (text.equals(getText())) {
                    return this;
                }
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

    public Locator all() {
        return locator();
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