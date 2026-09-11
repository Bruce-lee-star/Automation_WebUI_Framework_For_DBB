package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTask;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightContextManager;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：固化 2026-09-08 并发卡死根因修复 —— 连接读线程自死锁（设计文档第九节 / README 第九节）。
 *
 * <p><b>根因</b>：Playwright 事件（{@code onPage}/{@code onLoad}/{@code onDownload}）在<b>连接读线程</b>派发，
 * 监听器内若同步调用 {@code title()}/{@code evaluate()}/{@code saveAs()} 等 CDP 方法会阻塞读线程自身，
 * 导致整条共享连接<b>自死锁</b>、所有并发导航挂起（页面停在 about:blank）。</p>
 *
 * <p><b>修复点（见 {@link PlaywrightContextManager}）</b>：
 * <ol>
 *   <li>{@code createContext} 的 {@code context.onPage} 仅读取 {@code newPage.url()}（字段读取，无传输），
 *       不再调用 {@code title()}；</li>
 *   <li>{@code createPage} 的 {@code page.onDownload} 将 {@code download.saveAs(...)} 卸载到专属守护线程
 *       {@code DOWNLOAD_EXECUTOR}，连接读线程仅负责派发事件。</li>
 * </ol>
 * 所有新增监听器均须只做字段读取 / 日志，禁止在监听器内发起同步 CDP 调用。</p>
 *
 * <p><b>本测试不导航真实 SIT（零网络依赖）</b>：用真实本地 Chromium（headless）+ 前端 {@code blob:} URL
 * （纯内存、不经过任何网络，故不受框架 Context 代理配置影响）+ 并发下载 / 并发建页，
 * 直接驱动生产的监听器注册路径（{@code PlaywrightContextManager.createContext/createPage}）。若修复被回退
 * （{@code onDownload} 内同步 {@code saveAs} / {@code onPage} 内同步 {@code title}），连接读线程将自死锁，
 * 任务在 {@code perTaskTimeoutMillis} 内无法完成 → 本测试失败（而非永久挂起 build）。以此把
 * "连接线程不自死锁" 固化进全护盾。</p>
 *
 * <p>@apiNote 测试与 {@link PlaywrightManager} 同包，故可白盒访问包级私有的
 * {@link PlaywrightContextManager#createContext()} / {@link PlaywrightContextManager#createPage(BrowserContext)}
 * 与 {@link PlaywrightManager#cleanupAll()}；这些能力不对外公开，仅框架内部与同包测试可见。</p>
 */
public class ConcurrentContextNoDeadlockTest {

    private static final int PARALLELISM = 3;
    private static final int PER_TASK_TIMEOUT_MS = 50_000;

    /** 强制无头，避免 CI/无显示器环境下启动失败（serenity.conf 默认 headless=false）。
     *  静态块在类加载时最早设置，确保 {@link PlaywrightManager#getBrowser()} 实时读取到无头模式。 */
    static {
        System.setProperty("playwright.browser.headless", "true");
    }

    @BeforeAll
    public static void launch() {
        // 经生产路径启动真实本地 Chromium；createContext/createPage 将注册修复后的监听器。
        PlaywrightManager.initialize();
        PlaywrightManager.getBrowser();
    }

    @AfterAll
    public static void shutdown() {
        // best-effort 关闭，避免浏览器进程泄漏影响后续测试。
        try {
            PlaywrightManager.cleanupAll();
        } catch (Throwable ignore) {
            // 修复态下 close 正常返回；即便异常也不应阻断后续测试。
        }
    }

    /**
     * 并发下载场景：{@code onDownload} 的 {@code saveAs} 必须卸载到 {@code DOWNLOAD_EXECUTOR}。
     * 若回退为监听器内同步 {@code saveAs}，连接读线程自死锁 → 任务超时失败。
     */
    @Test
    public void concurrentDownloadsDoNotDeadlockConnectionThread() {
        assertTimeoutPreemptively(Duration.ofMillis(90_000), () -> {
            List<ContextTask<String>> tasks = new ArrayList<>();

            for (int i = 0; i < PARALLELISM; i++) {
                final int idx = i;
                tasks.add(ContextTask.of("download-" + idx, () -> {
                    BrowserContext ctx = PlaywrightContextManager.createContext();
                    Page page = PlaywrightContextManager.createPage(ctx);
                    try {
                        // 用 blob: URL 触发下载（纯前端、零网络、不受框架 Context 代理配置影响）；
                        // 点击 attachment 链接 → 确定性触发 Download 事件（派发于连接读线程）。
                        String blobUrl = page.evaluate("URL.createObjectURL(new Blob(['hello" + idx
                                + "'], {type:'text/plain'}))").toString();
                        page.setContent("<html><body><a id='dl' download='deadlock-guard-" + idx
                                + ".txt' href='" + blobUrl + "'>d</a></body></html>");
                        // waitForDownload 在下载事件派发后立即返回，证明 onDownload 监听器已注册且未阻塞。
                        // 修复点：onDownload 内 saveAs 已卸载到 DOWNLOAD_EXECUTOR，故连接读线程不被阻塞。
                        Download download = page.waitForDownload(() -> page.click("#dl"));
                        assertNotNull(download, "Download 事件应被派发（onDownload 监听器已注册）");
                        assertNotNull(download.suggestedFilename(), "suggestedFilename 应非空");
                        // 关键防回归：onDownload 派发后连接读线程仍应立即响应 page.title()；
                        // 若 saveAs 同步阻塞连接读线程（旧 bug），此处将自死锁 → 任务在 perTaskTimeout 内失败。
                        String title = page.title();
                        assertNotNull(title, "onDownload 派发后连接读线程应可响应 title()（未自死锁）");
                        return "ok-" + idx;
                    } finally {
                        safeClose(page, ctx);
                    }
                }));
            }

            long start = System.currentTimeMillis();
            List<ContextTaskResult<String>> results = ConcurrentContextExecutor.runAll(tasks,
                    ConcurrentContextOptions.builder()
                            .parallelism(PARALLELISM)
                            .perTaskTimeoutMillis(PER_TASK_TIMEOUT_MS)
                            .build());
            long took = System.currentTimeMillis() - start;

            assertEquals(PARALLELISM, results.size());
            for (int i = 0; i < PARALLELISM; i++) {
                assertTrue( results.get(i).isSuccess(), "任务 " + i + " 应成功（未死锁）：" + results.get(i));
            }
            assertTrue( took < 60_000, "并发下载应在合理时间内完成（无死锁），took=" + took);
        });
    }

    /**
     * 并发建页场景：{@code context.onPage} 仅读取 {@code url()}（字段读取）。若回退为同步 {@code title()}，
     * 连接读线程自死锁 → 任务超时失败。本测试通过多次 {@code ctx.newPage()} 确定性触发 {@code onPage}。
     */
    @Test
    public void concurrentPageCreationOnPageListenerDoNotDeadlock() {
        assertTimeoutPreemptively(Duration.ofMillis(90_000), () -> {
            List<ContextTask<String>> tasks = new ArrayList<>();

            for (int i = 0; i < PARALLELISM; i++) {
                final int idx = i;
                tasks.add(ContextTask.of("page-" + idx, () -> {
                    BrowserContext ctx = PlaywrightContextManager.createContext();
                    Page page = PlaywrightContextManager.createPage(ctx); // 触发 onPage（page1）
                    Page second = null;
                    try {
                        // 确定性触发 context.onPage（page2）；修复后监听器仅 newPage.url()，不阻塞读线程。
                        second = ctx.newPage();
                        // 断言连接读线程仍响应（未卡死）：页面操作可正常返回。
                        page.setContent("<html><body><h1>guard" + idx + "</h1></body></html>");
                        String title = page.title();
                        assertNotNull(title, "页面应可响应 title()（连接读线程未死锁）");
                        return "ok-" + idx;
                    } finally {
                        safeClose(second, null);
                        safeClose(page, ctx);
                    }
                }));
            }

            long start = System.currentTimeMillis();
            List<ContextTaskResult<String>> results = ConcurrentContextExecutor.runAll(tasks,
                    ConcurrentContextOptions.builder()
                            .parallelism(PARALLELISM)
                            .perTaskTimeoutMillis(PER_TASK_TIMEOUT_MS)
                            .build());
            long took = System.currentTimeMillis() - start;

            assertEquals(PARALLELISM, results.size());
            for (int i = 0; i < PARALLELISM; i++) {
                assertTrue( results.get(i).isSuccess(), "任务 " + i + " 应成功（未死锁）：" + results.get(i));
            }
            assertTrue( took < 60_000, "并发建页应在合理时间内完成（无死锁），took=" + took);
        });
    }

    // ==================== 辅助 ====================

    /**
     * 资源释放保证（回应"内存泄漏 / 资源线程未释放"关切）：
     * <ol>
     *   <li>每个任务在 finally 走<b>生产清理路径</b> {@link PlaywrightContextManager#closePage(Page)} /
     *       {@link PlaywrightContextManager#closeContext(BrowserContext)}，而非直接 {@code page.close()/ctx.close()}。
     *       生产路径额外执行 {@code RouteLifecycleRegistry.clearContext(...)} 释放路由层对 context 的引用，
     *       并受保护地关闭（避免 close 异常吞掉清理），否则调度器线程池可能长期持有已销毁 context → 内存泄漏。</li>
     *   <li>{@code @AfterAll#shutdown()} 调用 {@link PlaywrightManager#cleanupAll()}，其遍历
     *       {@code browserInstances} 关闭<b>所有</b>浏览器实例（含并发 worker 线程各自 per-thread 启动的 Chromium）
     *       及其全部 context，并 clear 两个实例 Map + 清理 ThreadLocal，故浏览器进程 / 上下文不会泄漏。</li>
     *   <li>{@code onDownload} 的 {@code saveAs} 卸载到 {@link PlaywrightContextManager} 的
     *       {@code DOWNLOAD_EXECUTOR}（受 {@code ShutdownCoordinator} 管理的守护线程池），JVM 退出时关闭；
     *       任务 finally 关闭 context 即取消在途下载，saveAs 立即以 TargetClosed 结束（被生产 handler 捕获），
     *       不会令该守护线程长期挂起或泄漏。</li>
     *   <li>{@link ConcurrentContextExecutor#runAll} 内部线程池为守护线程，finally 执行
     *       {@code shutdown()+awaitTermination(30s)}，worker 线程必然释放。</li>
     * </ol>
     */
    private static void safeClose(Page page, BrowserContext ctx) {
        // 先关 page 再关 context：closeContext 内部需 context.browser() 非 null 才能执行受保护 close。
        try {
            if (page != null) {
                PlaywrightContextManager.closePage(page);
            }
        } catch (Throwable ignore) {
            // 连接读线程异常时关闭可能失败，忽略；closeContext / cleanupAll 兜底。
        }
        try {
            if (ctx != null) {
                PlaywrightContextManager.closeContext(ctx);
            }
        } catch (Throwable ignore) {
            // 同上。
        }
    }
}
