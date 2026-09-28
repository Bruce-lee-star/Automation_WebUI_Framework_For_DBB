package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dispatch;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

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
     * IO 线程专用：真实响应拦截（MOCK intercept）。
     *
     * <p>本方法必须经 {@link com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec.BoundedOps}
     * 调用——{@code route.fetch()} 是同步 HTTP 且可能慢，事件线程严禁触碰。
     *
     * <p>A4 根治（Route fetch failed）：导航期原始响应句柄可能被浏览器驱动回收，
     * 此时 {@code route.fetch()} 抛 {@code "Object doesn't exist: response@..."}。
     * 直接 fail-open 会放行真实响应、丢失 mock（如 profile/list 的 updateContctOverlayFlag 替换），
     * 进而触发遮罩。故在此捕获该异常，改用 {@code page.request} 重放被拦截的请求，
     * 拿到全新 {@link APIResponse} 后照常 fulfill——mock 不再被静默丢弃。重放仍失败时恢复原异常，
     * 交由 {@link com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec.BoundedOps} 安全 fail-open。</p>
     */
    public static APIResponse fetch(Route route) {
        try {
            return route.fetch(new Route.FetchOptions().setTimeout(DEFAULT_FETCH_TIMEOUT_MS));
        } catch (PlaywrightException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Object doesn't exist")) {
                // 响应句柄被回收：用 page.request 重放拦截请求，获取全新响应（绕过已失效句柄）
                try {
                    LOGGER.warn("[RouteV2] route.fetch response handle reclaimed ({}), "
                            + "replaying via page.request", msg);
                    Request req = route.request();
                    Page page = req.frame().page();
                    return page.request().fetch(req);
                } catch (PlaywrightException re) {
                    LOGGER.warn("[RouteV2] replay via page.request failed: {}", re.toString());
                    throw e; // 仍失败则交上层 BoundedOps fail-open
                }
            }
            throw e;
        }
    }
}
