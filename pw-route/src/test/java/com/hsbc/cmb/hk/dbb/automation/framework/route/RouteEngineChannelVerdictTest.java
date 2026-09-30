package com.hsbc.cmb.hk.dbb.automation.framework.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.GuardedDriverCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.GuardedDriverCallImpl;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;

/**
 * 用例边界"信道体检"显式入口的验收（2026-09-29 政策落地）。
 *
 * <p>被固化的政策：<b>不是每用例都重建 {@code Playwright} 实例</b>，而是
 * "信道被确证不可继续时才重建"。本测试证明该判定可由代码给出，且换完连接（回调
 * {@link RouteEngine#resetDriverChannel()}）后判定会自动回到可复用。
 */
public class RouteEngineChannelVerdictTest {

    @After
    public void cleanup() {
        GuardedDriverCallImpl.resetChannel();
        RouteEngine.shutdownAll();
    }

    @Test
    public void cleanChannelIsReusableForNextCase() {
        assertEquals("常规情况下必须判定为可复用（不得逼着调用方每用例重建实例）",
                RouteEngine.ChannelVerdict.REUSABLE, RouteEngine.channelVerdictForNewCase());
    }

    @Test
    public void unstoppableInFlightCallForcesSessionRebuild() throws Exception {
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

        assertEquals("在途调用未收尾 ⇒ 必须判定为需要重建会话（唯一能复位信道的手段）",
                RouteEngine.ChannelVerdict.REBUILD_SESSION_REQUIRED, RouteEngine.channelVerdictForNewCase());

        stop.set(true);
        Thread.sleep(300L);
        RouteEngine.resetDriverChannel(); // 会话层换完 Playwright 实例后的回调

        assertEquals("换完连接并回调后，判定必须回到可复用",
                RouteEngine.ChannelVerdict.REUSABLE, RouteEngine.channelVerdictForNewCase());
    }
}
