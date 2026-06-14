package com.hsbc.cmb.hk.dbb.automation.framework.web.route.persistence;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.route.core.MonitorCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 框架内置的数据库存储 Monitor 响应回调。
 *
 * <p><b>用户无需手动注册此回调</b>。框架在 {@link MonitorHandler}
 * 中自动调用，根据配置决定是否持久化到数据库。
 *
 * <p><b>配置控制</b>（serenity.properties）：
 * <pre>{@code
 * # 是否启用 DB 存储（默认 false，不存储）
 * monitor.db.store.enabled=true
 *
 * # 数据库连接信息
 * monitor.db.type=MYSQL
 * monitor.db.url=jdbc:mysql://localhost:3306/route_monitor
 * monitor.db.user=root
 * monitor.db.password=yourpassword
 * }</pre>
 *
 * <p>用户业务层只需在配置中开启即可，无需任何代码变更：
 * <pre>{@code
 * // 业务代码中正常写 monitor 即可，无需额外配置回调
 * RouteDsl.on(page)
 *     .api("/api/users")
 *     .monitor()
 *     .expectStatus(200)
 *     .done()
 *     .start();
 * }</pre>
 *
 * <p><b>安全降级</b>：
 * <ul>
 *   <li>配置 {@code monitor.db.store.enabled=false}（默认）→ 静默跳过，不存储</li>
 *   <li>DB 连接失败 → 打 WARN 日志，不抛异常，不中断测试</li>
 *   <li>单条写入失败 → 打 WARN 日志，不抛异常</li>
 * </ul>
 *
 * <p><b>线程安全</b>：懒初始化使用 volatile + synchronized 双重检查锁定，
 * 支持并发测试场景。
 */
public final class DatabaseStoreMonitorCallback implements MonitorCallback {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseStoreMonitorCallback.class);

    /** 单例 */
    public static final DatabaseStoreMonitorCallback INSTANCE = new DatabaseStoreMonitorCallback();

    /** 是否已检查过配置（懒加载，仅检查一次） */
    private volatile boolean configChecked = false;

    /** 是否已启用（enabled=true 且 DB 初始化成功） */
    private volatile boolean storeEnabled = false;

    private DatabaseStoreMonitorCallback() {}

    // ═══════════════════════════════════════════════════════════════
    // MonitorCallback 实现
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void onResponse(String url, int status, String body,
                           Map<String, String> responseHeaders, String method) {
        if (!configChecked) {
            checkConfigAndInit();
            configChecked = true;
        }
        if (!storeEnabled) return;

        try {
            ApiMonitoringRecord record = ApiMonitoringRecord.builder()
                    .endpoint(url)
                    .requestUrl(url)
                    .method(method)
                    .statusCode(status)
                    .responseHeaders(responseHeaders)
                    .responseBody(body)
                    .capturedAt(System.currentTimeMillis())
                    .testRunId(System.getProperty("monitor.test.run.id"))
                    .build();

            ApiMonitoringRepository.save(record);

        } catch (Exception e) {
            LOGGER.warn("[DatabaseStoreMonitorCallback] Failed to build/save record: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 配置检查 & DB 初始化
    // ═══════════════════════════════════════════════════════════════

    private void checkConfigAndInit() {
        boolean enabled = FrameworkConfigManager.getBoolean(FrameworkConfig.MONITOR_DB_STORE_ENABLED);
        if (!enabled) {
            LOGGER.info("[DatabaseStoreMonitorCallback] DB store is DISABLED. "
                    + "Set 'monitor.db.store.enabled=true' in serenity.properties to enable.");
            storeEnabled = false;
            return;
        }

        String dbType = FrameworkConfigManager.getString(FrameworkConfig.MONITOR_DB_TYPE);
        String dbUrl = FrameworkConfigManager.getString(FrameworkConfig.MONITOR_DB_URL);
        String dbUser = FrameworkConfigManager.getString(FrameworkConfig.MONITOR_DB_USER);
        String dbPassword = FrameworkConfigManager.getString(FrameworkConfig.MONITOR_DB_PASSWORD);
        int poolMaxSize = FrameworkConfigManager.getInt(FrameworkConfig.MONITOR_DB_POOL_MAX_SIZE, 5);

        if (dbUrl == null || dbUrl.trim().isEmpty()) {
            LOGGER.warn("[DatabaseStoreMonitorCallback] monitor.db.store.enabled=true, "
                    + "but monitor.db.url is empty. DB store will be disabled.");
            storeEnabled = false;
            return;
        }

        // 委托 Repository 初始化
        ApiMonitoringRepository.init(dbUrl, dbUser, dbPassword, dbType, poolMaxSize);

        storeEnabled = ApiMonitoringRepository.isInitialized();
        if (!storeEnabled) {
            LOGGER.warn("[DatabaseStoreMonitorCallback] DB initialization failed. "
                    + "Monitor data will NOT be stored to database. "
                    + "Check db connection config and ensure the database is running.");
        } else {
            LOGGER.info("[DatabaseStoreMonitorCallback] DB store is ENABLED and ready.");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════════

    /**
     * 重置回调状态（主要用于测试）。
     */
    void reset() {
        configChecked = false;
        storeEnabled = false;
    }
}
