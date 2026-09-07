package com.hsbc.cmb.hk.dbb.automation.framework.web.config;

import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T3-1 收拢验证：{@link BrowserOverrideManager} 的 per-thread 浏览器覆盖与 Scenario 标签
 * （原静态 {@code ThreadLocal<String> overrideBrowserType} 与
 * {@code ThreadLocal<String[]> scenarioTags} 已迁入
 * {@link com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder}，
 * 默认 null 语义保持不变）。
 * 本测试证明两处状态按线程隔离：其他线程读不到主线程设置的值。
 */
public class BrowserOverrideManagerConcurrencyTest {

    @Test
    public void perThreadOverrideIsolation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            BrowserOverrideManager.setOverrideBrowser("firefox");
            assertTrue("主线程应设置浏览器覆盖", BrowserOverrideManager.hasOverride());
            Future<Boolean> otherSees = pool.submit(BrowserOverrideManager::hasOverride);
            assertFalse("其他线程不应看到主线程的浏览器覆盖", otherSees.get(5, TimeUnit.SECONDS));
        } finally {
            BrowserOverrideManager.clearAll();
            pool.shutdown();
        }
    }

    @Test
    public void perThreadScenarioTagsIsolation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            BrowserOverrideManager.setScenarioTags(new String[]{"@firefox"});
            assertNotNull("主线程应读到 Scenario 标签", BrowserOverrideManager.getScenarioTags());
            Future<String[]> other = pool.submit(BrowserOverrideManager::getScenarioTags);
            assertNull("其他线程不应看到主线程的 Scenario 标签", other.get(5, TimeUnit.SECONDS));
        } finally {
            BrowserOverrideManager.clearAll();
            pool.shutdown();
        }
    }
}
