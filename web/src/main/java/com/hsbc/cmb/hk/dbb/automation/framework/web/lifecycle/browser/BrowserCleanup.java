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
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestart;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestartImpl;
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

import com.microsoft.playwright.Browser;

/**
 * Browser 清理与断开守卫契约（WEB-P1-6 Phase 1 接口提取）。
 *
 * <p>默认实现 {@link BrowserCleanupImpl}（WEB-P1-1 拆分成果的逻辑承载体，已实例化为单例）。
 * 该接口把「全量清理 / 清理步骤包装 / 断开守卫注册 / 主动关闭收口 / 断开探测」这组职责收敛为可替换契约，
 * 供 {@code PlaywrightRuntime} 实例门面与测试替身经此接口替换实现。</p>
 *
 * @see BrowserCleanupImpl
 */
public interface BrowserCleanup {

    void cleanupAll();

    void safeClean(String step, Runnable action);

    void registerBrowserDisconnectGuard(Browser browser);

    void closeBrowserInstance(Browser browser);

    boolean isCurrentBrowserDisconnected();

    /**
     * 兜底回收<b>本线程</b>所有 Browser 上仍打开的 BrowserContext（headed 模式即 OS 窗口），
     * <b>不依赖</b> {@code TestContextHolder} 中的 {@code CONTEXT_KEY}。
     *
     * <p>用于修复「窗口堆积」：收尾链路关闭 Context 依赖 {@code CONTEXT_KEY}，若用例级上下文已解绑
     * （Cucumber {@code @After} 早于 Serenity {@code testFinished}）则该步被静默跳过，
     * Context/窗口持续堆积，直到套件级 {@link #cleanupAll()} 才随 Browser 释放。
     *
     * @return 实际关闭的 Context 数
     */
    int closeOrphanContextsForCurrentThread();

    /**
     * 枚举<b>本线程</b>所有 Browser 上仍打开的 BrowserContext（<b>不关闭</b>）。
     *
     * <p>供线程级资源清理使用：只返回 {@code "<threadId>:"} 前缀的 Browser 下的 context，
     * <b>绝不触碰</b>并发邻居线程（G4）。
     *
     * @return 本线程的 Context 列表（可能为空）
     */
    java.util.List<com.microsoft.playwright.BrowserContext> contextsForCurrentThread();
}
