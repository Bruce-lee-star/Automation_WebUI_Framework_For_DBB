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
    private final int defaultTimeoutMs = PlaywrightManager.config().getElementCheckTimeout();

    /**
     * 缓存首次 size() 调用结果。设置为 -1 表示未缓存。
     * 调用 invalidateCache() 可强制重新计算。
     * 注意：size 缓存是首次调用时计算的一次性快照，非实时。
     */
    private volatile int cachedSize = -1;

    /**
     * 缓存的 Locator 实例（DCL 线程安全懒加载）。
     * Playwright Locator 是延迟求值的——每次操作时重新查询 DOM，缓存是安全的。
     */
    private volatile Locator cachedLocator;

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
        Locator loc = cachedLocator;
        if (loc == null) {
            synchronized (this) {
                loc = cachedLocator;
                if (loc == null) {
                    cachedLocator = page.locator(selector);
                    loc = cachedLocator;
                }
            }
        }
        return loc;
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
     *
     * 使用 Playwright 原生 Locator.waitFor() 而非忙等轮询，避免 CPU 浪费。
     * 策略：
     * 1. expectCount == 0：立即返回（无需等待）
     * 2. 先等待第 expectCount 个元素 ATTACHED（Playwright 原生等待，零 CPU 开销）
     * 3. 确认数量达标
     *
     * @param expectCount 期望的最小元素数量
     * @param timeoutSec  超时时间（秒）
     */
    public void waitForCount(int expectCount, int timeoutSec) {
        if (expectCount <= 0) {
            return;
        }

        long timeoutMs = (long) timeoutSec * 1000;

        try {
            // 使用 Playwright 原生等待：等待第 expectCount 个元素出现在 DOM 中
            // nth(expectCount - 1) 定位到第 N 个元素（0-based），waitFor ATTACHED 等待其出现
            locator().nth(expectCount - 1).waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout(timeoutMs));

            // 确认数量（此时第 N 个元素已出现在 DOM，但 count 可能更多）
            int actual = locator().count();
            if (actual < expectCount) {
                throw new IllegalStateException(
                        String.format("Expected ≥ %d, found %d: %s", expectCount, actual, selector));
            }
        } catch (TimeoutError e) {
            int actual = locator().count();
            throw new IllegalStateException(
                    String.format("Timeout after %ds: expected ≥ %d, found %d: %s",
                            timeoutSec, expectCount, actual, selector), e);
        }
    }

    // ========================== 大小（缓存优化版） ==========================
    @Override
    public int size() {
        return size(defaultTimeoutMs / 1000);
    }

    public int size(int timeoutSec) {
        if (cachedSize >= 0) return cachedSize;
        synchronized (this) {
            if (cachedSize >= 0) return cachedSize;
            try {
                Locator loc = locator();
                loc.first().waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.ATTACHED)
                        .setTimeout((long) timeoutSec * 1000));
                cachedSize = loc.count();
                return cachedSize;
            } catch (TimeoutError e) {
                cachedSize = 0;
                return 0;
            } catch (Exception e) {
                logger.warn("Unexpected error getting size: {}", selector, e);
                cachedSize = 0;
                return 0;
            }
        }
    }

    /**
     * 使所有缓存失效（size + Locator），下次调用将重新查询 DOM。
     * 适用于页面内容动态变化后需要重新计数的场景。
     */
    public void invalidateCache() {
        cachedSize = -1;
        cachedLocator = null;
    }

    // ========================== 获取元素（一次等待） ==========================
    @Override
    public PageElement get(int index) {
        int currentSize = size();
        Objects.checkIndex(index, currentSize);

        // size() 已通过 waitFor(ATTACHED) 确认元素存在，无需再次等待 VISIBLE
        // 元素的可见性交给后续操作自行判断（Playwright Locator 操作时自动等待）
        return new PageElementWithIndex(selector, page, index, locator().nth(index));
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
        return isEmpty(defaultTimeoutMs / 1000);
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
     * 返回当前 DOM 快照中的所有 Locator（非实时集合，调用后 DOM 变化不会反映在返回列表中）。
     * 全版本 Playwright 兼容。
     */
    public List<Locator> allLocators() {
        waitForVisible(defaultTimeoutMs / 1000);
        return locator().all();
    }

    public void waitFor() {
        waitForVisible(defaultTimeoutMs / 1000);
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