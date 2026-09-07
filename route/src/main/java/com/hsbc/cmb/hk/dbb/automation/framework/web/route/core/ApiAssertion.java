package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * API 断言器 — 流式 API 对采集结果进行断言。
 *
 * <p>支持 status、jsonPath、bodyContains、isMock 断言。
 * 断言失败时自动保留完整快照并记录到测试报告。
 * 原 {@code ApiCaptureContext} 的内部静态类，Phase 5 抽离为独立类型（零行为变更）。
 */
public class ApiAssertion {

    private final String urlPattern;
    private final Pattern regex;
    /**  P1: 断言首次未命中时，等待采集管道在途请求闭合的超时上限 */
    private static final long CAPTURE_AWAIT_TIMEOUT_MS = 1_500L;

    ApiAssertion(String urlPattern) {
        this.urlPattern = urlPattern;
        // 复用 route 模块统一的 Ant-glob→正则实现（RoutePatternCache，带编译缓存；
        // 原 ApiCaptureContext 静态域 Phase 5 抽离，ResponseStore 已同样委托，消除重复实现）
        this.regex = RoutePatternCache.antGlobToRegex(urlPattern);
    }

    /**
     * 断言 HTTP 状态码。
     *
     * @param expectedStatus 期望的状态码
     * @return 当前断言器（链式调用）
     * @throws AssertionError 如果断言失败
     */
    public ApiAssertion statusIs(int expectedStatus) {
        CapturedApiCall call = findCall();
        if (call == null) {
            String msg = "No API call matched pattern '" + urlPattern + "'";
            recordFailure(msg);
            throw new AssertionError(msg);
        }
        if (call.statusCode() != expectedStatus) {
            String msg = String.format("Expected status %d for '%s', but got %d",
                    expectedStatus, urlPattern, call.statusCode());
            ApiCaptureContext.getCurrent().recordAssertionFailure(
                    call.requestUrl(), "STATUS",
                    String.valueOf(expectedStatus), String.valueOf(call.statusCode()),
                    "pattern=" + urlPattern);
            throw new AssertionError(msg);
        }
        return this;
    }

    /**
     * 断言 JSONPath 表达式的值。
     *
     * @param jsonPath JSONPath 表达式
     * @param expectedValue 期望值
     * @return 当前断言器（链式调用）
     * @throws AssertionError 如果断言失败
     */
    public ApiAssertion jsonPath(String jsonPath, Object expectedValue) {
        CapturedApiCall call = findCall();
        if (call == null) {
            String msg = "No API call matched pattern '" + urlPattern + "'";
            recordFailure(msg);
            throw new AssertionError(msg);
        }
        Object actual = call.json(jsonPath);
        if (actual == null || !actual.equals(expectedValue)) {
            String msg = String.format("JSONPath '%s' for '%s': expected '%s', but got '%s'",
                    jsonPath, urlPattern, expectedValue, actual);
            ApiCaptureContext.getCurrent().recordAssertionFailure(
                    call.requestUrl(), "JSONPATH",
                    String.valueOf(expectedValue), String.valueOf(actual),
                    "jsonPath=" + jsonPath + ", pattern=" + urlPattern);
            throw new AssertionError(msg);
        }
        return this;
    }

    /**
     * 断言响应体包含指定字符串。
     *
     * @param content 期望包含的字符串
     * @return 当前断言器（链式调用）
     * @throws AssertionError 如果断言失败
     */
    public ApiAssertion bodyContains(String content) {
        CapturedApiCall call = findCall();
        if (call == null) {
            String msg = "No API call matched pattern '" + urlPattern + "'";
            recordFailure(msg);
            throw new AssertionError(msg);
        }
        String body = call.responseBody();
        if (body == null || !body.contains(content)) {
            String msg = String.format("Response body for '%s' does not contain '%s'",
                    urlPattern, content);
            ApiCaptureContext.getCurrent().recordAssertionFailure(
                    call.requestUrl(), "BODY_CONTAINS",
                    content, body != null ? body.substring(0, Math.min(100, body.length())) : "null",
                    "pattern=" + urlPattern);
            throw new AssertionError(msg);
        }
        return this;
    }

    /**
     * 断言这是一个 Mock 响应。
     */
    public ApiAssertion isMock() {
        CapturedApiCall call = findCall();
        if (call == null) {
            String msg = "No API call matched pattern '" + urlPattern + "'";
            recordFailure(msg);
            throw new AssertionError(msg);
        }
        if (!call.fromMock()) {
            String msg = String.format("Expected '%s' to be a mock response, but it was not",
                    urlPattern);
            ApiCaptureContext.getCurrent().recordAssertionFailure(
                    call.requestUrl(), "IS_MOCK",
                    "true", "false", "pattern=" + urlPattern);
            throw new AssertionError(msg);
        }
        return this;
    }

    /**
     * 获取匹配的 API 调用（不断言）。
     */
    public CapturedApiCall get() {
        return findCall();
    }

    // ── 内部 ──

    private CapturedApiCall findCall() {
        ApiCaptureContext ctx = ApiCaptureContext.getCurrent();

        // 1/1b. 快路径：endpoint key + 完整 URL 索引双通道精确匹配
        CapturedApiCall call = fastExactMatch(ctx);
        if (call != null) return call;

        //  P1: 竞态兜底 — 等待采集管道在途请求闭合后再重试。
        awaitCapturePipeline(ctx);

        //  P1.2: 投递式等待 — awaitCompletion 可能在 captureInFlight==0
        //   （事件尚未到达 merger、REQUEST 还没计数）时立即返回；若此时只扫一次就放弃，
        //   调用会在随后几毫秒入库但断言已失败。先补扫一次已入库调用；仍未命中则
        //   注册一次性谓词，storeApiCall 入库时直接评估并精确完成 future。
        call = fastExactMatch(ctx);
        if (call != null) return call;

        // 2. 通配符匹配：仅遍历当前步骤窗口内的调用
        CapturedApiCall best = wildcardScan(ctx);
        if (best != null) return best;

        long stepStart = ctx.getStepStartTimestamp();
        CompletableFuture<CapturedApiCall> waiter =
                ctx.registerApiCallWaiter(c -> matchesPattern(c, stepStart));
        // 关闭"注册前入库 → 投递丢失"竞态：调用可能在注册与评估之间已入库，
        // 注册后立即补扫一次（单次检查，非轮询），命中即返回。
        CapturedApiCall late = fastExactMatch(ctx);
        if (late == null) late = wildcardScan(ctx);
        if (late != null) {
            ctx.unregisterApiCallWaiter(waiter);
            return late;
        }
        try {
            return waiter.get(CAPTURE_AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (TimeoutException | ExecutionException e) {
            return null;
        } finally {
            ctx.unregisterApiCallWaiter(waiter);
        }
    }

    /**
     *  P1.2: 投递式匹配谓词 — 与 fastExactMatch/wildcardScan 同源：
     * endpoint key（path-only）与完整 URL 双通道 + 步骤窗口。
     */
    private boolean matchesPattern(CapturedApiCall c, long stepStart) {
        if (c == null) return false;
        if (stepStart != 0L && c.timestamp() < stepStart) return false;
        String endpoint = c.endpoint();
        String url = c.requestUrl();
        if (urlPattern.equals(endpoint) || urlPattern.equals(url)) return true;
        if (endpoint != null && regex.matcher(endpoint).matches()) return true;
        return url != null && regex.matcher(url).matches();
    }

    /**  快路径精确匹配：endpoint key（path-only）+ 完整 URL 索引双通道（限定步骤窗口）。 */
    private CapturedApiCall fastExactMatch(ApiCaptureContext ctx) {
        // 1. 精确匹配（限定在当前步骤窗口内，R4）— 按 endpoint key（path-only）检索
        CapturedApiCall call = ctx.getLastApiCallSinceStepStart(urlPattern);
        if (call != null) return call;

        // 1b.  P2: 完整 URL 精确索引（O(1)，apiCallsByUrl）——pattern 传完整 URL 时
        //     endpoint key 无法命中（存储键为 path-only），这里补一次 URL 索引查询。
        if (!containsGlobWildcard(urlPattern)) {
            CapturedApiCall byUrl = lastSinceStepStart(ctx.getCallsByUrl(urlPattern), ctx);
            if (byUrl != null) return byUrl;
        }
        return null;
    }

    /**  步骤 2：通配符全量扫描（遍历每个 endpoint 的全部调用而非仅最后一条）。 */
    private CapturedApiCall wildcardScan(ApiCaptureContext ctx) {
        Map<String, List<CapturedApiCall>> all = ctx.getAllApiCalls();
        CapturedApiCall best = null;
        long bestTimestamp = 0;
        long stepStart = ctx.getStepStartTimestamp();
        for (Map.Entry<String, List<CapturedApiCall>> e : all.entrySet()) {
            List<CapturedApiCall> calls = e.getValue();
            if (calls == null || calls.isEmpty()) continue;
            for (CapturedApiCall c : calls) {
                if (c == null) continue;
                // 仅考虑本步骤窗口内的调用
                if (stepStart != 0L && c.timestamp() < stepStart) continue;
                if (!regex.matcher(e.getKey()).matches()
                        && !regex.matcher(c.requestUrl()).matches()) {
                    continue;
                }
                if (c.timestamp() > bestTimestamp) {
                    best = c;
                    bestTimestamp = c.timestamp();
                }
            }
        }
        return best;
    }

    /**  P2: 取列表内步骤窗口中的最近一条调用（列表按时间追加，倒序查找）。 */
    private CapturedApiCall lastSinceStepStart(List<CapturedApiCall> calls, ApiCaptureContext ctx) {
        if (calls == null || calls.isEmpty()) return null;
        long stepStart = ctx == null ? 0L : ctx.getStepStartTimestamp();
        for (int i = calls.size() - 1; i >= 0; i--) {
            CapturedApiCall c = calls.get(i);
            if (c == null) continue;
            if (stepStart != 0L && c.timestamp() < stepStart) continue;
            return c;
        }
        return null;
    }

    /**  P1: 有限等待采集管道在途请求闭合，不抛出中断异常。 */
    private void awaitCapturePipeline(ApiCaptureContext ctx) {
        if (ctx == null) return;
        try {
            ctx.awaitCompletion(CAPTURE_AWAIT_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean containsGlobWildcard(String s) {
        return s != null && s.indexOf('*') >= 0;
    }

    private void recordFailure(String msg) {
        ApiCaptureContext.getCurrent().recordAssertionFailure(
                urlPattern, "NO_MATCH", "ANY", "NONE", msg);
    }

}
