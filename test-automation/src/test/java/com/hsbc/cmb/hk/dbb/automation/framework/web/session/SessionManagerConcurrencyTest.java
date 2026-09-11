package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3-1 收拢验证：{@link SessionManager} 的 Feature 级 Session 状态
 * （原静态 {@code ThreadLocal<String> currentFeatureSessionKey}、
 * {@code ThreadLocal<Boolean> featureSessionRestored}（withInitial(false)）与
 * {@code ThreadLocal<String> currentFeatureHomeUrl} 已迁入
 * {@link com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder}）。
 * <p>
 * 默认 false 语义由读取侧 null 守卫（{@code Boolean.TRUE.equals} / {@code restored != null}）
 * 等价保证，故未设值返回 null 与返回 false 行为一致。
 */
public class SessionManagerConcurrencyTest {

    @Test
    public void perThreadFeatureSessionIsolation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            SessionManager.markFeatureSessionRestored("key1", "https://home.example.com");
            assertTrue( SessionManager.isFeatureSessionRestored("key1"), "主线程应标记为已恢复");
            assertTrue( SessionManager.isAnyFeatureSessionRestored(), "主线程 isAny 应为 true");
            assertEquals( "https://home.example.com",  SessionManager.getFeatureHomeUrl(), "主线程应读到 homeUrl");

            Future<Boolean> otherRestored = pool.submit(() -> SessionManager.isFeatureSessionRestored("key1"));
            Future<Boolean> otherAny = pool.submit(SessionManager::isAnyFeatureSessionRestored);
            Future<String> otherHome = pool.submit(SessionManager::getFeatureHomeUrl);

            assertFalse( otherRestored.get(5, TimeUnit.SECONDS), "其他线程不应看到主线程的恢复标记");
            assertFalse( otherAny.get(5, TimeUnit.SECONDS), "其他线程不应看到主线程的 isAny 标记");
            assertNull( otherHome.get(5, TimeUnit.SECONDS), "其他线程不应看到主线程的 homeUrl");
        } finally {
            SessionManager.resetFeatureSession();
            pool.shutdown();
        }
    }
}
