package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.DownloadLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.DownloadRegistry;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CT2-15 契约：<b>不让问题发生</b> —— Context 进入关闭流程后，框架不再为其发起
 * {@code download.saveAs(...)}（该保存必被 {@code close()} 取消）。
 *
 * <p>取代原「按异常文案分类」的做法：文案随 Playwright 版本变化（{@code TargetClosedError}、
 * {@code Cannot find object to call close} …），按文案分类必然要不断追补且会漏判。
 * 现在的判据是<b>框架已知状态</b>（{@link DownloadLifecycle#isContextClosing(BrowserContext)}），
 * 且绝大多数情况下保存根本不会被发起，因而没有「需要分类的异常」。</p>
 */
class DownloadSaveSkipOnClosingContextTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        DownloadLifecycle.clearAll();
    }

    @Test
    @DisplayName("CT2-15 预防：关闭中的 context 绝不发起 saveAs（不建目录、不占位、不登记）")
    void closingContextNeverStartsSave() throws Exception {
        BrowserContext context = mock(BrowserContext.class);
        Download download = mock(Download.class);
        Path downloadDir = tempDir.resolve("thread-1");

        DownloadLifecycle.markContextClosing(context);
        PlaywrightContextManager.saveDownloadAsync(context, download, downloadDir, 5_000);

        // after(...).never()：给异步任务足够时间证明它确实不会执行保存
        verify(download, after(800).never()).saveAs(any());
        verify(download, after(800).never()).suggestedFilename();
        assertFalse(Files.exists(downloadDir),
                "关闭中的 context 连下载目录都不应创建（保存注定被 close() 取消）");
        assertNull(DownloadRegistry.instance().last(context),
                "未保存成功就不得登记下载路径（否则业务会查到不存在的文件）");
    }

    @Test
    @DisplayName("CT2-15 对照：存活 context 正常保存并登记路径（不得因误判关闭而跳过）")
    void liveContextSavesAndRegisters() throws Exception {
        BrowserContext context = mock(BrowserContext.class);
        Browser browser = mock(Browser.class);
        when(context.browser()).thenReturn(browser);
        when(browser.isConnected()).thenReturn(true);
        Download download = mock(Download.class);
        when(download.suggestedFilename()).thenReturn("statement.txt");
        Path downloadDir = tempDir.resolve("thread-2");

        PlaywrightContextManager.saveDownloadAsync(context, download, downloadDir, 5_000);

        verify(download, timeout(3_000)).saveAs(any(Path.class));
        assertNotNull(waitForRecorded(context), "保存成功后必须登记下载路径，供业务查询");
        assertEquals(0, DownloadLifecycle.pendingCount(downloadDir),
                "任务结束（含成功）后必须注销在途计数，否则收尾永远跳过删除下载目录");
    }

    @Test
    @DisplayName("CT2-15：拿不到 Browser 不得被当成不可用 —— 持久化上下文（browser() 返回 null）必须照常保存")
    void nullBrowserDoesNotSuppressSave() throws Exception {
        BrowserContext context = mock(BrowserContext.class);
        // launchPersistentContext 的真实行为：browser() 返回 null，但上下文完全可用。
        // 若把「拿不到 Browser」当作不可用，该拓扑下所有下载会被静默跳过（比日志噪音严重得多）。
        when(context.browser()).thenReturn(null);
        Download download = mock(Download.class);
        when(download.suggestedFilename()).thenReturn("persist.txt");
        Path downloadDir = tempDir.resolve("thread-persistent");

        PlaywrightContextManager.saveDownloadAsync(context, download, downloadDir, 5_000);

        verify(download, timeout(3_000)).saveAs(any(Path.class));
    }

    @Test
    @DisplayName("CT2-15：Browser 已断开属实证不可用 → 不发起 saveAs（避免注定失败的调用）")
    void disconnectedBrowserSkipsSave() throws Exception {
        BrowserContext context = mock(BrowserContext.class);
        Browser browser = mock(Browser.class);
        when(context.browser()).thenReturn(browser);
        when(browser.isConnected()).thenReturn(false);
        Download download = mock(Download.class);
        Path downloadDir = tempDir.resolve("thread-dead");

        PlaywrightContextManager.saveDownloadAsync(context, download, downloadDir, 5_000);

        verify(download, after(800).never()).saveAs(any());
        assertFalse(Files.exists(downloadDir), "环境已不可用时不建目录、不占位（避免 0 字节残留）");
    }

    /** 轮询等待下载登记（保存为异步任务，登记发生在 saveAs 之后）。 */
    private Path waitForRecorded(BrowserContext context) {
        long deadline = System.nanoTime() + 3_000_000_000L;
        Path recorded = DownloadRegistry.instance().last(context);
        while (recorded == null && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            recorded = DownloadRegistry.instance().last(context);
        }
        return recorded;
    }
}
