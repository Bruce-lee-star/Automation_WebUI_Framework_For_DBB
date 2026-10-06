package com.hsbc.cmb.hk.dbb.automation.framework.web.utils;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * WEB-P1-5 种子测试：国际化语言状态（无浏览器，纯逻辑）。
 * 覆盖默认无语言、set/get 往返、reset 清状态、可见文本剥 HTML、模板编译占位符替换。
 */
public class NLSUtilsTest {

    @After
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
        assertNull("reset 应清空语言状态", NLSUtils.getLanguage());
    }

    @Test
    public void visibleText_stripsHtmlTags() {
        String visible = NLSUtils.visibleText("<b>Hello</b> world");
        assertFalse("visibleText 应剥离 HTML 标签", visible.contains("<"));
        assertTrue(visible.contains("Hello"));
        assertTrue(visible.contains("world"));
    }

    @Test
    public void templateRegexSource_replacesPlaceholder() {
        String src = NLSUtils.templateRegexSource("Hi {{name}}");
        assertTrue("模板占位符应编译为正则捕获组", src.contains("(.*?)"));
        assertFalse("模板占位符不应残留 {{name}}", src.contains("{{name}}"));
    }
}
