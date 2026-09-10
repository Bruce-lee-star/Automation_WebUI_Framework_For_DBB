package com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture;

import com.hsbc.cmb.hk.dbb.automation.framework.route.util.RouteUtil;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Ant 风格 glob → 正则的编译缓存（原 {@code ApiCaptureContext} 的静态域，Phase 5 抽离）。
 * <p>零行为变更：缓存上限与伪 LRU 淘汰策略与原实现一致。
 *
 * @apiNote framework-internal：框架内部类型，非公开 API。跨子包 public 可见性仅为分层迁移需要，外部不得依赖。
 */
public final class RoutePatternCache {
    private static final int MAX_PATTERN_CACHE_SIZE = 200;
    private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    private RoutePatternCache() {
    }

    static Pattern antGlobToRegex(String glob) {
        Pattern cached = PATTERN_CACHE.get(glob);
        if (cached != null) return cached;

        String regex = antGlobToRegexString(glob);
        Pattern compiled = Pattern.compile(regex);

        //  #7 伪 LRU：超限时移除 ~25% 条目（避免全量清空导致命中率归零）
        if (PATTERN_CACHE.size() >= MAX_PATTERN_CACHE_SIZE) {
            RouteUtil.evictOldestQuarter(PATTERN_CACHE);
        }
        PATTERN_CACHE.put(glob, compiled);
        return compiled;
    }

    private static String antGlobToRegexString(String glob) {
        StringBuilder sb = new StringBuilder();
        sb.append('^');
        int len = glob.length();
        int i = 0;
        while (i < len) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < len && glob.charAt(i + 1) == '*') {
                sb.append(".*");
                i += 2;
            } else if (c == '*') {
                sb.append("[^/]*");
                i++;
            } else {
                if (c == '.' || c == '+' || c == '?' || c == '(' || c == ')'
                        || c == '[' || c == ']' || c == '{' || c == '}'
                        || c == '\\' || c == '^' || c == '$' || c == '|') {
                    sb.append('\\');
                }
                sb.append(c);
                i++;
            }
        }
        sb.append('$');
        return sb.toString();
    }
}
