package com.hsbc.cmb.hk.dbb.automation.framework.web.page.recording;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Phase 1 专属 UT：验证 {@link RecordingPageProxy}（Layer A 原生操作录制装饰器）。
 * 用 JDK 动态代理自造轻量 fake Page/Locator（记录被调用方法），不依赖 Mockito。
 *
 * <p>覆盖：原生 click/navigate 录制 + 委托；递归包装 locator.click；{@code onXxx} 不包装；
 * {@code Object} 方法直接委托；录制关闭时返回裸 Page（零开销）。
 */
public class RecordingPageProxyTest {

    private String prevLogging;

    @Before
    public void setUp() {
        prevLogging = System.getProperty("serenity.logging");
        System.setProperty("serenity.logging", "VERBOSE"); // 开启录制开关使 wrap 生效
    }

    @After
    public void tearDown() {
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
    public void wrap_nativeClick_delegatesToRealPage() {
        CallRecorder rec = new CallRecorder();
        Page real = fakePage(rec);
        Page proxy = RecordingPageProxy.wrap(real);

        proxy.click("button#submit");

        assertTrue("real page click must be invoked", rec.calls.contains("click"));
    }

    @Test
    public void wrap_nativeNavigate_delegates() {
        CallRecorder rec = new CallRecorder();
        Page real = fakePage(rec);
        Page proxy = RecordingPageProxy.wrap(real);

        proxy.navigate("https://example.com");

        assertTrue("real page navigate must be invoked", rec.calls.contains("navigate"));
    }

    @Test
    public void wrap_recursiveLocatorWrapping() {
        CallRecorder pageRec = new CallRecorder();
        CallRecorder locRec = new CallRecorder();
        Locator fakeLoc = fakeLocator(locRec);
        pageRec.returns.put("locator", fakeLoc);
        Page real = fakePage(pageRec);
        Page proxy = RecordingPageProxy.wrap(real);

        Locator wrappedLoc = proxy.locator("input#q");
        assertTrue("returned locator must be wrapped by recorder", Proxy.isProxyClass(wrappedLoc.getClass()));

        wrappedLoc.click();
        assertTrue("delegated click to underlying locator", locRec.calls.contains("click"));
        assertTrue("locator() invoked on real page", pageRec.calls.contains("locator"));
    }

    @Test
    public void onXxx_listenerRegistrationNotWrapped_returnsReal() {
        CallRecorder rec = new CallRecorder();
        Page real = fakePage(rec);
        rec.returns.put("onResponse", real); // 模拟 Playwright 返回真实 Page
        Page proxy = RecordingPageProxy.wrap(real);

        proxy.onResponse(r -> {});

        assertTrue("onResponse must be delegated", rec.calls.contains("onResponse"));
    }

    @Test
    public void objectMethods_delegateDirectly() {
        CallRecorder rec = new CallRecorder();
        Page real = fakePage(rec);
        Page proxy = RecordingPageProxy.wrap(real);

        String s = proxy.toString();
        assertTrue("toString must delegate to real object", s != null && s.startsWith("FAKE:"));
    }

    @Test
    public void disabledWrap_returnsRealPage_zeroOverhead() {
        System.setProperty("serenity.logging", "QUIET"); // 关闭录制
        CallRecorder rec = new CallRecorder();
        Page real = fakePage(rec);
        Page proxy = RecordingPageProxy.wrap(real);

        assertSame("disabled wrap must return the real page (zero overhead)", real, proxy);
        proxy.click("x");
        assertTrue("real page click still invoked when disabled", rec.calls.contains("click"));
    }

    @Test
    public void close_onManagedPage_throwsBrowserException_andNotDelegated() {
        CallRecorder rec = new CallRecorder();
        Page real = fakePage(rec);
        Page proxy = RecordingPageProxy.wrap(real);

        BrowserException ex = assertThrows("业务经装饰 Page 调用 close() 必须被语义化拒绝", BrowserException.class, proxy::close);
        assertFalse("close() 绝不可委托到真实受管 Page", rec.calls.contains("close"));
        assertTrue("异常信息应说明生命周期由框架托管", ex.getMessage().contains("owned by the framework"));
    }

    @Test
    public void closeWithOptions_onManagedPage_throwsBrowserException_andNotDelegated() {
        CallRecorder rec = new CallRecorder();
        Page real = fakePage(rec);
        Page proxy = RecordingPageProxy.wrap(real);

        assertThrows("close(CloseOptions) 重载也必须被拒绝", BrowserException.class, () -> proxy.close(new Page.CloseOptions()));
        assertFalse("close(CloseOptions) 绝不可委托到真实受管 Page", rec.calls.contains("close"));
    }
}
