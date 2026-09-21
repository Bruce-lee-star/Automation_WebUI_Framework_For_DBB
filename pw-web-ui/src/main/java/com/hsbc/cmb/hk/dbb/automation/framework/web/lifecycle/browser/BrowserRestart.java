package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestartImpl;

/**
 * Browser 重启与崩溃重建契约（WEB-P1-6 Phase 1 接口提取）。
 *
 * <p>默认实现 {@link BrowserRestartImpl}（WEB-P1-1 拆分成果的逻辑承载体，已实例化为单例）。
 * 该接口把「重启 / 崩溃重建」这组韧性职责收敛为可替换契约，
 * 供 {@code PlaywrightRuntime} 实例门面与测试替身经此接口替换实现。</p>
 *
 * @see BrowserRestartImpl
 */
public interface BrowserRestart {

    void restartBrowser();

    boolean rebuildBrowserIfDisconnected();

    boolean rebuildBrowser();
}