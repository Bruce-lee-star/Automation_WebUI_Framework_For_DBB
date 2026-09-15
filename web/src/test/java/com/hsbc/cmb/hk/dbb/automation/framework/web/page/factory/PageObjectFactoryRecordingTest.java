package com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory;

import com.hsbc.cmb.hk.dbb.automation.framework.web.core.RuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.ManagedPageAware;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.recording.RecordingPageProxy;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 专属 UT：验证 {@link PageObjectFactory} 为组合式 Page Object（{@link ManagedPageAware}）
 * 注入录制装饰的受管 Page；并用 fake {@link RuntimeProvider}（返回 fake Page）脱离真实浏览器。
 * 同时验证 D6：测试替身（{@code isTestDouble}）不包装。
 */
class PageObjectFactoryRecordingTest {

    private String prevLogging;
    private RuntimeProvider prevProvider;

    /** 测试用组合式 Page Object：仅持有工厂注入的受管 Page 供应器。 */
    public static final class DummyManagedPage implements ManagedPageAware {
        private Supplier<Page> managedPage;
        @Override
        public void setManagedPage(Supplier<Page> s) {
            this.managedPage = s;
        }
        public Page getPage() {
            return managedPage.get();
        }
    }

    /** 轻量 fake Page：记录被调用方法。 */
    private static final class CallRecorder implements InvocationHandler {
        final List<String> calls = new ArrayList<>();
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("toString")) return "FAKE";
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                return null;
            }
            calls.add(method.getName());
            return null;
        }
    }

    private static Page fakePage(CallRecorder rec) {
        return (Page) Proxy.newProxyInstance(PageObjectFactoryRecordingTest.class.getClassLoader(),
                new Class<?>[]{Page.class}, rec);
    }

    /** fake provider：返回 fakePage；{@code isTestDouble} 可控。 */
    private static final class FakeProvider implements RuntimeProvider {
        final CallRecorder rec = new CallRecorder();
        final Page page = fakePage(rec);
        boolean testDouble;
        @Override
        public com.microsoft.playwright.Playwright getPlaywright() {
            return null;
        }
        @Override
        public com.microsoft.playwright.Browser getBrowser() {
            return null;
        }
        @Override
        public com.microsoft.playwright.BrowserContext getContext() {
            return null;
        }
        @Override
        public Page getPage() {
            return page;
        }
        @Override
        public boolean isTestDouble() {
            return testDouble;
        }
    }

    @BeforeEach
    void setUp() {
        prevLogging = System.getProperty("serenity.logging");
        prevProvider = PlaywrightManager.getProvider();
        System.setProperty("serenity.logging", "VERBOSE"); // 开启录制开关
    }

    @AfterEach
    void tearDown() {
        PlaywrightManager.setProvider(prevProvider);
        PageObjectFactory.clear(DummyManagedPage.class);
        if (prevLogging == null) {
            System.clearProperty("serenity.logging");
        } else {
            System.setProperty("serenity.logging", prevLogging);
        }
    }

    @Test
    void factory_injectsDecoratedPage_whenEnabledAndNotTestDouble() {
        FakeProvider fp = new FakeProvider();
        fp.testDouble = false;
        PlaywrightManager.setProvider(fp);

        DummyManagedPage page = PageObjectFactory.getPage(DummyManagedPage.class);
        Page managed = page.getPage();

        assertTrue(Proxy.isProxyClass(managed.getClass()),
                "factory-injected managed page must be decorated (recording proxy)");
        managed.click("btn");
        assertTrue(fp.rec.calls.contains("click"), "native op delegated to underlying page");
    }

    @Test
    void factory_doesNotWrap_testDouble() {
        FakeProvider fp = new FakeProvider();
        fp.testDouble = true; // mock
        PlaywrightManager.setProvider(fp);

        DummyManagedPage page = PageObjectFactory.getPage(DummyManagedPage.class);
        Page managed = page.getPage();

        assertSame(fp.page, managed, "test double must NOT be wrapped (D6)");
    }
}
