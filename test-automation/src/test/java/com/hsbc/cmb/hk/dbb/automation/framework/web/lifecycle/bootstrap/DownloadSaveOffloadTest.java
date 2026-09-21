package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.DownloadRegistry;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回归守护（2026-09-21）：<b>下载保存必须卸载出「连接读线程」，监听器内绝不同步执行 {@code saveAs}</b>。
 *
 * <p><b>要防的回归</b>：Playwright 的 {@code onDownload} 事件在<b>连接读线程</b>上派发；若在该监听器内
 * 同步调用 {@code download.saveAs(...)}（同步等待下载完成 + 落盘），读线程会阻塞<b>自身</b> → 该连接上所有
 * CDP 命令排队（该 worker 的当前/后续 scenario 表现为「卡住 / 排队」），极端情况自死锁、整轮挂起。
 * 2026-09-08 曾修（专属 {@code DOWNLOAD_EXECUTOR}），但在改为「context 级一次注册」时该卸载被丢失
 * （复核：全仓只剩 {@code ConcurrentContextNoDeadlockTest} 的 javadoc 还提到它）。</p>
 *
 * <p><b>本测试怎么防</b>：用一个「慢 {@code saveAs} 桩」（睡 1.5s）直接调用生产的派发入口
 * {@link PlaywrightContextManager#saveDownloadAsync}，断言<b>派发本身毫秒级返回</b>（远小于桩的 1.5s）——
 * 一旦有人把 {@code saveAs} 改回监听器内同步执行，本测试立即变红。同时验证卸载后<b>保存仍然发生</b>且
 * 被登记进 {@link DownloadRegistry}（避免"为了不阻塞而干脆不存"）。</p>
 */
public class DownloadSaveOffloadTest {

    /** 慢 saveAs 桩的耗时（远大于派发应耗时间）。 */
    private static final long SLOW_SAVE_MS = 1500;

    /** 派发允许的最大耗时；若同步执行 saveAs 将≈{@link #SLOW_SAVE_MS}。 */
    private static final long DISPATCH_MAX_MS = 400;

    /** 「慢」延迟：用无人计数的 latch 等待替代 Thread.sleep（与本仓架构门禁的等待方式保持一致）。 */
    private static void slowDelay(long ms) throws InterruptedException {
        new CountDownLatch(1).await(ms, TimeUnit.MILLISECONDS);
    }

    @Test
    public void dispatchDoesNotBlockCallerEvenWhenSaveAsIsSlow() throws Exception {
        Path dir = Files.createTempDirectory("pw-dl-offload");
        CountDownLatch saveStarted = new CountDownLatch(1);
        CountDownLatch saveDone = new CountDownLatch(1);
        AtomicReference<Path> savedPath = new AtomicReference<>();

        Download download = mock(Download.class);
        when(download.suggestedFilename()).thenReturn("report.xlsx");
        doAnswer(inv -> {
            savedPath.set(inv.getArgument(0));
            saveStarted.countDown();
            slowDelay(SLOW_SAVE_MS);
            Files.createFile(savedPath.get());
            saveDone.countDown();
            return null;
        }).when(download).saveAs(any(Path.class));

        BrowserContext context = mock(BrowserContext.class);

        long start = System.nanoTime();
        PlaywrightContextManager.saveDownloadAsync(context, download, dir, 30_000);
        long dispatchMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(dispatchMs < DISPATCH_MAX_MS,
                "下载保存必须卸载出调用（连接读）线程：派发耗时应 ≪ " + SLOW_SAVE_MS + "ms，实测 " + dispatchMs
                        + "ms（阈值 " + DISPATCH_MAX_MS + "ms）—— 若失败，说明 saveAs 又被放回监听器内同步执行");

        // 卸载后仍必须真的保存 + 登记（防止"为了不阻塞而不保存"）
        assertTrue(saveStarted.await(10, TimeUnit.SECONDS), "卸载任务应实际开始执行 saveAs");
        assertTrue(saveDone.await(10, TimeUnit.SECONDS), "卸载任务应在池线程内完成落盘");
        assertNotNull(savedPath.get());
        assertTrue(savedPath.get().startsWith(dir), "保存路径应落在配置的下载目录内：" + savedPath.get());

        // 登记发生在 saveAs 返回之后（同一池任务内），故此处有界轮询等待，避免测试自身竞态
        List<Path> recorded = DownloadRegistry.instance().all(context);
        long deadline = System.currentTimeMillis() + 5000;
        while (recorded.isEmpty() && System.currentTimeMillis() < deadline) {
            slowDelay(50);
            recorded = DownloadRegistry.instance().all(context);
        }
        assertEquals(1, recorded.size(), "保存成功后应登记到 DownloadRegistry（供 getLastDownloadPath 等查询）");
        assertEquals(savedPath.get(), recorded.get(0));

        // 清理：登记簿 + 临时目录
        DownloadRegistry.instance().clear(context);
        Files.deleteIfExists(savedPath.get());
        Files.deleteIfExists(dir);
    }

    /**
     * 同名去重与登记组合语义（不涉并发）：桩内直接建文件，验证「冲突时加序号」在卸载路径上同样生效。
     */
    @Test
    public void offloadedSaveStillResolvesNonConflictingName() throws Exception {
        Path dir = Files.createTempDirectory("pw-dl-offload2");
        Files.createFile(dir.resolve("a.xlsx"));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Path> savedPath = new AtomicReference<>();
        Download download = mock(Download.class);
        when(download.suggestedFilename()).thenReturn("a.xlsx");
        doAnswer(inv -> {
            savedPath.set(inv.getArgument(0));
            Files.createFile(savedPath.get());
            done.countDown();
            return null;
        }).when(download).saveAs(any(Path.class));

        BrowserContext context = mock(BrowserContext.class);
        PlaywrightContextManager.saveDownloadAsync(context, download, dir, 10_000);

        assertTrue(done.await(10, TimeUnit.SECONDS), "卸载保存应在池线程内完成");
        assertEquals(dir.resolve("a (1).xlsx"), savedPath.get(), "同名冲突应加序号后缀（与既有语义一致）");

        DownloadRegistry.instance().clear(context);
        Files.deleteIfExists(savedPath.get());
        Files.deleteIfExists(dir.resolve("a.xlsx"));
        Files.deleteIfExists(dir);
    }
}
