package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.util;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * FieldReplacer 内容类型感知字段替换：JSON / 表单 / XML（含 XXE 防护）/ 文本 /
 * 非适用类型明确失败，单字段失败不影响其它字段。
 */
public class FieldReplacerTest {

    @Test
    public void jsonReplacementByJsonPath() {
        FieldReplacer.ReplaceResult r = FieldReplacer.replace(
                MediaType.parse("application/json"),
                "{\"code\":0,\"data\":{\"name\":\"old\"}}",
                Map.of("$.data.name", "new", "$.data.flag", true));
        assertTrue(r.failures().isEmpty());
        assertEquals((Object) "{\"code\":0,\"data\":{\"name\":\"new\",\"flag\":true}}", (Object) r.body());
    }

    @Test
    public void jsonReplacementMissingPathFailsIndependently() {
        FieldReplacer.ReplaceResult r = FieldReplacer.replace(
                MediaType.parse("application/json"),
                "{\"code\":0}",
                Map.of("$.code", 1, "$.nope.deep", "x"));
        assertEquals("缺失路径必须记为失败", (long) 1, (long) r.failures().size());
        assertTrue( "成功字段仍生效", r.body().contains("\"code\":1"));
    }

    @Test
    public void formReplacementSetsFirstValueAndAppendsMissing() {
        FieldReplacer.ReplaceResult r = FieldReplacer.replace(
                MediaType.parse("application/x-www-form-urlencoded"),
                "token=old&count=3",
                Map.of("token", "new", "extra", "added"));
        assertTrue(r.failures().isEmpty());
        assertEquals((Object) "token=new&count=3&extra=added", (Object) r.body());
    }

    @Test
    public void xmlReplacementByXPathWithXxeProtection() {
        FieldReplacer.ReplaceResult r = FieldReplacer.replace(
                MediaType.parse("application/xml"),
                "<root><name>old</name><code>0</code></root>",
                Map.of("/root/name", "new"));
        assertTrue(r.failures().isEmpty());
        assertTrue( "XPath 文本节点必须被替换", r.body().contains("<name>new</name>"));
        assertFalse(r.body().contains(">old<"));
    }

    @Test
    public void xmlWithDoctypeIsRejectedByXxeGuard() {
        String malicious = "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><root><name>&xxe;</name></root>";
        FieldReplacer.ReplaceResult r = FieldReplacer.replace(
                MediaType.parse("application/xml"),
                malicious,
                Map.of("/root/name", "safe"));
        // XXE 防护下 DOCTYPE 解析必须失败 → 替换失败保留原体（fail-open），绝不解析外部实体
        assertEquals((long) 1, (long) r.failures().size());
        assertTrue(r.failures().get(0).contains("failed"));
    }

    @Test
    public void textReplacementFirstOccurrence() {
        FieldReplacer.ReplaceResult r = FieldReplacer.replace(
                MediaType.parse("text/plain"),
                "hello world hello",
                Map.of("hello", "hi"));
        assertTrue(r.failures().isEmpty());
        assertEquals("只替换首次出现", (Object) "hi world hello", (Object) r.body());
    }

    @Test
    public void binaryBodyRejectedClearly() {
        FieldReplacer.ReplaceResult r = FieldReplacer.replace(
                MediaType.parse("image/png"),
                "binary",
                Map.of("$.a", "1"));
        assertEquals((long) 1, (long) r.failures().size());
        assertTrue(r.failures().get(0).contains("not applicable"));
        assertEquals("失败保留原体", (Object) "binary", (Object) r.body());
    }
}
