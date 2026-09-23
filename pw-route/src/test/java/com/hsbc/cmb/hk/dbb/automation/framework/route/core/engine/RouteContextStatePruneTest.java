package com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.ApiMonitorOrchestrator;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CT2-19 契约：以 {@code BrowserContext} 为<b>强引用键</b>的状态表必须有「被动清理之外的主动兜底」。
 *
 * <p>缺陷背景：{@code CONTEXT_RULES_BY_CONTEXT} / {@code DISPATCHED_ROUTES} / {@code STOPPED_CAPS} /
 * {@code CONTEXT_ENGINES} 的清理<b>全部依赖</b> {@code stopContextEngine} 被上层收尾链路被动调用；
 * 一旦 onClose 钩子注册失败（原先只记 DEBUG 且不回滚）或 context 崩溃未经正常关闭流程，
 * 强引用残留 → context 及其全部 Page 无法 GC、跨用例串扰。
 *
 * <p>本测试固化新增的 {@link RouteContextState#pruneClosedContexts()} 语义：
 * 只清扫「已被显式标记关闭」的 context，且绝不误伤仍存活的 context。
 */
class RouteContextStatePruneTest {

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, Function<String, Object> special) {
        return (T) Proxy.newProxyInstance(
                RouteContextStatePruneTest.class.getClassLoader(),
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

    private BrowserContext ctx;

    @AfterEach
    void cleanup() {
        if (ctx != null) {
            RouteContextState.CONTEXT_RULES_BY_CONTEXT.remove(ctx);
            RouteContextState.DISPATCHED_ROUTES.remove(ctx);
            RouteContextState.STOPPED_CAPS.remove(ctx);
            RouteContextState.CONTEXT_ENGINES.remove(ctx);
        }
    }

    @Test
    @DisplayName("CT2-19：pruneClosedContexts 只清扫「已标记关闭」的强键残留，且幂等、不误伤存活 context")
    void pruneClosedContextsRemovesOnlyMarkedContexts() {
        ctx = proxy(BrowserContext.class, n -> null);

        RouteContextState.CONTEXT_RULES_BY_CONTEXT.put(ctx, new ConcurrentHashMap<>());
        RouteContextState.DISPATCHED_ROUTES.put(ctx, ConcurrentHashMap.newKeySet());
        RouteContextState.STOPPED_CAPS.put(ctx, new ConcurrentHashMap<>());

        // ① 未标记关闭 → 不得误伤（否则会破坏仍存活 context 的规则与门控）
        assertEquals(0, RouteContextState.pruneClosedContexts(),
                "未标记关闭的 context 不得被清扫（误伤会造成规则丢失/重复注册）");
        assertTrue(RouteContextState.CONTEXT_RULES_BY_CONTEXT.containsKey(ctx),
                "存活 context 的规则表条目必须保留");

        // ② 显式标记关闭 → 兜底清扫必须移除全部强键残留
        RouteContextState.markContextClosed(ctx);
        assertTrue(RouteContextState.pruneClosedContexts() >= 1,
                "已标记关闭的 context 应被清扫");

        assertFalse(RouteContextState.CONTEXT_RULES_BY_CONTEXT.containsKey(ctx),
                "CONTEXT_RULES_BY_CONTEXT 残留（CT2-19：强引用键泄漏）");
        assertFalse(RouteContextState.DISPATCHED_ROUTES.containsKey(ctx),
                "DISPATCHED_ROUTES 残留（CT2-19）");
        assertFalse(RouteContextState.STOPPED_CAPS.containsKey(ctx),
                "STOPPED_CAPS 残留（CT2-19）");

        // ③ 幂等：再次清扫无残留可清
        assertEquals(0, RouteContextState.pruneClosedContexts(), "pruneClosedContexts 必须幂等");
    }

    @Test
    @DisplayName("CT2-19：Monitor 侧强键清扫在无登记时为 no-op（不误伤）")
    void monitorPruneIsNoOpWhenNothingRegistered() {
        assertEquals(0, ApiMonitorOrchestrator.getInstance().pruneContextsMarkedClosed(),
                "无登记时清扫应为 no-op（幂等且不误伤）");
        assertEquals(0L, ApiMonitorOrchestrator.getInstance().getCloseHookFailures(),
                "未发生钩子注册失败时，失败计数应为 0（该计数即强键残留风险的可观测信号）");
    }
}
