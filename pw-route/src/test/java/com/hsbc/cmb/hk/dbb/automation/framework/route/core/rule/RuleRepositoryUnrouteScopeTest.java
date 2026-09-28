package com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteContextState;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteEngine;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跨用例收尾竞态回归守卫（2026-09-26，A3 + A4）。
 *
 * <p><b>被守卫的真实缺陷</b>：feature 模式下 BrowserContext 跨 scenario 复用，上一用例的
 * teardown worker 可能在下一个用例<b>已注册完路由之后</b>才执行（实测重叠 5.5s）。旧实现会在 worker 里
 * 调用 {@code context.unrouteAll()}，把新用例刚注册的全部原生路由摘掉，表现为
 * {@code Object doesn't exist: response@/request@}、{@code Execution context was destroyed}
 * 以及后续用例卡死。
 *
 * <p><b>本用例如何做到确定性</b>：worker 在进入句柄 close 循环之前会先做
 * {@code RouteEngine.awaitInterceptCompletion(...)}（在途请求排空）。故测试先在
 * {@link ApiCaptureContext} 上 {@code incrementActiveRequests()} 制造"在途请求"作为<b>闸门</b>，
 * 使 worker 必然停在循环之前；此时再模拟"新用例接管"，最后 {@code decrementActiveRequests()} 放行。
 * 该时序与线上真实发生时序（先排空、后循环检查）完全一致，无任何 sleep 竞态。
 */
class RuleRepositoryUnrouteScopeTest {

    @Test
    @DisplayName("A3+A4：teardown worker 仅 close 未被接管者，且绝不调用 context 级 unrouteAll")
    void teardownWorkerSkipsPatternsReownedByNewerScenario() throws Exception {
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.isClosed()).thenReturn(false);

        AtomicBoolean staleHandleClosed = new AtomicBoolean(false);    // P1：未被接管 → 应被 close
        AtomicBoolean reownedHandleClosed = new AtomicBoolean(false);  // P2：被接管 → 绝不能被 close

        AutoCloseable p1Handle = () -> staleHandleClosed.set(true);
        AutoCloseable p2Handle = () -> reownedHandleClosed.set(true);

        //  registerInternal 按规则顺序调用 context.route(...)：第 1 条 → P1 句柄，第 2 条 → P2 句柄
        when(ctx.route(anyString(), any())).thenReturn(p1Handle, p2Handle);

        RouteRule p1Rule = new RouteRule();
        p1Rule.setUrlPattern("/api/p1/**");
        p1Rule.setType(RouteHandleType.MOCK);
        RouteRule p2Rule = new RouteRule();
        p2Rule.setUrlPattern("/api/p2/**");
        p2Rule.setType(RouteHandleType.MOCK);
        RuleRepository.register(ctx, List.of(p1Rule, p2Rule));

        String np1 = RouteEngine.normalizePattern("/api/p1/**");
        String np2 = RouteEngine.normalizePattern("/api/p2/**");
        assertTrue(RouteContextState.hasRouteHandle(ctx, np1), "前置：P1 句柄应已记账");
        assertTrue(RouteContextState.hasRouteHandle(ctx, np2), "前置：P2 句柄应已记账");

        //  —— 闸门：制造"在途请求"，使 teardown worker 必然停在 close 循环之前 ——
        ApiCaptureContext capture = ApiCaptureContext.forContext(ctx);
        capture.incrementActiveRequests();

        // 用例1 收尾：起 teardown worker（主线程同步摘表后立即返回）
        RuleRepository.clearContext(ctx);

        assertFalse(RouteContextState.hasRouteHandle(ctx, np1), "主线程应已同步摘除句柄记账");

        //  —— 模拟"下一个用例接管"：在同一 context 上重新注册 P2 ——
        AtomicBoolean newP2HandleClosed = new AtomicBoolean(false);
        AutoCloseable newP2Handle = () -> newP2HandleClosed.set(true);
        RouteContextState.registerRouteHandle(ctx, np2, newP2Handle);

        // 放行闸门：worker 继续 → 循环内逐句柄实时复查
        capture.decrementActiveRequests();

        // 有界等待 worker 收尾
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!staleHandleClosed.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        Thread.sleep(200L); // 让"跳过 P2"的分支也执行完（仅用于让 never() 判定稳定）

        assertTrue(staleHandleClosed.get(),
                "未被接管的 pattern（P1）仍应被确定性 close —— 收尾能力不得因本修复而丢失");
        assertFalse(reownedHandleClosed.get(),
                "已被新用例重新注册的 pattern（P2）绝不能被上一用例的 teardown close，否则会摘掉新路由");
        assertFalse(newP2HandleClosed.get(), "新用例注册的句柄不得被上一用例的 teardown close");
        assertSame(newP2Handle, RouteContextState.ROUTE_HANDLES.get(ctx).get(np2),
                "新用例注册的句柄必须原样保留在记账表中");
        //  A3：worker 不得再调用 context 级 unrouteAll（那会一次性摘掉 context 上全部路由）
        verify(ctx, never()).unrouteAll();
    }
}
