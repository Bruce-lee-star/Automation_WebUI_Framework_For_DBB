package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.lock.LifecycleLockMediator;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.serenity.PlaywrightSerenityBridge;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap.PlaywrightContextManager;

import com.hsbc.cmb.hk.dbb.automation.framework.web.codegen.spi.RoleCodegenBridgeRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteLifecycleRegistry;


import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.VerboseLogging;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;

/** Context registry (WEB-P1-1 Step 4). Package-private internal collaborator. */
public final class ContextRegistryImpl implements ContextRegistry {

    /** Default singleton instance (stateless, thread-safe). */
    public static final ContextRegistryImpl INSTANCE = new ContextRegistryImpl();

    private static final Logger logger = LoggerFactory.getLogger(PlaywrightManager.class);

    /**
     * 本线程最近创建/持有的 Context —— <b>线程级</b>持有，独立于用例级的
     * {@link PlaywrightManager#CONTEXT_KEY}。
     *
     * <p><b>为什么需要它（窗口堆积根因）</b>：Cucumber 下 {@code TestContextHolder} 解析为<b>用例级</b>，
     * 而 Cucumber {@code @After}（{@code ScenarioContext.end} → {@code ctx.clear()}）早于 Serenity
     * {@code testFinished}，故收尾时 {@code CONTEXT_KEY} 已不存在 → 原 {@code closeContext()} 整段被
     * 静默跳过，BrowserContext（headed 下即 OS 窗口）泄漏并跨用例堆积。本线程级记录不受用例边界影响，
     * 使收尾能可靠拿到并关闭本线程 Context；同时供孤儿回收<b>保护在用 Context</b>。
     */
    private static final ThreadLocal<BrowserContext> CURRENT_CONTEXT_BY_THREAD = new ThreadLocal<>();

    private ContextRegistryImpl() {
    }

    public BrowserContext getContext() {
        if (!FrameworkState.getInstance().isInitialized()) {
            throw new IllegalStateException("Playwright environment not initialized. Call FrameworkCore.initialize() first.");
        }

        //  修复 1.2：若本线程的 configId 已被标记为废弃（其它线程正在切换浏览器类型），
        // 强制清理本地 Context/Page，避免绑定到即将被关闭的旧 Browser。
        if (isCurrentConfigRetired()) {
            PlaywrightManager.closePage();
            PlaywrightManager.closeContext();
        }

        BrowserContext context = TestContextHolder.get().get(PlaywrightManager.CONTEXT_KEY);
        //  窗口堆积修复（复用优先）：用例级 CONTEXT_KEY 可能暂时取不到（如 @After 之后、
        //    或异步/事件回调路径），此时回退到线程级记录复用，避免对同一线程重复 newContext
        //    （headed 下即重复开窗，是窗口堆积的主因）。
        if (context == null) {
            BrowserContext threaded = CURRENT_CONTEXT_BY_THREAD.get();
            if (threaded != null && threaded.browser() != null && threaded.browser().isConnected()) {
                context = threaded;
                TestContextHolder.get().set(PlaywrightManager.CONTEXT_KEY, threaded);
            }
        }
        
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
            //  线程级记录：使收尾（用例级 CONTEXT_KEY 已被 @After 清空）仍能可靠关闭本线程 Context
            CURRENT_CONTEXT_BY_THREAD.set(result);
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
        //  窗口堆积修复：用例级 CONTEXT_KEY 可能已被 @After 清空，回退到线程级记录以免泄漏
        BrowserContext existingContext = TestContextHolder.get().get(PlaywrightManager.CONTEXT_KEY);
        if (existingContext == null) {
            existingContext = CURRENT_CONTEXT_BY_THREAD.get();
        }
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
                CURRENT_CONTEXT_BY_THREAD.remove();
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
        if (existingContext == null) {
            existingContext = CURRENT_CONTEXT_BY_THREAD.get();
        }
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
                CURRENT_CONTEXT_BY_THREAD.remove();
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
            //  窗口堆积修复（可靠关闭）：用例级 CONTEXT_KEY 在收尾时可能已被 Cucumber @After 清空，
            //    此时回退到线程级记录，避免"context==null → 整段静默跳过 → Context/窗口泄漏"。
            BrowserContext resolved = TestContextHolder.get().get(PlaywrightManager.CONTEXT_KEY);
            if (resolved == null) {
                resolved = CURRENT_CONTEXT_BY_THREAD.get();
            }
            //  lambda 捕获要求 effectively-final：此处定稿
            final BrowserContext context = resolved;
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
                //  线程级记录同步清除，避免持有已关闭 Context 引用
                CURRENT_CONTEXT_BY_THREAD.remove();
            }
            //  T3-3 修复：per-thread 状态清理必须【无条件】执行。
            // 原实现把 TestServices.clear 等包在 if(context!=null) 内，
            // 导致 context 为 null 的路径（feature 模式无 session 复用、未创建 context 的场景、
            // 场景初始化重建路径）下这些 per-thread 状态跨 scenario 残留，污染下一个场景
            // （继承旧 entity/env/自定义配置）。现移出 if 块，无论 context 是否存在都清理；
            // 并补上此前遗漏的 CustomOptionsManager 全量清理（调用 closeContext 即视为场景结束）。
            // 注：BasePage 的静态 ThreadLocal 清理（clearAllThreadLocals）已在架构整改中移除——
            // 该静态引用本就是死状态，iframe/shadow 上下文已改为每实例独立持有（见 BasePage.currentFrame/currentShadow）。
            PlaywrightRuntime.instance().browserCleanup.safeClean("TestServices.clear", ContextRegistryImpl::clearApiTestServices);
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
     * ARCH-1（2026-09-20）：反射调用 api 模块的 {@code TestServices.clear()}，清除 API 侧 per-thread 状态。
     * <p>采用反射（而非编译期引用）是为了让 web 在<b>不依赖 api 模块</b>的前提下仍能在 api 在场时联动清理：
     * 纯 UI 框架独立运行（api 未加载）时 {@code ClassNotFound} → 降级跳过，与原"API 模块不一定被 classloader 看到"语义一致。
     */
    private static void clearApiTestServices() {
        try {
            Class.forName("com.hsbc.cmb.hk.dbb.automation.framework.api.core.services.TestServices")
                    .getMethod("clear")
                    .invoke(null);
        } catch (Throwable e) {
            // api 模块未加载：预期降级，不静默（仍记 debug 便于排查）
            logger.debug("[ContextRegistry] TestServices.clear skipped (api module not visible): {}", e.toString());
        }
    }

    /**
     * 获取当前线程的活跃 Context（不创建、不重建）。
     * 供 SessionManager「就地换会话」在已有 Context 上调用 {@code BrowserContext.setStorageState}。
     */
    @Override
    public BrowserContext getCurrentContext() {
        BrowserContext context = TestContextHolder.get().get(PlaywrightManager.CONTEXT_KEY);
        return (context != null && context.browser() != null && context.browser().isConnected()) ? context : null;
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

    /** 本线程当前 Context（线程级记录；供收尾可靠关闭与孤儿回收保护使用）。 */
    public BrowserContext currentContextForThread() {
        return CURRENT_CONTEXT_BY_THREAD.get();
    }

}