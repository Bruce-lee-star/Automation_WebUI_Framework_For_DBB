package com.hsbc.cmb.hk.dbb.automation.framework.common.persistence;

import com.hsbc.cmb.hk.dbb.automation.framework.common.ShutdownCoordinator;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link HikariConfigFactory} 单测（core 首批单测之一，整改 P1 / 维度7 易用性）。
 *
 * <p>验证致命缺陷2 修复：{@code createDataSource} 必须在返回前把数据源登记到
 * {@link ShutdownCoordinator}，使 JVM 退出期统一关闭，避免连接泄漏与意外写库。</p>
 *
 * <p>本测试在 core 独立测试 JVM 中执行，{@link ShutdownCoordinator#runAll()} 仅触发本任务，无副作用。</p>
 */
public class HikariConfigFactoryTest {

    @Test
    public void createDataSourceRegistersShutdownClose() {
        HikariConfigFactory.Spec spec = new HikariConfigFactory.Spec();
        spec.jdbcUrl = "jdbc:h2:mem:hikari_shutdown_" + UUID.randomUUID().toString().replace('-', '_');
        spec.username = "sa";
        spec.password = "";
        spec.maxPoolSize = 2;
        spec.minIdle = 1;
        spec.connectionTimeoutMs = 30_000;
        spec.maxLifetimeMs = 1_800_000;
        spec.poolName = "hikari-shutdown-test";

        HikariDataSource ds = HikariConfigFactory.createDataSource(spec, null);
        assertFalse("data source must be open right after creation", ds.isClosed());

        // 触发 JVM 退出期统一关闭编排
        ShutdownCoordinator.runAll();

        assertTrue("data source must be closed by ShutdownCoordinator on JVM exit", ds.isClosed());
    }

    @Test
    public void eachCreatedDataSourceGetsItsOwnShutdownTask() {
        HikariConfigFactory.Spec spec = new HikariConfigFactory.Spec();
        spec.jdbcUrl = "jdbc:h2:mem:hikari_shutdown_multi_" + UUID.randomUUID().toString().replace('-', '_');
        spec.username = "sa";
        spec.password = "";
        spec.maxPoolSize = 1;
        spec.minIdle = 0;
        spec.connectionTimeoutMs = 30_000;
        spec.maxLifetimeMs = 1_800_000;

        HikariDataSource first = HikariConfigFactory.createDataSource(spec, null);
        HikariDataSource second = HikariConfigFactory.createDataSource(spec, null);
        assertFalse(first.isClosed());
        assertFalse(second.isClosed());

        ShutdownCoordinator.runAll();

        assertTrue("first data source must be closed", first.isClosed());
        assertTrue("second data source must be closed independently", second.isClosed());
    }
}
