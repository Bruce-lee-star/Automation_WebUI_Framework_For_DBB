package com.hsbc.cmb.hk.dbb.automation.framework.common.result;

/**
 * 结果上报端口（框架自有）—— D4-2。
 *
 * <p><b>存在的意义</b>：框架与业务只面向本接口产出结果（{@link StepResult} / {@link TestResult}），
 * 具体"写到哪个报告引擎"由实现方决定。
 * Serenity 的实现是 {@code SerenityReporter} —— 它<b>退化为单向适配器</b>：
 * 只负责把框架模型单向推送到 Serenity，不再反向定义框架的语义。
 *
 * <p><b>换报告引擎不需重写</b>：新增引擎只需再写一个 {@code ResultReporter} 实现并注册，
 * 框架核心、{@code PlaywrightListener} 与业务代码全部零改动。
 *
 * @apiNote 实现类必须是线程安全的：回调可能来自多个执行线程。
 */
public interface ResultReporter {

    /** 上报一个步骤结果。 */
    void reportStep(StepResult step);

    /** 上报一个场景（用例）结果。 */
    void reportScenario(String scenarioName, TestResult result, long durationMs);
}
