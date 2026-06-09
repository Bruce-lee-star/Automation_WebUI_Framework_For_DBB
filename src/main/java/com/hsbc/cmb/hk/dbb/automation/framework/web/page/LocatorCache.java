package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

import com.microsoft.playwright.Locator;

import java.util.function.Supplier;

/**
 * DCL（双重检查锁定）线程安全的 Locator 缓存器。
 * <p>
 * Playwright Locator 是"延迟求值"的——每次操作时重新查询 DOM，因此缓存是安全的。
 * Page 切换重建由 {@code BasePage.ensurePageValid() → initializeAnnotatedFields() → invalidateCache()} 触发。
 * <p>
 * 供 {@link PageElement} 和 {@link PageElementList} 共用，消除重复的 DCL 模板代码。
 */
final class LocatorCache {
    private volatile Locator cached;

    /**
     * 获取或创建缓存的 Locator。
     *
     * @param factory 创建 Locator 的工厂（通常为 {@code () -> page.locator(selector)}）
     * @return 缓存的 Locator（第一次调用时由 factory 创建）
     */
    Locator get(Supplier<Locator> factory) {
        Locator loc = cached;
        if (loc == null) {
            synchronized (this) {
                loc = cached;
                if (loc == null) {
                    cached = loc = factory.get();
                }
            }
        }
        return loc;
    }

    /** 使缓存失效，下次 {@link #get(Supplier)} 将重新通过 factory 创建 */
    void invalidate() {
        cached = null;
    }
}
