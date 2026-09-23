package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * N-16 契约（doc 21 MEDIUM）：页面 / 导航超时必须带<b>正值下界</b>。
 *
 * <p><b>要防的缺陷</b>：Playwright 语义中 {@code 0} = <b>无限等待</b>。元素侧此前已补
 * {@code Math.max(1, ceil(ms/1000))} 下界，页面 / 导航侧却原样透传 —— 误配
 * {@code playwright.page.timeout=0}（或负值）会让所有页面级操作<b>永久挂起且无任何信号</b>
 * （默认 15000 尚安全，所以这类误配只有在真的被写进配置时才暴露）。</p>
 *
 * <p>本用例在旧实现下无对应函数可断言（旧实现直接透传配置值，0 会原样下发）。</p>
 */
class PlaywrightContextManagerTimeoutGuardTest {

    private static final int DEFAULT_MS = 15_000;

    @Test
    @DisplayName("N-16：合法正值原样下发（不得改写用户配置）")
    void positiveValueIsPassedThrough() {
        assertEquals(2000, PlaywrightContextManager.positiveTimeoutOrDefault("playwright.page.timeout", 2000),
                "正值必须原样使用，不能擅自改写");
        assertEquals(1, PlaywrightContextManager.positiveTimeoutOrDefault("playwright.page.timeout", 1),
                "最小合法正值 1ms 亦原样使用");
    }

    @Test
    @DisplayName("N-16：0 被视为无限等待 → 必须回落到默认值（否则操作永久挂起且无信号）")
    void zeroFallsBackToDefault() {
        assertEquals(DEFAULT_MS, PlaywrightContextManager.positiveTimeoutOrDefault("playwright.page.timeout", 0),
                "配置 0 在 Playwright 语义中 = 无限等待，必须回落默认值而非透传");
    }

    @Test
    @DisplayName("N-16：负值同样回落默认值")
    void negativeFallsBackToDefault() {
        assertEquals(DEFAULT_MS,
                PlaywrightContextManager.positiveTimeoutOrDefault("playwright.page.navigationTimeout", -1),
                "负值非法，必须回落默认值");
        assertEquals(DEFAULT_MS,
                PlaywrightContextManager.positiveTimeoutOrDefault("playwright.page.navigationTimeout", Integer.MIN_VALUE),
                "极端负值亦不得透传（否则行为未定义）");
    }
}
