package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import com.hsbc.cmb.hk.dbb.automation.framework.route.binding.GuardedDriverCall.OnTimeout;
import org.junit.Test;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link GuardedDriverCallImpl} 超时文案契约（T5 文案去误导）：FAIL_FAST 超时必须说明
 * "未收到回包（驱动忙 / 客户端共享连接被并发占用）" 并提示 {@code DEBUG=pw:channel} 排查协议层，
 * 不得再用误导性的 "driver unresponsive"。
 */
public class GuardedDriverCallMessageTest {

    @Test
    public void failFastTimeoutMessageIsAccurateChinese() throws Exception {
        GuardedDriverCallImpl call = new GuardedDriverCallImpl();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> call.guarded("bind:x", 50, OnTimeout.FAIL_FAST, () -> {
                    Thread.sleep(1000);
                    return null;
                }));

        String msg = ex.getMessage();
        assertTrue("应说明未收到回包 + 驱动忙/连接被并发占用: " + msg,
                msg.contains("未收到回包") && msg.contains("驱动忙"));
        assertTrue("应提示 DEBUG=pw:channel 排查协议层收发: " + msg, msg.contains("DEBUG=pw:channel"));
        assertTrue("旧误导文案 driver unresponsive 应已替换: " + msg, !msg.contains("driver unresponsive"));
    }
}
