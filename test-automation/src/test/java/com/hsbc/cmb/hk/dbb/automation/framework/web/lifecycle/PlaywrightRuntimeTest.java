package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestart;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistry;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

/**
 * {@link PlaywrightRuntime} 组合根单测（DI 二期 Phase 2）。
 * 仅验证组合根自身（默认单例持有 6 协作者 + 状态根、注入构造拒绝 null），不触达真实运行时。
 *
 * <p>doc16 Phase 2：状态根 {@link LifecycleState} 成为第 7 个可替换角色，故补充
 * 「默认非空 / null 防御 / 替换生效」三项覆盖，使其余 6 个协作者的契约保持一致。
 */
public class PlaywrightRuntimeTest {

    @Test
    public void defaultInstanceHoldsAllCollaborators() {
        PlaywrightRuntime rt = PlaywrightRuntime.instance();
        assertNotNull(rt);
        assertNotNull(rt.browserRegistry);
        assertNotNull(rt.contextRegistry);
        assertNotNull(rt.pageRegistry);
        assertNotNull(rt.browserStartup);
        assertNotNull(rt.browserRestart);
        assertNotNull(rt.browserCleanup);
        assertNotNull(rt.state);
    }

    @Test(expected = NullPointerException.class)
    public void injectionConstructorRejectsNullCollaborator() {
        new PlaywrightRuntime(null, null, null, null, null, null, null);
    }

    /** 6 个协作者取替身、仅替换状态根，用于隔离验证 state 角色本身。 */
    private static PlaywrightRuntime runtimeWithState(LifecycleState state) {
        return new PlaywrightRuntime(mock(BrowserRegistry.class),
                mock(ContextRegistry.class),
                mock(PageRegistry.class),
                mock(BrowserStartup.class),
                mock(BrowserRestart.class),
                mock(BrowserCleanup.class),
                state);
    }

    @Test(expected = NullPointerException.class)
    public void injectionConstructorRejectsNullState() {
        runtimeWithState(null);
    }

    @Test
    public void stateRoleCanBeSwapped() {
        LifecycleState fake = mock(LifecycleState.class);
        assertSame(fake, runtimeWithState(fake).state);
    }
}
