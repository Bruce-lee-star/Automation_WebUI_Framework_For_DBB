package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3 / S9 回归：feature 级会话缓存按 featureId 隔离，以及统一重置入口。
 * 纯内存、零网络依赖（不创建浏览器 / 不登录）。
 */
class SessionManagerFeatureCacheTest {

    @BeforeEach
    void setUp() {
        SessionManager.resetAllForTest();
    }

    @AfterEach
    void tearDown() {
        SessionManager.resetAllForTest();
    }

    /** S3：不同 feature 的缓存互不踩踏；跨 feature 的 resetFeatureSession() 只清当前 feature。 */
    @Test
    void featureCache_isolatedByFeatureId() {
        SessionManager.setCurrentFeatureId("featureA");
        SessionManager.markFeatureSessionRestored("key1", "http://homeA");

        assertTrue(SessionManager.isFeatureSessionRestored("key1"));
        assertEquals("http://homeA", SessionManager.getFeatureHomeUrl());

        // 切换到另一 feature：缓存不可见（不同桶）
        SessionManager.setCurrentFeatureId("featureB");
        assertFalse(SessionManager.isFeatureSessionRestored("key1"));
        assertNull(SessionManager.getFeatureHomeUrl());

        // 跨 feature 的 resetFeatureSession() 仅清当前 feature（featureB），不影响 featureA
        SessionManager.resetFeatureSession();

        SessionManager.setCurrentFeatureId("featureA");
        assertTrue(SessionManager.isFeatureSessionRestored("key1"),
                "featureA 的缓存不应被 featureB 的 reset 清除");

        // 显式按 featureId 范围清理
        SessionManager.resetFeatureSession("featureA");
        assertFalse(SessionManager.isFeatureSessionRestored("key1"));
    }

    /** S3：从未设置 featureId（默认 "default" 桶）时行为与旧实现一致（整表清理，零回归）。 */
    @Test
    void defaultBucket_degradesSafelyWithoutFeatureId() {
        SessionManager.markFeatureSessionRestored("k", "http://h");
        assertTrue(SessionManager.isFeatureSessionRestored("k"));

        SessionManager.resetFeatureSession(); // 默认桶 → 整表清理
        assertFalse(SessionManager.isFeatureSessionRestored("k"));
    }

    /** S9：统一重置入口清空 feature 缓存。 */
    @Test
    void resetAllForTest_clearsFeatureCache() {
        SessionManager.setCurrentFeatureId("f");
        SessionManager.markFeatureSessionRestored("k", "http://h");
        assertTrue(SessionManager.isFeatureSessionRestored("k"));

        SessionManager.resetAllForTest();
        assertFalse(SessionManager.isFeatureSessionRestored("k"));
    }
}
