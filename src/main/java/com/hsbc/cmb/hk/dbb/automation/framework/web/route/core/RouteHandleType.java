package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

/**
 * 路由处理类型枚举
 * 区分监控/修改请求/Mock/弱网延迟四种场景
 */
public enum RouteHandleType {
    /**
     * 仅监控，不修改请求响应
     * 先放行，后异步解析，零阻塞 UI
     */
    MONITOR,

    /**
     * 修改请求头/请求体
     * 拦截请求，修改后继续发送
     */
    MODIFY,

    /**
     * 直接 Mock 返回响应
     * 拦截请求，直接返回自定义响应
     */
    MOCK,

    /**
     * 弱网延迟模拟
     * 拦截请求，等待指定毫秒后放行（模拟高延迟网络）
     */
    DELAY
}
