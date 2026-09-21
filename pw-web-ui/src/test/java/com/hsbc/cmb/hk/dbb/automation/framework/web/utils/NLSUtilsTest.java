package com.hsbc.cmb.hk.dbb.automation.framework.web.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WEB-P1-5 种子测试：国际化语言状态（无浏览器，纯逻辑）。
 * 覆盖默认无语言、set/get 往返、reset 清状态、可见文本剥 HTML、模板编译占位符替换。
 */
public class NLSUtilsTest {

    @AfterEach
    public void tearDown() {
        NLSUtils.reset();
    }

    @Test
    public void setGetReset_roundTrip() {
        NLSUtils.reset();
        assertNull(NLSUtils.getLanguage());
        NLSUtils.setLanguage("zh");
        assertEquals("zh", NLSUtils.getLanguage());
        NLSUtils.reset();
        assertNull( NLSUtils.getLanguage(), "reset 应清空语言状态");
    }

    @Test
    public void visibleText_stripsHtmlTags() {
        String visible = NLSUtils.visibleText("<b>Hello</b> world");
        assertFalse( visible.contains("<"), "visibleText 应剥离 HTML 标签");
        assertTrue(visible.contains("Hello"));
        assertTrue(visible.contains("world"));
    }

    @Test
    public void templateRegexSource_replacesPlaceholder() {
        String src = NLSUtils.templateRegexSource("Hi {{name}}");
        assertTrue( src.contains("(.*?)"), "模板占位符应编译为正则捕获组");
        assertFalse( src.contains("{{name}}"), "模板占位符不应残留 {{name}}");
    }
}
