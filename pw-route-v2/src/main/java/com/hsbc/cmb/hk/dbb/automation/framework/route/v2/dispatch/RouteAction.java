package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dispatch;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.RequestOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 终结动作 —— 对 Playwright Route 的全部终结调用的统一出口。
 *
 * <p>为什么统一出口（对应 playwright-java-1.62.0 源码事实）：
 * <ul>
 *   <li>{@code startHandling()} 单次性：重复终结抛 "Route is already handled!"（RouteImpl:246-251），
 *       统一出口把所有终结路径收敛到一个 try/catch 边界，失败一律记录并转为 fail-open；</li>
 *   <li>{@code resume/fulfill/abort/fallback} 均为 {@code sendMessageAsync}（Connection:139），
 *       不阻塞事件线程——因此这些动作允许在事件线程直接调用，而 {@code fetch()} 是同步 HTTP，
 *       只允许在 IO 线程经 BoundedOps 调用（见 {@link #fetchAndFulfill}）。</li>
 * </ul>
 */
public final class RouteAction {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteAction.class);

    /** fetch 同步请求的超时（毫秒），与 Playwright APIRequestContext 默认一致。 */
    public static final long DEFAULT_FETCH_TIMEOUT_MS = 30_000L;

    private RouteAction() {
    }

    /**
     * 放行请求（可选 overrides：MODIFY_REQUEST 语义）。
     *
     * @return true=resume 命令成功发出；false=route 已被处理或对象已失效（无悬挂风险）
     */
    public static boolean resume(Route route, ApiSpec spec) {
        return resume(route, spec, null);
    }

    /**
     * 纯放行请求（无 MODIFY/MOCK，仅 DELAY/MONITOR 观测）：等价于 {@code route.resume()} 无参。
     *
     * @return true=resume 命令成功发出；false=route 已被处理或对象已失效（无悬挂风险）
     */
    public static boolean resume(Route route) {
        try {
            route.resume();
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("[RouteV2] resume failed for url='{}': {}", route.request().url(), e.toString());
            return false;
        }
    }

    /**
     * 放行请求（MODIFY_REQUEST 语义，可同时携带 method、header 与 body 修改）。
     *
     * <p>对照 playwright-java-1.62.0：{@code Route.ResumeOptions} 原生支持
     * {@code setMethod/setHeaders/setPostData}（Route.java:38-83），{@code RouteImpl.resumeImpl}
     * 序列化 url/method/headers/postData 四项（RouteImpl.java:118-137）——因此
     * {@code modifyMethod} 无需 fetch+fulfill 重放，直接 resume(options) 即可：
     * 请求真实发出、单次请求零额外 HTTP 往返、无副作用重放（POST 不会二次提交）。
     *
     * <p>headers 语义：合并（保留未指定 header + 删除 remove 集 + 覆盖 set 集）后整体传入。
     * 与官方「resume(headers) 整体替换」行为等效（官方以传入集为准；此处传入集=原集+修改）。
     *
     * @param postData 修改后的请求体；null=不改 body
     */
    public static boolean resume(Route route, ApiSpec spec, String postData) {
        try {
            if (spec == null && postData == null) {
                route.resume();
            } else {
                Route.ResumeOptions options = new Route.ResumeOptions();
                if (spec != null && spec.modifyMethod() != null) {
                    options.setMethod(spec.modifyMethod());
                }
                if (spec != null && (!spec.requestHeadersToSet().isEmpty() || !spec.requestHeadersToRemove().isEmpty())) {
                    Map<String, String> headers = new LinkedHashMap<>(route.request().headers());
                    headers.keySet().removeAll(spec.requestHeadersToRemove());
                    headers.putAll(spec.requestHeadersToSet());
                    options.setHeaders(headers);
                }
                if (postData != null) {
                    options.setPostData(postData);
                }
                route.resume(options);
            }
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("[RouteV2] resume failed for url='{}': {}", route.request().url(), e.toString());
            return false;
        }
    }

    /** 静态伪造响应（MOCK 静态）。 */
    public static boolean fulfill(Route route, ApiSpec spec) {
        try {
            Route.FulfillOptions options = new Route.FulfillOptions();
            if (spec.mockStatus() != null) {
                options.setStatus(spec.mockStatus());
            }
            if (spec.mockBody() != null) {
                options.setBody(spec.mockBody());
            }
            if (spec.mockContentType() != null) {
                options.setContentType(spec.mockContentType());
            }
            if (!spec.mockHeaders().isEmpty()) {
                options.setHeaders(spec.mockHeaders());
            }
            route.fulfill(options);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("[RouteV2] fulfill failed for url='{}': {}", route.request().url(), e.toString());
            return false;
        }
    }

    /** 以真实响应终结（MOCK intercept，IO 线程内调用）。 */
    public static boolean fulfillWithResponse(Route route, APIResponse response) {
        try {
            route.fulfill(new Route.FulfillOptions().setResponse(response));
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("[RouteV2] fulfill(response) failed for url='{}': {}", route.request().url(), e.toString());
            return false;
        }
    }

    /** 以显式 status/headers/body 终结（MOCK 字段替换后，IO 线程内调用）。 */
    public static boolean fulfillWithBody(Route route, Integer status, Map<String, String> headers, String body) {
        try {
            Route.FulfillOptions options = new Route.FulfillOptions();
            if (status != null) {
                options.setStatus(status);
            }
            if (headers != null && !headers.isEmpty()) {
                options.setHeaders(headers);
            }
            if (body != null) {
                options.setBody(body);
            }
            route.fulfill(options);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("[RouteV2] fulfill(body) failed for url='{}': {}", route.request().url(), e.toString());
            return false;
        }
    }

    /** 终止请求（仅 MOCK 在途任务取消场景使用；其余场景一律 fallback）。 */
    public static boolean abort(Route route, String errorCode) {
        try {
            route.abort(errorCode);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("[RouteV2] abort failed for url='{}': {}", route.request().url(), e.toString());
            return false;
        }
    }

    /**
     * 放弃处理、放行到真实后端（fail-open 出口）。
     *
     * <p>对应源码语义：pending 状态下 {@code fallback()} 自动转 continue（RouteImpl:68-77），
     * 是「框架不处理」时最安全的出口。
     */
    public static boolean fallback(Route route) {
        try {
            route.fallback();
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("[RouteV2] fallback failed for url='{}': {}", route.request().url(), e.toString());
            return false;
        }
    }

    /**
     * IO 线程专用：真实响应拦截（MOCK intercept）的取响应实现。
     *
     * <p>本方法必须经 {@link com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec.BoundedOps}
     * 调用——它是同步 HTTP 且可能慢（实测数百 ms），事件线程严禁触碰。</p>
     *
     * <p><b>方案 A（结构化消除竞态）</b>：不使用 {@code route.fetch()}，统一用<b>事件线程拦截那一刻</b>
     * 取好的 {@link RequestSnapshot}（url/method/headers/body 值拷贝）+ {@code page.request()} 从零重发。
     * 取响应因此<b>不依赖</b>任何可能被浏览器驱动回收的 {@code frame}/{@code Request} 句柄，
     * 从结构上消除"导航期句柄回收"竞态——<b>无需</b>任何异常捕获或错误串匹配。任何失败直接上抛，
     * 由 {@link com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec.BoundedOps} 统一 fail-open。</p>
     *
     * @param requestContext runtime 所属 Context 的 {@link APIRequestContext}（调用方保证非空）
     * @param snapshot       拦截时的请求快照（调用方保证非空）
     */
    public static APIResponse fetch(APIRequestContext requestContext, RequestSnapshot snapshot) {
        RequestOptions options = RequestOptions.create()
                .setMethod(snapshot.method() == null || snapshot.method().isEmpty() ? "GET" : snapshot.method())
                .setTimeout(DEFAULT_FETCH_TIMEOUT_MS);
        Set<String> skip = Set.of("host", "content-length", "connection", "accept-encoding", "cookie");
        for (Map.Entry<String, String> entry : snapshot.headers().entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isEmpty() || name.startsWith(":")
                    || skip.contains(name.toLowerCase(Locale.ROOT))) {
                continue; // 浏览器托管头交由客户端重算/注入，避免重复或冲突
            }
            options.setHeader(name, entry.getValue());
        }
        String body = snapshot.postData();
        if (body != null && !body.isEmpty()) {
            options.setData(body);
        }
        return requestContext.fetch(snapshot.url(), options);
    }

    /**
     * 被拦截请求的快照（值类型）：在事件线程拦截那一刻复制 url/method/headers/body，
     * 从而彻底脱离可能随后被驱动回收的 {@link Request} 句柄。
     */
    public record RequestSnapshot(String url, String method, Map<String, String> headers, String postData) {

        /** 从 {@link Request} 复制快照；任何异常（含 mock/句柄不可用）返回 {@code null}。 */
        public static RequestSnapshot of(Request request) {
            if (request == null) {
                return null;
            }
            try {
                Map<String, String> headers = request.headers();
                return new RequestSnapshot(request.url(), request.method(),
                        headers == null ? Map.of() : new LinkedHashMap<>(headers), request.postData());
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
