package com.microsoft.playwright.impl;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * DBBN-PATCH-01 守门测试（2026-09-29）。
 *
 * <p><b>为什么必须存在</b>：本框架依赖的是<b>自建</b>的 Playwright 客户端
 * {@code com.microsoft.playwright:playwright:1.62.0-dbb2}
 * (DBBN-PATCH-01 + DBBN-PATCH-02)（源码在 {@code pw-playwright} 模块）。
 * 一旦有人把它换回官方 jar，缺陷就会**静默复现**（表现为随机的
 * {@code Object doesn't exist: worker@/frame@} → {@code setNetworkInterceptionPatterns} 被带崩）。
 * 因此必须有一条会**响亮失败**的守卫。</p>
 *
 * <p>本测试刻意放在 {@code com.microsoft.playwright.impl} 包内，以便访问包级私有类型
 * （{@link Connection} / {@link Message} / {@link Transport}）直接验证行为，而不是只验证常量。</p>
 *
 * <p>依据文档：{@code docs/patches/playwright-java-1.62.0-dbb-patch-01.md}、
 * {@code docs/patches/playwright-java-1.62.0-dbb-patch-02.md}。</p>

 */
public class DbbPatchGuardTest {

    /** 编译期守卫：官方 jar 没有这个常量 —— 换回去时本类直接编译失败。 */
    @Test
    public void patchedPlaywrightClientIsOnTheClasspath() {
        assertEquals("必须使用自建补丁客户端 playwright:1.62.0-dbb2（DBBN-PATCH-01 + 02）",
                2, Connection.DBBN_PATCH_LEVEL);    }

    /**
     * 行为守卫：一条引用"已被释放对象"的事件，**不得**再炸掉正在泵消息的调用。
     *
     * <p>补丁前：{@code dispatch} 抛出 {@code Cannot find object to call ...}，异常冒出
     * {@code ChannelOwner.runUntil} 的泵循环，把当时在途的协议调用（如
     * {@code setNetworkInterceptionPatterns}）一起中止 —— 这正是 E2E 里 route 注册/注销随机失败的根因。
     * 补丁后：仅记录并丢弃，调用链不受影响。</p>
     */
    @Test
    public void eventForUnknownObjectMustNotAbortThePump() throws Exception {
        Connection connection = new Connection(new NoopTransport(), new HashMap<>());

        Message staleEvent = new Message();
        staleEvent.id = 0;                              // 0 = 事件（非回执）
        staleEvent.method = "console";
        staleEvent.guid = "worker@dbbn-guard-probe";    // 从未注册过 → 模拟已被 __dispose__
        staleEvent.params = new JsonObject();

        long before = Connection.dbbnDroppedMessageCount();
        Method dispatch = Connection.class.getDeclaredMethod("dispatch", Message.class);
        dispatch.setAccessible(true);
        try {
            dispatch.invoke(connection, staleEvent);
        } catch (java.lang.reflect.InvocationTargetException e) {
            fail("悬空事件不得再抛出（补丁前会抛 " + e.getCause() + "），否则会带崩在途的协议调用");
        }
        assertTrue("悬空事件必须被丢弃并计入可观测计数器",
                Connection.dbbnDroppedMessageCount() > before);
    }

    /** 最小传输替身：本测试只驱动 dispatch，不需要真实协议收发。 */
    private static final class NoopTransport implements Transport {
        @Override
        public void send(JsonObject message) {
        }

        @Override
        public JsonObject poll(Duration timeout) {
            return null;
        }

        @Override
        public void close() throws IOException {
        }
    }
}
