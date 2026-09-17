package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：Browser 实例键<b>恒为</b> {@code "<threadId>:<configId>"}。
 *
 * <p>背景（W-7 / DEV-V3 最终结论）：Playwright for Java 官方明确 <i>"Playwright Java is not thread safe"</i> ——
 * {@code Playwright}/{@code Browser}/{@code BrowserContext}/{@code Page} 必须在创建它们的同一线程调用；
 * 跨线程共享单个 {@code Browser} 会损坏客户端对象注册表（{@code __adopt__} / {@code pausedStateChanged}）。
 * 因此「共享 Browser 模式」已从框架<b>彻底移除</b>（含配置项、{@code enable/isSharedBrowserMode} API
 * 与 {@code keyFor} 的 {@code shared:} 分支），并发统一为「N 并行 = N 线程 = N Browser」。
 *
 * <p>本测试把该不变式固化为可执行断言，防止共享模式以任何形式回归（例如后人再加一个
 * {@code shared:} 前缀以"省内存"）。
 */
class BrowserInstanceKeyInvariantTest {

    private static final String CONFIG_ID = "chromium_headless_";

    /** 键必须带本线程 threadId 维度，且不得出现任何"共享"标记。 */
    @Test
    void keyIsPerThreadAndNeverShared() {
        String key = BrowserRegistryImpl.INSTANCE.keyFor(CONFIG_ID);
        assertTrue(key.startsWith(Thread.currentThread().threadId() + ":"),
                "Browser 键必须以 threadId 为前缀（每线程独立 Browser）：" + key);
        assertTrue(key.endsWith(":" + CONFIG_ID), "键须以 configId 结尾：" + key);
        assertFalse(key.toLowerCase().contains("shared"),
                "共享 Browser 模式已移除（Playwright Java 非线程安全），键中不得出现 shared 维度：" + key);
    }

    /** 不同线程必须得到不同的键 —— 即不可能共享同一 Browser 实例。 */
    @Test
    void keyDiffersAcrossThreads() throws Exception {
        String mainKey = BrowserRegistryImpl.INSTANCE.keyFor(CONFIG_ID);
        String[] other = new String[1];
        Thread worker = new Thread(
                () -> other[0] = BrowserRegistryImpl.INSTANCE.keyFor(CONFIG_ID), "key-invariant-worker");
        worker.start();
        worker.join(5_000);
        assertNotEquals(mainKey, other[0], "不同线程必须得到不同的 Browser 键（不得共享实例）");
    }

    /** 空白/ null configId 必须 fail-fast（否则会生成无法定位的孤儿键）。 */
    @Test
    void blankConfigIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BrowserRegistryImpl.INSTANCE.keyFor(null));
        assertThrows(IllegalArgumentException.class, () -> BrowserRegistryImpl.INSTANCE.keyFor("   "));
        // 形态校验：必须含 '_' 分隔符（'<browserType>_<headless>[_channel]'）
        assertThrows(IllegalArgumentException.class,
                () -> BrowserRegistryImpl.INSTANCE.validateConfigIdShape("chromiumheadless"));
    }
}
