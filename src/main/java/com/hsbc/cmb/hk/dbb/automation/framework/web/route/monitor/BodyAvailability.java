package com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor;

/** 响应体在 Monitor 完成时的可用状态。 */
public enum BodyAvailability {
    /** 已成功读取非空响应体。 */
    AVAILABLE,
    /** 已成功读取，但响应体为空。 */
    EMPTY,
    /** 已尝试读取，但浏览器或协议层未能提供响应体。 */
    UNAVAILABLE,
    /** 当前采集策略明确未请求响应体。 */
    NOT_REQUESTED
}
