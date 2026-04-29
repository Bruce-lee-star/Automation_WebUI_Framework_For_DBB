package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

public final class PageElementList extends AbstractList<PageElement> {
    private static final Logger logger = LoggerFactory.getLogger(PageElementList.class);
    private final String selector;
    private final BasePage page;
    private final int defaultTimeoutSec = PlaywrightManager.config().getElementCheckTimeout() / 1000;

    // ========================== 构造（线程安全） ==========================
    public PageElementList(String selector, BasePage page) {
        if (selector == null || selector.isBlank())
            throw new IllegalArgumentException("Selector cannot be null or blank");
        if (page == null)
            throw new IllegalArgumentException("BasePage cannot be null");
        this.selector = selector;
        this.page = page;
    }

    public Locator locator() {
        return page.locator(selector);
    }

    public String getSelector() {
        return selector;
    }

    // ========================== 核心等待 ==========================
    public void waitForExists(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout((long) timeoutSec * 1000));
        } catch (Exception e) {
            throw new IllegalStateException(String.format("[%ds] No elements attached: %s", timeoutSec, selector), e);
        }
    }

    public void waitForVisible(int timeoutSec) {
        try {
            locator().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout((long) timeoutSec * 1000));
        } catch (Exception e) {
            throw new IllegalStateException(String.format("[%ds] No elements visible: %s", timeoutSec, selector), e);
        }
    }

    /**
     * 等待元素数量 ≥ expectCount
     * ✅ expectCount = 0 立即返回
     * ✅ 不依赖 JS
     * ✅ 超时精确
     * ✅ 支持 CSS + XPath
     */
    public void waitForCount(int expectCount, int timeoutSec) {
        if (expectCount == 0) {
            return;
        }

        long timeoutMs = (long) timeoutSec * 1000;
        long start = System.currentTimeMillis();

        try {
            locator().first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout(timeoutMs));

            int actual = locator().count();
            if (actual >= expectCount) return;

            while (System.currentTimeMillis() - start < timeoutMs) {
                page.waitForTimeout(150);
                actual = locator().count();
                if (actual >= expectCount) return;
            }

            throw new IllegalStateException(
                    String.format("Expected ≥ %d, found %d: %s", expectCount, actual, selector));

        } catch (TimeoutError e) {
            int actual = locator().count();
            throw new IllegalStateException(
                    String.format("Timeout after %ds: expected ≥ %d, found %d: %s",
                            timeoutSec, expectCount, actual, selector), e);
        }
    }

    // ========================== 大小（性能优化版） ==========================
    @Override
    public int size() {
        return size(defaultTimeoutSec);
    }

    public int size(int timeoutSec) {
        try {
            Locator loc = locator();
            loc.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout((long) timeoutSec * 1000));
            return loc.count();
        } catch (TimeoutError e) {
            return 0;
        } catch (Exception e) {
            logger.warn("Unexpected error getting size: {}", selector, e);
            return 0;
        }
    }

    // ========================== 获取元素（无竞态） ==========================
    @Override
    public PageElement get(int index) {
        int currentSize = size();
        Objects.checkIndex(index, currentSize);

        try {
            Locator target = locator().nth(index);
            target.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(PlaywrightManager.config().getElementCheckTimeout()));

            return new PageElementWithIndex(selector, page, index, target);
        } catch (Exception e) {
            throw new IllegalStateException(
                    String.format("Failed to get element index=%d/%d, selector=%s",
                            index, currentSize, selector), e);
        }
    }

    // ========================== 迭代器 ==========================
    @Override
    public Iterator<PageElement> iterator() {
        int count = size();
        if (count == 0) return Collections.emptyIterator();

        return new Iterator<>() {
            private int idx = 0;

            @Override
            public boolean hasNext() {
                return idx < count;
            }

            @Override
            public PageElement next() {
                if (!hasNext()) throw new NoSuchElementException();
                return get(idx++);
            }
        };
    }

    // ========================== 安全遍历 ==========================
    public void forEachSafe(Consumer<PageElement> action) {
        int count = size();
        for (int i = 0; i < count; i++) {
            try {
                action.accept(get(i));
            } catch (Exception e) {
                logger.warn("Skip element at index [{}] for selector: {}", i, selector, e);
            }
        }
    }

    // ========================== 空判断 ==========================
    @Override
    public boolean isEmpty() {
        return isEmpty(defaultTimeoutSec);
    }

    public boolean isEmpty(int timeoutSec) {
        try {
            locator().first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout((long) timeoutSec * 1000));
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    public boolean hasElements() {
        return !isEmpty();
    }

    // ========================== 快捷方法 ==========================
    public PageElement first() {
        if (isEmpty()) {
            throw new IllegalStateException("Element list is empty, cannot get first: " + selector);
        }
        return get(0);
    }

    public PageElement last() {
        int s = size();
        if (s == 0) {
            throw new IllegalStateException("Element list is empty, cannot get last: " + selector);
        }
        return get(s - 1);
    }

    /**
     * ⚠️ 返回当前 DOM 快照，非实时集合
     * 全版本 Playwright 兼容
     */
    public List<Locator> allLocators() {
        waitForVisible(defaultTimeoutSec);
        return locator().all();
    }

    public void waitFor() {
        waitForVisible(defaultTimeoutSec);
    }

    // ========================== 缓存定位器内部类 ==========================
    private static final class PageElementWithIndex extends PageElement {
        private final int index;
        private final Locator cachedLocator;

        public PageElementWithIndex(String selector, BasePage page, int index, Locator cachedLocator) {
            super(selector, page);
            this.index = index;
            this.cachedLocator = Objects.requireNonNull(cachedLocator);
        }

        @Override
        public Locator locator() {
            return cachedLocator;
        }

        public int getIndex() {
            return index;
        }
    }
}