package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * ApiCaptureManager 行为护盾：常驻采集、Handler 汇聚、场景隔离、enable 开关、去重。
 */
public class ApiCaptureManagerTest {

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

    @After
    public void tearDown() {
        ApiCaptureManager.getInstance().setApiCaptureEnabled(true);
        ApiCaptureManager.getInstance().endApiCapture();
    }

    @Test
    public void handlerAggregationCapturesMockModifyDelay() {
        ApiCaptureManager mgr = ApiCaptureManager.getInstance();
        mgr.beginApiCapture();

        mgr.record(call("/api/m", "https://x/api/m", RouteHandleType.MOCK));
        mgr.record(call("/api/x", "https://x/api/x", RouteHandleType.MODIFY));
        mgr.record(call("/api/d", "https://x/api/d", RouteHandleType.DELAY));

        assertEquals(1, mgr.getAllByType(RouteHandleType.MOCK).size());
        assertEquals(1, mgr.getAllByType(RouteHandleType.MODIFY).size());
        assertEquals(1, mgr.getAllByType(RouteHandleType.DELAY).size());
    }

    @Test
    public void passthroughForUnregisteredTraffic() {
        ApiCaptureManager mgr = ApiCaptureManager.getInstance();
        mgr.beginApiCapture();

        mgr.recordPassthrough("https://x/api/free", 200, "GET", null, null);
        CapturedApiCall c = mgr.getLastApiCall("/api/free");
        assertNotNull(c);
        assertEquals(RouteHandleType.MONITOR, c.handleType());
    }

    @Test
    public void scenarioIsolationClearsStore() {
        ApiCaptureManager mgr = ApiCaptureManager.getInstance();
        mgr.beginApiCapture();
        mgr.record(call("/api/a", "https://x/api/a", RouteHandleType.MOCK));
        assertTrue(mgr.getApiCalls("/api/a").size() >= 1);

        mgr.endApiCapture();
        assertEquals(0, mgr.getApiCalls("/api/a").size());
    }

    @Test
    public void disabledCaptureDropsRecords() {
        ApiCaptureManager mgr = ApiCaptureManager.getInstance();
        mgr.beginApiCapture();
        mgr.setApiCaptureEnabled(false);

        mgr.record(call("/api/a", "https://x/api/a", RouteHandleType.MOCK));
        mgr.recordPassthrough("https://x/api/b", 200, "GET", null, null);

        assertEquals(0, mgr.getApiCalls("/api/a").size());
        assertEquals(0, mgr.getApiCalls("/api/b").size());
    }

    @Test
    public void groupedByTypeReflectsAllKinds() {
        ApiCaptureManager mgr = ApiCaptureManager.getInstance();
        mgr.beginApiCapture();

        mgr.record(call("/api/m", "https://x/api/m", RouteHandleType.MOCK));
        mgr.record(call("/api/d", "https://x/api/d", RouteHandleType.DELAY));

        Map<RouteHandleType, List<CapturedApiCall>> grouped = mgr.getAllGroupedByType();
        assertEquals(1, grouped.get(RouteHandleType.MOCK).size());
        assertEquals(1, grouped.get(RouteHandleType.DELAY).size());
    }
}
