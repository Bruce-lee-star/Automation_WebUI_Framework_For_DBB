package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 页面类名生成与 URL -> Page 类名映射（由 RoleElementPicker 调用，属 T5-1 拆分第三步）。
 * 负责 URL 归一化、语言编码剥离、类名字段清洗与去重派生，以及跨多次 pick 运行的全局持久映射缓存；
 * 原方法体逐字平移，由 RoleElementPicker 以完全限定名直接调用，行为完全等价。
 */
final class RolePickerClassNameResolver {

    private static final Logger log = LoggerFactory.getLogger(RolePickerClassNameResolver.class);
    private RolePickerClassNameResolver() {}
    static String pageClassNameFromUrl(String url, Collection<String> used) {
        String raw = url == null ? "" : url.trim();
        int q = raw.indexOf('?'); if (q >= 0) raw = raw.substring(0, q);
        int h = raw.indexOf('#'); if (h >= 0) raw = raw.substring(0, h);
        int s = raw.lastIndexOf('/');
        String seg = (s >= 0) ? raw.substring(s + 1) : raw;
        if (seg.isEmpty()) seg = "Index";           // 特殊：根路径 / 仅域名
        String base = toClassNameSegment(seg);
        if (base.isEmpty()) base = "Index";
        String candidate = base + "Page";
        String unique = candidate;
        int n = 2;
        while (used.contains(unique)) unique = candidate + (n++);
        return unique;
    }

    /**
     * 跨多次 pick 运行持久化的"URL → Page 类名"稳定映射。
     * 关键修复（修复"同一 URL 来回跳转却生成 XxxPage / XxxPage2 两个类"）：
     * 旧实现 urlToClass 是每次 pick 会话的局部变量，跨"停止→再开始"或多次运行会被重建，导致同 URL
     * 在新会话重新派生类名；若既有类名因 pageNames 残留被计入去重，就派生出 XxxPage2。提升为全局持久
     * 映射后，同一 URL 首次派生即记住，之后任何会话/导航都复用，永不再派生重复类。
     */
    private static final java.util.Map<String, String> GLOBAL_URL_TO_CLASS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 语言/地区码路径片段（首段），如 en / zh / zh-HK / en_US，用于 URL 归一化时忽略语言差异。
     *  仅当首段恰好是一个 IETF 风格的语言码时才剥离，尽量降低误伤真实内容路径的概率。 */
    private static final java.util.regex.Pattern LOCALE_SEGMENT =
            java.util.regex.Pattern.compile("(?i)/[a-z]{2}([-_][a-z]{2,4})?(?=/|$)");
    /** 归一化 URL：去 query/hash，剥离首段语言/地区码，并去除末尾斜杠，作为 urlToClass 的稳定键。
     *  去除末尾斜杠可让肉眼"相同"但末尾斜杠有差异的 URL（如 /help 与 /help/）映射到同一页类；
     *  剥离语言码可让同一页面在切换语言后（如 /en/accounts 与 /zh/accounts）归并到同一页类，
     *  避免它们被误判为两个不同页面而派生出 XxxPage / XxxPage2（修复"切换语言后同一页生成 Page2"）。 */
    static String normalizeUrl(String url) {
        String raw = url == null ? "" : url.trim();
        int q = raw.indexOf('?'); if (q >= 0) raw = raw.substring(0, q);
        int h = raw.indexOf('#'); if (h >= 0) raw = raw.substring(0, h);
        // 忽略语言/地区码片段：/en/accounts 与 /zh/accounts 归并为 /accounts，复用同一页类。
        java.util.regex.Matcher lm = LOCALE_SEGMENT.matcher(raw);
        if (lm.find()) {
            raw = raw.substring(0, lm.start()) + raw.substring(lm.end());
            log.debug("[picker][normalize] 剥离语言码，归一化键={}", raw);
        }
        while (raw.length() > 1 && raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        return raw;
    }

    /**
     * 解析某 URL 对应的 Page 类名：优先复用会话级 urlToClass 稳定映射（同一 URL 全程复用同一类名，
     * 避免"离开默认页又回到默认页 URL 时被派生成 LogonPage 等重复类"）。
     * 仅当该 URL 从未出现时才用 pageClassNameFromUrl 派生，并登记进映射；派生时把已有的映射类名
     * 一并计入 used，避免与已分配类重名。
     */
    static String resolvePageClassForUrl(String url, Collection<String> used,
                                                 LinkedHashMap<String, String> urlToClass) {
        String key = normalizeUrl(url);
        String existing = urlToClass.get(key);
        if (existing != null) return existing;
        Set<String> allUsed = new LinkedHashSet<>(used);
        allUsed.addAll(urlToClass.values());
        String cls = pageClassNameFromUrl(url, allUsed);
        urlToClass.put(key, cls);
        // ⭐ 修复 P3：达到上限时批量淘汰约 1/4，避免 Map 在长跑 / 多站点扫描下无限增长。
        //    这里内联淘汰而非复用 RouteUtil.evictOldestQuarter，是为了不新增
        //    web.page → web.route 的反向依赖（见评审 A4 的分层问题）。
        if (GLOBAL_URL_TO_CLASS.size() >= RolePickerConstants.CAP_GLOBAL_URL_TO_CLASS_MAX) {
            int toRemove = Math.max(1, GLOBAL_URL_TO_CLASS.size() / 4);
            int removed = 0;
            java.util.Iterator<String> it = GLOBAL_URL_TO_CLASS.keySet().iterator();
            while (it.hasNext() && removed++ < toRemove) {
                it.next();
                it.remove();
            }
        }
        GLOBAL_URL_TO_CLASS.put(key, cls);
        return cls;
    }

    private static String toClassNameSegment(String seg) {
        if (seg == null || seg.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean upperNext = true;
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(upperNext ? Character.toUpperCase(c) : c);
                upperNext = false;
            } else if (c == '-' || c == '_' || c == '.' || c == ' ' || c == '/') {
                upperNext = true;   // 分隔符 → 下一词首字母大写
            }
            // 其余字符丢弃
        }
        String s = sb.toString().replaceAll("[^\\p{L}\\p{N}_$]", "");
        if (s.isEmpty()) return "";
        if (!Character.isJavaIdentifierStart(s.charAt(0))) s = "P" + s;
        return s;
    }
    // ---- 供 RoleElementPicker 访问全局持久映射的接口 ----
    static Collection<String> values() { return GLOBAL_URL_TO_CLASS.values(); }
    static LinkedHashMap<String, String> snapshot() { return new LinkedHashMap<>(GLOBAL_URL_TO_CLASS); }
    static void put(String key, String cls) { GLOBAL_URL_TO_CLASS.put(key, cls); }
    static void clear() { GLOBAL_URL_TO_CLASS.clear(); }

}