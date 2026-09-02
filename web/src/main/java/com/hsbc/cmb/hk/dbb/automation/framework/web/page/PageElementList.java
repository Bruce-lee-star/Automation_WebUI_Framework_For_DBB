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
    /** iframe 嵌套路径（自顶向下）；非空时在 locator() 中以 frameLocator 逐层下钻，对齐 page.pause 录制。 */
    private final List<String> frameSegs;

    // ========================== 构造（线程安全） ==========================
    public PageElementList(String selector, BasePage page) {
        this(selector, page, null);
    }

    public PageElementList(String selector, BasePage page, List<String> frameSegs) {
        if (selector == null || selector.isBlank())
            throw new IllegalArgumentException("Selector cannot be null or blank");
        if (page == null)
            throw new IllegalArgumentException("BasePage cannot be null");
        this.selector = selector;
        this.page = page;
        this.frameSegs = (frameSegs == null || frameSegs.isEmpty()) ? null : new ArrayList<>(frameSegs);
    }

    /**
     * 页面存活性保护 + Locator 重建。
     * 不再缓存 Locator——每次调用通过 {@code page.getPage()} 触发 ensurePageValid()，
     * 确保 Page 关闭重建后返回绑定到新 Page 实例的 Locator。
     * 若元素位于 iframe 内，逐层 frameLocator 下钻。
     */
    public Locator locator() {
        // 触发 ensurePageValid() → 如 page 已关闭则重建 page
        page.getPage();
        Locator base = page.locator(selector);
        if (frameSegs != null) {
            for (String seg : frameSegs) {
                base = page.getPage().frameLocator(seg).locator(base);
            }
        }
        return base;
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

    // ========================== 大小（实时查询 DOM） ==========================
    /**
     * 列表默认大小 = 当前【可见】元素数量。
     * 被隐藏（display:none / visibility:hidden / opacity:0 / 被遮挡）的元素不计入，
     * 避免后续 nth()/first() 拿到隐藏元素后，Playwright 在 click/getText 时的
     * scrollIntoViewIfNeeded 报异常。
     */
    @Override
    public int size() {
        return size(defaultTimeoutMs / 1000);
    }

    public int size(int timeoutSec) {
        try {
            Locator loc = locator();
            loc.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout((long) timeoutSec * 1000));
            return loc.count();
        } catch (TimeoutError e) {
            return 0;
        } catch (Exception e) {
            logger.warn("Unexpected error getting size: {}", selector, e);
            return 0;
        }
    }

    // ========================== 获取元素（一次等待） ==========================
    @Override
    public PageElement get(int index) {
        int currentSize = size();
        Objects.checkIndex(index, currentSize);
        return new PageElementWithIndex(selector, page, index, frameSegs);
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
                    .setState(WaitForSelectorState.VISIBLE)
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
    /** 返回第一个【可见】元素（默认语义）。无可见元素时抛异常。 */
    public PageElement first() {
        if (isEmpty(defaultTimeoutMs / 1000)) {
            throw new IllegalStateException("Element list has no visible element, cannot get first: " + selector);
        }
        return get(0);
    }

    /** 返回最后一个【可见】元素（默认语义）。无可见元素时抛异常。 */
    public PageElement last() {
        int s = size(defaultTimeoutMs / 1000);
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

    // ========================== 索引定位器内部类 ==========================
    private static final class PageElementWithIndex extends PageElement {
        private final int index;

        private PageElementWithIndex(String selector, BasePage page, int index) {
            super(selector, page, null);
            this.index = index;
        }

        private PageElementWithIndex(String selector, BasePage page, int index, List<String> frameSegs) {
            super(selector, page, frameSegs);
            this.index = index;
        }

        /**
         * 通过父类 locator().nth(index) 获取定位器。
         * Locator 不再缓存——父类的 locator() 每次动态绑定新 Page 实例，
         * 页面切换后自动使用新的 Page 重新创建 Locator。
         */
        @Override
        public Locator locator() {
            return super.locator().nth(index);
        }

        private int getIndex() {
            return index;
        }
    }
}