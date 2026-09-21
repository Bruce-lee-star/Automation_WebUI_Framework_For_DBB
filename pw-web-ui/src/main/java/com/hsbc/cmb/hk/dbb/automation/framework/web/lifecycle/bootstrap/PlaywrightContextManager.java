package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.DownloadRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.PageEventMonitor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.PageInteractionMonitor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.ProxyConfigResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.trace.ScenarioTraceRecorder;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.async.AsyncPool;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.Geolocation;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Context 和 Page 管理器 - 负责 Context 和 Page 的创建、配置和关闭
 * <p>
 * 职责：
 * - Context 创建和配置
 * - Page 创建和稳定化
 * - Context 和 Page 的关闭
 */
public class PlaywrightContextManager {
    
    private static final Logger logger = LoggerFactory.getLogger(PlaywrightContextManager.class);

    /**
     * 本线程「框架受管页创建中」标记（见 {@link #createPage}）。
     *
     * <p><b>用途</b>：Playwright 的 {@code BrowserContext.onPage} 对 Context 内<b>任何</b>新页触发 ——
     * 既含框架自身 {@code createPage()} 的 {@code context.newPage()}（此刻尚未导航，url 恒为
     * {@code about:blank}），也含业务 {@code window.open}/{@code target=_blank} 弹窗或泄漏页。
     * 仅凭该回调无法区分二者，故在框架唯一建页入口 {@link #createPage} 内置位本标记，
     * 使回调能精确判定「受管页 vs 额外页」，避免把框架自己的 about:blank 受管页误报成弹窗。</p>
     *
     * <p><b>为何用 {@code ThreadLocal}</b>：Playwright Java 在<b>等待命令返回的线程</b>上派发事件，
     * 故 {@code context.newPage()} 触发的 onPage 回调与本次 {@code createPage} 调用同线程
     * （实测 onPage 与 createPage 同 worker 线程、时间窗重合），标记对该回调可见。</p>
     */
    private static final ThreadLocal<Boolean> MANAGED_PAGE_CREATION_IN_FLIGHT = new ThreadLocal<>();

    /**
     * 下载同名去重的候选序号上限（防御性）：{@code name}、{@code name (1)} … 最多尝试这么多次占位。
     * 正常场景序号极小（同目录同名文件数），该上限只为避免极端情况下无限递增。
     */
    private static final int MAX_DOWNLOAD_NAME_SEQ = 10_000;

    //  trace 的启停 / 分段 / 导出 / 报告挂载已收口到
    //  {@link com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.trace.ScenarioTraceRecorder}
    //  （方案 A：按 scenario 分段录制）；其专属写盘线程池与 ShutdownCoordinator 登记随之内聚到该类。


    /**
     * 创建新的 BrowserContext
     */
    public static BrowserContext createContext() {
        VerboseLogging.logInfoIfVerbose(logger, "Creating new BrowserContext...");

        Browser currentBrowser = PlaywrightManager.getBrowser();
        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions();

        // 配置框架默认项
        configureDefaultContextOptions(contextOptions);

        // 条件注入自定义配置
        Boolean customFlag = CustomOptionsManager.getInstance().isCustomContextOptionsFlag();
        if (customFlag != null && customFlag) {
            VerboseLogging.logInfoIfVerbose(logger, "Applying custom context options...");
            configureCustomContextOptions(contextOptions);
        }

        //  修复问题3：用 try-finally 确保 customContextOptionsFlag 在 newContext() 抛异常（浏览器断开/
        // 参数非法）时也能重置，避免该线程后续重试持续携带已失效的自定义配置而反复失败。
        BrowserContext context;
        try {
            // 初始化 Context
            context = currentBrowser.newContext(contextOptions);

            //  页面创建监听（判定「框架受管页」vs「额外页」）：
            //   context.onPage 对「任何」新页触发，不能笼统记成 "detected via window.open()" ——
            //   框架自身 createPage() 的 context.newPage() 也会触发（此刻尚未导航，url 为 about:blank），
            //   在并行逐 scenario 重建下会被误读为"同一窗口多开了一个 about:blank 页"。
            //   故经 MANAGED_PAGE_CREATION_IN_FLIGHT 精确判定归属，并打出 Context 内页数供判定。
            context.onPage(newPage -> {
                logNewPageEvent(context, newPage,
                        classifyNewPageEvent(context, newPage,
                                Boolean.TRUE.equals(MANAGED_PAGE_CREATION_IN_FLIGHT.get())));
                newPage.onLoad(pageLoad -> {
                    VerboseLogging.logDebugIfVerbose(logger,
                            "New page loaded: url={}, title={}", newPage.url(), newPage.title());
                });
            });

            // 注册页面级可观测性诊断监听（未捕获异常/控制台错误/网络失败/崩溃）
            // 经 context.onPage 覆盖所有新建页面（含 window.open 弹窗），与上方 onPage 日志互不冲突
            PageEventMonitor.register(context);

            // 注册页面级交互事件监听（导航轨迹 / 未受管弹窗 / 对话框自动处置 / 文件选择器自动上传）
            // 与 PageEventMonitor 同族、同接缝；各项均默认零行为回归，受独立配置开关控制
            PageInteractionMonitor.register(context);

            // 注册下载保存监听（§3.2：1.60+ 经 context.onDownload 一次注册即覆盖该 Context 下所有页面，
            // 含 window.open 弹窗，无需逐页注册；原先在 createPage 逐页 page.onDownload 会让弹窗内下载漏捕获，
            // 现由 Context 级注册彻底堵住该洞）
            registerDownloadHandler(context);

            // 设置超时
            configureTimeouts(context);

            // 启用 tracing（如果配置了）
            enableTracing(context);
        } finally {
            // 重置标志（成功或失败都执行）
            if (customFlag != null && customFlag) {
                CustomOptionsManager.getInstance().disableCustomOptions();
                VerboseLogging.logInfoIfVerbose(logger, "Custom context options applied, flag reset to false");
            }
        }

        VerboseLogging.logInfoIfVerbose(logger, "BrowserContext created successfully");
        return context;
    }

    /**
     * 创建新的 Page
     */
    public static Page createPage(BrowserContext context) {
        VerboseLogging.logInfoIfVerbose(logger, "Creating new Page...");
        //  置位「框架受管页创建中」标记：createPage 是框架唯一调用 context.newPage() 的入口，
        //  其执行期间触发的 onPage 回调必属受管页；标记之外触发的 onPage 即业务弹窗 / 泄漏页。
        //  必须在 newPage() 之前置位、之后清除（含异常路径），否则归属判定失真。
        MANAGED_PAGE_CREATION_IN_FLIGHT.set(Boolean.TRUE);
        Page page;
        try {
            page = context.newPage();
        } finally {
            MANAGED_PAGE_CREATION_IN_FLIGHT.remove();
        }

        // 下载保存监听已迁移至 createContext 经 context.onDownload 一次性注册（见报告 §3.2），
        // 此处不再逐页注册，避免 window.open 弹窗内下载漏捕获。
        stabilizePage(page);
        VerboseLogging.logInfoIfVerbose(logger, "Page created successfully");
        return page;
    }

    /**
     * 注册 BrowserContext 级下载保存监听（报告 §3.2：1.60+ 经 {@code context.onDownload} 一次注册即覆盖
     * 该上下文下所有页面，含 {@code window.open} 弹窗，无需逐页注册）。
     * <p>
     * 取代原先在 {@link #createPage} 逐页 {@code page.onDownload} 的做法，彻底堵住「弹窗内下载漏捕获」的洞；
     * 关闭时序降级（WEB-P3-N14 ②）与真实失败告警的日志语义与原实现逐字一致。
     *
     * @param context 浏览器上下文（null 安全：直接忽略）
     */
    private static void registerDownloadHandler(BrowserContext context) {
        if (context == null) {
            return;
        }
        final Path downloadDir = PlaywrightManager.downloadDirectoryForCurrentThread();
        final long saveTimeoutMs = TimeUnit.MINUTES.toMillis(
                Math.max(1, PlaywrightManager.config().getBrowserDownloadTimeoutMinutes()));
        //  监听器内【只派发】：绝不在此同步调用 saveAs —— 见 saveDownloadAsync 的「连接读线程」说明。
        context.onDownload(download -> saveDownloadAsync(context, download, downloadDir, saveTimeoutMs));
    }

    /**
     * 异步保存下载文件 —— <b>监听器内绝不同步执行 {@code saveAs}</b>。
     *
     * <p><b>为什么必须卸载（连接读线程自死锁防护；2026-09-08 修复的恢复）</b>：Playwright 的事件
     * （{@code onPage}/{@code onLoad}/{@code onDownload}）在<b>连接读线程</b>上派发，而
     * {@code download.saveAs(...)} 是同步操作（需等下载完成并落盘）。在监听器内直接调用会阻塞读线程
     * <b>自身</b> → 该连接上所有 CDP 命令排队（表现为该 worker 的当前/后续 scenario「卡住、排队」），
     * 极端情况自死锁、整轮挂起。2026-09-08 曾用专属 {@code DOWNLOAD_EXECUTOR} 卸载该调用，但在改为
     * 「context 级一次注册」时被丢失（2026-09-21 复核：全仓只剩测试 javadoc 提及它，生产代码已无此卸载）。</p>
     *
     * <p>现卸载到受管 {@link AsyncPool}（守护线程 + 有界队列 + 拒绝/超时可观测 + {@code ShutdownCoordinator} 收口），
     * 与 {@code MonitorHandler}/{@code ModifyHandler} 把阻塞链路移出事件线程同一治理方式；{@code timeoutMs}
     * 取 {@code playwright.browser.download.timeout.minutes}，超时由池兜底记 ERROR（不静默）。</p>
     *
     * <p>包级可见以便单测用「慢 {@code saveAs} 桩」证明派发<b>不阻塞</b>调用线程（{@code DownloadSaveOffloadTest}）。</p>
     *
     * @param context     触发下载的上下文（仅用于下载登记，可 mock）
     * @param download    下载对象（{@code saveAs} 在池线程执行）
     * @param downloadDir 下载目录（不存在则创建）
     * @param timeoutMs   保存超时（毫秒；≤0 由 {@link AsyncPool} 默认值兜底）
     */
    static void saveDownloadAsync(BrowserContext context, Download download, Path downloadDir, long timeoutMs) {
        AsyncPool.runWithTimeout(() -> {
            Path reserved = null;
            boolean saved = false;
            try {
                String suggestedFilename = download.suggestedFilename();
                //  原子占位（目录按需创建）：并发同名下载不会选到同一路径（见 resolveNonConflictingDownloadPath）
                reserved = resolveNonConflictingDownloadPath(downloadDir, suggestedFilename);
                download.saveAs(reserved);
                saved = true;
                VerboseLogging.logInfoIfVerbose(logger,
                        "Download completed: {} -> {}", suggestedFilename, reserved.toAbsolutePath());
                // 登记已落盘文件，供业务层经 PlaywrightManager.getLastDownloadPath() 等查询（报告 §3.2 收尾）
                DownloadRegistry.instance().record(context, reserved);
            } catch (Exception e) {
                //  占位文件回滚：仅当尚未写成功（保存失败/上下文关闭）时删除，避免残留 0 字节文件
                rollbackReservation(reserved, saved);
                //  关闭时序降级（WEB-P3-N14 ②）：Playwright 在 BrowserContext.close() 时会先清理
                //   未完成的下载，导致 download.saveAs() 抛 TargetClosedError。这属于「预期噪音」，
                //   若记 ERROR 会污染收尾期日志、掩盖真实告警；故降级为 DEBUG。
                //   真实保存失败（磁盘满 / 路径非法 / 权限不足）不被降级，仍记 ERROR，保证告警信噪比。
                if (isContextClosedError(e)) {
                    VerboseLogging.logDebugIfVerbose(logger,
                            "Save aborted (context/browser closing, download discarded): {}", e.getMessage());
                } else {
                    logger.error("[Download] Failed to save file: {}", e.getMessage(), e);
                }
            }
        }, timeoutMs);
    }

    /**
     * 回滚未被写入的下载占位文件（best-effort）。
     *
     * <p>占位文件由 {@link #resolveNonConflictingDownloadPath} 预先创建；保存失败时应删除，避免残留 0 字节文件
     * 干扰后续同名去重（残留也会被本线程收尾的 {@code cleanupTempDownloads} 兜底清掉）。</p>
     *
     * @param reserved 已占位的路径（可为 null）
     * @param saved    是否已成功写入（true 表示文件是有效产物，<b>不得</b>删除）
     */
    private static void rollbackReservation(Path reserved, boolean saved) {
        if (reserved == null || saved) {
            return;
        }
        try {
            Files.deleteIfExists(reserved);
        } catch (IOException ignored) {
            // 收尾清理（按线程目录）会兜底；此处不得影响异常上报语义
        }
    }

    /**
     * 解析并<b>原子占位</b>「不覆盖既有文件」的下载保存路径（同名去重）。
     *
     * <p><b>2026-09-21 修复（并发同名覆盖）</b>：原实现是「先查后选」（{@code Files.exists} 探测 + 递增序号），
     * 真并行下两个同名下载可能同时探测到同一"空闲"路径并互相覆盖。现改为 {@link Files#createFile}（{@code CREATE_NEW}，
     * 原子）<b>抢占</b>候选路径：抢占失败（{@link FileAlreadyExistsException}）即换下一个序号 ——
     * 并发同名下载必然拿到不同路径。抢占到的 0 字节文件随后由 {@code download.saveAs(...)} 覆写
     * （{@code saveAs} 会覆盖既有文件）；保存失败由调用方回滚删除该占位文件。</p>
     *
     * <p>命名规则不变：若 {@code dir/name} 已被占用，则在主文件名与扩展名之间插入序号后缀
     * {@code " (1)"} / {@code " (2)"} …；隐藏文件（如 {@code .gitignore}，点号在首位）整体作为主名处理，
     * 不加序号到扩展名。</p>
     *
     * @param dir               下载目录（不存在则创建）
     * @param suggestedFilename 服务器建议的文件名（来自 {@code Content-Disposition}）
     * @return 已被<b>本次调用原子占位</b>的路径（调用方负责写入；失败应回滚删除）
     * @throws IOException 目录不可创建，或候选序号耗尽（防御性上限，正常不会发生）
     */
    static Path resolveNonConflictingDownloadPath(Path dir, String suggestedFilename) throws IOException {
        Files.createDirectories(dir);
        String base = suggestedFilename;
        String ext = "";
        int dot = suggestedFilename.lastIndexOf('.');
        if (dot > 0) { // dot == 0 视为隐藏文件主名（如 ".env"），不拆扩展名
            base = suggestedFilename.substring(0, dot);
            ext = suggestedFilename.substring(dot);
        }
        for (int seq = 0; seq < MAX_DOWNLOAD_NAME_SEQ; seq++) {
            Path candidate = seq == 0
                    ? dir.resolve(suggestedFilename)
                    : dir.resolve(base + " (" + seq + ")" + ext);
            try {
                Files.createFile(candidate);   // 原子占位（CREATE_NEW）：并发下不可能两方拿到同一路径
                return candidate;
            } catch (FileAlreadyExistsException taken) {
                // 已被占用（含并发邻居刚抢占）→ 试下一个序号（正常路径，不记日志）
            }
        }
        throw new IOException("Exhausted " + MAX_DOWNLOAD_NAME_SEQ + " candidate names for '"
                + suggestedFilename + "' in " + dir);
    }

    /**
     * 关闭 Context
     */
    public static void closeContext(BrowserContext context) {
        if (context != null) {
            try {
                //  先释放路由层资源：停止 MonitorSession 定时器、unroute、清理注册表与防重门控，
                //    避免调度器线程池持有已销毁 context 引用导致内存泄漏 / 对已关闭 context 无效调度。
                //    统一走 RouteLifecycle 生命周期钩子（含关闭前泄漏诊断）。
                try {
                    RouteLifecycleRegistry.get().clearContext(context);
                } catch (Exception re) {
                    VerboseLogging.logWarnIfVerbose(logger, "Failed to clear Route resources on context close: {}", re.getMessage());
                }
                // 清理下载登记簿中该上下文的记录（与路由资源同批释放，避免陈旧记录堆积）
                DownloadRegistry.instance().clear(context);
                //  trace 收尾（方案 A，2026-09-17）：交给 ScenarioTraceRecorder。
                //  正常路径下每个用例已在 scenario 收尾时导出了自己的 chunk 文件；若仍有未导出的活动 chunk
                //  （该用例未走到收尾，如异常/强制清理路径），在此兜底导出为 ONCLOSE 文件，随后结束 tracing。
                //  导出带超时、走专属线程池，任何失败只降级为日志，绝不阻塞 context.close()。
                ScenarioTraceRecorder.onContextClosing(context);
                //  独立的 close try：即使上面任何步骤抛异常，也要保证 context.close() 被执行，
                //    否则已关闭失败会导致 context 资源泄漏。
                //    注意：BrowserContext 无 isClosed() 方法，用 browser 连接状态判断其是否仍活跃。
                //  窗口堆积修复：原实现仅在 browser() 可用且已连接时才 close —— 浏览器已断开/对象失效时
                //    静默跳过，Context 及其窗口残留（直到套件级 cleanupAll 才释放）。改为无条件尝试 close，
                //    已关闭/失效（TargetClosedError 等）按预期降级为 debug 日志。
                try {
                    context.close();
                    VerboseLogging.logInfoIfVerbose(logger, "BrowserContext closed");
                } catch (Exception closeEx) {
                    VerboseLogging.logDebugIfVerbose(logger,
                            "BrowserContext close skipped (already closed or browser gone): {}", closeEx.getMessage());
                }
            } catch (Exception e) {
                logger.error("Failed to close BrowserContext: {}", e.getMessage(), e);
                throw new BrowserException("Failed to close BrowserContext", e);
            }
        }
    }

    // ==================== Trace（已迁移）====================
    //  文件名 sanitize 与「trace 挂进 Serenity 报告」两件事已随方案 A 迁至
    //  {@link com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.trace.ScenarioTraceRecorder}：
    //  文件名改为 {@code trace-<scenarioId>-<start>-<end>-<PASS|FAIL|ONCLOSE>.zip}（带起止时间），
    //  报告条目同时给出覆盖时间窗，便于按区间定位。

    /**
     * 关闭 Page
     */
    public static void closePage(Page page) {
        if (page != null) {
            try {
                if (!page.isClosed()) {
                    //  先释放该 Page 上的路由层资源（停止 MonitorSession 定时器、清理注册表），
                    //    再关闭 Page，避免调度器线程池持有已销毁 page 引用导致内存泄漏。
                    //    统一走 RouteRegistry.clearContext 释放。
                    try {
                        RouteLifecycleRegistry.get().clearContext(page);
                    } catch (Exception re) {
                        VerboseLogging.logWarnIfVerbose(logger, "Failed to clear Route resources on page close: {}", re.getMessage());
                    }
                    VerboseLogging.logInfoIfVerbose(logger, "Closing Page...");
                    page.close();
                    VerboseLogging.logInfoIfVerbose(logger, "Page closed");
                }
            } catch (Exception e) {
                logger.error("Failed to close page: {}", e.getMessage(), e);
                throw new BrowserException("Failed to close page", e);
            }
        }
    }

    /**
     * 配置框架默认 Context 选项
     */
    private static void configureDefaultContextOptions(Browser.NewContextOptions contextOptions) {
        // 启用文件下载（Playwright 安全策略默认禁止下载，需显式开启）
        contextOptions.setAcceptDownloads(true);

        contextOptions.setLocale(PlaywrightManager.config().getContextLocale());
        
        String timezoneId = PlaywrightManager.config().getContextTimezone();
        if (timezoneId != null && !timezoneId.isEmpty()) {
            contextOptions.setTimezoneId(timezoneId);
        }

        String userAgent = PlaywrightManager.config().getContextUserAgent();
        if (userAgent != null && !userAgent.isEmpty()) {
            contextOptions.setUserAgent(userAgent);
        }

        String permissionsConfig = PlaywrightManager.config().getContextPermissions();
        if (permissionsConfig != null && !permissionsConfig.isEmpty()) {
            contextOptions.setPermissions(List.of(permissionsConfig.split(",")));
        }

        // 配置代理服务器（直接从统一代理配置读取）
        // 优先级：customProxyEnabled ThreadLocal > playwright.context.proxy.enabled
        Boolean customProxyOverride = PlaywrightManager.customOptions().getProxyEnabled();
        boolean proxyEnabled;
        if (customProxyOverride != null) {
            proxyEnabled = customProxyOverride;
            VerboseLogging.logInfoIfVerbose(logger, "Using custom proxyEnabled override: {} (from business code)", proxyEnabled);
        } else {
            proxyEnabled = PlaywrightConfigManager.config().getContextProxyEnabled();
        }

        if (proxyEnabled) {
            String proxyUrl = ProxyConfigResolver.getHttpProxyUrl();
            if (proxyUrl != null) {
                contextOptions.setProxy(proxyUrl);
                VerboseLogging.logInfoIfVerbose(logger, "Setting context proxy from unified config");
            } else {
                VerboseLogging.logWarnIfVerbose(logger, "Proxy enabled but no HTTP proxy configured, skipping context proxy");
            }
        }

        // 配置设备缩放因子
        String deviceScaleFactor = PlaywrightManager.config().getDeviceScaleFactor();
        if (deviceScaleFactor == null || deviceScaleFactor.trim().isEmpty()) {
            double systemDpiScaleFactor = PlaywrightManager.config().getSystemDpiScaleFactor();
            deviceScaleFactor = String.valueOf(systemDpiScaleFactor);
        }
        contextOptions.setDeviceScaleFactor(Double.parseDouble(deviceScaleFactor));

        // 设置 viewport（使用逻辑尺寸，Playwright viewport 以 CSS 像素为单位）
        Dimension screenSize = PlaywrightManager.config().getAvailableScreenSize();
        int viewportWidth = (int) screenSize.getWidth();
        int viewportHeight = (int) screenSize.getHeight();

        String browserType = PlaywrightManager.config().getBrowserType();

        // 统一 viewport 策略: viewport = 逻辑屏幕尺寸 - 浏览器 chrome 估算高度
        // stabilizePage 中 window.resizeTo(logicalWidth, logicalHeight) 将窗口外尺寸最大化到屏幕逻辑尺寸
        // 窗口外尺寸 = viewport + titleBar + tabBar + addressBar + ... ≈ viewport + 100px
        // 因此 viewport 应设为逻辑屏幕高度 - chrome估算值，才能填满最大化窗口
        int estimatedChromeHeight = 100;
        viewportHeight = Math.max(viewportHeight - estimatedChromeHeight, 600);
        contextOptions.setViewportSize(viewportWidth, viewportHeight);
        VerboseLogging.logInfoIfVerbose(logger,
            "Context viewport: {}x{} (logical screen {}x{}, adjusted for browser chrome ~{}px, browser={})",
            viewportWidth, viewportHeight, (int) screenSize.getWidth(), (int) screenSize.getHeight(),
            estimatedChromeHeight, browserType);

        contextOptions.setHasTouch(PlaywrightManager.config().hasTouch());
        contextOptions.setIsMobile(PlaywrightManager.config().isMobile());

        String colorScheme = PlaywrightManager.config().getColorScheme();
        contextOptions.setColorScheme(ColorScheme.valueOf(colorScheme.toUpperCase().replace("-", "_")));

        // 配置录屏
        if (PlaywrightManager.config().isRecordVideoEnabled()) {
            String videoDir = PlaywrightManager.config().getRecordVideoDir();
            screenSize = PlaywrightManager.config().getAvailableScreenSize();
            contextOptions.setRecordVideoDir(Paths.get(videoDir));
            contextOptions.setRecordVideoSize((int) screenSize.getWidth(), (int) screenSize.getHeight());
        }
    }

    /**
     * 配置自定义 Context 选项
     */
    private static void configureCustomContextOptions(Browser.NewContextOptions contextOptions) {
        CustomOptions cm = PlaywrightManager.customOptions();

        // StorageState：优先用内存缓存的 JSON 内容（零文件 IO）；否则退回文件路径
        String storageState = cm.getStorageState();
        if (storageState != null && !storageState.isEmpty()) {
            contextOptions.setStorageState(storageState);
            VerboseLogging.logInfoIfVerbose(logger, "Using in-memory storageState (from cache)");
        } else {
            Path storagePath = cm.getStorageStatePath();
            if (storagePath != null && Files.exists(storagePath)) {
                contextOptions.setStorageStatePath(storagePath);
                VerboseLogging.logInfoIfVerbose(logger, "Using custom storageStatePath: {}", storagePath);
            }
        }

        applyIfNotEmpty(cm.getLocale(), "locale", () -> contextOptions.setLocale(cm.getLocale()));
        applyIfNotEmpty(cm.getTimezoneId(), "timezoneId", () -> contextOptions.setTimezoneId(cm.getTimezoneId()));
        applyIfNotEmpty(cm.getUserAgent(), "userAgent", () -> contextOptions.setUserAgent(cm.getUserAgent()));
        applyIfNotNull(cm.getPermissions(), "permissions", () -> contextOptions.setPermissions(cm.getPermissions()));

        Geolocation geo = cm.getGeolocation();
        applyIfNotNull(geo, "geolocation", () -> contextOptions.setGeolocation(geo.latitude, geo.longitude));

        Integer sf = cm.getDeviceScaleFactor();
        applyIfNotNull(sf, "deviceScaleFactor", () -> contextOptions.setDeviceScaleFactor(sf / 100.0));

        applyIfNotNull(cm.getIsMobile(), "isMobile", () -> contextOptions.setIsMobile(cm.getIsMobile()));
        applyIfNotNull(cm.getHasTouch(), "hasTouch", () -> contextOptions.setHasTouch(cm.getHasTouch()));
        applyIfNotNull(cm.getColorScheme(), "colorScheme", () -> contextOptions.setColorScheme(cm.getColorScheme()));

        // Viewport（需要两个值均非空）
        Integer vw = cm.getViewportWidth();
        Integer vh = cm.getViewportHeight();
        if (vw != null && vh != null) {
            contextOptions.setViewportSize(vw, vh);
            VerboseLogging.logInfoIfVerbose(logger, "Using custom viewportSize: {}x{}", vw, vh);
        }
    }

    /**
     * 仅在值非空时应用自定义选项（适用于非 String 类型）
     */
    private static void applyIfNotNull(Object value, String name, Runnable applier) {
        if (value != null) {
            applier.run();
            VerboseLogging.logInfoIfVerbose(logger, "Using custom {}: {}", name, value);
        }
    }

    /**
     * 仅在值非空且非空字符串时应用（适用于 String 类型）
     */
    private static void applyIfNotEmpty(String value, String name, Runnable applier) {
        if (value != null && !value.isEmpty()) {
            applier.run();
            VerboseLogging.logInfoIfVerbose(logger, "Using custom {}: {}", name, value);
        }
    }

    /**
     * 配置超时
     */
    private static void configureTimeouts(BrowserContext context) {
        context.setDefaultNavigationTimeout(PlaywrightManager.config().getNavigationTimeout());
        context.setDefaultTimeout(PlaywrightManager.config().getPageTimeout());
    }

    /**
     * 启用 Tracing
     */
    private static void enableTracing(BrowserContext context) {
        //  方案 A：开启 tracing（chunk #0 随 context 开始），后续由 ScenarioTraceRecorder 在用例边界
        //  用 startChunk()/stopChunk(path) 切段 —— 使 trace 的时间区间 == 用例执行区间。
        ScenarioTraceRecorder.onContextCreated(context);
    }

    /**
     * 页面稳定化：确保页面加载完成并固定窗口/缩放状态
     */
    private static void stabilizePage(Page page) {
        try {
            VerboseLogging.logDebugIfVerbose(logger, "Page stabilization: ensuring correct window size...");

            int stabilizeWaitTimeout = PlaywrightManager.config().getStabilizeTimeout();
            LoadState loadState = getConfiguredLoadState();
            try {
                page.waitForLoadState(loadState, new Page.WaitForLoadStateOptions().setTimeout(stabilizeWaitTimeout));
            } catch (Exception e) {
                VerboseLogging.logDebugIfVerbose(logger, "Page load wait timed out (LoadState: {}), continuing stabilization: {}", loadState, e.getMessage());
            }

            Dimension screenSize = PlaywrightManager.config().getAvailableScreenSize();
            int logicalWidth = (int) screenSize.getWidth();
            int logicalHeight = (int) screenSize.getHeight();

            // 统一使用 window.resizeTo 最大化窗口（所有浏览器）
            // 窗口外尺寸 = viewport + chrome，这里将外尺寸设为逻辑屏幕尺寸
            // context 中已设置 viewport = logicalHeight - chromeEstimate，两者配合正好填满
            //
            // 关键：window.resizeTo 会触发 OS 窗口管理器异步重排位置（居中），
            // moveTo(0,0) 必须通过 setTimeout 延迟执行，等 resize 彻底完成后才移到左上角
            // WebKit 不支持 --window-position launch arg，完全依赖此 JS 定位，延迟尤为关键
            page.evaluate(String.format(
                    "window.resizeTo(%d, %d);"
                    + "setTimeout(function() { window.moveTo(%d, %d); }, "
                    + WebFrameworkConfig.PLAYWRIGHT_CONTEXT_STABILIZE_DELAY_MS.getIntValue() + ");",
                    logicalWidth, logicalHeight, 0, 0
            ));
            VerboseLogging.logDebugIfVerbose(logger, "Window maximize: window.resizeTo({}, {}), moveTo(0,0) delayed 300ms", logicalWidth, logicalHeight);

            page.evaluate(
                    "document.body.style.zoom = '100%'; "
                            + "document.documentElement.style.zoom = '100%'; "
                            + "document.documentElement.style.transform = 'none'; "
                            + "document.documentElement.style.transformOrigin = '0 0';"
            );

            page.evaluate(
                    "window.addEventListener('resize', function(e) { e.stopPropagation(); }, true);"
                            + "document.addEventListener('DOMContentLoaded', function() {"
                            + "    if (window.devicePixelRatio !== 1) { "
                            + "    }"
                            + "});"
            );

            Integer customViewportWidthVal = CustomOptionsManager.getInstance().getViewportWidth();
            Integer customViewportHeightVal = CustomOptionsManager.getInstance().getViewportHeight();

            if (customViewportWidthVal != null && customViewportHeightVal != null) {
                // 用户自定义 viewport：直接应用
                page.setViewportSize(customViewportWidthVal, customViewportHeightVal);
                VerboseLogging.logDebugIfVerbose(logger, "Custom viewport applied: {}x{}",
                        customViewportWidthVal, customViewportHeightVal);
            } else {
                // context 已统一设置 viewport = logical - chromeEstimate
                // window.resizeTo + context viewport 配合 → 窗口填满屏幕
                VerboseLogging.logDebugIfVerbose(logger, "No custom viewport, keeping viewport from context (window-adapted)");
            }

            VerboseLogging.logDebugIfVerbose(logger, "Page stabilization completed");
        } catch (Exception e) {
            logger.warn("Page stabilization failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取配置的页面加载状态
     * 配置属性: playwright.page.load.state
     * 可选值: LOAD, DOMCONTENTLOADED, NETWORKIDLE
     * 默认值: DOMCONTENTLOADED
     */
    private static LoadState getConfiguredLoadState() {
        String loadStateConfig = PlaywrightManager.config().getPageLoadState();
        try {
            return LoadState.valueOf(loadStateConfig.toUpperCase());
        } catch (IllegalArgumentException e) {
            VerboseLogging.logWarnIfVerbose(logger, "Invalid LoadState configuration: {}, using default: DOMCONTENTLOADED", loadStateConfig);
            return LoadState.DOMCONTENTLOADED;
        }
    }

    /**
     * onPage 事件的新页归属判定结果（不可变；无副作用，供日志与同包单测断言）。
     *
     * @apiNote 框架内部能力（包级私有）：仅供 {@link #createContext()} 接线与同包单测使用。
     */
    static final class NewPageEvent {
        final boolean frameworkManaged;
        final int pageCount;
        final String url;
        final boolean aboutBlank;

        NewPageEvent(boolean frameworkManaged, int pageCount, String url) {
            this.frameworkManaged = frameworkManaged;
            this.pageCount = pageCount;
            this.url = url;
            this.aboutBlank = isAboutBlank(url);
        }
    }

    /**
     * 判定 onPage 事件中新页的归属：<b>框架受管页</b> vs <b>额外页</b>（业务 {@code window.open} 弹窗 /
     * 测试直接 {@code context.newPage()} / 泄漏页）。
     *
     * <p><b>纯函数</b>：无副作用、不读 verbose 配置，故可被同包单测确定性驱动并固化判据
     * （见 {@code PlaywrightContextManagerNewPageClassificationTest}）。</p>
     *
     * @param context          事件所属 Context
     * @param newPage          事件对应的新页
     * @param frameworkManaged {@link #createPage} 是否正在创建该页（由
     *                         {@link #MANAGED_PAGE_CREATION_IN_FLIGHT} 判定）
     * @return 判定结果（含 Context 内页数与 about:blank 标记）
     * @apiNote 框架内部能力（包级私有）。
     */
    static NewPageEvent classifyNewPageEvent(BrowserContext context, Page newPage, boolean frameworkManaged) {
        return new NewPageEvent(frameworkManaged, countPagesSafely(context), urlSafely(newPage));
    }

    /**
     * 记录 onPage 事件的归属判定日志。
     *
     * <p><b>受管页</b>：about:blank 是 {@code newPage()} 后、首次 {@code navigate()} 前的正常状态，
     * 非异常，故仅 verbose 记录并显式标注 {@code expected}。</p>
     *
     * <p><b>额外页</b>：恒记 INFO（含 aboutBlank 标记）；当 Context 内页数 &gt;1 时追加 WARN 与归属清单，
     * 便于现场判定是合法弹窗、测试造页还是泄漏。</p>
     *
     * @param context 事件所属 Context
     * @param newPage 事件对应的新页（用于清单中标 {@code (new)}）
     * @param event   {@link #classifyNewPageEvent} 的判定结果
     * @apiNote 框架内部能力（包级私有）。
     */
    static void logNewPageEvent(BrowserContext context, Page newPage, NewPageEvent event) {
        if (event.frameworkManaged) {
            VerboseLogging.logInfoIfVerbose(logger,
                    "New page created by framework (managed): ctx=#{} pages={} url={}{}",
                    System.identityHashCode(context), event.pageCount, event.url,
                    event.aboutBlank ? " [about:blank until first navigate — expected]" : "");
            return;
        }
        //  非框架创建 ⇒ 业务 window.open / target=_blank 弹窗，或某路径泄漏出的额外页。
        logger.info("[multi-page] Extra page (not framework-managed): ctx=#{} pages={} url={} aboutBlank={}",
                System.identityHashCode(context), event.pageCount, event.url, event.aboutBlank);
        if (event.pageCount > 1) {
            logger.warn("[multi-page] Context=#{} now holds {} pages (expected 1 managed): {}"
                            + " ([managed]=框架登记的当前受管页；[extra]=非登记页：业务 window.open 弹窗 / 测试直接 newPage / 泄漏)",
                    System.identityHashCode(context), event.pageCount,
                    describeContextPages(context, newPage));
        }
    }

    /** 安全读取 Context 内页数（Context 已关闭/失效时返回 0，不抛异常）。 */
    private static int countPagesSafely(BrowserContext context) {
        try {
            List<Page> pages = context.pages();
            return pages == null ? 0 : pages.size();
        } catch (Exception e) {
            VerboseLogging.logDebugIfVerbose(logger, "pages() unavailable while logging new page: {}", e.toString());
            return 0;
        }
    }

    /** 安全读取 Page 的 URL（页面/连接已失效时返回占位符，不抛异常）。 */
    private static String urlSafely(Page page) {
        try {
            return page.url();
        } catch (Exception e) {
            return "<unavailable>";
        }
    }

    /** 是否为「尚未导航」的初始地址（框架受管页在首次 {@code navigate()} 前的正常状态）。 */
    private static boolean isAboutBlank(String url) {
        return url == null || url.isEmpty() || "about:blank".equals(url);
    }

    /**
     * 渲染 Context 内所有页面的归属清单（{@code [managed]} / {@code [extra]}，本次新出现的页标 {@code (new)}），
     * 用于「同 Context 多页」告警时现场判定是合法弹窗还是泄漏页。
     *
     * <p>归属判据：与 {@link PlaywrightManager#currentPageForThread()}（框架本线程受管页）或
     * 用例级 {@code PAGE_KEY}（{@code createNewContextAndPage} 路径）同一实例者为 {@code [managed]}，
     * 其余为 {@code [extra]}（业务弹窗 / 测试直接 {@code context.newPage()} / 泄漏页）。</p>
     *
     * @param context  目标 Context
     * @param appeared 本次 onPage 回调对应的新页（标 {@code (new)}）
     * @return 可读清单；{@code pages()} 不可用时返回降级说明（不抛异常）
     */
    private static String describeContextPages(BrowserContext context, Page appeared) {
        StringBuilder sb = new StringBuilder();
        Page managed = PlaywrightManager.currentPageForThread();
        Page registered = TestContextHolder.get().get(PlaywrightManager.PAGE_KEY);
        try {
            for (Page p : context.pages()) {
                if (!sb.isEmpty()) {
                    sb.append(" || ");
                }
                sb.append((p == managed || p == registered) ? "[managed] " : "[extra] ");
                sb.append(p == appeared ? "(new) " : "");
                sb.append(p.isClosed() ? "(closed) " : "");
                sb.append(urlSafely(p));
            }
        } catch (Exception e) {
            sb.append("<pages() unavailable: ").append(e.getClass().getSimpleName()).append('>');
        }
        return sb.toString();
    }

    /**
     * 判定异常是否为「Context / Browser 正在关闭」引发的时序噪音（WEB-P3-N14 ②）。
     *
     * <p>Playwright 的 {@code TargetClosedError} 会被包装进 {@code PlaywrightException} 的
     * <b>cause 链</b>深处，故必须沿 {@code getCause()} 逐层识别，仅看顶层消息会漏判。
     * 判定依据（二者命中其一即可）：
     * <ul>
     *   <li>异常类名包含 {@code TargetClosed}（规避直接依赖 Playwright 内部类型）；</li>
     *   <li>消息包含 {@code "Target page, context or browser has been closed"}。</li>
     * </ul>
     *
     * <p><b>健壮性</b>：{@code null} 返回 {@code false}（调用方按「非关闭」处理，保持原有告警级别）；
     * 采用身份集合做 <b>cause 自环防御</b>，避免异常链成环（{@code a.initCause(a)} 等畸形链）导致无限循环。
     *
     * @param t 待判定异常，可为 {@code null}
     * @return true 表示属关闭时序噪音（应降级为 DEBUG 而非 ERROR）
     * @apiNote <b>框架内部能力</b>（包级私有）：仅供同包下载/资源清理路径使用，业务代码不应依赖。
     */
    static boolean isContextClosedError(Throwable t) {
        if (t == null) {
            return false;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable current = t;
        while (current != null && seen.add(current)) {
            if (current.getClass().getSimpleName().contains("TargetClosed")) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("Target page, context or browser has been closed")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}