package com.hsbc.cmb.hk.dbb.automation.framework.route.v2;

/**
 * Route 模块 V2 的统一异常类型。
 *
 * <p>框架内部所有可控失败都包装为 {@link RouteException} 并以 fail-open 方式处理
 * （转换为 {@code route.fallback()} 继续请求），业务侧不应捕获本异常做分支判断——
 * 判断依据始终是「请求是否已被框架处理」（通过 {@link com.hsbc.cmb.hk.dbb.automation.framework.route.v2.claim.RouteClaim}
 * 状态观察），而非异常类型（Playwright 客户端抛出的 {@code PlaywrightException}
 * 不可类型化，见 Connection#getExistingObject）。
 */
public class RouteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RouteException(String message) {
        super(message);
    }

    public RouteException(String message, Throwable cause) {
        super(message, cause);
    }
}
