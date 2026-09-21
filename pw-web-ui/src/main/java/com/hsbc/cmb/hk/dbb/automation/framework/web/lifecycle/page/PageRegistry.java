package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistryImpl;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

/**
 * Page 注册契约（WEB-P1-6 Phase 1 接口提取）。
 *
 * <p>默认实现 {@link PageRegistryImpl}（WEB-P1-1 拆分成果的逻辑承载体，已实例化为单例）。
 * 该接口把 Page 的「获取 / 创建 / 关闭 / 设置」职责收敛为可替换契约，
 * 供 {@code PlaywrightRuntime} 实例门面与测试替身经此接口替换实现。</p>
 *
 * @see PageRegistryImpl
 */
public interface PageRegistry {

    Page getPage();

    Page createPage(BrowserContext context);

    void closePage();

    void setPage(Page page);

    /**
     * 本线程当前 Page（<b>线程级</b>记录，独立于用例级的 {@code PAGE_KEY}）。
     *
     * <p>用途：① 收尾（Serenity {@code testFinished}，用例级键已被 Cucumber {@code @After} 清空）时
     * 仍能可靠关闭本线程 Page；② feature 模式<b>跨用例复用 Context/Page</b> 的判定依据。
     *
     * @return 本线程当前 Page；无则 {@code null}
     */
    Page currentPageForThread();
}