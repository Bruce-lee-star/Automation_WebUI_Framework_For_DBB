package com.hsbc.cmb.hk.dbb.automation.tests.glue.parallel;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.tests.utils.RouteDemoApi;
import com.microsoft.playwright.Page;
import net.serenitybdd.annotations.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Route Demo 本地并行冒烟步骤 —— 验证 CON-1 引擎级并行真正并发 GREEN（绕过内网）。
 *
 * <p><b>线程安全要点（关键）：</b>引擎级并行（Cucumber fixed 策略）下，Serenity 将 {@code @Steps} /
 * PageObject 实例 JVM 级缓存，若把 Page 缓存在字段中会被多线程共享 → "BasePage 实例被线程 X 访问，
 * 但创建它的线程是 Y"。因此本类<b>不缓存任何 Page/PageObject</b>，每步经
 * {@link PlaywrightManager#getPage()} 取<b>当前线程</b>托管的 Page（由 Glue 的
 * {@code @Before FrameworkCore.beforeTest()} 每线程建好）。</p>
 *
 * <p>后端为只读 GET（/demo/api/users）+ 拦截式 MOCK（不写后端），路由规则 per-Context（X-3 已修），
 * 故并发 scenario 互不串扰，可安全跑梯度 2→4→8 压测（本地代理验证）。</p>
 */
public class RouteDemoParallelSmokeSteps {

    private static final Logger logger = LoggerFactory.getLogger(RouteDemoParallelSmokeSteps.class);
    private static final String BASE = "http://localhost:8888/demo/api";
    private static final String ORIGIN_URL = "http://localhost:8888/demo/api/search";

    /** 当前线程托管的 Page（引擎级并行下每 scenario 独立，绝不跨线程共享）。 */
    private Page page() {
        return PlaywrightManager.getPage();
    }

    private void openOrigin(Page p) {
        if (p.url() != null && p.url().startsWith("http://localhost:8888")) {
            p.reload();
        } else {
            p.navigate(ORIGIN_URL);
        }
    }

    private String get(Page p, String path) {
        if (p.url() == null || !p.url().startsWith("http://localhost:8888")) {
            openOrigin(p);
        }
        String result = RouteDemoApi.getJson(p, BASE + path);
        logger.info("[DIAG] GET {} => {}",
                path, (result == null ? "null" : "len=" + result.length()));
        return result;
    }

    private CapturedApiCall waitForCaptured(String urlContains) {
        ApiCaptureContext ctx = ApiCaptureContext.forContext(page().context());
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            for (List<CapturedApiCall> calls : ctx.getAllApiCalls().values()) {
                for (CapturedApiCall c : calls) {
                    String url = c.requestUrl() != null ? c.requestUrl() : "";
                    if (url.contains(urlContains)) {
                        return c;
                    }
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    @Step
    public void monitorCollectsRealResponse() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .monitor()
                    .record(true)
                    .expectStatus(200)
                    .minMatches(1)
                    .autoStopOnMatch(true)
                    .timeout(5)
                .done()
                .start();

        openOrigin(page());

        String body = get(page(), "/users");
        CapturedApiCall call = waitForCaptured("/demo/api/users");
        assertNotNull(call, "monitor 应将 /demo/api/users 的响应记入采集上下文");
        assertEquals(200, call.statusCode());
        assertNotNull(body);
        assertTrue(body.contains("Alice"), "采集到的响应应来自真实 demo service（含 Alice）");
    }

    @Step
    public void mockReplacesWholeResponse() {
        RouteDsl.on(page())
                .api("/demo/api/users")
                .mock()
                    .mockBody("{\"users\":[{\"name\":\"MockedUser\",\"role\":\"MOCK\"}]}")
                .done()
                .start();

        openOrigin(page());

        String body = get(page(), "/users");
        assertTrue(body.contains("MockedUser"), "mock 响应应返回 MockedUser");
        assertFalse(body.contains("Alice"), "mock 响应不应包含真实数据 Alice");
    }

    @Step
    public void cleanup() {
        try {
            RouteDsl.clear(page());
        } catch (Exception ignored) {
            // 某些场景未注册规则，clear 允许空操作
        }
        try {
            ApiCaptureContext.removeContext(page().context());
        } catch (Exception ignored) {
            // 上下文已释放时静默
        }
    }
}
