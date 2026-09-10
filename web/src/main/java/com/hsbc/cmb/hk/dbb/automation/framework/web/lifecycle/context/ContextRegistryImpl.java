package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context;
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

import com.hsbc.cmb.hk.dbb.automation.framework.web.codegen.spi.RoleCodegenBridgeRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent.ConcurrencyGate;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ContextKey;

import com.hsbc.cmb.hk.dbb.automation.framework.web.config.AutoBrowserProcessor;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.InitializationException;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.WebFrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.BrowserException;

/** Context registry (WEB-P1-1 Step 4). Package-private internal collaborator. */
public final class ContextRegistryImpl implements ContextRegistry {

    /** Default singleton instance (stateless, thread-safe). */
    public static final ContextRegistryImpl INSTANCE = new ContextRegistryImpl();

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightManager.class);

    private ContextRegistryImpl() {
    }

    public BrowserContext getContext() {
        if (!PlaywrightManager.getFrameworkState().isInitialized()) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }

        //  修复 1.2：若本线程的 configId 已被标记为废弃（其它线程正在切换浏览器类型），
        // 强制清理本地 Context/Page，避免绑定到即将被关闭的旧 Browser。
        if (isCurrentConfigRetired()) {
            PlaywrightManager.closePage();
            PlaywrightManager.closeContext();
        }

        BrowserContext context = TestContextHolder.get().get(PlaywrightManager.CONTEXT_KEY);
        
        // 检测是否需要重建Context（因为设置了自定义配置）
        Boolean customFlag = TestContextHolder.get().get(CustomOptionsManager.CUSTOM_CONTEXT_OPTIONS_FLAG_KEY);
        if (context != null && customFlag != null && customFlag) {
            VerboseLogging.logInfoIfVerbose(logger, "Custom context options detected, recreating context to apply them...");
            recreateContextIfCustomConfigNeeded();
            context = null;
        }
        
        // 【关键】线程安全：使用锁保护 Context 创建
        final BrowserContext existingContext = context;
        return LifecycleLockMediator.withContextLock(() -> {
            BrowserContext result = existingContext;
            if (result == null || (result.browser() != null && !result.browser().isConnected())) {
                result = createContext();
                TestContextHolder.get().set(PlaywrightManager.CONTEXT_KEY, result);
            }
            return result;
        });
    }
    /**
     * 调度 Context 重建（立即生效机制）
     * <p>
     * 当设置自定义配置时调用此方法，立即关闭现有的 Page 和 Context
     * 下次 PlaywrightManager.getContext() 或 PlaywrightManager.getPage() 时会使用新配置创建全新的 Context
     * 多次连续 set 只会触发一次关闭（因为 Context 已不存在）
     */
    public void scheduleContextRebuild() {
        // 先关闭 Page
        Page existingPage = TestContextHolder.get().get(PlaywrightManager.PAGE_KEY);
        if (existingPage != null && !existingPage.isClosed()) {
            try {
                VerboseLogging.logInfoIfVerbose(logger, "Closing existing page for context rebuild");
                existingPage.close();
            } catch (Exception e) {
                VerboseLogging.logWarnIfVerbose(logger, "Failed to close existing page: {}", e.getMessage());
            }
        }
        TestContextHolder.get().remove(PlaywrightManager.PAGE_KEY);

        // 立即关闭 Context（如果有），确保新配置立即生效
        BrowserContext existingContext = TestContextHolder.get().get(PlaywrightManager.CONTEXT_KEY);
        if (existingContext != null) {
            VerboseLogging.logInfoIfVerbose(logger, "Closing existing context to apply new custom configurations...");

            try {
                if (existingContext.browser() != null && existingContext.browser().isConnected()) {
                    existingContext.close();
                }
            } catch (Exception e) {
                logger.warn("Failed to close existing context: {}", e.getMessage());
            } finally {
                TestContextHolder.get().remove(PlaywrightManager.CONTEXT_KEY);
            }
            VerboseLogging.logInfoIfVerbose(logger, "Context closed, new context will be created with updated configurations on next access");
        }

        // customContextOptionsFlag 已在 CustomOptionsManager.setXXX() 中设置
    }
    /**
     * 如果 Context 已存在且设置了自定义配置，重建它
     * <p>
     * 此方法用于在需要应用自定义配置时关闭现有Context
     * 会在以下情况调用：
     * - PlaywrightManager.getContext() 检测到自定义配置时
     * - 确保所有自定义配置（包括 storageState）都能正确应用
     */
    public void recreateContextIfCustomConfigNeeded() {
        BrowserContext existingContext = TestContextHolder.get().get(PlaywrightManager.CONTEXT_KEY);
        if (existingContext != null) {
            VerboseLogging.logInfoIfVerbose(logger, "Context already exists, closing it to apply custom configurations...");
            
            try {
                // 关闭 Page
                Page existingPage = TestContextHolder.get().get(PlaywrightManager.PAGE_KEY);
                if (existingPage != null && !existingPage.isClosed()) {
                    existingPage.close();
                }
                TestContextHolder.get().remove(PlaywrightManager.PAGE_KEY);
                
                // 关闭 Context（只有浏览器还连接着才关闭）
                if (existingContext.browser() != null && existingContext.browser().isConnected()) {
                    existingContext.close();
                }
            } catch (Exception e) {
                logger.warn("Failed to close existing context: {}", e.getMessage());
            } finally {
                TestContextHolder.get().remove(PlaywrightManager.CONTEXT_KEY);
            }
            VerboseLogging.logInfoIfVerbose(logger, "Context closed, will create new one with custom configurations on next access");
        }
    }
    /**
     * 创建新的 BrowserContext（保证场景间配置隔离）
     * 委托给 PlaywrightContextManager 处理
     */
    public BrowserContext createContext() {
        return PlaywrightContextManager.createContext();
    }
    /**
     * 当前线程的 configId 是否已被标记为废弃（其它线程正在切换浏览器类型）。
     * 用于修复 1.2 的竞态：让并发线程感知并强制重建本地 Context/Page。
     */
    public boolean isCurrentConfigRetired() {
        String mine = TestContextHolder.get().get(PlaywrightManager.CURRENT_CONFIG_ID_KEY);
        if (mine == null) {
            return false;
        }
        return PlaywrightRuntime.instance().state.isRetired(mine);
    }
    /**
     * 关闭当前线程的 Context
     * 委托给 PlaywrightContextManager 处理
     */
    public void closeContext() {
        LifecycleLockMediator.withContextLock(() -> {
            BrowserContext context = TestContextHolder.get().get(PlaywrightManager.CONTEXT_KEY);
            // 与底层 BrowserContext 资源绑定的清理：仅当 context 真实存在时执行。
            if (context != null) {
                //  修复 R6：每条清理步骤独立 try-catch，避免任一失败中断整条清理链
                // （例如 RoleElementPicker.cleanupContext 抛异常会导致后续清理被跳过）。
                PlaywrightRuntime.instance().browserCleanup.safeClean("RouteRegistry.clearContext", () -> RouteLifecycleRegistry.get().clearContext(context));
                PlaywrightRuntime.instance().browserCleanup.safeClean("RoleCodegenBridge.cleanupContext", () -> {
                    // 经 codegen 桥接（未注册=no-op，等价于默认关闭，零回归）。
                    RoleCodegenBridgeRegistry.getBridge().ifPresent(b -> {
                        if (b.isCodegenEnabled()) {
                            b.cleanupContext(context);
                        }
                    });
                });
                PlaywrightRuntime.instance().browserCleanup.safeClean("RouteEngine.stopContextEngine", () -> RouteLifecycleRegistry.get().stopContextEngine(context));
                PlaywrightRuntime.instance().browserCleanup.safeClean("PlaywrightContextManager.closeContext", () -> PlaywrightContextManager.closeContext(context));
                TestContextHolder.get().remove(PlaywrightManager.CONTEXT_KEY);
            }
            //  T3-3 修复：per-thread 状态清理必须【无条件】执行。
            // 原实现把 TestServices.clear 等包在 if(context!=null) 内，
            // 导致 context 为 null 的路径（feature 模式无 session 复用、未创建 context 的场景、
            // 场景初始化重建路径）下这些 per-thread 状态跨 scenario 残留，污染下一个场景
            // （继承旧 entity/env/自定义配置）。现移出 if 块，无论 context 是否存在都清理；
            // 并补上此前遗漏的 CustomOptionsManager 全量清理（调用 closeContext 即视为场景结束）。
            // 注：BasePage 的静态 ThreadLocal 清理（clearAllThreadLocals）已在架构整改中移除——
            // 该静态引用本就是死状态，iframe/shadow 上下文已改为每实例独立持有（见 BasePage.currentFrame/currentShadow）。
            PlaywrightRuntime.instance().browserCleanup.safeClean("TestServices.clear", () -> {
                try {
                    com.hsbc.cmb.hk.dbb.automation.framework.api.core.services.TestServices.clear();
                } catch (Throwable ignored) {
                    // API 模块不一定被 classloader 看到（仅 UI 框架独立运行时），兜底静默
                }
            });
            PlaywrightRuntime.instance().browserCleanup.safeClean("CustomOptionsManager.removeAllThreadLocals", CustomOptionsManager::removeAllThreadLocals);
        });
    }

    /**
     * 检查当前线程是否有存活的 Context（不创建新 Context）
     * <p>
     * 与 PlaywrightManager.getContext() 的区别：PlaywrightManager.getContext() 在 Context 不存在时会创建新的，
     * 本方法仅检查存在性，用于 SessionManager 判断是否已有活跃的 Context
     *
     * @return true 如果 ThreadLocal 中有存活的 Context
     */
    public boolean hasContext() {
        BrowserContext context = TestContextHolder.get().get(PlaywrightManager.CONTEXT_KEY);
        return context != null && context.browser() != null && context.browser().isConnected();
    }

    /**
     * 创建新的 Context 和 Page（委托给 PlaywrightSerenityBridge）
     */
    public void createNewContextAndPage() {
        PlaywrightSerenityBridge.createNewContextAndPage();
    }

    /**
     * 丢弃当前线程的 Page/Context（仅关闭，不清除 custom options），
     * 使下一次 PlaywrightManager.getContext()/PlaywrightManager.getPage() 在干净起点重建。
     * <p>
     * 用于 Feature 模式切换不同 env/user（不同 sessionKey）时，避免复用上一个 session 的
     * Context（其 Cookie/Storage 残留会污染新用户）。调用方负责随后清理/重设自定义配置
     * （例如清除过期 storageStatePath），本方法仅关闭 Page+Context。
     */
    public void discardCurrentContext() {
        scheduleContextRebuild();
    }

}
