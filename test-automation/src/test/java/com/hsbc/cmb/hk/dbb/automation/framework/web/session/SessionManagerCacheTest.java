package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * SessionManager 内存缓存护盾：验证同 key 的 meta 在首次读盘后缓存、删除磁盘文件后仍可命中
 * 内存快照（证明不再重复读盘，缓解多线程读盘 IO 竞争），以及 clearSession 失效后回落为 null。
 * 纯文件 IO + 静态缓存，不依赖 Playwright / 浏览器。
 */
public class SessionManagerCacheTest {

    private static final String SESSION_DIR = "target/.sessions";
    // 实例级唯一 key：避免两个 @Test 共享静态缓存导致串扰
    private final String key = "UNITTEST_CACHE_" + UUID.randomUUID();

    private Path metaPath() {
        return Paths.get(SESSION_DIR, key + ".meta");
    }

    private Path sessionPath() {
        return Paths.get(SESSION_DIR, key + ".json");
    }

    private void writeSessionFiles(String homeUrl) throws Exception {
        Files.createDirectories(Paths.get(SESSION_DIR));
        Properties props = new Properties();
        props.setProperty("homeUrl", homeUrl);
        props.setProperty("lastAccessTime", String.valueOf(System.currentTimeMillis()));
        try (var w = Files.newBufferedWriter(metaPath(), StandardCharsets.UTF_8)) {
            props.store(w, "unit-test");
        }
        // 占位 session 文件（仅用于存在性校验，不依赖 Playwright 解析）
        Files.write(sessionPath(), new byte[0]);
    }

    @Test
    public void metaCachedAfterFirstRead_andSurvivesFileDeletion() throws Exception {
        writeSessionFiles("https://example.com/home");

        // 首次读取：落盘 → 缓存
        assertEquals("https://example.com/home", SessionManager.loadHomeUrl(key));

        // 删除磁盘文件，验证后续命中内存缓存（不再读盘）
        Files.deleteIfExists(metaPath());
        Files.deleteIfExists(sessionPath());
        assertEquals("https://example.com/home", SessionManager.loadHomeUrl(key));
    }

    @Test
    public void clearSessionInvalidatesCache() throws Exception {
        writeSessionFiles("https://example.com/home2");
        assertEquals("https://example.com/home2", SessionManager.loadHomeUrl(key));

        // 清除：删除文件 + 失效内存缓存
        SessionManager.clearSession(key);
        assertNull(SessionManager.loadHomeUrl(key));
    }

    @Test
    public void expiredSessionIsEvictedAndFilesDeleted() throws Exception {
        // 写入"已过期"的 session（lastAccessTime 远早于任何合理超时阈值，确保触发驱逐）
        Files.createDirectories(Paths.get(SESSION_DIR));
        Properties props = new Properties();
        props.setProperty("homeUrl", "https://example.com/expired");
        props.setProperty("lastAccessTime",
                String.valueOf(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000));
        try (var w = Files.newBufferedWriter(metaPath(), StandardCharsets.UTF_8)) {
            props.store(w, "expired");
        }
        Files.write(sessionPath(), new byte[0]); // session 文件存在

        // 读取触发过期驱逐：返回 null 且磁盘文件被删除（"失效即删除"）
        assertNull(SessionManager.loadHomeUrl(key));
        assertFalse("expired .meta should be deleted", Files.exists(metaPath()));
        assertFalse("expired .json should be deleted", Files.exists(sessionPath()));
    }
}
