package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.TimeoutConfig;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.WaitForSelectorState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.AbstractList;
import java.util.List;

/**
 * Dynamic list of PageElements with smart multi-stage waiting.
 * Re-queries elements on each access to ensure freshness.
 * 
 * <pre>
 * @Element("[data-i18n='button_logon']")
 * public List&lt;PageElement&gt; loginButtons; 
 * 
 * // Iterate all buttons
 * for (PageElement button : loginButtons) {
 *     button.click();
 * } 
 * // Get first element (with auto-wait: ATTACHED -> VISIBLE -> DOM stable)
 * loginButtons.get(0).click();
 * </pre>
 */
public class PageElementList extends AbstractList<PageElement> {
    private static final Logger logger = LoggerFactory.getLogger(PageElementList.class);
    private final String selector;
    private final BasePage page;
    
    public PageElementList(String selector, BasePage page) {
        if (selector == null || selector.trim().isEmpty()) {
            throw new IllegalArgumentException("Selector cannot be null or empty");
        }
        this.selector = selector;
        this.page = page;
    }
    
    public String getSelector() {
        return selector;
    }
    
    private BasePage getPage() {
        if (page == null) {
            BasePage currentPage = BasePage.getCurrentPage();
            if (currentPage == null) {
                throw new IllegalStateException("No BasePage instance found. Please ensure page is initialized.");
            }
            return currentPage;
        }
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
     * 
     * @param timeoutSeconds 超时时间（秒）
     */
    public List<Locator> allLocators(int timeoutSeconds) {
        waitForVisible(timeoutSeconds);
        return getPage().locator(selector).all();
    }

    /**
     * 获取元素数量（等待至少1个元素存在）
     */
    @Override
    public int size() {
        return size(TimeoutConfig.getElementCheckTimeout() / 1000);
    }

    /**
     * 获取元素数量（等待至少1个元素存在）
     * 
     * @param timeoutSeconds 超时时间（秒）
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

        // Final verification (should always pass if waitForElementReady succeeded)
        int count = getPage().locator(selector).count();
        if (index >= count) {
            throw new IllegalStateException(
                "Element not found: expected index " + index + 
                " but only found " + count + " element(s)" +
                ", selector: " + selector);
        }
        return new PageElementWithIndex(selector, page, index);
    }

    /**
     * 等待至少一个元素存在
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
     * 等待至少一个元素可见
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
        long startTime = System.currentTimeMillis();
        int interval = TimeoutConfig.getPollingInterval();
        
        while (System.currentTimeMillis() - startTime < timeoutSeconds * 1000L) {
            int count = getPage().locator(selector).count();
            if (count >= expectedCount) {
                return;
            }
            getPage().waitForTimeout(interval);
        }
        
        throw new IllegalStateException("Expected at least " + expectedCount + 
            " elements for selector '" + selector + "', but found only " + 
            getPage().locator(selector).count() + " within " + timeoutSeconds + "s");
    }

    /**
     * Smart multi-stage wait ensuring the target element is ready for interaction.
     * Uses Playwright's native waitFor (auto-retry, no manual polling loop).
     */
    private void waitForElementReady(int targetIndex) {
        int timeout = TimeoutConfig.getElementCheckTimeout();

        // Stage 0: Page stable before searching
        try {
            getPage().waitForDOMContentLoaded(Math.max(TimeoutConfig.getStabilizeTimeout() / 1000, 5));
        } catch (Exception e) {
            logger.debug("Page stability check skipped: {}", e.getMessage());
        }

        // Stage 1 & 2: Single smart waitFor call handles existence + visibility
        try {
            locator().nth(targetIndex).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(timeout));
        } catch (Exception e) {
            final int finalCount = size();
            if (finalCount == 0) {
                throw new IllegalStateException(String.format(
                    "No elements found for selector '%s' within %dms. Verify selector.",
                    selector, timeout));
            } else if (finalCount <= targetIndex) {
                throw new IllegalStateException(String.format(
                    "Not enough elements: needed index %d, but found only %d for selector '%s'.",
                    targetIndex, finalCount, selector));
            } else {
                throw new IllegalStateException(String.format(
                    "Element at index %d for '%s' did not become visible within %dms.",
                    targetIndex, selector, timeout));
            }
        }
    }
    
    public Locator locator() {
        return getPage().locator(selector);
    }
    
    /**
     * 检查列表是否为空（不等待，立即返回）
     */
    public boolean isEmpty() {
        return getPage().locator(selector).count() == 0;
    }

    /**
     * 检查列表是否为空（等待指定时间后再判断）
     */
    public boolean isEmpty(int timeoutSeconds) {
        try {
            waitForExists(timeoutSeconds);
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }
    
    /**
     * Get first element (throws if empty after waiting)
     */
    public PageElement first() {
        return get(0);
    }
    
    /**
     * Get last element (throws if empty after waiting)
     */
    public PageElement last() {
        int count = size();
        if (count == 0) {
            throw new IllegalStateException("No elements found for selector: " + selector);
        }
        return get(count - 1);
    }
    
    /**
     * 检查是否存在元素（不等待，立即返回）
     */
    public boolean hasElements() {
        return !isEmpty();
    }

    /**
     * 检查是否存在元素（等待指定时间后再判断）
     */
    public boolean hasElements(int timeoutSeconds) {
        try {
            waitForExists(timeoutSeconds);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }
    
    /**
     * 等待至少一个元素存在且可见（默认超时时间）
     */
    public void waitFor() {
        waitFor(TimeoutConfig.getElementCheckTimeout() / 1000);
    }

    /**
     * 等待至少一个元素存在且可见
     */
    public void waitFor(int timeoutSeconds) {
        waitForVisible(timeoutSeconds);
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
