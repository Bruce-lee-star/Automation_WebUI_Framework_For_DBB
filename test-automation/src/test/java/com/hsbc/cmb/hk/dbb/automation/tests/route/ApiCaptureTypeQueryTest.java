package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证「按能力类型查询」能力：{@code ApiCaptureContext.getAllByType(...)} 等。
 *
 * <p>背景：全局旁路采集移除后，四种 Handler 各自落库，且一次请求在复合场景下
 * （如 MODIFY + DELAY + MONITOR）会产生多条快照。四种类型是<b>并列维度</b>而非互斥枚举，
 * 本测试固化该契约。
 */
public class ApiCaptureTypeQueryTest {

    private static final String ENDPOINT = "/api/order";

    private ApiCaptureContext ctx;

    @Before
    public void setUp() {
        ctx = ApiCaptureContext.getCurrent();
        ctx.reset();
    }

    private static CapturedApiCall call(String endpoint, RouteHandleType type, long ts) {
        return new CapturedApiCall(
                endpoint, "POST", Map.of("X-Trace", "t1"),
                200, Map.of("Content-Type", "application/json"),
                "{\"code\":0}", ts,
                "http://host" + endpoint, null, type);
    }

    /**
     *  DELAY 是「维度标记」而非完整调用快照（无响应体），
     * 框架把它存放在<b>独立索引</b>中，与主快照存储隔离。
     */
    private static CapturedApiCall delayMarker(String endpoint, long ts) {
        return new CapturedApiCall(
                endpoint, "GET", null,
                0, null, null, ts,
                "http://host" + endpoint, null, RouteHandleType.DELAY);
    }

    @Test
    public void mockCallUsesMockTypeAndFromMockFlag() {
        CapturedApiCall mockCall = new CapturedApiCall(
                ENDPOINT, "GET", null, 200, null, "{\"mocked\":true}",
                1L, "http://host/api/order", null, RouteHandleType.MOCK);

        assertEquals(RouteHandleType.MOCK, mockCall.handleType());
        assertTrue("MOCK 快照必须标记 fromMock，否则 isMock() 断言永远失败", mockCall.fromMock());
        assertEquals("MOCK", mockCall.captureSource());
    }

    @Test
    public void getAllByType_filtersByHandleType() {
        ctx.storeApiCall(call(ENDPOINT, RouteHandleType.MONITOR, 1L));
        ctx.storeApiCall(call(ENDPOINT, RouteHandleType.MODIFY, 2L));
        ctx.storeDelayMarker(delayMarker(ENDPOINT, 3L));
        ctx.storeApiCall(call(ENDPOINT, RouteHandleType.MOCK, 4L));

        assertEquals(1, ctx.getAllByType(RouteHandleType.MONITOR).size());
        assertEquals(1, ctx.getAllByType(RouteHandleType.MODIFY).size());
        assertEquals(1, ctx.getAllByType(RouteHandleType.DELAY).size());
        assertEquals(1, ctx.getAllByType(RouteHandleType.MOCK).size());
    }

    @Test
    public void compositeScenario_producesOneRecordPerCapability() {
        // 同一 endpoint 同时被「延迟 + 修改 + 监控」处理：应各自落一条，互不覆盖
        ctx.storeDelayMarker(delayMarker(ENDPOINT, 1L));
        ctx.storeApiCall(call(ENDPOINT, RouteHandleType.MODIFY, 2L));
        ctx.storeApiCall(call(ENDPOINT, RouteHandleType.MONITOR, 3L));

        // 主快照存储只含带响应的完整调用（MODIFY + MONITOR）
        assertEquals("主快照存储应只含 2 条完整调用", 2, ctx.getApiCalls(ENDPOINT).size());
        assertEquals("DELAY 标记应存在", 1, ctx.getApiCallsByType(ENDPOINT, RouteHandleType.DELAY).size());
        assertEquals("MODIFY 记录应存在", 1, ctx.getApiCallsByType(ENDPOINT, RouteHandleType.MODIFY).size());
        assertEquals("MONITOR 记录应存在", 1, ctx.getApiCallsByType(ENDPOINT, RouteHandleType.MONITOR).size());
        assertEquals("未启用 MOCK，不应有 MOCK 记录",
                0, ctx.getApiCallsByType(ENDPOINT, RouteHandleType.MOCK).size());
    }

    /**
     *  核心契约：DELAY 标记<b>不得</b>污染按 endpoint 的通用查询。
     *
     * <p>DELAY 无响应体且在请求放行前落库，若混入主快照存储，
     * {@code getLastApiCall} / {@code waitForApi} 会先命中这条空记录，
     * 迫使每个调用方都写一遍「跳过 DELAY」的过滤逻辑（语义泄漏）。
     */
    @Test
    public void delayMarkerDoesNotPolluteGeneralQueries() {
        ctx.storeDelayMarker(delayMarker(ENDPOINT, 1L));
        ctx.storeApiCall(call(ENDPOINT, RouteHandleType.MONITOR, 2L));

        CapturedApiCall last = ctx.getLastApiCall(ENDPOINT);
        assertNotNull(last);
        assertEquals("通用查询应返回带响应的完整调用，而非 DELAY 占位",
                RouteHandleType.MONITOR, last.handleType());
        assertNotNull("通用查询拿到的记录必须有响应体", last.responseBody());

        // waitForApi 同样不应命中 DELAY 占位
        CapturedApiCall waited = ctx.waitForApi(c -> c.endpoint().equals(ENDPOINT), 500);
        assertNotNull(waited);
        assertEquals(RouteHandleType.MONITOR, waited.handleType());

        // 但按类型查询 DELAY 仍应可查
        assertEquals(1, ctx.getAllByType(RouteHandleType.DELAY).size());
    }

    @Test
    public void delayMarkerOnlyScenario_generalQueryFindsNothing() {
        // 只有 DELAY 标记、尚无完整调用时，通用查询应返回空（而非返回空 body 的占位）
        ctx.storeDelayMarker(delayMarker(ENDPOINT, 1L));

        assertNull("仅有 DELAY 标记时 getLastApiCall 应返回 null", ctx.getLastApiCall(ENDPOINT));
        assertTrue("仅有 DELAY 标记时 getApiCalls 应为空", ctx.getApiCalls(ENDPOINT).isEmpty());
        assertFalse("但 getAllByType(DELAY) 应能查到标记",
                ctx.getAllByType(RouteHandleType.DELAY).isEmpty());
    }

    @Test
    public void getLastApiCallByType_returnsNewestOfThatType() {
        ctx.storeApiCall(call(ENDPOINT, RouteHandleType.MONITOR, 1L));
        ctx.storeApiCall(call(ENDPOINT, RouteHandleType.MONITOR, 5L));
        ctx.storeApiCall(call(ENDPOINT, RouteHandleType.MODIFY, 9L));

        CapturedApiCall lastMonitor = ctx.getLastApiCallByType(ENDPOINT, RouteHandleType.MONITOR);
        assertNotNull(lastMonitor);
        assertEquals(5L, lastMonitor.timestamp());

        CapturedApiCall lastModify = ctx.getLastApiCallByType(ENDPOINT, RouteHandleType.MODIFY);
        assertNotNull(lastModify);
        assertEquals(9L, lastModify.timestamp());
    }

    @Test
    public void getAllGroupedByType_alwaysHasFourKeys() {
        ctx.storeApiCall(call(ENDPOINT, RouteHandleType.MONITOR, 1L));

        Map<RouteHandleType, List<CapturedApiCall>> grouped = ctx.getAllGroupedByType();
        assertEquals(4, grouped.size());
        for (RouteHandleType t : RouteHandleType.values()) {
            assertNotNull("分组必须包含 " + t + " 键", grouped.get(t));
        }
        assertEquals(1, grouped.get(RouteHandleType.MONITOR).size());
        assertTrue("未落库的其它类型应为空列表", grouped.get(RouteHandleType.MOCK).isEmpty());
    }

    @Test
    public void emptyAndNullSafeContracts() {
        assertTrue("无记录时返回空列表而非 null", ctx.getAllByType(RouteHandleType.MOCK).isEmpty());
        assertTrue("type 为 null 时返回空列表", ctx.getAllByType(null).isEmpty());
        assertTrue("type 为 null 时返回空列表", ctx.getApiCallsByType(ENDPOINT, null).isEmpty());
        assertNull("无记录时返回 null", ctx.getLastApiCallByType(ENDPOINT, RouteHandleType.MODIFY));
    }

    @Test
    public void resultsAreSortedByTimestamp() {
        ctx.storeApiCall(call("/api/a", RouteHandleType.MONITOR, 30L));
        ctx.storeApiCall(call("/api/b", RouteHandleType.MONITOR, 10L));
        ctx.storeApiCall(call("/api/c", RouteHandleType.MONITOR, 20L));

        List<CapturedApiCall> all = ctx.getAllByType(RouteHandleType.MONITOR);
        assertEquals(3, all.size());
        assertEquals(10L, all.get(0).timestamp());
        assertEquals(20L, all.get(1).timestamp());
        assertEquals(30L, all.get(2).timestamp());
    }
}
