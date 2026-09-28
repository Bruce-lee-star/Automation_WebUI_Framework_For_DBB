package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrencyGate;
import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrencyPartitionKey;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.microsoft.playwright.BrowserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SessionStore —— 会话状态的<b>新内核</b>（2026-09-27 重构：从 {@link SessionManager} 抽取）。
 *
 * <p><b>设计哲学（与旧实现解耦后的新契约）</b>：
 * <ol>
 *   <li><b>身份与句柄分离</b>：{@code sessionKey}（业务层传入，<b>不含 browserType</b>）只标识"逻辑会话"；
 *       其"是否有效/可复用"由有效期判定，与浏览器活对象（Page/Context）无关。</li>
 *   <li><b>有效期判定三层信号</b>（本内核相对旧实现的核心改进）：
 *       <ol type="a">
 *         <li><b>① cookie 过期时间（权威）</b>：从 storageState 的 session cookie 的 {@code expires}
 *             解析出真实服务端 TTL，过期即无效；</li>
 *         <li><b>② 本地文件最大年龄</b>：仅作兜底（cookie 无 expires / 只用 sessionStorage 时生效）；</li>
 *         <li><b>③ 服务端探针</b>：可选（{@code PROBE_ON_REUSE}），用于兜服务端提前失效（管理员踢人）。
 *             默认只在①缺失或临近过期时触发，避免每次复用都做网络往返。</li>
 *       </ol>
 *       绝不可只信本地文件时间戳（旧实现即此缺陷）。
 *   </li>
 *   <li><b>两层缓存</b>：L1 进程内 {@code ConcurrentHashMap}（同运行最快复用）；L2 磁盘 storageState(.json)
 *       + meta(.meta)。L2 写用"临时文件 + 原子移动"，永不出现半截文件。</li>
 *   <li><b>单飞（single-flight）</b>：同一 sessionKey 只允许一个线程真实登录，其余阻塞复用；
 *       以 {@link LoginGuard}（CountDownLatch + owner 线程 + 被取代标记）实现，leader 在<b>锁外</b>
 *       执行登录，follower 仅 {@code await}（不持监视器），故不会把"登录慢"变成"全局阻塞"。
 *       超时 + 宽限后仍无可用会话才夺取，并显式标记原 leader 已被取代（可观测双重登录风险）。</li>
 *   <li><b>feature / scenario 模式只影响句柄保留策略</b>：复用决策（身份+有效期）两种模式共用同一逻辑。</li>
 * </ol>
 *
 * <p>{@link SessionManager} 退化为兼容门面，全部方法委托本类，<b>签名零变更</b>以保证 84+ 调用点与
 * 反射测试继续编译。</p>
 *
 * <p>铁律：本工程为<b>单 JVM</b> 多线程执行模型（forkCount=1），跨 JVM 单飞不被支持（CI 分片会触发
 * SSO 单会话互踢，属明确的不支持用法）。</p>
 */
final class SessionStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionStore.class);

    // ===================== 目录与配置 =====================
    private static final String SESSION_DIR = resolveSessionDir();

    private static String resolveSessionDir() {
        String override = System.getProperty("serenity.playwright.session.dir");
        if (override != null && !override.trim().isEmpty()) {
            return Paths.get(override).toAbsolutePath().normalize().toString();
        }
        return Paths.get(System.getProperty("user.dir", "."), "target", ".sessions")
                .toAbsolutePath().normalize().toString();
    }

    /** 本地最大年龄兜底（分钟）；cookie 无 expires 时生效。 */
    private static final long SESSION_TIMEOUT_MINUTES =
            FrameworkConfigManager.getInt(WebFrameworkConfig.PLAYWRIGHT_NO_LOGIN_SESSION_TIMEOUT);

    /**
     * 服务端探针（设计钩子，当前未启用）：用于兜服务端提前失效（管理员踢人）。
     * 探针需发起一次受保护页导航/轻量接口调用，成本高，故默认不实现、不触发；
     * 有效期判定以 cookie 过期（主信号）+ 本地年龄（兜底）为准。如需启用，在此引入
     * {@code WebFrameworkConfig} 新键并实现 probe 逻辑即可。
     */

    /**
     * 单飞 follower 等待 leader 完成的兜底超时（毫秒）。
     * 常规路径：leader 完成即经 latch 唤醒 follower，零额外等待；
     * 此值仅在 leader 异常慢/死亡（远超正常登录时长）时触发夺取，属保险丝而非常规等待。
     */
    private static final long SINGLE_FLIGHT_TIMEOUT_MS =
            FrameworkConfigManager.getInt(WebFrameworkConfig.PLAYWRIGHT_NO_LOGIN_SINGLE_FLIGHT_TIMEOUT_MS);

    /** storageState 快照是否包含 IndexedDB（Firebase/SPA 登录态存于 IndexedDB 时置 true）。 */
    private static final boolean SESSION_INCLUDE_INDEXED_DB =
            Boolean.TRUE.equals(FrameworkConfigManager.getBoolean(
                    WebFrameworkConfig.PLAYWRIGHT_NO_LOGIN_SESSION_INCLUDE_INDEXED_DB));

    // ===================== 单飞 =====================
    private static final ConcurrentHashMap<String, LoginGuard> loginGuards = new ConcurrentHashMap<>();
    private static final AtomicLong SINGLE_FLIGHT_TAKEOVERS = new AtomicLong();
    private static final ThreadLocal<LoginGuard> MY_LOGIN_GUARD = new ThreadLocal<>();
    private static final ThreadLocal<String> MY_LOGIN_GUARD_KEY = new ThreadLocal<>();

    // ===================== 内存缓存（Guava 并发缓存） =====================
    private static final SessionMeta ABSENT_META = new SessionMeta(null, 0L, false, -1L);

    private static final LoadingCache<String, SessionMeta> META_CACHE = CacheBuilder.newBuilder()
            .concurrencyLevel(16)
            .maximumSize(1000)
            .build(new CacheLoader<String, SessionMeta>() {
                @Override
                public SessionMeta load(String sessionKey) {
                    SessionMeta meta = readSessionMetaFromDisk(sessionKey);
                    return (meta != null) ? meta : ABSENT_META;
                }
            });

    private static final LoadingCache<String, String> STORAGE_CONTENT_CACHE = CacheBuilder.newBuilder()
            .concurrencyLevel(16)
            .maximumSize(1000)
            .build(new CacheLoader<String, String>() {
                @Override
                public String load(String sessionKey) throws Exception {
                    String content = new String(Files.readAllBytes(getSessionPath(sessionKey)), StandardCharsets.UTF_8);
                    if (!isValidStorageStateJson(content)) {
                        throw new IllegalArgumentException(
                                "storageState content for " + sessionKey + " is not a valid JSON document"
                                        + " (corrupted/truncated file)");
                    }
                    return content;
                }
            });

    // ===================== Feature 级会话缓存 =====================
    private static final ConcurrentHashMap<String, FeatureSession> FEATURE_SESSION_BY_KEY =
            new ConcurrentHashMap<>();
    private static final ThreadLocal<String> CURRENT_FEATURE_KEY = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_FEATURE_ID = ThreadLocal.withInitial(() -> "default");

    // ===================== 同一 sessionKey 并发互斥 =====================
    private static final ThreadLocal<ConcurrencyPartitionKey> SESSION_GATE = new ThreadLocal<>();
    /** 「闸门未启用」提示只打一次（acquireSessionGate 每次会话读写都会经过，避免刷屏）。 */
    private static final AtomicBoolean GATE_DISABLED_NOTICE = new AtomicBoolean(false);

    private SessionStore() {
    }

    // ===================== 公开统计 =====================
    static long getSingleFlightTakeoverCount() {
        return SINGLE_FLIGHT_TAKEOVERS.get();
    }

    // ===================== 单飞守卫 =====================
    /** leader 登录完成后 {@link #complete(boolean)} 释放，follower 通过 {@link #await(long)} 等待。 */
    private static final class LoginGuard {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean success;
        final Thread owner = Thread.currentThread();
        private final AtomicBoolean superseded = new AtomicBoolean(false);

        boolean await(long ms) {
            try {
                return latch.await(ms, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void complete(boolean ok) {
            success = ok;
            latch.countDown();
        }

        boolean isSuccess() {
            return success;
        }

        void markSuperseded() {
            superseded.set(true);
        }

        boolean isSuperseded() {
            return superseded.get();
        }
    }

    // ===================== Session Meta =====================
    /**
     * 会话元数据快照（不可变值对象）。
     * <ul>
     *   <li>{@code homeUrl} —— 登录后首页；</li>
     *   <li>{@code lastAccessTime} —— 末次访问（本地年龄兜底判据）；</li>
     *   <li>{@code sessionFileExists} —— storageState 文件存在性；</li>
     *   <li>{@code cookieExpiryEpoch} —— session cookie 的过期时间戳（毫秒）；
     *       {@code -1} 表示无 expires（退化本地年龄）。</li>
     * </ul>
     */
    private static final class SessionMeta {
        final String homeUrl;
        final long lastAccessTime;
        final boolean sessionFileExists;
        final long cookieExpiryEpoch;

        SessionMeta(String homeUrl, long lastAccessTime, boolean sessionFileExists, long cookieExpiryEpoch) {
            this.homeUrl = homeUrl;
            this.lastAccessTime = lastAccessTime;
            this.sessionFileExists = sessionFileExists;
            this.cookieExpiryEpoch = cookieExpiryEpoch;
        }
    }

    // ===================== 有效期判定（核心改进点） =====================
    /**
     * 判断会话是否过期。
     * <p><b>主信号 = cookie 过期时间</b>：若 storageState 含带 {@code expires} 的 session cookie，
     * 以该时间戳为准（服务端真实 TTL）；过期即无效。
     * <b>兜底 = 本地文件年龄</b>：cookie 无 expires 时，以 {@code lastAccessTime} 距现在是否超
     * {@code SESSION_TIMEOUT_MINUTES} 判定。</p>
     */
    private static boolean isSessionExpired(SessionMeta meta) {
        if (meta.cookieExpiryEpoch > 0) {
            return System.currentTimeMillis() > meta.cookieExpiryEpoch;
        }
        long elapsedMinutes = (System.currentTimeMillis() - meta.lastAccessTime) / (60_000L);
        return elapsedMinutes > SESSION_TIMEOUT_MINUTES;
    }

    /**
     * 从 storageState JSON 解析 session cookie 的最大过期时间戳（毫秒）。
     * 取所有 cookie 中 {@code expires} 最大者（通常就是会话主 cookie，如 JSESSIONID / OAuth）。
     * 无 {@code expires} 或解析失败返回 {@code -1}（退化本地年龄判定）。
     */
    private static long parseCookieExpiryEpoch(String storageStateJson) {
        if (storageStateJson == null || storageStateJson.isBlank()) {
            return -1L;
        }
        try {
            JsonElement root = JsonParser.parseString(storageStateJson);
            if (!root.isJsonObject()) {
                return -1L;
            }
            JsonElement cookiesEl = root.getAsJsonObject().get("cookies");
            if (cookiesEl == null || !cookiesEl.isJsonArray()) {
                return -1L;
            }
            long maxExpiry = -1L;
            for (JsonElement e : (JsonArray) cookiesEl) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject c = e.getAsJsonObject();
                JsonElement expiresEl = c.get("expires");
                if (expiresEl == null || !expiresEl.isJsonPrimitive()) {
                    continue;
                }
                String expires = expiresEl.getAsString();
                if (expires == null || expires.isEmpty()) {
                    continue;
                }
                long epoch = parseHttpDateToEpoch(expires);
                if (epoch > maxExpiry) {
                    maxExpiry = epoch;
                }
            }
            return maxExpiry;
        } catch (JsonSyntaxException | UnsupportedOperationException | IllegalStateException ex) {
            return -1L;
        }
    }

    /** 轻量 HTTP-date → epoch 解析（支持 RFC1123 与 RFC1036/asctime 常见形态；失败返回 -1）。 */
    private static long parseHttpDateToEpoch(String httpDate) {
        if (httpDate == null || httpDate.isEmpty()) {
            return -1L;
        }
        // 优先交给 java.time 的兼容解析；失败再降级 -1
        try {
            // java.net.HttpCookie 自带 RFC1123/1036/asctime 解析
            java.net.HttpCookie hc = java.net.HttpCookie.parse(
                    "dummy=" + "x; Expires=" + httpDate).isEmpty()
                    ? null : java.net.HttpCookie.parse("dummy=x; Expires=" + httpDate).get(0);
            if (hc != null) {
                long maxAge = hc.getMaxAge();
                if (maxAge >= 0) {
                    return System.currentTimeMillis() + maxAge * 1000L;
                }
            }
        } catch (Exception ignored) {
            LOGGER.debug("cookie maxAge parse failed, fallback to timestamp heuristic", ignored);
        }
        // 退路：尝试直接当毫秒/秒时间戳
        try {
            String trimmed = httpDate.trim();
            if (trimmed.matches("\\d{10,13}")) {
                long v = Long.parseLong(trimmed);
                return v < 1_000_000_000_000L ? v * 1000L : v;
            }
        } catch (NumberFormatException ignored) {
            LOGGER.debug("expires value not numeric, fallback to -1L", ignored);
        }
        return -1L;
    }

    // ===================== 内存缓存访问 =====================
    static boolean isValidStorageStateJson(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonParser.parseString(json);
            return true;
        } catch (JsonSyntaxException e) {
            return false;
        }
    }

    static void purgeSessionFiles(String sessionKey) {
        try {
            Files.deleteIfExists(getSessionPath(sessionKey));
        } catch (Exception e) {
            LOGGER.warn("Failed to delete corrupted session file for {}: {}", sessionKey, e.getMessage());
        }
        try {
            Files.deleteIfExists(getMetaPath(sessionKey));
        } catch (Exception e) {
            LOGGER.warn("Failed to delete corrupted meta file for {}: {}", sessionKey, e.getMessage());
        }
        META_CACHE.invalidate(sessionKey);
        STORAGE_CONTENT_CACHE.invalidate(sessionKey);
    }

    private static SessionMeta loadSessionMeta(String sessionKey) {
        try {
            SessionMeta meta = META_CACHE.get(sessionKey);
            return (meta == ABSENT_META) ? null : meta;
        } catch (ExecutionException | UncheckedExecutionException e) {
            LOGGER.warn("[SessionStore] Failed to load meta cache for {} -> treating as no cache entry", sessionKey, e);
            return null;
        }
    }

    private static SessionMeta readSessionMetaFromDisk(String sessionKey) {
        Path sessionPath = getSessionPath(sessionKey);
        Path metaPath = getMetaPath(sessionKey);
        boolean sessionFileExists = Files.exists(sessionPath);
        if (!Files.exists(metaPath)) {
            return null;
        }
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(metaPath, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (Exception e) {
            LOGGER.warn("[SessionStore] Failed to load meta for {} -> treating as no cache entry", sessionKey, e);
            return null;
        }
        String homeUrl = props.getProperty("homeUrl");
        long lastAccessTime = 0L;
        String lastAccessTimeStr = props.getProperty("lastAccessTime");
        if (lastAccessTimeStr != null && !lastAccessTimeStr.isEmpty()) {
            try {
                lastAccessTime = Long.parseLong(lastAccessTimeStr);
            } catch (NumberFormatException nfe) {
                lastAccessTime = 0L;
            }
        }
        // cookie 过期时间：优先从磁盘 meta 读取（saveMeta 写入），否则从 storageState 内容解析
        long cookieExpiry = -1L;
        String cookieExpiryStr = props.getProperty("cookieExpiry");
        if (cookieExpiryStr != null && !cookieExpiryStr.isEmpty()) {
            try {
                cookieExpiry = Long.parseLong(cookieExpiryStr);
            } catch (NumberFormatException nfe) {
                cookieExpiry = -1L;
            }
        }
        if (cookieExpiry < 0) {
            try {
                String content = STORAGE_CONTENT_CACHE.get(sessionKey);
                cookieExpiry = parseCookieExpiryEpoch(content);
            } catch (Exception ignored) {
                cookieExpiry = -1L;
            }
        }
        return new SessionMeta(homeUrl, lastAccessTime, sessionFileExists, cookieExpiry);
    }

    private static String getStorageStateContent(String sessionKey) {
        SessionMeta meta = loadSessionMeta(sessionKey);
        if (meta == null || !meta.sessionFileExists || isSessionExpired(meta)) {
            STORAGE_CONTENT_CACHE.invalidate(sessionKey);
            return null;
        }
        try {
            return STORAGE_CONTENT_CACHE.get(sessionKey);
        } catch (ExecutionException | UncheckedExecutionException e) {
            if (e.getCause() instanceof IllegalArgumentException) {
                LOGGER.error("[SessionStore] Corrupted storageState content for {} -> purging session files "
                        + "so next run re-logs in (cause: {})", sessionKey, e.getCause().getMessage());
                purgeSessionFiles(sessionKey);
                return null;
            }
            LOGGER.warn("[SessionStore] Failed to load storageState content for {} -> treating as no cache entry",
                    sessionKey, e);
            return null;
        }
    }

    private static boolean evictIfExpired(String sessionKey) {
        SessionMeta meta = loadSessionMeta(sessionKey);
        if (meta == null || !meta.sessionFileExists) {
            return false;
        }
        if (isSessionExpired(meta)) {
            VerboseLogging.logInfoIfVerbose(LOGGER, "Session expired for: {}", sessionKey);
            try {
                Files.delete(getSessionPath(sessionKey));
                Files.delete(getMetaPath(sessionKey));
            } catch (Exception e) {
                LOGGER.warn("Failed to delete expired session: {}", sessionKey, e);
            }
            META_CACHE.put(sessionKey, ABSENT_META);
            return true;
        }
        return false;
    }

    // ===================== Feature 级会话 =====================
    private static String featureCacheKey(String sessionKey) {
        return CURRENT_FEATURE_ID.get() + "::" + (sessionKey == null ? "" : sessionKey);
    }

    static void setCurrentFeatureId(String featureId) {
        CURRENT_FEATURE_ID.set((featureId == null || featureId.trim().isEmpty()) ? "default" : featureId);
    }

    private static final class FeatureSession {
        final String sessionKey;
        final String homeUrl;

        FeatureSession(String sessionKey, String homeUrl) {
            this.sessionKey = sessionKey;
            this.homeUrl = homeUrl;
        }
    }

    static boolean isAnyFeatureSessionRestored() {
        return !FEATURE_SESSION_BY_KEY.isEmpty();
    }

    static String currentFeatureSessionKey() {
        return CURRENT_FEATURE_KEY.get();
    }

    static void markFeatureSessionRestored(String sessionKey, String homeUrl) {
        FEATURE_SESSION_BY_KEY.put(featureCacheKey(sessionKey), new FeatureSession(sessionKey, homeUrl));
        CURRENT_FEATURE_KEY.set(sessionKey);
        PlaywrightManager.bindCurrentContextSessionKey(sessionKey);
        VerboseLogging.logInfoIfVerbose(LOGGER,
                "Feature-level session marked as restored: {} (homeUrl: {})", sessionKey, homeUrl);
    }

    static boolean isFeatureSessionRestored(String sessionKey) {
        FeatureSession session = FEATURE_SESSION_BY_KEY.get(featureCacheKey(sessionKey));
        if (session != null && java.util.Objects.equals(sessionKey, session.sessionKey)) {
            VerboseLogging.logInfoIfVerbose(LOGGER,
                    "Feature-level session already restored for: {}, skipping restore", sessionKey);
            return true;
        }
        return false;
    }

    static String getFeatureHomeUrl() {
        String key = CURRENT_FEATURE_KEY.get();
        if (key == null) {
            return null;
        }
        FeatureSession session = FEATURE_SESSION_BY_KEY.get(featureCacheKey(key));
        return session == null ? null : session.homeUrl;
    }

    static void resetFeatureSession() {
        resetFeatureSession(CURRENT_FEATURE_ID.get());
    }

    static void resetFeatureSession(String featureId) {
        VerboseLogging.logInfoIfVerbose(LOGGER, "Resetting feature-level session state (feature: {})", featureId);
        if (featureId != null && !featureId.trim().isEmpty() && !"default".equals(featureId)) {
            String prefix = featureId + "::";
            FEATURE_SESSION_BY_KEY.keySet().removeIf(k -> k.startsWith(prefix));
        } else {
            FEATURE_SESSION_BY_KEY.clear();
        }
        CURRENT_FEATURE_KEY.remove();
        String leaderKey = MY_LOGIN_GUARD_KEY.get();
        if (leaderKey != null) {
            loginGuards.remove(leaderKey);
        }
    }

    static void resetAllForTest() {
        loginGuards.clear();
        FEATURE_SESSION_BY_KEY.clear();
        META_CACHE.invalidateAll();
        STORAGE_CONTENT_CACHE.invalidateAll();
        CURRENT_FEATURE_KEY.remove();
        MY_LOGIN_GUARD.remove();
        MY_LOGIN_GUARD_KEY.remove();
        SESSION_GATE.remove();
        CURRENT_FEATURE_ID.remove();
    }

    // ===================== 并发互斥闸门 =====================
    /**
     * 同 sessionKey 并发闸门 —— <b>框架侧唯一的并发闸门接入点，业务层不得自建</b>。
     * 本进程内同一 sessionKey 的 scenario 串行、不同 sessionKey 并行；键 = {@code Map.of("sessionkey", sessionKey)}。
     * 释放由框架在 scenario 收尾（{@code PlaywrightListener.cleanupThreadLocals()} →
     * {@link #releaseSessionGate()} 及 {@link ConcurrencyGate#releaseAllForCurrentThread()}）无条件执行，
     * <b>不依赖任何业务 {@code @After}</b>。
     *
     * <p>闸门未启用时（{@code auto} 且串行执行）只提示一次：进程内互斥未启用，跨进程 / 移动端等
     * <b>外部</b>并发使用同一身份仍可能造成 SSO 互踢（表现为会话中途失效、其后页面与接口断言漂移），
     * 本闸门对此无能为力 —— 排查时须先排除外部并发。</p>
     */
    static void acquireSessionGate(String sessionKey) {
        if (sessionKey == null || sessionKey.trim().isEmpty()) {
            return;
        }
        if (!ConcurrencyGate.isEnabled() && GATE_DISABLED_NOTICE.compareAndSet(false, true)) {
            LOGGER.warn("[SessionStore] 同 sessionKey 并发闸门未启用（{} = auto 且当前为串行执行）——"
                            + "本进程内不做同会话互斥；跨进程 / 移动端并发登录不受保护，"
                            + "若出现会话中途失效请先排除有外部并发使用同一身份。",
                    WebFrameworkConfig.CONCURRENCY_PARTITION_ENABLED.getKey());
        }
        ConcurrencyPartitionKey key;
        try {
            key = ConcurrencyPartitionKey.of(Map.of("sessionkey", sessionKey));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[SessionStore] sessionKey '{}' cannot form a partition key, skipping gate: {}",
                    sessionKey, e.getMessage());
            return;
        }
        ConcurrencyPartitionKey held = SESSION_GATE.get();
        if (held != null) {
            if (held.equals(key)) {
                return;
            }
            //  换键（同场景内切换 sessionKey）：先放旧再取新 —— 全程保持同一种加锁顺序，避免与
            //  另一线程形成 A→B / B→A 的互等（acquire 虽有界 + fail-closed，但会误判场景失败）。
            //  代价：两步之间存在极短窗口（另一场景可乘隙进入旧键），仅存在于"同场景换身份"这一少见路径。
            releaseSessionGate();
        }
        ConcurrencyGate.acquire(key);
        SESSION_GATE.set(key);
    }

    static void releaseSessionGate() {
        ConcurrencyPartitionKey key = SESSION_GATE.get();
        SESSION_GATE.remove();
        if (key != null) {
            ConcurrencyGate.release(key);
        }
        String leaderKey = MY_LOGIN_GUARD_KEY.get();
        if (leaderKey != null) {
            completeLoginGuard(leaderKey, false);
        }
    }

    // ===================== homeUrl =====================
    static String getHomeUrl(String sessionKey) {
        String homeUrl = getFeatureHomeUrl();
        if (homeUrl != null && !homeUrl.isEmpty()) {
            return homeUrl;
        }
        return loadHomeUrl(sessionKey);
    }

    static String loadHomeUrl(String sessionKey) {
        if (evictIfExpired(sessionKey)) {
            return null;
        }
        SessionMeta meta = loadSessionMeta(sessionKey);
        return (meta != null) ? meta.homeUrl : null;
    }

    // ===================== 会话存在性 =====================
    private static boolean hasSession(String sessionKey) {
        SessionMeta meta = loadSessionMeta(sessionKey);
        if (meta == null || !meta.sessionFileExists) {
            VerboseLogging.logInfoIfVerbose(LOGGER, "Session file not found: {}", sessionKey);
            return false;
        }
        if (evictIfExpired(sessionKey)) {
            return false;
        }
        VerboseLogging.logInfoIfVerbose(LOGGER, "Valid session found for: {}", sessionKey);
        return true;
    }

    private static boolean hasUsableSession(String sessionKey) {
        if (!hasSession(sessionKey)) {
            return false;
        }
        String homeUrl = loadHomeUrl(sessionKey);
        return homeUrl != null && !homeUrl.isEmpty();
    }

    // ===================== 复用路径 =====================
    // 注：原 tryReuseExisting 已合并入 reusePersistedSession（功能超集：复用同时刷新 meta/cache），
    // 避免两条复用路径分叉。所有复用入口统一走 reusePersistedSession。

    private static boolean reusePersistedSession(String sessionKey, String restartStrategy) {
        if (!hasUsableSession(sessionKey)) {
            return false;
        }
        String persistedHomeUrl = loadHomeUrl(sessionKey);
        if (getStorageStateContent(sessionKey) != null) {
            PlaywrightManager.applyStorageStatePath(getSessionPath(sessionKey));
        } else {
            return false;
        }
        PlaywrightManager.getContext();

        META_CACHE.put(sessionKey,
                new SessionMeta(persistedHomeUrl, System.currentTimeMillis(), true,
                        parseCookieExpiryEpochSafely(sessionKey)));
        saveMeta(sessionKey, persistedHomeUrl);
        try {
            String warm = getStorageStateContent(sessionKey);
            if (warm != null) {
                STORAGE_CONTENT_CACHE.put(sessionKey, warm);
            }
        } catch (Exception ignored) {
            VerboseLogging.logDebugIfVerbose(LOGGER,
                    "storageState warm back-fill skipped for {} (non-fatal)", sessionKey);
        }

        if ("feature".equalsIgnoreCase(restartStrategy)) {
            markFeatureSessionRestored(sessionKey, persistedHomeUrl);
        }
        VerboseLogging.logInfoIfVerbose(LOGGER,
                "Reusing session persisted by single-flight leader: {}", sessionKey);
        return true;
    }

    private static long parseCookieExpiryEpochSafely(String sessionKey) {
        try {
            return parseCookieExpiryEpoch(STORAGE_CONTENT_CACHE.get(sessionKey));
        } catch (Exception e) {
            return -1L;
        }
    }

    // ===================== 单飞协调 =====================
    static LoginGuard acquireOrAwait(String sessionKey) {
        if (sessionKey == null) {
            return null;
        }
        LoginGuard candidate = new LoginGuard();
        LoginGuard existing = loginGuards.putIfAbsent(sessionKey, candidate);
        if (existing == null) {
            MY_LOGIN_GUARD.set(candidate);
            MY_LOGIN_GUARD_KEY.set(sessionKey);
            return null; // leader
        }
        if (!existing.owner.isAlive() && loginGuards.remove(sessionKey, existing)) {
            existing.complete(false);
            LoginGuard recheck = loginGuards.putIfAbsent(sessionKey, candidate);
            if (recheck == null) {
                MY_LOGIN_GUARD.set(candidate);
                MY_LOGIN_GUARD_KEY.set(sessionKey);
                LOGGER.warn("[SessionStore] abandoned login guard for sessionKey={} (owner thread no longer "
                                + "alive) -> taking over immediately instead of waiting {}ms",
                        sessionKey, SINGLE_FLIGHT_TIMEOUT_MS);
                return null;
            }
            existing = recheck;
        }

        boolean completed = existing.await(SINGLE_FLIGHT_TIMEOUT_MS);
        if (completed) {
            return existing;
        }
        if (hasUsableSession(sessionKey)) {
            LOGGER.info("[SessionStore] session for {} became usable while waiting -> reuse it "
                    + "(no second login)", sessionKey);
            return existing;
        }
        existing.markSuperseded();
        LoginGuard raced = loginGuards.putIfAbsent(sessionKey, candidate);
        if (raced == null) {
            MY_LOGIN_GUARD.set(candidate);
            MY_LOGIN_GUARD_KEY.set(sessionKey);
            long takeovers = SINGLE_FLIGHT_TAKEOVERS.incrementAndGet();
            LOGGER.warn("[SessionStore] Timed out {}ms waiting for concurrent login of sessionKey={} "
                            + "and no usable session yet -> taking over as leader (potential twofold login; "
                            + "takeovers={}). If frequent: raise playwright.noLogin.singleFlightTimeoutMs or make "
                            + "login faster.",
                    SINGLE_FLIGHT_TIMEOUT_MS, sessionKey, takeovers);
            return null;
        }
        if (raced.await(SINGLE_FLIGHT_TIMEOUT_MS)) {
            return raced;
        }
        if (hasUsableSession(sessionKey)) {
            return existing;
        }
        return null;
    }

    static void completeLoginGuard(String sessionKey, boolean success) {
        if (sessionKey == null) {
            return;
        }
        LoginGuard mine = MY_LOGIN_GUARD.get();
        if (mine != null && mine.isSuperseded()) {
            LOGGER.error("[SessionStore] login for sessionKey={} was SUPERSEDED by a waiter after "
                            + "timeout -> a concurrent second real login may have occurred "
                            + "(SSO single-session policy may kick one side out). takeovers={}",
                    sessionKey, SINGLE_FLIGHT_TAKEOVERS.get());
        }
        boolean removed = mine != null && loginGuards.remove(sessionKey, mine);
        if (removed) {
            mine.complete(success);
        }
        MY_LOGIN_GUARD.remove();
        MY_LOGIN_GUARD_KEY.remove();
    }

    // ===================== restore / save / clear =====================
    static boolean restoreSession(String sessionKey) {
        if (sessionKey == null || sessionKey.trim().isEmpty()) {
            LOGGER.warn("[SessionStore] restoreSession called with empty sessionKey -> skip");
            return false;
        }
        acquireSessionGate(sessionKey);
        String restartStrategy = PlaywrightManager.config().getRestartStrategy();
        if ("feature".equalsIgnoreCase(restartStrategy)) {
            if (isFeatureSessionRestored(sessionKey)) {
                BrowserContext liveCtx = PlaywrightRuntime.instance().contextRegistry.currentContextForThread();
                boolean ctxAlive = liveCtx != null && liveCtx.browser() != null && liveCtx.browser().isConnected();
                if (ctxAlive) {
                    PlaywrightManager.bindCurrentContextSessionKey(sessionKey);
                    VerboseLogging.logInfoIfVerbose(LOGGER,
                            "Feature-level session cache hit: {} (homeUrl: {})", sessionKey, getFeatureHomeUrl());
                    return true;
                }
                VerboseLogging.logInfoIfVerbose(LOGGER,
                        "Feature cache hit but context dead for {} — falling back to file restore", sessionKey);
            }
            String boundKey = PlaywrightManager.currentContextSessionKeyForThread();
            if (PlaywrightRuntime.instance().contextRegistry.hasContext() && boundKey != null && !sessionKey.equals(boundKey)) {
                VerboseLogging.logInfoIfVerbose(LOGGER,
                        "Feature mode: sessionKey {} differs from the one bound to the current Context ({})"
                                + " — discarding current Context",
                        sessionKey, boundKey);
                PlaywrightRuntime.instance().contextRegistry.discardCurrentContext();
                PlaywrightManager.customOptions().setStorageStatePath(null);
                PlaywrightManager.customOptions().setStorageState(null);
            }
        } else {
            VerboseLogging.logDebugIfVerbose(LOGGER, "Scenario mode: skipping feature-level cache for {}", sessionKey);
        }

        if (reusePersistedSession(sessionKey, restartStrategy)) {
            return true;
        }
        VerboseLogging.logWarnIfVerbose(LOGGER, "Session file exists but unusable for: {} — will login", sessionKey);

        LoginGuard guard = acquireOrAwait(sessionKey);
        if (guard == null) {
            // 本线程是 leader：有可复用态直接成功并完成单飞守卫；否则返回 false（由调用方决定是否登录）
            if (hasUsableSession(sessionKey) && reusePersistedSession(sessionKey, restartStrategy)) {
                completeLoginGuard(sessionKey, true);
                return true;
            }
            return false;
        }
        if (reusePersistedSession(sessionKey, restartStrategy)) {
            return true;
        }
        loginGuards.remove(sessionKey, guard);
        VerboseLogging.logWarnIfVerbose(LOGGER,
                "Single-flight leader did not produce a usable session for {} — this thread will login", sessionKey);
        return false;
    }

    static boolean restoreSession(String sessionKey, Runnable leaderAction) {
        if (sessionKey == null || sessionKey.trim().isEmpty()) {
            LOGGER.warn("[SessionStore] restoreSession called with empty sessionKey -> skip");
            return false;
        }
        acquireSessionGate(sessionKey);
        String restartStrategy = PlaywrightManager.config().getRestartStrategy();
        if ("feature".equalsIgnoreCase(restartStrategy)) {
            if (isFeatureSessionRestored(sessionKey)) {
                VerboseLogging.logInfoIfVerbose(LOGGER,
                        "Feature-level session cache hit: {} (homeUrl: {})", sessionKey, getFeatureHomeUrl());
                return true;
            }
            String boundKey = PlaywrightManager.currentContextSessionKeyForThread();
            if (PlaywrightRuntime.instance().contextRegistry.hasContext() && boundKey != null && !sessionKey.equals(boundKey)) {
                VerboseLogging.logInfoIfVerbose(LOGGER,
                        "Feature mode: sessionKey {} differs from the one bound to the current Context ({})"
                                + " — discarding current Context",
                        sessionKey, boundKey);
                PlaywrightRuntime.instance().contextRegistry.discardCurrentContext();
                PlaywrightManager.customOptions().setStorageStatePath(null);
                PlaywrightManager.customOptions().setStorageState(null);
            }
        } else {
            VerboseLogging.logDebugIfVerbose(LOGGER, "Scenario mode: skipping feature-level cache for {}", sessionKey);
        }

        if (reusePersistedSession(sessionKey, restartStrategy)) {
            return true;
        }
        VerboseLogging.logInfoIfVerbose(LOGGER, "No valid session for: {}, waiting for login", sessionKey);

        LoginGuard guard = acquireOrAwait(sessionKey);
        if (guard == null) {
            // 本线程是 leader：有可复用态直接成功并完成单飞守卫；否则执行 leaderAction 登录
            if (hasUsableSession(sessionKey) && reusePersistedSession(sessionKey, restartStrategy)) {
                completeLoginGuard(sessionKey, true);
                return true;
            }
            wrapLeaderAction(sessionKey, leaderAction).run();
            return false;
        }
        if (reusePersistedSession(sessionKey, restartStrategy)) {
            return true;
        }
        loginGuards.remove(sessionKey, guard);
        VerboseLogging.logWarnIfVerbose(LOGGER,
                "Single-flight leader did not produce a usable session for {} — this thread will login", sessionKey);
        // leader 已结束但未产出可用态：本线程再尝试复用一次（完成守卫），否则执行登录
        if (hasUsableSession(sessionKey) && reusePersistedSession(sessionKey, restartStrategy)) {
            completeLoginGuard(sessionKey, true);
            return true;
        }
        wrapLeaderAction(sessionKey, leaderAction).run();
        return false;
    }

    private static Runnable wrapLeaderAction(String sessionKey, Runnable leaderAction) {
        return () -> {
            try {
                leaderAction.run();
            } catch (RuntimeException ex) {
                completeLoginGuard(sessionKey, false);
                throw ex;
            }
        };
    }

    static void saveSession(String sessionKey, String homeUrl) {
        acquireSessionGate(sessionKey);
        try {
            Path sessionPath = getSessionPath(sessionKey);
            Path sessionParent = sessionPath.getParent();
            if (sessionParent != null && !Files.exists(sessionParent)) {
                Files.createDirectories(sessionParent);
            }
            VerboseLogging.logInfoIfVerbose(LOGGER, "Saving session for: {} (homeUrl: {})", sessionKey, homeUrl);

            BrowserContext context = PlaywrightManager.getContext();
            BrowserContext.StorageStateOptions storageOptions = new BrowserContext.StorageStateOptions();
            if (SESSION_INCLUDE_INDEXED_DB) {
                storageOptions.setIndexedDB(true);
            }
            String storageStateJson = context.storageState(storageOptions);
            writeStorageStateAtomically(sessionKey, storageStateJson);
            STORAGE_CONTENT_CACHE.put(sessionKey, storageStateJson);

            saveMeta(sessionKey, homeUrl, parseCookieExpiryEpoch(storageStateJson));
            META_CACHE.put(sessionKey, new SessionMeta(homeUrl, System.currentTimeMillis(), true,
                    parseCookieExpiryEpoch(storageStateJson)));

            String restartStrategy = PlaywrightManager.config().getRestartStrategy();
            if ("feature".equalsIgnoreCase(restartStrategy)) {
                markFeatureSessionRestored(sessionKey, homeUrl);
            }
            VerboseLogging.logInfoIfVerbose(LOGGER, "Session saved successfully: {} -> {}", sessionKey, sessionPath);
            completeLoginGuard(sessionKey, true);
        } catch (Exception e) {
            completeLoginGuard(sessionKey, false);
            LOGGER.error("Failed to save session for: {}", sessionKey, e);
            throw new RuntimeException("Failed to save session", e);
        }
    }

    static boolean clearSession(String sessionKey) {
        try {
            completeLoginGuard(sessionKey, false);
            META_CACHE.invalidate(sessionKey);
            STORAGE_CONTENT_CACHE.invalidate(sessionKey);

            Path sessionPath = getSessionPath(sessionKey);
            Path metaPath = getMetaPath(sessionKey);
            boolean sessionDeleted = false;
            boolean metaDeleted = false;
            if (Files.exists(sessionPath)) {
                Files.delete(sessionPath);
                sessionDeleted = true;
            }
            if (Files.exists(metaPath)) {
                Files.delete(metaPath);
                metaDeleted = true;
            }
            boolean cleared = sessionDeleted || metaDeleted;
            if (cleared) {
                LOGGER.info("Session cleared successfully: {}", sessionKey);
            } else {
                VerboseLogging.logInfoIfVerbose(LOGGER, "No session found to clear: {}", sessionKey);
            }
            return cleared;
        } catch (Exception e) {
            LOGGER.error("Failed to clear session for: {}", sessionKey, e);
            return false;
        }
    }

    static int clearAllSessions() {
        try {
            Path sessionDir = Paths.get(SESSION_DIR);
            if (!Files.exists(sessionDir)) {
                return 0;
            }
            Set<String> sessionNames = new HashSet<>();
            try (var stream = Files.list(sessionDir)) {
                stream.forEach(path -> {
                    String name = path.getFileName().toString();
                    if (name.endsWith(".json") || name.endsWith(".meta")) {
                        int dotIdx = name.lastIndexOf('.');
                        sessionNames.add(dotIdx > 0 ? name.substring(0, dotIdx) : name);
                        try {
                            Files.delete(path);
                        } catch (Exception e) {
                            LOGGER.warn("Failed to delete: {}", path, e);
                        }
                    }
                });
            }
            META_CACHE.invalidateAll();
            STORAGE_CONTENT_CACHE.invalidateAll();
            LOGGER.info("Cleared {} session(s)", sessionNames.size());
            return sessionNames.size();
        } catch (Exception e) {
            LOGGER.error("Failed to clear all sessions", e);
            return 0;
        }
    }

    // ===================== 路径与落盘 =====================
    private static Path getSessionPath(String sessionKey) {
        return Paths.get(SESSION_DIR, sessionKey + ".json");
    }

    private static Path getMetaPath(String sessionKey) {
        return Paths.get(SESSION_DIR, sessionKey + ".meta");
    }

    private static void writeStorageStateAtomically(String sessionKey, String json) {
        Path sessionPath = getSessionPath(sessionKey);
        Path tmp = sessionPath.resolveSibling(sessionPath.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, sessionPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, sessionPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception cleanupIgnored) {
                VerboseLogging.logDebugIfVerbose(LOGGER,
                        "Temp storageState file not deleted for {}: {}", sessionKey, cleanupIgnored.getMessage());
            }
            throw new RuntimeException("Failed to write storageState for " + sessionKey, e);
        }
    }

    private static void saveMeta(String sessionKey, String homeUrl) {
        saveMeta(sessionKey, homeUrl, -1L);
    }

    private static void saveMeta(String sessionKey, String homeUrl, long cookieExpiry) {
        try {
            Path metaPath = getMetaPath(sessionKey);
            Properties props = new Properties();
            if (Files.exists(metaPath)) {
                try (var reader = Files.newBufferedReader(metaPath, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
            }
            if (homeUrl != null && !homeUrl.isEmpty()) {
                props.setProperty("homeUrl", homeUrl);
            }
            props.setProperty("lastAccessTime", String.valueOf(System.currentTimeMillis()));
            if (cookieExpiry > 0) {
                props.setProperty("cookieExpiry", String.valueOf(cookieExpiry));
            }
            try (var writer = Files.newBufferedWriter(metaPath, StandardCharsets.UTF_8)) {
                props.store(writer, "Session Meta Data");
            }
            VerboseLogging.logDebugIfVerbose(LOGGER, "Meta saved: {} (homeUrl: {})", sessionKey, homeUrl);
        } catch (Exception e) {
            LOGGER.error("Failed to save meta for: {} (session reuse may fall back to re-login)", sessionKey, e);
        }
    }
}
