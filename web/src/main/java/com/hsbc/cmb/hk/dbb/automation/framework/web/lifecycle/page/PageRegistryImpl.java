package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkState;
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
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestartImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCrashGuard;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptions;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.scenario.ScenarioLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.PlaywrightSerenityBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.SerenityBusBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.TestContextBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightContextManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightInitializer;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.media.PlaywrightScreenshotManager;

import com.hsbc.cmb.hk.dbb.automation.framework.web.codegen.spi.RoleCodegenBridgeRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrencyGate;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.AutoBrowserProcessor;
import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;

import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Page registry (WEB-P1-1 Step 4). Package-private internal collaborator. */
public final class PageRegistryImpl implements PageRegistry {

    /** Default singleton instance (stateless, thread-safe). */
    public static final PageRegistryImpl INSTANCE = new PageRegistryImpl();

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightManager.class);

    private PageRegistryImpl() {
    }

    public Page getPage() {
        // 框架层自动处理 @AutoBrowser 注解（在真正需要操作页面时触发）
        AutoBrowserProcessor.processAutoBrowserAnnotation();

        if (!FrameworkState.getInstance().isInitialized()) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }

        //  韧性：浏览器已断开则快速失败，给出语义化异常而非底层 NPE/StateError
        if (PlaywrightRuntime.instance().browserCleanup.isCurrentBrowserDisconnected()) {
            throw new BrowserException("Browser has been disconnected; the current test session is no longer valid. "
                    + "This usually indicates the browser process crashed or was terminated externally.");
        }

        //  修复 1.2：configId 已废弃则强制重建，见 PlaywrightManager.getContext() 注释。
        if (PlaywrightRuntime.instance().contextRegistry.isCurrentConfigRetired()) {
            PlaywrightManager.closePage();
            PlaywrightManager.closeContext();
        }

        // 先检查是否已有有效 Page（快速路径，避免不必要的锁竞争）
        Page page = TestContextHolder.get().get(PlaywrightManager.PAGE_KEY);
        if (page != null && !page.isClosed()) {
            return page;
        }

        // 【关键】统一在锁内创建 Page，避免锁外创建 + 锁内再创建导致资源泄漏
        return LifecycleLockMediator.withPageLock(() -> {
            Page current = TestContextHolder.get().get(PlaywrightManager.PAGE_KEY);
            if (current == null || current.isClosed()) {
                BrowserContext context = PlaywrightManager.getContext();
                current = createPage(context);
                TestContextHolder.get().set(PlaywrightManager.PAGE_KEY, current);
            }
            return current;
        });
    }
    /**
     * 创建新的 Page（使用指定的 Context）
     * 委托给 PlaywrightContextManager 处理
     */
    public Page createPage(BrowserContext context) {
        return PlaywrightContextManager.createPage(context);
    }
    /**
     * 关闭当前线程的 Page
     * 委托给 PlaywrightContextManager 处理
     */
    public void closePage() {
        LifecycleLockMediator.withPageLock(() -> {
            Page page = TestContextHolder.get().get(PlaywrightManager.PAGE_KEY);
            if (page != null) {
                try {
                    PlaywrightContextManager.closePage(page);
                } finally {
                    TestContextHolder.get().remove(PlaywrightManager.PAGE_KEY);
                }
            }
        });
    }

    /**
     * 设置当前线程的 Page（用于 BasePage 切换页面后同步，不触发创建）。
     */
    public void setPage(Page page) {
        if (page != null && !page.isClosed()) {
            TestContextHolder.get().set(PlaywrightManager.PAGE_KEY,page);
        } else {
            logger.warn("[PlaywrightManager] setPage() ignored: page is {}",
                    page == null ? "null" : "closed");
        }
    }

}
