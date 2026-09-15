package com.hsbc.cmb.hk.dbb.automation.framework.web.page.recording;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 专属 UT：验证 {@link RecordingPageProxy}（Layer A 原生操作录制装饰器）。
 * 用 JDK 动态代理自造轻量 fake Page/Locator（记录被调用方法），不依赖 Mockito。
 *
 * <p>覆盖：原生 click/navigate 录制 + 委托；递归包装 locator.click；{@code onXxx} 不包装；
 * {@code Object} 方法直接委托；录制关闭时返回裸 Page（零开销）。
 */
class RecordingPageProxyTest {

    private String prevLogging;

    @BeforeEach
    void setUp() {
        prevLogging = System.getProperty("serenity.logging");
        System.setProperty("serenity.logging", "VERBOSE"); // 开启录制开关使 wrap 生效
    }

    @AfterEach
    void tearDown() {
        if (prevLogging == null) {
            System.clearProperty("serenity.logging");
        } else {
            System.setProperty("serenity.logging", prevLogging);
        }
    }

    /** 轻量 fake：记录被调用方法名，可按方法名返回预置值。 */
    private static final class CallRecorder implements InvocationHandler {
        final List<String> calls = new ArrayList<>();
        final Map<String, Object> returns = new HashMap<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "toString":
                        return "FAKE:" + calls;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return null;
                }
            }
            calls.add(method.getName());
            return returns.get(method.getName());
        }
    }

    private static Page fakePage(CallRecorder rec) {
        return (Page) Proxy.newProxyInstance(RecordingPageProxyTest.class.getClassLoader(),
                new Class<?>[]{Page.class}, rec);
    }

    private static Locator fakeLocator(CallRecorder rec) {
        return (Locator) Proxy.newProxyInstance(RecordingPageProxyTest.class.getClassLoader(),
                new Class<?>[]{Locator.class}, rec);
    }

    @Test
    void wrap_nativeClick_delegatesToRealPage() {
        CallRecorder rec = new CallRecorder();
        Page real = fakePage(rec);
        Page proxy = RecordingPageProxy.wrap(real);

        proxy.click("button#submit");

        assertTrue(rec.calls.contains("click"), "real page click must be invoked");
    }

    @Test
    void wrap_nativeNavigate_delegates() {
        CallRecorder rec = new CallRecorder();
        Page real = fakePage(rec);
        Page proxy = RecordingPageProxy.wrap(real);

        proxy.navigate("https://example.com");

        assertTrue(rec.calls.contains("navigate"), "real page navigate must be invoked");
    }

    @Test
    void wrap_recursiveLocatorWrapping() {
        CallRecorder pageRec = new CallRecorder();
        CallRecorder locRec = new CallRecorder();
        Locator fakeLoc = fakeLocator(locRec);
        pageRec.returns.put("locator", fakeLoc);
        Page real = fakePage(pageRec);
        Page proxy = RecordingPageProxy.wrap(real);

        Locator wrappedLoc = proxy.locator("input#q");
        assertTrue(Proxy.isProxyClass(wrappedLoc.getClass()), "returned locator must be wrapped by recorder");

        wrappedLoc.click();
        assertTrue(locRec.calls.contains("click"), "delegated click to underlying locator");
        assertTrue(pageRec.calls.contains("locator"), "locator() invoked on real page");
    }

    @Test
    void onXxx_listenerRegistrationNotWrapped_returnsReal() {
        CallRecorder rec = new CallRecorder();
        Page real = fakePage(rec);
        rec.returns.put("onResponse", real); // 模拟 Playwright 返回真实 Page
        Page proxy = RecordingPageProxy.wrap(real);

        proxy.onResponse(r -> {});

        assertTrue(rec.calls.contains("onResponse"), "onResponse must be delegated");
    }

    @Test
    void objectMethods_delegateDirectly() {
        CallRecorder rec = new CallRecorder();
        Page real = fakePage(rec);
        Page proxy = RecordingPageProxy.wrap(real);

        String s = proxy.toString();
        assertTrue(s != null && s.startsWith("FAKE:"), "toString must delegate to real object");
    }

    @Test
    void disabledWrap_returnsRealPage_zeroOverhead() {
        System.setProperty("serenity.logging", "QUIET"); // 关闭录制
        CallRecorder rec = new CallRecorder();
        Page real = fakePage(rec);
        Page proxy = RecordingPageProxy.wrap(real);

        assertSame(real, proxy, "disabled wrap must return the real page (zero overhead)");
        proxy.click("x");
        assertTrue(rec.calls.contains("click"), "real page click still invoked when disabled");
    }

    @Test
    void close_onManagedPage_throwsBrowserException_andNotDelegated() {
        CallRecorder rec = new CallRecorder();
        Page real = fakePage(rec);
        Page proxy = RecordingPageProxy.wrap(real);

        BrowserException ex = assertThrows(BrowserException.class, proxy::close,
                "业务经装饰 Page 调用 close() 必须被语义化拒绝");
        assertFalse(rec.calls.contains("close"), "close() 绝不可委托到真实受管 Page");
        assertTrue(ex.getMessage().contains("托管"), "异常信息应说明生命周期由框架托管");
    }

    @Test
    void closeWithOptions_onManagedPage_throwsBrowserException_andNotDelegated() {
        CallRecorder rec = new CallRecorder();
        Page real = fakePage(rec);
        Page proxy = RecordingPageProxy.wrap(real);

        BrowserException ex = assertThrows(BrowserException.class,
                () -> proxy.close(new Page.CloseOptions()),
                "close(CloseOptions) 重载也必须被拒绝");
        assertFalse(rec.calls.contains("close"), "close(CloseOptions) 绝不可委托到真实受管 Page");
    }
}
