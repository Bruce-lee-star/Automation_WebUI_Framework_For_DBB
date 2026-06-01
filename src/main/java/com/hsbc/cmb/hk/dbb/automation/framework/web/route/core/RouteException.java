package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

/**
 * 路由框架核心异常体系 — 统一异常分类，便于针对性捕获和处理。
 *
 * <p>异常层次：
 * <pre>
 *   RouteException (runtime)
 *   ├── RouteConfigException   — 配置错误（URL pattern 无效、状态码越界等）
 *   ├── RouteRuntimeException  — 运行时异常（路由注册/注销失败等）
 *   └── ApiAssertionException  — API 断言失败（状态码/JSONPath 不匹配）
 * </pre>
 *
 * <p>所有异常均携带规则 URL、断言路径等上下文信息，便于定位问题。
 */
public class RouteException extends RuntimeException {

    private final String urlPattern;
    private final String contextId;

    public RouteException(String message) {
        super(message);
        this.urlPattern = null;
        this.contextId = null;
    }

    public RouteException(String message, Throwable cause) {
        super(message, cause);
        this.urlPattern = null;
        this.contextId = null;
    }

    public RouteException(String message, String urlPattern, String contextId) {
        super(buildDetail(message, urlPattern, contextId));
        this.urlPattern = urlPattern;
        this.contextId = contextId;
    }

    public RouteException(String message, String urlPattern, String contextId, Throwable cause) {
        super(buildDetail(message, urlPattern, contextId), cause);
        this.urlPattern = urlPattern;
        this.contextId = contextId;
    }

    /** 规则对应的 URL pattern */
    public String getUrlPattern() { return urlPattern; }

    /** 附加上下文 ID（如测试用例 ID） */
    public String getContextId() { return contextId; }

    private static String buildDetail(String message, String urlPattern, String contextId) {
        StringBuilder sb = new StringBuilder(message);
        if (urlPattern != null) sb.append(" [url=").append(urlPattern).append("]");
        if (contextId != null) sb.append(" [context=").append(contextId).append("]");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════
    // 子类
    // ═══════════════════════════════════════════════════════════

    /** 配置异常：URL pattern 非法、状态码越界等 */
    public static class RouteConfigException extends RouteException {
        public RouteConfigException(String message) { super(message); }
        public RouteConfigException(String message, String urlPattern) { super(message, urlPattern, null); }
        public RouteConfigException(String message, String urlPattern, Throwable cause) { super(message, urlPattern, null, cause); }
    }

    /** 运行时异常：路由注册/注销失败、Handler 执行异常等 */
    public static class RouteRuntimeException extends RouteException {
        public RouteRuntimeException(String message) { super(message); }
        public RouteRuntimeException(String message, Throwable cause) { super(message, cause); }
        public RouteRuntimeException(String message, String urlPattern, String contextId) { super(message, urlPattern, contextId); }
        public RouteRuntimeException(String message, String urlPattern, String contextId, Throwable cause) { super(message, urlPattern, contextId, cause); }
    }

    /** API 断言异常：状态码/JSONPath 断言失败 */
    public static class ApiAssertionException extends RouteException {

        private final String assertionType;   // "STATUS" / "JSONPATH"
        private final String expectedValue;
        private final String actualValue;

        public ApiAssertionException(String urlPattern, String assertionType, String expectedValue, String actualValue) {
            super(String.format("API assertion FAILED [%s]: expected='%s', actual='%s'",
                            assertionType, expectedValue, actualValue),
                    urlPattern, null);
            this.assertionType = assertionType;
            this.expectedValue = expectedValue;
            this.actualValue = actualValue;
        }

        public ApiAssertionException(String urlPattern, String assertionType, String expectedValue, String actualValue, Throwable cause) {
            super(String.format("API assertion FAILED [%s]: expected='%s', actual='%s'",
                            assertionType, expectedValue, actualValue),
                    urlPattern, null, cause);
            this.assertionType = assertionType;
            this.expectedValue = expectedValue;
            this.actualValue = actualValue;
        }

        public String getAssertionType() { return assertionType; }
        public String getExpectedValue() { return expectedValue; }
        public String getActualValue() { return actualValue; }
    }
}
