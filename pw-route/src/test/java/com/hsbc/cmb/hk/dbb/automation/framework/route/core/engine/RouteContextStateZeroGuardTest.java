package com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle.PerContextEngine;
import com.microsoft.playwright.BrowserContext;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.mockito.Mockito;

/**
 * C-11 强键注册表归零守卫：验证 {@link RouteContextState#removeContextFromAllRegistries(BrowserContext)}
 * 能将某 context 从所有以 {@code BrowserContext} 为强引用键的状态表中移除并取消其全部在途任务，
 * 且不影响其它 context（无越界清零）；同时验证 {@link RouteEngine#cleanupClosedContext(BrowserContext)}
 * 经该守卫在上下文收口末段统一兜底清零。
 */
class RouteContextStateZeroGuardTest {

    @Test
    void removeContextFromAllRegistries_zeroesAllStrongKeyMapsForTargetOnly() {
        BrowserContext target = Mockito.mock(BrowserContext.class);
        BrowserContext other = Mockito.mock(BrowserContext.class);
        PerContextEngine targetEngine = new PerContextEngine(target);
        PerContextEngine otherEngine = new PerContextEngine(other);
        try {
            RouteContextState.CONTEXT_RULES_BY_CONTEXT.put(target, Collections.emptyMap());
            RouteContextState.CONTEXT_RULES_BY_CONTEXT.put(other, Collections.emptyMap());
            RouteContextState.DISPATCHED_ROUTES.put(target, Collections.newSetFromMap(new WeakHashMap<>()));
            RouteContextState.DISPATCHED_ROUTES.put(other, Collections.newSetFromMap(new WeakHashMap<>()));
            RouteContextState.STOPPED_CAPS.put(target, Collections.emptyMap());
            RouteContextState.STOPPED_CAPS.put(other, Collections.emptyMap());
            RouteContextState.CONTEXT_ENGINES.put(target, targetEngine);
            RouteContextState.CONTEXT_ENGINES.put(other, otherEngine);

            CompletableFuture<?> targetTask = new CompletableFuture<>();
            CompletableFuture<?> otherTask = new CompletableFuture<>();
            RouteContextState.registerPendingTask(target, targetTask);
            RouteContextState.registerPendingTask(other, otherTask);

            // 执行守卫
            RouteContextState.removeContextFromAllRegistries(target);

            // target 在全部强键表中归零
            assertFalse(RouteContextState.CONTEXT_RULES_BY_CONTEXT.containsKey(target));
            assertFalse(RouteContextState.DISPATCHED_ROUTES.containsKey(target));
            assertFalse(RouteContextState.STOPPED_CAPS.containsKey(target));
            assertFalse(RouteContextState.CONTEXT_ENGINES.containsKey(target));
            assertTrue(targetTask.isCancelled());
            assertEquals(0, RouteContextState.cancelPendingTasksFor(target));

            // other 不受影响（无越界清零）
            assertTrue(RouteContextState.CONTEXT_RULES_BY_CONTEXT.containsKey(other));
            assertTrue(RouteContextState.DISPATCHED_ROUTES.containsKey(other));
            assertTrue(RouteContextState.STOPPED_CAPS.containsKey(other));
            assertTrue(RouteContextState.CONTEXT_ENGINES.containsKey(other));
            assertEquals(1, RouteContextState.cancelPendingTasksFor(other));
        } finally {
            RouteContextState.removeContextFromAllRegistries(other);
            targetEngine.close();
            otherEngine.close();
        }
    }

    @Test
    void cleanupClosedContext_zeroesStrongKeyMapsViaGuard() {
        BrowserContext ctx = Mockito.mock(BrowserContext.class);
        PerContextEngine engine = new PerContextEngine(ctx);
        try {
            RouteContextState.CONTEXT_ENGINES.put(ctx, engine);
            RouteContextState.DISPATCHED_ROUTES.put(ctx, Collections.newSetFromMap(new WeakHashMap<>()));
            CompletableFuture<?> task = new CompletableFuture<>();
            RouteContextState.registerPendingTask(ctx, task);

            RouteEngine.cleanupClosedContext(ctx);

            assertFalse(RouteContextState.CONTEXT_ENGINES.containsKey(ctx));
            assertFalse(RouteContextState.DISPATCHED_ROUTES.containsKey(ctx));
            assertTrue(task.isCancelled());
        } finally {
            RouteContextState.removeContextFromAllRegistries(ctx);
            engine.close();
        }
    }
}
