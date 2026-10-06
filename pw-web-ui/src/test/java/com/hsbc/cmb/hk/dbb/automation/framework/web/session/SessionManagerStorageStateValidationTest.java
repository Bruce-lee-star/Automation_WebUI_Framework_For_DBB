package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.hsbc.cmb.hk.dbb.automation.framework.web.JUnit4Assertions.assertDoesNotThrow;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P1-3 / P2-5 回归（2026-09-27 评审修复）：
 * <ul>
 *   <li>storageState 内容完整性校验（isValidStorageStateJson）与损坏自愈（purgeSessionFiles 幂等）；</li>
 * </ul>
 * 纯内存 / 纯本地文件、零网络依赖（不创建浏览器 / 不登录）。
 */
public class SessionManagerStorageStateValidationTest {

    @Before
    public void setUp() {
        SessionManager.resetAllForTest();
    }

    @After
    public void tearDown() {
        SessionManager.resetAllForTest();
    }

    // ==================== P1-3：isValidStorageStateJson ====================

    @Test
    public void validStorageStateJson_accepted() {
        assertTrue(SessionManager.isValidStorageStateJson("{\"cookies\":[],\"origins\":[]}"));
    }

    @Test
    public void truncatedJson_rejected() {
        // 崩溃残留的典型形态：JSON 被截断（末尾缺 }）
        assertFalse(SessionManager.isValidStorageStateJson("{\"cookies\":[{\"name\":\"JSESSIONID\""));
    }

    @Test
    public void emptyOrBlank_rejected() {
        assertFalse(SessionManager.isValidStorageStateJson(null));
        assertFalse(SessionManager.isValidStorageStateJson(""));
        assertFalse(SessionManager.isValidStorageStateJson("   "));
    }

    @Test
    public void nonJsonText_rejected() {
        assertFalse(SessionManager.isValidStorageStateJson("<html>not json</html>"));
        assertFalse(SessionManager.isValidStorageStateJson("just some text"));
    }

    // ==================== P1-3：purgeSessionFiles 幂等自愈 ====================

    @Test
    public void purgeSessionFiles_deletesFilesAndInvalidatesCaches() throws Exception {
        String key = "O63_SIT1_PURGE_TEST";
        Path sessionPath = sessionPath(key);
        Path metaPath = metaPath(key);
        Path parent = sessionPath.getParent();
        assertNotNull("target/.sessions 父目录不应为 null", parent);
        Files.createDirectories(parent);
        Files.writeString(sessionPath, "{\"cookies\":[]}", StandardCharsets.UTF_8);
        Files.writeString(metaPath,
                "homeUrl=http://home" + "\n" + "lastAccessTime=" + System.currentTimeMillis(),
                StandardCharsets.UTF_8);

        // 预热 meta 缓存（读盘一次），再 purge
        assertNotNull(SessionManager.loadHomeUrl(key));
        SessionManager.purgeSessionFiles(key);

        assertFalse("损坏的 .json 应被删除", Files.exists(sessionPath));
        assertFalse("损坏的 .meta 应被删除", Files.exists(metaPath));
        assertNull("缓存失效后应回落为无 session", SessionManager.loadHomeUrl(key));
    }

    @Test
    public void purgeSessionFiles_idempotentOnMissingFiles() {
        // 不存在任何文件时 purge 不得抛异常（并发重复删除 / 已清除场景）
        assertDoesNotThrow(() -> SessionManager.purgeSessionFiles("O63_SIT1_NONEXISTENT"));
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
