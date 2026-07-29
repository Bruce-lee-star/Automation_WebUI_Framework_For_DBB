package com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.client;

import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.core.entity.ApiRequestEntity;

/**
 * ApiInterceptor - 请求/响应拦截器（钩子），用于统一处理横切关注点，例如：
 * <ul>
 *   <li>发送前：加签名、注入 traceId、统一补充鉴权头；</li>
 *   <li>接收后：统一脱敏、统一日志、统一断言前置。</li>
 * </ul>
 * 通过 {@link PlaywrightApiClient#addInterceptor(ApiInterceptor)} 注册，按注册顺序执行。
 */
public interface ApiInterceptor {

    /** 发送请求前回调，可修改 entity（头/参数/body） */
    default void onRequest(ApiRequestEntity entity) {
    }

    /** 收到响应并写回 entity 后回调 */
    default void onResponse(ApiRequestEntity entity) {
    }
}
