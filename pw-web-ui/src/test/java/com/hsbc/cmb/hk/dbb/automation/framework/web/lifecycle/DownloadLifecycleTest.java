package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.microsoft.playwright.BrowserContext;
import org.junit.After;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * CT2-15 契约：下载生命周期登记的两种状态。
 *
 * <ol>
 *   <li><b>Context 关闭状态</b>：进入关闭流程后，保存侧据此<b>不再发起</b>注定失败的 {@code saveAs}
 *       （避免问题发生），并在事后按此已知状态归因，而不是解析 Playwright 的异常文案。</li>
 *   <li><b>按下载目录的在途保存数</b>：收尾删除目录前据此判定能否安全删除（非阻塞）。</li>
 * </ol>
 */
public class DownloadLifecycleTest {

    private static final Path DIR = Paths.get("target", "download-lifecycle-test", "thread-1");

    @After
    public void cleanup() {
        DownloadLifecycle.clearAll();
    }

    @Test
    // @DisplayName: "CT2-15：Context 关闭状态按身份标记，未标记的 context 不得被判为关闭"
    public void contextClosingStateIsPerContext() {
        BrowserContext closing = mock(BrowserContext.class);
        BrowserContext live = mock(BrowserContext.class);

        assertFalse("未标记即未关闭（避免误跳过正常保存）", DownloadLifecycle.isContextClosing(closing));

        DownloadLifecycle.markContextClosing(closing);
        assertTrue("已登记关闭流程的 context 必须被识别", DownloadLifecycle.isContextClosing(closing));
        assertFalse("其它 context 不得被连带判定为关闭", DownloadLifecycle.isContextClosing(live));
    }

    @Test
    // @DisplayName: "CT2-15：null 安全（未关闭 / 无在途），且 clearAll 复位"
    public void nullSafeAndResettable() {
        assertFalse("null 按『未关闭』处理", DownloadLifecycle.isContextClosing(null));
        assertEquals("null 目录视为无在途（收尾不阻塞）", 0, DownloadLifecycle.pendingCount(null));

        BrowserContext ctx = mock(BrowserContext.class);
        DownloadLifecycle.markContextClosing(ctx);
        DownloadLifecycle.begin(DIR);
        DownloadLifecycle.clearAll();
        assertFalse("clearAll 必须复位关闭状态", DownloadLifecycle.isContextClosing(ctx));
        assertEquals("clearAll 必须复位在途计数", 0, DownloadLifecycle.pendingCount(DIR));
    }

    @Test
    // @DisplayName: "CT2-15：按目录登记/注销、计数配对归零；未登记目录恒为 0（可安全删除）"
    public void tracksInFlightSavesPerDirectory() {
        assertEquals("未登记目录应为 0 → 收尾可直接删除", 0, DownloadLifecycle.pendingCount(DIR));

        DownloadLifecycle.begin(DIR);
        DownloadLifecycle.begin(DIR);
        assertEquals(2, DownloadLifecycle.pendingCount(DIR));

        DownloadLifecycle.end(DIR);
        assertEquals(1, DownloadLifecycle.pendingCount(DIR));
        DownloadLifecycle.end(DIR);
        assertEquals("计数必须配对归零（成功/失败/被取消都须注销，归零即移除键）", 0, DownloadLifecycle.pendingCount(DIR));
    }

    @Test
    // @DisplayName: "CT2-15：归属按【目录】而非线程 —— 其它线程登记的在该目录上的在途，收尾线程必须能看到"
    public void ownershipIsByDirectoryNotByThread() throws Exception {
        Thread worker = new Thread(() -> DownloadLifecycle.begin(DIR), "download-lifecycle-worker");
        worker.start();
        worker.join(5_000);

        assertEquals("收尾线程必须能看到其它线程登记的、落在该目录上的在途保存 —— "
                        + "若按线程 id 归属，此处会误判为 0 并删除未写完的文件（CT2-15 根因）", 1, DownloadLifecycle.pendingCount(DIR));

        DownloadLifecycle.end(DIR);
        assertEquals(0, DownloadLifecycle.pendingCount(DIR));
    }

    @Test
    // @DisplayName: "CT2-15：目录键归一化 —— 同一目录的不同写法必须命中同一条登记"
    public void directoryKeyIsNormalized() {
        Path normalized = DIR.toAbsolutePath().normalize();
        DownloadLifecycle.begin(normalized);
        assertEquals("不同写法（相对/绝对、含 . 或 ..）必须命中同一登记，否则收尾查不到在途写入", 1, DownloadLifecycle.pendingCount(DIR));
        DownloadLifecycle.end(DIR);
        assertEquals(0, DownloadLifecycle.pendingCount(normalized));
    }
}
