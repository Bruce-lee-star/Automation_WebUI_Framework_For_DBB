package com.hsbc.cmb.hk.dbb.automation.framework.route.handler;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteContextState;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约测试：观测兜底读取的「<b>死句柄零触碰</b>」原则。
 *
 * <p><b>为什么需要本测试（真实 Flake 回归守卫）</b>：IT 运行中偶发（约 1/3 次）
 * {@code Object doesn't exist: response@…} / {@code Cannot find parent object request@…}，
 * 表现为随机用例失败（实测 stopModify、E4）。根因是<b>上一个场景已结束、Context 已关闭</b>时，
 * 其<b>在途观测任务</b>仍去读 {@code req.response()}（并对死句柄调 {@code req.url()} 打日志）——
 * 对已释放句柄的操作会让 Playwright 报错，<b>并污染连接</b>，使随后的无关命令（如 {@code page.evaluate}）
 * 也失败，进而把失败抛到别的用例上。
 *
 * <p>修复后契约：观测所属 context 已关闭 ⇒ <b>直接落降级快照，绝不调用任何 request 访问器</b>；
 * 而 context 存活/未知时仍照常尝试读取（守卫必须是<b>条件性</b>的，不得误伤正常采集）。
 *
 * <p>本测试用 JDK 动态代理做 Playwright 桩（route 模块测试无 Mockito），
 * 记录被调用的方法名，从而<b>确定性</b>验证"零触碰"，不依赖偶发复现。
 */
class MonitorHandlerClosedContextNoTouchTest {

    /** 记录被调用方法名的 {@link Request} 桩（动态代理，免手写 30+ 空实现）。 */
    private static Request recordingRequest(List<String> calls) {
        return (Request) Proxy.newProxyInstance(
                MonitorHandlerClosedContextNoTouchTest.class.getClassLoader(),
                new Class<?>[]{Request.class},
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return method.getReturnType().isPrimitive() ? 0 : null;
                });
    }

    private static BrowserContext context() {
        return (BrowserContext) Proxy.newProxyInstance(
                MonitorHandlerClosedContextNoTouchTest.class.getClassLoader(),
                new Class<?>[]{BrowserContext.class},
                (proxy, method, args) -> {
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return method.getReturnType().isPrimitive() ? 0 : null;
                });
    }

    @Test
    @DisplayName("TOUCH-1 context 已关闭 → 立即放弃且零触碰 request 句柄（不得进入重试退避）")
    void closedContextNeverTouchesRequestHandle() {
        BrowserContext closed = context();
        RouteContextState.markContextClosed(closed);
        List<String> calls = new CopyOnWriteArrayList<>();
        Request req = recordingRequest(calls);

        long begin = System.currentTimeMillis();
        Response res = MonitorHandler.fallbackResponseWithRetry(req, closed);
        long elapsed = System.currentTimeMillis() - begin;

        assertNull(res, "已关闭的上下文不得返回响应");
        assertTrue(calls.isEmpty(),
                "零触碰契约：context 已关闭时不得调用 req 的任何访问器（含用于打日志的 url()），实际调用=" + calls);
        assertTrue(elapsed < 200,
                "应立即放弃，而不是进入 FALLBACK 重试退避（实测 " + elapsed + "ms）");
    }

    @Test
    @DisplayName("TOUCH-2 context 存活 → 仍正常尝试读取（守卫是条件性的，未禁用兜底）")
    void openContextStillTriesToRead() {
        List<String> calls = new CopyOnWriteArrayList<>();
        Request req = recordingRequest(calls);

        Response res = MonitorHandler.fallbackResponseWithRetry(req, context());

        assertNull(res, "桩的 req.response() 恒为 null ⇒ 重试后返回 null");
        assertTrue(calls.contains("response"),
                "存活的 context 必须仍然尝试 req.response()（否则正常采集会被误伤），实际调用=" + calls);
    }

    @Test
    @DisplayName("TOUCH-3 上下文未知（null）→ 按『未关闭』处理，仍尝试读取（不误伤旧调用方）")
    void unknownContextStillTriesToRead() {
        List<String> calls = new CopyOnWriteArrayList<>();
        Request req = recordingRequest(calls);

        MonitorHandler.fallbackResponseWithRetry(req, null);

        assertTrue(calls.contains("response"),
                "未知上下文不得被误判为已关闭，实际调用=" + calls);
    }

    @Test
    @DisplayName("TOUCH-4 关闭标记幂等：多次标记与查询结果一致")
    void markClosedIsIdempotent() {
        BrowserContext ctx = context();
        RouteContextState.markContextClosed(ctx);
        RouteContextState.markContextClosed(ctx);

        assertTrue(RouteContextState.isContextClosed(ctx));
        assertTrue(!RouteContextState.isContextClosed(null), "null 恒视为未关闭（未知）");
    }
}
