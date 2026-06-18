package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.cloud.BrowserStackManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.AutoBrowserProcessor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.InitializationException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Dimension;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


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
    // 线程安全的实例存储
    private static final ConcurrentMap<String, Playwright> playwrightInstances = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Browser> browserInstances = new ConcurrentHashMap<>();

    // 线程安全锁：保护共享资源（Browser 实例）
    private static final Object BROWSER_LOCK = new Object();

    // Context/Page 细粒度锁：保护 Context 和 Page 创建/销毁
    private static final Object CONTEXT_LOCK = new Object();
    private static final Object PAGE_LOCK = new Object();

    // 框架状态引用
    static final FrameworkState frameworkState = FrameworkState.getInstance();

    // ==================== ThreadLocal 变量（3个，集中管理） ====================

    // ---- 核心 Page/Context ----
    static final ThreadLocal<BrowserContext> contextThreadLocal = new ThreadLocal<>();
    static final ThreadLocal<Page> pageThreadLocal = new ThreadLocal<>();

    // ---- 配置标识 ----
    static final ThreadLocal<String> currentConfigId = new ThreadLocal<>();

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
    private static void ensureBrowserInstalledForType() {
        PlaywrightInitializer.ensureBrowsersInstalled();
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
        if (frameworkState.isInitialized() && currentConfigId.get() != null) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Playwright environment already initialized with config: {}", currentConfigId.get());
            return;
        }

        String configId = generateConfigId();
        LoggingConfigUtil.logInfoIfVerbose(logger, "Initializing Playwright environment with config: {}", configId);
        initializePlaywright(configId);
        // 不在此处初始化浏览器，延迟到首次访问时启动
        // 这样可以支持 @AutoBrowser 动态浏览器切换
        // initializeBrowser(configId);
        currentConfigId.set(configId);

        LoggingConfigUtil.logInfoIfVerbose(logger, " Playwright environment initialized successfully (browser will be launched on first access)");
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
        LoggingConfigUtil.logInfoIfVerbose(logger, "Initializing Playwright for config: {}", configId);

        try {
            Playwright.CreateOptions createOptions = getCreateOptions();
            Playwright playwright = Playwright.create(createOptions);
            playwrightInstances.put(configId, playwright);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Playwright initialized successfully for config: {}", configId);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to initialize Playwright for config: {}", configId, e);
            // 清理已创建的实例（如果有）
            if (playwrightInstances.containsKey(configId)) {
                playwrightInstances.remove(configId);
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
        LoggingConfigUtil.logDebugIfVerbose(logger, "Playwright browsers path: {}", absoluteBrowsersPath);

        // 跳过 Playwright.create() 内部的自动下载，因为我们已经通过 ensureBrowserInstalledForType() 管理下载
        // 如果不禁用，Node.js 进程启动时会尝试连接 cdn.playwright.dev 下载所有浏览器
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        LoggingConfigUtil.logInfoIfVerbose(logger, "Playwright instance created with browser download disabled (managed separately)");

        // ==================== BrowserStack 代理透传 ====================
        // 公司网络环境下，域名解析和外网直连均被阻断
        // 需要将 HTTPS_PROXY 注入 Playwright 底层 Node.js 子进程，
        // 让 CDP WebSocket 连接通过公司 HTTP 代理建立 CONNECT 隧道
        if (BrowserStackManager.isBrowserStackEnabled()) {
            String proxyHost = FrameworkConfigManager.getString(
                FrameworkConfig.BROWSERSTACK_PROXY_HOST);
            String proxyPort = FrameworkConfigManager.getString(
                FrameworkConfig.BROWSERSTACK_PROXY_PORT);
            String proxyUser = FrameworkConfigManager.getString(
                FrameworkConfig.BROWSERSTACK_PROXY_USERNAME);
            String proxyPass = FrameworkConfigManager.getString(
                FrameworkConfig.BROWSERSTACK_PROXY_PASSWORD);

            if (proxyHost != null && !proxyHost.trim().isEmpty()
                    && proxyPort != null && !proxyPort.trim().isEmpty()) {
                String proxyUrl;
                if (proxyUser != null && !proxyUser.trim().isEmpty()
                        && proxyPass != null && !proxyPass.trim().isEmpty()) {
                    proxyUrl = String.format("http://%s:%s@%s:%s",
                            proxyUser, proxyPass, proxyHost, proxyPort);
                } else {
                    proxyUrl = String.format("http://%s:%s", proxyHost, proxyPort);
                }
                env.put("HTTPS_PROXY", proxyUrl);
                env.put("HTTP_PROXY", proxyUrl);
                env.put("NO_PROXY", "localhost,127.0.0.1,::1");
                LoggingConfigUtil.logInfoIfVerbose(logger,
                        "[BrowserStack Proxy] Injecting proxy for Playwright Node process: {}:{}",
                        proxyHost, proxyPort);
            }
        }

        options.setEnv(env);

        return options;
    }

    /**
     * 初始化 Browser 实例
     */
    private static synchronized void initializeBrowser(String configId) {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Initializing Browser for config: {}", configId);

        // 双重检查：如果已经有连接的浏览器实例，直接返回
        Browser existingBrowser = browserInstances.get(configId);
        if (existingBrowser != null && existingBrowser.isConnected()) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Browser already initialized and connected for config: {}", configId);
            return;
        }

        // 关闭现有浏览器实例（如果存在但未连接）
        if (existingBrowser != null) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Closing existing browser instance for config: {}", configId);
            try {
                existingBrowser.close();
                LoggingConfigUtil.logInfoIfVerbose(logger, "Existing browser closed successfully");
            } catch (Exception e) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to close existing browser, continuing with new initialization", e);
            }
        }

        // 获取浏览器类型（这一步很关键，必须在初始化 Playwright 之前）
        String browserType = config().getBrowserType();
        
        // 确保所需的浏览器已安装（延迟下载）
        // ensureBrowsersInstalled 内部已处理 channel 场景（直接跳过）
        LoggingConfigUtil.logInfoIfVerbose(logger, "[Browser Init] Checking if {} browser is installed...", browserType);
        long checkStart = System.currentTimeMillis();
        ensureBrowserInstalledForType();
        long checkElapsed = System.currentTimeMillis() - checkStart;
        LoggingConfigUtil.logInfoIfVerbose(logger, "[Browser Init] Browser check completed in {}ms", checkElapsed);

        // 初始化 Playwright 实例（浏览器已确保安装）
        if (playwrightInstances.containsKey(configId)) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Playwright instance already exists for config: {}, skipping initialization", configId);
        } else {
            initializePlaywright(configId);
        }

        Playwright playwright = playwrightInstances.get(configId);
        if (playwright == null) {
            throw new InitializationException("Playwright instance is null after initialization for config: " + configId);
        }

        // 获取浏览器配置
        boolean headless = config().isHeadless();
        int slowMo = config().getBrowserSlowMo();
        int timeout = config().getBrowserTimeout();

        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setSlowMo(slowMo)
                .setTimeout(timeout);

        // 配置窗口大小和启动参数
        configureBrowserLaunchOptions(launchOptions);

        long initStart = System.currentTimeMillis();
        try {
            LoggingConfigUtil.logInfoIfVerbose(logger, "[Browser Init] Starting browser launch: type={}, channel={}, headless={}", 
                browserType, config().getBrowserChannel(), config().isHeadless());
            // 启动浏览器
            Browser browser = setupBrowser(playwright, browserType, launchOptions);
            browserInstances.put(configId, browser);

            long elapsed = System.currentTimeMillis() - initStart;
            LoggingConfigUtil.logInfoIfVerbose(logger, "[Browser Init] Browser initialized successfully in {}ms: {} for config: {}", 
                elapsed, browserType, configId);
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to initialize Browser for config: {}", configId, e);

            // 清理已创建的实例（如果有）
            if (browserInstances.containsKey(configId)) {
                browserInstances.remove(configId);
            }

            throw new BrowserException("Failed to initialize Browser for config: " + configId, e);
        }
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

        // 窗口最大化统一在 stabilizePage 中通过 window.resizeTo 实现

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
            LoggingConfigUtil.logDebugIfVerbose(logger,
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
     * 根据配置选择浏览器类型
     */
    private static Browser setupBrowser(Playwright playwright, String browserType, BrowserType.LaunchOptions
            launchOptions) {
        if (playwright == null) {
            throw new IllegalArgumentException("Playwright instance cannot be null");
        }

        try {
            // 检查是否启用 BrowserStack
            if (BrowserStackManager.isBrowserStackEnabled()) {
                logger.info("Using BrowserStack for browser: {}", browserType);
                return setupBrowserStackBrowser(playwright, browserType);
            }

            // 本地浏览器
            return switch (browserType.toLowerCase()) {
                case "chromium" -> playwright.chromium().launch(launchOptions);
                case "firefox" -> playwright.firefox().launch(launchOptions);
                case "webkit" -> playwright.webkit().launch(launchOptions);
                default -> throw new IllegalArgumentException("Unsupported browser type: " + browserType);
            };
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
     * 设置 BrowserStack 浏览器连接（使用新API）
     */
    private static Browser setupBrowserStackBrowser(Playwright playwright, String browserType) {
        try {
            // 使用新的企业级 BrowserStackManager.connect() API
            BrowserStackManager.setCurrentSessionId("auto-" + System.currentTimeMillis());
            Browser browser = BrowserStackManager.connect(playwright);
            logger.info("[BrowserStack] Connected successfully via CDP");
            return browser;
        } catch (Exception e) {
            logger.error("Failed to connect to BrowserStack for browser: {}", browserType, e);
            throw new BrowserException("Failed to connect to BrowserStack", e);
        }
    }


    // ==================== 实例访问方法 ====================

    /**
     * 获取当前配置ID
     */
    private static String getCurrentConfigId() {
        if (currentConfigId.get() == null) {
            currentConfigId.set(generateConfigId());
        }
        return currentConfigId.get();
    }

    /**
     * 获取 Playwright 实例
     */
    public static Playwright getPlaywright() {
        String configId = getCurrentConfigId();
        if (configId == null) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }
        return playwrightInstances.get(configId);
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
        Browser currentBrowser = browserInstances.get(currentConfig);
        if (currentBrowser != null && currentBrowser.isConnected()) {
            // 浏览器已存在且连接正常，检查是否需要切换
            String[] configParts = currentConfig.split("_");
            String currentBrowserType = configParts.length > 0 ? configParts[0] : "chromium";
            
            if (!currentBrowserType.equalsIgnoreCase(desiredBrowserType)) {
                return handleBrowserTypeSwitch(currentConfig, currentBrowser, currentBrowserType, desiredBrowserType);
            }
            
            return currentBrowser;
        }
        
        // 慢速路径：浏览器不存在或断开，加锁创建
        synchronized (BROWSER_LOCK) {
            // 双重检查：另一个线程可能已在等待期间创建了浏览器
            currentBrowser = browserInstances.get(currentConfig);
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
                currentConfigId.set(newConfigId);
                currentConfig = newConfigId;
            }
            
            // 初始化浏览器
            initializeBrowser(currentConfig);
            
            return browserInstances.get(currentConfig);
        }
    }

    /**
     * 处理浏览器类型切换逻辑
     *
     * <p>⭐ 锁安全设计：closePage/closeContext 在 BROWSER_LOCK 之外执行，
     * 避免 BROWSER_LOCK → PAGE_LOCK → CONTEXT_LOCK 与 getPage() 的
     * PAGE_LOCK → CONTEXT_LOCK 形成死锁链。
     */
    private static Browser handleBrowserTypeSwitch(String currentConfig, Browser currentBrowser,
                                                    String currentBrowserType, String desiredBrowserType) {
        logger.info("[getBrowser] Browser type changed: {} -> {}", currentBrowserType, desiredBrowserType);
        logger.info("[getBrowser] Switching browser...");

        // ⭐ 1. 在 BROWSER_LOCK 之外关闭旧 Context 和 Page（避免死锁）
        closePage();
        closeContext();

        // ⭐ 2. 在 BROWSER_LOCK 内关闭旧浏览器 + 初始化新浏览器
        synchronized (BROWSER_LOCK) {
            // 关闭旧浏览器
            Browser oldBrowser = browserInstances.get(currentConfig);
            if (oldBrowser != null && oldBrowser.isConnected()) {
                logger.info("[getBrowser] Closing old browser: {}", currentBrowserType);
                try {
                    oldBrowser.close();
                } catch (Exception e) {
                    logger.warn("[getBrowser] Error closing old browser: {}", e.getMessage());
                }
                browserInstances.remove(currentConfig);
            }

            // 生成新的 configId
            String newConfigId = generateConfigId();
            logger.info("[getBrowser] New configId: {}", newConfigId);

            // 更新 currentConfigId
            currentConfigId.set(newConfigId);

            // 初始化新浏览器
            initializeBrowser(newConfigId);

            return browserInstances.get(newConfigId);
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

        BrowserContext context = contextThreadLocal.get();
        
        // 检测是否需要重建Context（因为设置了自定义配置）
        Boolean customFlag = CustomOptionsManager.customContextOptionsFlag.get();
        if (context != null && customFlag != null && customFlag) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Custom context options detected, recreating context to apply them...");
            recreateContextIfCustomConfigNeeded();
            context = null;
        }
        
        // 【关键】线程安全：使用锁保护 Context 创建
        synchronized (CONTEXT_LOCK) {
            if (context == null || (context.browser() != null && !context.browser().isConnected())) {
                context = createContext();
                contextThreadLocal.set(context);
                
                // 【Context 生命周期钩子】通知规则管理器 Context 已重建，需要重绑规则
                ContextLifecycleHookManager.onContextRebuilt(context);
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
        Page existingPage = pageThreadLocal.get();
        if (existingPage != null && !existingPage.isClosed()) {
            try {
                LoggingConfigUtil.logInfoIfVerbose(logger, "Closing existing page for context rebuild");
                existingPage.close();
            } catch (Exception e) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to close existing page: {}", e.getMessage());
            }
        }
        pageThreadLocal.remove();

        // 立即关闭 Context（如果有），确保新配置立即生效
        BrowserContext existingContext = contextThreadLocal.get();
        if (existingContext != null) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Closing existing context to apply new custom configurations...");

            // 【Context 生命周期钩子】通知规则管理器 Context 即将重建
            ContextLifecycleHookManager.onContextAboutToRebuild(existingContext);

            try {
                if (existingContext.browser() != null && existingContext.browser().isConnected()) {
                    existingContext.close();
                }
            } catch (Exception e) {
                logger.warn("Failed to close existing context: {}", e.getMessage());
            } finally {
                contextThreadLocal.remove();
            }
            LoggingConfigUtil.logInfoIfVerbose(logger, "Context closed, new context will be created with updated configurations on next access");
        }

        // customContextOptionsFlag 已在 CustomOptionsManager.setXXX() 中设置
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
        BrowserContext existingContext = contextThreadLocal.get();
        if (existingContext != null) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Context already exists, closing it to apply custom configurations...");
            
            // 【Context 生命周期钩子】通知规则管理器 Context 即将重建，需要捕获规则
            ContextLifecycleHookManager.onContextAboutToRebuild(existingContext);
            
            try {
                // 关闭 Page
                Page existingPage = pageThreadLocal.get();
                if (existingPage != null && !existingPage.isClosed()) {
                    existingPage.close();
                }
                pageThreadLocal.remove();
                
                // 关闭 Context（只有浏览器还连接着才关闭）
                if (existingContext.browser() != null && existingContext.browser().isConnected()) {
                    existingContext.close();
                }
            } catch (Exception e) {
                logger.warn("Failed to close existing context: {}", e.getMessage());
            } finally {
                contextThreadLocal.remove();
            }
            LoggingConfigUtil.logInfoIfVerbose(logger, "Context closed, will create new one with custom configurations on next access");
        }
    }

    /**
     * 设置当前线程的 Page（用于 BasePage 切换页面后同步，不触发创建）。
     */
    public static void setPage(Page page) {
        if (page != null && !page.isClosed()) {
            pageThreadLocal.set(page);
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

        // 先检查是否已有有效 Page（快速路径，避免不必要的锁竞争）
        Page page = pageThreadLocal.get();
        if (page != null && !page.isClosed()) {
            return page;
        }

        // 【关键】统一在锁内创建 Page，避免锁外创建 + 锁内再创建导致资源泄漏
        synchronized (PAGE_LOCK) {
            page = pageThreadLocal.get();
            if (page == null || page.isClosed()) {
                BrowserContext context = getContext();
                page = createPage(context);
                pageThreadLocal.set(page);

                // 【Page 生命周期钩子】通知规则管理器 Page 已创建，需要绑定 Page 级别规则
                ContextLifecycleHookManager.rebindRulesToPage(page);
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

    // ==================== Context 和 Page 创建方法 ====================

    /**
     * 创建新的 BrowserContext（保证场景间配置隔离）
     * 委托给 PlaywrightContextManager 处理
     */
    private static BrowserContext createContext() {
        return PlaywrightContextManager.createContext();
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
            Page page = pageThreadLocal.get();
            if (page != null) {
                try {
                    PlaywrightContextManager.closePage(page);
                } finally {
                    pageThreadLocal.remove();
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
            BrowserContext context = contextThreadLocal.get();
            if (context != null) {
                try {
                    PlaywrightContextManager.closeContext(context);
                } finally {
                    contextThreadLocal.remove();
                }
            }
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
        BrowserContext context = contextThreadLocal.get();
        return context != null && context.browser() != null && context.browser().isConnected();
    }


    /**
     * 重启浏览器（用于重跑测试时或浏览器类型切换）
     */
    public static synchronized void restartBrowser() {
        String oldConfigId = getCurrentConfigId();
        if (oldConfigId == null) {
            logger.warn("Cannot restart browser: configId is null. Browser not initialized.");
            return;
        }

        LoggingConfigUtil.logInfoIfVerbose(logger, "🔄 Restarting browser for config: {}", oldConfigId);

        try {
            closePage();
            closeContext();

            // 关闭所有浏览器实例，确保没有残留的实例
            for (Map.Entry<String, Browser> entry : browserInstances.entrySet()) {
                Browser browser = entry.getValue();
                if (browser != null && browser.isConnected()) {
                    try {
                        browser.close();
                        LoggingConfigUtil.logInfoIfVerbose(logger, "Browser closed for config: {}", entry.getKey());
                    } catch (Exception e) {
                        logger.warn("Error closing browser instance for config {}: {}", entry.getKey(), e.getMessage());
                    }
                }
            }
            browserInstances.clear();

            // 关闭所有Playwright实例
            for (Map.Entry<String, Playwright> entry : playwrightInstances.entrySet()) {
                Playwright playwright = entry.getValue();
                if (playwright != null) {
                    try {
                        playwright.close();
                        LoggingConfigUtil.logInfoIfVerbose(logger, "Playwright instance closed for config: {}", entry.getKey());
                    } catch (Exception e) {
                        logger.warn("Error closing Playwright instance for config {}: {}", entry.getKey(), e.getMessage());
                    }
                }
            }
            playwrightInstances.clear();

            // 生成新的 configId（此时浏览器类型可能已经更新）
            String newConfigId = generateConfigId();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Generating new configId: {} (old was: {})", newConfigId, oldConfigId);

            initializePlaywright(newConfigId);
            initializeBrowser(newConfigId);
            currentConfigId.set(newConfigId);

            LoggingConfigUtil.logInfoIfVerbose(logger, " Browser restarted successfully for config: {}", newConfigId);
        } catch (Exception e) {
            logger.error("Failed to restart browser for config: {}", oldConfigId, e);
            throw new BrowserException("Failed to restart browser for config: " + oldConfigId, e);
        }
    }


    /**
     * 清理所有资源
     */
    public static void cleanupAll() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Cleaning up all Playwright resources...");

        // 关闭当前线程的页面和上下文
        closePage();
        closeContext();

        // 关闭所有浏览器实例（每个 try-catch 独立保护，防止单个失败阻断后续清理）
        for (Browser browser : new ArrayList<>(browserInstances.values())) {
            if (browser != null && browser.isConnected()) {
                try {
                    browser.close();
                    LoggingConfigUtil.logInfoIfVerbose(logger, "Browser instance closed");
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
                LoggingConfigUtil.logInfoIfVerbose(logger, "Playwright instance closed");
            } catch (Exception e) {
                logger.warn("Error closing Playwright instance", e);
            }
        });
        playwrightInstances.clear();

        // 统一清理所有 ThreadLocal（防止线程复用/线程池场景下的内存泄漏）
        PlaywrightSerenityBridge.cleanupThreadLocals(true);
        // ⭐ 最终清理：Browser 已关闭，currentConfigId 可以安全清除
        currentConfigId.remove();

        LoggingConfigUtil.logInfoIfVerbose(logger, "All Playwright resources cleaned up");
    }

    /**
     * 清理资源（向后兼容，委托给 cleanupForScenario）
     */
    public static void cleanup() {
        PlaywrightSerenityBridge.cleanupForScenario();
    }

    // ==================== Serenity BDD 集成方法（委托给 PlaywrightSerenityBridge） ====================

    public static void initializeForScenario() {
        PlaywrightSerenityBridge.initializeForScenario();
    }

    public static void cleanupForScenario() {
        PlaywrightSerenityBridge.cleanupForScenario();
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
        return pageThreadLocal.get();
    }

    static FrameworkState getFrameworkState() {
        return frameworkState;
    }
}