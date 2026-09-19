package com.hsbc.cmb.hk.dbb.automation.framework.route.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-9（RT-C3）收口契约：FileStore 落盘按 scenario 隔离，并行 scenario 不串号 / 不串目录。
 *
 * <p>回归点：旧实现持有全局 {@code counters} 与全局 {@code currentScenarioKey}，B 场景切换时
 * {@code resolveTargetDir()} 内 {@code counters.clear()} 会清空<b>正在运行</b>的 A 场景序号，导致
 * 跨场景串号 / 串目录（A 的第三条被重置为 {@code endpoint_0} 覆盖自身）。新实现按 {@code scenarioKey}
 * 隔离（{@code scenarioStates} 映射 + 每场景独立计数器 / 子目录），无全局状态。
 *
 * <p>纯单测：绕过 serenity.properties，直接经反射注入启用状态 + 临时输出目录；scenarioKey 经
 * package-private 测试 seam {@link FileStoreMonitorCallback#writeForScenario} 直接注入，无需 Serenity 上下文。
 */
class FileStoreMonitorCallbackScenarioIsolationTest {

    @TempDir
    java.nio.file.Path tempDir;

    private FileStoreMonitorCallback cb;

    @BeforeEach
    void setUp() throws Exception {
        cb = FileStoreMonitorCallback.INSTANCE;
        cb.reset();
        setField("storeEnabled", true);
        setField("outputDir", tempDir.toFile());
        setField("pretty", false);
    }

    @AfterEach
    void tearDown() {
        cb.reset();
    }

    private void setField(String name, Object value) throws Exception {
        Field f = FileStoreMonitorCallback.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(cb, value);
    }

    /** 单线程 FIFO 写盘执行器：投哨兵任务，其执行即代表此前所有写任务已完成（保证不丢数据）。 */
    private void awaitWritesDone() throws Exception {
        Field f = FileStoreMonitorCallback.class.getDeclaredField("WRITE_EXECUTOR");
        f.setAccessible(true);
        ThreadPoolExecutor ex = (ThreadPoolExecutor) f.get(null);
        CountDownLatch latch = new CountDownLatch(1);
        ex.execute(latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS), "异步写盘应在 5s 内收敛");
    }

    @Test
    @DisplayName("RT-C3/P1-9 不同 scenario 写入独立目录，同一 endpoint 序号不跨场景串扰")
    void scenarioIsolation_distinctDirs_noCrossTalk() throws Exception {
        String endpoint = "api/users";
        int perScenario = 3;
        for (int i = 0; i < perScenario; i++) {
            cb.writeForScenario("scenario-A", endpoint, endpoint, 200, "{}", null, null, "GET");
            cb.writeForScenario("scenario-B", endpoint, endpoint, 200, "{}", null, null, "GET");
        }
        awaitWritesDone();

        File dirA = new File(tempDir.toFile(), "scenario-A");
        File dirB = new File(tempDir.toFile(), "scenario-B");
        assertTrue(dirA.isDirectory(), "scenario-A 应有独立子目录");
        assertTrue(dirB.isDirectory(), "scenario-B 应有独立子目录");

        //  A / B 各自目录内序号连续 0,1,2，互不串号
        assertFilePresent(dirA, "api_users.json");
        assertFilePresent(dirA, "api_users_1.json");
        assertFilePresent(dirA, "api_users_2.json");
        assertFilePresent(dirB, "api_users.json");
        assertFilePresent(dirB, "api_users_1.json");
        assertFilePresent(dirB, "api_users_2.json");

        //  不存在 _3.json（证明 B 未因与 A 共享全局计数器而把 A 推进到 _3）
        assertFileAbsent(dirA, "api_users_3.json");
        assertFileAbsent(dirB, "api_users_3.json");
    }

    @Test
    @DisplayName("RT-C3/P1-9 晚到的 scenario 切换不再重置正在运行的 scenario 计数（第三条仍为 _2）")
    void noGlobalCounterResetOnScenarioSwitch() throws Exception {
        //  旧实现：A 写 2 条 → B 切换触发 counters.clear() → A 回头写第 3 条被重置为 endpoint_0（覆盖）；
        //  新实现：A 计数器独立保留，第 3 条应为 api_orders_2.json。
        cb.writeForScenario("scenario-A", "api/orders", "api/orders", 200, "{}", null, null, "GET");
        cb.writeForScenario("scenario-A", "api/orders", "api/orders", 200, "{}", null, null, "GET");
        cb.writeForScenario("scenario-B", "api/orders", "api/orders", 200, "{}", null, null, "GET");
        cb.writeForScenario("scenario-A", "api/orders", "api/orders", 200, "{}", null, null, "GET");
        awaitWritesDone();

        File dirA = new File(tempDir.toFile(), "scenario-A");
        File dirB = new File(tempDir.toFile(), "scenario-B");
        assertFilePresent(dirA, "api_orders.json");
        assertFilePresent(dirA, "api_orders_1.json");
        assertFilePresent(dirA, "api_orders_2.json"); // A 第三条未因 B 切换被重置
        assertFilePresent(dirB, "api_orders.json");
    }

    @Test
    @DisplayName("RT-C3/P1-9 平铺模式（scenarioKey=null）仍保持 JVM 内累计序号（旧行为兼容）")
    void flatMode_accumulatesAcrossCalls() throws Exception {
        cb.writeForScenario(null, "api/flat", "api/flat", 200, "{}", null, null, "GET");
        cb.writeForScenario(null, "api/flat", "api/flat", 200, "{}", null, null, "GET");
        cb.writeForScenario(null, "api/flat", "api/flat", 200, "{}", null, null, "GET");
        awaitWritesDone();

        File root = tempDir.toFile();
        assertFilePresent(root, "api_flat.json");
        assertFilePresent(root, "api_flat_1.json");
        assertFilePresent(root, "api_flat_2.json");
    }

    private void assertFilePresent(File dir, String name) {
        File f = new File(dir, name);
        assertTrue(f.exists() && f.isFile(), "期望文件存在: " + f.getAbsolutePath());
    }

    private void assertFileAbsent(File dir, String name) {
        File f = new File(dir, name);
        assertFalse(f.exists(), "期望文件不存在（跨场景串号）: " + f.getAbsolutePath());
    }
}
