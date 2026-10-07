package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

/**
 * 页类名解析契约（{@link RolePickerClassNameResolver#resolvePageClassForUrl}）。
 *
 * <p>这是"URL 变化是否被识别为<b>新页面</b>"的唯一判据，也是"URL change 后没有生成新的页面"的边界所在：
 * <ul>
 *   <li><b>路径不同 ⇒ 必须是不同页类</b>：否则新页面永远生成不出来（新页拾取的元素会被归入旧页类）；</li>
 *   <li><b>仅 query/hash 抖动 ⇒ 复用同一页类</b>：既有设计（避免"同一页因 query 抖动派生出 XxxPage2"）。
 *       因此"仅靠 query/hash 区分的两个视图"不会被识别为新页面 —— 这是<b>有意识的取舍</b>，
 *       本用例显式锁定，避免日后被误改或被当成 bug 反复排查；</li>
 *   <li><b>语言码差异 ⇒ 复用同一页类</b>：{@code /en/x} 与 {@code /zh-HK/x} 归并；</li>
 *   <li><b>同一 URL（会话内）⇒ 复用同一页类</b>：不重复派生，避免出现 XxxPage2。</li>
 * </ul>
 */
public class RolePickerClassNameResolverTest {

    private static String resolve(String url, List<String> used, LinkedHashMap<String, String> urlToClass) {
        return RolePickerClassNameResolver.resolvePageClassForUrl(url, used, urlToClass);
    }

    @Test
    // @DisplayName: "路径不同必须解析出不同页类（否则新页面永远生成不出来）"
    public void differentPathYieldsDifferentPageClass() {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        List<String> used = new ArrayList<>();
        String logon = resolve("https://host/portalserver/logon", used, map);
        assertNotNull(logon);
        assertFalse(logon.isEmpty());
        used.add(logon);
        String next = resolve("https://host/portalserver/msk/activate", used, map);
        assertNotNull(next);
        assertNotEquals("路径不同必须解析出不同页类，否则新页面永远生成不出来", logon, next);
    }

    @Test
    // @DisplayName: "仅 query/hash 抖动应复用同一页类（既有设计取舍）"
    public void queryHashJitterReusesSamePageClass() {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        String a = resolve("https://host/portalserver/logon?a=1", new ArrayList<>(), map);
        String b = resolve("https://host/portalserver/logon?a=2#section", new ArrayList<>(), map);
        assertEquals("仅 query/hash 抖动应复用同一页类", a, b);
    }

    @Test
    // @DisplayName: "语言码差异归并为同一页类"
    public void localeSegmentIsStripped() {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        String en = resolve("https://host/en/accounts", new ArrayList<>(), map);
        String zh = resolve("https://host/zh-HK/accounts", new ArrayList<>(), map);
        assertEquals("语言码差异应归并为同一页类", en, zh);
        assertFalse(en.isEmpty());
    }

    @Test
    // @DisplayName: "同一 URL 会话内复用已登记页类，不重复派生"
    public void sameUrlReusesRegisteredClass() {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        List<String> used = new ArrayList<>();
        String first = resolve("https://host/portalserver/logon", used, map);
        used.add(first);
        String second = resolve("https://host/portalserver/logon", used, map);
        assertEquals("同一 URL 必须复用同一页类（否则会派生出 XxxPage2）", first, second);
    }
}
