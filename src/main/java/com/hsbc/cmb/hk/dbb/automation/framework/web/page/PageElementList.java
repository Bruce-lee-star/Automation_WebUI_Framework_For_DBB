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

    public List<Locator> allLocators() {
        return getPage().locator(selector).all();
    }

    @Override
    public int size() {
        return allLocators().size();
    }

    @Override
    public PageElement get(int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("Index cannot be negative: " + index);
        }
        waitForElementReady(index);

        // Final verification (should always pass if waitForElementReady succeeded)
        List<Locator> locators = allLocators();
        if (index >= locators.size()) {
            throw new IllegalStateException(
                "Element not found: expected index " + index + 
                " but only found " + locators.size() + " element(s)" +
                ", selector: " + selector);
        }
        return new PageElementWithIndex(selector, page, index);
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
    
    public boolean isEmpty() {
        return size() == 0;
    }
    
    /**
     * Get first element (throws if empty after waiting)
     */
    public PageElement first() {
        if (isEmpty()) {
            throw new IllegalStateException("No elements found for selector: " + selector);
        }
        return get(0);
    }
    
    /**
     * Get last element (throws if empty after waiting)
     */
    public PageElement last() {
        if (isEmpty()) {
            throw new IllegalStateException("No elements found for selector: " + selector);
        }
        return get(size() - 1);
    }
    
    public boolean hasElements() {
        return !isEmpty();
    }
    
    /**
     * Wait for at least one element to exist and be visible
     */
    public void waitFor(int timeoutSeconds) {
        getPage().waitForElementExists(selector, timeoutSeconds);
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
