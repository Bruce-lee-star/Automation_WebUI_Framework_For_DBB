package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import com.microsoft.playwright.BrowserContext;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import java.util.concurrent.ExecutionException;

/**
 * Session Manager - Manage user login state, supports skip login functionality
 * <p>
 * 新版特性：
 * - 框架层自动处理 session 管理逻辑
 * - 业务层只需传递 session key
 * - 自动处理 session 过期检查
 * - 简化的 API 设计
 * <p>
 * Session Key 格式（推荐）：env_username（如 O63_SIT1_WP7UAT2_2）
 */
public class SessionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionManager.class);

    // Session storage directory
    private static final String SESSION_DIR = "target/.sessions";

    // Session timeout in minutes — read from FrameworkConfig (default: 5)
    private static final long SESSION_TIMEOUT_MINUTES =
            FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_NO_LOGIN_SESSION_TIMEOUT);

    //  原 SESSION_IO_EXECUTOR（单线程 IO 超时守卫）已移除（2026-09-06，方案 B）：
    // META_CACHE 已缓存 .meta 的 homeUrl/lastAccessTime/exists，STORAGE_CONTENT_CACHE 已缓存
    // .json 内容，使 hasSession/loadHomeUrl 在缓存命中时为零磁盘读；仅冷启动 .meta 未命中会读盘，
    // 该读针对本地 target/.sessions 的极小文件，挂起概率可忽略。移除同时规避了单线程池
    // "一次卡死毒化全池、所有 session 复用降级为重新登录"的隐患。冷读若真卡死将直接作用于
    // 业务线程——属已接受的极小概率风险，无需线程池兜底。

    // ==================== 同 user 登录单飞（single-flight） ====================
    // 防止并行 scenario / 跨 feature 同 sessionKey 并发 restoreSession 时，两个线程都看到
    // "session 文件不存在" 而各自登录 → 服务端（单会话策略）把对端踢下线。
    // 约定：首个进入的线程（leader）执行真实登录并在 saveSession 成功后 complete；其余线程（follower）
    // 阻塞等待 leader 完成，成功后直接复用已落盘的 storageState，不再触发第二次登录。
    // 注意：仅 FileChannel 锁无法跨 JVM；本协调基于 JVM 内静态 Map，覆盖 Serenity 单 JVM 多线程并行
    // （forkCount=0）这一主场景。多 JVM（forkCount>0）需额外文件锁兜底。
    // 单飞 follower 等待 leader 完成的兜底超时（毫秒）— 读自 FrameworkConfig（默认 60000）
    private static final long SINGLE_FLIGHT_TIMEOUT_MS =
            FrameworkConfigManager.getInt(FrameworkConfig.PLAYWRIGHT_NO_LOGIN_SINGLE_FLIGHT_TIMEOUT_MS);
    private static final ConcurrentHashMap<String, LoginGuard> loginGuards = new ConcurrentHashMap<>();

    /**
     * 单飞守卫：leader 登录完成后 {@link #complete(boolean)} 释放，follower 通过 {@link #await(long)} 等待。
     */
    private static final class LoginGuard {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean success;

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
    }

    // ==================== Session Meta 内存缓存（Guava CacheBuilder 并发缓存，缓解多线程读盘 IO 竞争） ====================
    // 同 sessionKey 的 meta（homeUrl + lastAccessTime + session 文件存在性）一经读盘即缓存，
    // 后续并发读（并行 scenario 同 key 恢复）直接命中内存，仅首次触发一次磁盘 IO。
    // 并发语义由 Guava LoadingCache 保证：concurrencyLevel(16) 提供分段锁，同 key 多线程读时
    // 仅一个线程执行 load（readSessionMetaFromDisk），其余线程阻塞复用其结果，避免多线程重复读
    // 同一缓存文件造成 IO 竞争；maximumSize 作为内存上限兜底（正常场景远不触达）。
    // 失效时机：saveSession（put 新鲜值）/ clearSession（invalidate）/ clearAllSessions（invalidateAll）
    // 修改磁盘文件后调用。
    private static final SessionMeta ABSENT_META = new SessionMeta(null, 0L, false);

    private static final LoadingCache<String, SessionMeta> META_CACHE = CacheBuilder.newBuilder()
            .concurrencyLevel(16)
            .maximumSize(1000)
            .build(new CacheLoader<String, SessionMeta>() {
                @Override
                public SessionMeta load(String sessionKey) {
                    SessionMeta meta = readSessionMetaFromDisk(sessionKey);
                    // Guava 不允许 null 值：以哨兵占位，等效"不缓存负结果"
                    return (meta != null) ? meta : ABSENT_META;
                }
            });

    /**
     * 登录态内容（storageState JSON 文本）内存缓存。
     * <p>与 {@link #META_CACHE} 仅缓存元数据不同，此处缓存的是 <em>storageState 文本内容</em>，
     * 使 restoreSession 命中时直接把 JSON 字符串传给 Playwright
     * （{@code NewContextOptions.setStorageState(String)}，1.58.0+ 支持直接传内存 JSON，无需临时文件/重复读盘），
     * 彻底消除高并发下对同一个 canonical {@code <key>.json} 的重复文件读 IO。
     * <p>加载器：缓存 miss 时读 canonical {@code <key>.json} 一次（磁盘仍是跨 JVM 真相源）。
     * 一致性：以 {@link #saveSession} 为唯一写入口，落盘后用新鲜内容 put 刷新；
     * {@link #clearSession}/{@link #clearAllSessions} 负责 invalidate。
     */
    private static final LoadingCache<String, String> STORAGE_CONTENT_CACHE = CacheBuilder.newBuilder()
            .concurrencyLevel(16)
            .maximumSize(1000)
            //  过期窗口与逻辑 TTL 对齐（同 SESSION_TIMEOUT_MINUTES，默认 5 分钟）：
            // 内容缓存只作"内存加速"，其存活窗口不应长于 session 逻辑过期，
            // 否则可能因缓存在而掩盖 evictIfExpired 已清盘后短时间内又命中旧内容的风险。
            // access 窗口内反复 get() 会刷新访问时间，热路径（同 key 5 分钟内复用）不受影响。
            .expireAfterAccess(SESSION_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .build(new CacheLoader<String, String>() {
                @Override
                public String load(String sessionKey) throws Exception {
                    return new String(Files.readAllBytes(getSessionPath(sessionKey)), StandardCharsets.UTF_8);
                }
            });

    /**
     * 取登录态内容：优先内存缓存（miss 时由加载器从 canonical {@code <key>.json} 读一次），失败返回 null。
     * 失败（如文件被并发清除）时调用方应回退到文件路径，保证健壮。
     */
    private static String getStorageStateContent(String sessionKey) {
        try {
            return STORAGE_CONTENT_CACHE.get(sessionKey);
        } catch (Exception e) {
            LOGGER.warn("[SessionManager] Failed to load storageState content for {} → fall back to file path",
                    sessionKey, e);
            return null;
        }
    }

    /**
     * Session meta 快照（从 .meta 文件解析，并附带 .json 会话文件存在性）。
     * 不可变值对象，供内存缓存复用，避免重复读盘。
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

    /**
     * 读取指定 sessionKey 的 meta（单飞 + 内存缓存）。
     * <p>缓存未命中时执行磁盘 IO（.meta 解析 + .json 存在性检查），命中则直接返回内存快照。
     * 文件缺失时返回 {@code null}（不缓存负结果，保证后续 saveSession 立即可见）。
     */
    private static SessionMeta loadSessionMeta(String sessionKey) {
        try {
            SessionMeta meta = META_CACHE.get(sessionKey);
            return (meta == ABSENT_META) ? null : meta;
        } catch (ExecutionException e) {
            LOGGER.warn("[SessionManager] Failed to load meta cache for {} → treating as no cache entry",
                    sessionKey, e);
            return null;
        }
    }

    private static SessionMeta readSessionMetaFromDisk(String sessionKey) {
        Path sessionPath = getSessionPath(sessionKey);
        Path metaPath = getMetaPath(sessionKey);
        boolean sessionFileExists = Files.exists(sessionPath);
        if (!Files.exists(metaPath)) {
            // meta 缺失：无 homeUrl/时间戳，文件不可作为有效 session → 不缓存负结果，返回 null
            return null;
        }
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(metaPath, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (Exception e) {
            LOGGER.warn("[SessionManager] Failed to load meta for {} → treating as no cache entry", sessionKey, e);
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
        return new SessionMeta(homeUrl, lastAccessTime, sessionFileExists);
    }

    // ==================== Feature 级别 Session 缓存 ====================
    // 用于支持 serenity.playwright.restart.browser.for.each=feature 配置
    // 确保同一个 Feature 中只恢复一次 Session，避免重复重建 Context

    // 记录当前 Feature 已恢复的 Session Key（ T3-1 收拢：由 static ThreadLocal 迁入 TestContext，per-thread 等价）
    private static final ContextKey<String> CURRENT_FEATURE_SESSION_KEY =
            ContextKey.of("sessionManager.currentFeatureSessionKey", String.class);

    // 标记当前 Feature 是否已经恢复了 Session
    // （ T3-1 收拢：原 withInitial(() -> false) 的默认 false 语义由读取侧 Boolean.TRUE.equals /
    //  restored != null 双重 null 守卫等价保证，未设值时返回 null 与 false 行为一致）
    private static final ContextKey<Boolean> FEATURE_SESSION_RESTORED =
            ContextKey.of("sessionManager.featureSessionRestored", Boolean.class);

    // 记录当前 Feature 已恢复的 Session 的 homeUrl（ T3-1 收拢：由 static ThreadLocal 迁入 TestContext）
    private static final ContextKey<String> CURRENT_FEATURE_HOME_URL =
            ContextKey.of("sessionManager.currentFeatureHomeUrl", String.class);

    /**
     * 检查当前 Feature 是否有任何 Session 被恢复/保存过。
     * <p>用于 Feature 模式下判断业务层是否使用了 SessionManager：
     * 若未曾使用，cleanupForScenario 应销毁 Context 而非保留 Cookie。
     */
    public static boolean isAnyFeatureSessionRestored() {
        return Boolean.TRUE.equals(TestContextHolder.get().get(FEATURE_SESSION_RESTORED));
    }

    /**
     * 标记 Feature 级别 Session 已恢复
     * <p>
     * 当第一个 Scenario 成功恢复 Session 后，调用此方法标记
     * 后续同一个 Feature 中的 Scenario 会直接复用，不再重建 Context
     *
     * @param sessionKey Session 标识
     * @param homeUrl 首页 URL
     */
    public static void markFeatureSessionRestored(String sessionKey, String homeUrl) {
        TestContextHolder.get().set(CURRENT_FEATURE_SESSION_KEY, sessionKey);
        TestContextHolder.get().set(FEATURE_SESSION_RESTORED, true);
        TestContextHolder.get().set(CURRENT_FEATURE_HOME_URL, homeUrl);
        VerboseLogging.logInfoIfVerbose(LOGGER,
            "Feature-level session marked as restored: {} (homeUrl: {})", sessionKey, homeUrl);
    }

    /**
     * 检查当前 Feature 是否已恢复指定的 Session
     * <p>
     * 用于避免在同一个 Feature 中重复恢复 Session 导致 Context 重建
     *
     * @param sessionKey Session 标识
     * @return true 表示当前 Feature 已恢复该 Session，可以直接复用
     */
    public static boolean isFeatureSessionRestored(String sessionKey) {
        Boolean restored = TestContextHolder.get().get(FEATURE_SESSION_RESTORED);
        String currentKey = TestContextHolder.get().get(CURRENT_FEATURE_SESSION_KEY);

        if (restored != null && restored && sessionKey.equals(currentKey)) {
            VerboseLogging.logInfoIfVerbose(LOGGER,
                "Feature-level session already restored for: {}, skipping restore", sessionKey);
            return true;
        }
        return false;
    }

    /**
     * 获取当前 Feature 已恢复 Session 的 homeUrl
     *
     * @return homeUrl，如果未恢复则返回 null
     */
    public static String getFeatureHomeUrl() {
        return TestContextHolder.get().get(CURRENT_FEATURE_HOME_URL);
    }

    /**
     * 【简化API】获取 homeUrl（自动处理 Feature 缓存和 meta 文件读取）
     * <p>
     * 封装了 homeUrl 的获取逻辑：
     * 1. 优先从 Feature 级别缓存读取（同一个 Feature 中已恢复的 Session）
     * 2. 如果缓存未命中，从 meta 文件读取
     * <p>
     * 业务层只需调用此方法，无需关心 homeUrl 的来源
     *
     * @param sessionKey Session 标识
     * @return homeUrl，如果不存在则返回 null
     */
    public static String getHomeUrl(String sessionKey) {
        // 优先从 Feature 级别缓存读取
        String homeUrl = getFeatureHomeUrl();
        if (homeUrl != null && !homeUrl.isEmpty()) {
            VerboseLogging.logInfoIfVerbose(LOGGER,
                "HomeUrl loaded from Feature cache: {}", homeUrl);
            return homeUrl;
        }

        // 缓存未命中，从 meta 文件读取
        homeUrl = loadHomeUrl(sessionKey);
        if (homeUrl != null && !homeUrl.isEmpty()) {
            VerboseLogging.logInfoIfVerbose(LOGGER,
                "HomeUrl loaded from meta file: {}", homeUrl);
        }

        return homeUrl;
    }

    /**
     * 重置 Feature 级别 Session 状态
     * <p>
     * 在 Feature 结束时调用，清理 ThreadLocal 变量
     */
    public static void resetFeatureSession() {
        VerboseLogging.logInfoIfVerbose(LOGGER, "Resetting feature-level session state");
        String featureKey = TestContextHolder.get().get(CURRENT_FEATURE_SESSION_KEY);
        TestContextHolder.get().remove(CURRENT_FEATURE_SESSION_KEY);
        //  修复 H11（防御）：Feature 结束时清理可能残留的单飞守卫，避免跨 Feature 的静态 Map 条目堆积
        // （正常成功路径已由 completeLoginGuard 在 saveSession 内移除，此处为异常/未落盘路径兜底）。
        if (featureKey != null) {
            loginGuards.remove(featureKey);
        }
        TestContextHolder.get().remove(FEATURE_SESSION_RESTORED);
        TestContextHolder.get().remove(CURRENT_FEATURE_HOME_URL);
    }

    /**
     * 【新】检查 Session 是否存在且有效
     * <p>
     * 框架层自动调用此方法检查 session 状态
     *
     * @param sessionKey Session 标识（如 "O63_SIT1_WP7UAT2_2"）
     * @return true 表示 session 文件存在且未过期，false 表示需要登录
     */
    /**
     * 检查是否存在有效的 Session。
     *
     * <p>纯内存优先：homeUrl/lastAccessTime/exists 均来自 {@link #META_CACHE}，命中时为零磁盘读；
     * 仅冷启动 .meta 未命中会同步读盘一次（本地 target/.sessions 极小文件，挂起概率可忽略）。
     * 原 IO 超时守卫线程池（SESSION_IO_EXECUTOR）已移除——见类顶部说明。
     */
    private static boolean hasSession(String sessionKey) {
        SessionMeta meta = loadSessionMeta(sessionKey);
        if (meta == null || !meta.sessionFileExists) {
            VerboseLogging.logInfoIfVerbose(LOGGER, "Session file not found: {}", sessionKey);
            return false;
        }

        // 过期则驱逐（删除磁盘文件 + 失效缓存）后视为无 session
        if (evictIfExpired(sessionKey)) {
            return false;
        }

        VerboseLogging.logInfoIfVerbose(LOGGER, "Valid session found for: {}", sessionKey);
        return true;
    }

    /**
     * 检查并驱逐过期 session（"失效即删除"的集中收口）。
     * <p>若 meta 指示已过期：删除 {@code .json} 与 {@code .meta} 磁盘文件、失效内存缓存，返回 {@code true}；
     * 否则返回 {@code false}。供 {@link #hasSession(String)} 与 {@link #loadHomeUrl(String)}
     * 等所有读取入口复用，确保无论走哪条路径，过期 session 最终都会被清理（满足"失效需要删除"）。
     */
    private static boolean evictIfExpired(String sessionKey) {
        SessionMeta meta = loadSessionMeta(sessionKey);
        if (meta == null || !meta.sessionFileExists) {
            return false;
        }
        if (isSessionExpired(sessionKey)) {
            VerboseLogging.logInfoIfVerbose(LOGGER, "Session expired for: {}", sessionKey);
            // 清除过期的 session 并失效内存缓存
            try {
                Files.delete(getSessionPath(sessionKey));
                Files.delete(getMetaPath(sessionKey));
            } catch (Exception e) {
                LOGGER.warn("Failed to delete expired session: {}", sessionKey, e);
            }
            META_CACHE.invalidate(sessionKey);
            return true;
        }
        return false;
    }

    /**
     * 【新】准备 Session（框架层自动处理）
     * <p>
     * 此方法是框架层入口，负责：
     * 1. 检查 session 文件是否存在且未过期
     * 2. 如果存在，读取 homeUrl 并通过 PlaywrightManager 设置 storageStatePath
     * 3. 框架会自动延迟Context/Page创建（避免不必要的创建和销毁）
     * 4. 如果不存在或过期，等待业务层登录后调用 saveCurrentSession()
     * <p>
     * 自定义配置机制：
     * - storageStatePath 是用户自定义配置，优先级高于框架默认配置
     * - 通过 customContextOptionsFlag 标志控制是否应用自定义配置
     * - 业务层通过 PlaywrightManager.customOptions().setXXX() 设置自定义配置
     * - 框架在 createContext() 时检查标志并应用自定义配置
     * <p>
     * 职责划分：
     * - 业务层：只传递 session key，执行登录逻辑
     * - 框架层：检查 session、设置 storageStatePath（自定义配置）、保存 session
     *
     * @param sessionKey Session 标识（如 "O63_SIT1_WP7UAT2_2"）
     * @return true 表示 session 已准备好，false 表示需要登录
     */
    public static boolean restoreSession(String sessionKey) {
        String restartStrategy = PlaywrightManager.config().getRestartStrategy();
        
        if ("feature".equalsIgnoreCase(restartStrategy)) {
            // 同一 sessionKey 已在本 Feature 恢复 → 直接复用当前 Context，不清理、不重建
            if (isFeatureSessionRestored(sessionKey)) {
                String homeUrl = getFeatureHomeUrl();
                VerboseLogging.logInfoIfVerbose(LOGGER,
                    "Feature-level session cache hit: {} (homeUrl: {})", sessionKey, homeUrl);
                return true;
            }
            //  Feature 模式遇到不同 env/user（不同 sessionKey）：不能再复用上一个 session 的 Context。
            //    先丢弃当前 Context（保留 custom options），并清除过期 storageStatePath，使后续重建
            //    从干净起点开始；随后按"缓存是否有此 key"分流：【命中→加载缓存】或【未命中→走登录】。
            if (PlaywrightManager.hasContext()
                    && Boolean.TRUE.equals(TestContextHolder.get().get(FEATURE_SESSION_RESTORED))
                    && !sessionKey.equals(TestContextHolder.get().get(CURRENT_FEATURE_SESSION_KEY))) {
                VerboseLogging.logInfoIfVerbose(LOGGER,
                        "Feature mode: sessionKey {} differs from restored — discarding current Context",
                        sessionKey);
                PlaywrightManager.discardCurrentContext();
                // 清除上一个 user 的 storageState（路径 + 内存 JSON），避免重建/登录时误加载旧 session
                PlaywrightManager.customOptions().setStorageStatePath(null);
                PlaywrightManager.customOptions().setStorageState(null);
            }
        } else {
            VerboseLogging.logDebugIfVerbose(LOGGER,
                "Scenario mode: skipping feature-level cache for {}", sessionKey);
        }

        if (hasSession(sessionKey)) {
            String homeUrl = loadHomeUrl(sessionKey);

            if (homeUrl != null && !homeUrl.isEmpty()) {
                //  命中：从内存内容缓存取 storageState JSON 直接传给 Playwright（零文件 IO）；
                //    加载失败则回退到 canonical 文件路径。
                // - 内部 scheduleContextRebuild() 会立即关闭可能存在的旧 Context（不同 user 不再复用）；
                // - 随后立即 getContext() 重建并加载内存 storageState，使 session 在 return 前即生效
                //   （满足"立即应用到当前，而非等下次 getContext 重建"的诉求）。
                String storageStateJson = getStorageStateContent(sessionKey);
                if (storageStateJson != null) {
                    PlaywrightManager.customOptions().setStorageState(storageStateJson);
                } else {
                    PlaywrightManager.customOptions().setStorageStatePath(getSessionPath(sessionKey));
                }
                PlaywrightManager.getContext();

                // 标记 Feature 级别 Session 已恢复（更新为当前 sessionKey，供后续 scenario 复用）
                if ("feature".equalsIgnoreCase(restartStrategy)) {
                    markFeatureSessionRestored(sessionKey, homeUrl);
                }

                VerboseLogging.logInfoIfVerbose(LOGGER,
                    "Session prepared for: {} (custom storageStatePath: {})", sessionKey, getSessionPath(sessionKey));
                return true;
            } else {
                VerboseLogging.logWarnIfVerbose(LOGGER,
                    "Session file exists but no homeUrl found: {}", sessionKey);
                return false;
            }
        } else {
            VerboseLogging.logInfoIfVerbose(LOGGER,
                "No valid session for: {}, waiting for login", sessionKey);

            //  单飞协调：同 sessionKey 并发"未命中"时只允许一个线程真实登录，
            //   否则两个线程都会看到"无 session 文件"而各自登录，触发服务端单会话策略把对端踢下线。
            LoginGuard guard = acquireOrAwait(sessionKey);
            if (guard == null) {
                // 本线程是 leader：返回 false 交由业务层登录，
                // 登录成功后 saveSession() 会释放守卫并唤醒 follower。
                return false;
            }

            // follower：leader 已结束（成功落盘 / 失败 / 超时）
            if (guard.isSuccess() && hasSession(sessionKey)) {
                String leaderHomeUrl = loadHomeUrl(sessionKey);
                if (leaderHomeUrl != null && !leaderHomeUrl.isEmpty()) {
                    //  与命中路径一致：取内存内容缓存，直接传 JSON（立即应用，零文件 IO）；失败回退文件
                    String storageStateJson = getStorageStateContent(sessionKey);
                    if (storageStateJson != null) {
                        PlaywrightManager.customOptions().setStorageState(storageStateJson);
                    } else {
                        PlaywrightManager.customOptions().setStorageStatePath(getSessionPath(sessionKey));
                    }
                    PlaywrightManager.getContext();
                    if ("feature".equalsIgnoreCase(restartStrategy)) {
                        markFeatureSessionRestored(sessionKey, leaderHomeUrl);
                    }
                    VerboseLogging.logInfoIfVerbose(LOGGER,
                        "Reusing session persisted by single-flight leader: {}", sessionKey);
                    return true;
                }
            }

            // leader 登录失败或落盘不可读 → 摘除失效守卫，本线程接替为 leader 自行登录
            loginGuards.remove(sessionKey, guard);
            VerboseLogging.logWarnIfVerbose(LOGGER,
                "Single-flight leader did not produce a usable session for {} — this thread will login", sessionKey);
            return false;
        }
    }

    /**
     * 单飞协调：为同一 sessionKey 竞争"登录权"。
     *
     * @return {@code null} 表示本线程是 leader（应执行真实登录，并在成功后调用
     *         {@link #saveSession(String, String)} 释放守卫）；非 null 表示本线程是 follower
     *         且已等到 leader 结束，调用方需检查 {@link LoginGuard#isSuccess()} 与 session 可用性。
     */
    private static LoginGuard acquireOrAwait(String sessionKey) {
        if (sessionKey == null) {
            return null;
        }
        LoginGuard candidate = new LoginGuard();
        LoginGuard existing = loginGuards.putIfAbsent(sessionKey, candidate);
        if (existing == null) {
            return null; // 本线程是 leader
        }

        boolean completed = existing.await(SINGLE_FLIGHT_TIMEOUT_MS);
        if (!completed) {
            // leader 超时未落盘（登录失败/被中断/业务层未调 saveSession）：
            // 摘除失效守卫，避免后续线程被一个已死的守卫永久阻塞；本线程接替为 leader。
            loginGuards.remove(sessionKey, existing);
            LOGGER.warn("[SessionManager] Timed out {}ms waiting for concurrent login of sessionKey={} "
                    + "→ proceeding as leader", SINGLE_FLIGHT_TIMEOUT_MS, sessionKey);
            return null;
        }
        return existing; // follower：leader 已结束
    }

    /**
     * 释放单飞守卫并唤醒所有等待同一 sessionKey 的 follower。
     *
     * @param sessionKey session 标识
     * @param success    leader 是否成功落盘 session
     */
    private static void completeLoginGuard(String sessionKey, boolean success) {
        if (sessionKey == null) {
            return;
        }
        LoginGuard guard = loginGuards.remove(sessionKey);
        if (guard != null) {
            guard.complete(success);
        }
    }

    /**
     * 【新】保存当前 Context 的 Session
     * <p>
     * 此方法由业务层在登录成功后调用，框架会自动：
     * 1. 保存 Playwright storageState 到文件
     * 2. 保存元数据（homeUrl + timestamp）
     * <p>
     * 使用示例：
     * <pre>
     * // 登录成功后，业务层调用
     * SessionManager.saveCurrentSession("O63_SIT1_WP7UAT2_2", homeUrl);
     * </pre>
     *
     * @param sessionKey Session 标识（如 "O63_SIT1_WP7UAT2_2"）
     * @param homeUrl 登录成功后的首页 URL
     */
    public static void saveSession(String sessionKey, String homeUrl) {
        try {
            Path sessionPath = getSessionPath(sessionKey);

            // 确保目录存在
            if (!Files.exists(sessionPath.getParent())) {
                Files.createDirectories(sessionPath.getParent());
            }

            VerboseLogging.logInfoIfVerbose(LOGGER,
                "Saving session for: {} (homeUrl: {})", sessionKey, homeUrl);

            // 获取当前 context
            BrowserContext context = PlaywrightManager.getContext();

            if (context == null) {
                throw new IllegalStateException("No context available for saving session");
            }

            // 使用 Playwright API 保存 storageState 到 canonical 文件（跨 JVM / 缓存 miss 的真相源）
            context.storageState(new BrowserContext.StorageStateOptions().setPath(sessionPath));
            //  同时把 storageState 内容（JSON 字符串）写入内存内容缓存：
            //    后续 restoreSession 命中时直接传内存 JSON 给 Playwright，零文件 IO。
            String storageStateJson = context.storageState();
            STORAGE_CONTENT_CACHE.put(sessionKey, storageStateJson);

            // 保存元数据（homeUrl + timestamp）
            saveMeta(sessionKey, homeUrl);

            //  内存缓存：落盘后立即用新鲜值刷新，避免后续读盘并保持一致
            META_CACHE.put(sessionKey, new SessionMeta(homeUrl, System.currentTimeMillis(), true));

            // 【关键】标记 Feature 级别 Session 已保存（后续 Scenario 直接复用）
            markFeatureSessionRestored(sessionKey, homeUrl);

            VerboseLogging.logInfoIfVerbose(LOGGER,
                "Session saved successfully: {} -> {}", sessionKey, sessionPath);

            //  释放单飞守卫：唤醒等待同一 sessionKey 的并发线程复用刚落盘的 storageState
            completeLoginGuard(sessionKey, true);
        } catch (Exception e) {
            // 登录/落盘失败同样必须释放守卫，否则 follower 会一直阻塞到 SINGLE_FLIGHT_TIMEOUT_MS
            completeLoginGuard(sessionKey, false);
            LOGGER.error("Failed to save session for: {}", sessionKey, e);
            throw new RuntimeException("Failed to save session", e);
        }
    }

    /**
     * 【新】清除指定的 Session
     * <p>
     * 此方法用于清除指定用户的 session，包括：
     * 1. 删除 session storageState 文件
     * 2. 删除 session 元数据文件
     * <p>
     * 使用场景：
     * - 用户登出时清除 session
     * - 需要强制重新登录时清除 session
     * - 测试清理时清除 session
     * <p>
     * 使用示例：
     * <pre>
     * // 登出时清除 session
     * SessionManager.clearSession("O63_SIT1_WP7UAT2_2");
     * </pre>
     *
     * @param sessionKey Session 标识（如 "O63_SIT1_WP7UAT2_2"）
     * @return true 表示清除成功，false 表示 session 不存在或清除失败
     */
    public static boolean clearSession(String sessionKey) {
        try {
            //  单飞守卫兜底：session 被显式清除后，在途 leader 即将落盘的 storageState
            //    对应的正是这份被删的 session，等待它已无意义。先释放守卫（标记失败），
            //    让 follower 立即自行登录，而不是白等到 SINGLE_FLIGHT_TIMEOUT_MS。
            completeLoginGuard(sessionKey, false);

            //  内存缓存失效：文件即将被删除，避免后续命中陈旧快照
            META_CACHE.invalidate(sessionKey);
            //  内存内容缓存（storageState JSON）同步失效
            STORAGE_CONTENT_CACHE.invalidate(sessionKey);

            Path sessionPath = getSessionPath(sessionKey);
            Path metaPath = getMetaPath(sessionKey);
            
            boolean sessionDeleted = false;
            boolean metaDeleted = false;
            
            // 删除 session 文件
            if (Files.exists(sessionPath)) {
                Files.delete(sessionPath);
                sessionDeleted = true;
                VerboseLogging.logInfoIfVerbose(LOGGER, 
                    "Session file deleted: {}", sessionPath);
            }
            
            // 删除 meta 文件
            if (Files.exists(metaPath)) {
                Files.delete(metaPath);
                metaDeleted = true;
                VerboseLogging.logInfoIfVerbose(LOGGER, 
                    "Meta file deleted: {}", metaPath);
            }
            
            // 只要有一个文件被删除就返回 true
            boolean cleared = sessionDeleted || metaDeleted;
            
            if (cleared) {
                LOGGER.info("Session cleared successfully: {}", sessionKey);
            } else {
                VerboseLogging.logInfoIfVerbose(LOGGER, 
                    "No session found to clear: {}", sessionKey);
            }
            
            return cleared;
        } catch (Exception e) {
            LOGGER.error("Failed to clear session for: {}", sessionKey, e);
            return false;
        }
    }

    /**
     * 【新】清除所有 Session
     * <p>
     * 此方法用于清除所有用户的 session，适用于：
     * - 测试环境清理
     * - 批量登出
     * <p>
     * 使用示例：
     * <pre>
     * // 清理所有 session
     * int count = SessionManager.clearAllSessions();
     * System.out.println("Cleared " + count + " sessions");
     * </pre>
     *
     * @return 清除的 session 数量
     */
    public static int clearAllSessions() {
        try {
            Path sessionDir = Paths.get(SESSION_DIR);
            if (!Files.exists(sessionDir)) {
                return 0;
            }

            // 单次遍历：收集基础名并删除文件
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

    /**
     * 【新】加载 HomeUrl
     * <p>
     * 从 meta 文件加载 homeUrl
     *
     * @param sessionKey Session 标识
     * @return homeUrl，如果不存在返回 null
     */
    /**
     * 读取 Session 的 homeUrl。
     *
     * <p>纯内存优先：homeUrl 来自 {@link #META_CACHE}（命中时零磁盘读）；仅冷启动 .meta 未命中
     * 会同步读盘一次。原 IO 超时守卫线程池（SESSION_IO_EXECUTOR）已移除——见类顶部说明。
     */
    public static String loadHomeUrl(String sessionKey) {
        // 先驱逐过期 session（失效即删除），避免返回陈旧 homeUrl
        if (evictIfExpired(sessionKey)) {
            return null;
        }
        SessionMeta meta = loadSessionMeta(sessionKey);
        return (meta != null) ? meta.homeUrl : null;
    }

    /**
     * 【新】检查 Session 是否过期
     */
    private static boolean isSessionExpired(String sessionKey) {
        SessionMeta meta = loadSessionMeta(sessionKey);
        if (meta == null) {
            return true;
        }
        long currentTime = System.currentTimeMillis();
        long elapsedMinutes = (currentTime - meta.lastAccessTime) / (60 * 1000);
        return elapsedMinutes > SESSION_TIMEOUT_MINUTES;
    }

    /**
     * 【新】获取 Session 文件路径
     */
    private static Path getSessionPath(String sessionKey) {
        return Paths.get(SESSION_DIR, sessionKey + ".json");
    }

    /**
     * 【新】获取 Meta 文件路径
     * <p>
     * 使用 target/.sessions 目录
     *
     * @param sessionKey Session 标识
     * @return Meta 文件路径
     */
    private static Path getMetaPath(String sessionKey) {
        return Paths.get(SESSION_DIR, sessionKey + ".meta");
    }

    /**
     * 【新】保存元数据
     * <p>
     * 保存 homeUrl 和 timestamp 到 meta 文件
     *
     * @param sessionKey Session 标识
     * @param homeUrl 首页 URL
     */
    private static void saveMeta(String sessionKey, String homeUrl) {
        try {
            Path metaPath = getMetaPath(sessionKey);
            Properties props = new Properties();

            // 如果文件已存在，先读取现有数据
            if (Files.exists(metaPath)) {
                try (var reader = Files.newBufferedReader(metaPath, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
            }

            // 更新 homeUrl
            if (homeUrl != null && !homeUrl.isEmpty()) {
                props.setProperty("homeUrl", homeUrl);
            }

            // 更新时间戳
            props.setProperty("lastAccessTime", String.valueOf(System.currentTimeMillis()));

            // 写入文件
            try (var writer = Files.newBufferedWriter(metaPath, StandardCharsets.UTF_8)) {
                props.store(writer, "Session Meta Data");
            }

            VerboseLogging.logDebugIfVerbose(LOGGER, "Meta saved: {} (homeUrl: {})", sessionKey, homeUrl);
        } catch (Exception e) {
            LOGGER.warn("Failed to save meta for: {}", sessionKey, e);
        }
    }
}

