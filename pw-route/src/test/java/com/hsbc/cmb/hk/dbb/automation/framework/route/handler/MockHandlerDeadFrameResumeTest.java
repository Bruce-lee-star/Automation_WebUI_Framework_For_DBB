package com.hsbc.cmb.hk.dbb.automation.framework.route.handler;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.MonitorDataLossReporter;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 竞态回归：{@code interceptRealResponse=true} 时，页面在「异步透传窗口」内跳转导致
 * {@code route.fetch()} 抛 {@code Object doesn't exist: frame}（死帧竞态），必须：
 * <ul>
 *   <li>无静态 mock 体 → {@code route.resume()} 放行原始请求（而非 {@code route.abort()} 造成
 *       {@code net::ERR_FAILED} 破坏页面）；</li>
 *   <li>有静态 mock 体 → 优先 {@code route.fulfill()} 静态体兜底（仍不 abort）。</li>
 * </ul>
 * 对应设计文档 {@code 24_整改专项设计_MockHandler拦截真实响应收敛.md} §3 死帧兜底分支。
 *
 * <p>桩模式沿用 {@code MockHandlerInterceptAsyncTest} 的 JDK 动态代理（route 模块无 Mockito）。</p>
 */
class MockHandlerDeadFrameResumeTest {

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, Function<String, Object> special) {
        return (T) Proxy.newProxyInstance(
                MockHandlerDeadFrameResumeTest.class.getClassLoader(),
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
        // 数据损失汇总器为进程级单例，复位避免污染汇总报告 golden 测试
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
                    return "http://example.com/api/profile";
                case "headers":
                    return new HashMap<String, String>();
                default:
                    return null;
            }
        });
    }

    private static Route routeStub(Request req, AtomicBoolean resumed, AtomicBoolean fulfilled) {
        return (Route) Proxy.newProxyInstance(
                MockHandlerDeadFrameResumeTest.class.getClassLoader(),
                new Class<?>[]{Route.class},
                (p, method, args) -> {
                    switch (method.getName()) {
                        case "request":
                            return req;
                        case "fetch":
                            // 模拟异步窗口内页面跳转 → frame 销毁
                            throw new PlaywrightException("Object doesn't exist: frame");
                        case "resume":
                            resumed.set(true);
                            return null;
                        case "fulfill":
                            fulfilled.set(true);
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
    }

    private static RouteRule ruleWithInterceptRealResponse() {
        RouteRule rule = new RouteRule() {
            @Override
            public String getUrlPattern() {
                return "profile";
            }
        };
        rule.setInterceptRealResponse(true);
        return rule;
    }

    /** 等待异步拦截任务在 mock-intercept-* 线程完成。 */
    private static void awaitSettled(AtomicBoolean resumed, AtomicBoolean fulfilled) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!resumed.get() && !fulfilled.get() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("死帧竞态：route.fetch() 抛 Object doesn't exist → resume 放行原始请求（不 abort）")
    void deadFrame_fetchThrows_resumesOriginalRequest() throws Exception {
        BrowserContext ctx = contextStub();
        Request req = requestStub(ctx);
        AtomicBoolean resumed = new AtomicBoolean(false);
        AtomicBoolean fulfilled = new AtomicBoolean(false);
        Route route = routeStub(req, resumed, fulfilled);

        RouteRule rule = ruleWithInterceptRealResponse();

        // handle() 异步提交到 mock-intercept-* 线程，事件线程立即返回
        MockHandler.handle(route, rule, 0);
        awaitSettled(resumed, fulfilled);

        assertTrue(resumed.get(),
                "死帧竞态下必须 resume 放行原始请求（避免 ERR_FAILED 破坏页面），实际 fulfilled=" + fulfilled.get());
        assertTrue(!fulfilled.get(),
                "死帧竞态下不得 fulfill（页面已跳转，注入无意义且会失败），实际 fulfilled=" + fulfilled.get());
    }

    @Test
    @Timeout(30)
    @DisplayName("死帧竞态 + 规则配静态 mock 体 → 优先 fulfill 静态体兜底（仍不 abort）")
    void deadFrame_withStaticMockBody_fulfillsStatic() throws Exception {
        BrowserContext ctx = contextStub();
        Request req = requestStub(ctx);
        AtomicBoolean resumed = new AtomicBoolean(false);
        AtomicBoolean fulfilled = new AtomicBoolean(false);
        Route route = routeStub(req, resumed, fulfilled);

        RouteRule rule = ruleWithInterceptRealResponse();
        rule.setMockBody("{\"static\":true}"); // 静态兜底体

        MockHandler.handle(route, rule, 0);
        awaitSettled(resumed, fulfilled);

        assertTrue(fulfilled.get(),
                "死帧 + 静态体应优先 fulfill 静态体兜底，实际 resumed=" + resumed.get());
    }
}
