package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.TimeoutConfig;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Dynamic list of PageElements with smart multi-stage waiting.
 * Re-queries elements on each access to ensure freshness.
 */
public class PageElementList extends AbstractList<PageElement> {
    private static final Logger logger = LoggerFactory.getLogger(PageElementList.class);
    private final String selector;
    private final BasePage page;

    public PageElementList(String selector, BasePage page) {
        if (selector == null || selector.trim().isEmpty()) {
            throw new IllegalArgumentException("Selector cannot be null or empty");
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

    private BasePage getPage() {
        return page;
    }

    /**
     * 获取所有匹配的 Locator（等待至少1个元素可见）
     */
    public List<Locator> allLocators() {
        return allLocators(TimeoutConfig.getElementCheckTimeout() / 1000);
    }

    /**
     * 获取所有匹配的 Locator（等待至少1个元素可见）
     */
    public List<Locator> allLocators(int timeoutSeconds) {
        waitForVisible(timeoutSeconds);
        return getPage().locator(selector).all();
    }

    /**
     * 获取元素数量（等待元素存在，不等待可见）
     */
    @Override
    public int size() {
        return size(TimeoutConfig.getElementCheckTimeout() / 1000);
    }

    /**
     * 获取元素数量（等待元素存在，不等待可见）
     */
    public int size(int timeoutSeconds) {
        waitForExists(timeoutSeconds);
        return getPage().locator(selector).count();
    }

    @Override
    public PageElement get(int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("Index cannot be negative: " + index);
        }
        waitForElementReady(index);
        return new PageElementWithIndex(selector, page, index);
    }

    /**
     * 等待至少一个元素存在（ATTACHED）
     */
    public void waitForExists(int timeoutSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout(timeoutSeconds * 1000L));
        } catch (Exception e) {
            throw new IllegalStateException("No elements found for selector '" + selector +
                    "' within " + timeoutSeconds + "s", e);
        }
    }

    /**
     * 等待至少一个元素可见（VISIBLE）
     */
    public void waitForVisible(int timeoutSeconds) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(timeoutSeconds * 1000L));
        } catch (Exception e) {
            throw new IllegalStateException("No visible elements found for selector '" + selector +
                    "' within " + timeoutSeconds + "s", e);
        }
    }

    /**
     * 等待指定数量的元素存在
     */
    public void waitForCount(int expectedCount, int timeoutSeconds) {
        long timeoutMs = timeoutSeconds * 1000L;
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start < timeoutMs) {
            int count = locator().count();
            if (count >= expectedCount) {
                return;
            }
            getPage().waitForTimeout(TimeoutConfig.getPollingInterval());
        }

        throw new IllegalStateException(String.format(
                "Expected at least %d elements, found %d for selector: %s",
                expectedCount, locator().count(), selector
        ));
    }

    /**
     * 等待目标索引元素可见且可交互
     */
    private void waitForElementReady(int targetIndex) {
        int timeout = TimeoutConfig.getElementCheckTimeout();

        try {
            getPage().waitForDOMContentLoaded(Math.max(TimeoutConfig.getStabilizeTimeout() / 1000, 5));
        } catch (Exception e) {
            logger.debug("Page stability check skipped: {}", e.getMessage());
        }

        try {
            locator().nth(targetIndex).waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(timeout));
        } catch (Exception e) {
            int finalCount = locator().count();
            if (finalCount == 0) {
                throw new IllegalStateException(String.format(
                        "No elements found for selector '%s' in %dms", selector, timeout));
            } else if (targetIndex >= finalCount) {
                throw new IllegalStateException(String.format(
                        "Index %d out of bounds: only %d elements exist", targetIndex, finalCount));
            } else {
                throw new IllegalStateException(String.format(
                        "Element %d not visible in %dms", targetIndex, timeout));
            }
        }
    }

    public Locator locator() {
        return getPage().locator(selector);
    }

    @Override
    public boolean isEmpty() {
        return isEmpty(TimeoutConfig.getElementCheckTimeout() / 1000);
    }

    public boolean isEmpty(int timeoutSeconds) {
        try {
            waitForExists(timeoutSeconds);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    public PageElement first() {
        return get(0);
    }

    public PageElement last() {
        return get(size() - 1);
    }

    public boolean hasElements() {
        return !isEmpty();
    }

    public boolean hasElements(int timeoutSeconds) {
        return !isEmpty(timeoutSeconds);
    }

    public void waitFor() {
        waitFor(TimeoutConfig.getElementCheckTimeout() / 1000);
    }

    public void waitFor(int timeoutSeconds) {
        waitForVisible(timeoutSeconds);
    }

    // ==================== 修复迭代器 ====================
    @Override
    public Iterator<PageElement> iterator() {
        List<PageElement> elements = new ArrayList<>();
        List<Locator> locators = allLocators();
        for (int i = 0; i < locators.size(); i++) {
            elements.add(new PageElementWithIndex(selector, page, i));
        }
        return elements.iterator();
    }

    private static class PageElementWithIndex extends PageElement {
        private final int index;

        public PageElementWithIndex(String selector, BasePage page, int index) {
            super(selector, page);
            this.index = index;
        }

        @Override
        public Locator locator() {
            return super.locator().nth(index);
        }

        public int getIndex() {
            return index;
        }
    }
}