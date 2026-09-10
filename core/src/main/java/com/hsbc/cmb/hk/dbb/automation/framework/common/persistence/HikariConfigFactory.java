package com.hsbc.cmb.hk.dbb.automation.framework.common.persistence;

import com.hsbc.cmb.hk.dbb.automation.framework.common.ShutdownCoordinator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.util.function.Consumer;

/**
 * HikariCP 配置工厂（修复 L2：消除 {@code DatabaseUtil} 与 {@code ApiMonitoringRepository}
 * 中重复的 {@link HikariConfig} 样板，统一通用字段装配入口，避免两处连接池配置漂移）。
 *
 * <p>从 web 的 {@code framework.web.utils.HikariConfigFactory} 下沉到 core，供 route 等模块复用，
 * 解除 route 对 web 的依赖。行为与原内联配置保持一致（未显式设置的字段沿用 Hikari 默认值）。
 *
 * <p>驱动类字符串由各调用方自行解析后传入（两处数据库类型枚举/字符串映射不同），
 * 本工厂只负责通用字段装配；调用方可经 {@code extra} 回调追加库特有属性
 * （如 MySQL 的 {@code rewriteBatchedStatements} / {@code cachePrepStmts}）。
 *
 * @apiNote 框架内部持久化使用；业务代码不应直接依赖。
 */
public final class HikariConfigFactory {

    private HikariConfigFactory() {
    }

    /** 构建参数（值语义，调用方按需填充）。 */
    public static final class Spec {
        public String jdbcUrl;
        public String username;
        public String password;
        public String driverClass;
        public int maxPoolSize;
        public int minIdle;
        /** 连接超时（毫秒）。 */
        public int connectionTimeoutMs;
        /**
         * 空闲超时（毫秒）。设为负数（推荐 {@code -1}）表示不显式设置，沿用 Hikari 默认值。
         * 原 DatabaseUtil 显式设 600000，原 ApiMonitoringRepository 未设（默认），以此区分。
         */
        public int idleTimeoutMs = -1;
        public int maxLifetimeMs;
        public String poolName;
    }

    /**
     * 按通用字段装配 {@link HikariConfig}，并允许调用方追加库特有属性。
     *
     * @param spec  通用配置（各字段由调用方保证有效）
     * @param extra 追加库特有属性的回调（可为 null）
     * @return 已装配完成的 HikariConfig（尚未建池）
     */
    public static HikariConfig build(Spec spec, Consumer<HikariConfig> extra) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(spec.jdbcUrl);
        config.setUsername(spec.username);
        config.setPassword(spec.password);
        // driverClass 为 null/空时交由 Hikari 按 jdbcUrl 经 JDBC SPI 自动探测 classpath 上的驱动，
        // 实现「测试层加哪个驱动依赖就用哪个」，框架不再硬编码/强制特定驱动。
        if (spec.driverClass != null && !spec.driverClass.isEmpty()) {
            config.setDriverClassName(spec.driverClass);
        }
        config.setMaximumPoolSize(spec.maxPoolSize);
        config.setMinimumIdle(spec.minIdle);
        config.setConnectionTimeout(spec.connectionTimeoutMs);
        if (spec.idleTimeoutMs >= 0) {
            config.setIdleTimeout(spec.idleTimeoutMs);
        }
        config.setMaxLifetime(spec.maxLifetimeMs);
        config.setPoolName(spec.poolName);
        if (extra != null) {
            extra.accept(config);
        }
        return config;
    }

    /**
     * 便捷方法：build 后直接建池，并在返回前登记到 {@link ShutdownCoordinator}。
     *
     * <p><b>致命缺陷2 修复</b>：原实现直接 {@code new HikariDataSource} 返回，调用方忘记
     * {@code close()} 即连接泄漏，JVM 退出亦无兜底；且 {@code ApiMonitoringRepository} 进程退出时
     * 仍 flush 队列，可能向监控库写入非预期批次。登记后由统一关闭编排在 JVM 退出时关闭。</p>
     *
     * @param spec  通用配置（各字段由调用方保证有效）
     * @param extra 追加库特有属性的回调（可为 null）
     * @return 已建池的数据源（JVM 退出时由 {@link ShutdownCoordinator} 关闭）
     */
    public static HikariDataSource createDataSource(Spec spec, Consumer<HikariConfig> extra) {
        HikariDataSource ds = new HikariDataSource(build(spec, extra));
        registerShutdown(ds);
        return ds;
    }

    /**
     * 登记数据源关闭任务（致命缺陷2 修复）。
     *
     * <p>关闭顺序定为 {@code 800}：晚于 API 监控 flush（100）/ route 引擎（200）/ monitor（300）/
     * session IO（500），早于框架核心（900）——确保监控 flush 完成后再关连接，避免"关闭后才 flush"
     * 导致的意外写库或连接泄漏。名称按实例 {@code identityHashCode} 唯一，保证多数据源各自登记、
     * 各自关闭；{@link HikariDataSource#close()} 幂等，调用方自行关闭亦无副作用。</p>
     *
     * @param ds 已建池的数据源
     */
    private static void registerShutdown(HikariDataSource ds) {
        String name = "hikari-datasource:" + System.identityHashCode(ds);
        ShutdownCoordinator.register(800, name, ds::close);
    }
}
