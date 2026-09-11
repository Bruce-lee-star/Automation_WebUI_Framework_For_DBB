package com.hsbc.cmb.hk.dbb.automation.framework.common.assertion;

/**
 * 框架断言失败的语义化异常类型 —— D3-2。
 *
 * <p>继承 {@link AssertionError} 而非自定义 {@link RuntimeException}：
 * <ul>
 *   <li>JUnit / TestNG / Cucumber 均把 {@code AssertionError} 识别为<b>断言失败</b>（与"错误"区分），
 *       报告与 IDE 标红语义正确；</li>
 *   <li>Serenity 的 {@code StepEventBus.testFailed(AssertionError)} 与框架既有失败传播 seam 天然兼容。</li>
 * </ul>
 *
 * <p>业务不应捕获本异常；它是终态信号。
 */
public class FrameworkAssertionError extends AssertionError {

    private static final long serialVersionUID = 1L;

    public FrameworkAssertionError(String message) {
        super(message);
    }

    public FrameworkAssertionError(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}
