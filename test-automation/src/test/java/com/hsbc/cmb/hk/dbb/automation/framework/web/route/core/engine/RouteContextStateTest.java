package com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine;

import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.lifecycle.PerContextEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine.RouteContextState;

/**
 * 固化 T2-4 收口后的统一活跃状态 {@link RouteContextState}（原散落于 RouteEngine 的静态 Map）。
 *
 * <p>重点锁定 {@link #markDispatched} 的防重门控语义（与原 RouteEngine.dispatchRoute 内联逻辑一致）：
 * null context 返回 null 且不记录；非 null context 返回非空桶且<b>桶内容恒为空占位</b>
 * （仅标记 context 维度分发，不缓存 Route 引用）。
 *
 * <p>四类活跃状态现已全部收口于此（含 {@code CONTEXT_ENGINES}，其值与已提取为同包顶层类的
 * {@code PerContextEngine}）。<b>不依赖 Playwright</b>。
 */
public class RouteContextStateTest {

    @After
    public void tearDown() {
        // RouteContextState 持有 static 状态，测试间隔离
        RouteContextState.CONTEXT_RULES_BY_CONTEXT.clear();
        RouteContextState.DISPATCHED_ROUTES.clear();
        RouteContextState.STOPPED_CAPS.clear();
    }

    @Test
    public void markDispatched_nullContext_returnsNullAndNoRecord() {
        // 提前返回 null，且从不以 null 为键触碰 ConcurrentHashMap（避免 NPE）
        assertNull("null context 不应记录", RouteContextState.markDispatched(null));
    }

    @Test
    public void markDispatched_nonNull_recordsContextWithEmptyBucket() {
        BrowserContext ctx = org.mockito.Mockito.mock(BrowserContext.class);
        Set<?> bucket = RouteContextState.markDispatched(ctx);
        assertNotNull("非 null context 应返回桶", bucket);
        assertTrue("DISPATCHED_ROUTES 应含该 context 键",
                RouteContextState.DISPATCHED_ROUTES.containsKey(ctx));
        assertEquals("桶内容恒为空占位（仅标记 context 维度分发，不缓存 Route 引用）", 0, bucket.size());
    }

    @Test
    public void markDispatched_idempotentSameBucket() {
        BrowserContext ctx = org.mockito.Mockito.mock(BrowserContext.class);
        Set<?> first = RouteContextState.markDispatched(ctx);
        Set<?> second = RouteContextState.markDispatched(ctx);
        assertSame("同一 context 反复 mark 返回同一桶（computeIfAbsent 幂等）", first, second);
        assertEquals("桶内容恒空", 0, first.size());
    }

    @Test
    public void collectedMapsAreLiveConcurrentMaps() {
        // 锁定 4 张收口 Map 已集中且为可写 ConcurrentHashMap（搬运后行为不退化）
        assertNotNull(RouteContextState.CONTEXT_RULES_BY_CONTEXT);
        assertNotNull(RouteContextState.DISPATCHED_ROUTES);
        assertNotNull(RouteContextState.STOPPED_CAPS);
        assertNotNull(RouteContextState.CONTEXT_ENGINES);

        BrowserContext ctx = org.mockito.Mockito.mock(BrowserContext.class);
        RouteContextState.CONTEXT_RULES_BY_CONTEXT.put(ctx, new java.util.concurrent.ConcurrentHashMap<>());
        assertTrue(RouteContextState.CONTEXT_RULES_BY_CONTEXT.containsKey(ctx));

        RouteContextState.STOPPED_CAPS.put(ctx, new java.util.concurrent.ConcurrentHashMap<>());
        assertTrue(RouteContextState.STOPPED_CAPS.containsKey(ctx));

        // CONTEXT_ENGINES 值与顶层 PerContextEngine 绑定（创建后立即 close，避免调度线程泄漏）
        PerContextEngine engine = new PerContextEngine(ctx);
        RouteContextState.CONTEXT_ENGINES.put(ctx, engine);
        assertTrue(RouteContextState.CONTEXT_ENGINES.containsKey(ctx));
        engine.close();
        RouteContextState.CONTEXT_ENGINES.remove(ctx);
    }
}
