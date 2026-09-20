package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.lock.LifecycleLockMediator;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistry;


import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

/**
 * Browser registry and key/lock dispatch (WEB-P1-1 Step 3). Package-private internal collaborator.
 *
 * <p><b>线程模型（对齐 Playwright for Java 官方 multithreading 文档）：</b>
 * Playwright for Java 是多线程绑定，官方明确 <i>"Playwright Java is not thread safe"</i>——
 * 一个 {@code Browser}/{@code BrowserContext}/{@code Page} 对象<b>不得</b>跨线程并发调用。
 * 因此并发模型为<b>「每线程独立 Browser」</b>：{@link #keyFor(String)} 始终返回
 * {@code "<threadId>:<configId>"}，每个 worker 线程拿到各自独立的 Browser 实例
 * （N 并行 = N 线程 = N Browser），隔离性由每线程各自的 {@code BrowserContext} 保证。
 * 共享单个 Browser 跨线程的模式在 Java 绑定下非线程安全（会损坏对象注册表），已从框架移除。</p>
 */
public final class BrowserRegistryImpl implements BrowserRegistry {

    /** Default singleton instance (stateless, thread-safe). */
    public static final BrowserRegistryImpl INSTANCE = new BrowserRegistryImpl();

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightManager.class);

    private BrowserRegistryImpl() {
    }

    /**
     * 计算 Browser/Playwright 实例的存储键（<b>纯函数</b>）。
     *
     * <p>并发模型为<b>每线程独立 Browser</b>：key 恒为 {@code "<threadId>:<configId>"}，
     * 每个 worker 线程拿到各自独立的 Browser 实例（共享 Browser 模式已从框架移除）。</p>
     *
     * @param configId 当前线程的浏览器配置标识
     * @return {@code "<threadId>:<configId>"}（每线程唯一）
     * @throws IllegalArgumentException configId 为 null 或空白时抛出（否则会生成无法定位的孤儿键）
     */
    public String keyFor(String configId) {
        if (configId == null || configId.isBlank()) {
            throw new IllegalArgumentException(
                    "configId must not be null or blank: it would produce an unusable Browser instance key");
        }
        return Thread.currentThread().threadId() + ":" + configId;
    }

    /**
     * 校验 configId 形态（并发实例键契约的防御性前置）：
     * 合法 configId 形如 {@code "<browserType>_<headless>[_channel]"}，必须含分隔符 {@code '_'}。
     * 缺失 {@code '_'} 意味着配置标识被截断/污染，会导致 {@link #getBrowser()} 中浏览器类型解析错误
     * 与静默重建，故直接抛 {@link IllegalArgumentException}（语义化异常），而非裸 NPE / 误判为 chromium。
     *
     * @param configId 待校验的 configId（调用方已保证非 null/非空；空白由 {@link #keyFor} 拒绝）
     * @throws IllegalArgumentException configId 为 null 或不含 {@code '_'} 时
     */
    public void validateConfigIdShape(String configId) {
        if (configId == null || configId.indexOf('_') < 0) {
            throw new IllegalArgumentException(
                    "configId must contain '_' separator (expected shape '<browserType>_<headless>[_channel]'): " + configId);
        }
    }
    public Playwright getPlaywright() {
        String configId = PlaywrightManager.getCurrentConfigId();
        if (configId == null) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }
        return PlaywrightRuntime.instance().state.getPlaywright(keyFor(configId));
    }
    /**
     * 返回（或懒创建）当前 configId 对应的 {@code Browser} 实例。
     *
     * <p><b>线程安全契约：</b>本方法对 Browser 的创建/类型切换均在 per-thread 的
     * {@link LifecycleLockMediator#withBrowserLock} 保护下进行；调用方不得在本锁之外 mutate 返回的 Browser。</p>
     *
     * @apiNote configId 经 {@link #validateConfigIdShape(String)} 校验形态，非法（缺失 {@code '_'} 分隔符）
     *          直接抛 {@link IllegalArgumentException}（语义化异常），而非静默误判浏览器类型 / 裸 NPE。
     */
    public Browser getBrowser() {
        // 获取当前的配置ID
        String currentConfig = PlaywrightManager.getCurrentConfigId();
        if (currentConfig == null) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }
        // 实例键契约：configId 形态必须含 '_' 分隔符，否则无法解析浏览器类型
        validateConfigIdShape(currentConfig);

        // 获取期望的浏览器类型（可能来自 @AutoBrowser 标签）
        String desiredBrowserType = PlaywrightManager.config().getBrowserType();
        
        // 快速路径：检查当前浏览器实例是否有效（无锁）
        Browser currentBrowser = PlaywrightRuntime.instance().state.getBrowser(keyFor(currentConfig));
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
        PlaywrightRuntime.instance().browserStartup.ensureBrowserInstalledForType();
        
        // 慢速路径：浏览器不存在或断开，加锁创建
        // 修复 WEB-P0-1：每线程独立 Browser 模型下，Browser 创建/类型切换统一在 per-thread 锁内，
        // 各线程互不阻塞、不会双发射同一 Browser。
        return LifecycleLockMediator.withBrowserLock(() -> {
            // 双重检查：另一个线程可能已在等待期间创建了浏览器
            Browser browser = PlaywrightRuntime.instance().state.getBrowser(keyFor(currentConfig));
            if (browser != null && browser.isConnected()) {
                return browser;
            }
            
            logger.info("[getBrowser] Browser not initialized yet, initializing with desired type: {}", desiredBrowserType);
            
            // 如果 currentConfig 中的浏览器类型与期望类型不同，更新 configId
            String[] configParts = currentConfig.split("_");
            String configBrowserType = configParts.length > 0 ? configParts[0] : "chromium";
            
            // lambda 内需要可变的 configId 副本（外层 currentConfig 须保持 effectively final）
            String effectiveConfig = currentConfig;
            if (!configBrowserType.equalsIgnoreCase(desiredBrowserType)) {
                // 生成新的 configId（使用期望的浏览器类型）
                String newConfigId = PlaywrightRuntime.instance().browserStartup.generateConfigId();
                logger.info("[getBrowser] Updating configId from {} to {} for browser type: {}",
                    currentConfig, newConfigId, desiredBrowserType);
                TestContextHolder.get().set(PlaywrightManager.CURRENT_CONFIG_ID_KEY,newConfigId);
                effectiveConfig = newConfigId;
            }
            
            // 初始化浏览器
            PlaywrightRuntime.instance().browserStartup.initializeBrowser(effectiveConfig);
            
            return PlaywrightRuntime.instance().state.getBrowser(keyFor(effectiveConfig));
        });
    }

    /**
     * 处理浏览器类型切换逻辑
     *
     * <p> 锁安全设计：closePage/closeContext 在 BROWSER_LOCK 之外执行，
     * 避免 BROWSER_LOCK → PlaywrightManager.PAGE_LOCK → PlaywrightManager.CONTEXT_LOCK 与 PlaywrightManager.getPage() 的
     * PlaywrightManager.PAGE_LOCK → PlaywrightManager.CONTEXT_LOCK 形成死锁链。
     */
    public Browser handleBrowserTypeSwitch(String currentConfig, Browser currentBrowser,
                                                    String currentBrowserType, String desiredBrowserType) {
        logger.info("[getBrowser] Browser type changed: {} -> {}", currentBrowserType, desiredBrowserType);
        logger.info("[getBrowser] Switching browser...");

        //  1. 在 BROWSER_LOCK 之外关闭旧 Context 和 Page（避免死锁）
        //  修复 H7：先把当前 configId 标记为"已废弃"，再关闭旧 Context/Page。
        // 顺序上移确保并发线程在 BROWSER_LOCK 外即可感知 retired 并重建立即生效（见 getContext 的 retired 预检），
        // 避免"标记前已取到旧 context"的竞态窗口。
        PlaywrightRuntime.instance().state.markRetired(currentConfig);

        PlaywrightRuntime.instance().pageRegistry.closePage();
        PlaywrightRuntime.instance().contextRegistry.closeContext();

        //  2. 在 BROWSER_LOCK 内关闭旧浏览器 + 初始化新浏览器
        //      （每线程独立 Browser：切换只需 per-thread 锁，各线程互不阻塞；共享 Browser 模式已移除）
        // 修复 WEB-P0-1：此处与 getBrowser 慢路径一致，统一在 per-thread 锁内完成 Browser 切换。
        return LifecycleLockMediator.withBrowserLock(() -> {
            // 关闭旧浏览器
            Browser oldBrowser = PlaywrightRuntime.instance().state.getBrowser(keyFor(currentConfig));
            if (oldBrowser != null && oldBrowser.isConnected()) {
                logger.info("[getBrowser] Closing old browser: {}", currentBrowserType);
                try {
                    PlaywrightRuntime.instance().browserCleanup.closeBrowserInstance(oldBrowser);
                } catch (Exception e) {
                    logger.warn("[getBrowser] Error closing old browser: {}", e.getMessage());
                }
                PlaywrightRuntime.instance().state.removeBrowser(keyFor(currentConfig));
            }

            //  修复 3.2：显式关闭旧 configId 对应的 Playwright 实例（Node 子进程），
            // 否则旧 Playwright 会一直留在状态根的 Playwright 实例表中直到下次 initializeBrowser 才清理，造成泄漏。
            Playwright oldPlaywright = PlaywrightRuntime.instance().state.removePlaywright(keyFor(currentConfig));
            if (oldPlaywright != null) {
                try {
                    oldPlaywright.close();
                    VerboseLogging.logInfoIfVerbose(logger, "[getBrowser] Closed old Playwright for config: {}", currentConfig);
                } catch (Exception e) {
                    logger.warn("[getBrowser] Error closing old Playwright: {}", e.getMessage());
                }
            }

            // 生成新的 configId
            String newConfigId = PlaywrightRuntime.instance().browserStartup.generateConfigId();
            logger.info("[getBrowser] New configId: {}", newConfigId);

            // 更新 currentConfigId
            TestContextHolder.get().set(PlaywrightManager.CURRENT_CONFIG_ID_KEY,newConfigId);

            // 初始化新浏览器
            PlaywrightRuntime.instance().browserStartup.initializeBrowser(newConfigId);

            // 切换完成，清除废弃标记
            PlaywrightRuntime.instance().state.clearRetired(currentConfig);

            return PlaywrightRuntime.instance().state.getBrowser(keyFor(newConfigId));
        });
    }

    /**
     * 懒重建（或返回）当前 configId。
     * <p>供 scenario/feature 级清理（{@code cleanupForScenario} 会移除 {@code currentConfigId}）之后，
     * {@code beforeTest} 恢复环境使用：若 PlaywrightManager.frameworkState 仍 initialized 但 configId 已被清空，
     * 此处就地重建，避免 {@code initializeForScenario} 误报"环境未初始化"而级联抛错。
     */
    public String ensureConfigId() {
        return PlaywrightManager.getCurrentConfigId();
    }

    /**
     * 显式设置当前线程的 configId（供并发 worker 复用统一的浏览器形态）。
     *
     * <p><b>每线程独立 Browser 模型（T3-2）：</b>{@link #keyFor(String)} 始终返回
     * {@code "<threadId>:<configId>"}，因此即便多个 worker 线程设置<b>相同</b>的 configId，
     * 也会因 {@code threadId} 维度而各自拿到独立的 Browser 实例——隔离性由 per-thread 的
     * {@code BrowserContext} 保证，且互不共享非线程安全的 {@code Browser} 对象。
     * 并发场景调用本方法传入 {@link #workerConfigId()}（统一浏览器类型 / headed 维度），
     * 使各线程创建形态一致的独立 Browser。</p>
     *
     * @param configId 浏览器配置标识，须含浏览器类型前缀（推荐经 {@link #workerConfigId()} 取得），非空
     * @throws IllegalArgumentException configId 为 null 或空白时抛出（否则生成无法定位的孤儿 Browser 键）
     */
    public void setConfigId(String configId) {
        if (configId == null || configId.isBlank()) {
            throw new IllegalArgumentException("configId must not be null or blank");
        }
        TestContextHolder.get().set(PlaywrightManager.CURRENT_CONFIG_ID_KEY, configId);
    }

    /**
     * 返回并发 worker 共用的规范 configId（含浏览器类型 / headed 前缀），
     * 避免触发 {@link #getBrowser()} 的浏览器类型切换逻辑而意外生成唯一 configId。
     *
     * <p>注意：这只是统一的<b>配置标识形态</b>，并不共享 Browser 实例——
     * {@link #keyFor(String)} 仍含 {@code threadId} 维度，各 worker 线程得到各自独立的 Browser。</p>
     *
     * @return 形如 {@code "<browserType>_headless_"} 的规范 configId
     */
    public String workerConfigId() {
        String browserType = PlaywrightManager.config().getBrowserType();
        String headlessMode = PlaywrightManager.config().isHeadless() ? "headless" : "headed";
        return browserType + "_" + headlessMode + "_";
    }

}