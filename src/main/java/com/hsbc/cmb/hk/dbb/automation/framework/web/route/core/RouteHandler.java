package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import com.microsoft.playwright.Route;

/**
 * 路由处理器接口 — 解耦 Handler 与 RouteEngine 的 switch-case 分发。
 *
 * <p>每种 {@link RouteHandleType} 对应一个 {@link RouteHandler} 实现，
 * RouteEngine 通过 {@link java.util.EnumMap} 查找并调用，新增 Handler 无需修改引擎代码。
 *
 * <p>实现类必须保证：
 * <ul>
 *   <li>内部使用 try-catch 包裹 route.fulfill()/route.resume() 避免单请求异常导致整个路由崩溃</li>
 *   <li>方法签名线程安全（Playwright 事件线程调用）</li>
 * </ul>
 */
@FunctionalInterface
public interface RouteHandler {

    /**
     * 处理单个路由请求。
     *
     * @param route Playwright Route 对象
     * @param rule  匹配到的路由规则
     */
    void handle(Route route, RouteRule rule);
}
