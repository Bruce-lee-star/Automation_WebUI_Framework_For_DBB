package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.core.RuntimeProvider;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务层下载查询公开 API 固化测试（走 WEB-P0-2 的 Provider 注入 seam，全程 mock，不启动真实浏览器）：
 * 验证 PlaywrightManager.getLastDownloadPath() / getLastDownloadFileName() / getDownloadPaths()
 * 能经 DownloadRegistry 正确返回当前上下文的下载记录，并在无记录 / 上下文未知时安全返回 null / 空。
 */
public class PlaywrightManagerDownloadQueryTest {

    private BrowserContext mockCtx;

    @AfterEach
    public void tearDown() {
        if (mockCtx != null) {
            DownloadRegistry.instance().clear(mockCtx);
        }
        PlaywrightManager.resetProvider();
    }

    @Test
    public void getLastDownloadPath_returnsRecordedPathForCurrentContext() {
        RuntimeProvider mockProvider = Mockito.mock(RuntimeProvider.class);
        mockCtx = Mockito.mock(BrowserContext.class);
        Path saved = Paths.get("target/downloads/report.xlsx").toAbsolutePath();
        Mockito.when(mockProvider.getContext()).thenReturn(mockCtx);

        PlaywrightManager.setProvider(mockProvider);
        DownloadRegistry.instance().record(mockCtx, saved);

        assertEquals(saved, PlaywrightManager.getLastDownloadPath());
        assertEquals("report.xlsx", PlaywrightManager.getLastDownloadFileName());
    }

    @Test
    public void getDownloadPaths_returnsAllForCurrentContext() {
        RuntimeProvider mockProvider = Mockito.mock(RuntimeProvider.class);
        mockCtx = Mockito.mock(BrowserContext.class);
        Path a = Paths.get("target/downloads/a.xlsx").toAbsolutePath();
        Path b = Paths.get("target/downloads/b.xlsx").toAbsolutePath();
        Mockito.when(mockProvider.getContext()).thenReturn(mockCtx);

        PlaywrightManager.setProvider(mockProvider);
        DownloadRegistry.instance().record(mockCtx, a);
        DownloadRegistry.instance().record(mockCtx, b);

        assertEquals(2, PlaywrightManager.getDownloadPaths().size());
    }

    @Test
    public void queries_returnNullSafeWhenNoDownload() {
        RuntimeProvider mockProvider = Mockito.mock(RuntimeProvider.class);
        mockCtx = Mockito.mock(BrowserContext.class);
        Mockito.when(mockProvider.getContext()).thenReturn(mockCtx);

        PlaywrightManager.setProvider(mockProvider);
        assertNull(PlaywrightManager.getLastDownloadPath());
        assertNull(PlaywrightManager.getLastDownloadFileName());
        assertTrue(PlaywrightManager.getDownloadPaths().isEmpty());
    }

    @Test
    public void queries_returnNullSafeWhenContextUnknown() {
        // provider.getContext() 返回 null：应安全返回 null / 空，不抛 NPE
        RuntimeProvider mockProvider = Mockito.mock(RuntimeProvider.class);
        Mockito.when(mockProvider.getContext()).thenReturn(null);

        PlaywrightManager.setProvider(mockProvider);
        assertNull(PlaywrightManager.getLastDownloadPath());
        assertNull(PlaywrightManager.getLastDownloadFileName());
        assertTrue(PlaywrightManager.getDownloadPaths().isEmpty());
    }

    // ==================== 异步保存的等待 API（P1-17 后新增） ====================

    /**
     * 异步保存的等待语义：登记发生在「另一个线程」（保存工作线程），调用线程必须能等到它，
     * 且用基线数量的方式不会误读到上一次下载的旧值。
     */
    @Test
    public void awaitLastDownloadPath_waitsForAsyncRegistration() throws Exception {
        RuntimeProvider mockProvider = Mockito.mock(RuntimeProvider.class);
        mockCtx = Mockito.mock(BrowserContext.class);
        Mockito.when(mockProvider.getContext()).thenReturn(mockCtx);
        PlaywrightManager.setProvider(mockProvider);

        Path saved = Paths.get("target/downloads/thread-9/async.xlsx").toAbsolutePath();
        Thread saver = new Thread(() -> {
            try {
                // 模拟"保存耗时"（登记晚于 waitForDownload 返回）；与本仓等待方式一致，不调 Thread.sleep
                new CountDownLatch(1).await(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            DownloadRegistry.instance().record(mockCtx, saved);
        }, "fake-download-save");
        saver.setDaemon(true);
        saver.start();

        assertEquals(0, PlaywrightManager.getDownloadCount(), "触发前基线应为 0");
        assertEquals(saved, PlaywrightManager.awaitLastDownloadPath(5000),
                "应等到异步保存登记完成并返回该路径");
        assertEquals(1, PlaywrightManager.getDownloadCount());
        saver.join(1000);
    }

    /** 超时未登记：不抛异常，返回 false / null（调用方据此判失败，而非 NPE）。 */
    @Test
    public void awaitDownload_returnsFalseAndNullOnTimeout() {
        RuntimeProvider mockProvider = Mockito.mock(RuntimeProvider.class);
        mockCtx = Mockito.mock(BrowserContext.class);
        Mockito.when(mockProvider.getContext()).thenReturn(mockCtx);
        PlaywrightManager.setProvider(mockProvider);

        assertTrue(!PlaywrightManager.awaitDownloadCount(1, 200), "超时应返回 false");
        assertNull(PlaywrightManager.awaitLastDownloadPath(200), "超时应返回 null");
    }

    /** 基线语义：已有 1 条记录时，awaitLastDownloadPath 不会把旧记录当成本次下载返回。 */
    @Test
    public void awaitLastDownloadPath_ignoresPreviousDownload() {
        RuntimeProvider mockProvider = Mockito.mock(RuntimeProvider.class);
        mockCtx = Mockito.mock(BrowserContext.class);
        Mockito.when(mockProvider.getContext()).thenReturn(mockCtx);
        PlaywrightManager.setProvider(mockProvider);

        DownloadRegistry.instance().record(mockCtx, Paths.get("target/downloads/old.xlsx").toAbsolutePath());

        assertNull(PlaywrightManager.awaitLastDownloadPath(200),
                "本次未触发新下载时应超时返回 null，而不是返回上一次的旧路径");
        assertTrue(PlaywrightManager.awaitDownloadCount(1, 200), "已登记数应满足 ≥1");
    }
}
