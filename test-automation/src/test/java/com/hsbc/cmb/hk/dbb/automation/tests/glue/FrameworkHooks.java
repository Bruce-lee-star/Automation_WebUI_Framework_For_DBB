package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.framework.common.assertion.SoftAssertions;
import com.hsbc.cmb.hk.dbb.automation.framework.common.context.LanguageState;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ScenarioContext;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;

/**
 * 框架级场景钩子（C-4 修复 + C-1 用例级隔离闭环）。
 *
 * <p><b>C-4（软断言收集器清理）</b>：{@link SoftAssertions} 基于 ThreadLocal 收集场景内软断言失败；
 * 若某场景收集失败却未 {@code assertAll()}（或中途 skip / 抛错），残留失败会随线程复用泄漏到下一场景。
 * 本钩子在 scenario 结束后无条件 {@code clearForCurrentThread()} 切断该残留传递。</p>
 *
 * <p><b>C-1（用例级隔离闭环）</b>：将上下文隔离从"线程纪律"升级为"机器强制"——
 * 每个 scenario 经 {@link ScenarioContext#begin(String)} / {@link ScenarioContext#end(String)} 绑定到
 * 用例 id（而非仅靠 per-thread），{@link ScenarioContext#assertUnbound()} 在 {@code @Before} 与
 * {@code @After} 两处校验：{@code @Before} 前置校验本线程无上一用例残留绑定（真实泄漏守卫），
 * {@code @After} 后置校验 end 已释放绑定（id 错配 / 腐败即抛）。并发子用例的等价边界由
 * {@code ConcurrentContextExecutor.runOnce} 经同一 {@link ScenarioContext} 强制，二者共用同一主键模型。</p>
 *
 * <p><b>顺序约定</b>：{@code @Before} 取最低 order 抢在其它钩子之前建好用例上下文；
 * {@code @After} 取最高 order 确保所有消费方（含 Serenity 报告钩子）仍可见上下文后再解绑。
 * 二者均对所有场景生效（skip / 失败 / 抛错场景 Cucumber 仍会执行 {@code @After}），故 skip 路径同样被强制解绑。</p>
 *
 * <p>注：与 {@code ApiScenarioHooks} 各司其职——后者清理 {@code ApiTestContext}（API 场景共享态），
 * 本类专注用例上下文绑定 + 软断言收集器；二者均幂等、对不相关场景为空操作，无副作用。</p>
 */
public class FrameworkHooks {

    /** scenario 开始前：先校验本线程无残留用例绑定（防上一用例泄漏），再绑定本用例上下文，并复位语言态。 */
    @Before(order = Integer.MIN_VALUE)
    public void beforeScenario(Scenario scenario) {
        ScenarioContext.assertUnbound();
        ScenarioContext.begin(scenario.getId());
        clearScenarioScopedState();
    }

    /** scenario 结束后：清理软断言收集器与语言态，解除本用例绑定并断言已释放（防脏上下文泄漏到下一用例）。 */
    @After(order = Integer.MAX_VALUE)
    public void afterScenario(Scenario scenario) {
        try {
            clearScenarioScopedState();
        } finally {
            ScenarioContext.end(scenario.getId());
            ScenarioContext.assertUnbound();
        }
    }

    /**
     * 复位「scenario 级、且不受 {@code TestContextHolder} 托管」的共享状态：软断言收集器 + 语言态。
     *
     * <p><b>C-8</b>：{@code LanguageState.globalLang} 是进程级全局值（跨线程可见性所必需），不在
     * {@code TestContextHolder} 清理范围内，会跨用例残留并在并行下造成语言态串扰；故在 scenario 前后
     * 显式复位，保证「当前语言」从干净态开始、且不泄漏到下一用例。
     *
     * <p>抽为包可见静态方法以便单测直接校验复位行为（Cucumber 的 {@code io.cucumber.java.Scenario}
     * 是 final 类，无法以桩对象驱动 {@code @Before}/{@code @After}）。
     */
    static void clearScenarioScopedState() {
        SoftAssertions.clearForCurrentThread();
        LanguageState.reset();
    }
}
