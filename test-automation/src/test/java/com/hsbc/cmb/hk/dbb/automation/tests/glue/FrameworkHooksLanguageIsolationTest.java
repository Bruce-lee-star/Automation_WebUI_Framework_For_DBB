package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.framework.common.context.LanguageState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * C-8：{@link FrameworkHooks#clearScenarioScopedState()} 复位进程级语言态，防止
 * {@code LanguageState.globalLang} 跨用例残留与并行串扰。
 *
 * <p>说明：Cucumber 的 {@code io.cucumber.java.Scenario} 是 final 类，无法以桩对象驱动
 * {@code @Before}/{@code @After}，故直接校验其调用的复位方法（钩子两处均调用该方法，
 * 见 {@code FrameworkHooks#beforeScenario}/{@code #afterScenario}）。
 */
class FrameworkHooksLanguageIsolationTest {

    @Test
    void clearScenarioScopedState_resetsGlobalLanguageState() {
        LanguageState.setLanguage("zh");
        LanguageState.setLanguage("ja");

        FrameworkHooks.clearScenarioScopedState();

        assertNull(LanguageState.getLanguage(), "复位后不应再读到任何语言态（C-8）");
    }

    @Test
    void clearScenarioScopedState_isIdempotentOnFreshState() {
        FrameworkHooks.clearScenarioScopedState();
        FrameworkHooks.clearScenarioScopedState();

        assertNull(LanguageState.getLanguage(), "空状态下重复复位仍应保持无语言态");
    }
}
