package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.lock.LifecycleLockMediator;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightContextManager;


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

    /**
     * 本线程当前 Page —— <b>线程级</b>持有，独立于用例级的 {@link PlaywrightManager#PAGE_KEY}。
     *
     * <p>与 Context 同因（Cucumber {@code @After} 清用例级存储早于 Serenity {@code testFinished}）：
     * 使 feature 模式「同 sessionKey 复用 Context/Page」可达，并让收尾可靠关闭本线程 Page。
     */
    private static final ThreadLocal<Page> CURRENT_PAGE_BY_THREAD = new ThreadLocal<>();

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
            //  线程级记录同步（供收尾可靠关闭 + feature 模式跨用例复用判定）
            CURRENT_PAGE_BY_THREAD.set(page);
            return page;
        }
        //  用例级 PAGE_KEY 可能暂时取不到（@After 之后 / 异步回调路径 / feature 模式跨用例）：
        //    回退线程级记录复用，避免同一线程重复建页。feature 模式「复用 Context/Page」正依赖此路径。
        Page threaded = CURRENT_PAGE_BY_THREAD.get();
        if (threaded != null && !threaded.isClosed()) {
            TestContextHolder.get().set(PlaywrightManager.PAGE_KEY, threaded);
            return threaded;
        }

        // 【关键】统一在锁内创建 Page，避免锁外创建 + 锁内再创建导致资源泄漏
        return LifecycleLockMediator.withPageLock(() -> {
            Page current = TestContextHolder.get().get(PlaywrightManager.PAGE_KEY);
            if (current == null || current.isClosed()) {
                current = CURRENT_PAGE_BY_THREAD.get();
            }
            if (current == null || current.isClosed()) {
                BrowserContext context = PlaywrightManager.getContext();
                current = createPage(context);
                TestContextHolder.get().set(PlaywrightManager.PAGE_KEY, current);
            }
            CURRENT_PAGE_BY_THREAD.set(current);
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
            if (page == null) {
                //  窗口/资源泄漏修复：用例级 PAGE_KEY 在收尾时可能已被 @After 清空，回退线程级记录
                page = CURRENT_PAGE_BY_THREAD.get();
            }
            if (page != null) {
                VerboseLogging.logDebugIfVerbose(logger, "[PageRegistry] closePage: page=#{} (fromThread={})",
                        System.identityHashCode(page), page == CURRENT_PAGE_BY_THREAD.get());
                try {
                    PlaywrightContextManager.closePage(page);
                } finally {
                    TestContextHolder.get().remove(PlaywrightManager.PAGE_KEY);
                    CURRENT_PAGE_BY_THREAD.remove();
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
            CURRENT_PAGE_BY_THREAD.set(page);
        } else {
            logger.warn("[PlaywrightManager] setPage() ignored: page is {}",
                    page == null ? "null" : "closed");
        }
    }

    /** 本线程当前 Page（线程级记录；供收尾可靠关闭与 feature 复用判定使用）。 */
    public Page currentPageForThread() {
        return CURRENT_PAGE_BY_THREAD.get();
    }

}