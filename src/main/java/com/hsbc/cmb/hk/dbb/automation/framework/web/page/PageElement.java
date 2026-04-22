package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.TimeoutConfig;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.MouseButton;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Objects;

public class PageElement {
    private static final Logger logger = LoggerFactory.getLogger(PageElement.class);

    private static final int ACTION_POST_DELAY = 200;
    private static final int MAX_RETRY = 2;
    private static final int RETRY_DELAY_MS = 300;

    private final String selector;
    private final BasePage page;

    // ==================== 构造 ====================
    public PageElement(String selector, BasePage page) {
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException("Selector cannot be null or blank");
        }
        if (page == null) {
            throw new IllegalArgumentException("BasePage cannot be null");
        }
        this.selector = selector;
        this.page = page;
    }

    public String getSelector() {
        return selector;
    }

    public BasePage getPage() {
        return page;
    }

    public Locator locator() {
        return page.locator(selector);
    }

    public Locator locator(String relativeSelector) {
        return locator().locator(relativeSelector);
    }

    // ==================== 重试 ====================
    private void executeWithRetry(Runnable action, String operation) {
        Exception lastEx = null;
        for (int i = 0; i <= MAX_RETRY; i++) {
            try {
                action.run();
                logger.debug("[{}] success: {}", operation, selector);
                return;
            } catch (PlaywrightException e) {
                lastEx = e;
                if (i == MAX_RETRY || !isRetriable(e)) {
                    break;
                }
                logger.warn("[Retry {}/{}] {} failed: {}", i+1, MAX_RETRY, operation, e.getMessage());
                page.waitForTimeout(RETRY_DELAY_MS);
            }
        }
        throw new PlaywrightException("Failed after retries: " + operation + " on " + selector, lastEx);
    }

    private boolean isRetriable(PlaywrightException e) {
        if (e instanceof TimeoutError) {
            return true;
        }
        String m = e.getMessage().toLowerCase();
        return m.contains("intercepted")
                || m.contains("obscured")
                || m.contains("detached")
                || m.contains("not interactable")
                || m.contains("not attached");
    }

    // ==================== 点击 ====================
    public PageElement click() {
        executeWithRetry(() -> {
            locator().scrollIntoViewIfNeeded();
            page.waitForTimeout(100);

            locator().click(new Locator.ClickOptions()
                    .setPosition(5, 5)
                    .setDelay(100)
                    .setForce(true));

            page.waitForTimeout(ACTION_POST_DELAY);
        }, "click");
        return this;
    }

    public PageElement doubleClick() {
        executeWithRetry(() -> {
            locator().scrollIntoViewIfNeeded();
            locator().dblclick();
            page.waitForTimeout(ACTION_POST_DELAY);
        }, "doubleClick");
        return this;
    }

    public PageElement rightClick() {
        executeWithRetry(() -> {
            locator().scrollIntoViewIfNeeded();
            locator().click(new Locator.ClickOptions().setButton(MouseButton.RIGHT));
            page.waitForTimeout(ACTION_POST_DELAY);
        }, "rightClick");
        return this;
    }

    // ==================== 输入 ====================
    public PageElement fill(String text) {
        executeWithRetry(() -> {
            locator().scrollIntoViewIfNeeded();
            locator().fill(text);
        }, "fill");
        return this;
    }

    public PageElement type(String text) {
        executeWithRetry(() -> {
            locator().scrollIntoViewIfNeeded();
            locator().pressSequentially(text);
        }, "type");
        return this;
    }

    public PageElement clear() {
        executeWithRetry(() -> locator().clear(), "clear");
        return this;
    }

    public PageElement clearAndSetValue(String text) {
        return clear().fill(text);
    }

    public PageElement clearAndTypeSequentially(String text) {
        return clear().type(text);
    }

    // ==================== 状态判断（无参 + 有参，全部不抛异常） ====================
    public boolean isVisible() {
        return isVisible(TimeoutConfig.getElementCheckTimeout() / 1000);
    }

    public boolean isVisible(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout((long) timeoutSec * 1000));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isNotVisible() {
        return isNotVisible(TimeoutConfig.getElementCheckTimeout() / 1000);
    }

    public boolean isNotVisible(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN)
                    .setTimeout((long) timeoutSec * 1000));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean exists() {
        return exists(TimeoutConfig.getElementCheckTimeout() / 1000);
    }

    public boolean exists(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout((long) timeoutSec * 1000));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isEnabled() {
        return isEnabled(TimeoutConfig.getElementCheckTimeout() / 1000);
    }

    public boolean isEnabled(int timeoutSec) {
        if (!exists(timeoutSec)) return false;
        try { return locator().isEnabled(); } catch (Exception e) { return false; }
    }

    public boolean isDisabled() {
        return !isEnabled();
    }

    public boolean isDisabled(int timeoutSec) {
        return !isEnabled(timeoutSec);
    }

    public boolean isEditable() {
        return isEditable(TimeoutConfig.getElementCheckTimeout() / 1000);
    }

    public boolean isEditable(int timeoutSec) {
        if (!exists(timeoutSec)) return false;
        try { return locator().isEditable(); } catch (Exception e) { return false; }
    }

    public boolean isChecked() {
        return isChecked(TimeoutConfig.getElementCheckTimeout() / 1000);
    }

    public boolean isChecked(int timeoutSec) {
        if (!exists(timeoutSec)) return false;
        try { return locator().isChecked(); } catch (Exception e) { return false; }
    }

    // ==================== 文本 ====================
    public String getText() {
        try {
            String raw = locator().textContent();
            if (raw == null) {
                logger.warn("getText() returned null for selector: {}", selector);
                return "";
            }
            return raw.replace('\u00A0', ' ')
                    .replaceAll("\\s+", " ")
                    .trim();
        } catch (Exception e) {
            logger.warn("getText failed: {}", selector, e);
            return "";
        }
    }

    public String getInnerText() {
        try {
            return locator().innerText();
        } catch (Exception e) {
            logger.warn("getInnerText failed: {}", selector, e);
            return "";
        }
    }

    public String getAttribute(String attr) {
        try {
            return locator().getAttribute(attr);
        } catch (Exception e) {
            logger.warn("getAttribute failed: {}", selector, e);
            return null;
        }
    }

    public String getValue() {
        try {
            return locator().inputValue();
        } catch (Exception e) {
            logger.warn("getValue failed: {}", selector, e);
            return "";
        }
    }

    // ==================== 下拉选择 ====================
    public PageElement selectByValue(String value) {
        executeWithRetry(() -> locator().selectOption(value), "selectByValue");
        return this;
    }

    public PageElement selectByIndex(int index) {
        executeWithRetry(() -> locator().selectOption(new SelectOption().setIndex(index)), "selectByIndex");
        return this;
    }

    // ==================== 等待方法（会抛异常 → 用于断言） ====================
    public PageElement waitVisible(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout((long) timeoutSec * 1000));
            return this;
        } catch (Exception e) {
            throw new PlaywrightException("Element not visible within " + timeoutSec + "s: " + selector, e);
        }
    }

    public PageElement waitForVisible(int timeout) {
        return waitVisible(timeout);
    }

    public PageElement waitForNotVisible(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN)
                    .setTimeout((long) timeoutSec * 1000));
            return this;
        } catch (Exception e) {
            throw new PlaywrightException("Element still visible after " + timeoutSec + "s: " + selector, e);
        }
    }

    public PageElement waitHidden() {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN)
                    .setTimeout(TimeoutConfig.getElementCheckTimeout()));
            return this;
        } catch (Exception e) {
            throw new PlaywrightException("Element not hidden: " + selector, e);
        }
    }

    // ==================== 工具 ====================
    public PageElement hover() {
        executeWithRetry(() -> locator().hover(), "hover");
        return this;
    }

    public PageElement focus() {
        executeWithRetry(() -> locator().focus(), "focus");
        return this;
    }

    public PageElement check() {
        executeWithRetry(() -> locator().check(), "check");
        return this;
    }

    public PageElement uncheck() {
        executeWithRetry(() -> locator().uncheck(), "uncheck");
        return this;
    }

    public PageElement uploadFile(String... paths) {
        Path[] pathArray = Arrays.stream(paths).map(Paths::get).toArray(Path[]::new);
        executeWithRetry(() -> locator().setInputFiles(pathArray), "uploadFile");
        return this;
    }

    public BoundingBox getBoundingBoxSafe() {
        waitVisible(TimeoutConfig.getElementCheckTimeout() / 1000);
        BoundingBox box = locator().boundingBox();
        return Objects.requireNonNull(box, "BoundingBox is null: " + selector);
    }

    public int count() {
        return locator().count();
    }

    // ==================== 子元素 ====================
    public PageElement child(String childSelector) {
        Objects.requireNonNull(childSelector, "childSelector must not be null");
        String clean = childSelector.trim();
        while (clean.startsWith(">>")) clean = clean.substring(2).trim();

        String parent = selector.trim();
        while (parent.endsWith(">>")) parent = parent.substring(0, parent.length() - 2).trim();

        return new PageElement(parent + " >> " + clean, page);
    }

    public PageElement child(String childSelector, int index) {
        PageElement child = child(childSelector);
        return new PageElement(child.getSelector() + " >> nth=" + index, page);
    }

    @Override
    public String toString() {
        return "PageElement[" + selector + "]";
    }
}