package com.hsbc.cmb.hk.dbb.automation.framework.common.assertion;

import java.util.Objects;

/**
 * 一条断言失败记录（<b>不可变</b>）—— D3-2。
 *
 * <p>软断言（{@link SoftAssertions}）收集的就是本对象：保留 message / expected / actual，
 * 便于 scenario 末统一渲染成可读报告，而不是只留一句"有失败"。
 */
public final class AssertionFailure {

    private final String message;
    private final Object expected;
    private final Object actual;

    public AssertionFailure(String message, Object expected, Object actual) {
        this.message = message;
        this.expected = expected;
        this.actual = actual;
    }

    public String getMessage() {
        return message;
    }

    public Object getExpected() {
        return expected;
    }

    public Object getActual() {
        return actual;
    }

    /** 单行摘要（供日志与报告使用）。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(message == null ? "assertion failed" : message);
        if (expected != null || actual != null) {
            sb.append(" | expected=").append(expected).append(" | actual=").append(actual);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AssertionFailure)) return false;
        AssertionFailure that = (AssertionFailure) o;
        return Objects.equals(message, that.message)
                && Objects.equals(expected, that.expected)
                && Objects.equals(actual, that.actual);
    }

    @Override
    public int hashCode() {
        return Objects.hash(message, expected, actual);
    }

    @Override
    public String toString() {
        return describe();
    }
}
