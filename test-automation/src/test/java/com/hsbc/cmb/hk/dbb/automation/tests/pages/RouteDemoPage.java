package com.hsbc.cmb.hk.dbb.automation.tests.pages;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.ManagedPageAware;
import com.microsoft.playwright.Page;

import java.util.function.Supplier;

/**
 * Route Demo Service 集成测试页面对象（新模型：组合式 POJO，无继承，G1）。
 *
 * <p>本页仅用于承载框架自动托管的 Playwright Page / BrowserContext，不依赖任何具体 UI 元素
 * （测试通过 {@code page.evaluate} 在同源 JS 上下文发起 fetch 以被框架路由拦截）。
 *
 * <p>由框架自有 {@code PageObjectFactory} 经 {@link ManagedPageAware} 注入受管 Page 惰性供应器；
 * {@code getPage()} 返回由 {@code RecordingPageProxy} 装饰（enabled 时）的受管 Page，
 * 原生操作（evaluate/navigate/...）自动录入 Serenity 报告（Layer A）。彻底无继承、零 {@code @RoleElement}/UI 方法。
 */
public class RouteDemoPage implements ManagedPageAware {

    private Supplier<Page> managedPage;

    @Override
    public void setManagedPage(Supplier<Page> managedPageSupplier) {
        this.managedPage = managedPageSupplier;
    }

    /**
     * 当前线程受管 Page 的录制装饰实例（enabled 时）；关闭时返回裸 Page。懒加载避免跨场景失效。
     */
    public Page getPage() {
        return managedPage.get();
    }
}
