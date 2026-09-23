package com.hsbc.cmb.hk.dbb.automation.framework.route.handler;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.MonitorDataLossReporter;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CT2-04 契约：{@code interceptRealResponse=true} 时 {@code route.fetch()}（≤30s 阻塞）
 * 必须下沉到专用拦截线程，Playwright 事件线程零阻塞；被拒时 fail-closed 而非静默放行。
 *
 * <p>route 模块测试无 Mockito，沿用 {@code MonitorHandlerRejectFailFastTest} 的 JDK 动态代理桩模式。
 */
class MockHandlerInterceptAsyncTest {

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, Function<String, Object> special) {
        return (T) Proxy.newProxyInstance(
                MockHandlerInterceptAsyncTest.class.getClassLoader(),
                new Class<?>[]{iface},
                (p, method, args) -> {
                    Object v = special.apply(method.getName());
                    if (v != null) {
                        return v;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return 0;
                    }
                    return null;
                });
    }

    @AfterEach
    void cleanup() {
        //  数据损失汇总器为进程级单例，复位避免污染汇总报告 golden 测试
        MonitorDataLossReporter.instance().reset();
    }

    /** 构造 route → request → frame → page → context 桩链（context 强引用持有，避免 WeakMap 提前回收）。 */
    private static BrowserContext contextStub() {
        return proxy(BrowserContext.class, n -> null);
    }

    private static Request requestStub(BrowserContext ctx) {
        Page page = proxy(Page.class, n -> "context".equals(n) ? ctx : null);
        Frame frame = proxy(Frame.class, n -> "page".equals(n) ? page : null);
        return proxy(Request.class, n -> {
            switch (n) {
                case "frame":
                    return frame;
                case "method":
                    return "GET";
                case "url":
                    return "http://example.com/api/slow";
                case "headers":
                    return new HashMap<String, String>();
                default:
                    return null;
            }
        });
    }

    @Test
    @Timeout(30)
    @DisplayName("CT2-04：handle() 事件线程零阻塞 —— route.fetch() 在 mock-intercept-* 线程执行")
    void handle_offloadsFetchOffEventThread() throws Exception {
        CountDownLatch fetchEntered = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        CountDownLatch fulfilled = new CountDownLatch(1);
        AtomicReference<String> fetchThread = new AtomicReference<>();
        AtomicBoolean fetchReleased = new AtomicBoolean(false);

        BrowserContext ctx = contextStub();
        Request req = requestStub(ctx);
        APIResponse realResp = proxy(APIResponse.class, n -> {
            switch (n) {
                case "status":
                    return 200;
                case "body":
                    return "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
                case "headers":
                    return new HashMap<String, String>();
                default:
                    return null;
            }
        });
        Route route = (Route) Proxy.newProxyInstance(
                MockHandlerInterceptAsyncTest.class.getClassLoader(),
                new Class<?>[]{Route.class},
                (p, method, args) -> {
                    switch (method.getName()) {
                        case "request":
                            return req;
                        case "fetch":
                            // 模拟慢后端：进入后阻塞，直到测试显式放行
                            fetchThread.set(Thread.currentThread().getName());
                            fetchEntered.countDown();
                            // 必须消费 await 的返回值（SpotBugs RV_RETURN_VALUE_IGNORED / CWE-252），
                            // 并由测试线程断言，避免「等待超时」被静默吞掉。
                            fetchReleased.set(releaseFetch.await(20, TimeUnit.SECONDS));
                            return realResp;
                        case "fulfill":
                            fulfilled.countDown();
                            return null;
                        default:
                            if (method.getReturnType() == boolean.class) {
                                return false;
                            }
                            if (method.getReturnType().isPrimitive()) {
                                return 0;
                            }
                            return null;
                    }
                });

        RouteRule rule = new RouteRule() {
            @Override
            public String getUrlPattern() {
                return "slow-pattern";
            }
        };
        rule.setInterceptRealResponse(true);

        // 事件线程语义：handle() 必须立即返回，fetch 仍在工作线程阻塞中
        MockHandler.handle(route, rule, 0);

        assertTrue(fetchEntered.await(5, TimeUnit.SECONDS),
                "route.fetch() 应在专用工作线程启动（handle() 未同步等待）");
        assertTrue(fetchThread.get() != null && fetchThread.get().startsWith("mock-intercept-"),
                "route.fetch() 必须运行在 mock-intercept-* 线程，实际=" + fetchThread.get());
        assertFalse(fulfilled.await(200, TimeUnit.MILLISECONDS),
                "fetch 未放行前不得 fulfill —— 证明 handle() 未在调用线程内同步完成拦截");

        releaseFetch.countDown();
        assertTrue(fulfilled.await(10, TimeUnit.SECONDS),
                "放行 fetch 后异步任务应完成 fulfill");
        assertTrue(fetchReleased.get(),
                "fetch 阻塞必须被 releaseFetch 正常放行（否则 await 超时会被静默吞掉）");
    }

    @Test
    @DisplayName("CT2-04：拦截任务被拒 → signalFailFast + 登记数据损失 + 放行原请求（fail-closed）")
    void interceptRejected_failsClosedAndResumes() {
        BrowserContext ctx = contextStub();
        Request req = requestStub(ctx);
        AtomicBoolean resumed = new AtomicBoolean(false);
        Route route = (Route) Proxy.newProxyInstance(
                MockHandlerInterceptAsyncTest.class.getClassLoader(),
                new Class<?>[]{Route.class},
                (p, method, args) -> {
                    switch (method.getName()) {
                        case "request":
                            return req;
                        case "resume":
                            resumed.set(true);
                            return null;
                        default:
                            if (method.getReturnType() == boolean.class) {
                                return false;
                            }
                            if (method.getReturnType().isPrimitive()) {
                                return 0;
                            }
                            return null;
                    }
                });

        RouteRule rule = new RouteRule() {
            @Override
            public String getUrlPattern() {
                return "p";
            }
        };

        ApiCaptureContext capture = ApiCaptureContext.forContext(ctx);
        capture.incrementActiveRequests();

        MockHandler.onInterceptRejected(route, rule, "http://example.com/api/x", capture);

        assertTrue(capture.hasAssertionFailures(),
                "被拒拦截必须置 hasAssertionFailures（fail-closed），否则 mock 未生效被静默吞掉 = API 假绿");
        assertTrue(MonitorDataLossReporter.instance().totalLoss() >= 1,
                "队列饱和丢弃必须登记数据损失，供汇总报告红色提示");
        assertTrue(resumed.get(),
                "被拒拦截必须放行原始请求，避免请求永久挂起");
    }
}
