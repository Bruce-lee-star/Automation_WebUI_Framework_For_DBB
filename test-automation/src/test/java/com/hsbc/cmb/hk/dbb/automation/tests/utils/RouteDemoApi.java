package com.hsbc.cmb.hk.dbb.automation.tests.utils;

import com.microsoft.playwright.Page;

import java.util.HashMap;
import java.util.Map;

/**
 * 在「页面 JS 上下文」中调用 demo service API 的语义化封装。
 *
 * <p><b>为什么必须在页面上下文发起</b>：Playwright 的 APIRequestContext
 * （{@code page.request()} / {@code context.request()}）发出的请求<b>不经过</b> page/context 的
 * route 拦截；框架的拦截只对「页面上下文内的 fetch / navigation」生效。
 * 因此要让 {@code RouteDsl} 的规则命中，就必须在页面上下文内发起调用。
 *
 * <p>本类把 JS 细节收敛在此，业务步骤只需 {@link #getJson} / {@link #postJson}，
 * 无需在测试代码中出现裸的 fetch 脚本。
 *
 * <p>注意：调用前页面必须已处于目标服务的同源 origin 下（否则跨域会阻止响应读取），
 * 参见 RouteDemoServiceSteps 中「先注册规则、再导航 / 刷新」的约定。
 */
public final class RouteDemoApi {

    private static final String GET_SCRIPT =
            "async (u) => { const r = await fetch(u); return await r.text(); }";

    private static final String POST_SCRIPT =
            "async (a) => { const r = await fetch(a.u, {method:'POST', "
                    + "headers:{'Content-Type':'application/json'}, body:a.b}); return await r.text(); }";

    private RouteDemoApi() {
    }

    /** 在页面上下文内 GET 指定 URL，返回响应文本。 */
    public static String getJson(Page page, String url) {
        return (String) page.evaluate(GET_SCRIPT, url);
    }

    /** 在页面上下文内 POST JSON 到指定 URL，返回响应文本。 */
    public static String postJson(Page page, String url, String jsonBody) {
        Map<String, String> args = new HashMap<>();
        args.put("u", url);
        args.put("b", jsonBody);
        return (String) page.evaluate(POST_SCRIPT, args);
    }
}
