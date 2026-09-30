package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestart;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.state.PlaywrightRuntimeState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用例异常收尾（失败/跳过/忽略）的资源处置契约（2026-09-30 政策：不复用活 Context）：
 * <b>无论是否承载登录态，异常终止都销毁本线程 Context 与 Page</b>；登录复用改走 storageState 快照
 * （{@code SessionManager.restoreSession()} 把文件级 storageState 注入新建 Context），不再保留活 Context。
 *
 * <p>零浏览器：全程 Mockito 替身 + {@link PlaywrightRuntime} 组合根替换（同 WEB-P1-6 seam）。</p>
 */
public class PlaywrightManagerCaseAbortCleanupTest {

    private final ContextRegistry contextRegistry = mock(ContextRegistry.class);
    private final PageRegistry pageRegistry = mock(PageRegistry.class);
    private final BrowserCleanup browserCleanup = mock(BrowserCleanup.class);

    @AfterEach
    public void tearDown() {
        // 复位组合根，避免污染其它测试
        PlaywrightRuntime.resetInstance();
        PlaywrightManager.resetProvider();
    }

    /** 同 sessionKey（Context 承载登录态）：异常终止也销毁 Context 与 Page（不再保留活 Context 延续登录态）。 */
    @Test
    public void sameSessionKey_abort_closesContextAndPage() {
        installRuntime();

        PlaywrightManager.clearThreadResourcesOnCaseAbort();

        // 不复用活 Context：即使承载登录态，异常终止也销毁，登录复用以 storageState 快照注入新建 Context
        verify(contextRegistry).closeContext();
        verify(pageRegistry).closePage();
        // 用例级清理仍必须发生：路由/采集状态 + 本线程孤儿 Context 回收
        verify(browserCleanup).contextsForCurrentThread();
        verify(browserCleanup).closeOrphanContextsForCurrentThread();
    }

    /** 无登录态绑定（纯 UI/无登录用例）：彻底拆除本线程 Context/Page（与同 sessionKey 路径一致，均销毁）。 */
    @Test
    public void noSessionKey_abort_closesContextAndPage() {
        installRuntime();

        PlaywrightManager.clearThreadResourcesOnCaseAbort();

        verify(contextRegistry).closeContext();
        verify(pageRegistry).closePage();
        verify(browserCleanup).closeOrphanContextsForCurrentThread();
    }

    /** 替换组合根：全部协作者用替身（方法不再按 sessionKey 分流）。 */
    private void installRuntime() {
        when(browserCleanup.contextsForCurrentThread()).thenReturn(List.of());
        PlaywrightRuntime.setInstance(new PlaywrightRuntime(
                mock(BrowserRegistry.class),
                contextRegistry,
                pageRegistry,
                mock(BrowserStartup.class),
                mock(BrowserRestart.class),
                browserCleanup,
                PlaywrightRuntimeState.INSTANCE));
    }
}
