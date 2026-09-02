package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.google.gson.Gson;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.NLSUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * nls 鍙嶅悜鏌ヨ〃缂撳瓨涓庤В鏋愶紙浠?{@link RoleElementPicker} 鎶藉嚭锛屽睘 T5-1 鎷嗗垎绗簩姝ワ級銆?
 * 浠呮壙杞?nls 鍙嶆煡 JSON 鐨勭紦瀛樻€佷笌绾嚱鏁拌В鏋愰€昏緫锛屾棤鍏变韩鍙彉鎬佸壇浣滅敤锛?
 * 鍘熸柟娉曚綋閫愬瓧杩佺Щ锛岀敱 RoleElementPicker 浠ュ畬鍏ㄩ檺瀹氬悕濮旀墭璋冪敤锛岃涓哄畬鍏ㄧ瓑浠枫€?
 */
final class RolePickerNlsCache {

    private static final Logger log = LoggerFactory.getLogger(RolePickerNlsCache.class);
    private static final Gson GSON = new Gson();
    private RolePickerNlsCache() {}
    /**
     * nls 反向查表缓存：同一组 nls 文件在会话内只解析一次，避免每次开始拾取（openPanel / ▶ 启动 /
     * pick）都重读并解析磁盘上的 nls json。key 为排序去重后的文件路径拼接（忽略传参顺序差异）；
     * 带 TTL，文件被更新后到期自动重建。可通过系统属性 {@code rolePicker.nlsCacheTtlMs} 调整有效期（毫秒）。
     */
    private static final Map<String, CachedNls> NLS_REVERSE_CACHE = new ConcurrentHashMap<>();
    private static final long NLS_CACHE_TTL_MS =
            Long.getLong("rolePicker.nlsCacheTtlMs", 5 * 60 * 1000L);
    /** ⭐ 修复 P3：软上限，达到后写入前先清理过期条目（nls 组合数很少，正常远不会触发）。 */
    private static final int NLS_REVERSE_CACHE_SOFT_MAX = 256;

    private static final class CachedNls {
        final String json;
        final long ts;
        CachedNls(String json) { this.json = json; this.ts = System.currentTimeMillis(); }
        boolean fresh() { return System.currentTimeMillis() - ts < NLS_CACHE_TTL_MS; }
    }

    static String buildNlsReverseJson(List<String> nlsFiles) {
        if (nlsFiles == null || nlsFiles.isEmpty()) return "{}";
        // 稳定 key：排序 + 去重 + 去首尾空白，忽略传参顺序差异（["a","b"] 与 ["b","a"] 命中同一缓存）
        String key = nlsFiles.stream()
                .filter(f -> f != null && !f.isBlank())
                .map(String::trim)
                .sorted().distinct()
                .collect(Collectors.joining("\u0000"));
        if (key.isEmpty()) return "{}";
        CachedNls cached = NLS_REVERSE_CACHE.get(key);
        if (cached != null && cached.fresh()) return cached.json;
        String json = buildNlsReverseJsonUncached(nlsFiles);
        // ⭐ 修复 P3：TTL 只在【读取时】判定新鲜度，过期条目永远不会被移除，
        //    于是 Map 在长跑会话中只增不减（每次换一组 nls 文件就多一条）。
        //    写入时顺带清掉已过期条目 —— put 本身是低频操作，清理开销可忽略。
        if (NLS_REVERSE_CACHE.size() >= NLS_REVERSE_CACHE_SOFT_MAX) {
            NLS_REVERSE_CACHE.entrySet().removeIf(e -> e.getValue() == null || !e.getValue().fresh());
        }
        NLS_REVERSE_CACHE.put(key, new CachedNls(json));
        return json;
    }

    /** 单文件便捷重载（向后兼容），走带缓存的 {@link #buildNlsReverseJson(List)} */
    static String buildNlsReverseJson(String nlsFile) {
        return buildNlsReverseJson(List.of(nlsFile));
    }

    /** 实际解析 nls 文件构建反向查表（带缓存，外部一律走 {@link #buildNlsReverseJson}） */
    private static String buildNlsReverseJsonUncached(List<String> nlsFiles) {
        try {
            Map<String, String> exact = new LinkedHashMap<>();
            Map<String, String> templates = new LinkedHashMap<>();
            for (String nlsFile : nlsFiles) {
                if (nlsFile == null || nlsFile.isBlank()) continue;
                Map<String, Map<String, String>> tables = NLSUtils.rawTables(nlsFile);
                for (Map<String, String> table : tables.values()) {
                    if (table == null) continue;
                    for (Map.Entry<String, String> en : table.entrySet()) {
                        // 反查 key 必须基于「页面可见文本」：nls 值里常内嵌 <a>/<strong>/<img> 与
                        // &nbsp;/&copy; 等实体，浏览器渲染后可见文本已无标签，故精确表与模板正则
                        // 一律用 NLSUtils.visibleText / templateRegexSource（二者都会剥 HTML + 解码实体）。
                        // 否则如 tab_security_device("保安編碼器&nbsp; <img...>") 的 key 会带 <img>，
                        // 与浏览器算出的可访问名 "保安編碼器" 对不上，反查失败退化为字面值。
                        String visible = NLSUtils.visibleText(en.getValue());
                        if (visible.isEmpty()) continue;
                        if (en.getValue().contains("{{")) {
                            // 含模板变量：无法精确反查，改用正则源（跨语言匹配替换后的可见文本）
                            String src = NLSUtils.templateRegexSource(en.getValue());
                            if (!src.isEmpty()) templates.putIfAbsent(src, en.getKey());
                        } else {
                            exact.putIfAbsent(visible, en.getKey());
                        }
                    }
                }
            }
            if (exact.isEmpty() && templates.isEmpty()) {
                log.warn("[picker] nls 文件无可用条目，无法反查 key：{}", nlsFiles);
                return "{}";
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("exact", exact);
            out.put("templates", templates.entrySet().stream()
                    .map(e -> new String[]{e.getKey(), e.getValue()})
                    .toArray(String[][]::new));
            log.info("[picker] 已加载 nls 反向查表（精确 {} 条 / 模板 {} 条），拾取时将自动匹配 key：{}",
                    exact.size(), templates.size(), nlsFiles);
            return GSON.toJson(out);
        } catch (Exception e) {
            log.warn("[picker] 加载 nls 文件失败，拾取时无法反查 key，将回退到 name 派生 slug：{}", nlsFiles, e);
            return "{}";
        }
    }    static void clear() {
        NLS_REVERSE_CACHE.clear();
    }

}