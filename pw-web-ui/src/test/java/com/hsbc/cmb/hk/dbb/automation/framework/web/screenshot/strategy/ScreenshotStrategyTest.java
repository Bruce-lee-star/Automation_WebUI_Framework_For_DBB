package com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.strategy;

import net.thucydides.model.domain.TestResult;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 截图策略解析验证。
 *
 * <p><b>本类钉住的回归</b>：配置 {@code serenity.screenshot.strategy} 同时被 Serenity 生态使用，
 * 官方名 {@code AFTER_FAILING_STEP}（只失败时截图）不在框架枚举名里。原实现 {@code valueOf} 抛异常后
 * <b>静默</b>退回 {@code AFTER_EACH_STEP}，于是「只失败时截图」实际变成「每步都截」——
 * 一轮 E2E 因此产出 85 张截图，且因兜底值与 {@code ConfigKeys} 默认值相同而长期无人察觉。</p>
 */
public class ScreenshotStrategyTest {

    /** Serenity 官方名必须被识别为"仅失败时截图"，且<b>不在步骤结束时</b>截图。 */
    @Test
    public void serenityAfterFailingStepMeansFailuresOnly() {
        ScreenshotStrategy strategy = ScreenshotStrategy.resolve("AFTER_FAILING_STEP");

        assertEquals((Object) ScreenshotStrategy.FOR_FAILURES, (Object) strategy);
        // "仅失败"的语义要点：步骤正常结束不截图（这正是每步一张的根因）
        assertFalse("AFTER_FAILING_STEP 不得在步骤结束时截图", strategy.shouldTakeScreenshotFor((TestResult) null));
        assertTrue("失败结果必须截图", strategy.shouldTakeScreenshotFor(TestResult.FAILURE));
        assertTrue("错误结果必须截图", strategy.shouldTakeScreenshotFor(TestResult.ERROR));
        assertFalse("成功结果不截图", strategy.shouldTakeScreenshotFor(TestResult.SUCCESS));
    }

    /** 大小写与空白不敏感；框架既有名与 Serenity 别名都要认。 */
    @Test
    public void acceptsBothNamingStylesCaseInsensitively() {
        assertEquals((Object) ScreenshotStrategy.AFTER_EACH_STEP,
                (Object) ScreenshotStrategy.resolve("after_each_step"));
        assertEquals((Object) ScreenshotStrategy.AFTER_EACH_STEP,
                (Object) ScreenshotStrategy.resolve("  FOR_EACH_STEP  "));
        assertEquals((Object) ScreenshotStrategy.DISABLED, (Object) ScreenshotStrategy.resolve("none"));
        assertEquals((Object) ScreenshotStrategy.BEFORE_AND_AFTER_EACH_STEP,
                (Object) ScreenshotStrategy.resolve("before_and_after_each_step"));
    }

    /**
     * 拼错的策略名必须向"少截"失败（而不是"每步都截"），且不得静默。
     */
    @Test
    public void unknownValueFailsSafeToFailuresOnly() {
        assertEquals((Object) ScreenshotStrategy.FOR_FAILURES,
                (Object) ScreenshotStrategy.resolve("NOT_A_REAL_STRATEGY"));
    }

    /** 未配置时维持既有默认（{@code ConfigKeys} 的默认值是 AFTER_EACH_STEP）。 */
    @Test
    public void blankKeepsExistingDefault() {
        assertEquals((Object) ScreenshotStrategy.AFTER_EACH_STEP, (Object) ScreenshotStrategy.resolve(null));
        assertEquals((Object) ScreenshotStrategy.AFTER_EACH_STEP, (Object) ScreenshotStrategy.resolve("   "));
    }
}
