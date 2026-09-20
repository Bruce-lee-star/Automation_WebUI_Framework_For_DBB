package com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.ManagedPageAware;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link PageObjectFactory} 对组合式 PageObject 的「受管 Page 注入」覆盖两条创建路径的回归测试
 * （固化评审 F-02，不启动浏览器）。
 *
 * <p><b>修复前的缺陷</b>：{@code ManagedPageAware} 注入代码位于反射回退的 {@code else} 分支内，
 * 于是文档推荐、编译期安全的 {@code register()} / {@code customSupplier()} 路径
 * <b>完全不注入</b> → 组合式页面 {@code AbstractManagedPage.getPage()} 直接 NPE
 * （"越按推荐写法越坏"）。</p>
 *
 * <p>注入本身是惰性供应器（不得在 setManagedPage 内取值），故本测试以「供应器是否被注入」为断言口径。</p>
 */
public class PageObjectFactoryManagedPageInjectionTest {

    /** register()（推荐路径）探针。 */
    public static class RegisteredProbePage implements ManagedPageAware {
        volatile Supplier<Page> managedPage;

        @Override
        public void setManagedPage(Supplier<Page> managedPage) {
            this.managedPage = managedPage;
        }
    }

    /** 反射回退路径探针（public 无参构造 + 未登记 Supplier）。 */
    public static class ReflectiveProbePage implements ManagedPageAware {
        volatile Supplier<Page> managedPage;

        @Override
        public void setManagedPage(Supplier<Page> managedPage) {
            this.managedPage = managedPage;
        }
    }

    @AfterEach
    public void cleanup() {
        PageObjectFactory.unregister(RegisteredProbePage.class);
        PageObjectFactory.clearAll();
    }

    @Test
    public void registeredSupplierPathAlsoInjectsManagedPage() {
        PageObjectFactory.register(RegisteredProbePage.class, RegisteredProbePage::new);

        RegisteredProbePage instance =
                (RegisteredProbePage) PageObjectFactory.getPage(RegisteredProbePage.class);

        assertNotNull(instance.managedPage,
                "register()/customSupplier()（推荐路径）必须注入受管 Page —— 修复评审 F-02 前此处为 null");
    }

    @Test
    public void reflectiveFallbackPathStillInjectsManagedPage() {
        ReflectiveProbePage instance =
                (ReflectiveProbePage) PageObjectFactory.getPage(ReflectiveProbePage.class);

        assertNotNull(instance.managedPage, "反射回退路径的注入行为不得回归");
    }
}
