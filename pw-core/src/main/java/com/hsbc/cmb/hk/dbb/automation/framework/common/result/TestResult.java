package com.hsbc.cmb.hk.dbb.automation.framework.common.result;

/**
 * 框架自有的测试结果枚举 —— D4-2。
 *
 * <p><b>为什么要自建</b>：此前结果语义直接借用报告引擎（Serenity）的枚举，
 * 导致框架核心与报告引擎耦合 —— 换引擎（或同一引擎跨版本改枚举名）就要改框架与业务代码。
 *
 * <p>本枚举<b>只表达语义、不依赖任何报告引擎</b>；引擎之间的转换一律由 Adapter 完成
 * （如 {@code SerenityResultAdapter}）。换报告引擎 = 换一个 Adapter，框架与业务零改动。
 *
 * <p>取值刻意保持小而通用：各引擎的特有状态（如 Serenity 的 UNDEFINED / COMPROMISED）
 * 统一归并到 {@link #UNKNOWN} 或最接近的语义，避免框架被引擎方言污染。
 */
public enum TestResult {

    /** 成功。 */
    SUCCESS,
    /** 断言失败。 */
    FAILURE,
    /** 执行错误（异常导致，非断言）。 */
    ERROR,
    /** 待实现 / 挂起。 */
    PENDING,
    /** 被跳过。 */
    SKIPPED,
    /** 未知 / 未归类（含引擎特有状态）。 */
    UNKNOWN;

    /** 是否属于失败（FAILURE / ERROR）。 */
    public boolean isFailure() {
        return this == FAILURE || this == ERROR;
    }

    /** 是否成功。 */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /** 是否"未完成"（挂起 / 跳过）。 */
    public boolean isPending() {
        return this == PENDING || this == SKIPPED;
    }
}
