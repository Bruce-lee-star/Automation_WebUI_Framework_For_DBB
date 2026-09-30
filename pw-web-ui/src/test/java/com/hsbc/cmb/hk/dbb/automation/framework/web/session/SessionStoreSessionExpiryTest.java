package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话有效期判定回归（2026-09-29 收敛为唯一判据后）。
 *
 * <p><b>契约</b>：过期与否只看 {@code lastAccessTime}（{@link SessionStore#saveSession} 写入、
 * 复用不刷新）与 {@code playwright.no.login.session.timeout.minutes}；
 * storageState 里 {@code cookies[].expires} 的"最长 TTL"（DBB 实测约 25 天）<b>不再参与判定</b>。</p>
 *
 * <p>本类替换原 {@code SessionStoreExpiryParsingTest} —— 它所测的 cookie TTL 解析已整体删除。
 * 第 2 例是"cookie TTL 不得延长会话寿命"的<b>判别性哨兵</b>：旧实现下该例失败
 * （cookie 距今 25 天 ⇒ 旧逻辑判未过期 ⇒ 不驱逐）。</p>
 */
class SessionStoreSessionExpiryTest {

    private static final String HOME_URL = "https://home.example";

    private static final long TIMEOUT_MINUTES =
            FrameworkConfigManager.getInt(WebFrameworkConfig.PLAYWRIGHT_NO_LOGIN_SESSION_TIMEOUT);

    /** 判据边界：恰好等于阈值不算过期、超过阈值必过期；时间戳缺失走 fail-safe 重登。 */
    @Test
    void expiryBoundaryIsLastAccessTimePlusConfiguredMinutes() {
        long savedAt = 1_800_000_000_000L;

        assertFalse(SessionStore.isSessionExpired(savedAt, savedAt + TIMEOUT_MINUTES * 60_000L),
                "恰好到阈值不得判过期（当前配置 " + TIMEOUT_MINUTES + " 分钟）");
        assertTrue(SessionStore.isSessionExpired(savedAt, savedAt + (TIMEOUT_MINUTES + 1) * 60_000L),
                "超过阈值必须判过期");
        assertFalse(SessionStore.isSessionExpired(savedAt, savedAt), "刚保存的会话必须有效");
        assertTrue(SessionStore.isSessionExpired(0L, savedAt),
                "lastAccessTime 缺失/为 0 ⇒ 判过期（重登，而非复用年龄不明的会话）");
    }

    /** 判别性哨兵：超龄会话必须被驱逐并删掉两个文件，即便 storageState 里的 cookie 还有 25 天 TTL。 */
    @Test
    void overAgeSessionIsEvictedEvenWhenCookieTtlIsFarInFuture() throws Exception {
        String sessionKey = "expiry-sentinel-" + UUID.randomUUID();
        writeSessionFixture(sessionKey, futureCookieTtlSeconds(),
                System.currentTimeMillis() - (TIMEOUT_MINUTES + 5) * 60_000L);
        try {
            assertNull(SessionStore.loadHomeUrl(sessionKey),
                    "超龄会话必须判过期：cookie TTL（约 25 天）不得延长寿命（旧实现会返回 homeUrl）");
            assertFalse(Files.exists(sessionJsonPath(sessionKey)), "过期会话的 storageState 应被删除");
            assertFalse(Files.exists(sessionMetaPath(sessionKey)), "过期会话的 meta 应被删除");
        } finally {
            SessionStore.clearSession(sessionKey);
        }
    }

    /** 正例：既防"一律判过期"的假修复，也自检夹具确实写进了生产代码读取的目录。 */
    @Test
    void withinLifetimeSessionIsReusable() throws Exception {
        String sessionKey = "expiry-positive-" + UUID.randomUUID();
        writeSessionFixture(sessionKey, futureCookieTtlSeconds(), System.currentTimeMillis());
        try {
            assertEquals(HOME_URL, SessionStore.loadHomeUrl(sessionKey), "未超龄会话必须可复用");
            assertNotNull(SessionStore.loadHomeUrl(sessionKey));
        } finally {
            SessionStore.clearSession(sessionKey);
        }
    }

    /** DBB 实测形态：主会话 cookie 无 TTL，另有约 25 天 TTL 的 cookie（旧实现据此延长寿命）。 */
    private static long futureCookieTtlSeconds() {
        return System.currentTimeMillis() / 1000L + 25L * 24L * 3600L;
    }

    private static void writeSessionFixture(String sessionKey, long cookieTtlSeconds, long lastAccessTime)
            throws Exception {
        Path sessionPath = sessionJsonPath(sessionKey);
        Path parent = sessionPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String storageState = "{\"cookies\":[{\"name\":\"eID\",\"value\":\"x\",\"expires\":"
                + cookieTtlSeconds + "},{\"name\":\"JSESSIONID\",\"value\":\"x\",\"expires\":-1}],"
                + "\"origins\":[]}";
        Files.writeString(sessionPath, storageState, StandardCharsets.UTF_8);

        Properties props = new Properties();
        props.setProperty("homeUrl", HOME_URL);
        props.setProperty("lastAccessTime", String.valueOf(lastAccessTime));
        try (var writer = Files.newBufferedWriter(sessionMetaPath(sessionKey), StandardCharsets.UTF_8)) {
            props.store(writer, "session expiry test fixture");
        }
    }

    /** 与 {@code SessionStore.resolveSessionDir()} 同源解析；若不一致，本类正例会失败（故自检）。 */
    private static Path sessionDir() {
        String override = System.getProperty("serenity.playwright.session.dir");
        if (override != null && !override.trim().isEmpty()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir", "."), "target", ".sessions")
                .toAbsolutePath().normalize();
    }

    private static Path sessionJsonPath(String sessionKey) {
        return sessionDir().resolve(sessionKey + ".json");
    }

    private static Path sessionMetaPath(String sessionKey) {
        return sessionDir().resolve(sessionKey + ".meta");
    }
}
