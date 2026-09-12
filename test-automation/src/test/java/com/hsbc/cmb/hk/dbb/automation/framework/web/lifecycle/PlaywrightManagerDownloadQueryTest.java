package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.core.RuntimeProvider;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.nio.file.Paths;

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
}
