package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * CT2-14 契约：单飞守卫的「夺取」语义与可观测计数。
 *
 * <p>缺陷背景：follower 等待超时后<b>立刻</b>摘除守卫并自任 leader，而原 leader 未被取消 ——
 * 若 leader 只是慢，则出现「两个线程各自真实登录」→ 服务端单会话策略互踢（随机 401）。
 *
 * <p>本测试固化三点（不启动浏览器、不需要真实 session 文件）：
 * <ol>
 *   <li>首个申请者成为 leader，正常完成后<b>不得</b>计入夺取（计数语义正确、无误报）；</li>
 *   <li>守卫在完成后被清理，同一 key 可再次成为 leader（不残留死守卫阻塞后续线程）；</li>
 *   <li>夺取计数对外可观测（{@code getSingleFlightTakeoverCount}）——「窗口曾打开」可被判定。</li>
 * </ol>
 *
 * <p>超时夺取路径本身需 {@code 单飞超时 + 宽限期}（默认 60s + 5s）才能触发，故不在单测内等待；
 * 其正确性依赖上述计数与 JVM 内单飞守卫的串行化（CT2-16 已移除跨进程锁 CrossJvmLoginLock，跨 JVM 单飞不再保证）。
 */
public class SessionManagerSingleFlightTest {

    private static final Method ACQUIRE;
    private static final Method COMPLETE;

    static {
        try {
            ACQUIRE = SessionManager.class.getDeclaredMethod("acquireOrAwait", String.class);
            ACQUIRE.setAccessible(true);
            COMPLETE = SessionManager.class.getDeclaredMethod("completeLoginGuard", String.class, boolean.class);
            COMPLETE.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Object acquire(String key) throws Exception {
        return ACQUIRE.invoke(null, key);
    }

    private static void complete(String key, boolean success) throws Exception {
        COMPLETE.invoke(null, key, success);
    }

    @Test
    // @DisplayName: "CT2-14：正常 leader 完成不计入夺取；守卫完成后可重新竞争；夺取计数可观测"
    public void leaderLifecycleDoesNotCountTakeover() throws Exception {
        String key = "single-flight-test-" + System.nanoTime();
        long before = SessionManager.getSingleFlightTakeoverCount();

        assertNull("首个申请者应为 leader（返回 null 表示需由调用方执行登录）", acquire(key));
        complete(key, true);

        assertEquals("正常 leader 完成绝不能计入夺取（否则计数失去信号意义）", before, SessionManager.getSingleFlightTakeoverCount());

        // 守卫已被清理 → 同一 key 可再次成为 leader（不残留死守卫）
        assertNull("守卫清理后再次申请应重新成为 leader", acquire(key));
        complete(key, false);
    }
}
