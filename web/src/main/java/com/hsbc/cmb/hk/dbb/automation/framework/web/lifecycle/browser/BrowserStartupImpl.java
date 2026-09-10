package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.state.PlaywrightRuntimeState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.provider.DefaultRuntimeProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextExecutor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTask;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ContextTaskResult;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.PageEventMonitor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.PlaywrightConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.config.ProxyConfigResolver;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestart;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestartImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCrashGuard;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.scenario.ScenarioLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.PlaywrightSerenityBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.SerenityBusBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.TestContextBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightContextManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightInitializer;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.media.PlaywrightScreenshotManager;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStackManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.CloudBrowserStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.LocalBrowserStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.InitializationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.codegen.spi.RoleCodegenBridgeRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import java.nio.file.Paths;
import java.awt.Dimension;

/** 
Browser startup orchestration (WEB-P1-1 Step 5).
 Package-private internal collaborator. */
public final class BrowserStartupImpl implements BrowserStartup {

    /** Default singleton instance (stateless, thread-safe). */
    public static final BrowserStartupImpl INSTANCE = new BrowserStartupImpl();

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightManager.class);

    private BrowserStartupImpl() {
    }

    /**
     * 确保浏览器已安装
     * 委托给 PlaywrightInitializer
     */
    public boolean ensureBrowserInstalledForType() {
        return PlaywrightInitializer.ensureBrowsersInstalled();
    }

    /**
     * 生成配置ID
     * 格式：{browserType}_{headless/headed}_{channel}
     * 
     * 注意：Firefox 和 WebKit 不支持 channel，会显示为空
     */
    public String generateConfigId() {
        String browserType = PlaywrightManager.config().getBrowserType();
        String headlessMode = PlaywrightManager.config().isHeadless() ? "headless" : "headed";
        String channel = PlaywrightManager.config().getBrowserChannel();
        
        // Firefox 和 WebKit 不支持 channel，忽略配置
        if ("firefox".equalsIgnoreCase(browserType) || "webkit".equalsIgnoreCase(browserType)) {
            return String.format("%s_%s", browserType, headlessMode);
        }
        
        // Chromium 系列浏览器包含 channel 信息
        if (channel != null && !channel.trim().isEmpty()) {
            return String.format("%s_%s_%s", browserType, headlessMode, channel);
        }
        
        // 无 channel 的 Chromium
        return String.format("%s_%s", browserType, headlessMode);
    }

    /**
     * 初始化 Playwright 实例
     */
    public void initializePlaywright(String configId) {
        VerboseLogging.logInfoIfVerbose(logger, "Initializing Playwright for config: {}", configId);

        try {
            Playwright.CreateOptions createOptions = getCreateOptions();
            Playwright playwright = Playwright.create(createOptions);
            PlaywrightRuntime.instance().state.putPlaywright(PlaywrightRuntime.instance().browserRegistry.keyFor(configId), playwright);
            VerboseLogging.logInfoIfVerbose(logger, "Playwright initialized successfully for config: {}", configId);
        } catch (Exception e) {
            VerboseLogging.logErrorIfVerbose(logger, "Failed to initialize Playwright for config: {}", configId, e);
            // 清理已创建的实例（如果有）
            if (PlaywrightRuntime.instance().state.hasPlaywright(PlaywrightRuntime.instance().browserRegistry.keyFor(configId))) {
                PlaywrightRuntime.instance().state.removePlaywright(PlaywrightRuntime.instance().browserRegistry.keyFor(configId));
            }
            throw new InitializationException("Failed to initialize Playwright for config: " + configId, e);
        }
    }

    public Playwright.CreateOptions getCreateOptions() {
        Playwright.CreateOptions options = new Playwright.CreateOptions();
        Map<String, String> env = new HashMap<>();

        // 设置浏览器缓存路径（转为绝对路径，确保 Node.js 子进程能找到）
        String browserPath = FrameworkConfigManager.getString(WebFrameworkConfig.PLAYWRIGHT_BROWSERS_PATH);
        if (browserPath == null || browserPath.trim().isEmpty()) {
            browserPath = ".playwright/browsers";
        }
        String absoluteBrowsersPath = Paths.get(browserPath).toAbsolutePath().toString();
        env.put("PLAYWRIGHT_BROWSERS_PATH", absoluteBrowsersPath);
        VerboseLogging.logDebugIfVerbose(logger, "Playwright browsers path: {}", absoluteBrowsersPath);

        // 跳过 Playwright.create() 内部的自动下载，因为我们已经通过 ensureBrowserInstalledForType() 管理下载
        // 如果不禁用，Node.js 进程启动时会尝试连接 cdn.playwright.dev 下载所有浏览器
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        VerboseLogging.logInfoIfVerbose(logger, "Playwright instance created with browser download disabled (managed separately)");

        // ── 将 Node.js 子进程的临时目录重定向到 .playwright/temp ──
        // Playwright 使用 os.tmpdir() 决定 Firefox profile、WebKit 运行时等临时文件位置
        // 默认指向系统临时目录 (C:\Users\...\AppData\Local\Temp)，不利于项目自包含
        // 通过 TMP/TEMP/TMPDIR 环境变量重定向，确保所有临时文件都在工程目录下
        String playwrightTemp = Paths.get(".playwright/temp").toAbsolutePath().toString();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            env.put("TMP", playwrightTemp);
            env.put("TEMP", playwrightTemp);
        } else {
            env.put("TMPDIR", playwrightTemp);
        }
        VerboseLogging.logInfoIfVerbose(logger, "Playwright temp directory redirected to: {}", playwrightTemp);

        // ── 抑制 Node.js 的 url.parse 弃用警告 (DEP0169) ──
        // Playwright 驱动内部 Node.js 代码使用了 url.parse()，这在 Node 11+ 已弃用
        // 控制台每次启动都会打印 (node:xxx) [DEP0169] DeprecationWarning 噪音
        env.put("NODE_OPTIONS", "--no-deprecation");

        // ==================== 代理透传（浏览器启动策略注入） ====================
        // 本地策略无注入；云端 Local 模式由 CloudBrowserStrategy 经 BrowserStackManager 注入隧道代理。
        resolveStrategy().injectEnvironment(env);

        options.setEnv(env);

        return options;
    }

    /**
     * 初始化 Browser 实例。
     * <p>调用方应持有 BROWSER_LOCK。
     * <p>浏览器下载为幂等检查（已安装时毫秒级返回），切换浏览器类型时可能需要下载新类型。
     */
    public void initializeBrowser(String configId) {
        VerboseLogging.logInfoIfVerbose(logger, "Initializing Browser for config: {}", configId);

        // 双重检查：如果已经有连接的浏览器实例，直接返回
        Browser existingBrowser = PlaywrightRuntime.instance().state.getBrowser(PlaywrightRuntime.instance().browserRegistry.keyFor(configId));
        if (existingBrowser != null && existingBrowser.isConnected()) {
            VerboseLogging.logInfoIfVerbose(logger, "Browser already initialized and connected for config: {}", configId);
            return;
        }

        // 关闭现有浏览器实例（如果存在但未连接）
        if (existingBrowser != null) {
            VerboseLogging.logInfoIfVerbose(logger, "Closing stale browser instance for config: {}", configId);
            try {
                existingBrowser.close();
            } catch (Exception e) {
                VerboseLogging.logWarnIfVerbose(logger, "Failed to close stale browser, continuing", e);
            }
        }

        String browserType = PlaywrightManager.config().getBrowserType();

        // 确保浏览器二进制已安装（幂等：已安装时毫秒级返回）
        // 场景1: PlaywrightManager.getBrowser() 已在锁外调用过 → 毫秒级 no-op
        // 场景2: handleBrowserTypeSwitch 切换类型 → 可能需要下载新浏览器
        ensureBrowserInstalledForType();

        // 确保 Playwright 实例最新（不在 initialize() 中提前创建）
        // 时刻保持：Playwright 实例在浏览器二进制就绪后创建
        if (PlaywrightRuntime.instance().state.hasPlaywright(PlaywrightRuntime.instance().browserRegistry.keyFor(configId))) {
            Playwright oldPw = PlaywrightRuntime.instance().state.removePlaywright(PlaywrightRuntime.instance().browserRegistry.keyFor(configId));
            try { oldPw.close(); } catch (Exception e) { logger.warn("[Playwright] Failed to close old playwright instance for config {}: {}", configId, e.getMessage()); }
        }
        initializePlaywright(configId);

        Playwright playwright = PlaywrightRuntime.instance().state.getPlaywright(PlaywrightRuntime.instance().browserRegistry.keyFor(configId));
        if (playwright == null) {
            throw new InitializationException("Playwright instance is null after initialization for config: " + configId);
        }

        // 获取浏览器配置
        boolean headless = PlaywrightManager.config().isHeadless();
        int slowMo = PlaywrightManager.config().getBrowserSlowMo();
        int timeout = PlaywrightManager.config().getBrowserTimeout();

        // Firefox 在 Windows 上启动偏慢（Juggler 协议初始化 + profile 创建 + 杀软扫描）
        // 自动给 Firefox 1.5 倍超时兜底，避免用户未考虑启动速度差异导致超时
        if ("firefox".equalsIgnoreCase(browserType) && timeout < 45000) {
            int originalTimeout = timeout;
            timeout = Math.max(timeout, 30000);  // 最小 30s
            if (timeout < 45000) timeout = (int)(originalTimeout * 1.5);
            VerboseLogging.logInfoIfVerbose(logger,
                "[Browser Init] Firefox detected: auto-adjusting launch timeout {}ms → {}ms", originalTimeout, timeout);
        }

        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setSlowMo(slowMo)
                .setTimeout(timeout);

        // 配置窗口大小和启动参数
        configureBrowserLaunchOptions(launchOptions);

        // 重试次数由策略决定：云端连接失败重试无意义，本地允许进程抖动重试。
        int maxRetries = resolveStrategy().maxRetries();
        Exception lastException = null;

        long initStart = System.currentTimeMillis();
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                VerboseLogging.logInfoIfVerbose(logger, "[Browser Init] Launching browser (attempt {}/{}): type={}, channel={}, headless={}",
                    attempt, maxRetries, browserType, PlaywrightManager.config().getBrowserChannel(), headless);
                
                Browser browser = setupBrowser(playwright, browserType, launchOptions);
                PlaywrightRuntime.instance().browserCleanup.registerBrowserDisconnectGuard(browser);
                PlaywrightRuntime.instance().state.putBrowser(PlaywrightRuntime.instance().browserRegistry.keyFor(configId), browser);

                long elapsed = System.currentTimeMillis() - initStart;
                VerboseLogging.logInfoIfVerbose(logger, "[Browser Init] Browser initialized in {}ms: {} for config: {}",
                    elapsed, browserType, configId);
                return;
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    long backoffMs = 2000L * attempt;
                    VerboseLogging.logWarnIfVerbose(logger,
                        "[Browser Init] Launch attempt {} failed: {}. Retrying in {}ms...",
                        attempt, com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStackManager.sanitizeMessage(e.getMessage()), backoffMs);
                    // 退避等待：基于 LockSupport.parkNanos（不调用 Thread.sleep，也不依赖 ForkJoinPool）
                    PlaywrightManager.parkMillis(backoffMs);
                }
            }
        }

        VerboseLogging.logErrorIfVerbose(logger, "Failed to initialize Browser after {} attempts for config: {}", maxRetries, configId, lastException);
        PlaywrightRuntime.instance().state.removeBrowser(PlaywrightRuntime.instance().browserRegistry.keyFor(configId));
        //  修复 Medium：启动失败路径必须释放 L292 已创建的 Playwright 节点子进程，
        // 否则仅移除 Browser 实例仍会泄漏 Playwright 实例持有的进程。
        Playwright leakedPw = PlaywrightRuntime.instance().state.removePlaywright(PlaywrightRuntime.instance().browserRegistry.keyFor(configId));
        if (leakedPw != null) {
            try {
                leakedPw.close();
            } catch (Exception e) {
                logger.warn("[Playwright] Failed to close leaked playwright instance for config {}: {}", configId, e.getMessage());
            }
        }
        throw new BrowserException("Failed to initialize Browser for config: " + configId, lastException);
    }

    /**
     * 配置浏览器启动选项
     */
    public void configureBrowserLaunchOptions(BrowserType.LaunchOptions launchOptions) {
        // 获取逻辑屏幕尺寸
        Dimension screenSize = PlaywrightManager.config().getAvailableScreenSize();
        int screenWidth = (int) screenSize.getWidth();
        int screenHeight = (int) screenSize.getHeight();

        // 获取浏览器类型
        String browserType = PlaywrightManager.config().getBrowserType();
        boolean isChromium = PlaywrightManager.config().isChromiumBased(browserType);

        // 构建启动参数
        List<String> args = new ArrayList<>();

        // 添加用户配置的浏览器启动参数（已根据浏览器类型自动选择）
        String browserArgs = PlaywrightManager.config().getBrowserArgs();
        if (browserArgs != null && !browserArgs.trim().isEmpty()) {
            String[] argsArray = browserArgs.split(",");
            for (String arg : argsArray) {
                if (!arg.trim().isEmpty() && !args.contains(arg.trim())) {
                    args.add(arg.trim());
                }
            }
        }

        // ── 窗口最大化：Chromium 用 launch args 设位置+尺寸，Firefox/WebKit 在 stabilizePage 中用 JS ──
        // Chromium 的 --window-position 和 --window-size 是最可靠的窗口定位手段
        // Firefox/WebKit 不支持这些 flag，只能走 JavaScript window.resizeTo/moveTo
        boolean maximize = PlaywrightManager.config().isWindowMaximize();
        if (isChromium && maximize) {
            if (!args.contains("--window-position=0,0")) {
                args.add(0, "--window-position=0,0");
            }
            String windowSizeArg = String.format("--window-size=%d,%d", screenWidth, screenHeight);
            if (!args.contains(windowSizeArg)) {
                args.add(1, windowSizeArg);
            }
        }

        if (!args.isEmpty()) {
            launchOptions.setArgs(args);
            logger.info("Browser args: {}", args);
        }

        // 设置浏览器 channel（仅适用于 Chromium 系列浏览器）
        String channel = PlaywrightManager.config().getBrowserChannel();
        if (channel != null && !channel.isEmpty() && isChromium) {
            launchOptions.setChannel(channel);
            logger.info("Browser channel: {}", channel);
        } else if (channel != null && !channel.isEmpty() && !isChromium) {
            VerboseLogging.logDebugIfVerbose(logger,
                "Ignoring browser channel '{}' for browser type '{}' (channel only applies to Chromium-based browsers)",
                channel, browserType);
        }

        // 设置浏览器可执行文件路径（用于启动本地安装的浏览器）
        String executablePath = PlaywrightManager.config().getBrowserExecutablePath();
        if (executablePath != null && !executablePath.trim().isEmpty()) {
            launchOptions.setExecutablePath(Paths.get(executablePath.trim()));
            logger.info("Browser executable path: {}", executablePath);  // 保留浏览器路径日志，这很重要
        }
    }

    /**
     * 解析当前浏览器启动策略：BrowserStack 启用则云端策略，否则本地策略。
     * T4-1：消除 PlaywrightManager 内 "if (cloud) ... else ..." 硬编码分支，
     * 新增接入方式只需新增 BrowserStrategy 实现并在此注册，无需改动 lifecycle 源码（OCP）。
     */
    public BrowserStrategy resolveStrategy() {
        return BrowserStackManager.isBrowserStackEnabled()
                ? new CloudBrowserStrategy()
                : new LocalBrowserStrategy();
    }

    /**
     * 根据当前策略建立浏览器连接（本地 launch / 云端 connect）。
     * T4-1：原 "if (BrowserStack enabled) ... else ..." 硬编码分支已下沉到 BrowserStrategy 实现。
     */
    public Browser setupBrowser(Playwright playwright, String browserType, BrowserType.LaunchOptions
            launchOptions) {
        if (playwright == null) {
            throw new IllegalArgumentException("Playwright instance cannot be null");
        }

        try {
            return resolveStrategy().connect(playwright, browserType, launchOptions);
        } catch (Exception e) {
            logger.error("Failed to launch browser {} with options: {}", browserType, launchOptions, e);

            // 提供更详细的错误信息
            if (e instanceof TimeoutError) {
                logger.error("Browser launch timed out. Consider increasing timeout or checking browser installation.");
            }

            throw new BrowserException("Failed to launch browser " + browserType, e);
        }
    }

    /**
     * 初始化整个 Playwright 环境
     * 由 FrameworkCore 调用，用于测试套件开始时
     * 
     * 注意：此方法只初始化 Playwright 实例，不启动浏览器
     * 浏览器会在首次调用 PlaywrightManager.getBrowser() 或 PlaywrightManager.getPage() 时启动
     * 这样可以支持 @AutoBrowser 动态浏览器切换，避免启动多余的浏览器实例
     */
    public synchronized void initialize() {
        //  修复 H6/H8：用状态根的 fullInit 作幂等判据（PlaywrightManager.frameworkState.isInitialized 在浏览器就绪前可能为 false，
        // 旧判据导致每次进入都重算 configId；static synchronized 已提供类级互斥，fullInit 再补可见性判据）。
        if (PlaywrightRuntime.instance().state.isFullInit() && TestContextHolder.get().get(PlaywrightManager.CURRENT_CONFIG_ID_KEY) != null) {
            VerboseLogging.logInfoIfVerbose(logger, "Playwright environment already initialized with config: {}", TestContextHolder.get().get(PlaywrightManager.CURRENT_CONFIG_ID_KEY));
            return;
        }

        String configId = generateConfigId();
        VerboseLogging.logInfoIfVerbose(logger, "Initializing Playwright environment with config: {}", configId);
        // ⚠ 不在此时创建 Playwright 实例（Node.js 子进程）
        // 原因：浏览器可能尚未安装，提前创建会导致初次 launch 时找不到二进制
        // Playwright 实例延迟到 initializeBrowser() 中、浏览器就绪后再创建
        TestContextHolder.get().set(PlaywrightManager.CURRENT_CONFIG_ID_KEY,configId);
        PlaywrightRuntime.instance().state.markFullInit();

        VerboseLogging.logInfoIfVerbose(logger, "Playwright environment initialized (Playwright/Browser deferred to first access)");
    }

}
