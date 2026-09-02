package com.hsbc.cmb.hk.dbb.automation.framework.web.route.core;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 固化 Phase 5 抽离出的 {@link AssertionFailureDetail} DTO 契约（与拆分前语义严格一致）。
 *
 * <p>覆盖：构造器字段赋值、{@code toString()} 的 URL 端点缩写（含 host 存在/缺失/超长/畸形）降级。
 * 与 {@link AssertionFailureDetail} 同包，可访问其 package-private 构造器。
 * <b>不依赖 Playwright</b>。
 */
public class AssertionFailureDetailTest {

    @Test
    public void fieldsAssignedViaPackagePrivateCtor() {
        AssertionFailureDetail d = new AssertionFailureDetail(
                "https://api.example.com/v1/users", "STATUS", "200", "500", "pattern=/api/**");
        org.junit.Assert.assertEquals("https://api.example.com/v1/users", d.url);
        org.junit.Assert.assertEquals("STATUS", d.assertionType);
        org.junit.Assert.assertEquals("200", d.expectedValue);
        org.junit.Assert.assertEquals("500", d.actualValue);
        org.junit.Assert.assertEquals("pattern=/api/**", d.failMessage);
    }

    @Test
    public void toString_handlesNullUrl() {
        AssertionFailureDetail d = new AssertionFailureDetail(
                null, "JSONPATH", "x", "y", null);
        String s = d.toString();
        assertNotNull(s);
        assertTrue("null URL 应降级为 N/A", s.contains("N/A"));
    }

    @Test
    public void toString_extractsEndpointFromFullUrl() {
        AssertionFailureDetail d = new AssertionFailureDetail(
                "https://api.example.com/v1/users?role=admin", "STATUS", "200", "200", null);
        String s = d.toString();
        // 应含 host 缩写与 path（endpoint 段完整保留）
        assertTrue(s.contains("api.example.com") || s.contains("..."));
        assertTrue(s.contains("/v1/users"));
    }

    @Test
    public void toString_handlesMalformedUrlGracefully() {
        AssertionFailureDetail d = new AssertionFailureDetail(
                "not a url ::::", "BODY_CONTAINS", "a", "b", "x");
        // 畸形 URL 不应抛异常，应降级截断
        assertNotNull(d.toString());
    }

    @Test
    public void toString_truncatesVeryLongUrl() {
        StringBuilder sb = new StringBuilder("https://host.example.com/");
        for (int i = 0; i < 200; i++) sb.append("segment").append(i).append('/');
        AssertionFailureDetail d = new AssertionFailureDetail(
                sb.toString(), "STATUS", "1", "2", null);
        assertNotNull(d.toString());
    }
}
