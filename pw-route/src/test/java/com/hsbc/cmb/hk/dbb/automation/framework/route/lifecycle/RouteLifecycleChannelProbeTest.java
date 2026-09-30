package com.hsbc.cmb.hk.dbb.automation.framework.route.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.GuardedDriverCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.GuardedDriverCallImpl;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 政策落地验收：V2 必须如实回答既有 SPI 信号 {@code RouteLifecycle.isConnectionUnresponsive(ctx)}，
 * 这样 web 在<b>用例起点</b>的既有恢复入口（{@code PlaywrightSerenityBridge.recoverIfConnectionUnresponsive}）
 * 才会对"驱动信道被污染"真正生效 —— 否则该恢复对 V2 永远不触发（默认实现恒 false）。
 *
 * <p>注意：恢复动作必须是<b>换连接</b>（重建 {@code Playwright} 实例）；只重建 Context/Browser 无效，
 * 因为 Connection 属于 {@code Playwright} 实例。</p>
 */
public class RouteLifecycleChannelProbeTest {

    @After
    public void cleanup() {
        GuardedDriverCallImpl.resetChannel();
        RouteEngine.shutdownAll();
    }

    @Test
    public void healthyChannelIsReportedResponsive() {
        assertFalse("信道健康 ⇒ 不得让 web 误判「连接无响应」而白付一次重建",
                new RouteLifecycleImpl().isConnectionUnresponsive(new Object()));
    }

    @Test
    public void unusableChannelIsReportedUnresponsiveSoWebRebuilds() throws Exception {
        AtomicBoolean stop = new AtomicBoolean(false);

        new GuardedDriverCallImpl().guarded("bind:stuck", 50L, GuardedDriverCall.OnTimeout.WARN_AND_ABANDON, () -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!stop.get() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException ignored) {
                    // 故意吞掉中断：模拟 native 不可中断的在途调用
                }
            }
            return null;
        });

        assertTrue("信道不可用 ⇒ 必须让 web 在用例起点重建，否则卡顿会传染后续用例",
                new RouteLifecycleImpl().isConnectionUnresponsive(new Object()));

        stop.set(true);
        Thread.sleep(300L);
        GuardedDriverCallImpl.resetChannel(); // web 换完 Playwright 实例后的等价效果

        assertFalse("复位后必须回到「可响应」，避免每次用例都重建",
                new RouteLifecycleImpl().isConnectionUnresponsive(new Object()));
    }
}
