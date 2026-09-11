package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.lock.LifecycleLockMediator;
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
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestart;
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
Browser restart and crash rebuild (WEB-P1-1 Step 5).
 Package-private internal collaborator. */
public final class BrowserRestartImpl implements BrowserRestart {

    /** Default singleton instance (stateless, thread-safe). */
    public static final BrowserRestartImpl INSTANCE = new BrowserRestartImpl();

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightManager.class);

    private BrowserRestartImpl() {
    }

    /**
     * 重启浏览器（用于重跑测试时或浏览器类型切换）
     * <p>
     * ⚠️ <b>作用域（T3-2 企业级隔离）：本操作仅作用于【当前线程】拥有的 Browser/Playwright 实例。</b>
     * 自 T3-2 起，Browser 按 {@code threadId:configId} 在状态根的 Browser 实例表中分桶，
     * 每个 worker 线程持有独立实例，故 {@code restartBrowser()} 不再误杀其它并发 scenario 的浏览器
     * （旧实现按 configId 跨线程共享同一 Browser，重启会连带杀掉所有并发场景）。
     * <p>
     * 防护：执行前仍会检测"本线程除自身 context 外是否仍有其它打开的 BrowserContext"，若有则
     * <b>拒绝执行并抛出 {@link BrowserException}</b>（fail-fast）。
     * <p>
     *  修复 1.1：不再使用 {@code synchronized (PlaywrightManager.class)} 类锁，改为仅在操作本线程实例时
     * 持有 per-thread 的 BROWSER_LOCK（已降级为 ThreadLocal，见字段声明）。PlaywrightRuntime.instance().pageRegistry.closePage()/PlaywrightRuntime.instance().contextRegistry.closeContext()
     * 在锁外执行，保持与 PlaywrightManager.getPage()/PlaywrightManager.getContext() 一致的锁获取顺序（PlaywrightManager.PAGE_LOCK → PlaywrightManager.CONTEXT_LOCK），避免死锁。
     */
    public void restartBrowser() {
        String oldConfigId = PlaywrightManager.getCurrentConfigId();
        if (oldConfigId == null) {
            logger.warn("Cannot restart browser: configId is null. Browser not initialized.");
            return;
        }

        //  共享 Browser 模式：Browser 由所有线程共享，绝不能关闭——否则会连带杀掉其它并发 scenario
        //    的 Context（正是 T3-2 修复掉的 P0）。此模式下"重启"降级为【仅重建本线程的 Context/Page】，
        //    隔离语义由 BrowserContext 保证（cookie / storage 彼此独立），与 Browser 级隔离等价。
        if (PlaywrightManager.isSharedBrowserMode()) {
            restartContextOnly(oldConfigId);
            return;
        }

        VerboseLogging.logInfoIfVerbose(logger, "🔄 Restarting browser for config: {}", oldConfigId);

        try {
            // 锁外关闭本线程的 Page/Context（避免持有 BROWSER_LOCK 时再进入细粒度锁导致顺序反转）
            PlaywrightRuntime.instance().pageRegistry.closePage();
            PlaywrightRuntime.instance().contextRegistry.closeContext();

            //  （T3-2 线程作用域化）：restartBrowser 现已收敛到【本线程】实例。
            //    fail-fast 仍保留：若本线程除自身 context 外仍有其它打开的 BrowserContext，
            //    说明本线程仍有未清理的 context，主动拒绝执行以避免半清理状态。
            //    注：browser.contexts() 仅返回【未关闭】的 context，故该判定是准确的；
            //    若探测本身抛出异常，同样不会走到后续关闭逻辑（失败即拒绝，方向安全）。
            // T3-2 线程隔离：仅检视【本线程】拥有的实例（键以 threadId: 前缀），其余跳过。
            final long tid = Thread.currentThread().getId();
            final String prefix = tid + ":";
            BrowserContext selfContext = TestContextHolder.get().get(PlaywrightManager.CONTEXT_KEY);
            List<String> foreignOwners = new ArrayList<>();
            for (Map.Entry<String, Browser> entry : PlaywrightRuntime.instance().state.browserEntries()) {
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
            LifecycleLockMediator.withPerThreadBrowserLock(() -> {
                // T3-2 线程隔离：仅关闭【本线程】的浏览器实例（匹配前缀），按 key 精确移除，不动其它线程。
                for (Map.Entry<String, Browser> entry : PlaywrightRuntime.instance().state.browserEntries()) {
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
                    PlaywrightRuntime.instance().state.removeBrowser(entry.getKey());
                }

                // 关闭本线程所有 Playwright 实例（仅匹配前缀），按 key 精确移除。
                for (Map.Entry<String, Playwright> entry : PlaywrightRuntime.instance().state.playwrightEntries()) {
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
                    PlaywrightRuntime.instance().state.removePlaywright(entry.getKey());
                }

                //  修复 H9：重启前清空路由层引用与废弃标记，避免旧 configId 的调度器/路由 handler 持有
                // 已销毁 context 造成内存泄漏与跨场景串扰（与 cleanupAll 一致的收口顺序）。
                PlaywrightRuntime.instance().state.clearRetiredAll();
                RouteLifecycleRegistry.get().stopAllContextEngines();
                RouteLifecycleRegistry.get().clearAll();

                //  修复 L1/H9：newConfigId 生成 + 初始化必须在 BROWSER_LOCK 内原子完成；
                // 附加 nanoTime 后缀确保与旧 key 不碰撞（配置未变时 generateConfigId 可能复用旧值）。
                String newConfigId = PlaywrightRuntime.instance().browserStartup.generateConfigId() + "_r" + System.nanoTime();
                VerboseLogging.logInfoIfVerbose(logger, "Generating new configId: {} (old was: {})", newConfigId, oldConfigId);

                PlaywrightRuntime.instance().browserStartup.initializePlaywright(newConfigId);
                PlaywrightRuntime.instance().browserStartup.initializeBrowser(newConfigId);
                TestContextHolder.get().set(PlaywrightManager.CURRENT_CONFIG_ID_KEY,newConfigId);

                VerboseLogging.logInfoIfVerbose(logger, " Browser restarted successfully for config: {}", newConfigId);
            });
        } catch (Exception e) {
            logger.error("Failed to restart browser for config: {}", oldConfigId, e);
            throw new BrowserException("Failed to restart browser for config: " + oldConfigId, e);
        }
    }

    /**
     * 共享 Browser 模式下的"重启"：<b>仅重建本线程的 Page/Context，不动共享 Browser</b>。
     * <p>{@link #PlaywrightRuntime.instance().pageRegistry.closePage()} / {@link #PlaywrightRuntime.instance().contextRegistry.closeContext()} 只作用于 ThreadLocal（本线程），
     * 不会影响其它并发 scenario。下次 {@code PlaywrightManager.getContext()} / {@code PlaywrightManager.getPage()} 访问时，
     * 会从共享 Browser 上新建一个干净的 Context。</p>
     *
     * @param configId 当前线程的浏览器配置标识（仅用于日志与异常信息）
     * @throws IllegalArgumentException configId 为 null 或空白时抛出
     * @throws BrowserException        本线程 Page/Context 关闭失败时抛出
     */
    public void restartContextOnly(String configId) {
        if (configId == null || configId.isBlank()) {
            throw new IllegalArgumentException(
                    "configId must not be null or blank when restarting context (shared browser mode)");
        }
        VerboseLogging.logInfoIfVerbose(logger,
                "🔄 [shared-browser] Restarting CONTEXT only for config: {} (shared Browser preserved)", configId);
        try {
            PlaywrightRuntime.instance().pageRegistry.closePage();
            PlaywrightRuntime.instance().contextRegistry.closeContext();
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
     * 共享 Browser 崩溃后的进程级单飞重建（由 {@code BrowserCrashGuard} 调用）。
     *
     * <p><b>仅共享 Browser 模式有效</b>：非共享模式下每个 worker 线程持有独立 Browser，
     * 断开时 {@link #PlaywrightManager.getBrowser()} 已会自动重建本线程实例，故此处直接返回 {@code true}（允许调用方重跑任务，
     * 由 {@code getBrowser} 的常规路径完成重建），不执行任何进程级操作。</p>
     *
     * <p>共享模式下在 {@link #PlaywrightManager.SHARED_BROWSER_LOCK} 内：若共享 Browser 已断开（状态根的断开标记
     * 或 Browser 实例表中实例 {@code isConnected()==false}），关闭残留引用并从头
     * {@link #initializeBrowser} 重建；若仍连接则直接返回（幂等，可被并发崩溃的多个任务反复安全调用）。</p>
     *
     * @return {@code true} 表示可继续重跑（已重建或无需重建）；本方法不返回 {@code false}
     * @throws BrowserException 共享模式重建失败时抛出（转换自底层 {@link #initializeBrowser} 异常）
     */
    public boolean rebuildSharedBrowserIfDisconnected() {
        if (!PlaywrightManager.isSharedBrowserMode()) {
            return true;
        }
        return LifecycleLockMediator.withSharedBrowserLock(() -> {
            String configId = PlaywrightManager.getCurrentConfigId();
            if (configId == null) {
                return true;
            }
            Browser current = PlaywrightRuntime.instance().state.getBrowser(PlaywrightRuntime.instance().browserRegistry.keyFor(configId));
            if (current != null && current.isConnected()) {
                return true; // 仍连接，无需重建（幂等）
            }
            // 清理断开残留：移除 Map 引用与断开标记，避免陈旧实例干扰后续 getBrowser 双重检查
            if (current != null) {
                PlaywrightRuntime.instance().state.clearDisconnected(current);
                PlaywrightRuntime.instance().state.removeBrowser(PlaywrightRuntime.instance().browserRegistry.keyFor(configId));
                try {
                    current.close();
                } catch (Exception e) {
                    // 已断开，close 多数情况为 no-op；放行底层异常但须留痕（D7-3）
                    logger.debug("[BrowserRestart] close of disconnected browser skipped: {}", e.toString());
                }
            }
            VerboseLogging.logInfoIfVerbose(logger,
                    "[shared-browser] Rebuilding disconnected shared browser for config: {}", configId);
            PlaywrightRuntime.instance().browserStartup.initializeBrowser(configId);
            return true;
        });
    }

    /**
     * 无条件重建共享 Browser（句柄损坏场景的强制恢复动作）。
     *
     * <p>与 {@link #rebuildSharedBrowserIfDisconnected()} 的差异：后者在实例仍连接时 no-op（幂等），
     * 本方法<b>无条件</b>关闭当前共享实例（含仍连接者）并从头重建——用于句柄损坏
     * （{@code cannot find object to call} 等）场景，此时 {@code isConnected()} 恒为 true，
     * 断开型重建会落空。
     *
     * <p>旧实例经 {@code onDisconnected} 回调被重新标记进断开集合，仅被"飞行中"的兄弟任务持有，
     * 它们以语义化 {@code BrowserException} 快速失败，随后经崩溃守卫各自重建后 replay。
     *
     * @return 始终返回 {@code true}（表示已执行重建）
     */
    public boolean rebuildSharedBrowser() {
        if (!PlaywrightManager.isSharedBrowserMode()) {
            return true;
        }
        return LifecycleLockMediator.withSharedBrowserLock(() -> {
            String configId = PlaywrightManager.getCurrentConfigId();
            if (configId == null) {
                return true;
            }
            Browser current = PlaywrightRuntime.instance().state.getBrowser(PlaywrightRuntime.instance().browserRegistry.keyFor(configId));
            if (current != null) {
                PlaywrightRuntime.instance().state.clearDisconnected(current);
                PlaywrightRuntime.instance().state.removeBrowser(PlaywrightRuntime.instance().browserRegistry.keyFor(configId));
                try {
                    current.close();
                } catch (Exception e) {
                    // 关闭失败不影响重建：旧实例已移出管理表，由 onDisconnected 标记快速失败（D7-3：不得静默）
                    logger.debug("[BrowserRestart] old browser close failed during rebuild: {}", e.toString());
                }
            }
            VerboseLogging.logInfoIfVerbose(logger,
                    "[shared-browser] Forcibly rebuilding shared browser for config: {}", configId);
            PlaywrightRuntime.instance().browserStartup.initializeBrowser(configId);
            return true;
        });
    }

}
