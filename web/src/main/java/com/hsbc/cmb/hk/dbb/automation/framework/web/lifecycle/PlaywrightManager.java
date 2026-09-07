package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStackManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.CloudBrowserStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.LocalBrowserStrategy;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.AutoBrowserProcessor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.InitializationException;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.codegen.spi.RoleCodegenBridgeRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Dimension;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;


/**
 * 企业级 Playwright Manager - 管理 Playwright 实例、Browser、Context 和 Page
 * 特性：
 * - 支持 Serenity BDD 集成
 * - 线程安全
 * - 灵活的浏览器生命周期管理
 * - 支持不同级别的浏览器启动策略
 * - 避免静态初始化问题
 */
public class PlaywrightManager {

    // ==================== 静态常量 ====================

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightManager.class);

    // ==================== 非 ThreadLocal 静态变量 ====================
    // 线程安全的实例回收容器（T3-2 企业级隔离）。
    // ⚠️ 默认不变式：Map 的 VALUE（Browser/Playwright 实例）绝不跨线程共享——
    // 存储键为 "threadId:configId"（见 {@link #keyFor}），保证每个 worker 线程拥有独立实例。
    // 共享 ConcurrentHashMap 仅作为跨线程安全的回收/清理容器（供 cleanupAll 统一关闭），
    // 不再像旧实现那样按 configId 跨线程复用同一个 Browser（评审 P0：单点故障 + 全局串行化）。
    //
    //  例外——共享 Browser 模式（serenity.playwright.shared.browser.enabled=true）：
    // 此时 keyFor() 返回 "shared:configId"，所有线程【有意】复用同一个 Browser 实例，
    // 隔离性改由 per-thread 的 BrowserContext 保证（Playwright 官方并发模型）。
    // 该模式下的配套约束见 restartBrowser()：重启降级为「仅重建本线程 Context」，绝不关闭共享 Browser。
    private static final ConcurrentMap<String, Playwright> playwrightInstances = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Browser> browserInstances = new ConcurrentHashMap<>();

    // 浏览器断开标记集合（onDisconnected 事件填充）：用于 getPage()/getContext() 快速失败，
    // 避免浏览器进程崩溃/被杀后继续操作抛出晦涩的 Playwright 底层异常。
    private static final java.util.Set<Browser> DISCONNECTED_BROWSERS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // 线程级锁（T3-2）：Browser 创建/关闭现已按 threadId 隔离，无需全局互斥。
    // 降级为 per-thread 锁可在放开并行创建的同时，仍保证单线程内创建/切换的原子性。
    //  T3-1 收拢：原 withInitial(Object::new) 迁入 TestContext，经 perThreadBrowserLock() 惰性创建，
    // 且创建后缓存在上下文中 → 同一线程内多次取到同一锁对象（与原 ThreadLocal 语义一致，锁语义不变）。
    // ⚠️ 与下方既有的 browserLock()（无参，按共享模式派发）/ browserLock(boolean) 区分命名，避免同名冲突与递归。
    private static final ContextKey<Object> BROWSER_LOCK_KEY = ContextKey.of("playwrightManager.browserLock", Object.class);

    /** 取本线程的锁对象（严格等价原 BROWSER_LOCK.get()，不随共享模式切换）。 */
    private static Object perThreadBrowserLock() {
        return TestContextHolder.get().computeIfAbsent(BROWSER_LOCK_KEY, Object::new);
    }

    //  共享 Browser 模式（一 Browser + 多 Context）下的【进程级】互斥锁。
    // 该模式下 Browser 被所有 worker 线程共享，必须用真正的静态锁保证并发只有一个 Browser 被创建；
    // per-thread 的 BROWSER_LOCK 在共享模式下无法提供跨线程互斥（每个线程拿到的都是各自的锁对象）。
    private static final Object SHARED_BROWSER_LOCK = new Object();

    // 共享模式下 Browser/Playwright 实例的存储键前缀（去掉 threadId 维度，使所有线程命中同一实例）
    private static final String SHARED_KEY_PREFIX = "shared:";

    // Context/Page 细粒度锁：保护 Context 和 Page 创建/销毁
    private static final Object CONTEXT_LOCK = new Object();
    private static final Object PAGE_LOCK = new Object();

    // 已废弃的 configId 集合：浏览器类型切换时设置，其它线程进入 getContext()/getPage()
    // 检测到自己的 configId 已被废弃后强制重建，避免绑定到即将关闭的旧 Browser（竞态窗口修复 1.2）。
    // P2-18 修复：使用并发 Set 支持多个并发废弃 ID，避免覆盖丢失。
    private static final java.util.Set<String> RETIRED_CONFIG_IDS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // 框架状态引用
    static final FrameworkState frameworkState = FrameworkState.getInstance();

    //  修复 H6/H8：初始化幂等判据（static synchronized 已提供类级互斥，再补一道可见性判据）
    private static final AtomicBoolean FULL_INIT = new AtomicBoolean(false);

    // ==================== per-thread 变量（ T3-1 收拢：原 3 个 static ThreadLocal 迁入 TestContext，
    //  均为默认 null 语义，迁移后等价；包级可见性保持不变，供同包 PlaywrightSerenityBridge 等访问） ====================

    // ---- 核心 Page/Context ----
    static final ContextKey<BrowserContext> CONTEXT_KEY = ContextKey.of("playwrightManager.context", BrowserContext.class);
    static final ContextKey<Page> PAGE_KEY = ContextKey.of("playwrightManager.page", Page.class);

    // ---- 配置标识 ----
    static final ContextKey<String> CURRENT_CONFIG_ID_KEY = ContextKey.of("playwrightManager.currentConfigId", String.class);

    // ==================== 静态初始化块 ====================

    static {
        // 委托给 PlaywrightInitializer 处理初始化逻辑
        PlaywrightInitializer.initializePlaywrightPaths();
        PlaywrightInitializer.cleanupPlaywrightTempDirs();
        // 浏览器下载延迟到实际需要时，不在静态初始化阶段下载
    }


    // ==================== 初始化相关方法（委托给 PlaywrightInitializer） ====================
    
    /**
     * 确保浏览器已安装
     * 委托给 PlaywrightInitializer
     */
    private static boolean ensureBrowserInstalledForType() {
        return PlaywrightInitializer.ensureBrowsersInstalled();
    }

    // ==================== 生命周期管理方法 ====================

    /**
     * 初始化整个 Playwright 环境
     * 由 FrameworkCore 调用，用于测试套件开始时
     * 
     * 注意：此方法只初始化 Playwright 实例，不启动浏览器
     * 浏览器会在首次调用 getBrowser() 或 getPage() 时启动
     * 这样可以支持 @AutoBrowser 动态浏览器切换，避免启动多余的浏览器实例
     */
    public static synchronized void initialize() {
        //  修复 H6/H8：用 FULL_INIT 作幂等判据（frameworkState.isInitialized 在浏览器就绪前可能为 false，
        // 旧判据导致每次进入都重算 configId；static synchronized 已提供类级互斥，FULL_INIT 再补可见性判据）。
        if (FULL_INIT.get() && TestContextHolder.get().get(CURRENT_CONFIG_ID_KEY) != null) {
            VerboseLogging.logInfoIfVerbose(logger, "Playwright environment already initialized with config: {}", TestContextHolder.get().get(CURRENT_CONFIG_ID_KEY));
            return;
        }

        String configId = generateConfigId();
        VerboseLogging.logInfoIfVerbose(logger, "Initializing Playwright environment with config: {}", configId);
        // ⚠ 不在此时创建 Playwright 实例（Node.js 子进程）
        // 原因：浏览器可能尚未安装，提前创建会导致初次 launch 时找不到二进制
        // Playwright 实例延迟到 initializeBrowser() 中、浏览器就绪后再创建
        TestContextHolder.get().set(CURRENT_CONFIG_ID_KEY,configId);
        FULL_INIT.set(true);

        VerboseLogging.logInfoIfVerbose(logger, "Playwright environment initialized (Playwright/Browser deferred to first access)");
    }

    /**
     * 生成配置ID
     * 格式：{browserType}_{headless/headed}_{channel}
     * 
     * 注意：Firefox 和 WebKit 不支持 channel，会显示为空
     */
    private static String generateConfigId() {
        String browserType = config().getBrowserType();
        String headlessMode = config().isHeadless() ? "headless" : "headed";
        String channel = config().getBrowserChannel();
        
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
    private static void initializePlaywright(String configId) {
        VerboseLogging.logInfoIfVerbose(logger, "Initializing Playwright for config: {}", configId);

        try {
            Playwright.CreateOptions createOptions = getCreateOptions();
            Playwright playwright = Playwright.create(createOptions);
            playwrightInstances.put(keyFor(configId), playwright);
            VerboseLogging.logInfoIfVerbose(logger, "Playwright initialized successfully for config: {}", configId);
        } catch (Exception e) {
            VerboseLogging.logErrorIfVerbose(logger, "Failed to initialize Playwright for config: {}", configId, e);
            // 清理已创建的实例（如果有）
            if (playwrightInstances.containsKey(keyFor(configId))) {
                playwrightInstances.remove(keyFor(configId));
            }
            throw new InitializationException("Failed to initialize Playwright for config: " + configId, e);
        }
    }

    private static Playwright.CreateOptions getCreateOptions() {
        Playwright.CreateOptions options = new Playwright.CreateOptions();
        Map<String, String> env = new HashMap<>();

        // 设置浏览器缓存路径（转为绝对路径，确保 Node.js 子进程能找到）
        String browserPath = FrameworkConfigManager.getString(FrameworkConfig.PLAYWRIGHT_BROWSERS_PATH);
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
    private static void initializeBrowser(String configId) {
        VerboseLogging.logInfoIfVerbose(logger, "Initializing Browser for config: {}", configId);

        // 双重检查：如果已经有连接的浏览器实例，直接返回
        Browser existingBrowser = browserInstances.get(keyFor(configId));
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

        String browserType = config().getBrowserType();

        // 确保浏览器二进制已安装（幂等：已安装时毫秒级返回）
        // 场景1: getBrowser() 已在锁外调用过 → 毫秒级 no-op
        // 场景2: handleBrowserTypeSwitch 切换类型 → 可能需要下载新浏览器
        ensureBrowserInstalledForType();

        // 确保 Playwright 实例最新（不在 initialize() 中提前创建）
        // 时刻保持：Playwright 实例在浏览器二进制就绪后创建
        if (playwrightInstances.containsKey(keyFor(configId))) {
            Playwright oldPw = playwrightInstances.remove(keyFor(configId));
            try { oldPw.close(); } catch (Exception e) { logger.warn("[Playwright] Failed to close old playwright instance for config {}: {}", configId, e.getMessage()); }
        }
        initializePlaywright(configId);

        Playwright playwright = playwrightInstances.get(keyFor(configId));
        if (playwright == null) {
            throw new InitializationException("Playwright instance is null after initialization for config: " + configId);
        }

        // 获取浏览器配置
        boolean headless = config().isHeadless();
        int slowMo = config().getBrowserSlowMo();
        int timeout = config().getBrowserTimeout();

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
                    attempt, maxRetries, browserType, config().getBrowserChannel(), headless);
                
                Browser browser = setupBrowser(playwright, browserType, launchOptions);
                registerBrowserDisconnectGuard(browser);
                browserInstances.put(keyFor(configId), browser);

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
                    parkMillis(backoffMs);
                }
            }
        }

        VerboseLogging.logErrorIfVerbose(logger, "Failed to initialize Browser after {} attempts for config: {}", maxRetries, configId, lastException);
        browserInstances.remove(keyFor(configId));
        //  修复 Medium：启动失败路径必须释放 L292 已创建的 Playwright 节点子进程，
        // 否则仅 remove browserInstances 仍会泄漏 playwrightInstances 持有的进程。
        Playwright leakedPw = playwrightInstances.remove(keyFor(configId));
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
     * 注册浏览器断开守卫：浏览器进程意外断开（崩溃/被杀/连接丢失）时记录严重错误，
     * 并标记该 Browser 已断开，使后续 getPage()/getContext() 快速失败并给出语义化异常，
     * 而非抛出晦涩的 Playwright 底层 NPE/StateError。
     */
    private static void registerBrowserDisconnectGuard(Browser browser) {
        browser.onDisconnected(disconnected -> {
            logger.error("[browser-disconnected] Browser disconnected unexpectedly: type={}",
                    disconnected.browserType() != null ? disconnected.browserType().name() : "unknown");
            DISCONNECTED_BROWSERS.add(browser);
        });
    }

    /** 当前测试线程关联的 Browser 是否已被标记为断开。 */
    private static boolean isCurrentBrowserDisconnected() {
        BrowserContext ctx = TestContextHolder.get().get(CONTEXT_KEY);
        Browser b = ctx != null ? ctx.browser() : null;
        return b != null && DISCONNECTED_BROWSERS.contains(b);
    }

    /**
     * 配置浏览器启动选项
     */
    private static void configureBrowserLaunchOptions(BrowserType.LaunchOptions launchOptions) {
        // 获取逻辑屏幕尺寸
        Dimension screenSize = config().getAvailableScreenSize();
        int screenWidth = (int) screenSize.getWidth();
        int screenHeight = (int) screenSize.getHeight();

        // 获取浏览器类型
        String browserType = config().getBrowserType();
        boolean isChromium = config().isChromiumBased(browserType);

        // 构建启动参数
        List<String> args = new ArrayList<>();

        // 添加用户配置的浏览器启动参数（已根据浏览器类型自动选择）
        String browserArgs = config().getBrowserArgs();
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
        boolean maximize = config().isWindowMaximize();
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
        String channel = config().getBrowserChannel();
        if (channel != null && !channel.isEmpty() && isChromium) {
            launchOptions.setChannel(channel);
            logger.info("Browser channel: {}", channel);
        } else if (channel != null && !channel.isEmpty() && !isChromium) {
            VerboseLogging.logDebugIfVerbose(logger,
                "Ignoring browser channel '{}' for browser type '{}' (channel only applies to Chromium-based browsers)",
                channel, browserType);
        }

        // 设置浏览器可执行文件路径（用于启动本地安装的浏览器）
        String executablePath = config().getBrowserExecutablePath();
        if (executablePath != null && !executablePath.trim().isEmpty()) {
            launchOptions.setExecutablePath(Paths.get(executablePath.trim()));
            logger.info("Browser executable path: {}", executablePath);  // 保留浏览器路径日志，这很重要
        }
    }

    /**
     * 根据当前策略建立浏览器连接（本地 launch / 云端 connect）。
     * T4-1：原 "if (BrowserStack enabled) ... else ..." 硬编码分支已下沉到 BrowserStrategy 实现。
     */
    private static Browser setupBrowser(Playwright playwright, String browserType, BrowserType.LaunchOptions
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
     * 解析当前浏览器启动策略：BrowserStack 启用则云端策略，否则本地策略。
     * T4-1：消除 PlaywrightManager 内 "if (cloud) ... else ..." 硬编码分支，
     * 新增接入方式只需新增 BrowserStrategy 实现并在此注册，无需改动 lifecycle 源码（OCP）。
     */
    private static BrowserStrategy resolveStrategy() {
        return BrowserStackManager.isBrowserStackEnabled()
                ? new CloudBrowserStrategy()
                : new LocalBrowserStrategy();
    }


    // ==================== 实例访问方法 ====================

    /**
     * 获取当前配置ID
     */
    private static String getCurrentConfigId() {
        if (TestContextHolder.get().get(CURRENT_CONFIG_ID_KEY) == null) {
            TestContextHolder.get().set(CURRENT_CONFIG_ID_KEY,generateConfigId());
            // 修复 4.1：懒初始化 configId 时同步标记 frameworkState 为已初始化，
            // 避免 getContext()/getPage() 因 frameworkState 未初始化而抛 IllegalStateException。
            frameworkState.markInitialized();
        }
        return TestContextHolder.get().get(CURRENT_CONFIG_ID_KEY);
    }

    /**
     * 懒重建（或返回）当前 configId。
     * <p>供 scenario/feature 级清理（{@code cleanupForScenario} 会移除 {@code currentConfigId}）之后，
     * {@code beforeTest} 恢复环境使用：若 frameworkState 仍 initialized 但 configId 已被清空，
     * 此处就地重建，避免 {@code initializeForScenario} 误报"环境未初始化"而级联抛错。
     */
    public static String ensureConfigId() {
        return getCurrentConfigId();
    }

    /**
     * 构造「线程隔离」存储键（T3-2 企业级隔离）。
     * <p>旧实现按 configId 在共享 Map 中跨线程复用同一 Browser 实例（评审 P0：单点故障 + 全局串行化）。
     * 现以 {@code threadId:configId} 为键，使每个 worker 线程拥有独立 Browser/Playwright 实例——
     * 并行场景下各 scenario 线程互不共享 Browser 对象，故障与 {@code restartBrowser} 作用域均收敛到本线程。
     * 共享 {@code ConcurrentHashMap} 仅作为线程安全的回收容器，KEY 保证 VALUE 永不跨线程共享。</p>
     *
     * @param configId 当前线程的浏览器配置标识
     * @return 线程隔离的存储键
     */
    /**
     * JVM 级<b>不可变</b>开关：是否启用「共享 Browser」模式（一个 Browser 实例 + 多 Context 并发）。
     *
     * <p><b>为何在类加载时解析一次、而不是每次现读配置：</b>{@link #keyFor(String)} 决定了 Browser 实例
     * 在 {@link #browserInstances} 中的存储键。若该开关在 JVM 运行期间发生翻转，同一个 configId 会先后
     * 映射到不同的键，使已创建的 Browser 变成<b>无法回收的孤儿实例</b>（既不在当前键上，
     * 也只有 cleanupAll 能扫到）。因此并发隔离模型必须对 JVM 生命周期全局稳定。
     * <p>由 {@code serenity.playwright.shared.browser.enabled} 控制，默认 {@code false}
     * （保持 T3-2 每线程独立 Browser 的既有行为）。
     */
    static final boolean SHARED_BROWSER_MODE = resolveSharedBrowserMode();

    /**
     * 当前 JVM 是否启用「共享 Browser」模式。
     *
     * @return true 表示共享单个 Browser，各线程通过独立 BrowserContext 隔离
     * @apiNote <b>框架内部能力（生命周期决策用）</b>，业务 Page / 业务步骤请勿依赖：
     *          该取值决定并发隔离模型，业务侧依赖它会导致与框架生命周期耦合。
     */
    static boolean isSharedBrowserMode() {
        return SHARED_BROWSER_MODE;
    }

    /**
     * 解析共享 Browser 开关的原始配置值（<b>纯函数</b>，便于单测覆盖各种输入）。
     *
     * <p>容错策略：{@code null} / 空白 → false（默认值）；无法识别的非法值 → false 并<b>告警</b>，
     * 避免静默降级后被误认为「已开启」而难以排查。</p>
     *
     * @param rawValue 原始配置值，可为 null
     * @return 是否启用共享 Browser 模式
     */
    static boolean parseSharedBrowserMode(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return false;
        }
        String normalized = rawValue.trim();
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        logger.warn("[shared-browser] Unrecognized value '{}' for "
                        + "serenity.playwright.shared.browser.enabled (expected true/false); falling back to false",
                rawValue);
        return false;
    }

    /**
     * 计算 Browser/Playwright 实例的存储键（<b>纯函数</b>，便于单测覆盖两种模式）。
     *
     * @param configId   当前线程的浏览器配置标识
     * @param sharedMode 是否共享 Browser 模式
     * @return 共享模式为 {@code "shared:<configId>"}（所有线程一致）；
     *         否则为 {@code "<threadId>:<configId>"}（每线程唯一）
     * @throws IllegalArgumentException configId 为 null 或空白时抛出（否则会生成无法定位的孤儿键）
     */
    static String keyFor(String configId, boolean sharedMode) {
        if (configId == null || configId.isBlank()) {
            throw new IllegalArgumentException(
                    "configId must not be null or blank: it would produce an unusable Browser instance key");
        }
        return sharedMode
                ? SHARED_KEY_PREFIX + configId
                : Thread.currentThread().getId() + ":" + configId;
    }

    private static String keyFor(String configId) {
        return keyFor(configId, SHARED_BROWSER_MODE);
    }

    /**
     * Browser 创建/切换所用的互斥锁（<b>纯函数</b>，便于单测覆盖两种模式）。
     * <p>共享模式返回进程级 {@link #SHARED_BROWSER_LOCK}（保证并发只创建一个 Browser）；
     * 否则返回 per-thread {@link #BROWSER_LOCK}（T3-2：各线程创建互不阻塞）。</p>
     */
    static Object browserLock(boolean sharedMode) {
        return sharedMode ? SHARED_BROWSER_LOCK : perThreadBrowserLock();
    }

    private static Object browserLock() {
        return browserLock(SHARED_BROWSER_MODE);
    }

    private static boolean resolveSharedBrowserMode() {
        return parseSharedBrowserMode(FrameworkConfig.PLAYWRIGHT_SHARED_BROWSER_ENABLED.getValue());
    }

    /**
     * 获取 Playwright 实例
     */
    public static Playwright getPlaywright() {
        String configId = getCurrentConfigId();
        if (configId == null) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }
        return playwrightInstances.get(keyFor(configId));
    }

    /**
     * 获取 Browser 实例（支持动态浏览器切换）
     *
     * 新特性：自动检测浏览器类型，不依赖Cucumber hooks
     * 在首次访问时自动从scenario标签中提取浏览器类型并切换
     */
    public static Browser getBrowser() {
        // 获取当前的配置ID
        String currentConfig = getCurrentConfigId();
        if (currentConfig == null) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }

        // 获取期望的浏览器类型（可能来自 @AutoBrowser 标签）
        String desiredBrowserType = config().getBrowserType();
        
        // 快速路径：检查当前浏览器实例是否有效（无锁）
        Browser currentBrowser = browserInstances.get(keyFor(currentConfig));
        if (currentBrowser != null && currentBrowser.isConnected()) {
            // 浏览器已存在且连接正常，检查是否需要切换
            String[] configParts = currentConfig.split("_");
            String currentBrowserType = configParts.length > 0 ? configParts[0] : "chromium";
            
            if (!currentBrowserType.equalsIgnoreCase(desiredBrowserType)) {
                return handleBrowserTypeSwitch(currentConfig, currentBrowser, currentBrowserType, desiredBrowserType);
            }
            
            return currentBrowser;
        }
        
        // ⚠ 在加锁前确保浏览器已安装（下载可能耗时数分钟，不应持有 BROWSER_LOCK）
        // 对已安装的情况仅做快速检查（毫秒级），不会阻塞其他线程
        ensureBrowserInstalledForType();
        
        // 慢速路径：浏览器不存在或断开，加锁创建
        synchronized (perThreadBrowserLock()) {
            // 双重检查：另一个线程可能已在等待期间创建了浏览器
            currentBrowser = browserInstances.get(keyFor(currentConfig));
            if (currentBrowser != null && currentBrowser.isConnected()) {
                return currentBrowser;
            }
            
            logger.info("[getBrowser] Browser not initialized yet, initializing with desired type: {}", desiredBrowserType);
            
            // 如果 currentConfig 中的浏览器类型与期望类型不同，更新 configId
            String[] configParts = currentConfig.split("_");
            String configBrowserType = configParts.length > 0 ? configParts[0] : "chromium";
            
            if (!configBrowserType.equalsIgnoreCase(desiredBrowserType)) {
                // 生成新的 configId（使用期望的浏览器类型）
                String newConfigId = generateConfigId();
                logger.info("[getBrowser] Updating configId from {} to {} for browser type: {}",
                    currentConfig, newConfigId, desiredBrowserType);
                TestContextHolder.get().set(CURRENT_CONFIG_ID_KEY,newConfigId);
                currentConfig = newConfigId;
            }
            
            // 初始化浏览器
            initializeBrowser(currentConfig);
            
            return browserInstances.get(keyFor(currentConfig));
        }
    }

    /**
     * 处理浏览器类型切换逻辑
     *
     * <p> 锁安全设计：closePage/closeContext 在 BROWSER_LOCK 之外执行，
     * 避免 BROWSER_LOCK → PAGE_LOCK → CONTEXT_LOCK 与 getPage() 的
     * PAGE_LOCK → CONTEXT_LOCK 形成死锁链。
     */
    private static Browser handleBrowserTypeSwitch(String currentConfig, Browser currentBrowser,
                                                    String currentBrowserType, String desiredBrowserType) {
        logger.info("[getBrowser] Browser type changed: {} -> {}", currentBrowserType, desiredBrowserType);
        logger.info("[getBrowser] Switching browser...");

        //  1. 在 BROWSER_LOCK 之外关闭旧 Context 和 Page（避免死锁）
        //  修复 H7：先把当前 configId 标记为"已废弃"，再关闭旧 Context/Page。
        // 顺序上移确保并发线程在 BROWSER_LOCK 外即可感知 retired 并重建立即生效（见 getContext 的 retired 预检），
        // 避免"标记前已取到旧 context"的竞态窗口。
        RETIRED_CONFIG_IDS.add(currentConfig);

        closePage();
        closeContext();

        //  2. 在 BROWSER_LOCK 内关闭旧浏览器 + 初始化新浏览器
        //      （共享模式下使用进程级锁：Browser 被所有线程共享，切换必须全局互斥）
        synchronized (perThreadBrowserLock()) {
            // 关闭旧浏览器
            Browser oldBrowser = browserInstances.get(keyFor(currentConfig));
            if (oldBrowser != null && oldBrowser.isConnected()) {
                logger.info("[getBrowser] Closing old browser: {}", currentBrowserType);
                try {
                    oldBrowser.close();
                } catch (Exception e) {
                    logger.warn("[getBrowser] Error closing old browser: {}", e.getMessage());
                }
                browserInstances.remove(keyFor(currentConfig));
            }

            //  修复 3.2：显式关闭旧 configId 对应的 Playwright 实例（Node 子进程），
            // 否则旧 Playwright 会一直留在 playwrightInstances Map 直到下次 initializeBrowser 才清理，造成泄漏。
            Playwright oldPlaywright = playwrightInstances.remove(keyFor(currentConfig));
            if (oldPlaywright != null) {
                try {
                    oldPlaywright.close();
                    VerboseLogging.logInfoIfVerbose(logger, "[getBrowser] Closed old Playwright for config: {}", currentConfig);
                } catch (Exception e) {
                    logger.warn("[getBrowser] Error closing old Playwright: {}", e.getMessage());
                }
            }

            // 生成新的 configId
            String newConfigId = generateConfigId();
            logger.info("[getBrowser] New configId: {}", newConfigId);

            // 更新 currentConfigId
            TestContextHolder.get().set(CURRENT_CONFIG_ID_KEY,newConfigId);

            // 初始化新浏览器
            initializeBrowser(newConfigId);

            // 切换完成，清除废弃标记
            RETIRED_CONFIG_IDS.remove(currentConfig);

            return browserInstances.get(keyFor(newConfigId));
        }
    }

    /**
     * 创建并获取 BrowserContext（线程安全）
     * <p>
     * 支持延迟重建机制：当检测到自定义配置时,自动重建Context
     */
    public static BrowserContext getContext() {
        if (!frameworkState.isInitialized()) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }

        //  修复 1.2：若本线程的 configId 已被标记为废弃（其它线程正在切换浏览器类型），
        // 强制清理本地 Context/Page，避免绑定到即将被关闭的旧 Browser。
        if (isCurrentConfigRetired()) {
            closePage();
            closeContext();
        }

        BrowserContext context = TestContextHolder.get().get(CONTEXT_KEY);
        
        // 检测是否需要重建Context（因为设置了自定义配置）
        Boolean customFlag = TestContextHolder.get().get(CustomOptionsManager.CUSTOM_CONTEXT_OPTIONS_FLAG_KEY);
        if (context != null && customFlag != null && customFlag) {
            VerboseLogging.logInfoIfVerbose(logger, "Custom context options detected, recreating context to apply them...");
            recreateContextIfCustomConfigNeeded();
            context = null;
        }
        
        // 【关键】线程安全：使用锁保护 Context 创建
        synchronized (CONTEXT_LOCK) {
            if (context == null || (context.browser() != null && !context.browser().isConnected())) {
                context = createContext();
                TestContextHolder.get().set(CONTEXT_KEY,context);
            }
        }
        return context;
    }

    /**
     * 调度 Context 重建（立即生效机制）
     * <p>
     * 当设置自定义配置时调用此方法，立即关闭现有的 Page 和 Context
     * 下次 getContext() 或 getPage() 时会使用新配置创建全新的 Context
     * 多次连续 set 只会触发一次关闭（因为 Context 已不存在）
     */
    static void scheduleContextRebuild() {
        // 先关闭 Page
        Page existingPage = TestContextHolder.get().get(PAGE_KEY);
        if (existingPage != null && !existingPage.isClosed()) {
            try {
                VerboseLogging.logInfoIfVerbose(logger, "Closing existing page for context rebuild");
                existingPage.close();
            } catch (Exception e) {
                VerboseLogging.logWarnIfVerbose(logger, "Failed to close existing page: {}", e.getMessage());
            }
        }
        TestContextHolder.get().remove(PAGE_KEY);

        // 立即关闭 Context（如果有），确保新配置立即生效
        BrowserContext existingContext = TestContextHolder.get().get(CONTEXT_KEY);
        if (existingContext != null) {
            VerboseLogging.logInfoIfVerbose(logger, "Closing existing context to apply new custom configurations...");

            try {
                if (existingContext.browser() != null && existingContext.browser().isConnected()) {
                    existingContext.close();
                }
            } catch (Exception e) {
                logger.warn("Failed to close existing context: {}", e.getMessage());
            } finally {
                TestContextHolder.get().remove(CONTEXT_KEY);
            }
            VerboseLogging.logInfoIfVerbose(logger, "Context closed, new context will be created with updated configurations on next access");
        }

        // customContextOptionsFlag 已在 CustomOptionsManager.setXXX() 中设置
    }

    /**
     * 丢弃当前线程的 Page/Context（仅关闭，不清除 custom options），
     * 使下一次 getContext()/getPage() 在干净起点重建。
     * <p>
     * 用于 Feature 模式切换不同 env/user（不同 sessionKey）时，避免复用上一个 session 的
     * Context（其 Cookie/Storage 残留会污染新用户）。调用方负责随后清理/重设自定义配置
     * （例如清除过期 storageStatePath），本方法仅关闭 Page+Context。
     */
    public static void discardCurrentContext() {
        scheduleContextRebuild();
    }

    /**
     * 如果 Context 已存在且设置了自定义配置，重建它
     * <p>
     * 此方法用于在需要应用自定义配置时关闭现有Context
     * 会在以下情况调用：
     * - getContext() 检测到自定义配置时
     * - 确保所有自定义配置（包括 storageState）都能正确应用
     */
    private static void recreateContextIfCustomConfigNeeded() {
        BrowserContext existingContext = TestContextHolder.get().get(CONTEXT_KEY);
        if (existingContext != null) {
            VerboseLogging.logInfoIfVerbose(logger, "Context already exists, closing it to apply custom configurations...");
            
            try {
                // 关闭 Page
                Page existingPage = TestContextHolder.get().get(PAGE_KEY);
                if (existingPage != null && !existingPage.isClosed()) {
                    existingPage.close();
                }
                TestContextHolder.get().remove(PAGE_KEY);
                
                // 关闭 Context（只有浏览器还连接着才关闭）
                if (existingContext.browser() != null && existingContext.browser().isConnected()) {
                    existingContext.close();
                }
            } catch (Exception e) {
                logger.warn("Failed to close existing context: {}", e.getMessage());
            } finally {
                TestContextHolder.get().remove(CONTEXT_KEY);
            }
            VerboseLogging.logInfoIfVerbose(logger, "Context closed, will create new one with custom configurations on next access");
        }
    }

    /**
     * 设置当前线程的 Page（用于 BasePage 切换页面后同步，不触发创建）。
     */
    public static void setPage(Page page) {
        if (page != null && !page.isClosed()) {
            TestContextHolder.get().set(PAGE_KEY,page);
        } else {
            logger.warn("[PlaywrightManager] setPage() ignored: page is {}",
                    page == null ? "null" : "closed");
        }
    }

    /**
     * 获取 Page（线程安全）
     * <p>
     * 支持延迟重建机制：在获取 Page 时检查是否需要重建 Context
     * 先调用 getContext() 确保重建检查被执行
     */
    public static Page getPage() {
        // 框架层自动处理 @AutoBrowser 注解（在真正需要操作页面时触发）
        AutoBrowserProcessor.processAutoBrowserAnnotation();

        if (!frameworkState.isInitialized()) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }

        //  韧性：浏览器已断开则快速失败，给出语义化异常而非底层 NPE/StateError
        if (isCurrentBrowserDisconnected()) {
            throw new BrowserException("Browser has been disconnected; the current test session is no longer valid. "
                    + "This usually indicates the browser process crashed or was terminated externally.");
        }

        //  修复 1.2：configId 已废弃则强制重建，见 getContext() 注释。
        if (isCurrentConfigRetired()) {
            closePage();
            closeContext();
        }

        // 先检查是否已有有效 Page（快速路径，避免不必要的锁竞争）
        Page page = TestContextHolder.get().get(PAGE_KEY);
        if (page != null && !page.isClosed()) {
            return page;
        }

        // 【关键】统一在锁内创建 Page，避免锁外创建 + 锁内再创建导致资源泄漏
        synchronized (PAGE_LOCK) {
            page = TestContextHolder.get().get(PAGE_KEY);
            if (page == null || page.isClosed()) {
                BrowserContext context = getContext();
                page = createPage(context);
                TestContextHolder.get().set(PAGE_KEY,page);
            }
        }
        return page;
    }

    /**
     * 创建新的 Page（使用指定的 Context）
     * 委托给 PlaywrightContextManager 处理
     */
    private static Page createPage(BrowserContext context) {
        return PlaywrightContextManager.createPage(context);
    }

    /**
     * 当前线程的 configId 是否已被标记为废弃（其它线程正在切换浏览器类型）。
     * 用于修复 1.2 的竞态：让并发线程感知并强制重建本地 Context/Page。
     */
    private static boolean isCurrentConfigRetired() {
        String mine = TestContextHolder.get().get(CURRENT_CONFIG_ID_KEY);
        if (mine == null) {
            return false;
        }
        return RETIRED_CONFIG_IDS.contains(mine);
    }

    // ==================== Context 和 Page 创建方法 ====================

    /**
     * 创建新的 BrowserContext（保证场景间配置隔离）
     * 委托给 PlaywrightContextManager 处理
     */
    private static BrowserContext createContext() {
        return PlaywrightContextManager.createContext();
    }

    /**
     * 非阻塞语义的退避等待工具：基于 LockSupport.parkNanos 实现，不调用 Thread.sleep，
     * 也不依赖 ForkJoinPool。仅用于初始化路径中"等待浏览器启动重试"这类本身即阻塞 I/O 的场景。
     *
     * @param millis 等待毫秒数
     */
    private static void parkMillis(long millis) {
        if (millis <= 0) {
            return;
        }
        // parkNanos 接受纳秒；直接阻塞当前线程（此处没有 Playwright 事件循环需要保护）
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    /**
     * 创建新的 Context 和 Page（委托给 PlaywrightSerenityBridge）
     */
    public static void createNewContextAndPage() {
        PlaywrightSerenityBridge.createNewContextAndPage();
    }

    // ==================== 关闭和清理方法 ====================

    /**
     * 关闭当前线程的 Page
     * 委托给 PlaywrightContextManager 处理
     */
    public static void closePage() {
        synchronized (PAGE_LOCK) {
            Page page = TestContextHolder.get().get(PAGE_KEY);
            if (page != null) {
                try {
                    PlaywrightContextManager.closePage(page);
                } finally {
                    TestContextHolder.get().remove(PAGE_KEY);
                }
            }
        }
    }

    /**
     * 关闭当前线程的 Context
     * 委托给 PlaywrightContextManager 处理
     */
    public static void closeContext() {
        synchronized (CONTEXT_LOCK) {
            BrowserContext context = TestContextHolder.get().get(CONTEXT_KEY);
            // 与底层 BrowserContext 资源绑定的清理：仅当 context 真实存在时执行。
            if (context != null) {
                //  修复 R6：每条清理步骤独立 try-catch，避免任一失败中断整条清理链
                // （例如 RoleElementPicker.cleanupContext 抛异常会导致后续清理被跳过）。
                safeClean("RouteRegistry.clearContext", () -> RouteLifecycleRegistry.get().clearContext(context));
                safeClean("RoleCodegenBridge.cleanupContext", () -> {
                    // 经 codegen 桥接（未注册=no-op，等价于默认关闭，零回归）。
                    RoleCodegenBridgeRegistry.getBridge().ifPresent(b -> {
                        if (b.isCodegenEnabled()) {
                            b.cleanupContext(context);
                        }
                    });
                });
                safeClean("RouteEngine.stopContextEngine", () -> RouteLifecycleRegistry.get().stopContextEngine(context));
                safeClean("PlaywrightContextManager.closeContext", () -> PlaywrightContextManager.closeContext(context));
                TestContextHolder.get().remove(CONTEXT_KEY);
            }
            //  T3-3 修复：per-thread 状态清理必须【无条件】执行。
            // 原实现把 TestServices.clear 等包在 if(context!=null) 内，
            // 导致 context 为 null 的路径（feature 模式无 session 复用、未创建 context 的场景、
            // 场景初始化重建路径）下这些 per-thread 状态跨 scenario 残留，污染下一个场景
            // （继承旧 entity/env/自定义配置）。现移出 if 块，无论 context 是否存在都清理；
            // 并补上此前遗漏的 CustomOptionsManager 全量清理（调用 closeContext 即视为场景结束）。
            // 注：BasePage 的静态 ThreadLocal 清理（clearAllThreadLocals）已在架构整改中移除——
            // 该静态引用本就是死状态，iframe/shadow 上下文已改为每实例独立持有（见 BasePage.currentFrame/currentShadow）。
            safeClean("TestServices.clear", () -> {
                try {
                    com.hsbc.cmb.hk.dbb.automation.framework.api.core.services.TestServices.clear();
                } catch (Throwable ignored) {
                    // API 模块不一定被 classloader 看到（仅 UI 框架独立运行时），兜底静默
                }
            });
            safeClean("CustomOptionsManager.removeAllThreadLocals", CustomOptionsManager::removeAllThreadLocals);
        }
    }

    /** 清理步骤包装：单步失败记录 warn 但不阻断后续清理（ 修复 R6）。 */
    private static void safeClean(String step, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            logger.warn("[PlaywrightManager] Cleanup step '{}' failed (continuing): {}", step, t.getMessage());
        }
    }

    /**
     * 检查当前线程是否有存活的 Context（不创建新 Context）
     * <p>
     * 与 getContext() 的区别：getContext() 在 Context 不存在时会创建新的，
     * 本方法仅检查存在性，用于 SessionManager 判断是否已有活跃的 Context
     *
     * @return true 如果 ThreadLocal 中有存活的 Context
     */
    public static boolean hasContext() {
        BrowserContext context = TestContextHolder.get().get(CONTEXT_KEY);
        return context != null && context.browser() != null && context.browser().isConnected();
    }


    /**
     * 重启浏览器（用于重跑测试时或浏览器类型切换）
     * <p>
     * ⚠️ <b>作用域（T3-2 企业级隔离）：本操作仅作用于【当前线程】拥有的 Browser/Playwright 实例。</b>
     * 自 T3-2 起，Browser 按 {@code threadId:configId} 在 {@link #browserInstances} 中分桶，
     * 每个 worker 线程持有独立实例，故 {@code restartBrowser()} 不再误杀其它并发 scenario 的浏览器
     * （旧实现按 configId 跨线程共享同一 Browser，重启会连带杀掉所有并发场景）。
     * <p>
     * 防护：执行前仍会检测"本线程除自身 context 外是否仍有其它打开的 BrowserContext"，若有则
     * <b>拒绝执行并抛出 {@link BrowserException}</b>（fail-fast）。
     * <p>
     *  修复 1.1：不再使用 {@code synchronized (PlaywrightManager.class)} 类锁，改为仅在操作本线程实例时
     * 持有 per-thread 的 BROWSER_LOCK（已降级为 ThreadLocal，见字段声明）。closePage()/closeContext()
     * 在锁外执行，保持与 getPage()/getContext() 一致的锁获取顺序（PAGE_LOCK → CONTEXT_LOCK），避免死锁。
     */
    public static void restartBrowser() {
        String oldConfigId = getCurrentConfigId();
        if (oldConfigId == null) {
            logger.warn("Cannot restart browser: configId is null. Browser not initialized.");
            return;
        }

        //  共享 Browser 模式：Browser 由所有线程共享，绝不能关闭——否则会连带杀掉其它并发 scenario
        //    的 Context（正是 T3-2 修复掉的 P0）。此模式下"重启"降级为【仅重建本线程的 Context/Page】，
        //    隔离语义由 BrowserContext 保证（cookie / storage 彼此独立），与 Browser 级隔离等价。
        if (isSharedBrowserMode()) {
            restartContextOnly(oldConfigId);
            return;
        }

        VerboseLogging.logInfoIfVerbose(logger, "🔄 Restarting browser for config: {}", oldConfigId);

        try {
            // 锁外关闭本线程的 Page/Context（避免持有 BROWSER_LOCK 时再进入细粒度锁导致顺序反转）
            closePage();
            closeContext();

            //  （T3-2 线程作用域化）：restartBrowser 现已收敛到【本线程】实例。
            //    fail-fast 仍保留：若本线程除自身 context 外仍有其它打开的 BrowserContext，
            //    说明本线程仍有未清理的 context，主动拒绝执行以避免半清理状态。
            //    注：browser.contexts() 仅返回【未关闭】的 context，故该判定是准确的；
            //    若探测本身抛出异常，同样不会走到后续关闭逻辑（失败即拒绝，方向安全）。
            // T3-2 线程隔离：仅检视【本线程】拥有的实例（键以 threadId: 前缀），其余跳过。
            final long tid = Thread.currentThread().getId();
            final String prefix = tid + ":";
            BrowserContext selfContext = TestContextHolder.get().get(CONTEXT_KEY);
            List<String> foreignOwners = new ArrayList<>();
            for (Map.Entry<String, Browser> entry : browserInstances.entrySet()) {
                if (!entry.getKey().startsWith(prefix)) {
                    continue;
                }
                Browser browser = entry.getValue();
                if (browser == null || !browser.isConnected()) {
                    continue;
                }
                for (BrowserContext bc : browser.contexts()) {
                    if (bc != null && bc != selfContext) {
                        foreignOwners.add(entry.getKey());
                        break;
                    }
                }
            }
            if (!foreignOwners.isEmpty()) {
                throw new BrowserException("restartBrowser() refused: " + foreignOwners.size()
                        + " other BrowserContext(s) are still open on THIS thread's browser(s) " + foreignOwners
                        + ". Close them before restarting.");
            }

            // 仅对共享实例 Map 的遍历与重建加细粒度锁，缩小临界区，避免全局挂起
            synchronized (perThreadBrowserLock()) {
                // T3-2 线程隔离：仅关闭【本线程】的浏览器实例（匹配前缀），按 key 精确移除，不动其它线程。
                for (Map.Entry<String, Browser> entry : browserInstances.entrySet()) {
                    if (!entry.getKey().startsWith(prefix)) {
                        continue;
                    }
                    Browser browser = entry.getValue();
                    if (browser != null && browser.isConnected()) {
                        try {
                            browser.close();
                            VerboseLogging.logInfoIfVerbose(logger, "Browser closed for config: {}", entry.getKey());
                        } catch (Exception e) {
                            logger.warn("Error closing browser instance for config {}: {}", entry.getKey(), e.getMessage());
                        }
                    }
                    browserInstances.remove(entry.getKey());
                }

                // 关闭本线程所有 Playwright 实例（仅匹配前缀），按 key 精确移除。
                for (Map.Entry<String, Playwright> entry : playwrightInstances.entrySet()) {
                    if (!entry.getKey().startsWith(prefix)) {
                        continue;
                    }
                    Playwright playwright = entry.getValue();
                    if (playwright != null) {
                        try {
                            playwright.close();
                            VerboseLogging.logInfoIfVerbose(logger, "Playwright instance closed for config: {}", entry.getKey());
                        } catch (Exception e) {
                            logger.warn("Error closing Playwright instance for config {}: {}", entry.getKey(), e.getMessage());
                        }
                    }
                    playwrightInstances.remove(entry.getKey());
                }

                //  修复 H9：重启前清空路由层引用与废弃标记，避免旧 configId 的调度器/路由 handler 持有
                // 已销毁 context 造成内存泄漏与跨场景串扰（与 cleanupAll 一致的收口顺序）。
                RETIRED_CONFIG_IDS.clear();
                RouteLifecycleRegistry.get().stopAllContextEngines();
                RouteLifecycleRegistry.get().clearAll();

                //  修复 L1/H9：newConfigId 生成 + 初始化必须在 BROWSER_LOCK 内原子完成；
                // 附加 nanoTime 后缀确保与旧 key 不碰撞（配置未变时 generateConfigId 可能复用旧值）。
                String newConfigId = generateConfigId() + "_r" + System.nanoTime();
                VerboseLogging.logInfoIfVerbose(logger, "Generating new configId: {} (old was: {})", newConfigId, oldConfigId);

                initializePlaywright(newConfigId);
                initializeBrowser(newConfigId);
                TestContextHolder.get().set(CURRENT_CONFIG_ID_KEY,newConfigId);

                VerboseLogging.logInfoIfVerbose(logger, " Browser restarted successfully for config: {}", newConfigId);
            }
        } catch (Exception e) {
            logger.error("Failed to restart browser for config: {}", oldConfigId, e);
            throw new BrowserException("Failed to restart browser for config: " + oldConfigId, e);
        }
    }

    /**
     * 共享 Browser 模式下的"重启"：<b>仅重建本线程的 Page/Context，不动共享 Browser</b>。
     * <p>{@link #closePage()} / {@link #closeContext()} 只作用于 ThreadLocal（本线程），
     * 不会影响其它并发 scenario。下次 {@code getContext()} / {@code getPage()} 访问时，
     * 会从共享 Browser 上新建一个干净的 Context。</p>
     *
     * @param configId 当前线程的浏览器配置标识（仅用于日志与异常信息）
     * @throws IllegalArgumentException configId 为 null 或空白时抛出
     * @throws BrowserException        本线程 Page/Context 关闭失败时抛出
     */
    static void restartContextOnly(String configId) {
        if (configId == null || configId.isBlank()) {
            throw new IllegalArgumentException(
                    "configId must not be null or blank when restarting context (shared browser mode)");
        }
        VerboseLogging.logInfoIfVerbose(logger,
                "🔄 [shared-browser] Restarting CONTEXT only for config: {} (shared Browser preserved)", configId);
        try {
            closePage();
            closeContext();
        } catch (Exception e) {
            logger.error("[shared-browser] Failed to restart context for config: {}", configId, e);
            throw new BrowserException(
                    "Failed to restart context (shared browser mode) for config: " + configId, e);
        }
        VerboseLogging.logInfoIfVerbose(logger,
                "✅ [shared-browser] Context restarted for config: {}; a fresh context will be created on next access",
                configId);
    }


    /**
     * 清理所有资源
     */
    public static void cleanupAll() {
        VerboseLogging.logInfoIfVerbose(logger, "Cleaning up all Playwright resources...");

        // 关闭当前线程的页面和上下文
        closePage();
        closeContext();

        // 关键遍历每个 Browser 实例，先关闭其所有 BrowserContext，再关闭 Browser，
        // 否则 BrowserContext 可能被静默丢弃（即便本框架不鼓励多线程持有 context，长跑+并发场景
        // 下仍有其他线程创建的 context 残留）。
        for (Browser browser : new ArrayList<>(browserInstances.values())) {
            if (browser != null && browser.isConnected()) {
                try {
                    for (BrowserContext bc : browser.contexts()) {
                        if (bc == null) continue;
                        try {
                            //  修复 L3：改走 PlaywrightContextManager.closeContext，其内部已包含
                            //    ① 带 15s 超时的 tracing.stop（原 bc.close() 会跳过 trace 落盘，
                            //       留下不完整/残留 trace 文件）
                            //    ② RouteRegistry.clearContext（释放路由层对该 context 的引用，
                            //       否则调度器线程池会持有已销毁 context → 内存泄漏）
                            //    ③ 受保护的 context.close()
                            //  修复 L4：删除原死代码空 if 块，以及
                            //    bc.pages().removeIf(p -> !p.isClosed()) —— 该语句把【仍打开】的 page
                            //    从列表移除（与注释"已关闭的 page 跳过"语义相反），且 pages() 返回
                            //    不可变列表时 removeIf 会抛 UnsupportedOperationException，
                            //    而它与 bc.close() 在同一 try 块内 → 异常会【吞掉 close】导致 context 泄漏。
                            //    直接交给 closeContext 统一处理即可，无需手工增删 page 列表。
                            PlaywrightContextManager.closeContext(bc);
                        } catch (Exception ex) {
                            logger.warn("Error closing browser context: {}", ex.getMessage());
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Error iterating browser contexts during cleanupAll: {}", e.getMessage());
                }
            }
        }

        // 关闭所有浏览器实例（每个 try-catch 独立保护，防止单个失败阻断后续清理）
        for (Browser browser : new ArrayList<>(browserInstances.values())) {
            if (browser != null && browser.isConnected()) {
                try {
                    browser.close();
                    VerboseLogging.logInfoIfVerbose(logger, "Browser instance closed");
                } catch (Exception e) {
                    logger.warn("Error closing browser instance during cleanupAll: {}", e.getMessage());
                }
            }
        }
        browserInstances.clear();

        // 关闭所有 Playwright 实例
        playwrightInstances.values().forEach(playwright -> {
            try {
                playwright.close();
                VerboseLogging.logInfoIfVerbose(logger, "Playwright instance closed");
            } catch (Exception e) {
                logger.warn("Error closing Playwright instance", e);
            }
        });
        playwrightInstances.clear();

        // 统一清理所有 ThreadLocal（防止线程复用/线程池场景下的内存泄漏）
        PlaywrightSerenityBridge.cleanupThreadLocals(true);
        //  最终清理：Browser 已关闭，currentConfigId 可以安全清除
        TestContextHolder.get().remove(CURRENT_CONFIG_ID_KEY);

        //  关闭 BrowserStack Local 隧道（由当前策略决定：本地策略内仍委托 BrowserStackManager.cleanup，非 Local 模式为 no-op）
        resolveStrategy().cleanup();

        //  修复 R5/R7：Browser 实例已全部关闭后，统一清理全局 Route 注册表与异步调度器，
        // 防止直接 close browser（未逐 context 关闭）场景下 DISPATCHED_ROUTES / 原生 route handler /
        // ContextRouteEngineManager 调度任务残留导致的泄漏与跨场景路由串扰。
        safeClean("ContextRouteEngineManager.stopAll", () -> RouteLifecycleRegistry.get().stopAllContextEngines());
        safeClean("RouteRegistry.clearAll", () -> RouteLifecycleRegistry.get().clearAll());
        safeClean("AsyncPool.shutdown", () -> com.hsbc.cmb.hk.dbb.automation.framework.common.async.AsyncPool.shutdown());

        VerboseLogging.logInfoIfVerbose(logger, "All Playwright resources cleaned up");
    }

    // ==================== Serenity BDD 集成方法（委托给 PlaywrightSerenityBridge） ====================

    public static void initializeForScenario() {
        PlaywrightSerenityBridge.initializeForScenario();
    }

    public static void cleanupForScenario() {
        //  修复 A-1：先调用桥做 scenario 级 Page/Context 关闭（桥内部 synchronized 取
        //   TestContextHolder.get().get(CONTEXT_KEY)/TestContextHolder.get().get(PAGE_KEY) 执行真正的 close）。
        //   ⚠️ 禁止在桥前 remove page/context 引用，否则桥取到 null → scenario 级
        //   Context/Page 不被关闭（仅触发 onClose 钩子），造成真实浏览器资源泄漏。
        //
        //  CustomOptionsManager 的清理必须放到【桥之后】。
        //   桥在 feature 模式（resetCustomContextOptionsForFeatureMode）会读取
        //   customStorageStatePath 来"跨 scenario 保留登录态"；原实现在桥之前调用
        //   removeAllThreadLocals() 将其清空，使该优化被静默绕过。
        //   currentConfigId 与钩子快照不参与桥的决策，可在桥前安全清理。
        TestContextHolder.get().remove(CURRENT_CONFIG_ID_KEY);
        //   内存泄漏：Scenario 结束时清除当前线程的 Context 规则快照，避免旧 Context 哈希 key 残留
        ContextLifecycleHookManager.clearSnapshotForCurrentThread();

        PlaywrightSerenityBridge.cleanupForScenario();

        // 桥已返回：Page/Context 已真正关闭，且桥已读完 customStorageStatePath，
        // 此时再清理当前线程的资源型与自定义选项 ThreadLocal 引用。
        CustomOptionsManager.removeAllThreadLocals();
        TestContextHolder.get().remove(PAGE_KEY);
        TestContextHolder.get().remove(CONTEXT_KEY);
    }

    /**
     * Feature 级别的清理（委托给 PlaywrightSerenityBridge）
     */
    public static void cleanupForFeature() {
        PlaywrightSerenityBridge.cleanupForFeature();
    }

    // ==================== 截图方法（委托给 PlaywrightScreenshotManager） ====================

    public static String takeScreenshot(String title) {
        return PlaywrightScreenshotManager.takeScreenshot(title);
    }

    // ==================== 配置访问（通过 config() 代理到 PlaywrightConfigManager） ====================
    // 使用 PlaywrightManager.config().getXXX() 或 PlaywrightConfigManager.config().getXXX() 访问配置

    // ==================== 公共访问方法 ====================

    /**
     * 获取自定义选项管理器（提供 PlaywrightManager.customOptions().getXXX() 风格的 API）
     */
    public static CustomOptionsManager customOptions() {
        return CustomOptionsManager.getInstance();
    }

    /**
     * 获取配置管理器（提供 PlaywrightManager.config().getXXX() 风格的 API）
     */
    public static PlaywrightConfigManager config() {
        return PlaywrightConfigManager.config();
    }

    // ==================== 包内访问器（供同包子类使用） ====================

    static Page getPageThreadLocal() {
        return TestContextHolder.get().get(PAGE_KEY);
    }

    static FrameworkState getFrameworkState() {
        return frameworkState;
    }

}