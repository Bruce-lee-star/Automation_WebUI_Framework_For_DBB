package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistryImpl;

import com.microsoft.playwright.BrowserContext;

/**
 * Context 注册契约（WEB-P1-6 Phase 1 接口提取）。
 *
 * <p>默认实现 {@link ContextRegistryImpl}（WEB-P1-1 拆分成果的逻辑承载体，已实例化为单例）。
 * 该接口把 Context 的「获取 / 重建 / 关闭 / 存在性」职责收敛为可替换契约，
 * 供 {@code PlaywrightRuntime} 实例门面与测试替身经此接口替换实现。</p>
 *
 * @see ContextRegistryImpl
 */
public interface ContextRegistry {

    BrowserContext getContext();

    void scheduleContextRebuild();

    void recreateContextIfCustomConfigNeeded();

    BrowserContext createContext();

    boolean isCurrentConfigRetired();

    void closeContext();

    boolean hasContext();

    /**
     * 获取当前线程的活跃 Context（<b>不创建、不重建</b>）。
     * <p>与 {@link #hasContext()} 区别：本方法返回 Context 引用（可能为 {@code null}）；
     * {@code hasContext()} 仅返回布尔。供 {@code SessionManager}「就地换会话」在已存在 Context 上
     * 调用 {@code BrowserContext.setStorageState} 使用，避免改会话即重建 Context 的绕路。</p>
     *
     * @return 当前活跃且已连接的 Context；无或已断开时返回 {@code null}
     */
    BrowserContext getCurrentContext();

    void createNewContextAndPage();

    void discardCurrentContext();

    /**
     * 本线程当前持有的 Context（<b>线程级</b>记录，独立于用例级的 {@code CONTEXT_KEY}）。
     *
     * <p>用途：① 收尾（Serenity {@code testFinished}，此时用例级 {@code CONTEXT_KEY} 已被 Cucumber
     * {@code @After} 清空）时仍能可靠关闭本线程 Context；② 孤儿回收时<b>保护</b>在用/复用的 Context 不被误关。
     *
     * @return 本线程当前 Context；无则 {@code null}
     */
    BrowserContext currentContextForThread();
}