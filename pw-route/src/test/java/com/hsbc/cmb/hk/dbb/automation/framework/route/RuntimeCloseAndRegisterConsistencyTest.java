package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.RouteBinderRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 + P2 验收：
 * <ul>
 *   <li><b>P1</b>：{@code context.onClose}（运行在 Playwright 消息泵线程）路径只做内存收尾 —— 不发起任何
 *       unroute，因为 context 销毁时驱动侧 route handler 由 Playwright 一并释放；而显式 shutdown
 *       （context 仍存活）仍逐条 unroute（不回归）。</li>
 *   <li><b>P2</b>：注册失败不得在内存规则表留下"无绑定的幽灵规则"；句柄关闭必须同时摘掉内存条目；
 *       且旧句柄<b>不得</b>摘掉同 pattern 的新规则（令牌化）。</li>
 * </ul>
 */
public class RuntimeCloseAndRegisterConsistencyTest {

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
        RouteDsl.on(ctx).api(pattern).mock().status(200).body("{}").register();
    }

    @After
    public void cleanup() {
        RouteBinderRegistry.reset();
        for (BrowserContext ctx : contexts) {
            RouteEngine.shutdown(ctx);
        }
        RouteEngine.shutdownAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void contextClosePathReleasesBindingsWithoutIssuingUnroute() throws Exception {
        BrowserContext ctx = mockContext();
        registerMockRule(ctx, "/api/a");
        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);
        assertEquals("注册一条规则应产生一个驱动句柄", 1, driverHandles.size());

        ArgumentCaptor<Consumer<BrowserContext>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(ctx).onClose(captor.capture());
        captor.getValue().accept(ctx); // 模拟 context 关闭事件（等价于消息泵线程上的回调）

        assertEquals("内存收尾照常：runtime 必须从注册表摘除", 0, RouteEngine.activeRuntimes());
        assertTrue(runtime.isClosed());
        for (AutoCloseable handle : driverHandles) {
            verify(handle, never()).close();
        }
    }

    @Test
    public void explicitShutdownStillUnroutesOwnedBindings() throws Exception {
        BrowserContext ctx = mockContext();
        registerMockRule(ctx, "/api/b");

        RouteEngine.shutdown(ctx);

        assertEquals(0, RouteEngine.activeRuntimes());
        for (AutoCloseable handle : driverHandles) {
            verify(handle, atLeastOnce()).close();
        }
    }

    @Test
    public void bindFailureLeavesNoGhostRuleInMemoryTable() {
        BrowserContext ctx = mockContext();
        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);
        RouteBinderRegistry.setInstance((c, spec, rt) -> {
            throw new IllegalStateException("boom: driver refused to bind");
        });

        try {
            registerMockRule(ctx, "/api/ghost");
            fail("绑定失败必须向上抛出");
        } catch (IllegalStateException expected) {
            // 预期：绑定失败
        }

        assertTrue("绑定失败 ⇒ 内存规则表不得残留无绑定的幽灵规则",
                runtime.ruleSnapshot().rules().isEmpty());
        assertTrue("绑定失败 ⇒ runtime 标记降级（可观测）", runtime.isDegraded());
    }

    @Test
    public void handleCloseRemovesRuleFromMemoryTable() throws Exception {
        BrowserContext ctx = mockContext();
        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);
        AutoCloseable handle = RouteDsl.on(ctx).api("/api/h").mock().status(200).body("{}").register();
        assertEquals(1, runtime.ruleSnapshot().rules().size());

        handle.close();

        assertTrue("句柄关闭必须同时摘掉内存规则表条目", runtime.ruleSnapshot().rules().isEmpty());
    }

    @Test
    public void staleHandleCloseDoesNotRemoveNewerRule() throws Exception {
        BrowserContext ctx = mockContext();
        RouteRuntime runtime = RouteEngine.runtimeOf(ctx);
        AutoCloseable first = RouteDsl.on(ctx).api("/api/t").mock().status(200).body("{\"v\":1}").register();
        AutoCloseable second = RouteDsl.on(ctx).api("/api/t").mock().status(200).body("{\"v\":2}").register();
        assertEquals(1, runtime.ruleSnapshot().rules().size());

        first.close();

        assertFalse("旧句柄（已被同 pattern 的新规则取代）必须成为 no-op",
                runtime.ruleSnapshot().rules().isEmpty());
        assertEquals("旧句柄不得摘掉同 pattern 的新规则（令牌化）",
                1, runtime.ruleSnapshot().rules().size());

        second.close();
        assertTrue(runtime.ruleSnapshot().rules().isEmpty());
    }
}
