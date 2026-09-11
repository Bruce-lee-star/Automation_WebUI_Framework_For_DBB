package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真浏览器 E2E：验证统一绑定模型（Phase 3）在生产级 Playwright 下的核心保证：
 * <ul>
 *   <li>context 级规则作用于同 context 全部页面（含发起请求的当前页）；</li>
 *   <li>同 pattern 混合 PAGE/CONTEXT 时，page 特定 &gt; context 全域（MOCK 终结、优先级裁决）；</li>
 *   <li>同 context 新页面（popup）自动继承 context 规则，但不继承其它页的 page 特定规则（pageRef 隔离）；</li>
 *   <li>单原生 context.route 绑定点，跨请求稳定合并执行（无重复处理 / 无竞态）。</li>
 * </ul>
 *
 * <p>仅断言响应<b>状态码</b>：route 直接 fulfill 时状态码始终可读，规避跨域 fetch 响应体读取的 CORS 限制。
 * 请求由「页面上下文内 fetch」发起（Playwright 仅拦截页面上下文请求，{@code page.request()} 不经过 route）。
 */
public class RouteUnifiedBindingBrowserE2ETest {

    private static Playwright pw;
    private static Browser browser;
    private BrowserContext context;
    private Page page;

    @BeforeAll
    public static void launch() {
        pw = Playwright.create();
        browser = pw.chromium().launch();
    }

    @AfterAll
    public static void shutdown() {
        if (browser != null) browser.close();
        if (pw != null) pw.close();
    }

    @BeforeEach
    public void setup() {
        context = browser.newContext();
        page = context.newPage();
        page.navigate("about:blank");
    }

    @AfterEach
    public void teardown() {
        try {
            if (context != null) RouteDsl.clear(context);
        } catch (Exception ignore) {
            // 清理失败不应影响其它用例
        }
        try {
            if (context != null) context.close();
        } catch (Exception ignore) {
            // 同上
        }
    }

    private static int fetchStatus(Page p, String url) {
        Object r = p.evaluate("async (u) => { const res = await fetch(u); return res.status; }", url);
        return ((Number) r).intValue();
    }

    /** context 级规则作用于同 context 当前页。 */
    @Test
    public void contextRule_appliesToPage() {
        final String pattern = "**/api/ctx";
        final String url = "https://example.com/api/ctx";
        RouteDsl.on(context).api(pattern).mock().mockStatus(201).mockBody("CTX").done().start();
        assertEquals(201, fetchStatus(page, url));
    }

    /** 同 pattern：page 特定规则优先于 context 全域规则（MOCK 终结 + 优先级裁决）。 */
    @Test
    public void pageSpecificOverridesContextOnSamePattern() {
        final String pattern = "**/api/mix";
        final String url = "https://example.com/api/mix";
        RouteDsl.on(context).api(pattern).mock().mockStatus(201).mockBody("CTX").done().start();
        RouteDsl.on(page).api(pattern).mock().mockStatus(200).mockBody("PAGE").done().start();
        assertEquals(200, fetchStatus(page, url));
    }

    /** 同 context 新页面（popup）继承 context 规则，但不继承其它页的 page 特定规则（pageRef 隔离）。 */
    @Test
    public void popupInheritsContextButNotOtherPageRule() {
        final String pattern = "**/api/shared";
        final String url = "https://example.com/api/shared";
        RouteDsl.on(context).api(pattern).mock().mockStatus(201).mockBody("CTX").done().start();
        RouteDsl.on(page).api(pattern).mock().mockStatus(200).mockBody("PAGE-ONLY").done().start();

        Page popup = context.newPage();
        popup.navigate("about:blank");
        try {
            assertEquals(201, fetchStatus(popup, url));
        } finally {
            popup.close();
        }
    }

    /** 单原生绑定：多次请求均稳定命中合并结果，无竞态 / 无重复处理。 */
    @Test
    public void singleNativeBindingStableAcrossRequests() {
        final String pattern = "**/api/stable";
        final String url = "https://example.com/api/stable";
        RouteDsl.on(context).api(pattern).mock().mockStatus(200).mockBody("OK").done().start();
        for (int i = 0; i < 5; i++) {
            assertEquals(200, fetchStatus(page, url));
        }
    }
}
