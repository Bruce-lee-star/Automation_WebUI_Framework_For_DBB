package com.hsbc.cmb.hk.dbb.automation.framework.route.binding;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@link RoutePatterns} 归一化单测 —— 语义对齐老仓 v1 {@code RouteEngine.normalizePattern}。
 *
 * <p>2026-09-27 E2E 实测根因回归：相对端点（{@code profile/list}）必须归一化为
 * 前导双星加斜杠、尾部双星的形式，否则 Playwright 原生 glob 按完整 URL 精确匹配、
 * handler 永不触发。</p>
 */
public class RoutePatternsTest {

    @Test
    public void nullIsPassthrough() {
        assertNull(RoutePatterns.normalize(null));
    }

    @Test
    public void relativeEndpointGetsPrefixAndSuffixWildcards() {
        assertEquals((Object) "**/profile/list**", (Object) RoutePatterns.normalize("profile/list"));
        assertEquals((Object) "**/auth/assert**", (Object) RoutePatterns.normalize("auth/assert"));
    }

    @Test
    public void slashPrefixedEndpointGetsPrefixWildcard() {
        assertEquals((Object) "**/error/profile-error.jsp**", (Object) RoutePatterns.normalize("/error/profile-error.jsp"));
    }

    @Test
    public void catchAllStartsWithSlash() {
        // "/**" 不以 "**" 开头（首字符为 '/'）→ 前缀 "**" → "**/**"；尾缀已是 "**" 保持
        assertEquals((Object) "**/**", (Object) RoutePatterns.normalize("/**"));
    }

    @Test
    public void alreadyWildcardedPatternIsPreserved() {
        assertEquals((Object) "**/api/**", (Object) RoutePatterns.normalize("**/api/**"));
        assertEquals((Object) "**/api/users/**", (Object) RoutePatterns.normalize("**/api/users/**"));
    }

    @Test
    public void trailingSingleStarNormalizedToDoubleStar() {
        // 尾部单个 '*' 被替换为 '**'（TRAILING_WILDCARDS 语义）
        assertEquals((Object) "**/api/list**", (Object) RoutePatterns.normalize("/api/list*"));
    }

    @Test
    public void absoluteUrlGetsPrefixAndSuffixWildcards() {
        assertEquals((Object) "**/https://host/profile/list**", (Object) RoutePatterns.normalize("https://host/profile/list"));
    }
}
