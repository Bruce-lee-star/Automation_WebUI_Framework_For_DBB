package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.microsoft.playwright.Page;
import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

/**
 * T3-1 收拢验证：{@link PlaywrightManager} 的 per-thread 生命周期状态
 * （原静态 {@code ThreadLocal<BrowserContext> contextThreadLocal}、
 * {@code ThreadLocal<Page> pageThreadLocal}、{@code ThreadLocal<String> currentConfigId}
 * 及 {@code ThreadLocal<Object> BROWSER_LOCK} 已迁入
 * {@link TestContextHolder}；包级可见的 ContextKey 供同包
 * {@link PlaywrightSerenityBridge} 等继续访问）。
 * <p>
 * 本测试经生产访问器 {@link PlaywrightManager#getPageThreadLocal()} 验证 Page 绑定按线程隔离，
 * 并验证 currentConfigId 同为按线程隔离（不启真实浏览器，Page 用 mock）。
 */
public class PlaywrightManagerConcurrencyTest {

    @Test
    public void perThreadPageIsolation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Page mockPage = mock(Page.class);
            TestContextHolder.get().set(PlaywrightManager.PAGE_KEY, mockPage);
            assertSame("主线程应读到本线程绑定的 Page", mockPage, PlaywrightManager.getPageThreadLocal());

            Future<Page> other = pool.submit(PlaywrightManager::getPageThreadLocal);
            assertNull("其他线程不应看到主线程绑定的 Page", other.get(5, TimeUnit.SECONDS));
        } finally {
            TestContextHolder.get().remove(PlaywrightManager.PAGE_KEY);
            pool.shutdown();
        }
    }

    @Test
    public void perThreadConfigIdIsolation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            TestContextHolder.get().set(PlaywrightManager.CURRENT_CONFIG_ID_KEY, "cfg-A");
            assertEquals("cfg-A", TestContextHolder.get().get(PlaywrightManager.CURRENT_CONFIG_ID_KEY));

            Future<String> other = pool.submit(() ->
                    TestContextHolder.get().get(PlaywrightManager.CURRENT_CONFIG_ID_KEY));
            assertNull("其他线程不应看到主线程的 configId", other.get(5, TimeUnit.SECONDS));
        } finally {
            TestContextHolder.get().remove(PlaywrightManager.CURRENT_CONFIG_ID_KEY);
            pool.shutdown();
        }
    }
}
