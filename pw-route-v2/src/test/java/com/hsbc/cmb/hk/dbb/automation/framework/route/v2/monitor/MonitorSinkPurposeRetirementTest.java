package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.exec.RouteIoExecutor;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 规则"按目的生灭"的触发点契约（2026-09-28，T2+ 第二步）。
 *
 * <p><b>语义</b>：带响应侧期望的 MONITOR 规则，其"目的"就是"断言这次调用"——因此<b>首个响应定案
 * （无论断言成功还是失败）</b>即视为目的达成，回调通知 runtime 撤销该规则（armed 窗口从"整个用例"
 * 收敛为"首个响应"）。断言失败必须先入结算队列，再撤销（不丢失败证据）。</p>
 *
 * <p>本测试守护四条语义：
 * <ol>
 *   <li>首个响应定案 → 触发一次撤销回调；</li>
 *   <li>同一条规则只触发一次（幂等）；</li>
 *   <li>断言<b>失败</b>时同样撤销，且失败证据已入 {@code drainSettledFailures()}；</li>
 *   <li>非 MONITOR / 无响应侧期望的规则不触发（它们没有"定案"概念）。</li>
 * </ol>
 *
 * <p>目的驱动撤销是<b>不可关闭的默认行为</b>（无 {@code -Droute.v2.retire.on.settle=off} 之类的回退开关）。</p>
 */
public class MonitorSinkPurposeRetirementTest {

    private RouteIoExecutor io;

    @After
    public void tearDown() {
        if (io != null) {
            io.close();
            io = null;
        }
    }

    private RouteIoExecutor io() {
        io = new RouteIoExecutor("purpose-test", 1, 8);
        return io;
    }

    private static Request mockRequest(String url) {
        Request request = mock(Request.class);
        when(request.method()).thenReturn("GET");
        when(request.url()).thenReturn(url);
        when(request.headers()).thenReturn(Map.of("x-test", "1"));
        when(request.postData()).thenReturn(null);
        return request;
    }

    private static Response mockResponse(int status) {
        Response response = mock(Response.class);
        when(response.status()).thenReturn(status);
        when(response.headers()).thenReturn(Map.of("content-type", "application/json"));
        when(response.body()).thenReturn("{\"code\":0}".getBytes(StandardCharsets.UTF_8));
        return response;
    }

    /** 场景 1：首个响应定案 ⇒ 触发一次撤销回调（armed 窗口收敛为"首个响应"）。 */
    @Test
    public void firstSettledResponseTriggersRetirement() {
        List<String> retired = new CopyOnWriteArrayList<>();
        MonitorSink sink = new MonitorSink(io(), spec -> retired.add(spec.pattern()));
        ApiSpec spec = ApiSpec.builder("leftmenu/permissionLeftMenuConfig", RouteCapability.MONITOR)
                .expectStatus(200).build();

        sink.recordRequest(mockRequest("https://host/leftmenu/permissionLeftMenuConfig"), spec);
        sink.onResponseForSpec(spec, mockResponse(200));

        assertEquals("必须恰好触发一次目的达成回调", List.of("leftmenu/permissionLeftMenuConfig"), retired);
    }

    /** 场景 2：同一条规则只撤一次（幂等）。 */
    @Test
    public void retirementIsIdempotentPerRule() {
        List<String> retired = new CopyOnWriteArrayList<>();
        MonitorSink sink = new MonitorSink(io(), spec -> retired.add(spec.pattern()));
        ApiSpec spec = ApiSpec.builder("/api/users/**", RouteCapability.MONITOR).expectStatus(200).build();

        sink.recordRequest(mockRequest("https://host/api/users/1"), spec);
        sink.onResponseForSpec(spec, mockResponse(200));
        sink.recordRequest(mockRequest("https://host/api/users/2"), spec);
        sink.onResponseForSpec(spec, mockResponse(200));

        assertEquals("同一条规则不得重复触发撤销", 1, retired.size());
    }

    /** 场景 3：断言失败也撤销（"不管成功失败都 unroute"），但不丢失败证据。 */
    @Test
    public void assertionFailureStillRetiresButKeepsEvidence() {
        List<String> retired = new CopyOnWriteArrayList<>();
        MonitorSink sink = new MonitorSink(io(), spec -> retired.add(spec.pattern()));
        ApiSpec spec = ApiSpec.builder("/api/pay", RouteCapability.MONITOR).expectStatus(201).build();

        sink.recordRequest(mockRequest("https://host/api/pay"), spec);
        sink.onResponseForSpec(spec, mockResponse(200)); // 期望 201，实际 200 ⇒ 断言失败

        assertEquals("失败也必须撤销（规则不因失败而残留）", List.of("/api/pay"), retired);
        assertEquals("失败证据必须在撤销前已入结算队列（先结算、后撤销）",
                1, sink.drainSettledFailures().size());
        assertTrue("失败已定案后 drain 不应再产生新失败", sink.drainSettledFailures().isEmpty());
    }

    /** 场景 4：非 MONITOR 或无响应侧期望 ⇒ 无"定案"概念，不触发撤销。 */
    @Test
    public void rulesWithoutSettlementPurposeDoNotRetire() {
        List<String> retired = new CopyOnWriteArrayList<>();
        MonitorSink sink = new MonitorSink(io(), spec -> retired.add(spec.pattern()));

        ApiSpec mockSpec = ApiSpec.builder("/api/mock", RouteCapability.MOCK).mockStatus(200).build();
        sink.recordRequest(mockRequest("https://host/api/mock"), mockSpec);
        sink.onResponseForSpec(mockSpec, mockResponse(200));

        ApiSpec captureOnly = ApiSpec.builder("/api/track", RouteCapability.MONITOR).build();
        sink.recordRequest(mockRequest("https://host/api/track"), captureOnly);
        sink.onResponseForSpec(captureOnly, mockResponse(200));

        assertTrue("MOCK / 无期望 MONITOR 不得触发目的撤销（由收尾 flush 收敛）", retired.isEmpty());
    }

    /** 场景 6（2026-09-29 裁定）：{@code autoStopOnMatch} 规则"达到 minMatches 才撤"；未达标不提前撤销。 */
    @Test
    public void autoStopOnMatchRuleRetiresOnlyAfterMinMatchesReached() {
        List<String> retired = new CopyOnWriteArrayList<>();
        MonitorSink sink = new MonitorSink(io(), spec -> retired.add(spec.pattern()));
        ApiSpec spec = ApiSpec.builder("/api/poll", RouteCapability.MONITOR)
                .expectStatus(200).autoStopOnMatch(true).minMatches(2).build();

        sink.recordRequest(mockRequest("https://host/api/poll"), spec);
        sink.onResponseForSpec(spec, mockResponse(200));

        assertTrue("未达 minMatches 不得提前撤销（autoStopOnMatch 的目的 = 观察足够次数）", retired.isEmpty());

        sink.recordRequest(mockRequest("https://host/api/poll"), spec);
        sink.onResponseForSpec(spec, mockResponse(200));

        assertEquals("达到 minMatches 后必须撤销", List.of("/api/poll"), retired);
    }

}
