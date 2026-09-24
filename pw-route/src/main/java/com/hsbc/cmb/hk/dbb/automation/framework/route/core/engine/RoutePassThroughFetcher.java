package com.hsbc.cmb.hk.dbb.automation.framework.route.core.engine;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.RequestOptions;
import framework.webbridge.RouteRuntimeBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 不绑定 Frame 的真实响应透传取数：经 {@link RouteRuntimeBridge} 获取独立 {@link APIRequestContext}，
 * 从原请求拷贝必要 Header（含 Cookie / 鉴权），完成 fetch。页面在异步窗口内跳转时仍可用，
 * 解决 {@code Object doesn't exist: frame} 竞态（T-挂死根因）。
 *
 * <p>与 {@link Route#fetch} 的等价点：默认继承原请求的 method / 目标 URL / 超时；
 * 差异点：底层 context 不挂靠发起请求的 Page/Frame，故不受页面跳转影响。</p>
 */
public final class RoutePassThroughFetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoutePassThroughFetcher.class);

    // 逐跳（hop-by-hop）Header 不应转发（由 Playwright 协议层自行管理）
    private static final Set<String> SKIP_HEADERS = new HashSet<>(Arrays.asList(
            "host", "content-length", "connection", "transfer-encoding",
            "upgrade", "keep-alive", "proxy-authorization"));

    private RoutePassThroughFetcher() {
    }

    /**
     * 透传真实响应。
     *
     * @param route      当前路由（提供原请求的方法 / Header / body）
     * @param url        目标 URL（通常为 route.request().url()）
     * @param timeoutMs  fetch 超时（毫秒）
     * @return 真实响应
     * @throws PlaywrightException 当桥接缺失或独立 fetch 失败（调用方应回退 route.fetch() 或 resume）
     */
    public static APIResponse fetch(Route route, String url, double timeoutMs) throws PlaywrightException {
        RouteRuntimeBridge bridge = framework.route.core.engine.RouteRuntimeBridgeHolder.get();
        if (bridge == null) {
            LOGGER.debug("[RoutePassThrough] No bridge impl (SPI 未提供) → 回退 route.fetch()");
            return route.fetch();
        }
        Request req = route.request();
        String method = req.method();
        Map<String, String> headers = filterHeaders(req.headers());
        byte[] postData = req.postDataBuffer();
        // APIRequestContext 非 AutoCloseable（Playwright 版本约束），改用 try/finally 显式释放
        APIRequestContext ctx = bridge.createApiRequestContext();
        if (ctx == null) {
            LOGGER.debug("[RoutePassThrough] bridge 返回 null（Playwright 未初始化）→ 回退 route.fetch()");
            return route.fetch();
        }
        try {
            RequestOptions opts = RequestOptions.create()
                    .setMethod(method)
                    .setTimeout(timeoutMs);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                opts.setHeader(h.getKey(), h.getValue());
            }
            if (postData != null) {
                opts.setData(postData);
            }
            return ctx.fetch(url, opts);
        } finally {
            try {
                ctx.dispose();
            } catch (Throwable t) {
                LOGGER.debug("[RoutePassThrough] 释放独立 APIRequestContext 失败（忽略）: {}", t.toString());
            }
        }
    }

    private static Map<String, String> filterHeaders(Map<String, String> raw) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (SKIP_HEADERS.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }
}
