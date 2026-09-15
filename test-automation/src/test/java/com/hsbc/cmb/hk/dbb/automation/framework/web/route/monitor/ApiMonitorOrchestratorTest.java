package com.hsbc.cmb.hk.dbb.automation.framework.web.route.monitor;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.ApiMonitorConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.ApiMonitorOrchestrator;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * X-3 / R-2 验证：监控 pattern 去重从进程级单例改为 context 级。
 *
 * <p>核心回归点：并行下不同 {@link BrowserContext} 应各自<b>独立</b>注册同一 pattern，
 * 不再因进程级去重导致后注册的 context 跳过自己的监控规则注册（缺口型跨 context 串扰）。
 * 同一 context 内跨 case 去重语义保持不变；context 关闭释放仅影响该 context。
 *
 * <p>features 程序化注入（绕过 {@code ApiMonitorConfig} 的 JSON 顶层 key 与 {@code features} 字段名不匹配的加载限制），
 * 专注于 R-2 去重逻辑本身；{@link ApiMonitorOrchestrator#registerFeature(String, Page)} 走 {@link ApiMonitorConfig#getInstance()}。
 */
public class ApiMonitorOrchestratorTest {

    private ApiMonitorOrchestrator orch;

    @BeforeEach
    public void setUp() {
        orch = ApiMonitorOrchestrator.getInstance();
        //  注入监控清单：login -> { api/login, api/auth/assert }
        Gson gson = new Gson();
        Type epMap = new TypeToken<Map<String, ApiMonitorConfig.EndpointConfig>>() { }.getType();
        Map<String, ApiMonitorConfig.EndpointConfig> login = gson.fromJson(
                "{\"api/login\":{\"timeout\":30,\"autoStopMonitor\":true,\"apiOwner\":\"a@x.com\",\"expectStatus\":200},"
                        + "\"api/auth/assert\":{\"timeout\":30,\"autoStopMonitor\":false,\"apiOwner\":\"b@x.com\",\"expectStatus\":200}}",
                epMap);
        Map<String, Map<String, ApiMonitorConfig.EndpointConfig>> features = new HashMap<>();
        features.put("login", login);
        ApiMonitorConfig.loadFrom(ApiMonitorConfig.DEFAULT_CONFIG_PATH);
        ApiMonitorConfig.getInstance().setFeatures(features);
    }

    @AfterEach
    public void tearDown() {
        orch.clear();
        ApiMonitorConfig.loadFrom(ApiMonitorConfig.DEFAULT_CONFIG_PATH);
    }

    private Page mockPage(BrowserContext ctx) {
        Page page = mock(Page.class);
        when(page.context()).thenReturn(ctx);
        // RouteEngine.register 在 mock context 上可能 enumerate pages()，避免 NPE
        when(ctx.pages()).thenReturn(Collections.emptyList());
        return page;
    }

    @Test
    public void samePatternRegistersIndependentlyPerContext() {
        BrowserContext ctxA = mock(BrowserContext.class);
        BrowserContext ctxB = mock(BrowserContext.class);
        int a = orch.registerFeature("login", mockPage(ctxA));
        int b = orch.registerFeature("login", mockPage(ctxB));

        //  关键断言：并行下两个 context 都应成功注册（旧全局去重会令 b == 0）
        assertTrue(a > 0, "context A 应注册监控");
        assertEquals(a, b, "不同 context 应各自独立注册同一 feature（不再被全局去重跳过）");
        assertTrue(orch.isRegistered("api/login", ctxA), "ctxA 应已注册 api/login");
        assertTrue(orch.isRegistered("api/login", ctxB), "ctxB 应已注册 api/login");
    }

    @Test
    public void dedupStillAppliesWithinSameContext() {
        BrowserContext ctx = mock(BrowserContext.class);
        Page page = mockPage(ctx);

        int first = orch.registerFeature("login", page);
        int second = orch.registerFeature("login", page);
        assertTrue(first > 0, "首次注册应成功");
        assertEquals(0, second, "同一 context 内重复注册应被去重（返回 0）");
    }

    @Test
    public void deregisterContextOnlyClearsThatContext() {
        BrowserContext ctxA = mock(BrowserContext.class);
        BrowserContext ctxB = mock(BrowserContext.class);

        orch.registerFeature("login", mockPage(ctxA));
        orch.registerFeature("login", mockPage(ctxB));

        orch.deregisterContext(ctxA);
        assertFalse(orch.isRegistered("api/login", ctxA), "A 注销后其 pattern 应清除");
        assertTrue(orch.isRegistered("api/login", ctxB), "B 的 pattern 不应被 A 的注销影响");
    }
}
