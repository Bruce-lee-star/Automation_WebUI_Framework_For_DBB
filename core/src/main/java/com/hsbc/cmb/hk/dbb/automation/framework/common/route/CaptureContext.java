package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

/**
 * 当前采集上下文的核心层视图（SPI 一部分）。
 * 避免 web 直接依赖 {@code framework.web.route.core.ApiCaptureContext}，
 * 从而打破 {@code web ↔ route} 的循环依赖。
 *
 * <p>接口方法为 web（PlaywrightListener）实际所需的采集上下文操作；
 * route 模块的 {@code ApiCaptureContext} 实现本接口，行为与原生调用一致。
 */
public interface CaptureContext {

    /** 标记一个步骤开始（原 ApiCaptureContext.markStepStart）。 */
    void markStepStart();

    /** 是否存在 API 断言失败（原 ApiCaptureContext.hasAssertionFailures）。 */
    boolean hasAssertionFailures();

    /** 生成断言失败详细报告（原 ApiCaptureContext.buildFailureReport）。 */
    String buildFailureReport();

    /** 生成断言失败明细（原 ApiCaptureContext.buildFailureDetails）。 */
    String buildFailureDetails();

    /** 当前活跃请求数（原 ApiCaptureContext.getActiveRequests）。 */
    int getActiveRequests();

    /** 等待所有异步 API 请求完成（原 ApiCaptureContext.awaitCompletion，会抛出 InterruptedException）。 */
    boolean awaitCompletion(long timeoutMs) throws InterruptedException;
}
