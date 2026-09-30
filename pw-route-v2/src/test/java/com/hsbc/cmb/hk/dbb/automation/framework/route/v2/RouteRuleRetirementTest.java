package com.hsbc.cmb.hk.dbb.automation.framework.route.v2;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding.CapturingGuardedDriverCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding.GuardedDriverCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding.GuardedDriverCallRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding.StubGuardedDriverCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteCapability;
import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规则"按目的生灭"契约（2026-09-28/29，T2+）。
 *
 * <p><b>模型</b>：规则的生命周期跟随<b>目的</b>，不再跟随<b>用例</b> —— 达成目的即撤销该规则
 * （无论断言成功或失败）；<b>没匹配到</b>也不会永久悬挂：监控窗口到期即"清理结束"。</p>
 *
 * <p><b>撤销必须可确证，但绝不重建 Context</b>（2026-09-29 裁定）：未确证 ⇒ 先<b>重发一次全量快照
 * 重同步自愈</b>（{@link com.hsbc.cmb.hk.dbb.automation.framework.route.v2.binding.PatternBinder#retryUnroute()}）；
 * 仍不可确证 ⇒ 响亮 ERROR + 计数（**仅可观测**）——绝不丢弃/重建 Context，因为 feature 模式下
 * 同一 sessionKey 不重建（不变式 I-1）。</p>
 */
public class RouteRuleRetirementTest {

    private BrowserContext ctx;
    private AutoCloseable nativeHandle;

    private BrowserContext mockContext() {
        ctx = mock(BrowserContext.class);
        nativeHandle = mock(AutoCloseable.class);
        when(ctx.route(anyString(), any(), any())).thenReturn(nativeHandle);
        Mockito.doAnswer(inv -> null).when(ctx).onClose(any());
        return ctx;
    }

    @After
    public void cleanup() {
        GuardedDriverCallRegistry.reset();
        if (ctx != null) {
            RouteEngine2.shutdown(ctx);
        }
    }

    /** 场景 1：目的达成即撤销，并确证（nativeHandle.close 被调用），状态 clean。 */
    @Test
    public void purposeMetRetiresRuleAndConfirmsUnroute() throws Exception {
        GuardedDriverCallRegistry.setInstance(new StubGuardedDriverCall()); // 执行 action（等价真实成功的协议调用）
        RouteRuntime runtime = RouteEngine2.runtimeOf(mockContext());
        runtime.register(ApiSpec.builder("leftmenu/permissionLeftMenuConfig", RouteCapability.MONITOR)
                .expectStatus(200).build());

        boolean submitted = runtime.retireByPurpose("leftmenu/permissionLeftMenuConfig");

        assertTrue("撤销必须被提交（IO 池可用）", submitted);
        verify(nativeHandle, Mockito.timeout(3000)).close();
        assertTrue("撤销已确证 ⇒ 状态必须判为干净", awaitClean(runtime));
        assertEquals("不应有未确证的撤销", 0, runtime.unconfirmedRetirements());
    }

    /** 场景 2：幂等 —— 重复撤销不重复下发，也不产生"未确证"。 */
    @Test
    public void retirementIsIdempotent() throws Exception {
        GuardedDriverCallRegistry.setInstance(new StubGuardedDriverCall());
        RouteRuntime runtime = RouteEngine2.runtimeOf(mockContext());
        runtime.register(ApiSpec.builder("/api/once", RouteCapability.MONITOR).expectStatus(200).build());

        assertTrue(runtime.retireByPurpose("/api/once"));
        assertTrue("重复撤销必须幂等返回 true（无待撤状态）", runtime.retireByPurpose("/api/once"));

        verify(nativeHandle, Mockito.timeout(3000)).close();
        assertTrue(awaitClean(runtime));
    }

    /** 场景 3：自愈也失败 ⇒ 计数并告警（**仅可观测**），但**绝不重建 Context**。 */
    @Test
    public void unconfirmedUnrouteIsReportedButNeverRebuildsContext() {
        // Capturing 替身：不执行 action 且返回 null —— 等价"首次与重发都无回包"
        GuardedDriverCallRegistry.setInstance(new CapturingGuardedDriverCall());
        RouteRuntime runtime = RouteEngine2.runtimeOf(mockContext());
        runtime.register(ApiSpec.builder("notifications/streams", RouteCapability.MONITOR).build());

        runtime.retireByPurpose("notifications/streams");

        assertTrue("必须等到异步撤销（含自愈重发）被处理", awaitUnconfirmed(runtime));
        assertFalse("仍不可确证 ⇒ isClean 必须为 false", runtime.isClean());
    }

    /** 场景 4：收尾兜底 flush —— 未按目的撤销的剩余规则在 runtime 关闭时统一撤销并汇总。 */
    @Test
    public void teardownFlushUnroutesRemainingRules() throws Exception {
        GuardedDriverCallRegistry.setInstance(new StubGuardedDriverCall());
        RouteRuntime runtime = RouteEngine2.runtimeOf(mockContext());
        runtime.register(ApiSpec.builder("/api/a", RouteCapability.MONITOR).expectStatus(200).build());
        runtime.register(ApiSpec.builder("/api/b", RouteCapability.MOCK).build());
        AutoCloseable handle = nativeHandle;

        RouteEngine2.shutdown(ctx); // 收尾兜底 flush

        verify(handle, Mockito.timeout(3000).atLeastOnce()).close();
        assertFalse("收尾 flush 全部确证后不得降级", runtime.isDegraded());
        assertTrue("收尾 flush 全部确证后应判为干净", runtime.isClean());
    }

    /** 场景 5（2026-09-29 裁定）：首次注销无回包 ⇒ **重发全量快照重同步自愈**，判为干净且不降级。 */
    @Test
    public void unconfirmedUnrouteIsHealedByResyncWithoutRebuild() throws Exception {
        final AtomicInteger unrouteAttempts = new AtomicInteger();
        GuardedDriverCallRegistry.setInstance(new GuardedDriverCall() {
            @Override
            public <T> T guarded(String opName, long boundMs, GuardedDriverCall.OnTimeout policy,
                                 Callable<T> action) {
                if (opName.startsWith("unroute:") && unrouteAttempts.incrementAndGet() == 1) {
                    return null; // 首次注销"界内无回包"（模拟抖动）；重发（unroute-retry）正常回包
                }
                try {
                    return action.call();
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        RouteRuntime runtime = RouteEngine2.runtimeOf(mockContext());
        runtime.register(ApiSpec.builder("/api/heal", RouteCapability.MONITOR).expectStatus(200).build());

        runtime.retireByPurpose("/api/heal");

        assertTrue("重发重同步应自愈并确证", awaitClean(runtime));
        assertFalse("自愈成功不得标记降级（更不得重建 Context）", runtime.isDegraded());
        assertEquals("自愈成功后不得留下未确证记录", 0, runtime.unconfirmedRetirements());
    }

    /** 场景 6（2026-09-29 裁定）：监控窗口到期**仍未匹配** ⇒ 规则也"清理结束"（不留悬挂规则）。 */
    @Test
    public void ruleIsRetiredWhenMonitorWindowExpiresWithoutAnyMatch() throws Exception {
        GuardedDriverCallRegistry.setInstance(new StubGuardedDriverCall());
        RouteRuntime runtime = RouteEngine2.runtimeOf(mockContext());
        runtime.register(ApiSpec.builder("/api/never-matches", RouteCapability.MONITOR)
                .expectStatus(200).monitorTimeoutMs(300).build());

        verify(nativeHandle, Mockito.timeout(5000)).close(); // 窗口到期必须撤销（没匹配到也清理）
        assertTrue(awaitClean(runtime));
    }

    /**
     * 竞态守护（2026-09-29，R-1）：同 pattern <b>重新注册</b>后，旧规则留下的"窗口到期任务"
     * <b>绝不得撤掉新规则</b> —— 旧触发者因令牌不一致直接作废，新规则保有自己的生命周期终点。
     */
    @Test
    public void staleDeadlineMustNotRetireReRegisteredRule() throws Exception {
        GuardedDriverCallRegistry.setInstance(new StubGuardedDriverCall());
        RouteRuntime runtime = RouteEngine2.runtimeOf(mockContext());

        // 规则 A：带 300ms 监控窗口 ⇒ 会安排一次"到期撤销"
        runtime.register(ApiSpec.builder("/api/reused", RouteCapability.MONITOR)
                .expectStatus(200).monitorTimeoutMs(300).build());
        // 规则 B：同 pattern 重新注册（新实例）且不设窗口 ⇒ 接管该 pattern 的生命周期
        runtime.register(ApiSpec.builder("/api/reused", RouteCapability.MONITOR)
                .expectStatus(200).monitorTimeoutMs(0).build());

        // 越过 A 的窗口：A 的到期任务必须被令牌检查拒绝（不得撤掉 B 的绑定）
        TimeUnit.MILLISECONDS.sleep(700);

        Mockito.verify(nativeHandle, Mockito.never()).close();
    }

    /**
     * 竞态守护（2026-09-29，R-4）：<b>IO 池被占满时撤销仍必须完成</b> —— 撤销走独立执行器，
     * 绝不被 body 断言/IO 任务挤压（否则会出现"框架自身导致的观测缺失"）。
     */
    @Test
    public void retirementStillRunsWhenIoPoolIsSaturated() throws Exception {
        GuardedDriverCallRegistry.setInstance(new StubGuardedDriverCall());
        // 极紧配置：IO 单线程 + 队列容量 1 ⇒ 两个阻塞任务即可占满 IO 池
        RouteV2Config tight = new RouteV2Config(1, 1, 1, 1, 1L, 1000L, 10, 2000L);
        RouteRuntime runtime = RouteEngine2.runtimeOf(mockContext(), tight);
        runtime.register(ApiSpec.builder("/api/saturated", RouteCapability.MONITOR).expectStatus(200).build());

        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        assertTrue(runtime.io().trySubmit("block-1", () -> awaitQuietly(release)));
        assertTrue(runtime.io().trySubmit("block-2", () -> awaitQuietly(release)));

        try {
            assertTrue("撤销必须仍被提交（走独立执行器，不依赖 IO 池）",
                    ((RouteRuntimeImpl) runtime).retireByPurpose("/api/saturated"));
            verify(nativeHandle, Mockito.timeout(3000)).close();
            assertTrue("撤销完成后状态必须判为干净", awaitClean(runtime));
        } finally {
            release.countDown();
        }
    }

    private static void awaitQuietly(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean awaitClean(RouteRuntime runtime) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            if (runtime.isClean()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return runtime.isClean();
    }

    private static boolean awaitUnconfirmed(RouteRuntime runtime) {
        try {
            for (int i = 0; i < 150; i++) {
                if (runtime.unconfirmedRetirements() > 0) {
                    return true;
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return runtime.unconfirmedRetirements() > 0;
    }
}
