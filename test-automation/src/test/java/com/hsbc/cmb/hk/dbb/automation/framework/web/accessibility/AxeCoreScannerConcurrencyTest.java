package com.hsbc.cmb.hk.dbb.automation.framework.web.accessibility;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T3-1 收拢验证：{@link AxeCoreScanner} 的扫描状态（原静态
 * {@code ThreadLocal<List<AxeScanResult>> results}、{@code ThreadLocal<Boolean> initialized}、
 * {@code ThreadLocal<AxeScanConfig> config} 已迁入
 * {@link com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder}）。
 * 三者原本均为默认 null 语义，迁移后等价。
 * 本测试证明 initialize 后的状态按线程隔离。
 */
public class AxeCoreScannerConcurrencyTest {

    @Test
    public void perThreadScannerStateIsolation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            AxeCoreScanner.initialize();
            assertTrue("主线程应已初始化", AxeCoreScanner.isInitialized());
            assertNotNull("主线程应读到 config", AxeCoreScanner.getConfig());

            Future<Boolean> otherInit = pool.submit(AxeCoreScanner::isInitialized);
            Future<AxeCoreScanner.AxeScanConfig> otherCfg = pool.submit(AxeCoreScanner::getConfig);
            Future<List<AxeCoreScanner.AxeScanResult>> otherResults = pool.submit(AxeCoreScanner::getResults);

            assertFalse("其他线程不应看到已初始化", otherInit.get(5, TimeUnit.SECONDS));
            assertNull("其他线程不应看到 config", otherCfg.get(5, TimeUnit.SECONDS));
            assertNull("其他线程不应看到 results", otherResults.get(5, TimeUnit.SECONDS));
        } finally {
            AxeCoreScanner.cleanup();
            pool.shutdown();
        }
    }
}
