package com.hsbc.cmb.hk.dbb.automation.framework.route.util;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 契约测试：捕获上下文的<b>两条查询语义分离</b>。
 *
 * <p><b>背景（实测缺陷）</b>：{@link RouteUtil#captureContext(Route)} 在 Page/Context 已销毁时回退到
 * {@code ApiCaptureContext.getCurrent()} —— 该回退为「断言失败可见性」（D7-3）而存在，<b>必须保留</b>；
 * 但若<b>落库</b>也走它，则上一场景已结束、其 Context 已关闭时，仍在途的观测任务会把记录写进
 * <b>当前</b>场景的存储，污染当前场景的计数/存在性断言（如 "不应再采集" 断言反而失败），
 * 并让观测失败被伪装成"已采集"。
 *
 * <p>因此新增 {@link RouteUtil#captureContextForRecord(Route)}（无回退）：owner 已销毁 ⇒ 返回 {@code null}
 * ⇒ 调用方丢弃该记录。本测试固定两者语义，防止将来被"统一简化"回去。
 *
 * <p>实现说明：route 模块测试无 Mockito，故用 JDK 动态代理构造 route→request→frame→page→context 链，
 * 并让"已销毁"场景在 {@code request()} 处抛异常（等价于 Playwright 的句柄失效信号）。
 */
class RouteUtilCaptureContextContractTest {

    /**
     * 构造 Playwright 桩：先兜住 {@code hashCode/equals/toString}（代理会被当作 Map 键使用，
     * 若 hashCode 返回 null 会 NPE）与基本类型返回值，再交给业务 handler。
     */
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                RouteUtilCaptureContextContractTest.class.getClassLoader(),
                new Class<?>[]{type},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "hashCode":
                            return System.identityHashCode(p);
                        case "equals":
                            return p == a[0];
                        case "toString":
                            return type.getSimpleName() + "$Stub";
                        default:
                            break;
                    }
                    Object result = handler.invoke(p, m, a);
                    if (result == null && m.getReturnType().isPrimitive()) {
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) {
                            return false;
                        }
                        if (rt == long.class) {
                            return 0L;
                        }
                        if (rt == double.class) {
                            return 0d;
                        }
                        if (rt == float.class) {
                            return 0f;
                        }
                        return 0;
                    }
                    return result;
                }));
    }

    /** 存活的 route：request().frame().page().context() 链可正常解析。 */
    private static Route aliveRoute(BrowserContext[] holder) {
        BrowserContext ctx = proxy(BrowserContext.class, (p, m, a) -> null);
        holder[0] = ctx;
        Page page = proxy(Page.class, (p, m, a) -> "context".equals(m.getName()) ? ctx : null);
        Frame frame = proxy(Frame.class, (p, m, a) -> "page".equals(m.getName()) ? page : null);
        Request req = proxy(Request.class, (p, m, a) -> "frame".equals(m.getName()) ? frame : null);
        return proxy(Route.class, (p, m, a) -> "request".equals(m.getName()) ? req : null);
    }

    /** 已销毁的 route：访问 request() 即失效（等价 Playwright "Object doesn't exist"/"Target closed"）。 */
    private static Route goneRoute() {
        return proxy(Route.class, (p, m, a) -> {
            throw new IllegalStateException("Target page, context or browser has been closed");
        });
    }

    @Test
    @DisplayName("CTX-1 route 存活 → 记录查询绑定到其所属上下文（与捕获上下文同一实例）")
    void aliveRouteRecordLookupIsContextBound() {
        BrowserContext[] holder = new BrowserContext[1];
        Route route = aliveRoute(holder);

        ApiCaptureContext bound = RouteUtil.captureContextForRecord(route);
        assertNotNull(bound, "存活 route 必须能解析出所属上下文的采集上下文");
        assertSame(ApiCaptureContext.forContext(holder[0]), bound,
                "必须返回该 route 所属 BrowserContext 对应的实例（隔离存储）");
    }

    @Test
    @DisplayName("CTX-2 owner 已销毁 → 记录查询返回 null（丢弃记录，绝不回退到『当前』上下文）")
    void destroyedOwnerRecordLookupReturnsNull() {
        assertNull(RouteUtil.captureContextForRecord(goneRoute()),
                "owner 已销毁时必须放弃落库 —— 否则记录会被写进当前场景，造成跨场景污染");
    }

    @Test
    @DisplayName("CTX-3 owner 已销毁 → 失败上报查询仍保留回退（断言失败可见性 D7-3 不受影响）")
    void destroyedOwnerFailureLookupKeepsFallback() {
        assertNotNull(RouteUtil.captureContext(goneRoute()),
                "可见性契约：失败上报路径必须仍有可用上下文（回退到 current）");
        assertSame(ApiCaptureContext.getCurrent(), RouteUtil.captureContext(goneRoute()),
                "该回退必须指向 current 上下文，供失败详情/标志上报");
    }

    @Test
    @DisplayName("CTX-4 null route 安全：记录查询不抛异常；失败查询保留回退")
    void nullRouteIsSafe() {
        assertNull(RouteUtil.captureContextForRecord(null), "null route 不得落库");
        assertNotNull(RouteUtil.captureContext(null), "null route 的失败查询仍回退 to current");
        assertEquals(ApiCaptureContext.getCurrent(), RouteUtil.captureContext(null));
    }
}
