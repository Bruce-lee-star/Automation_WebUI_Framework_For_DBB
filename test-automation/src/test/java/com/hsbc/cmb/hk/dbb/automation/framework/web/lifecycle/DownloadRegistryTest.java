package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DownloadRegistry 纯单元测试：验证按 BrowserContext 隔离的下载记录（最近 / 全部 / 清理 / null 防御）。
 * 全程 mock，不启动真实浏览器。
 */
public class DownloadRegistryTest {

    @Test
    public void record_thenLast_returnsMostRecent() {
        BrowserContext ctx = Mockito.mock(BrowserContext.class);
        DownloadRegistry reg = DownloadRegistry.instance();
        reg.clear(ctx);
        Path a = Paths.get("target/downloads/a.xlsx");
        Path b = Paths.get("target/downloads/b.xlsx");
        reg.record(ctx, a);
        reg.record(ctx, b);
        assertEquals(b, reg.last(ctx));
        reg.clear(ctx);
    }

    @Test
    public void all_returnsChronologicalOrder() {
        BrowserContext ctx = Mockito.mock(BrowserContext.class);
        DownloadRegistry reg = DownloadRegistry.instance();
        reg.clear(ctx);
        Path a = Paths.get("target/downloads/a.xlsx");
        Path b = Paths.get("target/downloads/b.xlsx");
        reg.record(ctx, a);
        reg.record(ctx, b);
        List<Path> all = reg.all(ctx);
        assertEquals(2, all.size());
        assertEquals(a, all.get(0));
        assertEquals(b, all.get(1));
        reg.clear(ctx);
    }

    @Test
    public void lastAndAll_returnNullSafeWhenEmpty() {
        BrowserContext ctx = Mockito.mock(BrowserContext.class);
        DownloadRegistry reg = DownloadRegistry.instance();
        reg.clear(ctx);
        assertNull(reg.last(ctx));
        assertTrue(reg.all(ctx).isEmpty());
        reg.clear(ctx);
    }

    @Test
    public void contextsAreIsolated() {
        BrowserContext c1 = Mockito.mock(BrowserContext.class);
        BrowserContext c2 = Mockito.mock(BrowserContext.class);
        DownloadRegistry reg = DownloadRegistry.instance();
        reg.clear(c1);
        reg.clear(c2);
        reg.record(c1, Paths.get("target/downloads/c1.xlsx"));
        assertNull(reg.last(c2));
        assertEquals(Paths.get("target/downloads/c1.xlsx"), reg.last(c1));
        reg.clear(c1);
        reg.clear(c2);
    }

    @Test
    public void record_ignoresNullArgs() {
        DownloadRegistry reg = DownloadRegistry.instance();
        // 不应抛异常（防御性 null 安全）
        reg.record(null, Paths.get("x"));
        reg.record(Mockito.mock(BrowserContext.class), null);
    }
}
