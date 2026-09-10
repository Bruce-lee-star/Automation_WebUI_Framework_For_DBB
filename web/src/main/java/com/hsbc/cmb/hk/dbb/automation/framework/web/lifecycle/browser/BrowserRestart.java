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

/**
 * Browser 重启与崩溃重建契约（WEB-P1-6 Phase 1 接口提取）。
 *
 * <p>默认实现 {@link BrowserRestartImpl}（WEB-P1-1 拆分成果的逻辑承载体，已实例化为单例）。
 * 该接口把「重启 / 仅重建 Context / 共享 Browser 崩溃重建」这组韧性职责收敛为可替换契约，
 * 供 {@code PlaywrightRuntime} 实例门面与测试替身经此接口替换实现。</p>
 *
 * @see BrowserRestartImpl
 */
public interface BrowserRestart {

    void restartBrowser();

    void restartContextOnly(String configId);

    boolean rebuildSharedBrowserIfDisconnected();

    boolean rebuildSharedBrowser();
}
