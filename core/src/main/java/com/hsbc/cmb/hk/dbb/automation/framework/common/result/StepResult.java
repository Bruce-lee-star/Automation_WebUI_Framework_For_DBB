package com.hsbc.cmb.hk.dbb.automation.framework.common.result;

import java.util.Objects;

/**
 * 框架自有的步骤结果模型（<b>不可变</b>）—— D4-2。
 *
 * <p>与 {@link TestResult} 同理：步骤结果不再直接使用报告引擎的类型，
 * 框架内流转的是本模型，由 Adapter 负责与具体引擎互转。
 *
 * <p>不可变是刻意的：结果对象会在监听器与报告线程之间传递，
 * 可变对象会带来并发可见性与"被中途篡改"的风险。
 */
public final class StepResult {

    private final String title;
    private final TestResult result;
    private final long startTimeMs;
    private final long durationMs;
    private final String errorMessage;

    public StepResult(String title, TestResult result, long startTimeMs,
                      long durationMs, String errorMessage) {
        this.title = title;
        this.result = result == null ? TestResult.UNKNOWN : result;
        this.startTimeMs = startTimeMs;
        this.durationMs = durationMs;
        this.errorMessage = errorMessage;
    }

    /** 便捷构造（无错误信息）。 */
    public static StepResult of(String title, TestResult result, long startTimeMs, long durationMs) {
        return new StepResult(title, result, startTimeMs, durationMs, null);
    }

    /** 失败步骤便捷构造。 */
    public static StepResult failed(String title, long startTimeMs, long durationMs, String errorMessage) {
        return new StepResult(title, TestResult.FAILURE, startTimeMs, durationMs, errorMessage);
    }

    public String getTitle() {
        return title;
    }

    public TestResult getResult() {
        return result;
    }

    public long getStartTimeMs() {
        return startTimeMs;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StepResult)) return false;
        StepResult that = (StepResult) o;
        return startTimeMs == that.startTimeMs
                && durationMs == that.durationMs
                && Objects.equals(title, that.title)
                && result == that.result
                && Objects.equals(errorMessage, that.errorMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, result, startTimeMs, durationMs, errorMessage);
    }

    @Override
    public String toString() {
        return "StepResult{title='" + title + "', result=" + result
                + ", durationMs=" + durationMs
                + (errorMessage == null ? "" : ", error='" + errorMessage + "'") + '}';
    }
}
