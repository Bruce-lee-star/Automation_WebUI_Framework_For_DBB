package com.hsbc.cmb.hk.dbb.automation.framework.route.monitor;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.ApiSpec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteCapability;
import com.hsbc.cmb.hk.dbb.automation.framework.route.util.MediaType;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * PayloadAssertor 内容类型感知断言语义：JSON / 表单 / 文本 / 二进制路由，
 * 非适用类型的明确失败、charset 解码。覆盖 MediaType 判定。
 */
public class PayloadAssertorTest {

    // ── MediaType 判定 ──

    @Test
    public void mediaTypeClassification() {
        assertTrue(MediaType.parse("application/json").isJson());
        assertTrue(MediaType.parse("application/hal+json; charset=utf-8").isJson());
        assertTrue(MediaType.parse("application/json;charset=GBK").charset().name().equals("GBK"));
        assertTrue(MediaType.parse("application/x-www-form-urlencoded").isFormUrlEncoded());
        assertTrue(MediaType.parse("text/html; charset=utf-8").isText());
        assertTrue(MediaType.parse("application/xml").isText());
        assertFalse( "二进制不得判定为文本", MediaType.parse("image/png").isText());
        assertFalse(MediaType.parse("application/octet-stream").isText());
        assertFalse( "缺失 content-type 不得猜测", MediaType.parse(null).isText());
        assertFalse(MediaType.parse(null).isJson());
    }

    @Test
    public void formFieldsParsedWithDuplicateKeys() {
        Map<String, List<String>> fields = FormAssertor.parse("a=1&a=2&b=x+y&flag", java.nio.charset.StandardCharsets.UTF_8);
        assertEquals((Object) List.of("1", "2"), (Object) fields.get("a"));
        assertEquals("表单值需 URL 解码（+ → 空格）", (Object) List.of("x y"), (Object) fields.get("b"));
        assertEquals((Object) List.of(""), (Object) fields.get("flag"));
    }

    // ── JSON 路由 ──

    @Test
    public void jsonAssertionsRouteToJsonPath() {
        ApiSpec spec = ApiSpec.builder("/x", RouteCapability.MONITOR)
                .expectJsonPath("$.code", 0)
                .build();
        assertTrue(PayloadAssertor.assertAll(spec, "{\"code\":0}", "application/json").isEmpty());
        assertEquals((long) 1, (long) PayloadAssertor.assertAll(spec, "{\"code\":1}", "application/json").size());
    }

    @Test
    public void jsonAssertionsOnNonJsonBodyFailClearly() {
        ApiSpec spec = ApiSpec.builder("/x", RouteCapability.MONITOR)
                .expectJsonPath("$.code", 0)
                .build();
        List<String> failures = PayloadAssertor.assertAll(spec, "<result><code>0</code></result>", "application/xml");
        assertEquals((long) 1, (long) failures.size());
        assertTrue( "非 JSON 必须明确 not applicable，而非 JSON 解析异常", failures.get(0).contains("not applicable"));
        assertTrue(failures.get(0).contains("application/xml"));
    }

    @Test
    public void jsonAssertionsRunWhenContentTypeMissing() {
        ApiSpec spec = ApiSpec.builder("/x", RouteCapability.MONITOR)
                .expectJsonPath("$.enableAdminTools", "Y")
                .build();
        // 对齐老版 MonitorHandler（直接 JsonPath.read(body)）：expectJsonPath 即「预期 JSON」的显式声明，
        // content-type 缺失时不得拒绝 —— 实测 DBB 端点 leftmenu/permissionLeftMenuConfig 返回 200 却无该头。
        assertTrue( "content-type 缺失时 jsonpath 断言仍须执行（老版语义）",
                PayloadAssertor.assertAll(spec, "{\"enableAdminTools\":\"Y\"}", null).isEmpty());

        List<String> failures = PayloadAssertor.assertAll(spec, "{\"enableAdminTools\":\"N\"}", null);
        assertEquals((long) 1, (long) failures.size());
        assertFalse("不得再报 not applicable", failures.get(0).contains("not applicable"));
        assertTrue(failures.get(0).contains("expected='Y'"));
    }

    @Test
    public void jsonAssertionsOnMissingContentTypeAndNonJsonBodyFailWithParseDetail() {
        ApiSpec spec = ApiSpec.builder("/x", RouteCapability.MONITOR)
                .expectJsonPath("$.code", 0)
                .build();
        List<String> failures = PayloadAssertor.assertAll(spec, "<html>logged out</html>", null);
        assertEquals((long) 1, (long) failures.size());
        assertTrue( "非 JSON 体必须给出明确的解析失败明细", failures.get(0).startsWith("path '$.code'"));
        // 空体（会话失效的空 200）同样必须失败，绝不能静默通过
        assertFalse("空体不得静默通过", PayloadAssertor.assertAll(spec, "", null).isEmpty());
    }

    @Test
    public void jsonAssertionsStillRejectedForExplicitBinaryContentType() {
        ApiSpec spec = ApiSpec.builder("/x", RouteCapability.MONITOR)
                .expectJsonPath("$.code", 0)
                .build();
        List<String> failures = PayloadAssertor.assertAll(spec, "{\"code\":0}", "application/octet-stream");
        assertEquals((long) 1, (long) failures.size());
        assertTrue( "content-type 明确声明非 JSON/二进制时仍拒绝解析（不猜测）", failures.get(0).contains("not applicable"));
    }

    // ── 表单路由 ──

    @Test
    public void formFieldAssertionsRouteToFormParser() {
        ApiSpec spec = ApiSpec.builder("/x", RouteCapability.MONITOR)
                .expectFormField("token", "abc")
                .expectFormField("count", "3")
                .build();
        String body = "token=abc&count=3";
        assertTrue(PayloadAssertor.assertAll(spec, body, "application/x-www-form-urlencoded").isEmpty());

        List<String> failures = PayloadAssertor.assertAll(spec, "token=def&count=3", "application/x-www-form-urlencoded");
        assertEquals((long) 1, (long) failures.size());
        assertTrue(failures.get(0).contains("token"));
    }

    @Test
    public void formFieldOnNonFormBodyFailsClearly() {
        ApiSpec spec = ApiSpec.builder("/x", RouteCapability.MONITOR)
                .expectFormField("token", "abc")
                .build();
        List<String> failures = PayloadAssertor.assertAll(spec, "{\"token\":\"abc\"}", "application/json");
        assertEquals((long) 1, (long) failures.size());
        assertTrue(failures.get(0).contains("not applicable"));
    }

    // ── 文本路由 ──

    @Test
    public void bodyContainsAndRegexOnTextTypes() {
        ApiSpec spec = ApiSpec.builder("/x", RouteCapability.MONITOR)
                .expectBodyContains("transaction ok")
                .expectBodyRegex("ref-\\d{6}")
                .build();
        String html = "<html>transaction ok ref-123456</html>";
        assertTrue(PayloadAssertor.assertAll(spec, html, "text/html; charset=utf-8").isEmpty());
        assertTrue( "XML 同属文本类型", PayloadAssertor.assertAll(spec, html, "application/xml").isEmpty());

        List<String> failures = PayloadAssertor.assertAll(spec, "<html>transaction failed</html>", "text/html");
        assertEquals("contains 与 regex 同时失败各记一条", (long) 2, (long) failures.size());
    }

    @Test
    public void textAssertionsOnBinaryBodyFailClearly() {
        ApiSpec spec = ApiSpec.builder("/x", RouteCapability.MONITOR)
                .expectBodyContains("abc")
                .build();
        List<String> failures = PayloadAssertor.assertAll(spec, "binary\u0000data", "image/png");
        assertEquals((long) 1, (long) failures.size());
        assertTrue(failures.get(0).contains("not applicable"));
    }

    // ── 混合断言：各路由独立裁决 ──

    @Test
    public void mixedAssertionsEachRouteIndependently() {
        ApiSpec spec = ApiSpec.builder("/x", RouteCapability.MONITOR)
                .expectJsonPath("$.code", 0)
                .expectBodyContains("hello")
                .build();
        // JSON 响应：jsonpath 过，contains 过
        assertTrue(PayloadAssertor.assertAll(spec, "{\"code\":0,\"msg\":\"hello\"}", "application/json").isEmpty());
        // XML 响应：jsonpath not applicable + contains 过
        List<String> failures = PayloadAssertor.assertAll(spec, "<r><m>hello</m></r>", "application/xml");
        assertEquals("仅 jsonpath 报 not applicable，contains 仍独立判定", (long) 1, (long) failures.size());
    }
}
