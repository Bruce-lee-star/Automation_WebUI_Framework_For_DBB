package com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions;

/**
 * 并发闸门等待超时异常（fail-closed 语义）。
 *
 * <p><b>为什么需要它</b>：SSO 感知并发闸门按身份（生产上为 {@code sessionKey}）串行化同一身份的
 * 场景；若等待许可超时后<b>静默放行</b>，串行化即失效 —— 同一会话被并发使用会互相踩踏
 * （SSO 单会话互踢、storageState 覆写），表现为随机 401 / 重登录失败 / 断言漂移，且用例
 * <b>仍可能通过</b>，属"最危险的静默降级"（评审 F-11）。</p>
 *
 * <p>故超时默认<b>失败快</b>：抛出本异常，由调用方（登录边界 / 并发用例执行）如实判为该场景失败。
 * 需要退回旧行为时显式配置
 * {@code serenity.playwright.concurrent.partition.fail.closed=false}（逃生舱）。</p>
 */
public class ConcurrencyGateTimeoutException extends FrameworkException {

    private static final long serialVersionUID = 1L;

    public ConcurrencyGateTimeoutException(String message) {
        super(message);
    }

    public ConcurrencyGateTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
