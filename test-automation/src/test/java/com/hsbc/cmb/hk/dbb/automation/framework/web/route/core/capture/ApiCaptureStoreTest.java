package com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureStore;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;

/**
 * ApiCaptureStore 行为护盾：delay/mock/modify 全量采集 + onResponse 兜底去重 + 即时清理。
 */
public class ApiCaptureStoreTest {

    private static CapturedApiCall call(String endpoint, String url, RouteHandleType type) {
        return new CapturedApiCall.Builder()
                .endpoint(endpoint)
                .method("GET")
                .requestUrl(url)
                .statusCode(200)
                .responseBody("{\"ok\":true}")
                .timestamp(System.currentTimeMillis())
                .fromMock(type == RouteHandleType.MOCK)
                .captureSource("TEST")
                .handleType(type)
                .build();
    }

    @Test
    public void delayMockModifyAreAllCaptured() {
        ApiCaptureStore store = new ApiCaptureStore();
        store.record(call("/api/a", "https://x/api/a", RouteHandleType.MOCK));
        store.record(call("/api/b", "https://x/api/b", RouteHandleType.MODIFY));
        store.record(call("/api/c", "https://x/api/c", RouteHandleType.DELAY));

        assertEquals(1, store.getAllByType(RouteHandleType.MOCK).size());
        assertEquals(1, store.getAllByType(RouteHandleType.MODIFY).size());
        assertEquals(1, store.getAllByType(RouteHandleType.DELAY).size());

        Map<RouteHandleType, List<CapturedApiCall>> grouped = store.getAllGroupedByType();
        assertEquals(1, grouped.get(RouteHandleType.MOCK).size());
        assertEquals(1, grouped.get(RouteHandleType.MODIFY).size());
        assertEquals(1, grouped.get(RouteHandleType.DELAY).size());
    }

    @Test
    public void onResponsePassthroughDoesNotOverrideRicherHandlerRecord() {
        ApiCaptureStore store = new ApiCaptureStore();
        // Handler 记录一条 MOCK（携带 mock 信息）
        store.record(call("/api/login", "https://x/api/login", RouteHandleType.MOCK));
        // onResponse 兜底针对同一 URL 记录 MONITOR —— 应被去重跳过
        store.record(call("/api/login", "https://x/api/login", RouteHandleType.MONITOR));

        List<CapturedApiCall> calls = store.getApiCalls("/api/login");
        assertEquals( 1,  calls.size(), "passthrough must not duplicate the handler record");
        assertEquals(RouteHandleType.MOCK, calls.get(0).handleType());
        assertEquals(RouteHandleType.MOCK, store.getLastApiCall("/api/login").handleType());
    }

    @Test
    public void unregisteredTrafficCapturedViaPassthrough() {
        ApiCaptureStore store = new ApiCaptureStore();
        store.record(call("/api/unknown", "https://x/api/unknown", RouteHandleType.MONITOR));
        assertNotNull(store.getLastApiCall("/api/unknown"));
        assertEquals(RouteHandleType.MONITOR, store.getLastApiCall("/api/unknown").handleType());
    }

    @Test
    public void clearReleasesAllData() {
        ApiCaptureStore store = new ApiCaptureStore();
        store.record(call("/api/a", "https://x/api/a", RouteHandleType.MOCK));
        store.record(call("/api/b", "https://x/api/b", RouteHandleType.DELAY));
        assertTrue(store.getTotalResponseCount() >= 2);

        store.clear();
        assertEquals(0, store.getApiCalls("/api/a").size());
        assertEquals(0, store.getTotalResponseCount());
        assertNull(store.getLastApiCall("/api/b"));
    }
}
