package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.microsoft.playwright.Page;

import java.util.function.Supplier;

/**
 * 组合式 Page Object 标记接口（新模型，G1 业务 Page 零继承）。
 *
 * <p>框架自有 {@code PageObjectFactory} 在创建组合式页面对象后，经本接口注入受管 Page 的
 * <b>惰性供应器</b>；页面对象经 {@code getPage()} 等入口取得由 {@code RecordingPageProxy}
 * 装饰（enabled 时）的受管 Playwright Page，从而原生操作自动录制（Layer A），无需继承
 * {@code BasePage} / {@code SerenityBasePage}。
 *
 * <p>惰性供应器避免工厂创建期即触碰浏览器（受管 Page 随场景切换），页面对象每次 {@code getPage()}
 * 取得当前线程最新的受管 Page。
 */
public interface ManagedPageAware {

    /**
     * 由 {@code PageObjectFactory} 注入受管 Page 惰性供应器；页面对象不应自行调用。
     *
     * @param managedPageSupplier 返回录制装饰（enabled 时）的受管 Page
     */
    void setManagedPage(Supplier<Page> managedPageSupplier);
}
