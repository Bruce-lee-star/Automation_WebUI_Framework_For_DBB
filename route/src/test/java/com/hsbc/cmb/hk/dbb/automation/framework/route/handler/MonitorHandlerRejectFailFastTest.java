package com.hsbc.cmb.hk.dbb.automation.framework.route.handler;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.MonitorDataLossReporter;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RT-OBS 收口契约：观测执行器队列饱和拒绝观测任务时，{@code MonitorHandler.onObservationRejected}
 * 必须调用 {@link ApiCaptureContext#signalFailFast()} 并登记「队列饱和丢弃」数据损失，
 * 使被拒的「应判红」监控断言仍能正确判红（fail-closed），而非被静默跳过导致 fail-open 假绿。
 *
 * <p>route 模块测试无 Mockito，沿用 {@code MonitorHandlerFailOpenTest} 的 JDK 动态代理桩模式
 * （route 接口 + 条件性方法覆盖），并反射调用 package-private 的 {@code onObservationRejected} 以确定性验证契约。
 */
class MonitorHandlerRejectFailFastTest {

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, Function<String, Object> special) {
        return (T) Proxy.newProxyInstance(
                MonitorHandlerRejectFailFastTest.class.getClassLoader(),
                new Class<?>[]{iface},
                (p, method, args) -> {
                    Object v = special.apply(method.getName());
                    if (v != null) return v;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType().isPrimitive()) return 0;
                    return null;
                });
    }

    @AfterEach
    void cleanup() {
        //  数据损失汇总器为进程级单例，复位避免污染汇总报告 golden 测试
        MonitorDataLossReporter.instance().reset();
    }

    @Test
    @DisplayName("RT-OBS 观测队列饱和拒绝 → onObservationRejected 必须 signalFailFast + 登记数据损失，杜绝 fail-open 假绿")
    void observationRejected_signalsFailFastAndRecordsLoss() throws Exception {
        //  桩链：route → request → frame → page → context（context 强引用持有，避免 WeakHashMap 提前回收）
        BrowserContext ctxProxy = proxy(BrowserContext.class, n -> null);
        Page page = proxy(Page.class, n -> "context".equals(n) ? ctxProxy : null);
        Frame frame = proxy(Frame.class, n -> "page".equals(n) ? page : null);
        Request req = proxy(Request.class, n -> {
            switch (n) {
                case "frame": return frame;
                case "method": return "GET";
                case "url": return "http://example.com/api/test";
                case "headers": return new HashMap<String, String>();
                default: return null;
            }
        });
        Route route = proxy(Route.class, n -> "request".equals(n) ? req : null);
        RouteRule rule = new RouteRule() {
            @Override
            public String getUrlPattern() {
                return "pattern";
            }
        };

        CompletableFuture<Void> ticket = new CompletableFuture<>();
        CompletableFuture<?> bodyFuture = new CompletableFuture<>();

        //  直接驱动拒绝路径补偿逻辑（无需真实灌爆队列，确定性验证契约）
        Method m = MonitorHandler.class.getDeclaredMethod(
                "onObservationRejected", Route.class, RouteRule.class, CompletableFuture.class, CompletableFuture.class);
        m.setAccessible(true);
        m.invoke(null, route, rule, ticket, bodyFuture);

        ApiCaptureContext ctx = ApiCaptureContext.forContext(ctxProxy);
        assertTrue(ctx.hasAssertionFailures(),
                "观测队列饱和拒绝必须置 hasAssertionFailures（signalFailFast），否则被拒断言静默跳过 = fail-open 假绿");
        assertTrue(MonitorDataLossReporter.instance().totalLoss() >= 1,
                "队列饱和丢弃必须登记数据损失，供汇总报告红色提示（与『响应未捕获』区分）");
    }
}
