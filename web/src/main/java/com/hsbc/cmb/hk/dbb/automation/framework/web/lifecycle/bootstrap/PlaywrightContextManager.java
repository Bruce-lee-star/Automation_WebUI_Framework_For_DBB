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
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.Geolocation;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

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

            //  监听 window.open() 等产生的新 Page，记录日志供 switchNewPage 调试
            context.onPage(newPage -> {
                VerboseLogging.logInfoIfVerbose(logger,
                        "New page detected via window.open(): url={}", newPage.url());
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
        Page page = context.newPage();

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
        final String downloadsPath = PlaywrightManager.config().getBrowserDownloadsPath();
        context.onDownload(download -> {
            try {
                Path downloadDir = Paths.get(downloadsPath);
                if (!Files.exists(downloadDir)) {
                    Files.createDirectories(downloadDir);
                }
                String suggestedFilename = download.suggestedFilename();
                Path savePath = resolveNonConflictingDownloadPath(downloadDir, suggestedFilename);
                download.saveAs(savePath);
                VerboseLogging.logInfoIfVerbose(logger,
                        "Download completed: {} -> {}", suggestedFilename, savePath.toAbsolutePath());
                // 登记已落盘文件，供业务层经 PlaywrightManager.getLastDownloadPath() 等查询（报告 §3.2 收尾）
                DownloadRegistry.instance().record(context, savePath);
            } catch (Exception e) {
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
        });
    }

    /**
     * 解析「不覆盖既有文件」的下载保存路径（同名去重）。
     * <p>
     * 若 {@code dir/name} 已存在，则在主文件名与扩展名之间插入序号后缀 {@code " (1)"} / {@code " (2)"} …，
     * 直到找到一个空闲路径；隐藏文件（如 {@code .gitignore}，点号在首位）整体作为主名处理，不加序号到扩展名。
     *
     * @param dir               下载目录（已确保存在）
     * @param suggestedFilename 服务器建议的文件名（来自 {@code Content-Disposition}）
     * @return 不与目录内现有文件冲突的绝对路径
     */
    static Path resolveNonConflictingDownloadPath(Path dir, String suggestedFilename) {
        Path candidate = dir.resolve(suggestedFilename);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String base = suggestedFilename;
        String ext = "";
        int dot = suggestedFilename.lastIndexOf('.');
        if (dot > 0) { // dot == 0 视为隐藏文件主名（如 ".env"），不拆扩展名
            base = suggestedFilename.substring(0, dot);
            ext = suggestedFilename.substring(dot);
        }
        int seq = 1;
        Path conflictFree;
        do {
            conflictFree = dir.resolve(base + " (" + seq + ")" + ext);
            seq++;
        } while (Files.exists(conflictFree));
        return conflictFree;
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