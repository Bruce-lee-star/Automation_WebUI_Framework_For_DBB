package com.hsbc.cmb.hk.dbb.automation.framework.route.v2;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteDsl2;
import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V2-2 验收：feature 模式「只清规则、保留 context」入口（{@code RouteEngine2.clearRules} / {@code RouteDsl2.clearRules}）。
 *
 * <p>四条断言（缺一即不成立）：
 * <ol>
 *   <li><b>规则确被退役</b>：每条规则持有的驱动句柄都收到关闭调用（走独立撤销执行器，故异步等待）；</li>
 *   <li><b>runtime 未被关闭</b>且是<b>同一实例</b>（未重建 ⇒ 不丢线程池、不触发重新登录）；</li>
 *   <li><b>Context 未被触碰</b>（{@code ctx.close()} 从未被调用）；</li>
 *   <li><b>清规则后同一 runtime 可继续注册</b>（幂等且可复用）。</li>
 * </ol>
 */
public class RouteClearRulesTest {

    private final List<BrowserContext> contexts = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> driverHandles = new CopyOnWriteArrayList<>();

    private BrowserContext mockContext() {
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.route(anyString(), any(), any())).thenAnswer(inv -> {
            AutoCloseable handle = mock(AutoCloseable.class);
            driverHandles.add(handle);
            return handle;
        });
        doAnswer(inv -> null).when(ctx).onClose(any());
        contexts.add(ctx);
        return ctx;
    }

    private static void registerMockRule(BrowserContext ctx, String pattern) {
        RouteDsl2.on(ctx).api(pattern).mock().status(200).body("{}").register();
    }

    @After
    public void cleanup() {
        for (BrowserContext ctx : contexts) {
            RouteEngine2.shutdown(ctx);
        }
        RouteEngine2.shutdownAll();
    }

    @Test
    public void clearRulesRetiresEveryRuleButKeepsRuntimeAndContext() throws Exception {
        BrowserContext ctx = mockContext();
        registerMockRule(ctx, "/api/a");
        registerMockRule(ctx, "/api/b");
        RouteRuntime runtime = RouteEngine2.runtimeOf(ctx);
        assertEquals("两条规则应产生两个驱动句柄", 2, driverHandles.size());

        int cleared = RouteEngine2.clearRules(ctx);

        assertEquals("必须提交全部规则的退役", 2, cleared);
        assertEquals("runtime 必须保留（未被 shutdown）", 1, RouteEngine2.activeRuntimes());
        assertSame("必须是同一 runtime 实例（未重建）", runtime, RouteEngine2.runtimeOf(ctx));
        assertFalse("runtime 不得被关闭", runtime.isClosed());
        verify(ctx, never()).close();
        for (AutoCloseable handle : driverHandles) {
            verify(handle, timeout(5_000L).atLeastOnce()).close();
        }
    }

    @Test
    public void clearRulesIsIdempotentAndKeepsRuntimeUsable() {
        BrowserContext ctx = mockContext();
        registerMockRule(ctx, "/api/a");

        assertEquals(1, RouteEngine2.clearRules(ctx));
        assertEquals("幂等：规则表已空", 0, RouteEngine2.clearRules(ctx));

        // 未被关闭 ⇒ 同 runtime 可继续注册（否则会抛 IllegalStateException）
        registerMockRule(ctx, "/api/c");
        assertEquals("清规则后仍可继续注册并被再次清理", 1, RouteEngine2.clearRules(ctx));
        assertFalse(RouteEngine2.runtimeOf(ctx).isClosed());
    }

    @Test
    public void clearRulesOnContextWithoutRuntimeIsZeroAndDoesNotCreateOne() {
        BrowserContext fresh = mockContext();
        int before = RouteEngine2.activeRuntimes();

        assertEquals("无 runtime ⇒ 0", 0, RouteEngine2.clearRules(fresh));
        assertEquals("不得为清规则而创建 runtime", before, RouteEngine2.activeRuntimes());
    }

    @Test
    public void clearRulesAllKeepsEveryRuntime() {
        BrowserContext a = mockContext();
        registerMockRule(a, "/api/a");
        BrowserContext b = mockContext();
        registerMockRule(b, "/api/b");
        assertEquals(2, RouteEngine2.activeRuntimes());

        assertEquals(2, RouteDsl2.clearRulesAll());

        assertEquals("全部 runtime 都必须保留", 2, RouteEngine2.activeRuntimes());
        assertFalse(RouteEngine2.runtimeOf(a).isClosed());
        assertFalse(RouteEngine2.runtimeOf(b).isClosed());
    }

    @Test
    public void dslClearRulesExposesEngineEntryAndRejectsForeignContextType() {
        BrowserContext ctx = mockContext();
        registerMockRule(ctx, "/api/a");

        assertEquals(1, RouteDsl2.clearRules(ctx));

        try {
            RouteDsl2.clearRules(new Object());
            fail("非 Page / BrowserContext 必须 fail-fast");
        } catch (IllegalArgumentException expected) {
            // 预期：fail-fast，避免静默误用
        }
    }
}
