package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.Cache;
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
 *   <li><b>有效期判定（2026-09-29 收敛为唯一判据）</b>：只看 {@code lastAccessTime} 与配置
 *       {@code playwright.no.login.session.timeout.minutes}（默认 5 分钟）—— 超过即过期、
 *       删会话文件、下次完整登录。
 *       <ul>
 *         <li>{@code lastAccessTime} 由 {@link #saveSession}（完整登录成功后）写入，
 *             <b>复用路径不再刷新</b> ⇒ 该阈值是会话的<b>绝对最大持续时间</b>，不会被复用无限延长；</li>
 *         <li><b>不再解析 storageState 的 {@code cookies[].expires}</b>：Playwright 写的是 Unix 秒数字
 *             （session cookie 为 {@code -1}），DBB 实测 49 个 cookie 中 34 个无 TTL，带 TTL 的
 *             （eID/currentLocale 等）约 25 天且与 SSO 服务端空闲超时无关。旧实现取"最长 cookie TTL"
 *             当主判据 ⇒ 会话在 25 天内永不判过期、配置阈值形同失效；该解析已<b>整体删除，勿再引入</b>；</li>
 *         <li><b>服务端探针</b>：{@code PROBE_ON_REUSE} <b>目前只有设计钩子、未实现</b>
 *             （见方法内注释），不要把它计入可用信号。</li>
 *       </ul>
 *   </li>
 *   <li><b>两层缓存</b>：L1 进程内 {@code ConcurrentHashMap}（同运行最快复用）；L2 磁盘 storageState(.json)
 *       + meta(.meta)。L2 写用"临时文件 + 原子移动"，永不出现半截文件。</li>
 *   <li><b>单飞（single-flight）</b>：同一 sessionKey 只允许一个线程真实登录，其余阻塞复用；
 *       以 {@link LoginGuard}（CountDownLatch + owner 线程 + 被取代标记）实现，leader 在<b>锁外</b>
 *       执行登录，follower 仅 {@code await}（不持监视器），故不会把"登录慢"变成"全局阻塞"。
 *       超时 + 宽限后仍无可用会话才夺取，并显式标记原 leader 已被取代（可观测双重登录风险）。</li>
 *   <li><b>重启模式决定"是否复用会话"</b>（2026-09-28 明确，见 {@code logScenarioModeNoReuse}）：
 *       <ul>
 *         <li>{@code feature}：同一 feature 内共享会话 —— L1 命中（Context 存活 + 绑定一致）或 L2 文件恢复
 *             （{@code applyStorageStatePath} 会把 cookies + localStorage 一起注入）；</li>
 *         <li>{@code scenario}：<b>零复用</b> —— 每个用例都在全新 Context 内完整登录，本地会话文件
 *             <b>只写不读</b>（默认策略即 scenario；要共享会话必须显式配 {@code feature}）。</li>
 *       </ul>
 *       同一模式内，复用决策（身份 + 有效期）仍共用同一逻辑。</li>
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

    /** 会话最大持续时间（分钟）；自 {@code lastAccessTime} 起算，超过即过期（见 {@link #isSessionExpired}）。 */
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
    private static final SessionMeta ABSENT_META = new SessionMeta(null, 0L, false);

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

    /**
     * 内存缓存（L1）：storageState 快照内容，键为 sessionKey，跨用例存活。
     * 并发优先读、每 case 从缓存拿；本地文件仅作<b>冷启动</b>来源（见 {@link #getStorageStateContent}）。
     *
     * <p>不设 maximumSize/过期：条目只经显式 {@code invalidate} 离开，配合 {@link #INVALIDATED_KEYS}
     * 区分「冷启动（从未加载 → 可回退文件）」与「运行中途失效（已显式失效 → 不回退文件）」。</p>
     */
    private static final Cache<String, String> STORAGE_CONTENT_CACHE = CacheBuilder.newBuilder()
            .concurrencyLevel(16)
            .build();

    /**
     * 运行中途已显式失效的 sessionKey 集合。进入后 {@link #getStorageStateContent} 直接返回
     * {@code null} 且<b>不再回退本地文件</b>——证明该会话在本轮运行中已失效（应重新登录），
     * 与「JVM 冷启动、该 key 从未加载过、允许从文件加载」严格区分。
     */
    private static final Set<String> INVALIDATED_KEYS = ConcurrentHashMap.newKeySet();

    // ===================== 当前线程会话 key（homeUrl 走 meta 来源） =====================
    // 2026-09-30 收口：不再区分 feature / scenario 模式，移除原 in-memory FEATURE_SESSION_BY_KEY 缓存。
    // 仅保留本线程「当前承载登录态的 sessionKey」线程局部（saveSession / reusePersistedSession 成功时登记），
    // 供 getHomeUrl() 从 meta 文件取权威 homeUrl；清理随 scenario/feature 边界进行。
    private static final ThreadLocal<String> CURRENT_SESSION_KEY = new ThreadLocal<>();

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
     *   <li>{@code lastAccessTime} —— 会话保存时刻（{@link #saveSession} 写入，复用不刷新），
     *       是有效期判定的<b>唯一</b>时间基准；</li>
     *   <li>{@code sessionFileExists} —— storageState 文件存在性。</li>
     * </ul>
     */
    private static final class SessionMeta {
        final String homeUrl;
        final long lastAccessTime;
        final boolean sessionFileExists;

        SessionMeta(String homeUrl, long lastAccessTime, boolean sessionFileExists) {
            this.homeUrl = homeUrl;
            this.lastAccessTime = lastAccessTime;
            this.sessionFileExists = sessionFileExists;
        }
    }

    // ===================== 有效期判定 =====================
    /**
     * 判断会话是否过期 —— <b>唯一判据</b>：{@link SessionMeta#lastAccessTime} 距现在是否超过配置的
     * 最大持续时间（{@code playwright.no.login.session.timeout.minutes}，默认 5 分钟）。
     *
     * <p>{@code lastAccessTime} 只在 {@link #saveSession} 写入、复用不刷新，故该阈值是会话的
     * <b>绝对最大持续时间</b>；不再参考 storageState 的 {@code cookies[].expires}（原因见类注释）。</p>
     */
    private static boolean isSessionExpired(SessionMeta meta) {
        return isSessionExpired(meta.lastAccessTime, System.currentTimeMillis());
    }

    /**
     * 纯函数版过期判定：{@code now - lastAccessTime >} 配置时长；{@code lastAccessTime <= 0}
     * （meta 缺失或损坏）一律判过期，走"完整重登"这条 fail-safe 路径。
     *
     * <p>package-private 供单测直接注入 {@code now}（{@code SessionStoreSessionExpiryTest}），
     * 免去为边界断言而 sleep。</p>
     */
    static boolean isSessionExpired(long lastAccessTime, long now) {
        if (lastAccessTime <= 0L) {
            return true;
        }
        return now - lastAccessTime > TimeUnit.MINUTES.toMillis(SESSION_TIMEOUT_MINUTES);
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
        INVALIDATED_KEYS.add(sessionKey);
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
        // 历史 meta 里可能残留旧实现的 cookieExpiry / generation 行：不再读取，由 saveMeta 下次写入时清除
        return new SessionMeta(homeUrl, lastAccessTime, sessionFileExists);
    }

    /**
     * 读取某 sessionKey 的 storageState 快照内容（内存缓存优先；冷启动回退本地文件）。
     *
     * <p><b>两层来源与「冷启动 / 运行中途失效」区分</b>：
     * <ul>
     *   <li>内存缓存 {@link #STORAGE_CONTENT_CACHE}：跨用例存活，并发优先读、每 case 从缓存拿；</li>
     *   <li>本地文件 {@code <sessionDir>/<sessionKey>.json}：仅<b>冷启动</b>（key 从未被加载过、
     *       且不在 {@link #INVALIDATED_KEYS}）时作为唯一来源加载并回填缓存；</li>
     *   <li>{@link #INVALIDATED_KEYS}：标记"运行中途已显式失效"的 key，进入后<b>不再回退文件</b>，
     *       证明该会话本轮已失效（应重新登录），与冷启动严格区分。</li>
     * </ul>
     */
    private static String getStorageStateContent(String sessionKey) {
        if (INVALIDATED_KEYS.contains(sessionKey)) {
            return null;  // 运行中途已明确失效：不回退文件
        }
        String cached = STORAGE_CONTENT_CACHE.getIfPresent(sessionKey);
        if (cached != null) {
            return cached;  // 命中 L1 缓存（每 case 从缓存拿）
        }
        // 冷启动：本地文件是唯一来源，校验后加载并回填缓存
        SessionMeta meta = loadSessionMeta(sessionKey);
        if (meta == null || !meta.sessionFileExists || isSessionExpired(meta)) {
            INVALIDATED_KEYS.add(sessionKey);
            return null;
        }
        try {
            String content = new String(Files.readAllBytes(getSessionPath(sessionKey)), StandardCharsets.UTF_8);
            if (!isValidStorageStateJson(content)) {
                throw new IllegalArgumentException(
                        "storageState content for " + sessionKey + " is not a valid JSON document"
                                + " (corrupted/truncated file)");
            }
            STORAGE_CONTENT_CACHE.put(sessionKey, content);
            return content;
        } catch (Exception e) {
            LOGGER.error("[SessionStore] Corrupted/unreadable storageState for {} -> purging session files "
                    + "so next run re-logs in (cause: {})", sessionKey, e.getMessage());
            INVALIDATED_KEYS.add(sessionKey);
            purgeSessionFiles(sessionKey);
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

    // ===================== 当前会话 key 清理（scenario/feature 边界） =====================
    // 2026-09-30 收口：原 in-memory feature 缓存已移除，此处仅清理本线程 CURRENT_SESSION_KEY，
    // 并兜底回收本线程可能滞留的单飞登录守卫（与 completeLoginGuard 互补，幂等）。
    static void resetCurrentSession() {
        VerboseLogging.logInfoIfVerbose(LOGGER, "Resetting current session key at scenario/feature boundary");
        CURRENT_SESSION_KEY.remove();
        String leaderKey = MY_LOGIN_GUARD_KEY.get();
        if (leaderKey != null) {
            loginGuards.remove(leaderKey);
        }
    }

    static void resetAllForTest() {
        loginGuards.clear();
        META_CACHE.invalidateAll();
        STORAGE_CONTENT_CACHE.invalidateAll();
        INVALIDATED_KEYS.clear();
        CURRENT_SESSION_KEY.remove();
        MY_LOGIN_GUARD.remove();
        MY_LOGIN_GUARD_KEY.remove();
        SESSION_GATE.remove();
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
            LOGGER.debug("[SessionStore] same-sessionKey concurrency gate is NOT enabled ({} = auto while running "
                            + "serially) — no in-process mutual exclusion for this session; concurrent logins of the "
                            + "same identity from other processes / mobile are NOT protected. If a session drops "
                            + "mid-run, rule out external concurrent use of the same identity first.",
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
    // 2026-09-30 收口：不再区分 feature / scenario 模式，移除 in-memory feature 缓存快路径；
    // homeUrl 一律以 meta 文件为权威来源。无参重载取本线程最近一次承载登录态的 sessionKey。
    static String getHomeUrl(String sessionKey) {
        return loadHomeUrl(sessionKey);
    }

    static String getHomeUrl() {
        String key = CURRENT_SESSION_KEY.get();
        return (key == null) ? null : loadHomeUrl(key);
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

    private static boolean reusePersistedSession(String sessionKey) {
        //  2026-09-30 收口：免登录复用已成为全局默认行为（每 case 重建 Context 但注入已落盘
        //  storageState），本方法是唯一会调用 PlaywrightManager.applyStorageState*/getContext()
        //  注入会话的地方。统一模型下任何有可用会话文件的 sessionKey 都走复用，不再做档位分流。
        if (!hasUsableSession(sessionKey)) {
            return false;
        }
        if (getStorageStateContent(sessionKey) != null) {
            // 必须走「路径」变体：驱动侧 storageState 只接受「对象或文件路径」，传 JSON 字符串会被拒
            // （PlaywrightException: storageState: expected object, got string）。
            // 内容变体（applyStorageState(String)）只在「就地换会话」分支才落临时文件转成路径，
            // 其 else 分支会把 JSON 字符串塞进 newContext options —— 驱动不认，故此处不用。
            PlaywrightManager.applyStorageStatePath(getSessionPath(sessionKey));
        } else {
            return false;
        }
        PlaywrightManager.getContext();

        // 复用**不刷新** meta：lastAccessTime 只在 saveSession（完整登录成功后）写入；
        // 若在此刷新，"最大持续时间"会被每次复用无限延长，配置阈值形同失效。
        try {
            String warm = getStorageStateContent(sessionKey);
            if (warm != null) {
                STORAGE_CONTENT_CACHE.put(sessionKey, warm);
            }
        } catch (Exception ignored) {
            VerboseLogging.logDebugIfVerbose(LOGGER,
                    "storageState warm back-fill skipped for {} (non-fatal)", sessionKey);
        }

        //  2026-09-30 收口：免登录复用已成为全局默认行为（每 case 重建 Context 但注入已落盘 storageState）。
        //  登记本线程当前 sessionKey，供 getHomeUrl() 从 meta 取权威 homeUrl（取代原 feature 缓存，不再按 feature/credential/scenario 分档）。
        CURRENT_SESSION_KEY.set(sessionKey);
        VerboseLogging.logInfoIfVerbose(LOGGER,
                "Reusing session persisted by single-flight leader: {}", sessionKey);
        return true;
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

    /**
     * 复用已落盘 storageState 的会话（<b>两个 restoreSession 重载共用，消除语义分叉</b>）。
     *
     * <p>命中条件：存在可用会话文件（{@code hasUsableSession}）且其快照内容可读。命中后把快照路径注入新建
     * Context（{@code applyStorageStatePath}），并登记 feature 级会话缓存，供收尾策略与同 sessionKey 复用判定使用。
     * 任何缺失都返回 {@code false}，由调用方回落文件恢复或重新登录。</p>
     *
     * @return true = 可直接复用（Context 注入快照生效）；false = 未命中，调用方应回落文件恢复
     */




    // ===================== restore / save / clear =====================
    static boolean restoreSession(String sessionKey) {
        if (sessionKey == null || sessionKey.trim().isEmpty()) {
            LOGGER.warn("[SessionStore] restoreSession called with empty sessionKey -> skip");
            return false;
        }
        acquireSessionGate(sessionKey);
        //  2026-09-30 收口：不再区分 feature/credential/scenario 档，统一为「每 case 重建 Context +
        //  复用已落盘凭证（免登录）」。feature 模式复用活 Context 的快速路径（tryFeatureCacheHit）已移除。


        if (reusePersistedSession(sessionKey)) {
            return true;
        }
        VerboseLogging.logWarnIfVerbose(LOGGER, "Session file exists but unusable for: {} — will login", sessionKey);

        LoginGuard guard = acquireOrAwait(sessionKey);
        if (guard == null) {
            // 本线程是 leader：有可复用态直接成功并完成单飞守卫；否则返回 false（由调用方决定是否登录）
            if (hasUsableSession(sessionKey) && reusePersistedSession(sessionKey)) {
                completeLoginGuard(sessionKey, true);
                return true;
            }
            return false;
        }
        if (reusePersistedSession(sessionKey)) {
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
        //  2026-09-30 收口：不再区分 feature/credential/scenario 档，统一为「每 case 重建 Context +
        //  复用已落盘凭证（免登录）」。feature 模式复用活 Context 的快速路径（tryFeatureCacheHit）已移除。


        if (reusePersistedSession(sessionKey)) {
            return true;
        }
        VerboseLogging.logInfoIfVerbose(LOGGER, "No valid session for: {}, waiting for login", sessionKey);

        LoginGuard guard = acquireOrAwait(sessionKey);
        if (guard == null) {
            // 本线程是 leader：有可复用态直接成功并完成单飞守卫；否则执行 leaderAction 登录
            if (hasUsableSession(sessionKey) && reusePersistedSession(sessionKey)) {
                completeLoginGuard(sessionKey, true);
                return true;
            }
            wrapLeaderAction(sessionKey, leaderAction).run();
            return false;
        }
        if (reusePersistedSession(sessionKey)) {
            return true;
        }
        loginGuards.remove(sessionKey, guard);
        VerboseLogging.logWarnIfVerbose(LOGGER,
                "Single-flight leader did not produce a usable session for {} — this thread will login", sessionKey);
        // leader 已结束但未产出可用态：本线程再尝试复用一次（完成守卫），否则执行登录
        if (hasUsableSession(sessionKey) && reusePersistedSession(sessionKey)) {
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

    /**
     * 描述 storageState 的组成（cookies / origins / localStorage 条目数）。
     *
     * <p><b>用途</b>：为"被测应用的会话状态到底存在哪"提供<b>可判定的证据</b>，尤其用于判断是否需要打开
     * {@code playwright.no.login.session.include.indexed.db}（默认 false）。该键文档警告：若 SPA 把
     * 登录态放在 IndexedDB 而快照未包含，恢复出的会话会缺一半 ⇒ 随机 401 / 回登录页。</p>
     *
     * <p>实测（DBB，2026-09-28）：{@code cookies=49, origins=1, localStorageEntries=21}，且键名均为
     * 前端会话/展示状态（{@code hsbc.session.active.lastTime}、{@code TT_DBB_KEEPALIVE}、
     * {@code lastKnownProfileValue} 等）⇒ 其前端会话态主要在 <b>localStorage</b>，
     * 因此（a）localStorage 必须随会话保留、（b）IndexedDB 是否为必需项可用本日志在下一次真实运行时确认。</p>
     */
    private static String describeStorageState(String storageStateJson) {
        if (storageStateJson == null || storageStateJson.isBlank()) {
            return "empty";
        }
        try {
            JsonObject root = JsonParser.parseString(storageStateJson).getAsJsonObject();
            int cookies = root.has("cookies") && root.get("cookies").isJsonArray()
                    ? root.getAsJsonArray("cookies").size() : 0;
            int origins = 0;
            int localStorageEntries = 0;
            if (root.has("origins") && root.get("origins").isJsonArray()) {
                JsonArray originsArr = root.getAsJsonArray("origins");
                origins = originsArr.size();
                for (JsonElement origin : originsArr) {
                    if (!origin.isJsonObject()) {
                        continue;
                    }
                    JsonObject originObj = origin.getAsJsonObject();
                    if (originObj.has("localStorage") && originObj.get("localStorage").isJsonArray()) {
                        localStorageEntries += originObj.getAsJsonArray("localStorage").size();
                    }
                }
            }
            return "cookies=" + cookies + ", origins=" + origins
                    + ", localStorageEntries=" + localStorageEntries;
        } catch (RuntimeException ex) {
            return "unparsable (" + ex.getClass().getSimpleName() + ")";
        }
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
            INVALIDATED_KEYS.remove(sessionKey);
            STORAGE_CONTENT_CACHE.put(sessionKey, storageStateJson);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("[Session] storageState captured for {}: {} ({} bytes, includeIndexedDb={})",
                        sessionKey, describeStorageState(storageStateJson), storageStateJson.length(),
                        SESSION_INCLUDE_INDEXED_DB);
            }

            long savedAt = System.currentTimeMillis();
            //  T8-6 代际：完整登录成功才自增（复用不递增），使"凭证被替换过几次"可观测。
            saveMeta(sessionKey, homeUrl, savedAt);
            META_CACHE.put(sessionKey, new SessionMeta(homeUrl, savedAt, true));
            VerboseLogging.logInfoIfVerbose(LOGGER,
                    "[Session] session saved for {} at {}", sessionKey, savedAt);

            //  2026-09-30 收口：完整登录成功后登记本线程当前 sessionKey，
            //  与复用路径（reusePersistedSession）一致，homeUrl 统一走 meta 来源。
            CURRENT_SESSION_KEY.set(sessionKey);
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
            INVALIDATED_KEYS.add(sessionKey);
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

    /**
     * 写入 meta（唯一调用方：{@link #saveSession}）。
     *
     * @param lastAccessTime 会话保存时刻；有效期判定的唯一时间基准，<b>只在完整登录成功后写入</b>
     */
    private static void saveMeta(String sessionKey, String homeUrl, long lastAccessTime) {
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
            props.setProperty("lastAccessTime", String.valueOf(lastAccessTime));
            props.remove("cookieExpiry");
            try (var writer = Files.newBufferedWriter(metaPath, StandardCharsets.UTF_8)) {
                props.store(writer, "Session Meta Data");
            }
            VerboseLogging.logDebugIfVerbose(LOGGER, "Meta saved: {} (homeUrl: {})", sessionKey, homeUrl);
        } catch (Exception e) {
            LOGGER.error("Failed to save meta for: {} (session reuse may fall back to re-login)", sessionKey, e);
        }
    }
}
