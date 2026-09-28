package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-3 / P1-2 / P2-5 回归（2026-09-27 评审修复）：
 * <ul>
 *   <li>storageState 内容完整性校验（isValidStorageStateJson）与损坏自愈（purgeSessionFiles 幂等）；</li>
 *   <li>feature 缓存按线程隔离（CURRENT_FEATURE_ID 由 volatile static 改 ThreadLocal 后，
 *       并行 feature 不再串桶）。</li>
 * </ul>
 * 纯内存 / 纯本地文件、零网络依赖（不创建浏览器 / 不登录）。
 */
class SessionManagerStorageStateValidationTest {

    @BeforeEach
    void setUp() {
        SessionManager.resetAllForTest();
    }

    @AfterEach
    void tearDown() {
        SessionManager.resetAllForTest();
    }

    // ==================== P1-3：isValidStorageStateJson ====================

    @Test
    void validStorageStateJson_accepted() {
        assertTrue(SessionManager.isValidStorageStateJson("{\"cookies\":[],\"origins\":[]}"));
    }

    @Test
    void truncatedJson_rejected() {
        // 崩溃残留的典型形态：JSON 被截断（末尾缺 }）
        assertFalse(SessionManager.isValidStorageStateJson("{\"cookies\":[{\"name\":\"JSESSIONID\""));
    }

    @Test
    void emptyOrBlank_rejected() {
        assertFalse(SessionManager.isValidStorageStateJson(null));
        assertFalse(SessionManager.isValidStorageStateJson(""));
        assertFalse(SessionManager.isValidStorageStateJson("   "));
    }

    @Test
    void nonJsonText_rejected() {
        assertFalse(SessionManager.isValidStorageStateJson("<html>not json</html>"));
        assertFalse(SessionManager.isValidStorageStateJson("just some text"));
    }

    // ==================== P1-3：purgeSessionFiles 幂等自愈 ====================

    @Test
    void purgeSessionFiles_deletesFilesAndInvalidatesCaches() throws Exception {
        String key = "O63_SIT1_PURGE_TEST";
        Path sessionPath = sessionPath(key);
        Path metaPath = metaPath(key);
        Path parent = sessionPath.getParent();
        assertNotNull(parent, "target/.sessions 父目录不应为 null");
        Files.createDirectories(parent);
        Files.writeString(sessionPath, "{\"cookies\":[]}", StandardCharsets.UTF_8);
        Files.writeString(metaPath,
                "homeUrl=http://home" + "\n" + "lastAccessTime=" + System.currentTimeMillis(),
                StandardCharsets.UTF_8);

        // 预热 meta 缓存（读盘一次），再 purge
        assertNotNull(SessionManager.loadHomeUrl(key));
        SessionManager.purgeSessionFiles(key);

        assertFalse(Files.exists(sessionPath), "损坏的 .json 应被删除");
        assertFalse(Files.exists(metaPath), "损坏的 .meta 应被删除");
        assertNull(SessionManager.loadHomeUrl(key), "缓存失效后应回落为无 session");
    }

    @Test
    void purgeSessionFiles_idempotentOnMissingFiles() {
        // 不存在任何文件时 purge 不得抛异常（并发重复删除 / 已清除场景）
        assertDoesNotThrow(() -> SessionManager.purgeSessionFiles("O63_SIT1_NONEXISTENT"));
    }

    // ==================== P1-2：feature 缓存线程隔离（并行 feature 不串桶） ====================

    /**
     * 构造旧实现（CURRENT_FEATURE_ID 为 volatile static）必现串桶的交错时序：
     * <ol>
     *   <li>线程 A 设置 featureId=featureA（准备）；</li>
     *   <li>线程 B 设置 featureId=featureB —— 旧实现下 static 被覆盖为 featureB；</li>
     *   <li>线程 A 执行 markFeatureSessionRestored —— 旧实现写入 "featureB::sharedKey" 桶；</li>
     *   <li>线程 B 查询 sharedKey —— 旧实现误命中 A 的条目（串桶，跨 feature 误复用 Context）；</li>
     *   <li>线程 A 再查 —— 应命中自己的桶。</li>
     * </ol>
     * ThreadLocal 修复后：A 的条目始终在 featureA 桶，B 查询 featureB 桶恒为 miss。
     */
    @Test
    void featureCache_threadIsolated_parallelFeaturesDoNotCrossBuckets() throws Exception {
        CountDownLatch bHasSet = new CountDownLatch(1);
        CountDownLatch aHasMarked = new CountDownLatch(1);
        final boolean[] aSeesOwn = new boolean[1];
        final boolean[] bSeesA = new boolean[1];

        Thread threadA = new Thread(() -> {
            SessionManager.setCurrentFeatureId("featureA");
            try {
                bHasSet.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 关键：此刻线程 B 已 set featureB。旧实现 static=featureB → 此条目写入 featureB 桶。
            SessionManager.markFeatureSessionRestored("sharedKey", "http://homeA");
            // A 线程再次查询：ThreadLocal 修复后查 featureA 桶命中；旧实现查 static(被 B 覆盖为 featureB) 桶也命中
            aSeesOwn[0] = SessionManager.isFeatureSessionRestored("sharedKey");
            aHasMarked.countDown();
        }, "featureA-thread");

        Thread threadB = new Thread(() -> {
            SessionManager.setCurrentFeatureId("featureB");
            bHasSet.countDown();
            try {
                aHasMarked.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // B 线程在自己的 feature 桶内查询：必须 miss（旧实现命中 A 写入 featureB 桶的条目 → 串桶）
            bSeesA[0] = SessionManager.isFeatureSessionRestored("sharedKey");
        }, "featureB-thread");

        threadA.start();
        threadB.start();
        threadA.join(5_000);
        threadB.join(5_000);
        assertFalse(threadA.isAlive(), "featureA 线程应正常结束");
        assertFalse(threadB.isAlive(), "featureB 线程应正常结束");

        assertTrue(aSeesOwn[0], "A 线程应命中自己的 feature 缓存（featureA 桶）");
        assertFalse(bSeesA[0], "B 线程不得误命中 A feature 的缓存条目（P1-2 串桶根因）");
    }

    private static Path sessionPath(String key) {
        return Paths.get(System.getProperty("user.dir"), "target", ".sessions", key + ".json")
                .toAbsolutePath().normalize();
    }

    private static Path metaPath(String key) {
        return Paths.get(System.getProperty("user.dir"), "target", ".sessions", key + ".meta")
                .toAbsolutePath().normalize();
    }
}
