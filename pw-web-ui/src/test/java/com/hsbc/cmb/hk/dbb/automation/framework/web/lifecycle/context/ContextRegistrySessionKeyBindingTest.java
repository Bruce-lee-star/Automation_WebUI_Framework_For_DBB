package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A5（2026-09-26）回归守卫：Context ↔ 登录 sessionKey 绑定的生命周期语义。
 *
 * <p><b>被守卫的真实缺陷</b>：feature 模式下"收尾是否保留 Context"原判据是 SessionManager 的
 * "feature session restored" 标志，而该标志会被用例失败清空（session 校验失败 → {@code clearSession}）。
 * 后果：同一个 sessionKey 的用例失败后，框架误判"无登录态"→ 关掉 Context → 后续同 key 用例被迫完整重登
 * （实证 1.txt：场景2 校验失败后，场景3 重新登录并卡死）。
 *
 * <p>本用例锁定新判据的基础不变量：绑定<b>随 Context 生命周期存活</b>（登录态无关失败），
 * 且只随 Context 被丢弃/关闭而清除。
 */
class ContextRegistrySessionKeyBindingTest {

    @AfterEach
    void cleanup() {
        //  还原线程级绑定，避免污染同一 worker 线程上的其它用例
        ContextRegistryImpl.INSTANCE.discardCurrentContext();
    }

    @Test
    @DisplayName("A5：可绑定并读回当前 Context 承载的登录 sessionKey")
    void bindsAndReadsSessionKey() {
        ContextRegistryImpl.INSTANCE.bindCurrentContextSessionKey("O63_SIT1_WP7UAT2_2");

        assertEquals("O63_SIT1_WP7UAT2_2",
                ContextRegistryImpl.INSTANCE.currentContextSessionKeyForThread());
    }

    @Test
    @DisplayName("A5：null / 空 sessionKey 为 no-op，不得抹掉已有绑定")
    void nullOrEmptyKeyDoesNotOverwriteExistingBinding() {
        ContextRegistryImpl.INSTANCE.bindCurrentContextSessionKey("k1");

        ContextRegistryImpl.INSTANCE.bindCurrentContextSessionKey(null);
        ContextRegistryImpl.INSTANCE.bindCurrentContextSessionKey("");

        assertEquals("k1", ContextRegistryImpl.INSTANCE.currentContextSessionKeyForThread(),
                "未知 sessionKey 不得把已知绑定抹掉（否则会退化为误判\"无登录态\"而重建 Context）");
    }

    @Test
    @DisplayName("A5：Context 被丢弃时绑定必须同步清除")
    void bindingClearedWhenContextDiscarded() {
        ContextRegistryImpl.INSTANCE.bindCurrentContextSessionKey("k2");

        ContextRegistryImpl.INSTANCE.discardCurrentContext();

        assertNull(ContextRegistryImpl.INSTANCE.currentContextSessionKeyForThread(),
                "Context 已不在 → 绑定必须清除，否则下个用例会误以为当前 Context 仍承载旧 session");
    }

    @Test
    @DisplayName("A5：PlaywrightManager 静态 seam 读写同一绑定（SessionManager 走此入口）")
    void playwrightManagerSeamExposesSameBinding() {
        PlaywrightManager.bindCurrentContextSessionKey("k3");
        assertEquals("k3", PlaywrightManager.currentContextSessionKeyForThread());

        PlaywrightManager.discardCurrentContext();
        assertNull(PlaywrightManager.currentContextSessionKeyForThread());
    }
}
