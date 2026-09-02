package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 固化 Phase 5 抽离出的 {@link ApiCallAwaiter} 投递式等待器契约（与拆分前语义严格一致）。
 *
 * <p>覆盖核心不变量：
 * <ul>
 *   <li>注册返回 future；入库时谓词命中即<b>精确完成</b>该 future（点对点，非广播+重扫）；</li>
 *   <li>谓词未命中不完成；</li>
 *   <li>注销后不再被投递；</li>
 *   <li>{@code reset()} 令所有在册 future 以 null 完成（调用方返回 null，避免空等至超时）；</li>
 *   <li>命中后从注册表移除，重复投递不重复完成。</li>
 * </ul>
 * 与 {@link ApiCallAwaiter} 同包，可访问其 package-private 构造器。<b>不依赖 Playwright</b>。
 */
public class ApiCallAwaiterTest {

    private static CapturedApiCall dummy() {
        // 7 参 public 构造器：endpoint, method, requestHeaders, statusCode, responseHeaders, responseBody, timestamp
        return new CapturedApiCall("/api/x", "GET", null, 200, null, "{}", 123L);
    }

    @Test
    public void register_returnsFuture_and_deliverCompletesMatchingWaiter() throws Exception {
        ApiCallAwaiter a = new ApiCallAwaiter();
        CompletableFuture<CapturedApiCall> f = a.register(c -> true);
        assertFalse("注册后 future 不应立即完成", f.isDone());

        CapturedApiCall call = dummy();
        a.deliver(call);

        assertTrue("谓词命中应完成 future", f.isDone());
        assertSame("完成值应为入库的调用快照", call, f.get());
    }

    @Test
    public void deliver_nonMatching_doesNotComplete() {
        ApiCallAwaiter a = new ApiCallAwaiter();
        CompletableFuture<CapturedApiCall> f = a.register(c -> false);
        a.deliver(dummy());
        assertFalse("谓词未命中不应完成 future", f.isDone());
    }

    @Test
    public void unregister_removesWaiter_beforeDeliver() {
        ApiCallAwaiter a = new ApiCallAwaiter();
        CompletableFuture<CapturedApiCall> f = a.register(c -> true);
        a.unregister(f);
        a.deliver(dummy());
        assertFalse("注销后不应再被投递", f.isDone());
    }

    @Test
    public void reset_completesAllWithNull() throws Exception {
        ApiCallAwaiter a = new ApiCallAwaiter();
        CompletableFuture<CapturedApiCall> f1 = a.register(c -> true);
        CompletableFuture<CapturedApiCall> f2 = a.register(c -> false);
        a.reset();
        assertTrue(f1.isDone());
        assertTrue(f2.isDone());
        assertNull("reset 应以 null 完成（调用方返回 null）", f1.get());
        assertNull(f2.get());
    }

    @Test
    public void deliver_twice_onlyFirstCompletes() throws Exception {
        ApiCallAwaiter a = new ApiCallAwaiter();
        CompletableFuture<CapturedApiCall> f = a.register(c -> c.statusCode() == 200);
        CapturedApiCall call = dummy();
        a.deliver(call);
        assertTrue(f.isDone());
        // 命中后已从注册表移除，重复投递不重复完成（值仍为首次入库的快照）
        a.deliver(call);
        assertSame(call, f.get());
    }
}
